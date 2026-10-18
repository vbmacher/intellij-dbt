package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project._
import com.google.gson.{Gson, JsonObject, JsonParser}
import com.intellij.icons.AllIcons
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.{ApplicationManager, ModalityState, ReadAction, WriteIntentReadAction}
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.{FileDocumentManager, FileEditorManager, FileEditorManagerEvent, FileEditorManagerListener}
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.util.{Disposer, IconLoader}
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.{VirtualFile, VirtualFileManager}
import com.intellij.openapi.wm.{ToolWindow, ToolWindowFactory}
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.ui.components.{JBLabel, JBScrollPane, JBTextArea, JBTextField}
import com.intellij.ui.{DocumentAdapter, JBColor, OnePixelSplitter}
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.{JBCefApp, JBCefBrowser, JBCefBrowserBase, JBCefJSQuery}
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.IconUtil
import java.awt.{BorderLayout, Dimension, FlowLayout, GridBagConstraints, GridBagLayout, Insets}
import java.awt.event.{FocusAdapter, FocusEvent}
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import java.util.Base64
import java.util.concurrent.Callable
import java.util.concurrent.atomic.AtomicLong
import javax.swing.{JButton, JCheckBox, JComboBox, JPanel}
import javax.swing.event.DocumentEvent
import javax.imageio.ImageIO
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal
import scala.util.Try

final class DbtToolWindowFactory extends ToolWindowFactory {
  override def createToolWindowContent(project: Project, toolWindow: ToolWindow): Unit = {
    val panel = new DbtToolWindowPanel(project)
    val tests = new DbtTestPanel(project)
    val manager = toolWindow.getContentManager
    Vector("Lineage" -> panel.lineagePanel, "Runner" -> panel.runnerPanel, "Tests" -> tests, "Status" -> panel.statusPanel).foreach { case (name, component) =>
      val content = ContentFactory.getInstance().createContent(component, name, false)
      manager.addContent(content)
    }
    Disposer.register(manager, tests)
    tests.refresh()
    Disposer.register(manager, panel)
    panel.refresh(follow = true)
  }
}

private[ide] final case class DbtViewContext(root: Option[String], selection: Option[DbtRunSelection], incremental: Boolean,
  targets: Vector[String], target: String, status: String, choices: Vector[DbtRunSelection] = Vector.empty)

