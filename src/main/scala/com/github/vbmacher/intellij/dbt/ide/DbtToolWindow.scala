package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project._
import com.google.gson.{Gson, JsonObject, JsonParser}
import com.intellij.icons.AllIcons
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.{ApplicationManager, ModalityState, ReadAction}
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.fileEditor.{FileDocumentManager, FileEditorManager, FileEditorManagerEvent, FileEditorManagerListener}
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.{Disposer, IconLoader}
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.{VirtualFile, VirtualFileManager}
import com.intellij.openapi.wm.{ToolWindow, ToolWindowFactory}
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.JBColor
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.{JBCefApp, JBCefBrowser, JBCefBrowserBase, JBCefJSQuery}
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.IconUtil
import java.awt.BorderLayout
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.Callable
import java.util.concurrent.atomic.AtomicLong
import javax.swing.JPanel
import javax.imageio.ImageIO
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

final class DbtToolWindowFactory extends ToolWindowFactory {
  override def createToolWindowContent(project: Project, toolWindow: ToolWindow): Unit = {
    val panel = new DbtToolWindowPanel(project)
    toolWindow.getContentManager.addContent(ContentFactory.getInstance().createContent(panel.lineagePanel, "Lineage", false))
    Disposer.register(toolWindow.getContentManager, panel)
    panel.refresh(follow = true)
  }
}

