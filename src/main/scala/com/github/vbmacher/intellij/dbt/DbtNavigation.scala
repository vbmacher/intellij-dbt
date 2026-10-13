package com.github.vbmacher.intellij.dbt

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi._
import org.jetbrains.yaml.psi._
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters._

/** Every resolver produces candidates; ambiguity is preserved for the IDE's declaration chooser. */
final class DbtNavigationReference private[dbt] (file: PsiFile, range: TextRange, val candidates: Vector[PsiElement], val label: String,
  val evidence: Vector[String] = Vector.empty)
  extends PsiPolyVariantReferenceBase[PsiFile](file, range, true) {
  override def multiResolve(incompleteCode: Boolean): Array[ResolveResult] = candidates.distinct.map(new PsiElementResolveResult(_)).toArray
  override def getCanonicalText: String = label
  override def isReferenceTo(element: PsiElement): Boolean = candidates.exists { candidate =>
    (candidate, element) match {
      case (a: DbtSymbolElement, _) if a.symbol.kind == "documentation" => false
      case (a: DbtSymbolElement, b: DbtSymbolElement) if b.symbol.attributes.contains("property") && !a.symbol.attributes.contains("property") =>
        a.isValid && b.isValid && DbtWorkspace.get(a.getProject).properties(a.symbol).exists(p =>
          p.file == b.getContainingFile && p.kind == b.symbol.kind && p.name == b.symbol.name &&
            p.version == b.symbol.version && p.element.getTextRange == b.getTextRange)
      case (a: DbtSymbolElement, b: DbtSymbolElement) => a.isValid && b.isValid && a.getContainingFile == b.getContainingFile &&
        (a.getTextRange == b.getTextRange || (a.symbol.attributes.contains("code") && b.symbol.attributes.contains("code"))) && a.symbol.kind == b.symbol.kind && a.symbol.name == b.symbol.name &&
        a.symbol.namespace == b.symbol.namespace && a.symbol.owner == b.symbol.owner && a.symbol.version == b.symbol.version
      case _ => candidate == element || candidate.getManager.areElementsEquivalent(candidate, element)
    }
  }
}

