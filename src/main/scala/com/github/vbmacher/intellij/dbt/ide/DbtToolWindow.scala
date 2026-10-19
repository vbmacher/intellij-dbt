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
import com.intellij.ui.{DocumentAdapter, JBColor}
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.{JBCefApp, JBCefBrowser, JBCefBrowserBase, JBCefJSQuery}
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.IconUtil
import java.awt.{BorderLayout, Dimension, FlowLayout, GridBagConstraints, GridBagLayout, GridLayout, Insets}
import java.awt.event.{FocusAdapter, FocusEvent}
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import java.util.Base64
import java.util.concurrent.Callable
import java.util.concurrent.atomic.AtomicLong
import javax.swing.{JButton, JCheckBox, JComboBox, JMenuItem, JPanel, JPopupMenu, JTabbedPane, JToggleButton}
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
    Vector("Runner" -> panel.runnerPanel, "Lineage" -> panel.lineagePanel, "Tests" -> tests).foreach { case (name, component) =>
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
  targets: Vector[String], target: String, status: String, choices: Vector[DbtRunSelection] = Vector.empty, completions: Vector[String] = Vector.empty, artifacts: String = "")

/** All PSI/artifact work is performed in cancellable nonblocking read actions. Only immutable UI payloads cross to the EDT. */
private[ide] final class DbtToolWindowPanel(project: Project) extends Disposable {
  val lineagePanel = new JPanel(new BorderLayout)
  val runnerPanel = new JPanel(new BorderLayout)
  private val settings = DbtProjectSettings.get(project)
  private val runner = project.getService(classOf[DbtRunner])
  private val contextLabel = new JBLabel("Choose a model or directory to run commands.")
  contextLabel.setName("dbt.context")
  private val runSelection = new TextFieldWithBrowseButton
  private val target = new JComboBox[String]()
  private val selectionExpression = new JBTextField(24)
  private val selectionExclude = new JBTextField(18)
  private val selectionPreview = new JButton("Review selection")
  private val selectionReview = new JBTextArea("Review selection to see locally matched resources.", 5, 80)
  private val selectionRefresh = new JButton("Refresh with dbt ls")
  private val selectionLineage = new JButton("Show in Lineage")
  private val selectionCopy = new JButton("Copy command")
  private var reviewCommand = Option.empty[DbtCommand]
  private var reviewRequest = 0L
  private var commandRequest = 0L
  private var cliIncremental = false
  private var lsRequest = Option.empty[Long]
  private var cliReview = Option.empty[Vector[String]]
  private var completionItems = Vector.empty[String]
  private val selectorToggle = new JToggleButton("dbt selectors")
  private val selectionControls = new JPanel(new BorderLayout)
  private val fullRefresh = new JCheckBox("Full Refresh")
  private val stop = new JButton("Stop", AllIcons.Actions.Suspend)
  private var lastPreviewCommand = Option.empty[DbtCommand]
  private val resultGrid = new DbtResultGrid(limit => rerunPreview(limit))
  private val previewEvidence = new DbtPreviewEvidencePanel(project)
  private val toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 3))
  private val command = new JComboBox[String](DbtCommand.Actions.toArray)
  command.getAccessibleContext.setAccessibleName("dbt command")
  private val execute = new JButton("Execute", AllIcons.Actions.Execute)
  private val output = new JTabbedPane
  private val alarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
  private val generation = new AtomicLong
  private val docGeneration = new AtomicLong
  private val graphWork = new Object
  private val docWork = new Object
  private var focus = Option(settings.getState.pinnedFocus).filter(_.nonEmpty)
  private var upstreamExtra = 0
  private var downstreamExtra = 0
  private var context = DbtViewContext(None, None, incremental = false, Vector.empty, "", "")
  private var fillingTarget = false
  private var fillingSelection = false
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
  val configuration = new JPanel(new GridBagLayout)
  Vector(("Model or directory:", runSelection, .75), ("Target:", target, .25)).zipWithIndex.foreach { case ((text, field, weight), column) =>
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
  command.setName("dbt.command")
  command.setToolTipText("Choose the dbt command to run for this scope and target.")
  command.addActionListener(_ => { invalidateReview(); updateButtons() })
  execute.setName("dbt.execute")
  execute.addActionListener(_ => start(command.getSelectedItem.toString))
  toolbar.add(command)
  toolbar.add(execute)
  toolbar.add(stop)
  toolbar.add(fullRefresh)
  selectorToggle.setName("dbt.selectorToggle")
  selectorToggle.setToolTipText("Show selector options. Clear Selection and Exclude to use the model or directory scope.")
  selectorToggle.addActionListener(_ => {
    selectionControls.setVisible(selectorToggle.isSelected)
    runnerPanel.revalidate()
  })
  stop.setToolTipText("Cancel the running dbt command")
  stop.addActionListener(_ => runner.stop())
  stop.setEnabled(false)
  val more = new JButton("More…")
  val menu = new JPopupMenu
  Vector[(String, () => Unit)](
    "Clear output" -> (() => {
      runner.console.clear()
      lastPreviewCommand = None
      resultGrid.clear("Run a preview to see rows.")
      previewEvidence.showEvidence(None)
    }),
    "Workspace details" -> (() => DbtTextDialog.show("dbt workspace details", diagnosticsText)),
    "Settings" -> (() => ShowSettingsUtil.getInstance().showSettingsDialog(project, "dbt Workspace"))
  ).foreach { case (label, action) =>
    val item = new JMenuItem(label)
    item.addActionListener(_ => action())
    menu.add(item)
  }
  more.addActionListener(_ => menu.show(more, 0, more.getHeight))
  toolbar.add(more)
  val north = new JPanel(new BorderLayout)
  val selectionBar = new JPanel(new GridBagLayout)
  selectionExpression.setName("dbt.selection")
  selectionExclude.setName("dbt.exclude")
  selectionExpression.setToolTipText("Optional dbt selector expression, such as tag:finance+. Overrides the model/directory selection.")
  Vector("Selection:" -> selectionExpression, "Exclude:" -> selectionExclude).zipWithIndex.foreach {
    case ((text, field), row) =>
      val c = new GridBagConstraints
      c.gridy = row
      c.anchor = GridBagConstraints.WEST
      c.insets = new Insets(3, 5, 3, 5)
      val label = new JBLabel(text)
      label.setLabelFor(field)
      selectionBar.add(label, c)
      c.gridx = 1
      c.weightx = 1
      c.fill = GridBagConstraints.HORIZONTAL
      field.setMinimumSize(new Dimension(0, field.getMinimumSize.height))
      selectionBar.add(field, c)
  }
  selectionPreview.addActionListener(_ => {
    cliReview = None
    cliIncremental = false
    saveSelection()
    saveTarget()
    val request = reviewRequest
    val action = command.getSelectedItem.toString
    val refresh = fullRefresh.isSelected && (action == "Run" || action == "Build")
    val rootPath = context.root
    val currentSelection = context.selection
    ReadAction.nonBlocking(new Callable[(String, Option[DbtCommand])] {
      override def call(): (String, Option[DbtCommand]) = {
        val w = DbtWorkspace.get(project)
        w.roots.find(r => rootPath.contains(r.directory.getPath)).map { root =>
          val expression = DbtSelectionReview.expression(w, root, currentSelection)
          val command = DbtCommand.selection(w, root, action, refresh, currentSelection)
          val result = com.github.vbmacher.intellij.dbt.lineage.DbtSelection.resolve(w, root, expression, root.runOptions.exclude)
          val text = "Local selector preview · " + root.name + " · target: " + DbtExecutionContext.activeTarget(root).getOrElse("profile default") +
            "\nMatched resources: " + result.resources.size + "\n" + result.resources.map(_.uniqueId).mkString("\n") +
            (if (result.problems.nonEmpty) "\nIncomplete: " + result.problems.mkString("\n") else "") +
            (if (root.runOptions.stateDirectory.nonEmpty) "\n\nBaseline: " + root.runOptions.stateDirectory + "\n" +
              DbtState.compare(w, root).fold(identity, _.map(c => s"${c.status}: ${c.id} — ${c.reason} ${c.relation}").mkString("\n")) +
              DbtState.preview(w, root, expression, root.runOptions.exclude).fold(error => "\n" + error, plan =>
                "\n\nBuild locally (static preview):\n" + plan.selected.map(_.uniqueId).mkString("\n") +
                  "\n\nUnselected upstream dependencies:\n" + plan.dependencies.map(d =>
                    s"${d.resolution}: ${d.id} ${d.relation}\n  ${d.reason}\n  ${d.evidence}").mkString("\n") +
                  (if (plan.problems.nonEmpty) "\nIncomplete: " + plan.problems.mkString("; ") else "")) else "") +
            "\n\nCommand:\n" + command.fold(identity, c => com.intellij.util.execution.ParametersListUtil.join(c.arguments.asJava))
          text -> command.toOption
        }.getOrElse("Choose an owning dbt project in the Runner." -> None)
      }
    }).expireWith(this).finishOnUiThread(ModalityState.defaultModalityState(), (result: (String, Option[DbtCommand])) => {
      if (request == reviewRequest) {
        selectionReview.setText(result._1); selectionReview.setCaretPosition(0)
        reviewCommand = result._2
        output.setSelectedIndex(2)
        updateButtons()
      }
    }).submit(AppExecutorUtil.getAppExecutorService)
  })
  selectionReview.setName("dbt.selectionReview")
  selectionReview.setEditable(false)
  selectionReview.setLineWrap(false)
  selectionReview.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12))
  val reviewControls = new JPanel(new GridLayout(1, 0, 5, 0))
  reviewControls.add(selectionPreview); reviewControls.add(selectionLineage); reviewControls.add(selectionCopy); reviewControls.add(selectionRefresh)
  selectionCopy.addActionListener(_ => reviewCommand.foreach(command =>
    com.intellij.openapi.ide.CopyPasteManager.getInstance().setContents(new java.awt.datatransfer.StringSelection(
      com.intellij.util.execution.ParametersListUtil.join(command.arguments.asJava)))))
  selectionLineage.addActionListener(_ => {
    saveSelection()
    showSelectionInLineage(selectionExpression.getText, selectionExclude.getText, cliReview)
    val window = com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow("dbt")
    if (window != null) Option(window.getContentManager.findContent("Lineage")).foreach(window.getContentManager.setSelectedContent(_))
  })
  selectionRefresh.setToolTipText("Run dbt ls explicitly using the current project, target and profile. Uses the shared Stop control.")
  selectionRefresh.addActionListener(_ => start("Resolve Selection"))
  Vector(selectionExpression, selectionExclude).foreach { field =>
    field.setToolTipText("dbt selector expression. Press Ctrl+Space for completion; spaces form unions and commas form intersections.")
    new com.intellij.openapi.project.DumbAwareAction("Complete dbt selection") {
      override def actionPerformed(event: com.intellij.openapi.actionSystem.AnActionEvent): Unit = {
        val choices = DbtSelectionReview.complete(field.getText, field.getCaretPosition, completionItems)
        if (choices.nonEmpty) com.intellij.openapi.ui.popup.JBPopupFactory.getInstance.createPopupChooserBuilder(choices.map(_._1).asJava)
          .setTitle("dbt selectors").setItemChosenCallback((chosen: String) => choices.find(_._1 == chosen).foreach { case (_, text, caret) =>
            field.setText(text); field.setCaretPosition(caret)
          }).createPopup().showUnderneathOf(field)
      }
    }.registerCustomShortcutSet(com.intellij.openapi.actionSystem.ActionManager.getInstance.getAction(com.intellij.openapi.actionSystem.IdeActions.ACTION_CODE_COMPLETION).getShortcutSet, field, this)
    field.getDocument.addDocumentListener(new DocumentAdapter {
      override def textChanged(event: DocumentEvent): Unit = if (!fillingSelection) { saveSelection(); invalidateReview() }
    })
  }
  selectionControls.add(selectionBar, BorderLayout.NORTH)
  selectionControls.add(reviewControls, BorderLayout.CENTER)
  selectionControls.add(new JBLabel("Selection replaces the model or directory scope. Exclude removes matches."), BorderLayout.SOUTH)
  selectionControls.setVisible(false)
  val configurationRows = new JPanel(new BorderLayout)
  val selectorSection = new JPanel(new BorderLayout)
  val selectorHeader = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0))
  selectorHeader.add(selectorToggle)
  selectorSection.add(selectorHeader, BorderLayout.NORTH)
  selectorSection.add(selectionControls, BorderLayout.CENTER)
  configurationRows.add(configuration, BorderLayout.NORTH)
  configurationRows.add(selectorSection, BorderLayout.CENTER)
  north.add(configurationRows, BorderLayout.NORTH)
  north.add(toolbar, BorderLayout.CENTER)
  north.add(contextLabel, BorderLayout.SOUTH)
  runnerPanel.add(north, BorderLayout.NORTH)
  output.setName("dbt.output")
  output.addTab("Console", runner.console.getComponent)
  val resultArea = new JPanel(new BorderLayout)
  resultArea.add(resultGrid, BorderLayout.CENTER)
  resultArea.add(previewEvidence, BorderLayout.SOUTH)
  output.addTab("Rows", resultArea)
  output.addTab("Selection", new JBScrollPane(selectionReview))
  runner.console match {
    case console: com.intellij.execution.impl.ConsoleViewImpl => console.getEditor.getSettings.setUseSoftWraps(true)
    case _ => ()
  }
  runnerPanel.add(output, BorderLayout.CENTER)
  fullRefresh.setVisible(false)
  runSelection.addDocumentListener(new DocumentAdapter {
    override def textChanged(event: DocumentEvent): Unit = {
      val path = selectionPath
      if (settings.getState.runnerSelection != path) {
        saveTarget()
        invalidateReview()
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
  target.getEditor.getEditorComponent match {
    case field: javax.swing.JTextField => field.getDocument.addDocumentListener(new DocumentAdapter {
      override def textChanged(event: DocumentEvent): Unit = if (!fillingTarget) invalidateReview()
    })
    case _ => ()
  }
  fullRefresh.addActionListener(_ => {
    val ids = cliReview
    val text = selectionReview.getText.split("\n\nCommand:", 2).head
    invalidateReview()
    cliReview = ids
    if (ids.nonEmpty) selectionReview.setText(text)
    ids.foreach(updateCliCommand(_, reviewRequest))
  })
  runner.listen(this, event => {
    updateButtons()
    browser.foreach(_.runnerState(event.running, event.message))
    if (event.running) output.setSelectedIndex(if (event.command.exists(_.action == "Resolve Selection")) 2 else 0)
    if (event.running && event.command.exists(_.action == "Preview")) previewEvidence.showEvidence(None)
    if (!event.running && event.previewEvidence.nonEmpty) previewEvidence.showEvidence(event.previewEvidence)
    if (event.running && event.command.exists(_.action == "Resolve Selection")) { lsRequest = Some(reviewRequest); cliReview = None }
    val lsFinished = !event.running && event.command.exists(_.action == "Resolve Selection") && lsRequest.contains(reviewRequest)
    if (lsFinished && event.selectionOutput.isEmpty) {
      reviewCommand = None; selectionCopy.setEnabled(false)
      selectionReview.setText("dbt ls did not complete. See the Runner console, then review the selection again.")
    }
    event.selectionOutput.foreach { output =>
      if (lsRequest.contains(reviewRequest)) {
        val header = "CLI selector preview · target: " + event.command.map(_.target).filter(_.nonEmpty).getOrElse("profile default")
        val parsed = DbtSelectionReview.cliResources(output)
        cliReview = parsed.toOption
        if (cliReview.isEmpty) { reviewCommand = None; selectionCopy.setEnabled(false) }
        selectionReview.setText(parsed.fold(error => header + "\n" + error,
          ids => header + "\nMatched resources: " + ids.size + "\n" + ids.mkString("\n")))
        selectionReview.setCaretPosition(0)
        cliReview.foreach(ids => updateCliCommand(ids, reviewRequest))
      }
    }
    if (event.running) resultGrid.clear("dbt command running…")
    event.result.foreach { result => resultGrid.showResult(result); output.setSelectedIndex(1) }
    if (!event.running && event.previewEvidence.nonEmpty) output.setSelectedIndex(1)
    if (event.result.nonEmpty) lastPreviewCommand = event.command.filter(_.action == "Preview")
    if (!event.running && event.result.isEmpty) resultGrid.clear(event.preview.getOrElse(event.message))
    if (event.refresh || event.execution.nonEmpty) schedule(follow = false)
  })
  com.intellij.openapi.editor.EditorFactory.getInstance.getEventMulticaster.addDocumentListener(
    new com.intellij.openapi.editor.event.DocumentListener {
      override def documentChanged(event: com.intellij.openapi.editor.event.DocumentEvent): Unit = {
        val file = FileDocumentManager.getInstance.getFile(event.getDocument)
        if (file != null && isReviewSource(file.getPath))
          invalidateReviewLater()
      }
    }, this)
  val connection = project.getMessageBus.connect(this)
  connection.subscribe(DbtProjectSettings.Changed, new DbtSettingsListener {
    override def settingsChanged(): Unit = { invalidateReviewLater(); schedule(follow = false) }
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
    override def after(events: java.util.List[_ <: VFileEvent]): Unit = {
      if (events.asScala.exists(e => isReviewSource(e.getPath))) invalidateReviewLater()
      schedule(follow = false)
    }
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

  private def isReviewSource(path: String): Boolean = {
    val artifacts = context.artifacts
    val inArtifacts = artifacts.nonEmpty && (path == artifacts || path.startsWith(artifacts + "/"))
    !inArtifacts && Vector(".sql", ".py", ".yml", ".yaml", ".json").exists(path.endsWith)
  }

  private def updateCliCommand(ids: Vector[String], request: Long): Unit = {
    val rootPath = context.root
    val selected = context.selection
    val action = command.getSelectedItem.toString
    val refresh = fullRefresh.isSelected && (action == "Run" || action == "Build")
    ReadAction.nonBlocking(new Callable[(Either[String, DbtCommand], Boolean)] {
      override def call(): (Either[String, DbtCommand], Boolean) = {
        val w = DbtWorkspace.get(project)
        val owner = w.roots.find(r => rootPath.contains(r.directory.getPath))
        val command = owner.toRight("The owning execution project is unavailable.")
          .flatMap(DbtCommand.selection(w, _, action, refresh, selected, Some(ids)))
        val incremental = owner.toVector.flatMap(r => com.github.vbmacher.intellij.dbt.lineage.DbtSelection.resolve(w, r, "").resources)
          .exists(s => ids.contains(s.uniqueId) && DbtResourceData.get(w, s).attributes.get("materialized").contains("incremental"))
        command -> incremental
      }
    }).expireWith(this).finishOnUiThread(ModalityState.defaultModalityState(), (result: (Either[String, DbtCommand], Boolean)) => {
      val (command, incremental) = result
      if (request == reviewRequest) {
        reviewCommand = command.toOption
        cliIncremental = incremental
        fullRefresh.setVisible(context.incremental || cliIncremental)
        selectionReview.append("\n\nCommand:\n" + command.fold(identity,
          c => com.intellij.util.execution.ParametersListUtil.join(c.arguments.asJava)))
        updateButtons()
      }
    }).submit(AppExecutorUtil.getAppExecutorService)
  }

  private def invalidateReviewLater(): Unit = {
    if (ApplicationManager.getApplication.isDispatchThread) { if (!disposed) invalidateReview() }
    else ApplicationManager.getApplication.invokeLater(() => if (!disposed) invalidateReview())
  }

  private def invalidateReview(): Unit = {
    reviewRequest += 1
    commandRequest += 1
    cliIncremental = false
    reviewCommand = None
    selectionReview.setText("Selection or execution context changed. Review selection again.")
    selectionCopy.setEnabled(false)
    cliReview = None
  }

  private def saveTarget(): Unit = context.root.foreach { path =>
    val options = settings.options(path)
    val value = targetText
    if (value != loadedTarget) {
      loadedTarget = value
      if (options.target != value) {
        invalidateReview()
        options.target = value
        settings.changed()
      }
    }
  }

  private[ide] def diagnosticsText: String = context.status

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
        val nextFocus = if (follow && state.pinnedFocus.isEmpty || oldFocus.isEmpty) editorModel.map(DbtGraphData.key(w, _)).orElse(oldFocus) else oldFocus
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
        val scope = owner.filter(_.runOptions.selection.trim.nonEmpty).map(r =>
          com.github.vbmacher.intellij.dbt.lineage.DbtSelection.resolve(w, r, r.runOptions.selection, r.runOptions.exclude).resources).getOrElse(models)
        val incremental = scope.exists(s => DbtResourceData.get(w, s).attributes.get("materialized").contains("incremental"))
        graph -> DbtViewContext(owner.map(_.directory.getPath), selection, incremental, profile.toVector.flatMap(_.targets), selectedTarget, summary, choices,
          owner.map(DbtSelectionReview.completions(w, _)).getOrElse(Vector.empty), owner.map(_.artifactDirectory.toString).getOrElse(""))
      }
    }).inSmartMode(project).expireWith(this).coalesceBy(graphWork).finishOnUiThread(ModalityState.any(), (result: (DbtGraphSnapshot, DbtViewContext)) =>
      if (!disposed && request == generation.get()) {
        val (snapshot, next) = result
        val changedRoot = context.root != next.root
        val changedSelection = context.selection.map(_.path) != next.selection.map(_.path) || changedRoot
        val changedTargets = context.targets != next.targets
        val pendingTarget = targetText
        val targetEdited = pendingTarget != loadedTarget
        if (changedRoot || changedSelection) invalidateReview()
        context = next
        completionItems = next.completions
        if (changedRoot) {
          fillingSelection = true
          selectionExpression.setText(context.root.map(settings.options(_).selection).getOrElse(""))
          selectionExclude.setText(context.root.map(settings.options(_).exclude).getOrElse(""))
          fillingSelection = false
          val active = selectionExpression.getText.trim.nonEmpty || selectionExclude.getText.trim.nonEmpty
          selectorToggle.setSelected(active)
          selectionControls.setVisible(active)
        }
        context.root.foreach(settings.getState.runnerRoot = _)
        if (changedSelection || !context.incremental && !cliIncremental) fullRefresh.setSelected(false)
        fullRefresh.setVisible(context.incremental || cliIncremental)
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
        payload.addProperty("pinned", settings.getState.pinnedFocus.nonEmpty)
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
    selectionCopy.setEnabled(reviewCommand.nonEmpty && !runner.isRunning)
    selectionPreview.setEnabled(context.root.nonEmpty && !runner.isRunning)
    selectionRefresh.setEnabled(context.root.nonEmpty && !runner.isRunning)
    selectionLineage.setEnabled(context.root.nonEmpty && !runner.isRunning)
    selectionExpression.setEnabled(!runner.isRunning)
    selectionExclude.setEnabled(!runner.isRunning)
    val action = command.getSelectedItem.toString
    val canExecute = !runner.isRunning && context.root.nonEmpty &&
      context.selection.exists(s => action != "Preview" || !s.directory)
    execute.setEnabled(canExecute)
    val description = action match {
      case "Run" => "Run models in the selected scope"
      case "Build" => "Build resources and run their tests in the selected scope"
      case "Compile" => "Compile the selected scope to SQL"
      case "Test" => "Run tests for the selected scope"
      case "Preview" => "Query one selected SQL model and display rows"
      case "Generate Docs" => "Generate documentation for the execution project and target"
      case "Source Freshness" => "Check freshness of all sources; choose the whole execution project"
    }
    execute.setToolTipText(description)
    command.setToolTipText(description)
    command.setEnabled(!runner.isRunning)
    val selectorActive = selectionExpression.getText.trim.nonEmpty || selectionExclude.getText.trim.nonEmpty
    selectorToggle.setText(if (selectorActive) "dbt selectors (active)" else "dbt selectors")
    runSelection.setToolTipText(if (selectionExpression.getText.trim.nonEmpty)
      "Sets the execution project. The active dbt selector determines which resources run."
      else "Leave blank for all models, or choose a model or directory.")
    val scope = if (selectionExpression.getText.trim.nonEmpty) "dbt selector: " + selectionExpression.getText.trim
      else context.selection.map(_.label).getOrElse("Choose a model or directory")
    val excluded = Option(selectionExclude.getText.trim).filter(_.nonEmpty).fold("")(text => " · exclude: " + text)
    val summary = scope + excluded + " · target: " + (if (targetText.isEmpty) "profile default" else targetText)
    contextLabel.setText(summary)
    contextLabel.setToolTipText(summary)
    runSelection.setEnabled(!runner.isRunning)
    stop.setEnabled(runner.isRunning)
    target.setEnabled(!runner.isRunning && context.root.nonEmpty)
    fullRefresh.setEnabled(!runner.isRunning && (action == "Run" || action == "Build"))
  }

  private def start(action: String): Unit = WriteIntentReadAction.run(() => {
    saveSelection()
    saveTarget()
    FileDocumentManager.getInstance().saveAllDocuments()
    val request = commandRequest
    val reviewedCli = cliReview
    val selection = context.selection.filter(_.path == selectionPath)
    val requestedRefresh = (action == "Run" || action == "Build") && fullRefresh.isSelected && context.selection.exists(s => selection.exists(_.path == s.path))
    ReadAction.nonBlocking(new Callable[Either[String, DbtCommand]] {
      override def call(): Either[String, DbtCommand] =
        selection.toRight("Choose a model or directory in the Runner.").flatMap { selected =>
          val w = DbtWorkspace.get(project)
          val root = DbtRunSelection.owner(w, selected).map(w.executionRoot)
          root.toRight("The owning execution project is unavailable.").flatMap { owner =>
            if (action == "Resolve Selection") DbtCommand.resolveSelection(w, owner, Some(selected))
            else if (owner.runOptions.selection.trim.nonEmpty || owner.runOptions.exclude.trim.nonEmpty)
              DbtCommand.selection(w, owner, action, requestedRefresh, Some(selected), reviewedCli)
            else DbtCommand.create(w, selected, action, requestedRefresh)
          }
        }
    }).expireWith(this).inSmartMode(project).withDocumentsCommitted(project).finishOnUiThread(ModalityState.defaultModalityState(), (result: Either[String, DbtCommand]) => result match {
      case Right(command) => WriteIntentReadAction.run(() => {
        if (request == commandRequest && !runner.isRunning) {
          FileDocumentManager.getInstance().saveAllDocuments()
          if (request == commandRequest) runner.start(command)
        } else contextLabel.setText("Selection or execution settings changed while preparing the command. Review and run again.")
      })
      case Left(message) => contextLabel.setText(message)
    }).submit(AppExecutorUtil.getAppExecutorService)
  })

  private def rerunPreview(limit: Int): Unit = lastPreviewCommand.foreach { previous =>
    saveTarget()
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
    val options = settings.options(path)
    val expression = selectionExpression.getText.trim
    val exclude = selectionExclude.getText.trim
    updateButtons()
    if (options.selection != expression || options.exclude != exclude) {
      options.selection = expression
      options.exclude = exclude
      settings.changed()
    }
  }

  private[ide] def handleMessage(raw: String): Unit = {
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
        }).expireWith(this).inSmartMode(project).coalesceBy(docWork).finishOnUiThread(ModalityState.defaultModalityState(), (result: Option[(JsonObject, DbtSymbolElement)]) =>
          if (!disposed && request == docGeneration.get()) result.foreach { case (doc, targetElement) =>
            if (kind == "edit") DbtPropertiesEditor.show(DbtWorkspace.get(project), targetElement.symbol)
            else if (kind == "column") {
              browser.foreach(_.showColumn(id, doc))
            } else browser.foreach(_.showDoc(id, doc))
            if (kind == "focus") {
              focus = Some(id)
              if (settings.getState.pinnedFocus.nonEmpty) settings.getState.pinnedFocus = id
              upstreamExtra = 0
              downstreamExtra = 0
              targetElement.navigate(true)
              refresh(follow = false)
            }
          }
        ).submit(AppExecutorUtil.getAppExecutorService)
      case "expand" => if (id == "upstream") upstreamExtra += 5 else downstreamExtra += 5; refresh(follow = false)
      case "regenerate" => start("Generate Docs")
      case "pin" =>
        settings.getState.pinnedFocus = if (field("value") == "true") focus.getOrElse(id) else ""
        refresh(follow = false)
      case "filter" =>
        if (field("shared") == "true") {
          selectionExpression.setText(id.take(4000)); selectionExclude.setText(field("value").take(4000))
          saveSelection()
        }
        showSelectionInLineage(id, field("value"), fromRunner = false)
      case "airflow" =>
        val request = Try(JsonParser.parseString(field("value").take(8000)).getAsJsonObject).toOption
        val mapping = request.flatMap(o => Option(o.get("mapping"))).filter(_.isJsonPrimitive).map(_.getAsString).getOrElse("")
        val destination = request.flatMap(o => Option(o.get("target"))).filter(_.isJsonPrimitive).map(_.getAsString).getOrElse("")
        ReadAction.nonBlocking(new Callable[Either[String, DbtAirflowDestination]] {
          override def call(): Either[String, DbtAirflowDestination] = {
            val w = DbtWorkspace.get(project)
            DbtGraphData.build(w, None, 1, 1, exposures = true).targets.get(id)
              .toRight("This Lineage resource is no longer available. Refresh Lineage.")
              .flatMap(resource => DbtAirflow.navigation(w, resource.symbol, mapping, destination))
          }
        }).expireWith(this).inSmartMode(project).finishOnUiThread(ModalityState.defaultModalityState(),
          (result: Either[String, DbtAirflowDestination]) => result match {
            case Right(target) => target.url match {
              case Some(url) => com.intellij.ide.BrowserUtil.browse(url)
              case None => Option(com.intellij.openapi.vfs.LocalFileSystem.getInstance.findFileByPath(target.path)).foreach(file =>
                new com.intellij.openapi.fileEditor.OpenFileDescriptor(project, file, target.offset).navigate(true))
            }
            case Left(message) => DbtTextDialog.show("Airflow context", message)
          }).submit(AppExecutorUtil.getAppExecutorService)
      case "export" => DbtGraphExport.show(project, id, field("value"))
      case "execution" =>
        val window = com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow("dbt")
        if (window != null) {
          Option(window.getContentManager.findContent("Tests")).foreach { content =>
            window.getContentManager.setSelectedContent(content)
            content.getComponent match { case tests: DbtTestPanel => tests.showResource(id); case _ => () }
          }
          window.show()
        }
      case "docs" => settings.getState.docsVisible = field("value") == "true"
      case "width" => field("value").toIntOption.foreach(n => settings.getState.docsWidth = n.max(220).min(1200))
      case _ => ()
    }
  }

  private def showSelectionInLineage(text: String, excluded: String, cliIds: Option[Vector[String]] = None, fromRunner: Boolean = true): Unit = {
    val request = reviewRequest
    val typedExpression = text.take(4000); val exclude = excluded.take(4000)
    val selectedScope = context.selection
    val rootPath = context.root
    ReadAction.nonBlocking(new Callable[JsonObject] {
      override def call(): JsonObject = {
        val w = DbtWorkspace.get(project)
        val payload = new JsonObject
        val root = w.roots.find(r => rootPath.contains(r.directory.getPath)).orElse(
          if (w.roots.map(w.executionRoot).distinct.size == 1) w.roots.map(w.executionRoot).headOption else None)
        val expression = if (!fromRunner || typedExpression.trim.nonEmpty) typedExpression else root.map(DbtSelectionReview.expression(w, _, selectedScope)).getOrElse("")
        payload.addProperty("expression", expression); payload.addProperty("exclude", exclude)
        payload.addProperty("fromRunner", fromRunner)
        val result = root.map { owner =>
          cliIds.fold(com.github.vbmacher.intellij.dbt.lineage.DbtSelection.resolve(w, owner, expression, exclude)) { ids =>
            val resources = com.github.vbmacher.intellij.dbt.lineage.DbtSelection.resolve(w, owner, "").resources.filter(s => ids.contains(s.uniqueId))
            payload.addProperty("expression", resources.map(com.github.vbmacher.intellij.dbt.lineage.DbtSelection.selector(w, _)).mkString(" "))
            payload.addProperty("exclude", "")
            com.github.vbmacher.intellij.dbt.lineage.DbtSelection(resources, ids.filterNot(id => resources.exists(_.uniqueId == id)).map("Unavailable CLI resource: " + _))
          }
        }
        val selected = result.toVector.flatMap(_.resources).map(s => DbtGraphData.key(w, s)).toSet
        val targets = DbtGraphData.build(w, None, 1, 1, exposures = true).targets
        val ids = targets.collect { case (key, e) if selected(DbtGraphData.key(w, e.symbol)) => key }.toVector
        payload.add("ids", new Gson().toJsonTree(ids.asJava))
        payload.add("diagnostics", new Gson().toJsonTree(result.map(_.problems).getOrElse(Vector("Choose an owning dbt project in the Runner.")).asJava))
        payload
      }
    }).expireWith(this).coalesceBy(this, "selector-filter").finishOnUiThread(ModalityState.any(), (payload: JsonObject) =>
      if (request == reviewRequest) browser.foreach(_.showFilter(payload))).submit(AppExecutorUtil.getAppExecutorService)
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
  def showColumn(id: String, data: JsonObject): Unit = browser.runJavaScript("window.dbtShowColumn&&window.dbtShowColumn(" + new Gson().toJson(id) + "," + decode(data.toString) + ")")
  def showFilter(data: JsonObject): Unit = browser.runJavaScript("window.dbtShowFilter&&window.dbtShowFilter(" + decode(data.toString) + ")")

  def runnerState(running: Boolean, message: String): Unit = browser.runJavaScript("window.dbtRunnerState&&window.dbtRunnerState(" + running + "," + new Gson().toJson(message) + ")")
}
