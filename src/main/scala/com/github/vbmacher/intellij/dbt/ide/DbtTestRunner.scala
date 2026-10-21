package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.lineage.DbtLineage
import com.github.vbmacher.intellij.dbt.project._
import com.github.vbmacher.intellij.dbt.syntax.DbtYaml
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.lineMarker.{ExecutorAction, RunLineMarkerContributor}
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.testframework.actions.AbstractRerunFailedTestsAction
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil
import com.intellij.execution.testframework.sm.runner.{SMTestLocator, SMTestProxy, SMTRunnerConsoleProperties}
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.execution.ui.{ConsoleView, ConsoleViewContentType}
import com.intellij.execution.{Executor, Location, PsiLocation}
import com.intellij.icons.AllIcons
import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.{Disposer, Key}
import com.intellij.openapi.vfs.{VirtualFile, VirtualFileManager}
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.{CachedValue, CachedValueProvider, CachedValuesManager, PsiModificationTracker}
import com.intellij.psi.{PsiElement, PsiFile, PsiManager}
import javax.swing.Icon
import org.jetbrains.yaml.psi.{YAMLMapping, YAMLScalar, YAMLSequence}
import scala.jdk.CollectionConverters._

private[ide] final case class DbtTestDefinition(id: String, name: String, kind: String, group: String,
  root: String, file: VirtualFile, offset: Int, selector: String, dependencies: Vector[String],
  fixtures: Vector[(VirtualFile, Int)] = Vector.empty, relation: String = "", provenance: String = "Source declaration", packageName: String = "", projectName: String = "", description: String = "")

