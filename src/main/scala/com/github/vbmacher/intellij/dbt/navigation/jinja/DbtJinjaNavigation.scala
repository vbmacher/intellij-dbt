package com.github.vbmacher.intellij.dbt.navigation.jinja

import com.github.vbmacher.intellij.dbt.navigation.{DbtInfoSchema, DbtNavigationContext, DbtNavigationReference, DbtReferenceProvider, DbtResolution, DbtStaticCalls}
import com.github.vbmacher.intellij.dbt.project.{DbtDeclarations, DbtEnvironment, DbtRoot, DbtSymbol, DbtWorkspace}
import com.github.vbmacher.intellij.dbt.syntax.{DbtSyntax, DbtYaml}
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi._
import org.jetbrains.yaml.psi.{YAMLFile, YAMLScalar}

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

/** Jinja templates of SQL, YAML and Markdown files: locals, macro and builtin calls, and context variables. */
private[jinja] final class DbtJinjaNavigation extends DbtReferenceProvider {
  override def references(context: DbtNavigationContext): Vector[DbtNavigationReference] =
    new DbtJinjaNavigation.FileReferences(context.file, context.workspace, context.root).collect()
}

private object DbtJinjaNavigation {
  private val StaticReference = "static reference"

  private val Resources = DbtSymbol.RefKinds
  private val BuiltinFunctions = Set("ref", "source", "var", "config", "doc", "env_var", "function", "return", "log", "print", "run_query",
    "statement", "load_result", "local_md5", "tojson", "fromjson", "toyaml", "fromyaml", "as_bool", "as_number", "as_native", "zip")
  private val BuiltinNamespaces = Set("adapter", "exceptions", "modules", "flags", "graph", "config")
  private val BuiltinFilters = Set("as_bool", "as_number", "as_native", "tojson", "fromjson", "toyaml", "fromyaml")
  private val ContextVariables = Set("this", "model", "project_name", "adapter", "exceptions", "modules", "graph", "flags", "execute",
    "run_started_at", "invocation_id", "dbt_version", "thread_id", "schema", "schemas", "database", "selected_resources", "results", "sql",
    "compiled_code", "builtins")

  /** Collects the references of one file in a stable order: locals, calls, then context variables. */
  final class FileReferences(file: PsiFile, workspace: DbtWorkspace, r: DbtRoot) {
    private val refs = ArrayBuffer.empty[DbtNavigationReference]
    private val extension = file.getVirtualFile.getExtension
    private val blocks =
      if (file.getName == "dependencies.yml" || extension == "csv" || extension == "py" && r.kind(file.getVirtualFile).contains("model"))
        Vector.empty
      else DbtYamlContexts.blocks(file, r)
    private val locals = file match {
      case _: YAMLFile => blocks.groupBy(b => DbtYamlContexts.scalar(file, b.range.getStartOffset))
        .valuesIterator.flatMap(localReferences(file, _, workspace)).toVector
      case _ => localReferences(file, blocks, workspace)
    }

    def collect(): Vector[DbtNavigationReference] = {
      refs ++= locals
      blocks.flatMap(DbtSyntax.calls).filter(c => DbtYamlContexts.allows(file, r, c.range.getStartOffset, c.name)).foreach(call)
      blocks.foreach(tokens)
      refs.toVector
    }

    private def add(range: TextRange, targets: Iterable[PsiElement], name: String, evidence: Vector[String] = Vector.empty,
      missing: Option[String] = None): Unit =
      if (range.getStartOffset >= 0 && range.getEndOffset <= file.getTextLength && range.getLength > 0)
        refs += new DbtNavigationReference(file, range, targets.toVector.distinct, name,
          if (name == StaticReference) Vector("Expression: " + range.substring(file.getText)) else evidence, missing)

    private def complete(call: DbtSyntax.Call): Boolean = {
      val text = call.block.range.substring(file.getText)
      (text.endsWith("}}") || text.endsWith("%}")) && call.closed
    }

    private def localAt(range: TextRange): Boolean = locals.exists(_.getRangeInElement.intersects(range))

    private def lookup(kinds: Set[String], name: String): Vector[PsiElement] = workspace.declarations(kinds, name, r).map(_.element)

