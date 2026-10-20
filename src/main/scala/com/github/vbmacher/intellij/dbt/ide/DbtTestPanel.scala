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
import com.intellij.ui.components.{JBLabel, JBScrollPane, JBTextField}
import com.intellij.ui.{DocumentAdapter, OnePixelSplitter}
import com.intellij.icons.AllIcons
import javax.swing.event.DocumentEvent
import com.intellij.util.concurrency.AppExecutorUtil
import java.awt.{BorderLayout, FlowLayout, Dimension}
import java.util.concurrent.Callable
import javax.swing.{Icon, JButton, JComboBox, JPanel, JMenuItem, JPopupMenu}
import scala.jdk.CollectionConverters._

/** A Community-compatible native test tree. All execution delegates to the project's single managed Runner. */
private[ide] final class DbtTestPanel(project: Project) extends JPanel(new BorderLayout) with Disposable {
  private val runner = project.getService(classOf[DbtRunner])
  private val header = new JBLabel("Discover tests to browse definitions; only Run executes dbt.")
  private val targetLabel = new JBLabel("Target: profile default")
  private val roots = new JComboBox[String]
  private val run = new JButton("Run tests")
  private val scope = new JComboBox[String](Array("Project", "Filtered tests", "Selected tests"))
  private val packages = new JComboBox[String](Array("All packages", "Project-owned", "Dependencies"))
  private val search = new JBTextField
  private val discoveryStatus = new JBLabel("Discovering tests…")
  private val details = DbtUi.text("Select a test to inspect its declaration and execution evidence.")
  private var projectNames = Map.empty[String, String]
  private var commandProblems = Map.empty[String, String]
  private var fillingRoots = false
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
  private var renderedExecution: Option[DbtExecutionRun] = None
  private var requestedResource: Option[(String, String)] = None
  private var observingTests = false
  private var view: SMTRunnerConsoleView = _
  private val center = new JPanel(new BorderLayout)
  roots.setName("dbt.tests.project")
  roots.setPreferredSize(new Dimension(190, roots.getPreferredSize.height))
  DbtUi.projectRenderer(roots, () => projectNames)
  scope.setName("dbt.tests.scope")
  search.setName("dbt.tests.search")
  search.getEmptyText.setText("Filter tests by name or resource")
  search.getAccessibleContext.setAccessibleName("Filter tests")
  packages.setName("dbt.tests.packages")
  details.setName("dbt.tests.details")
  details.setBorder(com.intellij.util.ui.JBUI.Borders.empty(8))
  val contextBar = new JPanel(new BorderLayout(8, 0))
  val projectBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 3))
  projectBar.add(new JBLabel("Project:")); projectBar.add(roots)
  contextBar.add(projectBar, BorderLayout.WEST); contextBar.add(search, BorderLayout.CENTER)
  contextBar.add(targetLabel, BorderLayout.EAST)
  val filterBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 3))
  filterBar.add(new JBLabel("Show:")); filterBar.add(packages); filterBar.add(discoveryStatus)
  Vector(new JBLabel("Run:"), scope, run, stop, failed).foreach(toolbar.add)
  run.setIcon(AllIcons.Actions.Execute); stop.setIcon(AllIcons.Actions.Suspend)
  val more = new JButton("More…")
  val menu = new JPopupMenu
  Vector(discover).foreach { button =>
    val item = new JMenuItem(button.getText)
    item.addActionListener(_ => button.doClick())
    menu.add(item)
  }
  more.addActionListener(_ => {
    menu.getComponent(0).setEnabled(discover.isEnabled)
    menu.show(more, 0, more.getHeight)
  })
  toolbar.add(more)
  val controls = new JPanel(new BorderLayout)
  controls.add(contextBar, BorderLayout.NORTH); controls.add(filterBar, BorderLayout.CENTER); controls.add(toolbar, BorderLayout.SOUTH)
  private val north = new JPanel(new BorderLayout)
  north.add(controls, BorderLayout.NORTH)
  header.setText(if (runner.isRunning) "dbt command running. Inspect Runner for output." else "No execution evidence loaded.")
  header.setToolTipText("Results refer to the recorded target and invocation, independently of discovery.")
  north.add(header, BorderLayout.SOUTH)
  val inspector = new JPanel(new BorderLayout)
  inspector.add(new JBScrollPane(details), BorderLayout.CENTER)
  val inspectActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 3))
  inspectActions.add(definition)
  val investigate = new JButton("Investigate…")
  val investigation = new JPopupMenu
  Vector(fixture, rows, previewRows).foreach { button =>
    val item = new JMenuItem(button.getText)
    item.addActionListener(_ => button.doClick())
    investigation.add(item)
  }
  investigate.addActionListener(_ => {
    investigation.getComponent(0).setEnabled(fixture.isEnabled)
    investigation.getComponent(1).setEnabled(rows.isEnabled)
    investigation.getComponent(2).setEnabled(previewRows.isEnabled)
    investigation.show(investigate, 0, investigate.getHeight)
  })
  inspectActions.add(investigate); inspector.add(inspectActions, BorderLayout.NORTH)
  val split = new OnePixelSplitter(true, "dbt.tests.inspector", .65f)
  split.setFirstComponent(center); split.setSecondComponent(inspector)
  add(north, BorderLayout.NORTH); add(split, BorderLayout.CENTER)
  failed.setEnabled(false); stop.setEnabled(runner.isRunning); run.setEnabled(false)

  private val profile = new RunProfile {
    override def getName: String = "dbt tests"
    override def getIcon: Icon = DbtIcons.Test
    override def getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState = null
  }
  private def selectedTests: Vector[DbtTestDefinition] = Option(view).toVector.flatMap { v =>
    Option(v.getResultsViewer.getTreeView.getSelectionPaths).toVector.flatten.flatMap { path =>
      Option(v.getResultsViewer.getTreeView.getSelectedTest(path)).toVector.flatMap { p =>
        val selectedProxies = p.getAllTests.asScala.toSet + p
        visibleDefinitions.filter(t => proxies.get(t.root + "|" + t.id).exists(selectedProxies))
      }
    }
  }.distinctBy(t => (t.root, t.id))
  private def selected: Option[DbtTestDefinition] = selectedTests.headOption
  private[ide] def visibleDefinitions: Vector[DbtTestDefinition] = definitions.filter { t =>
    Option(roots.getSelectedItem).contains(t.root) &&
      (packages.getSelectedIndex match { case 1 => t.packageName == t.projectName; case 2 => t.packageName != t.projectName; case _ => true }) &&
      (search.getText.trim.isEmpty || (t.name + " " + t.group + " " + t.dependencies.mkString(" ")).toLowerCase.contains(search.getText.trim.toLowerCase))
  }
  private def updateActions(): Unit = {
    val tests = if (scope.getSelectedItem == "Selected tests") selectedTests else visibleDefinitions
    run.setEnabled(!runner.isRunning && Option(roots.getSelectedItem).exists(r => commands.contains(r.toString)) &&
      (scope.getSelectedItem == "Project" || tests.nonEmpty && tests.forall(_.selector.nonEmpty)))
    run.setText(if (scope.getSelectedItem == "Project") "Run tests" else "Run " + tests.size + " tests")
    run.setToolTipText(Option(roots.getSelectedItem).flatMap(r => commandProblems.get(r.toString)).getOrElse(if (scope.getSelectedItem == "Project") "Run all project tests, including dependencies; view filters do not limit this command."
      else "Run exactly the " + scope.getSelectedItem.toString.toLowerCase + "; indirect selection is disabled. Unresolved identities require Generate Docs."))
    val test = selected
    definition.setEnabled(test.nonEmpty)
    fixture.setEnabled(test.exists(_.fixtures.nonEmpty))
    rows.setEnabled(test.exists(t => t.relation.nonEmpty || resultFor(t).exists(_.relation.nonEmpty)))
    previewRows.setEnabled(!runner.isRunning && rows.isEnabled && test.exists(t => lastCommand.exists(_.root == t.root)))
    failed.setEnabled(!runner.isRunning && lastResults.exists(r => Set("failed", "error")(r.status)))
    failed.setVisible(lastResults.exists(r => Set("failed", "error")(r.status)))
    targetLabel.setText("Target: " + Option(roots.getSelectedItem).flatMap(r => commands.get(r.toString)).map(_.target).filter(_.nonEmpty).getOrElse("profile default"))
    targetLabel.setToolTipText(targetLabel.getText + " · execution uses this project's configured profile")
    roots.setEnabled(!runner.isRunning); packages.setEnabled(!runner.isRunning); search.setEnabled(!runner.isRunning)
    scope.setEnabled(!runner.isRunning)
  }
  private def resultFor(test: DbtTestDefinition): Option[DbtExecutionResult] = renderedExecution
    .filter(r => Option(r.root).filter(_.nonEmpty).getOrElse(r.context.headOption.getOrElse("")) == test.root)
    .flatMap(_.results.find(_.id == test.id))
  private def showDetails(): Unit = {
    details.setText(selected.fold(if (visibleDefinitions.isEmpty) { if (definitions.isEmpty) "No test declarations found. Add a SQL or YAML test, then Refresh tests." else "No tests match these filters. Choose All packages or clear the search." } else "Select a test to inspect its declaration and execution evidence.") { t =>
      val result = resultFor(t)
      val status = result.map(r => r.status + (if (r.failures > 0) s" · ${r.failures} failing rows" else "")).getOrElse(
        if (renderedExecution.exists(_.active)) "No execution event for this test yet" else "No matching result available for this test")
      val run = renderedExecution.filter(r => Option(r.root).filter(_.nonEmpty).getOrElse(r.context.headOption.getOrElse("")) == t.root)
      s"${t.name}\n${t.kind} · ${t.packageName} (${if (t.packageName == t.projectName) "project-owned" else "dependency package"})" +
        "\n\nPurpose: " + Option(t.description).filter(_.nonEmpty).getOrElse("Declared check: " + t.name.stripPrefix(t.packageName + ".")) +
        "\nOwning resource: " + t.dependencies.mkString(", ") +
        "\n\nExecution: " + status + result.filter(_.message.nonEmpty).fold("")(r => "\n" + r.message) +
        run.fold("")(r => "\nTarget: " + Option(r.target).filter(_.nonEmpty).getOrElse("profile default") + "\nRun: " + r.invocation + "\n" + r.timestamp + "\n" + r.artifact +
          (if (r.problem.nonEmpty) "\nEvidence: " + DbtUi.evidenceProblem(r.problem) else "")) +
        "\n\nDeclaration: " + t.file.getPath + ":" + t.offset + "\n" + t.provenance +
        "\nIdentity: " + t.id + "\nSelector: " + Option(t.selector).filter(_.nonEmpty).getOrElse("Unresolved — Generate Docs and refresh tests")
    })
    details.setCaretPosition(0)
    updateActions()
  }
  private def renderDiscovery(): Unit = {
    val selection = selectedTests.map(t => t.root + "|" + t.id)
    newView()
    renderedEvidence.foreach(update)
    selection.flatMap(proxies.get).headOption.foreach(view.getResultsViewer.selectAndNotify)
    showDetails()
  }
  private var renderedEvidence: Option[DbtExecutionRun] = None
  private def navigate(file: com.intellij.openapi.vfs.VirtualFile, offset: Int): Unit =
    new OpenFileDescriptor(project, file, offset).navigate(true)
  private def newView(): Unit = {
    Option(view).foreach(Disposer.dispose)
    center.removeAll()
    val properties = new SMTRunnerConsoleProperties(project, profile, "dbt", DefaultRunExecutor.getRunExecutorInstance)
    properties.setPrintTestingStartedTime(true)
    val splitterKey = SMTestRunnerConnectionUtil.getSplitterPropertyName("dbt")
    val preferences = com.intellij.ide.util.PropertiesComponent.getInstance()
    if (!preferences.isValueSet(splitterKey)) preferences.setValue(splitterKey, .45f, .2f)
    view = SMTestRunnerConnectionUtil.createConsole(properties)
    view.getResultsViewer.getStatusLine.setVisible(false)
    renderedExecution = None
    Disposer.register(this, view)
    center.add(view.getComponent, BorderLayout.CENTER)
    center.revalidate(); center.repaint()
    val root = view.getResultsViewer.getTestsRootNode
    val suites = scala.collection.mutable.Map.empty[String, SMTestProxy]
    proxies = visibleDefinitions.map { t =>
      val key = t.root + "|" + t.packageName + "|" + t.group + "|" + t.kind
      val suite = suites.getOrElseUpdate(key, {
        val resource = t.group.split(", ").map(g => if (g.startsWith("model.")) g.split("\\.").drop(2).mkString(".") else g.stripPrefix(t.packageName + ".")).mkString(", ")
        val p = new SMTestProxy((if (t.packageName == t.projectName) "Project" else "Dependency: " + t.packageName) +
          (if (resource == t.kind) "" else " / " + resource) + " / " + t.kind, true, null)
        root.addChild(p); view.getResultsViewer.onSuiteTreeNodeAdded(p); p
      })
      val proxy = new SMTestProxy(t.name, false, "dbt-test://" + java.net.URLEncoder.encode(t.id, "UTF-8"))
      proxy.setPresentableName(t.name.stripPrefix(t.packageName + "."))
      proxy.setLocator(new SMTestLocator {
        override def getLocation(protocol: String, path: String, p: Project, scope: GlobalSearchScope): java.util.List[Location[_]] = {
          val psi = Option(PsiManager.getInstance(p).findFile(t.file))
          psi.map(f => PsiLocation.fromPsiElement(Option(f.findElementAt(t.offset)).getOrElse(f)).asInstanceOf[Location[_]]).toList.asJava
        }
      })
      suite.addChild(proxy); view.getResultsViewer.onSuiteTreeNodeAdded(proxy)
      proxy.addSystemOutput(t.provenance + "\n" + t.file.getPath + ":" + t.offset + "\nNo matching execution result loaded.\n")
      (t.root + "|" + t.id) -> proxy
    }.toMap
    view.getResultsViewer.getTreeView.addTreeSelectionListener(_ => showDetails())
    discoveryStatus.setText(s"${visibleDefinitions.size} shown / ${definitions.size} discovered")
    discoveryStatus.setToolTipText("Discovery includes all projects and dependency packages. Filters only change this view and Filtered tests scope.")
    if (visibleDefinitions.isEmpty) details.setText(if (definitions.isEmpty) "No test declarations found. Refresh tests after adding SQL or YAML test declarations." else "No tests match these filters. Choose All packages or clear the search.")
  }
  private[ide] def testView: SMTRunnerConsoleView = view
  private[ide] def testDefinitions: Vector[DbtTestDefinition] = definitions
  def refresh(execution: Option[DbtExecutionRun] = None): Unit = {
    discoveryStatus.setText("Discovering tests…")
    val stamp = generation.incrementAndGet()
    val preferred = Option(roots.getSelectedItem).map(_.toString)
    ReadAction.nonBlocking(new Callable[(Vector[DbtTestDefinition], Map[String, Either[String, DbtCommand]], Option[DbtExecutionRun], Map[String, String])] {
      override def call(): (Vector[DbtTestDefinition], Map[String, Either[String, DbtCommand]], Option[DbtExecutionRun], Map[String, String]) = {
        val w = DbtWorkspace.get(project)
        val executionRoots = w.roots.map(w.executionRoot).distinct
        val commands = executionRoots.map { root =>
          root.directory.getPath -> DbtRunSelection.all(w, root.directory.getPath).toRight("Execution project unavailable.")
            .flatMap(s => DbtCommand.create(w, s, "Test", fullRefresh = false))
        }.toMap
        val definitions = DbtTestDiscovery.discover(w)
        val recorded = w.roots.map(w.executionRoot).distinct.filter(r => preferred.forall(_ == r.directory.getPath)).flatMap { root =>
          project.getService(classOf[DbtExecutionEvidence]).runs(root).filter(r => r.active || r.problem.nonEmpty || r.results.exists(result => definitions.exists(t => t.root == root.directory.getPath && t.id == result.id)))
        }.headOption
        (definitions, commands, recorded, executionRoots.map(r => r.directory.getPath -> r.name).toMap)
      }
    }).expireWith(this).expireWhen(() => generation.get() != stamp).finishOnUiThread(ModalityState.any(), data => {
      if (generation.get() == stamp) {
        // onTestingFinished schedules native tree selection asynchronously. Keep that
        // console alive when discovery only confirms the result already rendered.
        val keepView = view != null && definitions == data._1 && execution.exists(renderedExecution.contains)
        definitions = data._1; commands = data._2.collect { case (root, Right(c)) => root -> c }
        commandProblems = data._2.collect { case (root, Left(p)) => root -> p }
        val prior = Option(roots.getSelectedItem).map(_.toString)
        val selection = selectedTests.map(t => t.root + "|" + t.id)
        fillingRoots = true
        projectNames = data._4
        roots.removeAllItems(); projectNames.keys.toVector.sorted.foreach(roots.addItem)
        prior.filter(projectNames.contains).foreach(roots.setSelectedItem)
        fillingRoots = false
        if (!keepView) {
          newView()
          failed.setEnabled(false)
          header.setText("No matching execution evidence loaded. Discovering tests does not execute dbt.")
        }
        renderedEvidence = execution.orElse(data._3)
        if (renderedEvidence.isEmpty) lastResults = Vector.empty
        renderedEvidence.foreach { recorded =>
          val root = Option(recorded.root).filter(_.nonEmpty).getOrElse(recorded.context.headOption.getOrElse(""))
          if (execution.nonEmpty && projectNames.contains(root)) { fillingRoots = true; roots.setSelectedItem(root); fillingRoots = false }
          if (execution.isEmpty || lastCommand.isEmpty) lastCommand = commands.get(root)
          if (!keepView) update(recorded)
        }
        selection.flatMap(proxies.get).headOption.foreach(view.getResultsViewer.selectAndNotify)
        discoveryStatus.setText(s"${visibleDefinitions.size} shown / ${definitions.size} discovered")
        showDetails()
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
  roots.addActionListener(_ => if (!fillingRoots) {
    roots.setToolTipText(Option(roots.getSelectedItem).map(_.toString).orNull)
    renderedEvidence = None; lastResults = Vector.empty; lastCommand = None
    renderDiscovery(); refresh()
  })
  packages.addActionListener(_ => renderDiscovery())
  search.getDocument.addDocumentListener(new DocumentAdapter {
    override def textChanged(event: DocumentEvent): Unit = renderDiscovery()
  })
  scope.addActionListener(_ => updateActions())
  run.addActionListener(_ => Option(roots.getSelectedItem).flatMap(r => commands.get(r.toString)).foreach { command =>
    val next = scope.getSelectedItem match {
      case "Selected tests" => DbtTestDiscovery.select(command, selectedTests)
      case "Filtered tests" => DbtTestDiscovery.select(command, visibleDefinitions)
      case _ => Right(command)
    }
    next match {
      case Right(c) => if (runner.start(c)) lastCommand = Some(c)
      case Left(problem) => details.setText(problem)
    }
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
    stop.setEnabled(event.running); discover.setEnabled(!event.running); updateActions()
    event.command.foreach(c => observingTests = Set("Test", "Build")(c.action))
    event.command.filter(c => event.running && Set("Test", "Build")(c.action)).foreach { command =>
      generation.incrementAndGet()
      lastCommand = Some(command)
      fillingRoots = true
      roots.setSelectedItem(Option(command.root).filter(_.nonEmpty).getOrElse(command.artifactContext.headOption.getOrElse(command.directory)))
      fillingRoots = false
      lastResults = Vector.empty; renderedEvidence = event.execution
      newView()
      val root = view.getResultsViewer.getTestsRootNode
      root.setStarted(); view.getResultsViewer.onTestingStarted(root)

    }
    event.execution.foreach { execution =>
      if (observingTests && lastCommand.exists(c => c.artifactContext == execution.context) && view != null) {
        update(execution)
        if (!execution.active) refresh(Some(execution))
      }
    }
  })
  private[ide] def update(execution: DbtExecutionRun): Unit = {
    renderedExecution = Some(execution)
    renderedEvidence = Some(execution)
    val tests = execution.results.filter(r => definitions.exists(t => t.id == r.id && t.root == Option(execution.root).filter(_.nonEmpty).getOrElse(execution.context.headOption.getOrElse(""))))
    view.getResultsViewer.getStatusLine.setVisible(execution.active)
    val summary = if (execution.results.isEmpty && execution.invocation.isEmpty && !execution.active) "No matching execution evidence" else
      (if (execution.active) "Running" else "Results") + s" · ${tests.count(_.status == "passed")} passed · ${tests.count(r => Set("failed", "error")(r.status))} failed · ${tests.count(_.status == "running")} active"
    header.setText(summary + (if (execution.problem.nonEmpty) " · " + DbtUi.evidenceProblem(execution.problem) else ""))
    header.setToolTipText(s"Target: ${execution.target} · Run: ${execution.invocation} · ${execution.timestamp}\n${execution.artifact}\n${DbtUi.evidenceProblem(execution.problem)}")
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
    if (!execution.active && (execution.results.nonEmpty || execution.invocation.nonEmpty)) {
      lastResults = execution.results
      proxies.values.filter(_.isInProgress).foreach { p => p.setTerminated(); view.getResultsViewer.onTestFinished(p) }
      val root = view.getResultsViewer.getTestsRootNode
      if (execution.problem.nonEmpty) root.setTerminated() else root.setFinished()
      view.getResultsViewer.onTestingFinished(root)
      failed.setEnabled(lastResults.exists(r => Set("failed", "error")(r.status)))
    }
    showDetails()
  }
  override def dispose(): Unit = ()
}