/** Source definitions remain browsable offline. Manifest identities enrich generated generic tests without guessing their hashes. */
private[ide] object DbtTestDiscovery {
  import DbtExecutionEvidence._
  def discover(w: DbtWorkspace, executionOptions: Map[String, DbtRunOptions] = Map.empty): Vector[DbtTestDefinition] = {
    lazy val sourceDependencies = DbtLineage.edges(w).filter(e => !e.ambiguous && DbtSymbol.RelationKinds(e.to.kind))
      .groupMap(_.from.id)(_.to.uniqueId)
    val source = w.symbols.filter(s => Set("test", "unit_test")(s.kind) && !s.metadata && s.isEnabled && !w.symbols.exists(g => g.kind == "generic_test" && g.file == s.file)).flatMap { s =>
      w.owner(s).map { owner =>
        val root = w.executionRoot(owner)
        val group = s.mapping.flatMap(DbtYaml.string(_, "model")).getOrElse("Data tests")
        val fixtures = s.mapping.toVector.flatMap(m => DbtYaml.items(m, "given") ++ DbtYaml.map(m, "expect").toVector).flatMap { m =>
          DbtYaml.string(m, "fixture").toVector.flatMap(name => w.symbols.filter(f => f.kind == "fixture" && f.name == name && w.owner(f).contains(owner))
            .map(f => f.file.getVirtualFile -> f.range.getStartOffset))
        }
        val fqn = Vector(s.namespace) ++ owner.resourceFolders(s.file.getVirtualFile).toVector.flatMap(_._2) ++ Vector(s.name)
        val dependencies = if (s.kind == "unit_test") w.declarations(Set("model"), group.split("\\.").last, owner,
          if (group.contains(".")) group.split("\\.").headOption else None).map(_.uniqueId)
          else (DbtResourceData.get(w, s).dependencies ++ sourceDependencies.getOrElse(s.id, Vector.empty)).distinct
        DbtTestDefinition(s.uniqueId, s.presentableText, if (s.kind == "unit_test") "Unit tests" else "Data tests", group,
          root.directory.getPath, s.file.getVirtualFile, s.range.getStartOffset,
          if (s.kind == "unit_test") "fqn:" + (Vector(s.namespace) ++ owner.resourceFolders(s.file.getVirtualFile).toVector.flatMap(_._2) ++ Vector(group, s.name)).mkString(".") + ",test_type:unit" else "fqn:" + fqn.mkString("."), dependencies, fixtures, packageName = owner.name, projectName = root.name, description = DbtResourceData.get(w, s).description)
      }
    }
    val attached = w.symbols.filter(s => s.mapping.nonEmpty && (DbtSymbol.RefKinds(s.kind) || s.kind == "column" || s.kind == "source_table")).flatMap { s =>
      w.owner(s).toVector.flatMap { owner => s.mapping.toVector.flatMap { m =>
        Vector("tests", "data_tests").flatMap(k => DbtYaml.value(m, k).toVector.collect { case seq: YAMLSequence => seq }.flatMap(_.getItems.asScala)).flatMap { item =>
          val value = Option(item.getValue)
          val declaration = value.flatMap {
            case scalar: YAMLScalar => Some((scalar.getTextValue, scalar.getTextOffset, Option.empty[YAMLMapping]))
            case mapping: YAMLMapping => mapping.getKeyValues.asScala.headOption.map(k => (k.getKeyText, k.getTextOffset, Option(k.getValue).collect { case m: YAMLMapping => m }))
            case _ => None
          }
          declaration.map { case (name, offset, config) =>
            val customName = config.flatMap(DbtYaml.string(_, "name"))
            val resource = if (s.kind == "column") w.symbols.find(r => DbtSymbol.RelationKinds(r.kind) && r.relationName == s.owner &&
              r.namespace == s.namespace && r.version == s.version && w.owner(r).contains(owner)) else Some(s)
            DbtTestDefinition("declaration:" + s.id + ":" + offset, customName.getOrElse(name) + (if (s.kind == "column") " (" + s.name + ")" else ""),
              "Data tests", resource.map(_.presentableText).getOrElse(s.owner), w.executionRoot(owner).directory.getPath,
              s.file.getVirtualFile, offset, customName.map(n => "fqn:" + s.namespace + "." + n).getOrElse(""), resource.map(_.uniqueId).toVector, packageName = owner.name, projectName = w.executionRoot(owner).name)
          }
        }
      }}
    }.distinctBy(t => (t.root, t.file.getPath, t.offset, t.dependencies))
    val declarations = source ++ attached
    val manifest = w.roots.map(w.executionRoot).distinct.map(r => executionOptions.get(r.directory.getPath).fold(r)(r.withRunOptions)).flatMap { root =>
      root.setting("manifest").flatMap(p => DbtManifestReader.get(w.project)
        .read(root, p, DbtManifestPolicy.TestDiscovery).toOption).toVector.flatMap { top =>
        top.in("nodes", "unit_tests").map(_.data).filter(n => Set("test", "unit_test")(string(n, "resource_type")) && string(obj(n, "config"), "enabled") != "false").flatMap { n =>
          ProgressManager.checkCanceled()
          val id = string(n, "unique_id"); val name = string(n, "name"); val pkg = string(n, "package_name")
          val path = string(n, "original_file_path")
          val deps = Option(obj(n, "depends_on").get("nodes")).filter(_.isJsonArray).toVector.flatMap(_.getAsJsonArray.asScala.map(_.getAsString))
          val fqn = Option(n.get("fqn")).filter(_.isJsonArray).toVector.flatMap(_.getAsJsonArray.asScala.map(_.getAsString)).mkString(".")
          val matching = declarations.filter(t => t.root == root.directory.getPath &&
            (t.id == id || t.file.getPath.endsWith("/" + path) && t.dependencies.exists(deps.contains) &&
              (t.name.takeWhile(_ != ' ').split("\\.").last == name || t.name.takeWhile(_ != ' ') == string(obj(n, "test_metadata"), "name")) &&
              (string(n, "column_name").isEmpty || t.name.endsWith("(" + string(n, "column_name") + ")"))))
          val owner = w.roots.find(r => r.name == pkg && w.executionRoot(r) == root)
          val ignored = owner.exists(r => r.psiFile(path).exists(f => r.excluded(f.getVirtualFile)))
          val file = if (ignored) None else (if (matching.size == 1) matching.headOption.map(_.file) else None)
            .orElse(owner.flatMap(_.psiFile(path)).map(_.getVirtualFile))
            .orElse(root.setting("manifest").flatMap(root.psiFile).map(_.getVirtualFile))
          file.map { f =>
            val exact = matching.size == 1
            DbtTestDefinition(id, s"$pkg.$name", if (string(n, "resource_type") == "unit_test") "Unit tests" else "Data tests",
              deps.filter(_.startsWith("model.")).mkString(", ") match { case "" => "Data tests"; case g => g },
              root.directory.getPath, f, if (exact) matching.head.offset else 0,
              if (fqn.nonEmpty) "fqn:" + fqn + ",resource_type:" + string(n, "resource_type") else "", deps,
              if (exact) matching.head.fixtures else Vector.empty, string(n, "relation_name"),
              if (exact) "Source definition + manifest identity" else "Manifest identity; source range unavailable", pkg, root.name, string(n, "description"))
          }
        }
      }
    }
    val superseded = manifest.flatMap(t => declarations.filter(s => s.root == t.root && s.file == t.file && s.offset == t.offset && s.dependencies.exists(t.dependencies.contains)).map(_.id)).toSet
    (declarations.filterNot(s => superseded(s.id)) ++ manifest).distinctBy(t => (t.root, t.id)).sortBy(t => (t.root, t.group, t.kind, t.name, t.id))
  }

  def preview(command: DbtCommand, relation: String, limit: Int): Either[String, DbtCommand] = {
    if (relation.trim.isEmpty) Left("No stored failing rows are available. Configure store_failures and run this test.")
    else Right(command.copy(request = DbtCommandRequest(DbtCommandAction.Preview,
      DbtCommandScope.Inline("select * from " + relation), limit = DbtCommandRequest.limit(limit)), colors = false,
      sourceSql = "", previewSource = None))
  }

  /** Preserve the exact executable, environment, profiles and target from the original run. */
  def rerun(command: DbtCommand, tests: Vector[DbtTestDefinition], results: Vector[DbtExecutionResult]): Either[String, DbtCommand] = {
    val failed = results.filter(r => Set("failed", "error")(r.status)).map(_.id).toSet
    val intended = tests.filter(t => failed(t.id))
    select(command, intended)
  }

  /** Exact test scope replaces prior selectors while retaining target, profiles and execution flags. */
  def select(command: DbtCommand, intended: Vector[DbtTestDefinition]): Either[String, DbtCommand] = {
    val root = command.executionRoot
    if (intended.isEmpty) Left("No tests in this scope.")
    else if (intended.exists(_.root != root)) Left("Choose tests from one execution project.")
    else if (intended.exists(_.selector.isEmpty)) Left("Exact test identities unavailable. Generate Docs for this project and target, then refresh tests.")
    else {
      Right(command.copy(request = command.request.copy(scope = DbtCommandScope.Selected(intended.map(_.selector).distinct),
        exclude = Vector.empty, indirectSelection = Some("empty"))))
    }
  }
}