/** All PSI/artifact work is performed in cancellable nonblocking read actions. Only immutable UI payloads cross to the EDT. */
private[ide] final class DbtToolWindowPanel(project: Project) extends Disposable {
  val lineagePanel = new JPanel(new BorderLayout)
  val runnerPanel = new JPanel(new BorderLayout)
  val statusPanel = new JPanel(new BorderLayout)
  private val settings = DbtProjectSettings.get(project)
  private val runner = project.getService(classOf[DbtRunner])
  private val contextLabel = new JBLabel("Choose a model or directory to run commands.")
  private val runSelection = new TextFieldWithBrowseButton
  private val target = new JComboBox[String]()
  private val executableArguments = new JBTextField(24)
  private val selectionExpression = new JBTextField(24)
  private val selectionExclude = new JBTextField(18)
  private val selectionPreview = new JButton("Review selection")
  private val fullRefresh = new JCheckBox("Full Refresh")
  private val stop = new JButton("Stop", AllIcons.Actions.Suspend)
  private val preview = new JBTextArea
  private var lastPreviewCommand = Option.empty[DbtCommand]
  private val resultGrid = new DbtResultGrid(limit => rerunPreview(limit))
  private val status = new JBTextArea
  private val toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 3))
  private val commands = scala.collection.mutable.Map.empty[String, JButton]
  private val alarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
  private val generation = new AtomicLong
  private val docGeneration = new AtomicLong
  private val graphWork = new Object
  private val docWork = new Object
  private var focus = Option.empty[String]
  private var upstreamExtra = 0
  private var downstreamExtra = 0
  private var context = DbtViewContext(None, None, incremental = false, Vector.empty, "", "")
  private var fillingTarget = false
  private var loadedArguments = ""
  private var loadedTarget = ""
  private var disposed = false
  private var followPending = false
  private val browser = try {
    if (!JBCefApp.isSupported()) None else Some(new DbtLineageBrowser(this, handleMessage))
  } catch {
    case NonFatal(_) => None
  }
  browser match {
    case Some(view) => lineagePanel.add(view.component, BorderLayout.CENTER)
    case None =>
      val unavailable = new JBTextArea("The lineage graph requires the IDE's embedded JCEF browser. Use the bundled JetBrains Runtime with JCEF and enable the JCEF module. dbt Dependencies and the Runner remain available.")
      unavailable.setEditable(false)
      unavailable.setLineWrap(true)
      unavailable.setWrapStyleWord(true)
      lineagePanel.add(unavailable, BorderLayout.CENTER)
  }
  executableArguments.setName("dbt.executableArguments")
  runSelection.setName("dbt.runSelection")
  runSelection.setText(settings.getState.runnerSelection)
  runSelection.getTextField.asInstanceOf[JBTextField].getEmptyText.setText("All models")
  runSelection.setToolTipText("Leave blank for all models, or enter a model or directory path, absolute or relative to the IDE project.")
  runSelection.addBrowseFolderListener(project, FileChooserDescriptorFactory.singleFileOrDir()
    .withTitle("Select dbt model or directory")
    .withDescription("Choose a model file or a directory containing models.")
    .withFileFilter(file => file.isDirectory || context.choices.exists(_.path == file.getPath)))
  Disposer.register(this, runSelection)
  target.setName("dbt.target")
  target.setEditable(true)
  target.setToolTipText("Select or type a dbt target. Leave blank for the profile default.")
  target.getEditor.getEditorComponent.addFocusListener(new FocusAdapter {
    override def focusLost(event: FocusEvent): Unit = saveTarget()
  })
  fullRefresh.setToolTipText("<html>Rebuild incremental models in the selected model or directory from all source data.<br>" +
    "Applies to Run and Build. Can take longer and use more warehouse compute.</html>")
  executableArguments.setToolTipText("Arguments passed before the dbt command. Quote arguments containing spaces.")
  executableArguments.addActionListener(_ => saveExecutableArguments())
  executableArguments.addFocusListener(new FocusAdapter {
    override def focusLost(event: FocusEvent): Unit = saveExecutableArguments()
  })
  val configuration = new JPanel(new GridBagLayout)
  Vector(("Target:", target, .2), ("Model or directory:", runSelection, .5),
    ("Executable arguments:", executableArguments, .3)).zipWithIndex.foreach { case ((text, field, weight), column) =>
    val label = new JBLabel(text)
    label.setLabelFor(field)
    val c = new GridBagConstraints
    c.gridy = 0
    c.gridx = column * 2
    c.anchor = GridBagConstraints.WEST
    c.insets = new Insets(3, 0, 3, 5)
    configuration.add(label, c)
    c.gridx += 1
    c.weightx = weight
    c.fill = GridBagConstraints.HORIZONTAL
    field.setMinimumSize(new Dimension(0, field.getMinimumSize.height))
    configuration.add(field, c)
  }
  DbtCommand.Actions.foreach { name =>
    val (icon, tooltip) = name match {
      case "Run" => AllIcons.Actions.Execute -> "Run the selected model or directory"
      case "Build" => AllIcons.Actions.Compile -> "Build and test the selected model or directory"
      case "Compile" => AllIcons.Actions.Compile -> "Compile the selected model or directory"
      case "Test" => AllIcons.RunConfigurations.TestState.Run -> "Run tests for the selected model or directory"
      case "Preview" => AllIcons.Actions.Preview -> "Preview a single selected model"
      case "Generate Docs" => AllIcons.Actions.PreviewDetails -> "Generate documentation for the selected execution project and target"
      case "Source Freshness" => AllIcons.Actions.Refresh -> "Check freshness of all sources in the selected dbt project"
    }
    val button = new JButton(name, icon)
    button.setToolTipText(tooltip)
    commands(name) = button
    button.addActionListener(_ => start(name))
    toolbar.add(button)
    if (name == "Build") toolbar.add(fullRefresh)
  }
  val clear = new JButton("Clear", AllIcons.Actions.GC)
  clear.setToolTipText("Clear the command log and preview")
  clear.addActionListener(_ => {
    runner.console.clear()
    preview.setText("")
    lastPreviewCommand = None
    resultGrid.clear("Run an explicit preview to see rows.")
  })
  toolbar.add(clear)
  stop.setToolTipText("Cancel the running dbt command")
  stop.addActionListener(_ => runner.stop())
  stop.setEnabled(false)
  toolbar.add(stop)
  val configure = new JButton("Settings", AllIcons.General.Settings)
  configure.setToolTipText("Configure dbt Workspace settings")
  configure.addActionListener(_ => ShowSettingsUtil.getInstance().showSettingsDialog(project, "dbt Workspace"))
  toolbar.add(configure)
  val north = new JPanel(new BorderLayout)
  val selectionBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 3))
  selectionExpression.setName("dbt.selection")
  selectionExclude.setName("dbt.exclude")
  selectionExpression.setToolTipText("Optional dbt selector expression, such as tag:finance+. Overrides the model/directory selection.")
  selectionBar.add(new JBLabel("Selection:")); selectionBar.add(selectionExpression)
  selectionBar.add(new JBLabel("Exclude:")); selectionBar.add(selectionExclude); selectionBar.add(selectionPreview)
  selectionPreview.addActionListener(_ => {
    saveSelection()
    val rootPath = context.root
    val currentSelection = context.selection
    ReadAction.nonBlocking(new Callable[String] {
      override def call(): String = {
        val w = DbtWorkspace.get(project)
        w.roots.find(r => rootPath.contains(r.directory.getPath)).map { root =>
          val expression = if (root.runOptions.selection.trim.nonEmpty) root.runOptions.selection
            else currentSelection.filter(s => s.path.nonEmpty && s.path != root.directory.getPath)
              .map(DbtRunSelection.selectors(w, _).mkString(" ")).getOrElse("")
          val result = com.github.vbmacher.intellij.dbt.lineage.DbtSelection.resolve(w, root, expression, root.runOptions.exclude)
          "Local selector preview · " + root.name + " · target: " + DbtExecutionContext.activeTarget(root).getOrElse("profile default") +
            "\nMatched resources: " + result.resources.size + "\n" + result.resources.map(_.uniqueId).mkString("\n") +
            (if (result.problems.nonEmpty) "\nIncomplete: " + result.problems.mkString("\n") else "") +
            (if (root.runOptions.stateDirectory.nonEmpty) "\n\nBaseline: " + root.runOptions.stateDirectory + "\n" +
              DbtState.compare(w, root).fold(identity, _.map(c => s"${c.status}: ${c.id} — ${c.reason} ${c.relation}").mkString("\n")) +
              DbtState.preview(w, root, expression, root.runOptions.exclude).fold(error => "\n" + error, plan =>
                "\n\nBuild locally (static preview):\n" + plan.selected.map(_.uniqueId).mkString("\n") +
                  "\n\nUnselected upstream dependencies:\n" + plan.dependencies.map(d =>
                    s"${d.resolution}: ${d.id} ${d.relation}\n  ${d.reason}\n  ${d.evidence}").mkString("\n") +
                  (if (plan.problems.nonEmpty) "\nIncomplete: " + plan.problems.mkString("; ") else "")) else "") +
            "\n\nCommand:\n" + (if (root.runOptions.selection.trim.nonEmpty) DbtCommand.selection(w, root, "Build", fullRefresh = false)
              else currentSelection.toRight("Choose a model or directory in the Runner.")
                .flatMap(DbtCommand.create(w, _, "Build", fullRefresh = false))).fold(identity, _.arguments.mkString(" "))
        }.getOrElse("Choose an owning dbt project in the Runner.")
      }
    }).expireWith(this).finishOnUiThread(ModalityState.defaultModalityState(), (text: String) => {
      DbtTextDialog.show("dbt selection and state", text)
    }).submit(AppExecutorUtil.getAppExecutorService)
  })
  val configurationRows = new JPanel(new BorderLayout)
  configurationRows.add(configuration, BorderLayout.NORTH); configurationRows.add(selectionBar, BorderLayout.SOUTH)
  north.add(configurationRows, BorderLayout.NORTH)
  north.add(toolbar, BorderLayout.CENTER)
  north.add(contextLabel, BorderLayout.SOUTH)
  runnerPanel.add(north, BorderLayout.NORTH)
  preview.setEditable(false)
  preview.setLineWrap(false)
  preview.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12))
  val output = new OnePixelSplitter(true, .75f)
  output.setFirstComponent(runner.console.getComponent)
  output.setSecondComponent(resultGrid)
  runner.console match {
    case console: com.intellij.execution.impl.ConsoleViewImpl => console.getEditor.getSettings.setUseSoftWraps(true)
    case _ => ()
  }
  runnerPanel.add(output, BorderLayout.CENTER)
  status.setEditable(false)
  status.setLineWrap(true)
  status.setWrapStyleWord(true)
  statusPanel.add(new JBScrollPane(status), BorderLayout.CENTER)
  fullRefresh.setVisible(false)
  runSelection.addDocumentListener(new DocumentAdapter {
    override def textChanged(event: DocumentEvent): Unit = {
      val path = selectionPath
      if (settings.getState.runnerSelection != path) {
        saveTarget()
        saveExecutableArguments()
        settings.getState.runnerSelection = path
        generation.incrementAndGet()
        fullRefresh.setSelected(false)
        context = DbtViewContext(None, None, incremental = false, Vector.empty, "", "")
        contextLabel.setText("Updating run selection and target…")
        updateButtons()
        settings.changed()
      }
    }
  })
  target.addActionListener(_ => if (!fillingTarget) saveTarget())
  runner.listen(this, event => {
    updateButtons()
    browser.foreach(_.runnerState(event.running, event.message))
    event.preview.foreach(preview.setText)
    if (event.running) resultGrid.clear("dbt command running…")
    event.result.foreach(resultGrid.showResult)
    if (event.result.nonEmpty) lastPreviewCommand = event.command.filter(_.action == "Preview")
    if (!event.running && event.result.isEmpty) resultGrid.clear(event.preview.getOrElse(event.message))
    if (event.refresh || event.execution.nonEmpty) schedule(follow = false)
  })
  val connection = project.getMessageBus.connect(this)
  connection.subscribe(DbtProjectSettings.Changed, new DbtSettingsListener {
    override def settingsChanged(): Unit = schedule(follow = false)
  })
  connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, new FileEditorManagerListener {
    override def fileOpened(source: FileEditorManager, file: VirtualFile): Unit = schedule(follow = false)

    override def selectionChanged(event: FileEditorManagerEvent): Unit = {
      schedule(follow = true)
    }
  })
  connection.subscribe(PsiModificationTracker.TOPIC, new PsiModificationTracker.Listener {
    override def modificationCountChanged(): Unit = schedule(follow = false)
  })
  connection.subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener {
    override def after(events: java.util.List[_ <: VFileEvent]): Unit = schedule(follow = false)
  })
  connection.subscribe(LafManagerListener.TOPIC, new LafManagerListener {
    override def lookAndFeelChanged(source: com.intellij.ide.ui.LafManager): Unit = schedule(follow = false)
  })
  connection.subscribe(EditorColorsManager.TOPIC, new com.intellij.openapi.editor.colors.EditorColorsListener {
    override def globalSchemeChange(scheme: com.intellij.openapi.editor.colors.EditorColorsScheme): Unit = schedule(follow = false)
  })
  updateButtons()

  private def selectionPath: String = {
    val value = runSelection.getText.trim
    if (value.isEmpty) "" else Try {
      val path = Paths.get(value)
      (if (path.isAbsolute) path else Option(project.getBasePath).map(Paths.get(_).resolve(path)).getOrElse(path.toAbsolutePath))
        .normalize().toString.replace(java.io.File.separatorChar, '/')
    }.getOrElse(value)
  }

  private def targetText: String = Option(target.getEditor.getItem).fold("")(_.toString.trim)

  private def saveTarget(): Unit = context.root.foreach { path =>
    val options = settings.options(path)
    val value = targetText
    if (value != loadedTarget) {
      loadedTarget = value
      if (options.target != value) {
        options.target = value
        settings.changed()
      }
    }
  }

  private def saveExecutableArguments(): Unit = context.root.foreach { path =>
    val options = settings.options(path)
    val value = executableArguments.getText.trim
    loadedArguments = value
    if (options.executableArguments != value) {
      options.executableArguments = value
      settings.changed()
    }
  }

  private def schedule(follow: Boolean): Unit = ApplicationManager.getApplication.invokeLater(() => if (!disposed) {
    followPending ||= follow
    alarm.cancelAllRequests()
    alarm.addRequest(() => {
      val next = followPending
      followPending = false
      refresh(next)
    }, 180)
  })

  def refresh(follow: Boolean): Unit = {
    if (disposed) return
    val request = generation.incrementAndGet()
    val file = Option(FileEditorManager.getInstance(project).getSelectedTextEditor).flatMap(e => Option(FileDocumentManager.getInstance().getFile(e.getDocument)))
      .orElse(FileEditorManager.getInstance(project).getSelectedFiles.headOption)
    val oldFocus = focus
    val state = settings.getState
    val selectedPath = state.runnerSelection
    val selectedRoot = state.runnerRoot
    val up = state.upstream + upstreamExtra
    val down = state.downstream + downstreamExtra
    ReadAction.nonBlocking(new Callable[(DbtGraphSnapshot, DbtViewContext)] {
      override def call(): (DbtGraphSnapshot, DbtViewContext) = {
        val w = DbtWorkspace.get(project)
        val psi = file.flatMap(f => Option(PsiManager.getInstance(project).findFile(f)))
        val editorModel = psi.toVector.flatMap(w.codeSymbols).find(_.kind == "model")
        val choices = DbtRunSelection.available(w)
        val selection = if (selectedPath.isEmpty) DbtRunSelection.all(w, selectedRoot) else choices.find(_.path == selectedPath)
        val owner = selection.flatMap(DbtRunSelection.owner(w, _)).map(w.executionRoot)
        val models = selection.toVector.flatMap(DbtRunSelection.selectedModels(w, _))
        val nextFocus = if (follow || oldFocus.isEmpty) editorModel.map(DbtGraphData.key(w, _)).orElse(oldFocus) else oldFocus
        val graph = DbtGraphData.build(w, nextFocus, if (nextFocus != oldFocus) state.upstream else up,
          if (nextFocus != oldFocus) state.downstream else down, state.exposures)
        val data = models.headOption.map(DbtResourceData.get(w, _))
        val profile = owner.map(DbtExecutionContext.profiles(_))
        val selectedTarget = owner.flatMap(DbtExecutionContext.activeTarget).getOrElse("")
        val artifacts = owner.map { root =>
          val manifest = root.setting("manifest").flatMap(root.psiFile)
          manifest.fold("Manifest unavailable. Generate Docs for this project/target.")(m =>
            "Manifest: " + m.getVirtualFile.getPath + "\nFile updated: " + java.time.Instant.ofEpochMilli(m.getVirtualFile.getTimeStamp) +
              (if (DbtArtifactContext.matches(root)) "\nPhysical relation context verified." else "\nPhysical relation target unverified; Generate Docs before clipboard conversion.")) +
            "\n" + DbtDeclarationSource.EP.getExtensionList.asScala.toVector.flatMap(_.artifactStatus(root)).mkString("\n")
        }.getOrElse("Choose a model or directory in the Runner.")
        val summary = owner.map(r => "Executable: " + DbtExecutionContext.executable(r)).getOrElse("Executable: unavailable") +
          "\nCLI version: " + data.flatMap(_.attributes.get("dbt_version")).getOrElse("Not available in the manifest; see Runner output.") +
          "\nTarget: " + selectedTarget + "\nAdapter: " + profile.flatMap(_.adapter).getOrElse("Unavailable") +
          "\nSelection: " + selection.map(_.label).getOrElse("None") + "\nLineage focus: " + editorModel.map(_.presentableText).getOrElse("None") + "\nResources: " + graph.payload.getAsJsonArray("nodes").size() + "\n" + artifacts
        val incremental = models.exists(s => DbtResourceData.get(w, s).attributes.get("materialized").contains("incremental"))
        graph -> DbtViewContext(owner.map(_.directory.getPath), selection, incremental, profile.toVector.flatMap(_.targets), selectedTarget, summary, choices)
      }
    }).inSmartMode(project).expireWith(this).coalesceBy(graphWork).finishOnUiThread(ModalityState.any(), (result: (DbtGraphSnapshot, DbtViewContext)) =>
      if (!disposed && request == generation.get()) {
        val (snapshot, next) = result
        val changedRoot = context.root != next.root
        val changedSelection = context.selection.map(_.path) != next.selection.map(_.path) || changedRoot
        val changedTargets = context.targets != next.targets
        val pendingTarget = targetText
        val targetEdited = pendingTarget != loadedTarget
        context = next
        if (changedRoot) {
          selectionExpression.setText(context.root.map(settings.options(_).selection).getOrElse(""))
          selectionExclude.setText(context.root.map(settings.options(_).exclude).getOrElse(""))
        }
        context.root.foreach(settings.getState.runnerRoot = _)
        if (changedRoot || executableArguments.getText == loadedArguments) {
          loadedArguments = context.root.map(settings.options(_).executableArguments).getOrElse("")
          executableArguments.setText(loadedArguments)
        }
        if (changedSelection || !context.incremental) fullRefresh.setSelected(false)
        fullRefresh.setVisible(context.incremental)
        fillingTarget = true
        if (changedRoot || changedTargets) {
          target.removeAllItems()
          target.addItem("")
          context.targets.foreach(target.addItem)
        }
        if (changedRoot || !targetEdited) target.setSelectedItem(context.target)
        else if (changedTargets) target.setSelectedItem(pendingTarget)
        loadedTarget = context.target
        fillingTarget = false
        contextLabel.setText(context.selection.map(_.label).getOrElse(if (runSelection.getText.trim.isEmpty)
          "Choose a model or directory" else "Choose an available dbt model or directory") +
          " · target: " + (if (context.target.isEmpty) "profile default" else context.target))
        status.setText(context.status)
        val payload = snapshot.payload
        val nextFocus = Option(payload.get("focus")).map(_.getAsString).filter(_.nonEmpty)
        if (nextFocus != focus) {
          upstreamExtra = 0
          downstreamExtra = 0
        }
        focus = nextFocus
        payload.addProperty("direction", settings.getState.direction)
        payload.addProperty("edgeStyle", settings.getState.edgeStyle)
        payload.addProperty("docsVisible", settings.getState.docsVisible)
        payload.addProperty("docsWidth", settings.getState.docsWidth)
        payload.addProperty("running", runner.isRunning)
        payload.addProperty("canGenerateDocs", context.root.nonEmpty)
        val focusedNode = payload.getAsJsonArray("nodes").asScala.map(_.getAsJsonObject)
          .find(n => Option(n.get("focus")).exists(_.getAsBoolean))
        payload.addProperty("status", if (runner.isRunning) "dbt command running…" else focusedNode.map { node =>
          if (!Option(node.get("manifest")).exists(_.getAsBoolean)) "Model missing from manifest — Generate Docs"
          else "Focused: " + node.get("name").getAsString
        }.getOrElse("Select a model or graph resource"))
        val theme = new JsonObject

        def color(c: java.awt.Color): String = f"#${c.getRed}%02x${c.getGreen}%02x${c.getBlue}%02x"

        val scheme = EditorColorsManager.getInstance().getGlobalScheme
        theme.addProperty("bg", color(scheme.getDefaultBackground))
        theme.addProperty("fg", color(scheme.getDefaultForeground))
        theme.addProperty("panel", color(javax.swing.UIManager.getColor("Panel.background")))
        payload.add("theme", theme)
        browser.foreach(_.update(payload))
        updateButtons()
      }
    ).submit(AppExecutorUtil.getAppExecutorService)
  }

  private def updateButtons(): Unit = {
    resultGrid.setRunning(runner.isRunning)
    commands.foreach { case (name, button) => button.setEnabled(!runner.isRunning && context.root.nonEmpty &&
      context.selection.exists(s => name != "Preview" || !s.directory)) }
    runSelection.setEnabled(!runner.isRunning)
    stop.setEnabled(runner.isRunning)
    target.setEnabled(!runner.isRunning && context.root.nonEmpty)
    fullRefresh.setEnabled(!runner.isRunning)
    executableArguments.setEnabled(!runner.isRunning && context.root.nonEmpty)
  }

  private def start(action: String): Unit = WriteIntentReadAction.run(() => {
    saveSelection()
    saveTarget()
    saveExecutableArguments()
    FileDocumentManager.getInstance().saveAllDocuments()
    val selection = context.selection.filter(_.path == selectionPath)
    val requestedRefresh = fullRefresh.isSelected && context.selection.exists(s => selection.exists(_.path == s.path))
    ReadAction.nonBlocking(new Callable[Either[String, DbtCommand]] {
      override def call(): Either[String, DbtCommand] =
        selection.toRight("Choose a model or directory in the Runner.").flatMap { selected =>
          val w = DbtWorkspace.get(project)
          val root = DbtRunSelection.owner(w, selected).map(w.executionRoot)
          root.filter(_.runOptions.selection.trim.nonEmpty).fold(DbtCommand.create(w, selected, action, requestedRefresh))(
            DbtCommand.selection(w, _, action, requestedRefresh))
        }
    }).expireWith(this).inSmartMode(project).withDocumentsCommitted(project).finishOnUiThread(ModalityState.defaultModalityState(), (result: Either[String, DbtCommand]) => result match {
      case Right(command) => WriteIntentReadAction.run(() => {
        FileDocumentManager.getInstance().saveAllDocuments()
        runner.start(command)
      })
      case Left(message) => contextLabel.setText(message)
    }).submit(AppExecutorUtil.getAppExecutorService)
  })

  private def rerunPreview(limit: Int): Unit = lastPreviewCommand.foreach { previous =>
    saveTarget(); saveExecutableArguments()
    FileDocumentManager.getInstance().saveAllDocuments()
    ReadAction.nonBlocking(new Callable[Either[String, DbtCommand]] {
      override def call(): Either[String, DbtCommand] = {
        val w = DbtWorkspace.get(project)
        for {
          root <- w.roots.find(_.directory.getPath == previous.root).toRight("The preview execution project is unavailable.")
          _ <- Either.cond(DbtArtifactContext.identity(root) == previous.artifactContext, (),
            "Execution settings changed. Start a new preview to use the current context.")
          index = previous.arguments.indexOf("--limit", previous.commandOffset + 1)
          _ <- Either.cond(index >= 0 && index + 1 < previous.arguments.size, (), "The previous query has no supported row limit.")
        } yield previous.copy(arguments = previous.arguments.updated(index + 1, limit.max(1).min(1000).toString))
      }
    }).expireWith(this).withDocumentsCommitted(project).finishOnUiThread(ModalityState.defaultModalityState(),
      (result: Either[String, DbtCommand]) => result match {
        case Right(command) =>
          val unchanged = ReadAction.computeBlocking[Boolean, RuntimeException](() =>
            lastPreviewCommand.contains(previous) && DbtWorkspace.get(project).roots.find(_.directory.getPath == previous.root)
              .exists(root => DbtArtifactContext.identity(root) == previous.artifactContext))
          if (unchanged) runner.start(command) else contextLabel.setText("The preview or execution settings changed. Start a new preview.")
        case Left(message) => contextLabel.setText(message)
      }).submit(AppExecutorUtil.getAppExecutorService)
  }

  private def saveSelection(): Unit = context.root.foreach { path =>
    settings.options(path).selection = selectionExpression.getText.trim
    settings.options(path).exclude = selectionExclude.getText.trim
  }

  private def handleMessage(raw: String): Unit = {
    if (disposed) return
    val message = JsonParser.parseString(raw).getAsJsonObject

    def field(name: String): String = Option(message.get(name)).map(_.getAsString).getOrElse("")

    val kind = field("type")
    val id = field("id")
    kind match {
      case "ready" => refresh(follow = true)
      case "declaration" | "origin" =>
        val value = field("value")
        ReadAction.nonBlocking(new Callable[Option[DbtSymbolElement]] {
          override def call(): Option[DbtSymbolElement] = {
            val w = DbtWorkspace.get(project)
            DbtGraphData.build(w, None, 1, 1, exposures = true).targets.get(id).flatMap { resource =>
              if (kind == "declaration") {
                w.columns(resource.symbol).find(_.name == value).map(_.element).orElse(
                  com.github.vbmacher.intellij.dbt.navigation.DbtSqlAnalysis.get(w, resource.symbol.file)
                    .flatMap(_.columns.find(_.name == value)).map(c =>
                      DbtSymbol("sql_projection", c.name, resource.symbol.namespace, resource.symbol.file, c.range).element))
              } else Try(JsonParser.parseString(value).getAsJsonObject).toOption.flatMap { request =>
                for {
                  column <- Option(request.get("column")).filter(_.isJsonPrimitive).map(_.getAsString)
                  index <- Option(request.get("index")).flatMap(v => Try(v.getAsInt).toOption)
                  edge <- com.github.vbmacher.intellij.dbt.lineage.DbtColumnLineage.upstream(w, resource.symbol, column).lift(index)
                } yield edge.input.element
              }
            }
          }
        }).expireWith(this).inSmartMode(project).finishOnUiThread(ModalityState.any(), (target: Option[DbtSymbolElement]) =>
          if (!disposed) target.filter(_.isValid).foreach(_.navigate(true))).submit(AppExecutorUtil.getAppExecutorService)
      case "select" | "focus" | "edit" | "column" =>
        val request = docGeneration.incrementAndGet()
        ReadAction.nonBlocking(new Callable[Option[(JsonObject, DbtSymbolElement)]] {
          override def call(): Option[(JsonObject, DbtSymbolElement)] = {
            val w = DbtWorkspace.get(project)
            // Rebuild the identity map rather than retaining PSI objects in the browser callback.
            DbtGraphData.build(w, None, 1, 1, exposures = true).targets.get(id).map(e =>
              (if (kind == "column") DbtGraphData.columnPaths(w, e.symbol, field("value")) else DbtGraphData.documentation(w, e.symbol)) -> e)
          }
        }).expireWith(this).inSmartMode(project).coalesceBy(docWork).finishOnUiThread(ModalityState.any(), (result: Option[(JsonObject, DbtSymbolElement)]) =>
          if (!disposed && request == docGeneration.get()) result.foreach { case (doc, targetElement) =>
            if (kind == "edit") DbtPropertiesEditor.show(DbtWorkspace.get(project), targetElement.symbol)
            else if (kind == "column") {
              browser.foreach(_.showColumn(id, doc))
            } else browser.foreach(_.showDoc(id, doc))
            if (kind == "focus") {
              focus = Some(id)
              upstreamExtra = 0
              downstreamExtra = 0
              targetElement.navigate(true)
              refresh(follow = false)
            }
          }
        ).submit(AppExecutorUtil.getAppExecutorService)
      case "expand" => if (id == "upstream") upstreamExtra += 5 else downstreamExtra += 5; refresh(follow = false)
      case "regenerate" => start("Generate Docs")
      case "docs" => settings.getState.docsVisible = field("value") == "true"
      case "width" => field("value").toIntOption.foreach(n => settings.getState.docsWidth = n.max(220).min(1200))
      case _ => ()
    }
  }

  override def dispose(): Unit = {
    disposed = true
    generation.incrementAndGet()
    docGeneration.incrementAndGet()
    ()
  }
}