    private def builtin(name: String, page: String): Vector[PsiElement] = DbtResolution.builtin(workspace, r, file, name, page)

    private def first(call: DbtSyntax.Call)(f: (String, TextRange) => Unit): Unit =
      for (name <- call.literal(0); range <- call.argumentRange(0)) f(name, range)

    /** The argument naming a resource, after an optional package argument. */
    private def nameArgument(call: DbtSyntax.Call): Option[TextRange] = call.argumentRange(if (call.literal(1).nonEmpty) 1 else 0)

    private def static(call: DbtSyntax.Call, constants: Map[String, Vector[String]], arguments: Vector[DbtSyntax.Argument]): Unit =
      if (arguments.nonEmpty) {
        val targets = DbtStaticCalls.targets(workspace, r, call, constants)
        val missing = if (complete(call)) DbtStaticCalls.missing(workspace, r, call, constants) else None
        arguments.flatMap(_.range).foreach(range => add(range, targets, StaticReference,
          missing = missing.filter(_._1.range.contains(range)).map(_._2)))
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
      macros.foreach(m => DbtStaticCalls.wrapperTargets(workspace, r, m, call, constants, onMissing = (a, message) =>
        if (complete(call) && macros.size == 1) a.range.foreach(add(_, Vector.empty, "delegated reference", missing = Some(message)))).foreach { case (a, targets) =>
        a.range.foreach(x => add(x, targets, "delegated reference", Vector(s"Wrapper: ${m.namespace}.${m.name}", "Argument: " + x.substring(file.getText))))
      })
      call.keywords.foreach { case (key, a) =>
        a.keywordRange.foreach(add(_, workspace.symbols.filter(s => s.kind == "parameter" && s.owner == call.callee._2 && s.name == key &&
          macros.exists(m => m.namespace == s.namespace && m.file == s.file)).map(_.element), key))
      }
    }

    private def builtinCall(call: DbtSyntax.Call, constants: Map[String, Vector[String]]): Unit = call.name.stripPrefix("builtins.") match {
      case base@("ref" | "function") =>
        static(call, constants, call.positional.filter(_.literal.isEmpty))
        val targets = call.version match {
          case Some(None) => Vector.empty
          case requested => DbtResolution.versioned(workspace.definitions(call, if (base == "ref") Resources else Set("function"), r), requested.flatten)
        }
        val missing = if (base == "ref" && complete(call) && call.positional.forall(_.literal.nonEmpty))
          DbtStaticCalls.missing(workspace, r, call, constants).map(_._2) else None
        nameArgument(call).foreach(add(_, targets.map(_.element), base, missing = missing))
        if (call.literal(1).nonEmpty) call.argumentRange(0).foreach(add(_, workspace.roots.filter(p => call.literal(0).contains(p.name))
          .map(_.config.getContainingFile), "package"))
      case "source" =>
        static(call, constants, dynamic(call))
        first(call) { (source, range) =>
          val sources = lookup(Set("source"), source)
          add(range, sources, source, missing = if (complete(call) && call.literal(1).nonEmpty && sources.isEmpty)
            Some(s"Unresolved source: '$source'") else None)
        }
        for (table <- call.literal(1); range <- call.argumentRange(1)) {
          val targets = call.literal(0).toVector.flatMap(DbtResolution.sourceTables(workspace, r, _, table)).map(_.element)
          val knownSource = call.literal(0).exists(s => lookup(Set("source"), s).nonEmpty)
          add(range, targets, table, missing = if (complete(call) && knownSource && targets.isEmpty)
            Some(s"Unresolved source table: '${call.literal(0).getOrElse("")}.$table'") else None)
        }
      case "var" =>
        val offset = call.range.getStartOffset
        call.positional.take(1).filter(_.literal.isEmpty).foreach(a => a.range.foreach(add(_,
          DbtStaticCalls.value(a.tokens, constants).flatMap(variableTargets(workspace, file, r, offset, _)), StaticReference)))
        first(call)((name, range) => add(range, variableTargets(workspace, file, r, offset, name), name))
      case base@("Dimension" | "TimeDimension" | "Entity" | "Metric") => first(call) { (name, range) =>
        add(range, DbtResolution.semantic(workspace, r, file, range.getStartOffset, base, name).map(_.element), name)
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
      case "env_var" => first(call)((name, range) => add(range, DbtEnvironment(workspace, r, name).map(_.element), name))
      case "config" => call.keywords.foreach { case (key, a) =>
        a.keywordRange.foreach(add(_, DbtResolution.configuration(workspace, file, key), key))
        for (name <- a.literal; range <- a.range) key match {
          case "materialized" => add(range, DbtResolution.materializations(workspace, r, name).map(_.element), name)
          case "group" => add(range, lookup(Set("group"), name), name)
          case "strategy" => add(range, lookup(Set("macro"), s"snapshot_${name}_strategy"), name)
          case _ => ()
        }
      }
      case "config.get" | "config.require" | "dbt.config.get" => first(call)((name, range) => add(range, DbtResolution.configuration(workspace, file, name), name))
      case "adapter.dispatch" => first(call)((name, range) => add(range, DbtResolution.dispatch(workspace, r, name, call.literal(1)).map(_.element), name))
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
        val tested = workspace.enclosing("unit_test", file, t.range.getStartOffset)
          .flatMap(_.mapping.toVector.flatMap(DbtYaml.string(_, "model")))
        workspace.codeSymbols(file).map(_.element) ++ tested.flatMap(lookup(Set("model"), _))
      case "project_name" => DbtYaml.keyValue(r.config, "name").toVector
      case name@("results" | "schemas" | "selected_resources") => builtin(name, "on-run-end-context")
      case name => builtin(name, name.replace('_', '-'))
    }
  }

