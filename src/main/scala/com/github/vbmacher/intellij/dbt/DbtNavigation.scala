package com.github.vbmacher.intellij.dbt

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.{Key, TextRange}
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi._
import com.intellij.psi.util.{CachedValue, CachedValueProvider, CachedValuesManager, PsiModificationTracker}
import org.jetbrains.yaml.psi._

import java.util.regex.Pattern
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
      case (a: DbtSymbolElement, b: DbtSymbolElement) if b.symbol.isProperty && !a.symbol.isProperty =>
        a.isValid && b.isValid && DbtWorkspace.get(a.getProject).properties(a.symbol).exists(p =>
          p.file == b.getContainingFile && p.kind == b.symbol.kind && p.name == b.symbol.name &&
            p.version == b.symbol.version && p.element.getTextRange == b.getTextRange)
      case (a: DbtSymbolElement, b: DbtSymbolElement) => a.isValid && b.isValid && a.getContainingFile == b.getContainingFile &&
        (a.getTextRange == b.getTextRange || (a.symbol.isCode && b.symbol.isCode)) && a.symbol.kind == b.symbol.kind && a.symbol.name == b.symbol.name &&
        a.symbol.namespace == b.symbol.namespace && a.symbol.owner == b.symbol.owner && a.symbol.version == b.symbol.version
      case _ => candidate == element || candidate.getManager.areElementsEquivalent(candidate, element)
    }
  }
}

