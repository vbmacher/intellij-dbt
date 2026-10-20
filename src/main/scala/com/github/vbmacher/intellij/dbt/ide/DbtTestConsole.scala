package com.github.vbmacher.intellij.dbt.ide

import com.intellij.execution.{Executor, Location, PsiLocation}
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.process.{ProcessHandler, ProcessOutputTypes}
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.testframework.actions.AbstractRerunFailedTestsAction
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil
import com.intellij.execution.testframework.sm.runner.{SMTestLocator, SMTestProxy, SMTRunnerConsoleProperties}
import com.intellij.execution.ui.{ConsoleView, ConsoleViewContentType}
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import java.io.OutputStream
import scala.jdk.CollectionConverters._

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
  final class ManagedProcess extends ProcessHandler {
    def complete(code: Int): Unit = notifyProcessTerminated(code)
    override def getProcessInput: OutputStream = null
    override def detachIsDefault: Boolean = false
    override protected def destroyProcessImpl(): Unit = {
      canceled = true
      if (observing) runner.stop() else notifyProcessTerminated(130)
    }
    override protected def detachProcessImpl(): Unit = destroyProcessImpl()
  }
  val process = new ManagedProcess
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
        case native: com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView => action.setModel(native.getResultsViewer)
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
      view.print(com.intellij.util.execution.ParametersListUtil.join(value.arguments.asJava) + "\n", ConsoleViewContentType.SYSTEM_OUTPUT)
      observing = true
      if (!runner.start(value, (text, kind) => process.notifyTextAvailable(text, kind))) {
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