  private def variableTargets(w: DbtWorkspace, file: PsiFile, root: DbtRoot, offset: Int, name: String): Vector[PsiElement] = {
    val overridden = DbtResolution.overriddenVariable(w, root, name).toVector
    if (DbtYamlContexts.profile(file, root) || root.isFile(file, "packages.yml")) overridden
    else if (DbtYamlContexts.projectConfiguration(file, root, offset) && overridden.isEmpty &&
      (!root.supports(12) || w.rootFile(root, "vars.yml").isEmpty)) Vector.empty
    else variableRoots(w, file, root, offset).flatMap(DbtResolution.variable(w, _, name)).distinct
  }

  /** A package macro uses the namespace of the node that ultimately calls it. */
  private def variableRoots(w: DbtWorkspace, file: PsiFile, fallback: DbtRoot, offset: Int): Vector[DbtRoot] = {
    if (!fallback.kind(file.getVirtualFile).contains("macro")) return Vector(fallback)
    val blocks = DbtSyntax.inFile(file)
    val macros = w.symbols.filter(s => s.kind == "macro" && s.file == file &&
      DbtSyntax.encloses(blocks, s.range.getStartOffset, "endmacro", new TextRange(offset, offset)))
    if (macros.isEmpty) return Vector(fallback)
    val calls = w.files.flatMap { caller =>
      ProgressManager.checkCanceled()
      w.root(caller).toVector.filter(_.resource(caller)).flatMap { root =>
        val bs = DbtYamlContexts.blocks(caller, root)
        val local = localReferences(caller, bs, w)
        val declarations = w.symbols.filter(s => s.kind == "macro" && s.file == caller)
        bs.flatMap(DbtSyntax.calls).filter(c => DbtYamlContexts.allows(caller, root, c.range.getStartOffset, c.name) &&
          !local.exists(_.getRangeInElement.intersects(c.range))).flatMap { call =>
          val owner = declarations.find(s => DbtSyntax.encloses(bs, s.range.getStartOffset, "endmacro", call.range))
          w.macros(call, root).map(target => (target.id, owner.map(_.id), root))
        }
      }
    }
    var frontier = macros.map(_.id).toSet
    val seen = mutable.Set.empty[String]
    val found = mutable.ArrayBuffer.empty[DbtRoot]
    var depth = 0
    while (frontier.nonEmpty && depth < 32) {
      ProgressManager.checkCanceled()
      val incoming = calls.filter(c => frontier(c._1))
      incoming.filter(_._2.isEmpty).foreach(c => found += c._3)
      frontier = incoming.flatMap(_._2).filter(seen.add).toSet
      depth += 1
    }
    val callers = found.toVector.distinct
    if (frontier.nonEmpty) Vector.empty else if (callers.nonEmpty) callers else Vector(fallback)
  }