/** Adapts the managed dbt process to the IDE Run tool window and its test actions. */
private[ide] final class DbtTestConsole(configuration: DbtRunConfiguration, executor: Executor) {
  private val project = configuration.getProject
  private val runner = project.getService(classOf[DbtRunner])
  private var observing = false
  private var canceled = false
  private var command = Option.empty[DbtCommand]
  private var definitions = Vector.empty[DbtTestDefinition]
  private var started = Set.empty[String]
  private var finished = Set.empty[String]
  val process = new DbtManagedProcess(p => { canceled = true; if (observing) runner.stop() else p.complete(130) })
  private[ide] val properties = new SMTRunnerConsoleProperties(configuration, "dbt", executor) {
    setIdBasedTestTree(true)
    override def getTestLocator: SMTestLocator = DbtTestConsole.Locator
    override def createRerunFailedTestsAction(console: ConsoleView): AbstractRerunFailedTestsAction = {
      val action = new AbstractRerunFailedTestsAction(console) {
        override def getRunProfile(environment: ExecutionEnvironment): AbstractRerunFailedTestsAction.MyRunProfile = {
          val failed = getFailedTests(project).asScala.collect { case proxy: SMTestProxy if proxy.isLeaf => proxy.getMetainfo }.toSet
          val selected = definitions.filter(t => failed(t.id))
          val rerun = configuration.clone().asInstanceOf[DbtRunConfiguration]
          rerun.selectors = selected.map(_.selector).distinct
          rerun.action = "Test"; rerun.fullRefresh = false; rerun.exactTests = true; rerun.namedSelector = ""
          // An unavailable identity must fail closed rather than rerunning the entire project.
          if (selected.isEmpty || selected.exists(_.selector.isEmpty) || failed.exists(id => !selected.exists(_.id == id))) rerun.selectors = Vector.empty
          rerun.captured = command.map(c => c.copy(request = c.request.copy(action = DbtCommandAction.Test, fullRefresh = false)))
          new AbstractRerunFailedTestsAction.MyRunProfile(rerun) {
            override def getModules: Array[Module] = Array.empty
            override def getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState = rerun.getState(executor, environment)
          }
        }
      }
      action.init(this)
      console match {
        case native: SMTRunnerConsoleView => action.setModel(native.getResultsViewer)
        case _ => ()
      }
      action
    }
  }
  val view = SMTestRunnerConnectionUtil.createConsole(properties)
  view.attachToProcess(process)
  process.startNotify()
  runner.listen(view, event => {
    if (observing) {
      event.execution.foreach(update)
      if (!event.running) {
        observing = false
        if (event.execution.isEmpty || event.exitCode.exists(_ != 0) && !canceled)
          message("message", "text" -> event.message, "status" -> "ERROR")
        process.complete(event.exitCode.getOrElse(if (canceled) 130 else 1))
      }
    }
  })
  Disposer.register(view, () => if (!process.isProcessTerminated) process.destroyProcess())

  private def message(kind: String, fields: (String, String)*): Unit = {
    val text = "\n##teamcity[" + kind + fields.map { case (key, value) => s" $key='${DbtTestConsole.escape(value)}'" }.mkString + "]\n"
    process.notifyTextAvailable(text, ProcessOutputTypes.STDOUT)
  }
  def start(value: DbtCommand, tests: Vector[DbtTestDefinition]): Unit = {
    if (!canceled && !process.isProcessTerminated) {
      command = Some(value); definitions = tests
      view.print(value.commandLine + "\n", ConsoleViewContentType.SYSTEM_OUTPUT)
      observing = true
      if (!runner.start(value, process.output)) {
        observing = false; fail("Another dbt command is running. Stop it before starting tests.")
      }
    }
  }
  def showEvidence(execution: DbtExecutionRun, tests: Vector[DbtTestDefinition]): Unit = {
    definitions = tests
    view.print(s"Target: ${execution.target} · Run: ${execution.invocation} · ${execution.timestamp}\n${execution.artifact}\n", ConsoleViewContentType.SYSTEM_OUTPUT)
    val compatible = if (execution.problem.isEmpty) execution else execution.copy(results = Vector.empty)
    compatible.results.filterNot(r => r.id.startsWith("test.") || r.id.startsWith("unit_test.")).foreach { result =>
      message("message", "text" -> s"${result.id}: ${result.status}\n${result.message}")
    }
    update(compatible.copy(active = false))
    process.complete(if (execution.problem.nonEmpty || execution.results.exists(r => Set("failed", "error")(r.status))) 1 else 0)
  }
  def fail(problem: String): Unit = {
    message("message", "text" -> problem, "status" -> "ERROR")
    process.complete(1)
  }
  private[ide] def update(execution: DbtExecutionRun): Unit = {
    execution.results.filter(r => r.id.startsWith("test.") || r.id.startsWith("unit_test.")).foreach { result =>
      val test = definitions.find(_.id == result.id)
      val name = test.map(_.name).getOrElse(result.id)
      if (!started(result.id)) {
        message("testStarted", "name" -> name, "nodeId" -> result.id, "parentNodeId" -> "0",
          "locationHint" -> test.map(DbtTestConsole.url).getOrElse(""), "metainfo" -> result.id)
        started += result.id
      }
      // Final artifacts validate the invocation. Streamed records only establish progress.
      if (!execution.active && !finished(result.id) && !Set("running", "unknown", "canceled")(result.status) &&
        (result.status != "passed" || execution.problem.isEmpty)) {
        val details = result.message + s"\nStatus: ${result.status}; failures: ${result.failures}\n" +
          (if (result.relation.nonEmpty) "Stored failure relation: " + result.relation + "\n" else "")
        message("testStdOut", "name" -> name, "nodeId" -> result.id, "out" -> details)
        result.status match {
          case "failed" | "error" =>
            val fields = Vector("name" -> name, "nodeId" -> result.id, "message" -> result.message, "details" -> details) ++
              (if (result.status == "error") Vector("error" -> "true") else Vector.empty)
            message("testFailed", fields: _*)
          case "warning" | "skipped" => message("testIgnored", "name" -> name, "nodeId" -> result.id,
            "message" -> (result.status.toUpperCase + ": " + result.message))
          case _ => ()
        }
        message("testFinished", "name" -> name, "nodeId" -> result.id, "duration" -> result.duration.toString)
        finished += result.id
      }
    }
    if (!execution.active && execution.problem.nonEmpty)
      message("message", "text" -> execution.problem, "status" -> "ERROR")
  }
}

