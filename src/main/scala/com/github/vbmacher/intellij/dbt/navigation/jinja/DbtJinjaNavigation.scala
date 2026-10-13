package com.github.vbmacher.intellij.dbt.navigation.jinja

import com.github.vbmacher.intellij.dbt.navigation.{DbtInfoSchema, DbtNavigationContext, DbtNavigationReference, DbtReferenceProvider, DbtResolution, DbtSemantics, DbtStaticCalls}
import com.github.vbmacher.intellij.dbt.project.{DbtEnvironment, DbtRoot, DbtSymbol, DbtWorkspace}
import com.github.vbmacher.intellij.dbt.syntax.{DbtSyntax, DbtYaml}
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi._
import org.jetbrains.yaml.psi.YAMLFile

import scala.collection.mutable.ArrayBuffer

/** Jinja templates of SQL, YAML and Markdown files: locals, macro and builtin calls, and context variables. */
private[jinja] final class DbtJinjaNavigation extends DbtReferenceProvider {
  override def references(context: DbtNavigationContext): Vector[DbtNavigationReference] =
    new DbtJinjaNavigation.FileReferences(context.file, context.workspace, context.root).collect()
}

private object DbtJinjaNavigation {
  private val StaticReference = "static reference"

  private val Resources = Set("model", "seed", "snapshot")
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
        .valuesIterator.flatMap(DbtLocals.references(file, _, workspace)).toVector
      case _ => DbtLocals.references(file, blocks, workspace)
    }

    def collect(): Vector[DbtNavigationReference] = {
      refs ++= locals
      blocks.flatMap(DbtSyntax.calls).filter(c => DbtYamlContexts.allows(file, r, c.range.getStartOffset, c.name)).foreach(call)
      blocks.foreach(tokens)
      refs.toVector
    }

    private def add(range: TextRange, targets: Iterable[PsiElement], name: String, evidence: Vector[String] = Vector.empty): Unit =
      if (range.getStartOffset >= 0 && range.getEndOffset <= file.getTextLength && range.getLength > 0)
        refs += new DbtNavigationReference(file, range, targets.toVector.distinct, name,
          if (name == StaticReference) Vector("Expression: " + range.substring(file.getText)) else evidence)

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
          case requested => DbtResolution.versioned(workspace.definitions(call, if (base == "ref") Resources else Set("function"), r), requested.flatten)
        }
        nameArgument(call).foreach(add(_, targets.map(_.element), base))
        if (call.literal(1).nonEmpty) call.argumentRange(0).foreach(add(_, workspace.roots.filter(p => call.literal(0).contains(p.name))
          .map(_.config.getContainingFile), "package"))
      case "source" =>
        static(call, constants, dynamic(call))
        first(call)((source, range) => add(range, lookup(Set("source"), source), source))
        for (table <- call.literal(1); range <- call.argumentRange(1))
          add(range, call.literal(0).toVector.flatMap(DbtResolution.sourceTables(workspace, r, _, table)).map(_.element), table)
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
      case "env_var" => first(call)((name, range) => add(range, DbtEnvironment(r, name).map(_.element), name))
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
        val tested = workspace.symbols.filter(s => s.kind == "unit_test" && s.file == file && s.mapping.exists(_.getTextRange.containsOffset(t.range.getStartOffset)))
          .flatMap(_.mapping.toVector.flatMap(DbtYaml.string(_, "model")))
        workspace.codeSymbols(file).map(_.element) ++ tested.flatMap(lookup(Set("model"), _))
      case "project_name" => DbtYaml.keyValue(r.config, "name").toVector
      case name @ ("results" | "schemas" | "selected_resources") => builtin(name, "on-run-end-context")
      case name => builtin(name, name.replace('_', '-'))
    }
  }

  private def variableTargets(w: DbtWorkspace, file: PsiFile, root: DbtRoot, offset: Int, name: String): Vector[PsiElement] = {
    val overridden = DbtResolution.overriddenVariable(root, name).toVector
    if (DbtYamlContexts.profile(file, root) || root.isFile(file, "packages.yml")) overridden
    else if (DbtYamlContexts.projectConfiguration(file, root, offset) && overridden.isEmpty &&
      (!root.supports(12) || w.rootFile(root, "vars.yml").isEmpty)) Vector.empty
    else DbtVariableContexts.roots(w, file, root, Some(offset)).flatMap(DbtResolution.variable(w, _, name)).distinct
  }
}