  /** Lexical bindings over Jinjava tokens. No runtime values or macro execution. */
  private def localReferences(file: PsiFile, blocks: Vector[DbtSyntax.Block], workspace: DbtWorkspace): Vector[DbtNavigationReference] = {
    val result = ArrayBuffer.empty[DbtNavigationReference]
    var scopes = List(Map.empty[String, DbtSymbol])
    var captures = List.empty[DbtSyntax.Token]

    def bind(t: DbtSyntax.Token): Unit = if (!t.string && DbtSyntax.isIdentifier(t.image)) {
      val parameter = workspace.symbols.find(s => s.file == file && s.kind == "parameter" && s.range == t.range)
      scopes = (scopes.head + (t.image -> parameter.getOrElse(DbtSymbol("local", t.image, "jinja", file, t.range)))) :: scopes.tail
    }

    def reference(range: TextRange, name: String): Unit =
      scopes.iterator.flatMap(_.get(name)).take(1).foreach(s => result += new DbtNavigationReference(file, range, Vector(s.element), name))

    blocks.foreach { b =>
      val ts = b.tokens
      val tag = b.tagName.getOrElse("")
      if (DbtSyntax.ScopeEndTags(tag) && scopes.size > 1) scopes = scopes.tail
      if (tag == "endset" && captures.nonEmpty) {
        bind(captures.head);
        captures = captures.tail
      }
      val attributeAssignment = tag == "set" && ts.lift(2).exists(_.image == ".")
      val setTargets = if (tag == "set" && !attributeAssignment) ts.drop(1).takeWhile(t => t.image != "=" && t.image != "|")
        .filter(t => !t.string && DbtSyntax.isIdentifier(t.image)) else Vector.empty
      if (DbtSyntax.ScopeTags(tag)) scopes = Map.empty[String, DbtSymbol] :: scopes
      val withTargets = if (tag == "with") DbtSyntax.assignedNames(ts).map(ts) else Vector.empty
      val loopTargets = if (tag == "for") ts.drop(1).takeWhile(_.image != "in").filter(_.image != ",") else Vector.empty
      val callParameters = if (tag == "call") DbtSyntax.parameters(b) else Vector.empty
      val parameters = if (Set("macro", "test").contains(tag)) DbtSyntax.parameters(b) else Vector.empty
      parameters.foreach(bind)
      val declarationRanges = (parameters ++ setTargets ++ withTargets ++ loopTargets ++ callParameters).map(_.range).toSet
      ts.zipWithIndex.foreach { case (t, i) =>
        val isSetTarget = tag == "set" && i == 1
        if (!t.string && !declarationRanges.contains(t.range) && !isSetTarget && !ts.lift(i - 1).exists(_.image == ".") &&
          !ts.lift(i + 1).exists(_.image == "=")) reference(t.range, t.image)
      }
      // A loop's iterable is evaluated in the outer scope before its targets are bound.
      loopTargets.foreach(bind)
      callParameters.foreach(bind)
      // All with-block values use the outer scope, including same-named bindings.
      withTargets.foreach(bind)
      // Assignment becomes visible after its RHS, preserving outer bindings in `set x = x`.
      if (tag == "set") {
        val attribute = ts.lift(2).exists(_.image == ".") && ts.lift(3).nonEmpty
        if (!attribute && ts.exists(_.image == "=")) setTargets.foreach(bind)
        else if (!attribute) setTargets.headOption.foreach(t => captures = t :: captures)
        if (attribute) {
          val name = ts(1).image + "." + ts(3).image
          val field = DbtSymbol("namespace attribute", name, "jinja", file, ts(3).range)
          // Mutating a namespace does not create a new namespace binding in a loop.
          val owner = scopes.indexWhere(_.contains(ts(1).image))
          if (owner >= 0 && !scopes.exists(_.contains(name))) scopes = scopes.updated(owner, scopes(owner) + (name -> field))
        }
        if (ts.lift(3).exists(_.image == "namespace")) ts.drop(5).sliding(2).foreach {
          case Vector(field, eq) if eq.image == "=" =>
            val name = ts(1).image + "." + field.image
            scopes = (scopes.head + (name -> DbtSymbol("namespace attribute", name, "jinja", file, field.range))) :: scopes.tail
          case _ => ()
        }
      }
      ts.sliding(3).foreach {
        case Vector(namespace, dot, field) if dot.image == "." => reference(field.range, namespace.image + "." + field.image)
        case _ => ()
      }
    }
    result.toVector
  }
}

/** dbt renders these hosts with different contexts, including deferred hooks and descriptions. */
private object DbtYamlContexts {
  private val hooks = Set("pre-hook", "post-hook", "pre_hook", "post_hook", "on-run-start", "on-run-end", "loaded_at_query", "sql")