private[ide] final class DbtLineageBrowser(parent: Disposable, receive: String => Unit) {
  private val browser = new JBCefBrowser
  Disposer.register(parent, browser)
  private val query = JBCefJSQuery.create(browser: JBCefBrowserBase)
  Disposer.register(parent, query)
  query.addHandler(raw => {
    ApplicationManager.getApplication.invokeLater(() => if (!browser.isDisposed) receive(raw))
    null
  })
  val component: javax.swing.JComponent = browser.getComponent

  private def resource(path: String): String = {
    val stream = Option(getClass.getResourceAsStream("/lineage/" + path)).getOrElse(throw new IllegalStateException("Missing bundled lineage resource " + path))
    try new String(stream.readAllBytes(), StandardCharsets.UTF_8) finally stream.close()
  }

  private val scripts = Vector("vendor/cytoscape.min.js", "vendor/dagre.min.js", "vendor/cytoscape-dagre.min.js")
    .map(path => "<script>" + resource(path).replace("</script", "<\\/script") + "</script>").mkString
  browser.loadHTML(resource("index.html").replace("<!-- SCRIPTS -->", scripts + "<script>window.dbtSend=function(message){" + query.inject("message") + "};</script>"))
  browser.disableNavigation()

  private def encoded(json: String): String = Base64.getEncoder.encodeToString(json.getBytes(StandardCharsets.UTF_8))

  private def decode(json: String): String = "JSON.parse(new TextDecoder().decode(Uint8Array.from(atob('" + encoded(json) + "'),c=>c.charCodeAt(0))))"

  private def icons(dark: Boolean): JsonObject = {
    val result = new JsonObject
    Vector("clear-search" -> AllIcons.Actions.Close, "zoom-in" -> AllIcons.General.ZoomIn,
      "zoom-out" -> AllIcons.General.ZoomOut, "fit" -> AllIcons.General.FitContent,
      "show-docs" -> AllIcons.Actions.PreviewDetails, "regenerate" -> AllIcons.Actions.Refresh,
      "close-docs" -> AllIcons.Actions.Close).foreach { case (id, icon) =>
      val bytes = new ByteArrayOutputStream
      ImageIO.write(IconUtil.toBufferedImage(IconLoader.getDarkIcon(icon, dark)), "png", bytes)
      result.addProperty(id, "data:image/png;base64," + Base64.getEncoder.encodeToString(bytes.toByteArray))
    }
    result
  }

  private lazy val lightIcons = icons(dark = false)
  private lazy val darkIcons = icons(dark = true)

  def update(payload: JsonObject): Unit = {
    payload.add("icons", if (JBColor.isBright) lightIcons else darkIcons)
    browser.runJavaScript("window.dbtUpdate&&window.dbtUpdate(" + decode(payload.toString) + ")")
  }

  def showDoc(id: String, data: JsonObject): Unit = browser.runJavaScript("window.dbtShowDoc&&window.dbtShowDoc(" + new Gson().toJson(id) + "," + decode(data.toString) + ")")

  def runnerState(running: Boolean, message: String): Unit = browser.runJavaScript("window.dbtRunnerState&&window.dbtRunnerState(" + running + "," + new Gson().toJson(message) + ")")
}