private[dbt] object DbtNavigation {
  private val StaticReference = "static reference"

  private val Resources = Set("model", "seed", "snapshot")
  private val ProjectFiles = Set(DbtWorkspace.ProjectFileName, "profiles.yml", "packages.yml", "package-lock.yml", "dependencies.yml",
    "selectors.yml", "vars.yml")
  private val BuiltinFunctions = Set("ref", "source", "var", "config", "doc", "env_var", "function", "return", "log", "print", "run_query",
    "statement", "load_result", "local_md5", "tojson", "fromjson", "toyaml", "fromyaml", "as_bool", "as_number", "as_native", "zip")
  private val BuiltinNamespaces = Set("adapter", "exceptions", "modules", "flags", "graph", "config")
  private val BuiltinFilters = Set("as_bool", "as_number", "as_native", "tojson", "fromjson", "toyaml", "fromyaml")
  private val ContextVariables = Set("this", "model", "project_name", "adapter", "exceptions", "modules", "graph", "flags", "execute",
    "run_started_at", "invocation_id", "dbt_version", "thread_id", "schema", "schemas", "database", "selected_resources", "results", "sql",
    "compiled_code", "builtins")
  private val ReferenceCache = Key.create[CachedValue[Vector[DbtNavigationReference]]]("dbt.navigation.references")

  def inFile(file: PsiFile, includeSelections: Boolean = true): Vector[DbtNavigationReference] =
    if (!file.isPhysical || file.getVirtualFile == null) Vector.empty
    else if (!includeSelections) compute(file, includeSelections = false)
    else CachedValuesManager.getManager(file.getProject).getCachedValue(file, ReferenceCache,
      () => CachedValueProvider.Result.create(compute(file, includeSelections = true),
        PsiModificationTracker.MODIFICATION_COUNT, VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS), false)

  /** The innermost references at an offset. */
  def at(file: PsiFile, offset: Int): Vector[DbtNavigationReference] = {
    val matches = inFile(file).filter(_.getRangeInElement.containsOffset(offset))
    matches.map(_.getRangeInElement.getLength).minOption.toVector.flatMap(size => matches.filter(_.getRangeInElement.getLength == size))
  }

  private def compute(file: PsiFile, includeSelections: Boolean): Vector[DbtNavigationReference] = {
    val workspace = DbtWorkspace.get(file.getProject)
    workspace.root(file).filter(navigable(file, _))
      .fold(Vector.empty[DbtNavigationReference])(new FileReferences(file, workspace, _).collect(includeSelections))
  }

  private def navigable(file: PsiFile, r: DbtRoot): Boolean = !r.excluded(file.getVirtualFile) && (r.resource(file) ||
    r.isSelectionFile(file) || ProjectFiles(file.getName) || DbtYamlContexts.profile(file, r))

  /** Collects the references of one file in a stable order: locals, calls, context variables, then other languages. */
  private final class FileReferences(file: PsiFile, workspace: DbtWorkspace, r: DbtRoot) {
    private val refs = ArrayBuffer.empty[DbtNavigationReference]
    private val extension = file.getVirtualFile.getExtension
    private val blocks =
      if (file.getName == "dependencies.yml" || extension == "csv" || extension == "py" && r.kind(file.getVirtualFile).contains("model"))
        Vector.empty
      else DbtYamlContexts.blocks(file, r)
    private val locals = file match {
      case _: YAMLFile => blocks.groupBy(b => DbtYamlContexts.scalar(file, b.range.getStartOffset))
        .valuesIterator.flatMap(DbtLocals.references(file, _, workspace)).toVector
      case _ => DbtLocals.references(file, blocks, workspace)
    }

    def collect(includeSelections: Boolean): Vector[DbtNavigationReference] = {
      refs ++= locals
      blocks.flatMap(DbtSyntax.calls).filter(c => DbtYamlContexts.allows(file, r, c.range.getStartOffset, c.name)).foreach(call)
      blocks.foreach(tokens)
      refs ++= DbtYamlNavigation.references(file, workspace, r)
      refs ++= DbtSelectors.references(file, workspace, r, includeSelections)
      refs ++= DbtPythonNavigation.references(file, workspace, r)
      refs ++= DbtSqlNavigation.references(file, workspace, r, refs.toVector)
      refs ++= DbtFunctionParameters.references(file, workspace)
      refs.toVector.groupBy(r => (r.getRangeInElement, r.candidates)).valuesIterator.map(_.head).toVector
        .sortBy(_.getRangeInElement.getStartOffset)
    }

    private def add(range: TextRange, targets: Iterable[PsiElement], name: String, evidence: Vector[String] = Vector.empty): Unit =
      if (range.getStartOffset >= 0 && range.getEndOffset <= file.getTextLength && range.getLength > 0)
        refs += new DbtNavigationReference(file, range, targets.toVector.distinct, name,
          if (name == StaticReference) Vector("Expression: " + range.substring(file.getText)) else evidence)

    private def localAt(range: TextRange): Boolean = locals.exists(_.getRangeInElement.intersects(range))

    private def lookup(kinds: Set[String], name: String): Vector[PsiElement] = workspace.declarations(kinds, name, r).map(_.element)

    private def builtin(name: String, page: String): Vector[PsiElement] = DbtNavigation.builtin(workspace, r, file, name, page)

    private def first(call: DbtSyntax.Call)(f: (String, TextRange) => Unit): Unit =
      for (name <- call.literal(0); range <- call.argumentRange(0)) f(name, range)

    /** The argument naming a resource, after an optional package argument. */
    private def nameArgument(call: DbtSyntax.Call): Option[TextRange] = call.argumentRange(if (call.literal(1).nonEmpty) 1 else 0)

    private def static(call: DbtSyntax.Call, constants: Map[String, Vector[String]], arguments: Vector[DbtSyntax.Argument]): Unit =
      if (arguments.nonEmpty) {
        val targets = DbtStaticCalls.targets(workspace, r, call, constants)
        arguments.flatMap(_.range).foreach(add(_, targets, StaticReference))
      }

    /** All positional arguments are dynamic when any of them is. */
    private def dynamic(call: DbtSyntax.Call): Vector[DbtSyntax.Argument] =
      if (call.positional.exists(_.literal.isEmpty)) call.positional else Vector.empty

    private def call(call: DbtSyntax.Call): Unit = if (!localAt(call.range)) {
      ProgressManager.checkCanceled()
      val constants = DbtStaticCalls.constants(DbtYamlContexts.hostBlocks(file, blocks, call.range.getStartOffset), call.block.range.getStartOffset)
      val macros = if (call.name.startsWith("builtins.")) Vector.empty else workspace.macros(call, r)
      if (macros.nonEmpty) macroCall(call, macros, constants)
      else {
        builtinCall(call, constants)
        val base = call.name.stripPrefix("builtins.")
        if (BuiltinFunctions(base)) add(call.range, builtin(base, base.replace('_', '-')), base)
        else call.callee._1.filter(BuiltinNamespaces).foreach(namespace => add(call.range, builtin(call.name, namespace), call.name))
      }
    }

    private def macroCall(call: DbtSyntax.Call, macros: Vector[DbtSymbol], constants: Map[String, Vector[String]]): Unit = {
      add(call.range, macros.map(_.element), call.name)
      macros.foreach(m => DbtStaticCalls.wrapperTargets(workspace, r, m, call, constants).foreach { case (a, targets) =>
        a.range.foreach(x => add(x, targets, "delegated reference", Vector(s"Wrapper: ${m.namespace}.${m.name}", "Argument: " + x.substring(file.getText))))
      })
      call.keywords.foreach { case (key, a) =>
        a.keywordRange.foreach(add(_, workspace.symbols.filter(s => s.kind == "parameter" && s.owner == call.callee._2 && s.name == key &&
          macros.exists(m => m.namespace == s.namespace && m.file == s.file)).map(_.element), key))
      }
    }

    private def builtinCall(call: DbtSyntax.Call, constants: Map[String, Vector[String]]): Unit = call.name.stripPrefix("builtins.") match {
      case base @ ("ref" | "function") =>
        static(call, constants, call.positional.filter(_.literal.isEmpty))
        val targets = call.version match {
          case Some(None) => Vector.empty
          case requested => versioned(workspace.definitions(call, if (base == "ref") Resources else Set("function"), r), requested.flatten)
        }
        nameArgument(call).foreach(add(_, targets.map(_.element), base))
        if (call.literal(1).nonEmpty) call.argumentRange(0).foreach(add(_, workspace.roots.filter(p => call.literal(0).contains(p.name))
          .map(_.config.getContainingFile), "package"))
      case "source" =>
        static(call, constants, dynamic(call))
        first(call)((source, range) => add(range, lookup(Set("source"), source), source))
        for (table <- call.literal(1); range <- call.argumentRange(1))
          add(range, call.literal(0).toVector.flatMap(sourceTables(workspace, r, _, table)).map(_.element), table)
      case "var" =>
        val offset = call.range.getStartOffset
        call.positional.take(1).filter(_.literal.isEmpty).foreach(a => a.range.foreach(add(_,
          DbtStaticCalls.value(a.tokens, constants).flatMap(variableTargets(workspace, file, r, offset, _)), StaticReference)))
        first(call)((name, range) => add(range, variableTargets(workspace, file, r, offset, name), name))
      case base @ ("Dimension" | "TimeDimension" | "Entity" | "Metric") => first(call) { (name, range) =>
        add(range, DbtSemantics.targets(workspace, r, file, range.getStartOffset, base, name).map(_.element), name)
        if (name.contains("__") && range.substring(file.getText) == name) {
          var start = range.getStartOffset
          name.split("__").dropRight(1).foreach { entity =>
            add(new TextRange(start, start + entity.length), lookup(Set("entity"), entity), entity)
            start += entity.length + 2
          }
        }
      }
      case "metric" => first(call)((name, range) => add(range, lookup(Set("metric"), name), name))
      case "doc" if call.argumentRange(0).exists(x => DbtYamlContexts.description(file, r, x.getStartOffset)) =>
        static(call, constants, dynamic(call))
        nameArgument(call).foreach(add(_, workspace.definitions(call, Set("doc"), r).map(_.element), "doc"))
      case "env_var" => first(call)((name, range) => add(range, environment(r, name).map(_.element), name))
      case "config" => call.keywords.foreach { case (key, a) =>
        a.keywordRange.foreach(add(_, configuration(workspace, file, key), key))
        for (name <- a.literal; range <- a.range) key match {
          case "materialized" => add(range, materializations(workspace, r, name).map(_.element), name)
          case "group" => add(range, lookup(Set("group"), name), name)
          case "strategy" => add(range, lookup(Set("macro"), s"snapshot_${name}_strategy"), name)
          case _ => ()
        }
      }
      case "config.get" | "config.require" | "dbt.config.get" => first(call)((name, range) => add(range, configuration(workspace, file, name), name))
      case "adapter.dispatch" => first(call)((name, range) => add(range, dispatch(workspace, r, name, call.literal(1)).map(_.element), name))
      case "load_result" => first(call) { (name, range) =>
        val statements = blocks.flatMap(DbtSyntax.calls).filter(c => c.name == "statement" && c.literal(0).contains(name))
        add(range, statements.flatMap(_.argumentRange(0)).map(DbtSymbol("result", name, r.name, file, _).element), name)
      }
      case "info_schema" if DbtInfoSchema.enabled(r, file) =>
        first(call)((name, range) => if (DbtInfoSchema.views.contains(name)) add(range, DbtInfoSchema.view(file, r, name), name))
      case _ => ()
    }

    private def tokens(b: DbtSyntax.Block): Unit = {
      b.tokens.sliding(3).foreach {
        case Vector(a, dot, field) if dot.image == "." && !a.string && !field.string && a.image == "target" &&
          !localAt(a.range) && DbtYamlContexts.allows(file, r, a.range.getStartOffset, "target") =>
          add(field.range, r.target(field.image), field.image)
        case _ => ()
      }
      b.tokens.sliding(2).foreach {
        case Vector(pipe, filter) if pipe.image == "|" && !filter.string && BuiltinFilters(filter.image) =>
          add(filter.range, builtin(filter.image, filter.image.replace('_', '-')), filter.image)
        case _ => ()
      }
      val attributes = b.tokens.sliding(2).collect { case Vector(dot, field) if dot.image == "." => field.range }.toSet
      b.tokens.filter(t => !t.string && !attributes(t.range) && ContextVariables(t.image))
        .filterNot(t => localAt(t.range) || refs.exists(_.getRangeInElement.contains(t.range)))
        .filter(t => DbtYamlContexts.allows(file, r, t.range.getStartOffset, t.image))
        .foreach(t => add(t.range, contextTargets(t), t.image))
    }

    private def contextTargets(t: DbtSyntax.Token): Vector[PsiElement] = t.image match {
      case "this" | "model" =>
        val tested = workspace.symbols.filter(s => s.kind == "unit_test" && s.file == file && s.mapping.exists(_.getTextRange.containsOffset(t.range.getStartOffset)))
          .flatMap(_.mapping.toVector.flatMap(DbtYaml.string(_, "model")))
        workspace.codeSymbols(file).map(_.element) ++ tested.flatMap(lookup(Set("model"), _))
      case "project_name" => DbtYaml.keyValue(r.config, "name").toVector
      case name @ ("results" | "schemas" | "selected_resources") => builtin(name, "on-run-end-context")
      case name => builtin(name, name.replace('_', '-'))
    }
  }

  /** Versioned declarations: the requested version, or the single latest version, or the unversioned ones. */
  def versioned(defs: Vector[DbtSymbol], requested: Option[String]): Vector[DbtSymbol] = {
    val latest = defs.flatMap(_.attributes.get("latest_version")).distinct
    val selected = requested.orElse(if (latest.size == 1) latest.headOption else None)
    defs.filter(s => selected.fold(s.version.isEmpty)(v => s.version.contains(v)))
  }

  private def varsIn(m: YAMLMapping, project: String, name: String): Option[YAMLKeyValue] =
    DbtYaml.map(m, project).flatMap(DbtYaml.keyValue(_, name)).orElse(DbtYaml.keyValue(m, name))

  private def overriddenVariable(r: DbtRoot, name: String): Option[YAMLKeyValue] =
    r.runConfiguration.flatMap(DbtYaml.map(_, "vars")).flatMap(DbtYaml.keyValue(_, name))

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

  private def variableTargets(w: DbtWorkspace, file: PsiFile, root: DbtRoot, offset: Int, name: String): Vector[PsiElement] = {
    val overridden = overriddenVariable(root, name).toVector
    if (DbtYamlContexts.profile(file, root) || root.isFile(file, "packages.yml")) overridden
    else if (DbtYamlContexts.projectConfiguration(file, root, offset) && overridden.isEmpty &&
      (!root.supports(12) || w.rootFile(root, "vars.yml").isEmpty)) Vector.empty
    else DbtVariableContexts.roots(w, file, root, Some(offset)).flatMap(variable(w, _, name)).distinct
  }

  private def environmentOverrides(r: DbtRoot): Vector[DbtSymbol] =
    r.runConfiguration.flatMap(DbtYaml.map(_, "env")).toVector.flatMap(_.getKeyValues.asScala)
      .flatMap(k => Option(k.getKey).map(e => DbtSymbol("environment", k.getKeyText, r.name, e.getContainingFile, e.getTextRange)))

  private def environmentFiles(r: DbtRoot, namePattern: String): Vector[DbtSymbol] = r.envFiles.flatMap { file =>
    s"(?m)^\\s*(?:export\\s+)?($namePattern)\\s*=".r.findAllMatchIn(file.getText)
      .map(m => DbtSymbol("environment", m.group(1), r.name, file, new TextRange(m.start(1), m.end(1))))
  }

  /** Run configuration overrides of an environment variable take precedence over `.env` files. */
  def environment(r: DbtRoot, name: String): Vector[DbtSymbol] = {
    val overrides = environmentOverrides(r).filter(_.name == name).take(1)
    if (overrides.nonEmpty) overrides else environmentFiles(r, Pattern.quote(name))
  }

  def environmentDeclarations(w: DbtWorkspace): Vector[DbtSymbol] =
    w.roots.flatMap(r => environmentOverrides(r) ++ environmentFiles(r, "[A-Za-z_][A-Za-z_0-9]*"))

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

  private def builtin(w: DbtWorkspace, root: DbtRoot, host: PsiFile, name: String, page: String): Vector[PsiElement] = {
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