  def scalar(file: PsiFile, offset: Int): Option[YAMLScalar] = Option(file.findElementAt(offset)).flatMap(e =>
    Iterator.iterate(e)(_.getParent).takeWhile(_ != null).collectFirst { case s: YAMLScalar => s })

  private def context(file: PsiFile, root: DbtRoot, offset: Int): String = {
    if (!file.isInstanceOf[YAMLFile]) return "runtime"
    val keys = scalar(file, offset).map(DbtYaml.ancestors).getOrElse(Vector.empty).map(_.stripPrefix("+"))
    val relative = root.relative(file.getVirtualFile)
    val unsupported = keys.lastOption.flatMap(DbtDeclarations.ResourceKeys.get).exists(!root.supportsKind(_))
    if (unsupported || keys.contains("vars") || relative.contains("dependencies.yml") || relative.contains("package-lock.yml") ||
      relative.contains("vars.yml")) "none"
    else if (relative.contains(root.profilesFile) || relative.contains("packages.yml")) "profile"
    else if (relative.contains("selectors.yml")) if (keys.headOption.contains("default")) "project" else "none"
    else if (relative.contains("dbt_project.yml")) if (keys.exists(hooks)) "runtime" else "project"
    else if (!keys.lastOption.exists(DbtDeclarations.ResourceKeys.contains)) "none"
    else if (keys.contains("meta")) "properties"
    else if (keys.exists(DbtSymbol.TestKeys) && keys.contains("arguments") && !root.supports(10, 5)) "none"
    else if (keys.contains("description") && !keys.exists(Set("meta", "config"))) "description"
    else if (keys.exists(hooks) || keys.exists(DbtSymbol.TestKeys)) "runtime"
    else if (keys.exists(Set("input", "depends_on", "relation", "filter", "where", "group_by", "order_by")) ||
      keys.headOption.contains("model") && keys.exists(Set("semantic_models", "unit_tests"))) "semantic"
    else "properties"
  }

  def blocks(file: PsiFile, root: DbtRoot): Vector[DbtSyntax.Block] = DbtSyntax.inFile(file)
    .filter(b => context(file, root, b.range.getStartOffset) != "none")

  def hostBlocks(file: PsiFile, blocks: Vector[DbtSyntax.Block], offset: Int): Vector[DbtSyntax.Block] =
    if (!file.isInstanceOf[YAMLFile]) blocks else scalar(file, offset).toVector.flatMap(s => blocks.filter(b => s.getTextRange.contains(b.range)))

  def allows(file: PsiFile, root: DbtRoot, offset: Int, name: String): Boolean = {
    val base = name.stripPrefix("builtins.")
    context(file, root, offset) match {
      case "runtime" => base != "doc"
      case "description" => Set("doc", "env_var", "var").contains(base)
      case "semantic" => Set("ref", "source", "function", "var", "env_var", "Dimension", "TimeDimension", "Entity", "Metric", "metric", "this")
        .contains(base)
      case "profile" => Set("env_var", "var").contains(base)
      case "project" => base == "env_var" || base == "var" && root.supports(12) || Set("target", "dbt_version", "builtins").contains(base)
      case "properties" => Set("env_var", "var", "target", "dbt_version").contains(base)
      case _ => false
    }
  }

  def description(file: PsiFile, root: DbtRoot, offset: Int): Boolean = context(file, root, offset) == "description"

  def profile(file: PsiFile, root: DbtRoot): Boolean = root.isFile(file, root.profilesFile)

  def projectConfiguration(file: PsiFile, root: DbtRoot, offset: Int): Boolean =
    root.isFile(file, DbtWorkspace.ProjectFileName) && context(file, root, offset) == "project"
}
