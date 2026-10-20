package com.github.vbmacher.intellij.dbt.lineage

import com.github.vbmacher.intellij.dbt.project.DbtResourceIdentity
import com.github.vbmacher.intellij.dbt.project.{DbtManifestPolicy, DbtManifestReader, DbtRoot, DbtSymbol, DbtSymbolElement, DbtWorkspace}
import com.github.vbmacher.intellij.dbt.syntax.DbtYaml
import com.intellij.openapi.progress.ProgressManager
import org.jetbrains.yaml.psi.{YAMLMapping, YAMLScalar, YAMLSequence, YAMLValue}
import scala.jdk.CollectionConverters._

private[dbt] final case class DbtSelection(resources: Vector[DbtSymbol], problems: Vector[String]) {
  def complete: Boolean = problems.isEmpty
}

/** Local selection preview. Unsupported/runtime selectors remain explicit problems, never broad matches. */
private[dbt] object DbtSelection {
  def selector(w: DbtWorkspace, resource: DbtSymbol): String = {
    val folders = w.owner(resource).flatMap(_.resourceFolders(resource.file.getVirtualFile)).toVector.flatMap(_._2)
    resource.kind match {
      case "source_table" => "source:" + resource.namespace + "." + resource.owner + "." + resource.name
      case "exposure" => "exposure:" + resource.namespace + "." + resource.name
      case "unit_test" => "fqn:" + (Vector(resource.namespace) ++ folders ++
        resource.mapping.flatMap(DbtYaml.string(_, "model")).toVector ++ Vector(resource.name)).mkString(".")
      case _ => "fqn:" + (Vector(resource.namespace) ++ folders ++ Vector(resource.name) ++ resource.version.map("v" + _)).mkString(".")
    }
  }
  private val Methods = Set("fqn", "path", "package", "source", "tag", "group", "exposure", "metric", "saved_query", "test",
    "function", "check", "resource_type", "selector", "file", "type", "access", "config.materialized", "config.tags")
  def resolve(w: DbtWorkspace, root: DbtRoot, expression: String, exclude: String = ""): DbtSelection = {
    val problems = scala.collection.mutable.ArrayBuffer.empty[String]
    def identity(s: DbtSymbol): String = DbtResourceIdentity.key(w, s)
    def union(xs: Vector[Vector[DbtSymbol]]): Vector[DbtSymbol] = xs.flatten.distinctBy(identity)
    def intersect(xs: Vector[Vector[DbtSymbol]]): Vector[DbtSymbol] = xs.headOption.getOrElse(Vector.empty)
      .filter(s => xs.drop(1).forall(_.exists(t => identity(t) == identity(s))))
    lazy val manifestSelectors = root.setting("manifest").toVector.flatMap(path =>
      DbtManifestReader.get(w.project).read(root, path, DbtManifestPolicy.Navigation).toOption.toVector.flatMap(_.in("selectors")))
    def json(value: com.google.gson.JsonElement, visiting: Set[String]): Vector[DbtSymbol] = {
      if (value.isJsonPrimitive && value.getAsJsonPrimitive.isString) terms(value.getAsString, visiting)
      else if (value.isJsonArray) union(value.getAsJsonArray.asScala.toVector.map(json(_, visiting)))
      else if (value.isJsonObject) {
        val mapping = value.getAsJsonObject
        def get(key: String) = Option(mapping.get(key)).filterNot(_.isJsonNull)
        def text(key: String) = get(key).filter(_.isJsonPrimitive).map(_.getAsString)
        val combined = get("union").orElse(get("intersection"))
        val selected = combined match {
          case Some(array) if array.isJsonArray =>
            val includes = array.getAsJsonArray.asScala.toVector.filterNot(e => e.isJsonObject && e.getAsJsonObject.has("exclude")).map(json(_, visiting))
            if (mapping.has("intersection")) intersect(includes) else union(includes)
          case Some(_) => problems += "Unsupported named selector definition."; Vector.empty
          case None if mapping.has("definition") => json(mapping.get("definition"), visiting)
          case None if text("method").nonEmpty && text("value").nonEmpty =>
            term((if (text("childrens_parents").contains("true")) "@" else "") +
              (if (text("parents").contains("true")) text("parents_depth").getOrElse("") + "+" else "") +
              text("method").get + ":" + text("value").get +
              (if (text("children").contains("true")) "+" + text("children_depth").getOrElse("") else ""), visiting)
          case _ => problems += "Unsupported named selector definition."; Vector.empty
        }
        val excludes = combined.toVector.filter(_.isJsonArray).flatMap(_.getAsJsonArray.asScala).filter(_.isJsonObject)
          .flatMap(e => Option(e.getAsJsonObject.get("exclude"))) ++ get("exclude")
        val removed = excludes.flatMap(json(_, visiting)).map(identity).toSet
        selected.filterNot(s => removed(identity(s)))
      } else { problems += "Unsupported named selector definition."; Vector.empty }
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
          case None =>
            val method = DbtYaml.string(mapping, "method")
            val value = DbtYaml.string(mapping, "value")
            if (method.nonEmpty && value.nonEmpty) {
              val parents = DbtYaml.string(mapping, "parents").contains("true")
              val children = DbtYaml.string(mapping, "children").contains("true")
              term((if (DbtYaml.string(mapping, "childrens_parents").contains("true")) "@" else "") +
                (if (parents) DbtYaml.string(mapping, "parents_depth").getOrElse("") + "+" else "") +
                method.get + ":" + value.get + (if (children) "+" + DbtYaml.string(mapping, "children_depth").getOrElse("") else ""), visiting)
            } else {
              val shorthand = mapping.getKeyValues.asScala.toVector.filter(k => Methods(k.getKeyText))
              if (shorthand.size == 1) term(shorthand.head.getKeyText + ":" + Option(shorthand.head.getValue).map(_.getText).getOrElse(""), visiting)
              else { problems += "Unsupported named selector definition."; Vector.empty }
            }
        }
        val excludes = include.toVector.collect { case s: YAMLSequence => s }.flatMap(_.getItems.asScala).flatMap(i => Option(i.getValue))
          .collect { case m: YAMLMapping => m }.flatMap(m => DbtYaml.value(m, "exclude")).flatMap(yaml(_, visiting)) ++
          DbtYaml.value(mapping, "exclude").toVector.flatMap(yaml(_, visiting))
        val removed = excludes.map(identity).toSet
        selected.filterNot(s => removed(identity(s)))
      case _ => problems += "Unsupported selector value."; Vector.empty
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
      } else if (raw.isEmpty || core.isEmpty || !Methods(method) || !raw.matches("([0-9]*\\+)?[^+,\\s]+(\\+[0-9]*)?") || raw.contains("{{") || raw.contains("@")) {
        problems += s"Selector '$raw' requires explicit dbt ls resolution."; Vector.empty
      } else if (method == "selector") {
        val name = core.stripPrefix("selector:")
        if (visiting(name)) { problems += s"Selector cycle: $name"; Vector.empty }
        else {
          val selectorFile = w.rootFile(root, "selectors.yml")
          val definitions = selectorFile.flatMap(DbtYaml.mapping).toVector
            .flatMap(DbtYaml.items(_, "selectors")).filter(m => DbtYaml.string(m, "name").contains(name))
          val recorded = if (selectorFile.isEmpty) manifestSelectors.filter(_.id == name) else Vector.empty
          if (definitions.size + recorded.size != 1) { problems += s"Selector '$name' is missing or ambiguous."; Vector.empty }
          else {
            val selected = definitions.headOption.toVector.flatMap(m => DbtYaml.value(m, "definition").toVector.flatMap(yaml(_, visiting + name))) ++
              recorded.flatMap(r => json(r.data, visiting + name))
            val prefix = "^[0-9]*\\+".r.findFirstIn(raw)
            val suffix = "\\+[0-9]*$".r.findFirstIn(raw)
            val edges = if (prefix.nonEmpty || suffix.nonEmpty) DbtLineage.edges(w) else Vector.empty
            union(Vector(selected, prefix.toVector.flatMap(p => selected.flatMap(s => DbtLineage.traverse(edges, s, downstream = false,
              p.dropRight(1).toIntOption.getOrElse(Int.MaxValue)))), suffix.toVector.flatMap(p => selected.flatMap(s => DbtLineage.traverse(edges, s, downstream = true,
              p.drop(1).toIntOption.getOrElse(Int.MaxValue))))))
          }
        }
      } else {
        val name = core.stripPrefix("fqn:")
        val preferLocal = method == "fqn" && com.github.vbmacher.intellij.dbt.syntax.DbtSyntax.isIdentifier(name) &&
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
