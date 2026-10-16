package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project._
import com.google.gson.{Gson, JsonObject, JsonParser}
import com.intellij.icons.AllIcons
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.{ApplicationManager, ModalityState, ReadAction, WriteIntentReadAction}
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.fileEditor.{FileDocumentManager, FileEditorManager, FileEditorManagerEvent, FileEditorManagerListener}
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.{Disposer, IconLoader}
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.{VirtualFile, VirtualFileManager}
import com.intellij.openapi.wm.{ToolWindow, ToolWindowFactory}
import com.intellij.psi.{PsiDocumentManager, PsiManager}
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.ui.components.{JBLabel, JBScrollPane, JBTextArea, JBTextField}
import com.intellij.ui.{JBColor, OnePixelSplitter}
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.{JBCefApp, JBCefBrowser, JBCefBrowserBase, JBCefJSQuery}
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.IconUtil
import java.awt.{BorderLayout, FlowLayout}
import java.awt.event.{FocusAdapter, FocusEvent}
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.Callable
import java.util.concurrent.atomic.AtomicLong
import javax.swing.{JButton, JCheckBox, JComboBox, JPanel}
import javax.imageio.ImageIO
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

final class DbtToolWindowFactory extends ToolWindowFactory {
  override def createToolWindowContent(project: Project, toolWindow: ToolWindow): Unit = {
    val panel = new DbtToolWindowPanel(project)
    val manager = toolWindow.getContentManager
    Vector("Lineage" -> panel.lineagePanel, "Runner" -> panel.runnerPanel, "Status" -> panel.statusPanel).foreach { case (name, component) =>
      val content = ContentFactory.getInstance().createContent(component, name, false)
      manager.addContent(content)
    }
    Disposer.register(manager, panel)
    panel.refresh(follow = true)
  }
}

private[ide] final case class DbtViewContext(root: Option[String], model: Option[String], incremental: Boolean,
  targets: Vector[String], target: String, status: String)