/** The dbt window owns graph interaction. Execution and documentation use the IDE's native windows. */
private[ide] final class DbtToolWindowPanel(project: Project) extends Disposable {
  val lineagePanel = new JPanel(new BorderLayout)
  private val settings = DbtProjectSettings.get(project)
  private val runner = project.getService(classOf[DbtRunner])
  private val alarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
  private val generation = new AtomicLong
  private val graphWork = new Object
  private var focus = Option(settings.getState.pinnedFocus).filter(_.nonEmpty)
  private[ide] var lastSnapshot: Option[DbtGraphSnapshot] = None
  private var executionRoot = Option.empty[String]
  private var upstreamExtra = 0
  private var downstreamExtra = 0
  private var disposed = false
  private var followPending = false
  private val browser = try {
    if (!JBCefApp.isSupported()) None else Some(new DbtLineageBrowser(this, handleMessage))
  } catch { case NonFatal(_) => None }
  browser match {
    case Some(view) => lineagePanel.add(view.component, BorderLayout.CENTER)
    case None =>
      val unavailable = new JBTextArea("The graph requires the IDE's embedded JCEF browser. Use Call Hierarchy for dependencies, Documentation for resource details, and Run configurations for dbt commands.")
      unavailable.setEditable(false); unavailable.setLineWrap(true); unavailable.setWrapStyleWord(true)
      lineagePanel.add(unavailable, BorderLayout.CENTER)
  }
  runner.listen(this, event => {
    browser.foreach(_.runnerState(event.running, event.message))
    if (!event.running) schedule(follow = false)
  })
  private val connection = project.getMessageBus.connect(this)
  connection.subscribe(DbtLineageSelection.Topic, new DbtLineageSelectionListener {
    override def showSelection(root: String, expression: String, excluded: String): Unit = {
      executionRoot = Some(root)
      showSelectionInLineage(expression, excluded, fromConfiguration = true)
    }
  })
  connection.subscribe(DbtProjectSettings.Changed, new DbtSettingsListener {
    override def settingsChanged(): Unit = schedule(follow = false)
  })
  connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, new FileEditorManagerListener {
    override def fileOpened(source: FileEditorManager, file: VirtualFile): Unit = schedule(follow = false)
    override def selectionChanged(event: FileEditorManagerEvent): Unit = schedule(follow = true)
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
  private def schedule(follow: Boolean): Unit = ApplicationManager.getApplication.invokeLater(() => if (!disposed) {
    followPending ||= follow
    alarm.cancelAllRequests()
    alarm.addRequest(() => { val next = followPending; followPending = false; refresh(next) }, 180)
  })
  def refresh(follow: Boolean): Unit = {
    if (disposed) return
    val request = generation.incrementAndGet()
    val file = FileEditorManager.getInstance(project).getSelectedFiles.headOption
    val oldFocus = focus
    val state = settings.getState
    ReadAction.nonBlocking(new Callable[(DbtGraphSnapshot, Option[String])] {
      override def call(): (DbtGraphSnapshot, Option[String]) = {
        val w = DbtWorkspace.get(project)
        val editorModel = file.flatMap(f => Option(PsiManager.getInstance(project).findFile(f))).toVector
          .flatMap(w.codeSymbols).find(_.kind == "model")
        val nextFocus = if (follow && state.pinnedFocus.isEmpty || oldFocus.isEmpty)
          editorModel.map(DbtResourceIdentity.key(w, _)).orElse(oldFocus) else oldFocus
        val graph = DbtGraphData.build(w, nextFocus,
          state.upstream + (if (nextFocus == oldFocus) upstreamExtra else 0),
          state.downstream + (if (nextFocus == oldFocus) downstreamExtra else 0))
        val root = nextFocus.flatMap(graph.targets.get).flatMap(e => w.owner(e.symbol)).map(w.executionRoot)
          .orElse(if (w.roots.map(w.executionRoot).distinct.size == 1) w.roots.headOption.map(w.executionRoot) else None)
        graph -> root.map(_.directory.getPath)
      }
    }).inSmartMode(project).expireWith(this).coalesceBy(graphWork).finishOnUiThread(ModalityState.any(), result => {
      if (!disposed && request == generation.get()) {
        val (snapshot, root) = result
        lastSnapshot = Some(snapshot)
        executionRoot = root
        val payload = snapshot.payload
        val next = Option(payload.get("focus")).map(_.getAsString).filter(_.nonEmpty)
        if (next != focus) { upstreamExtra = 0; downstreamExtra = 0 }
        focus = next
        payload.addProperty("direction", state.direction)
        payload.addProperty("running", runner.isRunning)
        payload.addProperty("pinned", state.pinnedFocus.nonEmpty)
        payload.addProperty("canGenerateDocs", root.nonEmpty)
        payload.addProperty("status", if (runner.isRunning) "dbt command running…" else "Select a resource for native Documentation; double-click to open its definition.")
        val theme = new JsonObject
        def color(c: java.awt.Color): String = f"#${c.getRed}%02x${c.getGreen}%02x${c.getBlue}%02x"
        val scheme = EditorColorsManager.getInstance().getGlobalScheme
        theme.addProperty("bg", color(scheme.getDefaultBackground)); theme.addProperty("fg", color(scheme.getDefaultForeground))
        theme.addProperty("panel", color(javax.swing.UIManager.getColor("Panel.background")))
        payload.add("theme", theme); browser.foreach(_.update(payload))
      }
    }).submit(AppExecutorUtil.getAppExecutorService)
    ()
  }
  private[ide] def showSelectionInLineage(expression: String, excluded: String,
    fromConfiguration: Boolean = false): Unit = {
    val rootPath = executionRoot
    ReadAction.nonBlocking(new Callable[JsonObject] {
      override def call(): JsonObject = {
        val w = DbtWorkspace.get(project)
        val result = new JsonObject
        result.addProperty("expression", expression); result.addProperty("exclude", excluded)
        result.addProperty("fromConfiguration", fromConfiguration)
        val owner = w.roots.find(r => rootPath.contains(r.directory.getPath)).orElse(if (w.roots.size == 1) w.roots.headOption else None)
        val selection = owner.map(r => com.github.vbmacher.intellij.dbt.lineage.DbtSelection.resolve(w, r, expression, excluded))
        result.add("ids", new Gson().toJsonTree(selection.toVector.flatMap(_.resources).map(DbtResourceIdentity.key(w, _)).asJava))
        result.add("diagnostics", new Gson().toJsonTree(selection.toVector.flatMap(_.problems).asJava))
        result
      }
    }).expireWith(this).finishOnUiThread(ModalityState.any(), data => browser.foreach(_.showFilter(data)))
      .submit(AppExecutorUtil.getAppExecutorService)
    ()
  }
  private[ide] def handleMessage(raw: String): Unit = {
    if (disposed) return
    val message = JsonParser.parseString(raw).getAsJsonObject
    def field(name: String): String = Option(message.get(name)).map(_.getAsString).getOrElse("")
    val id = field("id")
    field("type") match {
      case "ready" => refresh(follow = true)
      case "select" | "focus" | "edit" | "column" =>
        val kind = field("type")
        ReadAction.nonBlocking(new Callable[Option[DbtSymbolElement]] {
          override def call(): Option[DbtSymbolElement] = {
            val w = DbtWorkspace.get(project)
            DbtGraphData.build(w, None, 1, 1).targets.get(id)
          }
        }).expireWith(this).inSmartMode(project).finishOnUiThread(ModalityState.defaultModalityState(), target => target.foreach { element =>
          if (kind == "focus") {
            focus = Some(id); if (settings.getState.pinnedFocus.nonEmpty) settings.getState.pinnedFocus = id
            upstreamExtra = 0; downstreamExtra = 0; element.navigate(true); refresh(follow = false)
          } else if (kind == "edit") DbtPropertiesEditor.show(DbtWorkspace.get(project), element.symbol)
          else DbtDocumentation.show(project, element.symbol)
        }).submit(AppExecutorUtil.getAppExecutorService)
      case "expand" => if (id == "upstream") { upstreamExtra += 5; refresh(follow = false) } else { downstreamExtra += 5; refresh(follow = false) }
      case "regenerate" => executionRoot.foreach(path => DbtRunConfiguration.edit(project, path, "Generate Docs"))
      case "pin" => settings.getState.pinnedFocus = if (field("value") == "true") focus.getOrElse(id) else ""; refresh(follow = false)
      case "use-configuration-selection" => executionRoot.foreach(path => DbtRunConfiguration.edit(project, path, "Build", id.take(4000), field("value").take(4000)))
      case "filter" => showSelectionInLineage(id, field("value"))
      case "export" => DbtGraphExport.show(project, id, field("value"))
      case "execution" => DbtRunConfiguration.openEvidence(project, id)
      case _ => ()
    }
  }
  override def dispose(): Unit = { disposed = true; generation.incrementAndGet(); () }
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
      "apply-filter" -> AllIcons.Actions.ShortcutFilter, "clear-filter" -> AllIcons.Actions.Close,
      "use-config" -> AllIcons.Actions.Execute, "pin-focus" -> AllIcons.Actions.PinTab,
      "copy-image" -> AllIcons.Actions.Copy, "export-png" -> AllIcons.Actions.Download,
      "export-svg" -> AllIcons.Actions.Download,
      "show-docs" -> AllIcons.Actions.PreviewDetails, "regenerate" -> AllIcons.Actions.Refresh).foreach { case (id, icon) =>
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

  def showFilter(data: JsonObject): Unit = browser.runJavaScript("window.dbtShowFilter&&window.dbtShowFilter(" + decode(data.toString) + ")")

  def runnerState(running: Boolean, message: String): Unit = browser.runJavaScript("window.dbtRunnerState&&window.dbtRunnerState(" + running + "," + new Gson().toJson(message) + ")")
}

private[ide] trait DbtLineageSelectionListener {
  def showSelection(root: String, expression: String, excluded: String): Unit
}
private[ide] object DbtLineageSelection {
  val Topic: com.intellij.util.messages.Topic[DbtLineageSelectionListener] =
    com.intellij.util.messages.Topic.create("dbt lineage selection", classOf[DbtLineageSelectionListener])
  def show(project: Project, root: String, expression: String, excluded: String): Unit = {
    val window = com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow("dbt")
    if (window != null) window.activate(() => project.getMessageBus.syncPublisher(Topic).showSelection(root, expression, excluded))
  }
}
