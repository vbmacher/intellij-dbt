package com.github.vbmacher.intellij.dbt.navigation

import com.github.vbmacher.intellij.dbt.project.{DbtConfiguration, DbtDeclarationSource, DbtRoot, DbtSymbol, DbtWorkspace}
import com.github.vbmacher.intellij.dbt.syntax.{DbtPython, DbtSyntax, DbtYaml}
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.{PsiElement, PsiFile}
import org.jetbrains.yaml.psi.{YAMLKeyValue, YAMLMapping, YAMLScalar}

import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters._

/** dbt's resolution rules shared by the languages hosting references. */
private[dbt] object DbtResolution {
  /** Versioned declarations: the requested version, or the single latest version, or the unversioned ones. */
  def versioned(defs: Vector[DbtSymbol], requested: Option[String]): Vector[DbtSymbol] = {
    val latest = defs.flatMap(_.attributes.get("latest_version")).distinct
    val selected = requested.orElse(if (latest.size == 1) latest.headOption else None)
    defs.filter(s => selected.fold(s.version.isEmpty)(v => s.version.contains(v)))
  }

  private def varsIn(m: YAMLMapping, project: String, name: String): Option[YAMLKeyValue] =
    DbtYaml.map(m, project).flatMap(DbtYaml.keyValue(_, name)).orElse(DbtYaml.keyValue(m, name))

  def overriddenVariable(r: DbtRoot, name: String): Option[YAMLKeyValue] =
    r.runConfiguration.flatMap(DbtYaml.map(_, "vars")).flatMap(DbtYaml.keyValue(_, name))

  /** A project variable as seen by a project, honouring run configuration overrides and `vars.yml`. */
  def variable(w: DbtWorkspace, r: DbtRoot, name: String): Vector[PsiElement] = {
    val consumer = w.packageOwner(r).getOrElse(r)
    val external = if (consumer.supports(12)) w.rootFile(consumer, "vars.yml").flatMap(DbtYaml.mapping).flatMap(DbtYaml.map(_, "vars")) else None
    val original = DbtYaml.map(consumer.config, "vars")
    def nonEmpty(m: Option[YAMLMapping]): Boolean = m.exists(_.getKeyValues.size() > 0)
    overriddenVariable(consumer, name) match {
      case Some(definition) => Vector(definition)
      case None if nonEmpty(external) && nonEmpty(original) => Vector.empty
      case None => external.orElse(original).flatMap(varsIn(_, r.name, name))
        .orElse(if (consumer != r) DbtYaml.map(r.config, "vars").flatMap(varsIn(_, r.name, name)) else None).toVector
    }
  }

  def configuration(w: DbtWorkspace, file: PsiFile, key: String): Vector[PsiElement] = {
    val generators = Map("schema" -> "generate_schema_name", "alias" -> "generate_alias_name", "database" -> "generate_database_name")
    DbtConfiguration.locations(w, file, key) ++ w.root(file).toVector.flatMap(r => generators.get(key).toVector
      .flatMap(n => w.declarations(Set("macro"), n, r).map(_.element)))
  }

  def sourceTables(w: DbtWorkspace, root: DbtRoot, source: String, table: String): Vector[DbtSymbol] = {
    val parents = w.declarations(Set("source"), source, root)
    w.symbols.filter(s => s.kind == "source_table" && s.name == table && s.owner == source &&
      s.isEnabled && parents.exists(p => p.namespace == s.namespace &&
        w.owner(p) == w.owner(s) && (p.file == s.file && p.mapping.exists(_.getTextRange.contains(s.range)) || s.metadata)))
  }

  /** Local sources selected for a builtin, or its documentation. */
  def builtin(w: DbtWorkspace, root: DbtRoot, host: PsiFile, name: String, page: String): Vector[PsiElement] = {
    val targets = w.symbols.filter(s => s.kind == "builtin" && s.name == name &&
      s.attributes.get("builtin-root").contains(root.directory.getPath)).map(_.element)
    if (targets.nonEmpty) targets else Vector(documentation(host, name, page, root))
  }

  /** Materializations of the configured adapter, falling back to more generic adapters. */
  def materializations(w: DbtWorkspace, r: DbtRoot, name: String): Vector[DbtSymbol] = {
    val declarations = w.declarations(Set("materialization"), name, r)
    if (r.adapter.isEmpty) declarations
    else r.adapterFallbacks.iterator.map(a => declarations.filter(_.attributes.get("adapter").contains(a))).find(_.nonEmpty).getOrElse(Vector.empty)
  }

  /** Adapter implementations of a dispatched macro, following the configured search order. */
  def dispatch(w: DbtWorkspace, r: DbtRoot, name: String, namespace: Option[String]): Vector[DbtSymbol] = {
    val order = DbtYaml.items(r.config, "dispatch").find(m => DbtYaml.string(m, "macro_namespace") == namespace)
      .map(DbtYaml.strings(_, "search_order")).getOrElse((Vector(r.name) ++ namespace).distinct)
    if (r.adapter.isEmpty) w.symbols.filter(s => s.kind == "macro" && s.name.endsWith("__" + name) && order.contains(s.namespace))
    else order.iterator.flatMap(ns => r.adapterFallbacks.iterator.map(p => w.declarations(Set("macro"), s"${p}__$name", r, Some(ns))))
      .find(_.nonEmpty).getOrElse(Vector.empty)
  }

  /** Semantic layer names, including `entity__dimension` join paths. */
  def semantic(w: DbtWorkspace, root: DbtRoot, file: PsiFile, offset: Int, kind: String, name: String): Vector[DbtSymbol] = {
    val resourceKind = Map("Dimension" -> "dimension", "TimeDimension" -> "dimension", "Entity" -> "entity", "Metric" -> "metric").getOrElse(kind, kind)
    val path = name.split("__").toVector
    val field = path.last
    val scope = w.enclosing("semantic_model", file, offset)
    val candidates = w.symbols.filter(s => s.kind == resourceKind && s.name == field && s.namespace == root.name && w.root(s.file).contains(root))
    if (path.size == 1 && scope.nonEmpty && resourceKind != "metric") candidates.filter(s => scope.exists(_.name == s.owner))
    else if (path.size > 1) {
      val entities = w.symbols.filter(s => s.kind == "entity" && s.namespace == root.name && w.root(s.file).contains(root))
      var owners = Set.empty[String]
      var known = true
      path.dropRight(1).foreach { name =>
        val references = entities.filter(e => e.name == name && (owners.isEmpty || owners(e.owner)))
        val joins = entities.filter(e => e.name == name && e.attributes.get("type").exists(Set("primary", "unique", "natural")))
        if (references.isEmpty || joins.isEmpty) known = false
        owners = joins.map(_.owner).toSet
      }
      if (known) candidates.filter(s => owners(s.owner)) else Vector.empty
    } else candidates
  }

  def documentation(file: PsiFile, name: String, page: String, r: DbtRoot): PsiElement = {
    val path = page match {
      case "info-schema" => "reference/info-schema"
      case "data-tests" => "reference/resource-properties/data-tests"
      case "function" => "docs/build/udfs"
      case "statement" | "load-result" => "reference/dbt-jinja-functions/statement-blocks"
      case "on-run-end-context" => "reference/dbt-jinja-functions/on-run-end-context"
      case _ => "reference/dbt-jinja-functions/" + page.replace('-', '_')
    }
    DbtSymbol("documentation", name, "dbt", file, new TextRange(0, 0), attributes = Map("url" ->
      s"https://docs.getdbt.com/$path?version=${if (page == "info-schema") "2" else r.version}"), metadata = true).element
  }
}

