package com.github.vbmacher.intellij.dbt.ide

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.{ProcessHandler, ProcessOutputTypes}
import com.intellij.execution.ui.{ConsoleView, ConsoleViewContentType, ExecutionConsole}
import com.intellij.openapi.util.Disposer
import java.awt.BorderLayout
import java.io.OutputStream
import javax.swing.{JComponent, JPanel, JTabbedPane}
import scala.jdk.CollectionConverters._

/** One native Run session owns its console, results and cancellation. */
private[ide] final class DbtCommandConsole(configuration: DbtRunConfiguration) extends ExecutionConsole {
  private val project = configuration.getProject
  private val snapshot = configuration.clone().asInstanceOf[DbtRunConfiguration]
  private val runner = project.getService(classOf[DbtRunner])
  val console: ConsoleView = TextConsoleBuilderFactory.getInstance().createBuilder(project).getConsole
  private val tabs = new JTabbedPane
  private val panel = new JPanel(new BorderLayout)
  private var active = Option.empty[DbtCommand]
  private var canceled = false
  private val grid = new DbtResultGrid(project, limit => active.foreach { previous =>
    com.intellij.openapi.application.ReadAction.nonBlocking(new java.util.concurrent.Callable[Either[String, DbtCommand]] {
      override def call(): Either[String, DbtCommand] = {
        val fresh = snapshot.clone().asInstanceOf[DbtRunConfiguration]; fresh.captured = None
        fresh.prepare().map(_._1).flatMap { command =>
          val same = command.artifactContext == previous.artifactContext && command.environment == previous.environment &&
            command.sourceSql == previous.sourceSql && command.previewSource.map(_.text) == previous.previewSource.map(_.text) &&
            command.copy(request = command.request.copy(limit = previous.request.limit)).arguments == previous.arguments
          Either.cond(same, command.copy(request = command.request.copy(limit = limit),
            previewSource = previous.previewSource.orElse(command.previewSource)),
            "Source or execution settings changed. Start a fresh preview before rerunning with a limit.")
        }
      }
    }).expireWith(this).finishOnUiThread(com.intellij.openapi.application.ModalityState.defaultModalityState(), result => result match {
      case Right(command) => DbtRunConfiguration.launch(project, command)
      case Left(error) => console.print(error + "\n", ConsoleViewContentType.ERROR_OUTPUT)
    }).submit(com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService)
  })
  private val evidence = new DbtPreviewEvidencePanel(project)
  final class ManagedProcess extends ProcessHandler {
    def complete(code: Int): Unit = if (!isProcessTerminated) notifyProcessTerminated(code)
    override def getProcessInput: OutputStream = null
    override def detachIsDefault: Boolean = false
    override protected def destroyProcessImpl(): Unit = {
      canceled = true
      if (active.nonEmpty) runner.stop() else complete(130)
    }
    override protected def detachProcessImpl(): Unit = destroyProcessImpl()
  }
  val process = new ManagedProcess
  tabs.addTab("Console", console.getComponent)
  panel.add(tabs, BorderLayout.CENTER)
  console.attachToProcess(process)
  Disposer.register(this, console)
  process.startNotify()
  runner.listen(this, event => if (active.exists(c => event.command.exists(_ eq c))) {
    grid.setRunning(event.running)
    if (!event.running) {
      console.print("\n" + event.message + "\n", ConsoleViewContentType.SYSTEM_OUTPUT)
      event.execution.foreach { run =>
        console.print(s"Target: ${run.target} · Invocation: ${run.invocation} · ${run.timestamp}\n${run.artifact}\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        if (run.problem.nonEmpty) console.print(run.problem + "\n", ConsoleViewContentType.ERROR_OUTPUT)
      }
      evidence.showEvidence(event.previewEvidence)
      event.result.foreach { result =>
        grid.showResult(result)
        val rows = new JPanel(new BorderLayout)
        rows.add(grid, BorderLayout.CENTER); rows.add(evidence, BorderLayout.SOUTH)
        tabs.addTab("Rows", rows); tabs.setSelectedComponent(rows)
      }
      if (event.result.isEmpty && event.previewEvidence.nonEmpty) panel.add(evidence, BorderLayout.SOUTH)
      event.selectionOutput.foreach(raw => DbtSelectionResults.showCli(configuration, raw))
      process.complete(event.exitCode.getOrElse(if (canceled) 130 else 1))
      panel.revalidate()
    }
  })
  def start(command: DbtCommand): Unit = if (!canceled && !process.isProcessTerminated) {
    active = Some(command)
    console.print(com.intellij.util.execution.ParametersListUtil.join(command.arguments.asJava) + "\n", ConsoleViewContentType.SYSTEM_OUTPUT)
    if (!runner.start(command, (text, kind) => process.notifyTextAvailable(text, kind))) {
      active = None; fail("Another dbt command is running. Stop it in Run or Debug first.")
    }
  }
  def fail(problem: String): Unit = { console.print(problem + "\n", ConsoleViewContentType.ERROR_OUTPUT); process.complete(1) }
  override def getComponent: JComponent = panel
  override def getPreferredFocusableComponent: JComponent = console.getPreferredFocusableComponent
  override def dispose(): Unit = if (!process.isProcessTerminated) process.destroyProcess()
}