private[ide] object DbtTestConsole {
  def escape(value: String): String = value.replace("|", "||").replace("'", "|'").replace("\n", "|n")
    .replace("\r", "|r").replace("[", "|[").replace("]", "|]")
  def url(test: DbtTestDefinition): String = "dbt-test://" + test.file.getUrl + "#" + test.offset
  val Locator: SMTestLocator = new SMTestLocator {
    override def getLocation(protocol: String, path: String, project: Project, scope: GlobalSearchScope): java.util.List[Location[_]] = {
      val at = path.lastIndexOf('#')
      (if (protocol != "dbt-test" || at < 0) Vector.empty else for {
        file <- Option(VirtualFileManager.getInstance.findFileByUrl(path.substring(0, at))).toVector
        psi <- Option(PsiManager.getInstance(project).findFile(file)).toVector
        offset <- path.substring(at + 1).toIntOption.toVector
        element <- Option(psi.findElementAt(offset)).toVector
      } yield PsiLocation.fromPsiElement(element): Location[_]).asJava
    }
  }
}

final class DbtTestRunLineMarker extends RunLineMarkerContributor {
  override def getInfo(element: PsiElement): RunLineMarkerContributor.Info = null
  override def getSlowInfo(element: PsiElement): RunLineMarkerContributor.Info = {
    if (element.getFirstChild != null || element.isInstanceOf[PsiFile]) null
    else DbtTestRunLineMarker.testsAt(element).filter(tests => tests.minByOption(_.offset).exists { test =>
      val file = element.getContainingFile
      val offset = if (test.offset == 0) DbtTestRunLineMarker.firstCode(file) else test.offset
      Option(file.findElementAt(offset)).contains(element)
    }).map { tests =>
      val state = DbtTestRunLineMarker.status(element.getContainingFile, tests)
      val icon = state match {
        case "passed" => AllIcons.RunConfigurations.TestState.Green2
        case "failed" | "error" => AllIcons.RunConfigurations.TestState.Red2
        case "warning" | "skipped" => AllIcons.RunConfigurations.TestState.Yellow2
        case _ => AllIcons.RunConfigurations.TestState.Run
      }
      DbtTestRunLineMarker.info(icon, "dbt tests: " + state +
        (if (tests.exists(_.selector.isEmpty)) ". Exact identity unavailable; Generate Docs for this project and target." else ". Run tests"))
    }.orNull
  }
}

