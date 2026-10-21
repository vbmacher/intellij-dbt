package com.github.vbmacher.intellij.dbt.lineage

import com.github.vbmacher.intellij.dbt.navigation.{DbtNavigation, DbtNavigationContext, DbtNavigationReference, DbtReferenceProvider, DbtSqlAnalysis, DbtSqlColumn}
import com.github.vbmacher.intellij.dbt.project._
import com.github.vbmacher.intellij.dbt.syntax.{DbtSyntax, DbtYaml}
import com.google.gson.JsonElement
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.{Key, TextRange}
import com.intellij.psi.{PsiElement, PsiFile, PsiRecursiveElementWalkingVisitor}
import com.intellij.psi.search.{PsiSearchScopeUtil, SearchScope}
import org.jetbrains.yaml.psi._

import java.nio.file.{FileSystems, Paths}
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters._
import scala.util.Try

/** `from` uses `to` at `reference`. */
private[dbt] final case class DbtDependency(from: DbtSymbol, to: DbtSymbol, reference: DbtNavigationReference) {
  def ambiguous: Boolean = reference.candidates.size > 1
}

/** Port of dependencies recorded outside of project sources; implementations are registered in `plugin.xml`. */
private[dbt] trait DbtDependencySource {
  /** Dependencies whose evidence is within the scope, if any. */
  def dependencies(workspace: DbtWorkspace, scope: Option[SearchScope]): Vector[DbtDependency]
}

private[dbt] object DbtDependencySource {
  val EP: ExtensionPointName[DbtDependencySource] = ExtensionPointName.create("com.github.vbmacher.intellij-dbt.dependencySource")
}

/** Dependencies between dbt declarations, statically resolved from sources and recorded by dependency sources. */
private[dbt] object DbtLineage {
  private val EdgeCache = Key.create[Vector[DbtDependency]]("dbt.lineage.edges")
  private val EndTags = Map("macro" -> "endmacro", "generic_test" -> "endtest", "materialization" -> "endmaterialization", "snapshot" -> "endsnapshot")
  private val Untracked = Set("documentation", "local", "parameter", "sql_alias", "sql_projection", "cte")
  private val ParentsOperator = "^[0-9]*\\+".r
  private val ChildrenOperator = "\\+[0-9]*$".r

  def edges(w: DbtWorkspace, scope: Option[SearchScope] = None): Vector[DbtDependency] = {
    val cached = if (scope.isEmpty) Option(w.getUserData(EdgeCache)) else None
    cached.getOrElse {
      val result = w.files.filter(file => scope.forall(PsiSearchScopeUtil.isInScope(_, file))).flatMap(sourceEdges(w, _)).distinct ++
        DbtDependencySource.EP.getExtensionList.asScala.toVector.flatMap(_.dependencies(w, scope))
      if (scope.isEmpty) w.putUserData(EdgeCache, result)
      result
    }
  }

  /** Edges from the innermost declaration enclosing each reference of a file. */
  private def sourceEdges(w: DbtWorkspace, file: PsiFile): Vector[DbtDependency] = {
    ProgressManager.checkCanceled()
    val declarations = w.symbols.filter(s => s.file == file && (s.isCode || s.isProperty || EndTags.contains(s.kind)))
    val blocks = DbtSyntax.inFile(file)
    DbtNavigation.inFile(file, includeSelections = false).flatMap { ref =>
      val range = ref.getRangeInElement
      val containing = declarations.filter(s => s.mapping.exists(m => m.getContainingFile == file && m.getTextRange.contains(range)))
      val blockOwner = declarations.filter(s => EndTags.contains(s.kind) && s.attributes.contains("block-start") &&
        DbtSyntax.encloses(blocks, s.range.getStartOffset, EndTags(s.kind), range)).sortBy(_.range.getStartOffset).lastOption.toVector
      val owner = if (containing.nonEmpty) containing.sortBy(_.mapping.get.getTextLength).take(1)
      else if (blockOwner.nonEmpty) blockOwner else declarations.filter(_.isCode).take(1)
      for (from <- owner; target <- ref.candidates.collect { case s: DbtSymbolElement => s.symbol }
           if !Untracked(target.kind) && from.id != target.id && !(from.isProperty && target.isCode && from.sameResource(target)))
      yield DbtDependency(from, target, ref)
    }
  }

  def traverse(edges: Vector[DbtDependency], start: DbtSymbol, downstream: Boolean, depth: Int): Vector[DbtSymbol] = {
    val seen = mutable.Set(start.id)
    var frontier = Vector(start)
    val result = mutable.ArrayBuffer.empty[DbtSymbol]
    var remaining = depth
    while (frontier.nonEmpty && remaining > 0) {
      ProgressManager.checkCanceled()
      val next = frontier.flatMap(s => edges.filter(e => (if (downstream) e.to.id else e.from.id) == s.id)
        .map(e => if (downstream) e.from else e.to)).filter(s => seen.add(s.id))
      result ++= next
      frontier = next
      remaining -= 1
    }
    result.toVector
  }

  /** The `n+` parents and `+n` children operators of a selection expression. */
  private[lineage] def operators(expression: String): (Option[String], Option[String]) =
    (ParentsOperator.findFirstIn(expression), ChildrenOperator.findFirstIn(expression))

  /** Ancestors and descendants of the seeds selected by graph operators, within their depths. */
  private[lineage] def neighbours(w: DbtWorkspace, seeds: Vector[DbtSymbol], parents: Option[String], children: Option[String]): Vector[DbtSymbol] =
    if (parents.isEmpty && children.isEmpty) Vector.empty
    else {
      val all = edges(w)
      parents.toVector.flatMap(p => seeds.flatMap(traverse(all, _, downstream = false, p.dropRight(1).toIntOption.getOrElse(Int.MaxValue)))) ++
        children.toVector.flatMap(p => seeds.flatMap(traverse(all, _, downstream = true, p.drop(1).toIntOption.getOrElse(Int.MaxValue))))
    }
}