/** Only explicitly selected local source files can replace builtin documentation targets. */
private[navigation] final class DbtBuiltinSources extends DbtDeclarationSource {
  override def declarations(w: DbtWorkspace): Vector[DbtSymbol] = w.roots.flatMap { root =>
    root.settings.flatMap(DbtYaml.map(_, "builtin-sources")).toVector.flatMap(_.getKeyValues.asScala).flatMap { entry =>
      val name = entry.getKeyText
      Option(entry.getValue).collect { case s: YAMLScalar => s.getTextValue }.toVector.flatMap(root.psiFile).flatMap { file =>
        val identifier = name.split("\\.").last
        val ranges = if (file.getVirtualFile.getExtension == "py") DbtPython.declarations(file, identifier).map(_.getTextRange)
          else if (file.getVirtualFile.getExtension == "sql") DbtSyntax.inFile(file)
            .filter(_.isTag("macro"))
            .flatMap(_.tokens.lift(1)).filter(_.image == identifier).map(_.range)
          else Vector.empty
        ranges.map(range => DbtSymbol("builtin", name, root.name, file, range, attributes = Map("builtin-root" -> root.directory.getPath)))
      }
    }
  }
}

/** Parse-time v1 view definitions published by dbt v2; no metadata queries are executed. */
private[navigation] object DbtInfoSchema {
  private val common = Set("unique_id", "name", "resource_type", "package_name", "original_file_path", "fqn", "alias", "description",
    "node_language", "database_name", "schema_name", "relation_name", "identifier", "enabled", "materialized", "config", "access",
    "group", "contract_enforced", "version", "latest_version", "deprecation_date", "primary_key", "properties_yml_file_path", "tags", "meta",
    "ingested_at")
  val views: Map[String, Set[String]] = Map(
    "models" -> common, "seeds" -> common, "snapshots" -> (common + "raw_code"), "functions" -> (common + "raw_code"),
    "analyses" -> (common + "raw_code"), "hooks" -> (common + "raw_code"), "checks" -> (common + "raw_code"),
    "sources" -> (common ++ Set("source_name", "source_description", "loader", "loaded_at_field")),
    "data_tests" -> (common ++ Set("test_name", "test_definition_package", "arguments", "column_name", "node_unique_id", "severity", "warn_if",
      "error_if", "fail_calc", "store_failures", "store_failures_as")),
    "unit_tests" -> Set("unique_id", "name", "model", "description", "package_name", "original_file_path", "fqn", "given", "expect", "overrides",
      "versions", "ingested_at"),
    "node_columns" -> Set("node_unique_id", "column_name", "data_type_declared", "description", "tags", "ingested_at"),
    "dag_nodes" -> Set("unique_id", "resource_type", "ingested_at")
  )

  def enabled(root: DbtRoot, file: PsiFile): Boolean = root.engine == "v2" && root.kind(file.getVirtualFile).contains("check") &&
    !DbtWorkspace.get(file.getProject).symbols.exists(s => s.file == file && s.kind == "check" && !s.isEnabled) &&
    DbtYaml.map(root.config, "info_schema").flatMap(DbtYaml.string(_, "version")).forall(_ == "1")

  def columns(file: PsiFile, root: DbtRoot, view: String): Map[String, Vector[PsiElement]] =
    views.getOrElse(view, Set.empty).map(n => n -> metadata(file, root, "information_schema_column", n, view)).toMap

  def view(file: PsiFile, root: DbtRoot, name: String): Vector[PsiElement] = metadata(file, root, "information_schema_view", name)

  /** Declarations read from Information Schema artifacts, or the documentation when none were read. */
  private def metadata(file: PsiFile, root: DbtRoot, kind: String, name: String, owner: String = ""): Vector[PsiElement] = {
    val w = DbtWorkspace.get(file.getProject)
    val found = w.symbols.filter(s => s.kind == kind && s.namespace == root.name && w.owner(s).contains(root) && s.owner == owner && s.name == name)
    if (found.nonEmpty) found.map(_.element)
    else Vector(DbtResolution.documentation(file, if (owner.isEmpty) name else s"$owner.$name", "info-schema", root))
  }
}