/** All PSI/artifact work is performed in cancellable nonblocking read actions. Only immutable UI payloads cross to the EDT. */
private[ide] final class DbtToolWindowPanel(project: Project) extends Disposable {
  val lineagePanel = new JPanel(new BorderLayout)
  val runnerPanel = new JPanel(new BorderLayout)
  val statusPanel = new JPanel(new BorderLayout)
  private val settings = DbtProjectSettings.get(project)
  private val runner = project.getService(classOf[DbtRunner])
  private val contextLabel = new JBLabel("Open a dbt model to run commands.")
  private val target = new JComboBox[String]()
  private val executableArguments = new JBTextField(24)
  private val fullRefresh = new JCheckBox("Full Refresh")
  private val stop = new JButton("Stop", AllIcons.Actions.Suspend)
  private val preview = new JBTextArea
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
      unavailable.setEditable(false);
      unavailable.setLineWrap(true);
      unavailable.setWrapStyleWord(true)
      lineagePanel.add(unavailable, BorderLayout.CENTER)
  }
  executableArguments.setName("dbt.executableArguments")
  target.setToolTipText("Select the dbt target for the current execution root")
  fullRefresh.setToolTipText("Rebuild incremental models from scratch for Run and Build")
  executableArguments.setToolTipText("Arguments passed before the dbt command. Quote arguments containing spaces.")
  executableArguments.addActionListener(_ => saveExecutableArguments())
  executableArguments.addFocusListener(new FocusAdapter {
    override def focusLost(event: FocusEvent): Unit = saveExecutableArguments()
  })
  val configuration = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 3))
  configuration.add(new JBLabel("Target:"));
  configuration.add(target)
  val argumentsLabel = new JBLabel("Executable arguments:");
  argumentsLabel.setLabelFor(executableArguments)
  configuration.add(argumentsLabel);
  configuration.add(executableArguments)
  DbtCommand.Actions.foreach { name =>
    val (icon, tooltip) = name match {
      case "Run" => AllIcons.Actions.Execute -> "Run the current dbt model"
      case "Build" => AllIcons.Actions.Compile -> "Build and test the current dbt model"
      case "Compile" => AllIcons.Actions.Compile -> "Compile the current dbt model"
      case "Test" => AllIcons.RunConfigurations.TestState.Run -> "Run tests for the current dbt model"
      case "Preview" => AllIcons.Actions.Preview -> "Preview selected SQL or the current dbt model"
      case "Generate Docs" => AllIcons.Actions.PreviewDetails -> "Generate documentation artifacts for the current dbt project and target"
    }
    val button = new JButton(name, icon);
    button.setToolTipText(tooltip)
    commands(name) = button;
    button.addActionListener(_ => start(name));
    toolbar.add(button)
    if (name == "Build") toolbar.add(fullRefresh)
  }
  val clear = new JButton("Clear", AllIcons.Actions.GC);
  clear.setToolTipText("Clear the command log and preview")
  clear.addActionListener(_ => {
    runner.console.clear();
    preview.setText("")
  });
  toolbar.add(clear)
  stop.setToolTipText("Cancel the running dbt command")
  stop.addActionListener(_ => runner.stop());
  stop.setEnabled(false);
  toolbar.add(stop)
  val configure = new JButton("Settings", AllIcons.General.Settings);
  configure.setToolTipText("Configure dbt Workspace settings")
  configure.addActionListener(_ => ShowSettingsUtil.getInstance().showSettingsDialog(project, "dbt Workspace"));
  toolbar.add(configure)
  val north = new JPanel(new BorderLayout);
  north.add(configuration, BorderLayout.NORTH);
  north.add(toolbar, BorderLayout.CENTER);
  north.add(contextLabel, BorderLayout.SOUTH)
  runnerPanel.add(north, BorderLayout.NORTH)
  preview.setEditable(false);
  preview.setLineWrap(false);
  preview.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12))
  val output = new OnePixelSplitter(true, .75f)
  output.setFirstComponent(runner.console.getComponent)
  output.setSecondComponent(new JBScrollPane(preview))
  runner.console match {
    case console: com.intellij.execution.impl.ConsoleViewImpl => console.getEditor.getSettings.setUseSoftWraps(true)
    case _ => ()
  }
  runnerPanel.add(output, BorderLayout.CENTER)
  status.setEditable(false);
  status.setLineWrap(true);
  status.setWrapStyleWord(true);
  statusPanel.add(new JBScrollPane(status), BorderLayout.CENTER)
  fullRefresh.setVisible(false)
  target.addActionListener(_ => if (!fillingTarget) context.root.foreach { path =>
    settings.options(path).target = Option(target.getSelectedItem).map(_.toString).getOrElse("")
    settings.changed();
    schedule(follow = false)
  })
  runner.listen(this, event => {
    updateButtons();
    browser.foreach(_.runnerState(event.running, event.message))
    event.preview.foreach(preview.setText)
    if (event.refresh) schedule(follow = false)
  })
  val connection = project.getMessageBus.connect(this)
  connection.subscribe(DbtProjectSettings.Changed, new DbtSettingsListener {
    override def settingsChanged(): Unit = schedule(follow = false)
  })
  connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, new FileEditorManagerListener {
    override def fileOpened(source: FileEditorManager, file: VirtualFile): Unit = schedule(follow = false)

    override def selectionChanged(event: FileEditorManagerEvent): Unit = {
      saveExecutableArguments()
      fullRefresh.setSelected(false)
      context = DbtViewContext(None, None, incremental = false, Vector.empty, "", "")
      contextLabel.setText("Updating current model and target…")
      updateButtons()
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

  private def saveExecutableArguments(): Unit = context.root.foreach { path =>
    val options = settings.options(path)
    val value = executableArguments.getText.trim
    if (options.executableArguments != value) {
      options.executableArguments = value;
      settings.changed()
    }
  }

  private def schedule(follow: Boolean): Unit = ApplicationManager.getApplication.invokeLater(() => if (!disposed) {
    followPending ||= follow
    alarm.cancelAllRequests();
    alarm.addRequest(() => {
      val next = followPending;
      followPending = false;
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
    val up = state.upstreamDepth.max(1).min(20) + upstreamExtra
    val down = state.downstreamDepth.max(1).min(20) + downstreamExtra
    ReadAction.nonBlocking(new Callable[(DbtGraphSnapshot, DbtViewContext)] {
      override def call(): (DbtGraphSnapshot, DbtViewContext) = {
        val w = DbtWorkspace.get(project)
        val psi = file.flatMap(f => Option(PsiManager.getInstance(project).findFile(f)))
        val owner = psi.flatMap(w.root).filter(r => psi.exists(f => !r.excluded(f.getVirtualFile))).map(w.executionRoot)
        val model = psi.toVector.flatMap(w.codeSymbols).filter(_.kind == "model").headOption
        val nextFocus = if (follow || oldFocus.isEmpty) model.map(DbtGraphData.key(w, _)).orElse(oldFocus) else oldFocus
        val graph = DbtGraphData.build(w, nextFocus, if (nextFocus != oldFocus) state.upstreamDepth.max(1).min(20) else up,
          if (nextFocus != oldFocus) state.downstreamDepth.max(1).min(20) else down, state.exposures)
        val data = model.map(DbtResourceData.get(w, _))
        val profile = owner.map(DbtExecutionContext.profiles(_))
        val selectedTarget = owner.flatMap(DbtExecutionContext.activeTarget).getOrElse("")
        val artifacts = owner.map { root =>
          val manifest = root.setting("manifest").flatMap(root.psiFile)
          manifest.fold("Manifest unavailable. Generate Docs for this project/target.")(m =>
            "Manifest: " + m.getVirtualFile.getPath + "\nFile updated: " + java.time.Instant.ofEpochMilli(m.getVirtualFile.getTimeStamp) +
              (if (DbtArtifactContext.matches(root)) "\nPhysical relation context verified." else "\nPhysical relation target unverified; Generate Docs before clipboard conversion.")) +
            "\n" + DbtDeclarationSource.EP.getExtensionList.asScala.toVector.flatMap(_.artifactStatus(root)).mkString("\n")
        }.getOrElse("Select a file in a dbt project.")
        val summary = owner.map(r => "Executable: " + DbtExecutionContext.executable(r)).getOrElse("Executable: unavailable") +
          "\nCLI version: " + data.flatMap(_.attributes.get("dbt_version")).getOrElse("Not available in the manifest; see Runner output.") +
          "\nTarget: " + selectedTarget + "\nAdapter: " + profile.flatMap(_.adapter).getOrElse("Unavailable") +
          "\nModel: " + model.map(_.presentableText).getOrElse("None") + "\nResources: " + graph.payload.getAsJsonArray("nodes").size() + "\n" + artifacts
        val incremental = model.exists(_.attributes.get("materialized").contains("incremental")) || data.exists(_.attributes.get("materialized").contains("incremental"))
        graph -> DbtViewContext(owner.map(_.directory.getPath), model.map(_.uniqueId), incremental, profile.toVector.flatMap(_.targets), selectedTarget, summary)
      }
    }).inSmartMode(project).expireWith(this).coalesceBy(graphWork).finishOnUiThread(ModalityState.any(), (result: (DbtGraphSnapshot, DbtViewContext)) =>
      if (!disposed && request == generation.get()) {
        val (snapshot, next) = result
        val changedRoot = context.root != next.root
        val changedModel = context.model != next.model || context.root != next.root
        context = next
        if (changedRoot || !executableArguments.isFocusOwner)
          executableArguments.setText(context.root.map(settings.options(_).executableArguments).getOrElse(""))
        if (changedModel || !context.incremental) fullRefresh.setSelected(false)
        fullRefresh.setVisible(context.incremental)
        fillingTarget = true;
        target.removeAllItems();
        target.addItem("");
        context.targets.foreach(target.addItem);
        target.setSelectedItem(context.target);
        fillingTarget = false
        contextLabel.setText(context.model.getOrElse("No current model") + " · target: " + (if (context.target.isEmpty) "profile default" else context.target))
        status.setText(context.status)
        val payload = snapshot.payload
        val nextFocus = Option(payload.get("focus")).map(_.getAsString).filter(_.nonEmpty)
        if (nextFocus != focus) {
          upstreamExtra = 0;
          downstreamExtra = 0
        }
        focus = nextFocus
        payload.addProperty("direction", settings.getState.direction);
        payload.addProperty("edgeStyle", settings.getState.edgeStyle)
        payload.addProperty("docsVisible", settings.getState.docsVisible);
        payload.addProperty("docsWidth", settings.getState.docsWidth)
        payload.addProperty("running", runner.isRunning)
        payload.addProperty("status", if (runner.isRunning) "dbt command running…" else if (context.model.nonEmpty && !snapshot.payload.getAsJsonArray("nodes").asScala.exists(n =>
          Option(n.getAsJsonObject.get("focus")).exists(_.getAsBoolean) && Option(n.getAsJsonObject.get("manifest")).exists(_.getAsBoolean))) "Model missing from manifest — Generate Docs" else context.model.map("Focused: " + _).getOrElse("Select a model or graph resource"))
        val theme = new JsonObject

        def color(c: java.awt.Color): String = f"#${c.getRed}%02x${c.getGreen}%02x${c.getBlue}%02x"

        val scheme = EditorColorsManager.getInstance().getGlobalScheme
        theme.addProperty("bg", color(scheme.getDefaultBackground));
        theme.addProperty("fg", color(scheme.getDefaultForeground))
        theme.addProperty("panel", color(javax.swing.UIManager.getColor("Panel.background")))
        payload.add("theme", theme)
        browser.foreach(_.update(payload))
        updateButtons()
      }
    ).submit(AppExecutorUtil.getAppExecutorService)
  }

  private def updateButtons(): Unit = {
    commands.foreach { case (name, button) => button.setEnabled(!runner.isRunning && context.root.nonEmpty && (name == "Generate Docs" || name == "Preview" || context.model.nonEmpty)) }
    stop.setEnabled(runner.isRunning);
    target.setEnabled(!runner.isRunning);
    fullRefresh.setEnabled(!runner.isRunning)
    executableArguments.setEnabled(!runner.isRunning && context.root.nonEmpty)
  }

  private def start(action: String): Unit = WriteIntentReadAction.run(() => {
    saveExecutableArguments()
    FileDocumentManager.getInstance().saveAllDocuments()
    val editor = Option(FileEditorManager.getInstance(project).getSelectedTextEditor)
    val selectedSql = editor.flatMap(e => Option(e.getSelectionModel.getSelectedText)).getOrElse("")
    val activeFile = editor.flatMap(e => Option(FileDocumentManager.getInstance().getFile(e.getDocument)))
      .orElse(FileEditorManager.getInstance(project).getSelectedFiles.headOption)
    val fullRefreshIdentity = if (fullRefresh.isSelected) focus else None
    ReadAction.nonBlocking(new Callable[Either[String, DbtCommand]] {
      override def call(): Either[String, DbtCommand] = {
        val w = DbtWorkspace.get(project)
        activeFile.flatMap(f => Option(PsiManager.getInstance(project).findFile(f))).toRight("Open a file in a dbt project.").flatMap { file =>
          val refreshModel = w.codeSymbols(file).find(s => s.kind == "model" && fullRefreshIdentity.contains(DbtGraphData.key(w, s))).map(_.id)
          DbtCommand.forFile(w, file, action, selectedSql, refreshModel)
        }
      }
    }).expireWith(this).inSmartMode(project).finishOnUiThread(ModalityState.defaultModalityState(), (result: Either[String, DbtCommand]) => result match {
      case Right(command) => WriteIntentReadAction.run(() => {
        FileDocumentManager.getInstance().saveAllDocuments()
        runner.start(command)
      })
      case Left(message) => contextLabel.setText(message)
    }).submit(AppExecutorUtil.getAppExecutorService)
  })

  private def handleMessage(raw: String): Unit = {
    if (disposed) return
    val message = JsonParser.parseString(raw).getAsJsonObject

    def field(name: String): String = Option(message.get(name)).map(_.getAsString).getOrElse("")

    val kind = field("type");
    val id = field("id")
    kind match {
      case "ready" => refresh(follow = true)
      case "select" | "focus" =>
        val request = docGeneration.incrementAndGet()
        browser.foreach(_.clearDoc())
        ReadAction.nonBlocking(new Callable[Option[(JsonObject, DbtSymbolElement)]] {
          override def call(): Option[(JsonObject, DbtSymbolElement)] = {
            val w = DbtWorkspace.get(project)
            // Rebuild the identity map rather than retaining PSI objects in the browser callback.
            DbtGraphData.build(w, None, 1, 1, exposures = true).targets.get(id).map(e => DbtGraphData.documentation(w, e.symbol) -> e)
          }
        }).expireWith(this).inSmartMode(project).coalesceBy(docWork).finishOnUiThread(ModalityState.any(), (result: Option[(JsonObject, DbtSymbolElement)]) =>
          if (!disposed && request == docGeneration.get()) result.foreach { case (doc, targetElement) =>
            browser.foreach(_.showDoc(id, doc))
            if (kind == "focus") {
              focus = Some(id);
              upstreamExtra = 0;
              downstreamExtra = 0;
              targetElement.navigate(true);
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
    disposed = true;
    generation.incrementAndGet();
    docGeneration.incrementAndGet();
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

  def clearDoc(): Unit = () // The browser clears immediately when selection changes, before requesting fresh data.

  def runnerState(running: Boolean, message: String): Unit = browser.runJavaScript("window.dbtRunnerState&&window.dbtRunnerState(" + running + "," + new Gson().toJson(message) + ")")
}