private[dbt] final case class DbtColumnEdge(resource: DbtSymbol, column: String, input: DbtSymbol, direct: Boolean,
  expression: String, complete: Boolean, kind: String = "value")

private[dbt] object DbtColumnLineage {
  private val Cache = Key.create[Vector[DbtColumnEdge]]("dbt.column.lineage")
  def edges(w: DbtWorkspace): Vector[DbtColumnEdge] = Option(w.getUserData(Cache)).getOrElse {
    val result = w.symbols.filter(s => s.kind == "model" && s.isCode && s.isEnabled).flatMap { resource =>
      ProgressManager.checkCanceled()
      DbtSqlAnalysis.get(w, resource.file).toVector.flatMap { analysis =>
        def inputs(column: DbtSqlColumn, seen: Set[Int]): Vector[(DbtSymbol, Boolean)] = column.inputs.flatMap { input =>
          val local = if (input.kind == "sql_projection" && input.file == resource.file)
            analysis.intermediates.find(c => c.range == input.range && c.name == input.name) else None
          local match {
            case Some(parent) if !seen(parent.range.getStartOffset) => inputs(parent, seen + parent.range.getStartOffset)
              .map { case (s, direct) => s -> (direct && column.direct && column.complete) }
            case Some(_) => Vector.empty
            case None => Vector(input -> (column.direct && column.complete))
          }
        }
        analysis.columns.flatMap(c => inputs(c, Set.empty).map { case (input, direct) =>
          DbtColumnEdge(resource, c.name, input, direct, c.expression, c.complete)
        } ++ c.contextInputs.map { case (input, kind) => DbtColumnEdge(resource, c.name, input, direct = false,
          kind + " dependency", c.complete, kind) })
      }
    }
    w.putUserData(Cache, result); result
  }

  private def of(w: DbtWorkspace, resource: DbtSymbol)(e: DbtColumnEdge): Boolean =
    e.resource.sameResource(resource) && w.owner(e.resource) == w.owner(resource)

  def upstream(w: DbtWorkspace, resource: DbtSymbol, column: String): Vector[DbtColumnEdge] =
    edges(w).filter(e => of(w, resource)(e) && e.column.equalsIgnoreCase(column))

  def inherited(w: DbtWorkspace, resource: DbtSymbol): Vector[(String, String, DbtSymbol)] = {
    val existing = DbtResourceData.get(w, resource).columns.map(c => c.name -> c.description).toMap
    edges(w).filter(e => e.kind == "value" && of(w, resource)(e)).groupBy(_.column).toVector
      .sortBy(_._1).flatMap { case (column, candidates) =>
        val inputs = candidates.map(_.input).distinctBy(_.id)
        if (existing.getOrElse(column, "").nonEmpty || !candidates.forall(e => e.direct && e.complete) || inputs.size != 1) Vector.empty
        else inputs.headOption.toVector.flatMap { input =>
          input.attributes.get("description").filter(s => s.nonEmpty && !s.contains("{{")).map(text => (column, text, input))
        }
      }
  }
}