private[dbt] object DbtNavigation {
  private val resources = Set("model", "seed", "snapshot")
  private val ReferenceCache = com.intellij.openapi.util.Key.create[com.intellij.psi.util.CachedValue[Vector[DbtNavigationReference]]]("dbt.navigation.references")
  def inFile(file: PsiFile, includeSelections: Boolean = true): Vector[DbtNavigationReference] = {
    if (!file.isPhysical || file.getVirtualFile == null) return Vector.empty
    if (!includeSelections) compute(file, includeSelections = false)
    else com.intellij.psi.util.CachedValuesManager.getManager(file.getProject).getCachedValue(file, ReferenceCache,
      () => com.intellij.psi.util.CachedValueProvider.Result.create(compute(file, includeSelections = true),
        com.intellij.psi.util.PsiModificationTracker.MODIFICATION_COUNT,
        com.intellij.openapi.vfs.VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS), false)
  }
  private def compute(file: PsiFile, includeSelections: Boolean): Vector[DbtNavigationReference] = {
    val workspace = DbtWorkspace.get(file.getProject)
    val root = workspace.root(file)
    if (root.isEmpty || root.exists(_.excluded(file.getVirtualFile))) return Vector.empty
    val r = root.get
    val configuredSelections = r.settings.toVector.flatMap(DbtYaml.strings(_, "selection-files")) ++ r.setting("run-config")
    if (!r.resource(file) && !r.relative(file.getVirtualFile).exists(configuredSelections.contains) &&
      !Set("dbt_project.yml", "profiles.yml", "packages.yml", "package-lock.yml", "dependencies.yml", "selectors.yml", "vars.yml").contains(file.getName) && !DbtYamlContexts.profile(file, r))
      return Vector.empty
    val refs = ArrayBuffer.empty[DbtNavigationReference]
    def add(range: TextRange, targets: Iterable[PsiElement], name: String, evidence: Vector[String] = Vector.empty): Unit =
      if (range.getStartOffset >= 0 && range.getEndOffset <= file.getTextLength && range.getLength > 0)
        refs += new DbtNavigationReference(file, range, targets.toVector.distinct, name,
          if (name == "static reference") Vector("Expression: " + range.substring(file.getText)) else evidence)
    val blocks = if (file.getName == "dependencies.yml" || file.getVirtualFile.getExtension == "csv" ||
      (file.getVirtualFile.getExtension == "py" && r.kind(file.getVirtualFile).contains("model")))
      Vector.empty else DbtYamlContexts.blocks(file, r)
    val locals = if (file.isInstanceOf[YAMLFile]) blocks.groupBy(b => DbtYamlContexts.scalar(file, b.range.getStartOffset))
      .valuesIterator.flatMap(bs => DbtLocals.references(file, bs, workspace)).toVector
      else DbtLocals.references(file, blocks, workspace)
    refs ++= locals
    def localAt(range: TextRange): Boolean = locals.exists(x => x.getRangeInElement.intersects(range))
    def lookup(kinds: Set[String], name: String, ns: Option[String] = None): Vector[DbtSymbol] = workspace.declarations(kinds, name, r, ns)
    def literal(call: DbtSyntax.Call, i: Int): Option[String] = call.arguments.filter(_.keyword.isEmpty).lift(i).flatMap(_.literal)
    def arg(call: DbtSyntax.Call, i: Int): Option[TextRange] = call.arguments.filter(_.keyword.isEmpty).lift(i).flatMap(_.range)
    def definitions(call: DbtSyntax.Call, kind: Set[String]): Vector[DbtSymbol] = {
      val second = literal(call, 1)
      val ns = if (second.nonEmpty) literal(call, 0) else None
      val name = second.orElse(literal(call, 0))
      name.toVector.flatMap(n => lookup(kind, n, ns))
    }
    blocks.flatMap(DbtSyntax.calls).filter(c => DbtYamlContexts.allows(file, r, c.range.getStartOffset, c.name)).foreach { call =>
      ProgressManager.checkCanceled()
      val base = call.name.stripPrefix("builtins.")
      val constants = DbtStaticCalls.constants(DbtYamlContexts.hostBlocks(file, blocks, call.range.getStartOffset), call.block.range.getStartOffset)
      val parts = call.name.split("\\.").toVector
      val macroName = parts.last
      val macroNamespace = if (parts.size == 2) Some(parts.head) else None
      val macros = if (call.name.startsWith("builtins.") || localAt(call.range)) Vector.empty else lookup(Set("macro"), macroName, macroNamespace)
      if (macros.nonEmpty) {
        add(call.range, macros.map(_.element), call.name)
        macros.foreach(m => DbtStaticCalls.wrapperTargets(workspace, r, m, call, constants).foreach { case (a, targets) =>
          a.range.foreach(x => add(x, targets, "delegated reference", Vector(s"Wrapper: ${m.namespace}.${m.name}", "Argument: " + x.substring(file.getText))))
        })
        call.arguments.filter(_.keyword.nonEmpty).foreach { a =>
          val key = a.keyword.get
          val range = a.keywordRange
          range.foreach(x => add(x, workspace.symbols.filter(s => s.kind == "parameter" && s.owner == macroName && s.name == key &&
            macros.exists(m => m.namespace == s.namespace && m.file == s.file)).map(_.element), key))
        }
      }
      if (macros.isEmpty && !localAt(call.range)) base match {
        case "ref" | "function" =>
          call.arguments.filter(a => a.keyword.isEmpty && a.literal.isEmpty).foreach(a => a.range.foreach(x =>
            add(x, DbtStaticCalls.targets(workspace, r, call, constants), "static reference")))
          val defs = definitions(call, if (base == "ref") resources else Set("function"))
          val versionArgument = call.arguments.find(a => a.keyword.exists(Set("v", "version")))
          val requested = versionArgument.flatMap(_.literal)
          val latest = defs.flatMap(_.attributes.get("latest_version")).distinct
          val selected = requested.orElse(if (latest.size == 1) latest.headOption else None)
          val targets = if (versionArgument.nonEmpty && requested.isEmpty) Vector.empty else
            defs.filter(s => selected.fold(s.version.isEmpty)(v => s.version.contains(v)))
          arg(call, if (literal(call, 1).nonEmpty) 1 else 0).foreach(x => add(x, targets.map(_.element), base))
          if (literal(call, 1).nonEmpty) arg(call, 0).foreach(x => add(x, workspace.roots.filter(rr => literal(call, 0).contains(rr.name))
            .map(rr => rr.config.getContainingFile), "package"))
        case "source" =>
          if (call.arguments.exists(a => a.keyword.isEmpty && a.literal.isEmpty)) call.arguments.filter(_.keyword.isEmpty).foreach(a =>
            a.range.foreach(x => add(x, DbtStaticCalls.targets(workspace, r, call, constants), "static reference")))
          val source = literal(call, 0)
          val table = literal(call, 1)
          source.foreach(n => arg(call, 0).foreach(x => add(x, lookup(Set("source"), n).map(_.element), n)))
          table.foreach(n => arg(call, 1).foreach(x => add(x, source.toVector.flatMap(src => sourceTables(workspace, r, src, n)).map(_.element), n)))
        case "var" =>
          call.arguments.filter(_.keyword.isEmpty).take(1).filter(_.literal.isEmpty).foreach(a => a.range.foreach(x =>
            add(x, DbtStaticCalls.value(a.tokens, constants).flatMap(n => variableTargets(workspace, file, r, call.range.getStartOffset, n)), "static reference")))
          literal(call, 0).foreach(n => arg(call, 0).foreach(x => add(x, variableTargets(workspace, file, r, call.range.getStartOffset, n), n)))
        case "Dimension" | "TimeDimension" | "Entity" | "Metric" =>
          literal(call, 0).foreach(n => arg(call, 0).foreach(x => add(x,
            DbtSemantics.targets(workspace, r, file, x.getStartOffset, base, n).map(_.element), n)))
          literal(call, 0).filter(_.contains("__")).foreach { name => arg(call, 0).foreach { range =>
            if (range.substring(file.getText) == name) {
              var start = range.getStartOffset
              name.split("__").dropRight(1).foreach { entity =>
                add(new TextRange(start, start + entity.length), lookup(Set("entity"), entity).map(_.element), entity)
                start += entity.length + 2
              }
            }
          }}
        case "metric" => literal(call, 0).foreach(n => arg(call, 0).foreach(x => add(x, lookup(Set("metric"), n).map(_.element), n)))
        case "doc" =>
          val allowed = arg(call, 0).exists(x => DbtYamlContexts.description(file, r, x.getStartOffset))
          if (allowed && call.arguments.exists(a => a.keyword.isEmpty && a.literal.isEmpty)) call.arguments.filter(_.keyword.isEmpty).foreach(a =>
            a.range.foreach(x => add(x, DbtStaticCalls.targets(workspace, r, call, constants), "static reference")))
          if (allowed) arg(call, if (literal(call, 1).nonEmpty) 1 else 0).foreach(x => add(x, definitions(call, Set("doc")).map(_.element), "doc"))
        case "env_var" => literal(call, 0).foreach(n => arg(call, 0).foreach(x => add(x, environment(workspace, r, n).map(_.element), n)))
        case "config" => call.arguments.filter(_.keyword.nonEmpty).foreach { a =>
          val key = a.keyword.get
          a.keywordRange.foreach(range => add(range, configuration(workspace, file, key), key))
          if (Set("group", "materialized", "strategy").contains(key)) a.literal.foreach { n => a.range.foreach(x =>
            add(x, (if (key == "materialized") materializations(workspace, r, n) else lookup(Set(if (key == "group") "group" else "macro"),
              if (key == "strategy") s"snapshot_${n}_strategy" else n)).map(_.element), n)) }
        }
        case "config.get" | "config.require" | "dbt.config.get" => literal(call, 0).foreach(n => arg(call, 0).foreach(x => add(x, configuration(workspace, file, n), n)))
        case "adapter.dispatch" => literal(call, 0).foreach(n => arg(call, 0).foreach(x => add(x, dispatch(workspace, r, n, literal(call, 1)).map(_.element), n)))
        case "statement" | "load_result" => literal(call, 0).foreach(n => arg(call, 0).foreach(x => {
          val defs = blocks.flatMap(DbtSyntax.calls).filter(c => c.name == "statement" && literal(c, 0).contains(n))
          val symbols = defs.flatMap(c => arg(c, 0).map(rr => DbtSymbol("result", n, r.name, file, rr).element))
          if (base == "load_result") add(x, symbols, n)
        }))
        case "info_schema" if DbtInfoSchema.enabled(r, file) => literal(call, 0).foreach(n => arg(call, 0).foreach(x =>
          if (DbtInfoSchema.views.contains(n))
            add(x, DbtInfoSchema.view(file, r, n), n)))
        case _ => ()
      }
      if (macros.isEmpty && !localAt(call.range)) {
        val known = Set("ref", "source", "var", "config", "doc", "env_var", "function", "return", "log", "print", "run_query",
          "statement", "load_result", "local_md5", "tojson", "fromjson", "toyaml", "fromyaml", "as_bool", "as_number", "as_native", "zip")
        if (known(base)) add(call.range, builtin(workspace, r, file, base, base.replace('_', '-')), base)
        else if (parts.size == 2 && Set("adapter", "exceptions", "modules", "flags", "graph", "config").contains(parts.head))
          add(call.range, builtin(workspace, r, file, call.name, parts.head), call.name)
      }
    }
    blocks.foreach { b => b.tokens.sliding(3).foreach {
      case Vector(a, dot, field) if dot.image == "." && !a.string && !field.string && a.image == "target" &&
        !localAt(a.range) && DbtYamlContexts.allows(file, r, a.range.getStartOffset, "target") =>
        add(field.range, target(workspace, r, field.image), field.image)
      case _ => ()
    }
      b.tokens.sliding(2).foreach {
        case Vector(pipe, filter) if pipe.image == "|" && !filter.string &&
          Set("as_bool", "as_number", "as_native", "tojson", "fromjson", "toyaml", "fromyaml").contains(filter.image) =>
          add(filter.range, builtin(workspace, r, file, filter.image, filter.image.replace('_', '-')), filter.image)
        case _ => ()
      }
      val attributeRanges = b.tokens.sliding(2).collect { case Vector(dot, field) if dot.image == "." => field.range }.toSet
      b.tokens.filter(t => !t.string && !attributeRanges(t.range) && Set("this", "model", "project_name", "adapter", "exceptions", "modules", "graph", "flags", "execute", "run_started_at", "invocation_id",
        "dbt_version", "thread_id", "schema", "schemas", "database", "selected_resources", "results", "sql", "compiled_code", "builtins").contains(t.image))
        .filterNot(t => localAt(t.range) || refs.exists(ref => ref.getRangeInElement.contains(t.range)))
        .filter(t => DbtYamlContexts.allows(file, r, t.range.getStartOffset, t.image)).foreach { t =>
          val current = workspace.symbols.filter(s => s.file == file && s.attributes.contains("code")) ++
            workspace.symbols.filter(s => s.kind == "unit_test" && s.file == file && s.mapping.exists(_.getTextRange.containsOffset(t.range.getStartOffset)))
              .flatMap(_.mapping.toVector.flatMap(m => DbtYaml.string(m, "model")).flatMap(n => lookup(Set("model"), n)))
          val targets = if (Set("this", "model").contains(t.image)) current.map(_.element)
            else if (t.image == "project_name") Vector(r.config.getKeyValueByKey("name"))
            else builtin(workspace, r, file, t.image, if (Set("results", "schemas", "selected_resources").contains(t.image)) "on-run-end-context" else t.image.replace('_', '-'))
          add(t.range, targets.filter(_ != null), t.image)
        }
    }
    refs ++= DbtYamlNavigation.references(file, workspace, r)
    refs ++= DbtSelectors.references(file, workspace, r, includeSelections)
    refs ++= DbtPythonNavigation.references(file, workspace, r)
    refs ++= DbtSqlNavigation.references(file, workspace, r, refs.toVector)
    refs ++= DbtFunctionParameters.references(file, workspace)
    refs.toVector.groupBy(r => (r.getRangeInElement, r.candidates)).valuesIterator.map(_.head).toVector.sortBy(_.getRangeInElement.getStartOffset)
  }

  def at(file: PsiFile, offset: Int): Vector[DbtNavigationReference] = {
    val matches = inFile(file).filter(_.getRangeInElement.containsOffset(offset))
    matches.map(_.getRangeInElement.getLength).minOption.toVector.flatMap(size => matches.filter(_.getRangeInElement.getLength == size))
  }
  def variable(w: DbtWorkspace, r: DbtRoot, name: String): Vector[PsiElement] = {
    val consumer = w.packageOwner(r).getOrElse(r)
    val external = if (consumer.supports(12)) w.files.find(f => f.getVirtualFile.getParent == consumer.directory && f.getName == "vars.yml")
      .flatMap(DbtYaml.mapping).flatMap(DbtYaml.map(_, "vars")) else None
    val original = DbtYaml.map(consumer.config, "vars")
    val overrideDefinition = consumer.runConfiguration.flatMap(DbtYaml.map(_, "vars")).flatMap(m => Option(m.getKeyValueByKey(name)))
    if (overrideDefinition.nonEmpty) overrideDefinition.toVector
    else if (external.exists(_.getKeyValues.size() > 0) && original.exists(_.getKeyValues.size() > 0)) Vector.empty
    else {
      val rootDefinition = external.orElse(original).flatMap { globals =>
        DbtYaml.map(globals, r.name).flatMap(m => Option(m.getKeyValueByKey(name))).orElse(Option(globals.getKeyValueByKey(name)))
      }
      rootDefinition.orElse(if (consumer != r) DbtYaml.map(r.config, "vars").flatMap(m =>
        DbtYaml.map(m, r.name).flatMap(n => Option(n.getKeyValueByKey(name))).orElse(Option(m.getKeyValueByKey(name)))) else None).toVector
    }
  }
  private def variableTargets(w: DbtWorkspace, file: PsiFile, root: DbtRoot, offset: Int, name: String): Vector[PsiElement] = {
    val overrideDefinition = root.runConfiguration.flatMap(DbtYaml.map(_, "vars")).flatMap(m => Option(m.getKeyValueByKey(name))).toVector
    if (DbtYamlContexts.profile(file, root) || root.relative(file.getVirtualFile).contains("packages.yml")) overrideDefinition
    else if (DbtYamlContexts.projectConfiguration(file, root, offset) && overrideDefinition.isEmpty &&
      (!root.supports(12) || !w.files.exists(f => f.getVirtualFile.getParent == root.directory && f.getName == "vars.yml"))) Vector.empty
    else DbtVariableContexts.roots(w, file, root, Some(offset)).flatMap(r => variable(w, r, name)).distinct
  }
  def environment(w: DbtWorkspace, r: DbtRoot, name: String): Vector[DbtSymbol] = {
    val overrides = r.runConfiguration.flatMap(DbtYaml.map(_, "env")).flatMap(m => Option(m.getKeyValueByKey(name)))
      .toVector.flatMap(k => Option(k.getKey).map(e => DbtSymbol("environment", name, r.name, e.getContainingFile, e.getTextRange)))
    if (overrides.nonEmpty) return overrides
    val names = r.settings.map(DbtYaml.strings(_, "env-files", Vector(".env"))).getOrElse(Vector(".env"))
    names.flatMap(p => Option(r.directory.findFileByRelativePath(p)).flatMap(f => Option(PsiManager.getInstance(w.project).findFile(f)))).flatMap { file =>
      val pattern = ("(?m)^\\s*(?:export\\s+)?(" + java.util.regex.Pattern.quote(name) + ")\\s*=").r
      pattern.findAllMatchIn(file.getText).map(m => DbtSymbol("environment", name, r.name, file, new TextRange(m.start(1), m.end(1))))
    }
  }
  def environmentDeclarations(w: DbtWorkspace): Vector[DbtSymbol] = w.roots.flatMap { root =>
    val overrides = root.runConfiguration.flatMap(DbtYaml.map(_, "env")).toVector.flatMap(_.getKeyValues.asScala)
      .flatMap(k => Option(k.getKey).map(e => DbtSymbol("environment", k.getKeyText, root.name, e.getContainingFile, e.getTextRange)))
    val paths = root.settings.map(DbtYaml.strings(_, "env-files", Vector(".env"))).getOrElse(Vector(".env"))
    val declarations = paths.flatMap(p => Option(root.directory.findFileByRelativePath(p)).flatMap(v => Option(PsiManager.getInstance(w.project).findFile(v))))
      .flatMap(file => "(?m)^\\s*(?:export\\s+)?([A-Za-z_][A-Za-z_0-9]*)\\s*=".r.findAllMatchIn(file.getText)
        .map(m => DbtSymbol("environment", m.group(1), root.name, file, new TextRange(m.start(1), m.end(1)))))
    overrides ++ declarations
  }
  def configuration(w: DbtWorkspace, file: PsiFile, key: String): Vector[PsiElement] = {
    val generators = Map("schema" -> "generate_schema_name", "alias" -> "generate_alias_name", "database" -> "generate_database_name")
    DbtConfiguration.locations(w, file, key) ++ w.root(file).toVector.flatMap(r => generators.get(key).toVector
      .flatMap(n => w.declarations(Set("macro"), n, r).map(_.element)))
  }
  def sourceTables(w: DbtWorkspace, root: DbtRoot, source: String, table: String): Vector[DbtSymbol] = {
    val parents = w.declarations(Set("source"), source, root)
    w.symbols.filter(s => s.kind == "source_table" && s.name == table && s.owner == source &&
      !s.attributes.get("enabled").exists(_.equalsIgnoreCase("false")) && parents.exists(p => p.namespace == s.namespace &&
        w.owner(p) == w.owner(s) && (p.file == s.file && p.mapping.exists(_.getTextRange.contains(s.range)) || s.metadata)))
  }
  private def builtin(w: DbtWorkspace, root: DbtRoot, host: PsiFile, name: String, page: String): Vector[PsiElement] = {
    val targets = w.symbols.filter(s => s.kind == "builtin" && s.name == name &&
      s.attributes.get("builtin-root").contains(root.directory.getPath)).map(_.element)
    if (targets.nonEmpty) targets else Vector(documentation(host, name, page, root))
  }
  def materializations(w: DbtWorkspace, r: DbtRoot, name: String): Vector[DbtSymbol] = {
    val declarations = w.declarations(Set("materialization"), name, r)
    val adapter = r.runtimeSetting("adapter").orElse(target(w, r, "type").headOption.collect { case k: YAMLKeyValue => Option(k.getValue).collect { case s: YAMLScalar => s.getTextValue } }.flatten)
    adapter.fold(declarations) { selected =>
      (Vector(selected) ++ (if (selected == "redshift") Vector("postgres") else Vector.empty) :+ "default")
        .iterator.map(a => declarations.filter(_.attributes.get("adapter").contains(a))).find(_.nonEmpty).getOrElse(Vector.empty)
    }
  }
  def profile(w: DbtWorkspace, r: DbtRoot): Option[YAMLMapping] = {
    val path = r.runtimeSetting("profiles-file").getOrElse("profiles.yml")
    Option(r.directory.findFileByRelativePath(path)).flatMap(f => Option(PsiManager.getInstance(w.project).findFile(f)))
      .flatMap(DbtYaml.mapping).flatMap(m => DbtYaml.string(r.config, "profile").flatMap(DbtYaml.map(m, _)))
  }
  def target(w: DbtWorkspace, r: DbtRoot, key: String): Vector[PsiElement] = profile(w, r).toVector.flatMap { p =>
    val selected = r.runtimeSetting("target").orElse(DbtYaml.string(p, "target")).filterNot(_.contains("{{"))
    if (key == "name") r.settings.flatMap(m => Option(m.getKeyValueByKey("target"))).orElse(r.runConfiguration.flatMap(m => Option(m.getKeyValueByKey("target")))).orElse(Option(p.getKeyValueByKey("target"))).toVector
    else selected.toVector.flatMap(t => DbtYaml.map(p, "outputs").flatMap(DbtYaml.map(_, t))
      .flatMap(o => Option(o.getKeyValueByKey(key))).toVector)
  }
  def dispatch(w: DbtWorkspace, r: DbtRoot, name: String, namespace: Option[String]): Vector[DbtSymbol] = {
    val order = DbtYaml.items(r.config, "dispatch").find(m => DbtYaml.string(m, "macro_namespace") == namespace)
      .map(DbtYaml.strings(_, "search_order")).getOrElse((Vector(r.name) ++ namespace).distinct)
    val adapter = r.runtimeSetting("adapter").orElse(target(w, r, "type").headOption.collect { case k: YAMLKeyValue =>
      Option(k.getValue).collect { case s: YAMLScalar => s.getTextValue }
    }.flatten)
    val prefixes = adapter.toVector ++ (if (adapter.contains("redshift")) Vector("postgres") else Vector.empty) :+ "default"
    if (adapter.isEmpty) w.symbols.filter(s => s.kind == "macro" && s.name.endsWith("__" + name) && order.contains(s.namespace))
    else order.iterator.flatMap(ns => prefixes.iterator.map(p => w.declarations(Set("macro"), s"${p}__$name", r, Some(ns))))
      .find(_.nonEmpty).getOrElse(Vector.empty)
  }
  def documentation(file: PsiFile, name: String, page: String, r: DbtRoot): PsiElement = {
    val path = page match {
      case "info-schema" => "reference/info-schema"
      case "data-tests" => "reference/resource-properties/data-tests"
      case "function" => "docs/build/udfs"
      case "statement" | "load-result" => "reference/dbt-jinja-functions/statement-blocks"
      case _ => "reference/dbt-jinja-functions/" + page.replace('-', '_')
    }
    DbtSymbol("documentation", name, "dbt", file, new TextRange(0, 0), attributes = Map("url" ->
      s"https://docs.getdbt.com/$path?version=${if (page == "info-schema") "2" else r.version}"), metadata = true).element
  }
}