private[ide] object DbtTestRunLineMarker {
  def firstCode(file: PsiFile): Int = file.getText.indexWhere(c => !c.isWhitespace)
  /** Run gutter entry offering the standard executors; `tooltip` is evaluated when the gutter is hovered. */
  def info(icon: Icon, tooltip: => String): RunLineMarkerContributor.Info =
    new RunLineMarkerContributor.Info(icon, ExecutorAction.getActions(0), new java.util.function.Function[PsiElement, String] {
      override def apply(value: PsiElement): String = tooltip
    })
  private val Definitions = Key.create[CachedValue[Vector[DbtTestDefinition]]]("dbt.test.definitions")
  def definitions(file: PsiFile): Vector[DbtTestDefinition] = {
    val project = file.getProject
    CachedValuesManager.getManager(project).getCachedValue(project, Definitions,
      () => CachedValueProvider.Result.create(DbtTestDiscovery.discover(DbtWorkspace.get(project)),
        PsiModificationTracker.MODIFICATION_COUNT, VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS, DbtProjectSettings.get(project)), false)
  }
  def testsAt(element: PsiElement): Option[Vector[DbtTestDefinition]] = Option(element).flatMap(e => Option(e.getContainingFile)).filter(_.isPhysical).map { file =>
    val tests = definitions(file).filter(_.file == file.getVirtualFile)
    if (element.isInstanceOf[PsiFile] || tests.size == 1 && tests.head.offset == 0) tests
    else Option(file.getViewProvider.getDocument).fold(Vector.empty[DbtTestDefinition]) { document =>
      val line = document.getLineNumber(element.getTextOffset)
      tests.filter(t => t.offset < document.getTextLength && document.getLineNumber(t.offset) == line)
    }
  }
  def status(file: PsiFile, tests: Vector[DbtTestDefinition]): String = {
    val w = DbtWorkspace.get(file.getProject)
    val runs = w.roots.filter(r => tests.exists(_.root == r.directory.getPath))
      .flatMap(file.getProject.getService(classOf[DbtExecutionEvidence]).runs).filter(_.problem.isEmpty)
    val recorded = runs.flatMap(run => run.results.map(result => result.id -> (if (run.active) "running" else result.status)))
      .distinctBy(_._1).toMap
    val states = tests.map(t => recorded.getOrElse(t.id, "not run"))
    Vector("error", "failed", "running", "canceled", "not run", "warning", "skipped").find(states.contains)
      .getOrElse(if (states.nonEmpty) "passed" else "not run")
  }
}

final class DbtModelRunLineMarker extends RunLineMarkerContributor {
  override def getInfo(element: PsiElement): RunLineMarkerContributor.Info = null
  override def getSlowInfo(element: PsiElement): RunLineMarkerContributor.Info = {
    val file = element.getContainingFile
    if (element.getFirstChild != null || element.isInstanceOf[PsiFile] || file == null ||
      element.getTextOffset != DbtTestRunLineMarker.firstCode(file) ||
      !DbtWorkspace.get(file.getProject).codeSymbols(file).exists(_.kind == "model")) null
    else DbtTestRunLineMarker.info(AllIcons.RunConfigurations.TestState.Run, "Run dbt model")
  }
}