private[dbt] final case class DbtSelection(resources: Vector[DbtSymbol], problems: Vector[String]) {
  def complete: Boolean = problems.isEmpty
}

/** Local selection preview. Unsupported/runtime selectors remain explicit problems, never broad matches. */
private[dbt] object DbtSelection {
  private val Methods = DbtSelectors.MethodKeys + "selector"

  def selector(w: DbtWorkspace, resource: DbtSymbol): String = resource.kind match {
    case "source_table" => "source:" + resource.namespace + "." + resource.owner + "." + resource.name
    case "exposure" => "exposure:" + resource.namespace + "." + resource.name
    case "unit_test" => "fqn:" + (Vector(resource.namespace) ++ DbtSelectors.folders(w, resource) ++
      resource.mapping.flatMap(DbtYaml.string(_, "model")).toVector ++ Vector(resource.name)).mkString(".")
    case _ => "fqn:" + DbtSelectors.fqn(w, resource).mkString(".")
  }

  /** A selection method with its graph operators, from the fields of a structured selector definition. */
  private def methodTerm(field: String => Option[String]): Option[String] = for (method <- field("method"); value <- field("value")) yield
    (if (field("childrens_parents").contains("true")) "@" else "") +
      (if (field("parents").contains("true")) field("parents_depth").getOrElse("") + "+" else "") + method + ":" + value +
      (if (field("children").contains("true")) "+" + field("children_depth").getOrElse("") else "")

  def resolve(w: DbtWorkspace, root: DbtRoot, expression: String, exclude: String = ""): DbtSelection = {
    val problems = ArrayBuffer.empty[String]
    def identity(s: DbtSymbol): String = DbtResourceIdentity.key(w, s)
    def union(xs: Vector[Vector[DbtSymbol]]): Vector[DbtSymbol] = xs.flatten.distinctBy(identity)
    def intersect(xs: Vector[Vector[DbtSymbol]]): Vector[DbtSymbol] = xs.headOption.getOrElse(Vector.empty)
      .filter(s => xs.drop(1).forall(_.exists(t => identity(t) == identity(s))))
    def unsupported(message: String = "Unsupported named selector definition."): Vector[DbtSymbol] = { problems += message; Vector.empty }
    lazy val manifestSelectors = root.setting("manifest").toVector.flatMap(path =>
      DbtManifestReader.get(w.project).read(root, path, DbtManifestPolicy.Navigation).toOption.toVector.flatMap(_.in("selectors")))
    def json(value: JsonElement, visiting: Set[String]): Vector[DbtSymbol] = {
      if (value.isJsonPrimitive && value.getAsJsonPrimitive.isString) terms(value.getAsString, visiting)
      else if (value.isJsonArray) union(value.getAsJsonArray.asScala.toVector.map(json(_, visiting)))
      else if (value.isJsonObject) {
        val mapping = value.getAsJsonObject
        def get(key: String) = Option(mapping.get(key)).filterNot(_.isJsonNull)
        val combined = get("union").orElse(get("intersection"))
        val selected = combined match {
          case Some(array) if array.isJsonArray =>
            val includes = array.getAsJsonArray.asScala.toVector.filterNot(e => e.isJsonObject && e.getAsJsonObject.has("exclude")).map(json(_, visiting))
            if (mapping.has("intersection")) intersect(includes) else union(includes)
          case Some(_) => unsupported()
          case None if mapping.has("definition") => json(mapping.get("definition"), visiting)
          case None => methodTerm(get(_).filter(_.isJsonPrimitive).map(_.getAsString)).fold(unsupported())(term(_, visiting))
        }
        val excludes = combined.toVector.filter(_.isJsonArray).flatMap(_.getAsJsonArray.asScala).filter(_.isJsonObject)
          .flatMap(e => Option(e.getAsJsonObject.get("exclude"))) ++ get("exclude")
        val removed = excludes.flatMap(json(_, visiting)).map(identity).toSet
        selected.filterNot(s => removed(identity(s)))
      } else unsupported()
    }
    def yaml(value: YAMLValue, visiting: Set[String]): Vector[DbtSymbol] = value match {
      case scalar: YAMLScalar => terms(scalar.getTextValue, visiting)
      case sequence: YAMLSequence => union(sequence.getItems.asScala.toVector.flatMap(i => Option(i.getValue).map(yaml(_, visiting))))
      case mapping: YAMLMapping =>
        val include = DbtYaml.value(mapping, "union").orElse(DbtYaml.value(mapping, "intersection"))
        val selected = include match {
          case Some(sequence: YAMLSequence) =>
            val children = sequence.getItems.asScala.toVector.flatMap(i => Option(i.getValue)).filterNot {
              case m: YAMLMapping => DbtYaml.value(m, "exclude").nonEmpty
              case _ => false
            }.map(yaml(_, visiting))
            if (DbtYaml.value(mapping, "intersection").nonEmpty) intersect(children) else union(children)
          case Some(other) => yaml(other, visiting)
          case None => methodTerm(DbtYaml.string(mapping, _)).map(term(_, visiting)).getOrElse {
            val shorthand = mapping.getKeyValues.asScala.toVector.filter(k => Methods(k.getKeyText))
            if (shorthand.size == 1) term(shorthand.head.getKeyText + ":" + Option(shorthand.head.getValue).map(_.getText).getOrElse(""), visiting)
            else unsupported()
          }
        }
        val excludes = include.toVector.collect { case s: YAMLSequence => s }.flatMap(_.getItems.asScala).flatMap(i => Option(i.getValue))
          .collect { case m: YAMLMapping => m }.flatMap(m => DbtYaml.value(m, "exclude")).flatMap(yaml(_, visiting)) ++
          DbtYaml.value(mapping, "exclude").toVector.flatMap(yaml(_, visiting))
        val removed = excludes.map(identity).toSet
        selected.filterNot(s => removed(identity(s)))
      case _ => unsupported("Unsupported selector value.")
    }
    def term(raw: String, visiting: Set[String]): Vector[DbtSymbol] = {
      ProgressManager.checkCanceled()
      val core = raw.replaceFirst("^[0-9]*\\+", "").replaceFirst("\\+[0-9]*$", "")
      val method = if (core.contains(":")) core.takeWhile(_ != ':') else "fqn"
      if (raw.startsWith("@") && raw.length > 1 && !raw.startsWith("@@")) {
        val seeds = term(raw.drop(1), visiting)
        val edges = DbtLineage.edges(w)
        val children = union(Vector(seeds, seeds.flatMap(s => DbtLineage.traverse(edges, s, downstream = true, Int.MaxValue))))
        union(Vector(children, children.flatMap(s => DbtLineage.traverse(edges, s, downstream = false, Int.MaxValue))))
      } else if (raw.isEmpty || core.isEmpty || !Methods(method) || !raw.matches("([0-9]*\\+)?[^+,\\s]+(\\+[0-9]*)?") || raw.contains("{{") ||
        raw.contains("@")) unsupported(s"Selector '$raw' requires explicit dbt ls resolution.")
      else if (method == "selector") {
        val name = core.stripPrefix("selector:")
        if (visiting(name)) unsupported(s"Selector cycle: $name")
        else {
          val selectorFile = w.rootFile(root, "selectors.yml")
          val definitions = selectorFile.flatMap(DbtYaml.mapping).toVector
            .flatMap(DbtYaml.items(_, "selectors")).filter(m => DbtYaml.string(m, "name").contains(name))
          val recorded = if (selectorFile.isEmpty) manifestSelectors.filter(_.id == name) else Vector.empty
          if (definitions.size + recorded.size != 1) unsupported(s"Selector '$name' is missing or ambiguous.")
          else {
            val selected = definitions.headOption.toVector.flatMap(m => DbtYaml.value(m, "definition").toVector.flatMap(yaml(_, visiting + name))) ++
              recorded.flatMap(r => json(r.data, visiting + name))
            val (parents, children) = DbtLineage.operators(raw)
            union(Vector(selected, DbtLineage.neighbours(w, selected, parents, children)))
          }
        }
      } else {
        val name = core.stripPrefix("fqn:")
        val preferLocal = method == "fqn" && DbtSyntax.isIdentifier(name) &&
          w.declarations(DbtSymbol.RefKinds, name, root).exists(_.namespace == root.name)
        val qualified = if (preferLocal) raw.replace(core, (if (core.startsWith("fqn:")) "fqn:" else "") + root.name + "." + name) else raw
        DbtSelectors.select(w, root, qualified, includeGraph = true).collect { case e: DbtSymbolElement => e.symbol }
      }
    }
    def terms(text: String, visiting: Set[String]): Vector[DbtSymbol] = union(text.trim.split("\\s+").toVector.filter(_.nonEmpty)
      .map(part => intersect(part.split(",", -1).toVector.map(term(_, visiting)))))
    val selected = terms(if (expression.trim.isEmpty) "*" else expression, Set.empty)
    val removed = if (exclude.trim.isEmpty) Set.empty[String] else terms(exclude, Set.empty).map(identity).toSet
    DbtSelection(selected.filter(DbtSelectors.selectable).filterNot(s => removed(identity(s))).distinctBy(identity), problems.toVector.distinct)
  }
}

