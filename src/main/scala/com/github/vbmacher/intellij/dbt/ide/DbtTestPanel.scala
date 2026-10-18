package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project._
import com.intellij.execution.configurations.{RunProfile, RunProfileState}
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.{Executor, Location, PsiLocation}
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil
import com.intellij.execution.testframework.sm.runner.{SMTestLocator, SMTestProxy, SMTRunnerConsoleProperties}
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.{ModalityState, ReadAction}
import com.intellij.openapi.fileEditor.{FileEditorManager, OpenFileDescriptor}
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.components.JBLabel
import com.intellij.util.concurrency.AppExecutorUtil
import java.awt.{BorderLayout, FlowLayout}
import java.util.concurrent.Callable
import javax.swing.{Icon, JButton, JComboBox, JPanel}
import scala.jdk.CollectionConverters._

/** A Community-compatible native test tree. All execution delegates to the project's single managed Runner. */
private[ide] final class DbtTestPanel(project: Project) extends JPanel(new BorderLayout) with Disposable {
  private val runner = project.getService(classOf[DbtRunner])
  private val header = new JBLabel("Discover tests to browse definitions; only Run executes dbt.")
  private val roots = new JComboBox[String]
  private val run = new JButton("Run tests")
  private val runSelected = new JButton("Run selected test")
  private val failed = new JButton("Rerun Failed")
  private val discover = new JButton("Refresh tests")
  private val stop = new JButton("Stop")
  private val definition = new JButton("Go to Definition")
  private val fixture = new JButton("Open Fixture")
  private val rows = new JButton("Stored failing rows")
  private val previewRows = new JButton("Preview stored rows")
  private val toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT))
  private val generation = new java.util.concurrent.atomic.AtomicLong
  private var definitions = Vector.empty[DbtTestDefinition]
  private var proxies = Map.empty[String, SMTestProxy]
  private var commands = Map.empty[String, DbtCommand]
  private var lastCommand: Option[DbtCommand] = None
  private var lastResults = Vector.empty[DbtExecutionResult]
  private var requestedResource: Option[(String, String)] = None
  private var observingTests = false
  private var view: SMTRunnerConsoleView = _
  private val center = new JPanel(new BorderLayout)
  Vector(roots, discover, run, runSelected, failed, stop, definition, fixture, rows, previewRows).foreach(toolbar.add)
  private val north = new JPanel(new BorderLayout)
  north.add(toolbar, BorderLayout.NORTH); north.add(header, BorderLayout.SOUTH)
  add(north, BorderLayout.NORTH); add(center, BorderLayout.CENTER)
  failed.setEnabled(false); stop.setEnabled(false)

  private val profile = new RunProfile {
    override def getName: String = "dbt tests"
    override def getIcon: Icon = DbtIcons.Test
    override def getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState = null
  }
  private def selected: Option[DbtTestDefinition] = Option(view).flatMap(v => Option(v.getResultsViewer.getTreeView.getSelectedTest))
    .flatMap(p => proxies.find(_._2 eq p).flatMap { case (id, _) => definitions.find(t => t.root + "|" + t.id == id) })
  private def navigate(file: com.intellij.openapi.vfs.VirtualFile, offset: Int): Unit =
    new OpenFileDescriptor(project, file, offset).navigate(true)
  private def newView(): Unit = {
    Option(view).foreach(Disposer.dispose)
    center.removeAll()
    val properties = new SMTRunnerConsoleProperties(project, profile, "dbt", DefaultRunExecutor.getRunExecutorInstance)
    properties.setPrintTestingStartedTime(true)
    view = SMTestRunnerConnectionUtil.createConsole(properties)
    Disposer.register(this, view)
    center.add(view.getComponent, BorderLayout.CENTER)
    center.revalidate(); center.repaint()
    val root = view.getResultsViewer.getTestsRootNode
    val suites = scala.collection.mutable.Map.empty[String, SMTestProxy]
    proxies = definitions.map { t =>
      val key = t.root + "|" + t.group + "|" + t.kind
      val suite = suites.getOrElseUpdate(key, {
        val p = new SMTestProxy(t.root + " / " + t.group + " / " + t.kind, true, null)
        root.addChild(p); view.getResultsViewer.onSuiteTreeNodeAdded(p); p
      })
      val proxy = new SMTestProxy(t.name, false, "dbt-test://" + java.net.URLEncoder.encode(t.id, "UTF-8"))
      proxy.setLocator(new SMTestLocator {
        override def getLocation(protocol: String, path: String, p: Project, scope: GlobalSearchScope): java.util.List[Location[_]] = {
          val psi = Option(PsiManager.getInstance(p).findFile(t.file))
          psi.map(f => PsiLocation.fromPsiElement(Option(f.findElementAt(t.offset)).getOrElse(f)).asInstanceOf[Location[_]]).toList.asJava
        }
      })
      suite.addChild(proxy); view.getResultsViewer.onSuiteTreeNodeAdded(proxy)
      proxy.addSystemOutput(t.provenance + "\n" + t.file.getPath + ":" + t.offset + "\nNot run / result unavailable\n")
      (t.root + "|" + t.id) -> proxy
    }.toMap
  }
  private[ide] def testView: SMTRunnerConsoleView = view
  private[ide] def testDefinitions: Vector[DbtTestDefinition] = definitions
  def refresh(execution: Option[DbtExecutionRun] = None): Unit = {
    val stamp = generation.incrementAndGet()
    val preferred = Option(roots.getSelectedItem).map(_.toString)
    ReadAction.nonBlocking(new Callable[(Vector[DbtTestDefinition], Map[String, DbtCommand], Option[DbtExecutionRun])] {
      override def call(): (Vector[DbtTestDefinition], Map[String, DbtCommand], Option[DbtExecutionRun]) = {
        val w = DbtWorkspace.get(project)
        val commands = w.roots.map(w.executionRoot).distinct.flatMap { root =>
          DbtRunSelection.all(w, root.directory.getPath).flatMap(s => DbtCommand.create(w, s, "Test", fullRefresh = false).toOption)
            .map(root.directory.getPath -> _)
        }.toMap
        val definitions = DbtTestDiscovery.discover(w)
        val recorded = w.roots.map(w.executionRoot).distinct.filter(r => preferred.forall(_ == r.directory.getPath)).flatMap { root =>
          project.getService(classOf[DbtExecutionEvidence]).runs(root).filter(r => r.results.exists(result => definitions.exists(t => t.root == root.directory.getPath && t.id == result.id)))
        }.headOption
        (definitions, commands, recorded)
      }
    }).expireWith(this).expireWhen(() => generation.get() != stamp).finishOnUiThread(ModalityState.any(), data => {
      if (generation.get() == stamp) {
        definitions = data._1; commands = data._2
        val prior = Option(roots.getSelectedItem).map(_.toString)
        roots.removeAllItems(); commands.keys.toVector.sorted.foreach(roots.addItem)
        prior.filter(commands.contains).foreach(roots.setSelectedItem)
        newView()
        failed.setEnabled(false)
        header.setText(s"${definitions.size} test declarations. Not run / result unavailable until explicitly executed.")
        execution.orElse(data._3).foreach { recorded =>
          val root = Option(recorded.root).filter(_.nonEmpty).getOrElse(recorded.context.headOption.getOrElse(""))
          if (commands.contains(root)) roots.setSelectedItem(root)
          if (execution.isEmpty || lastCommand.isEmpty) lastCommand = commands.get(root)
          update(recorded)
        }
        requestedResource.foreach { case (root, resource) =>
          val affected = definitions.filter(t => t.root == root && t.dependencies.contains(resource))
          val failureIds = lastResults.filter(r => Set("failed", "error")(r.status)).map(_.id).toSet
          affected.find(t => failureIds(t.id)).orElse(affected.headOption).flatMap(t => proxies.get(t.root + "|" + t.id))
            .foreach(view.getResultsViewer.selectAndNotify)
        }
      }
    }).submit(AppExecutorUtil.getAppExecutorService)
    ()
  }
  def showResource(key: String): Unit = {
    val parts = key.split("\\|", 3)
    if (parts.length >= 2) {
      ReadAction.nonBlocking(new Callable[String] {
        override def call(): String = {
          val w = DbtWorkspace.get(project)
          w.roots.find(_.directory.getPath == parts(0)).map(w.executionRoot).map(_.directory.getPath).getOrElse(parts(0))
        }
      }).expireWith(this).finishOnUiThread(ModalityState.any(), root => {
        requestedResource = Some(root -> parts(1))
        roots.setSelectedItem(root)
        refresh()
      }).submit(AppExecutorUtil.getAppExecutorService)
      ()
    }
  }
  project.getMessageBus.connect(this).subscribe(DbtProjectSettings.Changed, new DbtSettingsListener {
    override def settingsChanged(): Unit = if (!runner.isRunning) refresh()
  })
  discover.addActionListener(_ => refresh())
  run.addActionListener(_ => Option(roots.getSelectedItem).flatMap(r => commands.get(r.toString)).foreach { command =>
    if (runner.start(command)) lastCommand = Some(command)
  })
  runSelected.addActionListener(_ => selected match {
    case Some(t) if t.selector.nonEmpty => commands.get(t.root).foreach { command =>
      val next = command.copy(arguments = command.arguments ++ Vector("--select", t.selector, "--indirect-selection", "empty"))
      if (runner.start(next)) lastCommand = Some(next)
    }
    case _ => header.setText("Select a test with a resolved dbt identity. Run the project tests to generate missing manifest identities.")
  })
  failed.addActionListener(_ => lastCommand.foreach { command =>
    DbtTestDiscovery.rerun(command, definitions.filter(_.root == Option(command.root).filter(_.nonEmpty).getOrElse(command.artifactContext.headOption.getOrElse(command.directory))), lastResults) match {
      case Right(next) => if (runner.start(next)) lastCommand = Some(next)
      case Left(problem) => header.setText(problem)
    }
  })
  stop.addActionListener(_ => runner.stop())
  definition.addActionListener(_ => selected.foreach(t => navigate(t.file, t.offset)))
  fixture.addActionListener(_ => selected match {
    case Some(t) if t.fixtures.nonEmpty => t.fixtures.foreach { case (file, offset) => navigate(file, offset) }
    case _ => header.setText("No fixture file is declared or available for this test.")
  })
  rows.addActionListener(_ => selected match {
    case Some(t) =>
      val relation = lastResults.find(_.id == t.id).map(_.relation).filter(_.nonEmpty).getOrElse(t.relation)
      if (relation.isEmpty) header.setText("No stored failing rows are available. Configure store_failures and run this test.")
      else {
        val file = new com.intellij.testFramework.LightVirtualFile("Stored failing rows.sql", "-- Stored relation from test execution; use Preview SQL to query explicitly.\nselect * from " + relation + "\n")
        FileEditorManager.getInstance(project).openFile(file, true)
      }
    case _ => header.setText("Select a test to inspect stored failing rows.")
  })
  previewRows.addActionListener(_ => (selected, lastCommand) match {
    case (Some(t), Some(command)) if t.root == Option(command.root).filter(_.nonEmpty).getOrElse(command.artifactContext.headOption.getOrElse(command.directory)) =>
      val relation = lastResults.find(_.id == t.id).map(_.relation).filter(_.nonEmpty).getOrElse(t.relation)
      val limit = DbtProjectSettings.get(project).options(t.root).previewLimit
      DbtTestDiscovery.preview(command, relation, limit) match {
        case Right(preview) =>
          if (runner.start(preview)) {
            val window = com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow("dbt")
            if (window != null) Option(window.getContentManager.findContent("Runner")).foreach(window.getContentManager.setSelectedContent(_))
          }
        case Left(problem) => header.setText(problem)
      }
    case _ => header.setText("Run the selected test in its owning project before previewing stored rows.")
  })
  runner.listen(this, event => {
    stop.setEnabled(event.running); previewRows.setEnabled(!event.running); run.setEnabled(!event.running); runSelected.setEnabled(!event.running); discover.setEnabled(!event.running)
    event.command.foreach(c => observingTests = Set("Test", "Build")(c.action))
    event.command.filter(c => event.running && Set("Test", "Build")(c.action)).foreach { command =>
      generation.incrementAndGet()
      lastCommand = Some(command)
      newView()
      val root = view.getResultsViewer.getTestsRootNode
      root.setStarted(); view.getResultsViewer.onTestingStarted(root)
      val selected = command.arguments.dropWhile(_ != "--select").drop(1).takeWhile(!_.startsWith("--")).toSet
      definitions.filter(t => t.root == Option(command.root).filter(_.nonEmpty).getOrElse(command.artifactContext.headOption.getOrElse(command.directory)) &&
        (selected.isEmpty || selected(t.selector))).foreach { t => proxies.get(t.root + "|" + t.id).foreach { p =>
          p.setStarted(); view.getResultsViewer.onTestStarted(p)
        }}
    }
    event.execution.foreach { execution =>
      if (observingTests && lastCommand.exists(c => c.artifactContext == execution.context) && view != null) {
        update(execution)
        if (!execution.active) refresh(Some(execution))
      }
    }
  })
  private[ide] def update(execution: DbtExecutionRun): Unit = {
    header.setText(s"Target: ${execution.target} · Run: ${execution.invocation} · ${execution.timestamp} · ${execution.artifact} · ${execution.problem}")
    execution.results.foreach { result => proxies.get(Option(execution.root).filter(_.nonEmpty).getOrElse(execution.context.headOption.getOrElse("")) + "|" + result.id).foreach { proxy =>
      if (!proxy.isFinal) {
        if (!proxy.isInProgress) { proxy.setStarted(); view.getResultsViewer.onTestStarted(proxy) }
        proxy.setDuration(result.duration)
        proxy.addSystemOutput(result.message + s"\nStatus: ${result.status}; failures: ${result.failures}\n")
        result.status match {
          case "passed" => proxy.setFinished(); view.getResultsViewer.onTestFinished(proxy)
          case "failed" | "error" => proxy.setTestFailed(result.message, result.message, result.status == "error"); view.getResultsViewer.onTestFailed(proxy); view.getResultsViewer.onTestFinished(proxy)
          case "warning" | "skipped" => proxy.setPresentableName((if (result.status == "warning") "WARN " else "SKIP ") + proxy.getName); proxy.setTestIgnored(if (result.status == "warning") "WARNING: " + result.message else result.message, ""); view.getResultsViewer.onTestIgnored(proxy); view.getResultsViewer.onTestFinished(proxy)
          case "canceled" => proxy.setPresentableName("CANCELED " + proxy.getName); proxy.setTerminated(); view.getResultsViewer.onTestFinished(proxy)
          case _ => ()
        }
      }
    }}
    if (!execution.active) {
      lastResults = execution.results
      proxies.values.filter(_.isInProgress).foreach { p => p.setTerminated(); view.getResultsViewer.onTestFinished(p) }
      val root = view.getResultsViewer.getTestsRootNode
      if (execution.problem.nonEmpty) root.setTerminated() else root.setFinished()
      view.getResultsViewer.onTestingFinished(root)
      failed.setEnabled(lastResults.exists(r => Set("failed", "error")(r.status)))
    }
  }
  override def dispose(): Unit = ()
}