/** A bounded proof of literal values; all unsupported operations return unknown. */
private[navigation] object DbtStaticCalls {
  def value(tokens: Vector[DbtSyntax.Token], constants: Map[String, Vector[String]]): Vector[String] = {
    def atom(ts: Vector[DbtSyntax.Token]): Vector[String] = ts match {
      case Vector(t) if t.string => Vector(t.image)
      case Vector(t) if !t.string => constants.getOrElse(t.image, Vector.empty)
      case xs if xs.headOption.exists(_.image == "[") && xs.lastOption.exists(_.image == "]") =>
        val items = xs.drop(1).dropRight(1).filterNot(t => !t.string && t.image == ",")
        if (items.size <= 16 && items.forall(_.string)) items.map(_.image) else Vector.empty
      case xs if xs.headOption.exists(_.image == "(") && xs.lastOption.exists(_.image == ")") => value(xs.drop(1).dropRight(1), constants)
      case _ => Vector.empty
    }

    val operators = tokens.zipWithIndex.collect { case (t, i) if !t.string && Set("~", "+").contains(t.image) => i }
    if (operators.isEmpty) atom(tokens)
    else {
      val boundaries = Vector(-1) ++ operators ++ Vector(tokens.size)
      boundaries.sliding(2).collect { case Vector(a, b) => atom(tokens.slice(a + 1, b)) }.foldLeft(Vector("")) { (left, right) =>
        if (left.size * right.size > 16) Vector.empty else for (a <- left; b <- right) yield a + b
      }
    }
  }

  def constants(blocks: Vector[DbtSyntax.Block], before: Int): Map[String, Vector[String]] = {
    var scopes = List(Map.empty[String, Vector[String]])
    var captures = List.empty[String]
    def visible: Map[String, Vector[String]] = scopes.reverse.foldLeft(Map.empty[String, Vector[String]])(_ ++ _)
    def bind(name: String, values: Vector[String]): Unit = scopes = (scopes.head + (name -> values)) :: scopes.tail
    blocks.takeWhile(_.range.getStartOffset < before).foreach { b =>
      val ts = b.tokens
      val tag = b.tagName.getOrElse("")
      if (DbtSyntax.ScopeTags(tag)) {
        val outer = visible
        val bindings = if (tag == "for") {
          val targets = ts.drop(1).takeWhile(_.image != "in").filterNot(_.image == ",")
          targets.map(t => t.image -> (if (targets.size == 1) value(ts.dropWhile(_.image != "in").drop(1), outer) else Vector.empty[String])).toMap
        } else if (Set("macro", "test", "call").contains(tag)) DbtSyntax.parameters(b).map(t => t.image -> Vector.empty[String]).toMap
        else if (tag == "with") {
          val assignments = DbtSyntax.assignedNames(ts)
          assignments.zipWithIndex.map { case (i, index) =>
            val end = assignments.lift(index + 1).map(_ - 1).getOrElse(ts.size)
            ts(i).image -> value(ts.slice(i + 2, end), outer)
          }.toMap
        } else Map.empty[String, Vector[String]]
        scopes = bindings :: scopes
      }
      if (DbtSyntax.ScopeEndTags(tag) && scopes.size > 1) scopes = scopes.tail
      if (tag == "endset" && captures.nonEmpty) {
        bind(captures.head, Vector.empty)
        captures = captures.tail
      }
      if (tag == "set" && ts.lift(2).exists(_.image == "=")) {
        val env = visible
        val name = ts(1).image
        val next = value(ts.drop(3), env)
        // Conditional branches remain a finite set of candidates, never an assumed runtime branch.
        val preceding = blocks.takeWhile(_.range.getStartOffset < b.range.getStartOffset)
        val conditional = preceding.count(_.isTag("if")) > preceding.count(_.isTag("endif"))
        bind(name, if (conditional && (next.isEmpty || env.get(name).exists(_.isEmpty))) Vector.empty
          else if (conditional) (env.getOrElse(name, Vector.empty) ++ next).distinct else next)
      } else if (tag == "set" && !ts.lift(2).exists(_.image == ".")) {
        if (!ts.exists(_.image == "=")) ts.lift(1).foreach(t => captures = t.image :: captures)
        else ts.drop(1).takeWhile(_.image != "=").filter(t => !t.string && DbtSyntax.isIdentifier(t.image)).foreach(t => bind(t.image, Vector.empty))
      }
    }
    visible
  }

  def targets(w: DbtWorkspace, root: DbtRoot, call: DbtSyntax.Call, env: Map[String, Vector[String]]): Vector[PsiElement] = {
    val args = call.positional.map(a => value(a.tokens, env))
    val base = call.name.stripPrefix("builtins.")
    if (!call.name.startsWith("builtins.") && w.declarations(Set("macro"), base, root).nonEmpty) return Vector.empty
    base match {
      case "ref" | "function" | "doc" =>
        val kinds = if (base == "ref") DbtSymbol.RefKinds else Set(base)
        val qualified = args.size >= 2
        val names = args.lift(if (qualified) 1 else 0).getOrElse(Vector.empty)
        val namespaces = if (qualified) args.head.map(Some(_)) else Vector(None)
        call.version match {
          case Some(None) => Vector.empty
          case version => (for (name <- names; ns <- namespaces;
                                s <- DbtResolution.versioned(w.declarations(kinds, name, root, ns), version.flatten)) yield s.element).distinct
        }
      case "source" if args.size == 2 =>
        (for (source <- args.head; table <- args(1); s <- DbtResolution.sourceTables(w, root, source, table)) yield s.element).distinct
      case "var" => args.headOption.toVector.flatten.flatMap(DbtResolution.variable(w, root, _))
      case _ => Vector.empty
    }
  }

  def wrapperTargets(w: DbtWorkspace, root: DbtRoot, macroSymbol: DbtSymbol, call: DbtSyntax.Call,
    constants: Map[String, Vector[String]], visited: Set[String] = Set.empty,
    onMissing: (DbtSyntax.Argument, String) => Unit = (_, _) => ()): Vector[(DbtSyntax.Argument, Vector[PsiElement])] = {
    if (visited(macroSymbol.id) || visited.size >= 8) return Vector.empty
    ProgressManager.checkCanceled()
    val blocks = DbtSyntax.inFile(macroSymbol.file)
    val start = blocks.indexWhere(b => b.tag && b.tokens.lift(1).exists(_.range == macroSymbol.range))
    if (start < 0) return Vector.empty
    val body = blocks.drop(start + 1).takeWhile(!_.isTag("endmacro"))
    val params = w.symbols.filter(s => s.kind == "parameter" && s.owner == macroSymbol.name && s.file == macroSymbol.file).sortBy(_.range.getStartOffset)
    val bindings = params.zipWithIndex.flatMap { case (p, i) =>
      call.arguments.find(_.keyword.contains(p.name)).orElse(call.positional.lift(i)).map(a => p.name -> (a, value(a.tokens, constants)))
    }.toMap
    def originals(argument: DbtSyntax.Argument): Vector[DbtSyntax.Argument] = argument.tokens.flatMap(t => bindings.get(t.image)).map(_._1)
    val env = bindings.view.mapValues(_._2).toMap
    val result = ArrayBuffer.empty[(DbtSyntax.Argument, Vector[PsiElement])]
    body.flatMap(DbtSyntax.calls).foreach { delegated =>
      // An assignment before the delegated call overrides a parameter, including unknown runtime values.
      val assigned = this.constants(body, delegated.block.range.getStartOffset)
      val reassigned = assigned.keySet
      val local = env ++ assigned
      val targets = this.targets(w, root, delegated, local)
      if (targets.isEmpty) missing(w, root, delegated, local).foreach { case (argument, message) =>
        argument.tokens.filterNot(_.string).flatMap(t => bindings.get(t.image)).foreach { case (original, _) => onMissing(original, message) }
      }
      delegated.arguments.foreach { a =>
        a.tokens.filter(t => !t.string && !reassigned(t.image)).flatMap(t => bindings.get(t.image)).foreach { case (argument, _) =>
          if (targets.nonEmpty) result += argument -> targets
        }
      }
      if (targets.isEmpty) w.macros(delegated, root).foreach { next =>
        wrapperTargets(w, root, next, delegated, local, visited + macroSymbol.id, (arg, message) => originals(arg).foreach(onMissing(_, message)))
          .foreach { case (arg, candidates) => originals(arg).foreach(result += _ -> candidates) }
      }
    }
    result.toVector
  }

  /** A proof of a single static missing target, using exactly the same bounded evaluator as navigation. */
  def missing(w: DbtWorkspace, root: DbtRoot, call: DbtSyntax.Call,
    constants: Map[String, Vector[String]]): Option[(DbtSyntax.Argument, String)] = {
    val args = call.positional.map(a => value(a.tokens, constants))
    val base = call.name.stripPrefix("builtins.")
    val literal = args.forall(_.size == 1)
    val qualified = args.size == 2
    val namespaceKnown = !qualified || base == "source" || args.headOption.flatMap(_.headOption).exists(ns =>
      w.roots.exists(_.name == ns) || w.symbols.exists(_.namespace == ns))
    if (!call.closed || !literal || !namespaceKnown || call.version.contains(None) ||
      !call.name.startsWith("builtins.") && w.macros(call, root).nonEmpty || targets(w, root, call, constants).nonEmpty) None
    else base match {
      case "ref" if args.size == 1 || args.size == 2 =>
        val index = if (qualified) 1 else 0
        call.positional.lift(index).map(_ -> (s"Unresolved ref: '${args(index).head}'" +
          call.version.flatten.map(" (version " + _ + ")").getOrElse("")))
      case "source" if args.size == 2 =>
        val source = args(0).head; val table = args(1).head
        if (w.declarations(Set("source"), source, root).isEmpty) call.positional.headOption.map(_ -> s"Unresolved source: '$source'")
        else call.positional.lift(1).map(_ -> s"Unresolved source table: '$source.$table'")
      case _ => None
    }
  }
}