/** Node selection expressions of `selectors.yml` and the configured selection files. */
private[lineage] final class DbtSelectorNavigation extends DbtReferenceProvider {
  override def references(context: DbtNavigationContext): Vector[DbtNavigationReference] =
    DbtSelectors.references(context.file, context.workspace, context.root, context.includeSelections)
}

/** dbt node selection, including graph operators. */
private[dbt] object DbtSelectors {
  private val SelectableKinds = Set("model", "seed", "snapshot", "source_table", "test", "generic_test", "analysis", "exposure", "metric",
    "saved_query", "function", "check", "group", "unit_test", "semantic_model")
  private[lineage] val MethodKeys = Set("fqn", "path", "package", "source", "tag", "group", "exposure", "metric", "saved_query", "test",
    "function", "check", "resource_type", "type", "file", "access", "config.materialized", "config.tags")

  def selectable(symbol: DbtSymbol): Boolean = SelectableKinds(symbol.kind) && symbol.isEnabled

  private[lineage] def folders(w: DbtWorkspace, s: DbtSymbol): Vector[String] =
    w.owner(s).flatMap(_.resourceFolders(s.file.getVirtualFile)).toVector.flatMap(_._2)

  /** The fully qualified name of a resource: package, folders, name and version. */
  private[lineage] def fqn(w: DbtWorkspace, s: DbtSymbol): Vector[String] =
    Vector(s.namespace) ++ folders(w, s) ++ Vector(s.name) ++ s.version.map("v" + _)

  private def glob(pattern: String, value: String): Boolean =
    Try(FileSystems.getDefault.getPathMatcher("glob:" + pattern).matches(Paths.get(value))).getOrElse(false)

  def select(w: DbtWorkspace, root: DbtRoot, expression: String, includeGraph: Boolean): Vector[PsiElement] = {
    val (prefix, suffix) = DbtLineage.operators(expression)
    val core = expression.drop(prefix.map(_.length).getOrElse(0)).dropRight(suffix.map(_.length).getOrElse(0))
    val split = core.split(":", 2)
    val method = if (split.length == 2) split.head else if (core.contains("/") || core.matches(".*\\.(sql|csv|py)")) "path" else "fqn"
    val value = split.last
    if (method == "selector") return if (root.supports(12)) w.symbols.filter(s => s.kind == "selector" &&
      s.name == value && w.root(s.file).contains(root)).map(_.element) else Vector.empty
    val candidates = w.symbols.filter(s => selectable(s) &&
      w.declarations(Set(s.kind), s.name, root, Some(s.namespace)).contains(s))
    val selected = candidates.filter { s => method match {
      case "fqn" =>
        val relative = w.root(s.file).flatMap(_.relative(s.file.getVirtualFile))
          .map(_.stripSuffix("." + s.file.getVirtualFile.getExtension).replace('/', '.')).getOrElse("")
        val parts = fqn(w, s)
        val prefixes = parts.indices.map(i => parts.take(i + 1).mkString(".")) ++ parts.drop(1).indices.map(i => parts.drop(1).take(i + 1).mkString("."))
        glob(value, parts.mkString(".")) || glob(value, s.name) || prefixes.exists(glob(value, _)) || glob(value, s.namespace + "." + s.name) ||
          glob(value, s.namespace + "." + relative.stripPrefix("models."))
      case "path" => w.root(s.file).flatMap(_.relative(s.file.getVirtualFile)).exists(p => glob(value, p) || p.startsWith(value.stripSuffix("/") + "/"))
      case "file" => glob(value, s.file.getName.replaceFirst("\\.(sql|py|csv)$", ""))
      case "package" => glob(value, s.namespace)
      case "tag" | "config.tags" => s.attributes.get("tags").exists(_.split(",").exists(glob(value, _)))
      case "access" => s.attributes.get("access").exists(glob(value, _))
      case "config.materialized" => s.attributes.get("materialized").exists(glob(value, _))
      case "source" => s.kind == "source_table" && (glob(value, s.owner + "." + s.name) || glob(value, s.owner) ||
        glob(value, s.namespace + "." + s.owner + "." + s.name))
      case "resource_type" | "type" => glob(value, if (s.kind == "source_table") "source" else s.kind) || s.kind == value
      case "group" => s.kind == "group" && glob(value, s.name) || s.attributes.get("group").exists(glob(value, _))
      case "exposure" | "metric" | "saved_query" => s.kind == method && (glob(value, s.name) || glob(value, s.namespace + "." + s.name))
      case "function" | "check" | "test" => s.kind == method && glob(value, s.name)
      case _ => false
    }}.distinct
    val expanded = selected ++ (if (includeGraph) DbtLineage.neighbours(w, selected, prefix, suffix) else Vector.empty)
    expanded.filter(selectable).map(_.element).distinct
  }

  private[lineage] def references(file: PsiFile, w: DbtWorkspace, root: DbtRoot, includeGraph: Boolean): Vector[DbtNavigationReference] = {
    val selectionFile = root.isSelectionFile(file)
    if (file.getName != "selectors.yml" && !selectionFile) return Vector.empty
    val selectorFile = if (file.getName == "selectors.yml") Some(file) else root.psiFile("selectors.yml")
    val result = ArrayBuffer.empty[DbtNavigationReference]
    file.accept(new PsiRecursiveElementWalkingVisitor {
      override def visitElement(e: PsiElement): Unit = {
        e match {
          case scalar: YAMLScalar =>
            val ancestors = DbtYaml.ancestors(scalar)
            val key = DbtYaml.key(scalar)
            val method = DbtYaml.parentMapping(scalar).flatMap(DbtYaml.string(_, "method"))
            val selectionContext = (if (selectionFile) ancestors.exists(Set("select", "exclude", "selector"))
              else ancestors.contains("definition")) && !ancestors.exists(Set("vars", "env", "description"))
            val named = selectionContext && (key == "selector" || (key == "value" && method.contains("selector")))
            val expression = if (!selectionContext) None else if (selectionFile && Set("select", "exclude").contains(key)) Some(scalar.getTextValue)
              else if (key == "value") method.map(_ + ":" + scalar.getTextValue)
              else if (MethodKeys(key)) Some(key + ":" + scalar.getTextValue)
              else if (key == "definition" || scalar.getParent.isInstanceOf[YAMLSequenceItem]) Some(scalar.getTextValue) else None
            lazy val targets = if (named) w.symbols.filter(s => s.kind == "selector" && selectorFile.contains(s.file) && s.name == scalar.getTextValue)
                .map(_.element)
              else expression.toVector.flatMap(select(w, root, _, includeGraph))
            if (selectionContext && expression.contains(scalar.getTextValue) && !named) {
              DbtYaml.decoded(scalar).foreach { case (text, offset) => "[^\\s,]+".r.findAllMatchIn(text).foreach { term =>
                val range = new TextRange(offset(term.start), offset(term.end))
                result += new DbtNavigationReference(file, range, select(w, root, term.matched, includeGraph), term.matched)
              }}
            } else if (named || expression.nonEmpty) result += new DbtNavigationReference(file, scalar.getTextRange, targets, scalar.getTextValue)
          case _ => ()
        }
        super.visitElement(e)
      }
    })
    result.toVector
  }
}
