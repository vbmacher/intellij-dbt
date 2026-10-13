package com.github.vbmacher.intellij.dbt

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi._
import org.jetbrains.yaml.psi._
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters._

/** Every resolver produces candidates; ambiguity is preserved for the IDE's declaration chooser. */
final class DbtNavigationReference private[dbt] (file: PsiFile, range: TextRange, val candidates: Vector[PsiElement], val label: String)
  extends PsiPolyVariantReferenceBase[PsiFile](file, range, true) {
  override def multiResolve(incompleteCode: Boolean): Array[ResolveResult] = candidates.distinct.map(new PsiElementResolveResult(_)).toArray
  override def getCanonicalText: String = label
  override def isReferenceTo(element: PsiElement): Boolean = candidates.exists { candidate =>
    (candidate, element) match {
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
      !Set("dbt_project.yml", "profiles.yml", "packages.yml", "package-lock.yml", "dependencies.yml", "selectors.yml", "vars.yml").contains(file.getName))
      return Vector.empty
    val refs = ArrayBuffer.empty[DbtNavigationReference]
    def add(range: TextRange, targets: Iterable[PsiElement], name: String): Unit =
      if (range.getStartOffset >= 0 && range.getEndOffset <= file.getTextLength && range.getLength > 0)
        refs += new DbtNavigationReference(file, range, targets.toVector.distinct, name)
    val blocks = if (file.getName == "dependencies.yml" || file.getVirtualFile.getExtension == "csv" ||
      (file.getVirtualFile.getExtension == "py" && r.kind(file.getVirtualFile).contains("model")))
      Vector.empty else DbtSyntax.inFile(file)
    val restricted = Set("profiles.yml", "packages.yml", "dependencies.yml").contains(file.getName)
    val locals = DbtLocals.references(file, blocks, workspace)
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
    blocks.flatMap(DbtSyntax.calls).filter(c => !restricted || c.name == "env_var").foreach { call =>
      ProgressManager.checkCanceled()
      val base = call.name.stripPrefix("builtins.")
      val constants = DbtStaticCalls.constants(blocks, call.block.range.getStartOffset)
      val parts = call.name.split("\\.").toVector
      val macroName = parts.last
      val macroNamespace = if (parts.size == 2) Some(parts.head) else None
      val macros = if (call.name.startsWith("builtins.") || localAt(call.range)) Vector.empty else lookup(Set("macro"), macroName, macroNamespace)
      if (macros.nonEmpty) {
        add(call.range, macros.map(_.element), call.name)
        macros.flatMap(m => DbtStaticCalls.wrapperTargets(workspace, r, m, call, constants)).foreach { case (a, targets) =>
          a.range.foreach(x => add(x, targets, "delegated reference"))
        }
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
          val requested = call.arguments.find(a => a.keyword.exists(Set("v", "version"))).flatMap(_.literal)
          val latest = defs.flatMap(_.attributes.get("latest_version")).distinct
          val selected = requested.orElse(if (latest.size == 1) latest.headOption else None)
          val targets = defs.filter(s => selected.fold(s.version.isEmpty)(v => s.version.contains(v)))
          arg(call, if (literal(call, 1).nonEmpty) 1 else 0).foreach(x => add(x, targets.map(_.element), base))
          if (literal(call, 1).nonEmpty) arg(call, 0).foreach(x => add(x, workspace.roots.filter(rr => literal(call, 0).contains(rr.name))
            .map(rr => rr.config.getContainingFile), "package"))
        case "source" =>
          val source = literal(call, 0)
          val table = literal(call, 1)
          source.foreach(n => arg(call, 0).foreach(x => add(x, lookup(Set("source"), n).map(_.element), n)))
          table.foreach(n => arg(call, 1).foreach(x => add(x, workspace.symbols.filter(s => s.kind == "source_table" &&
            s.name == n && source.contains(s.owner) && !s.attributes.get("enabled").exists(_.equalsIgnoreCase("false")) &&
            source.toVector.flatMap(src => lookup(Set("source"), src)).exists(parent => parent.namespace == s.namespace &&
              ((parent.file == s.file && parent.mapping.exists(_.getTextRange.contains(s.range))) || (s.metadata && s.owner == parent.name))))
            .map(_.element), n)))
        case "var" =>
          literal(call, 0).foreach(n => arg(call, 0).foreach(x => add(x, if (file.getName == "dbt_project.yml" && r.runConfiguration.flatMap(DbtYaml.map(_, "vars")).isEmpty && (!r.supports(12) ||
            !workspace.files.exists(f => f.getVirtualFile.getParent == r.directory && f.getName == "vars.yml"))) Vector.empty
            else DbtVariableContexts.roots(workspace, file, r).flatMap(root => variable(workspace, root, n)).distinct, n)))
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
          val allowed = !file.isInstanceOf[YAMLFile] || arg(call, 0).exists(x =>
            Option(file.findElementAt(x.getStartOffset)).exists(e => DbtSyntax.yamlAncestors(e).contains("description")))
          if (allowed) arg(call, if (literal(call, 1).nonEmpty) 1 else 0).foreach(x => add(x, definitions(call, Set("doc")).map(_.element), "doc"))
        case "env_var" => literal(call, 0).foreach(n => arg(call, 0).foreach(x => add(x, environment(workspace, r, n).map(_.element), n)))
        case "config" => call.arguments.filter(_.keyword.nonEmpty).foreach { a =>
          val key = a.keyword.get
          a.keywordRange.foreach(range => add(range, configuration(workspace, file, key), key))
          if (Set("group", "materialized", "strategy").contains(key)) a.literal.foreach { n => a.range.foreach(x =>
            add(x, (if (key == "materialized") materializations(workspace, r, n) else lookup(Set(if (key == "group") "group" else "macro"),
              if (key == "strategy") s"snapshot_${n}_strategy" else n)).map(_.element), n)) }
        }
        case "config.get" | "dbt.config.get" => literal(call, 0).foreach(n => arg(call, 0).foreach(x => add(x, configuration(workspace, file, n), n)))
        case "adapter.dispatch" => literal(call, 0).foreach(n => arg(call, 0).foreach(x => add(x, dispatch(workspace, r, n, literal(call, 1)).map(_.element), n)))
        case "statement" | "load_result" => literal(call, 0).foreach(n => arg(call, 0).foreach(x => {
          val defs = blocks.flatMap(DbtSyntax.calls).filter(c => c.name == "statement" && literal(c, 0).contains(n))
          val symbols = defs.flatMap(c => arg(c, 0).map(rr => DbtSymbol("result", n, r.name, file, rr).element))
          if (base == "load_result") add(x, symbols, n)
        }))
        case "info_schema" if DbtInfoSchema.enabled(r, file) => literal(call, 0).foreach(n => arg(call, 0).foreach(x =>
          if (DbtInfoSchema.views.contains(n))
            add(x, Vector(documentation(file, n, "info-schema", r)), n)))
        case _ => ()
      }
      if (macros.isEmpty && !localAt(call.range)) {
        val known = Set("ref", "source", "var", "config", "doc", "env_var", "function", "return", "log", "print", "run_query",
          "statement", "load_result", "local_md5", "tojson", "fromjson", "toyaml", "fromyaml", "as_bool", "as_number", "as_native", "zip")
        if (known(base)) add(call.range, Vector(documentation(file, base, base.replace('_', '-'), r)), base)
        else if (parts.size == 2 && Set("adapter", "exceptions", "modules", "flags", "graph").contains(parts.head))
          add(call.range, Vector(documentation(file, call.name, parts.head, r)), call.name)
      }
    }
    blocks.foreach { b => b.tokens.sliding(3).foreach {
      case Vector(a, dot, field) if dot.image == "." && !a.string && !field.string && a.image == "target" =>
        add(field.range, target(workspace, r, field.image), field.image)
      case _ => ()
    }
      b.tokens.filter(t => !t.string && Set("this", "model", "project_name", "adapter", "exceptions", "modules", "graph", "flags", "execute", "run_started_at", "invocation_id").contains(t.image))
        .filterNot(t => localAt(t.range)).foreach { t =>
          val current = workspace.symbols.filter(s => s.file == file && s.attributes.contains("code")) ++
            workspace.symbols.filter(s => s.kind == "unit_test" && s.file == file && s.mapping.exists(_.getTextRange.containsOffset(t.range.getStartOffset)))
              .flatMap(_.mapping.toVector.flatMap(m => DbtYaml.string(m, "model")).flatMap(n => lookup(Set("model"), n)))
          val targets = if (Set("this", "model").contains(t.image)) current.map(_.element)
            else if (t.image == "project_name") Vector(r.config.getKeyValueByKey("name"))
            else Vector(documentation(file, t.image, t.image.replace('_', '-'), r))
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
  def configuration(w: DbtWorkspace, file: PsiFile, key: String): Vector[PsiElement] = {
    val generators = Map("schema" -> "generate_schema_name", "alias" -> "generate_alias_name", "database" -> "generate_database_name")
    DbtConfiguration.locations(w, file, key) ++ w.root(file).toVector.flatMap(r => generators.get(key).toVector
      .flatMap(n => w.declarations(Set("macro"), n, r).map(_.element)))
  }
  def materializations(w: DbtWorkspace, r: DbtRoot, name: String): Vector[DbtSymbol] = {
    val declarations = w.declarations(Set("materialization"), name, r)
    val adapter = r.runtimeSetting("adapter").orElse(target(w, r, "type").headOption.collect { case k: YAMLKeyValue => k.getValueText })
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
    if (key == "name") r.runConfiguration.flatMap(m => Option(m.getKeyValueByKey("target"))).orElse(Option(p.getKeyValueByKey("target"))).toVector
    else selected.toVector.flatMap(t => DbtYaml.map(p, "outputs").flatMap(DbtYaml.map(_, t))
      .flatMap(o => Option(o.getKeyValueByKey(key))).toVector)
  }
  def dispatch(w: DbtWorkspace, r: DbtRoot, name: String, namespace: Option[String]): Vector[DbtSymbol] = {
    val order = DbtYaml.items(r.config, "dispatch").find(m => DbtYaml.string(m, "macro_namespace") == namespace)
      .map(DbtYaml.strings(_, "search_order")).getOrElse(namespace.toVector :+ r.name)
    val adapter = r.runtimeSetting("adapter").orElse(target(w, r, "type").headOption.collect { case k: YAMLKeyValue => k.getValueText })
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
