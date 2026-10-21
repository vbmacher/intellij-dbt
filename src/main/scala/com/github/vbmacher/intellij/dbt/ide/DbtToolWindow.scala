package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.ide.DbtAgentProtocol.{array, obj, strings}
import com.github.vbmacher.intellij.dbt.lineage.{DbtColumnLineage, DbtLineage, DbtSelection}
import com.github.vbmacher.intellij.dbt.project._
import com.google.gson.{Gson, JsonObject, JsonParser}
import com.intellij.icons.AllIcons
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.{ApplicationManager, ModalityState}
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.fileChooser.{FileChooserFactory, FileSaverDescriptor}
import com.intellij.openapi.fileEditor.{FileEditorManager, FileEditorManagerEvent, FileEditorManagerListener}
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProgressManager
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
import com.intellij.util.IconUtil
import java.awt.BorderLayout
import java.awt.datatransfer.{DataFlavor, Transferable, UnsupportedFlavorException}
import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import javax.swing.JPanel
import javax.imageio.ImageIO
import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.util.Try
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
    DbtUi.readLater(this, ModalityState.any(), Some(project), Some(graphWork)) {
      val w = DbtWorkspace.get(project)
      val editorModel = file.flatMap(f => Option(PsiManager.getInstance(project).findFile(f))).flatMap(w.model)
      val nextFocus = if (follow && state.pinnedFocus.isEmpty || oldFocus.isEmpty)
        editorModel.map(DbtResourceIdentity.key(w, _)).orElse(oldFocus) else oldFocus
      val graph = DbtGraphData.build(w, nextFocus,
        state.upstream + (if (nextFocus == oldFocus) upstreamExtra else 0),
        state.downstream + (if (nextFocus == oldFocus) downstreamExtra else 0))
      val root = nextFocus.flatMap(graph.targets.get).flatMap(e => w.owner(e.symbol)).map(w.executionRoot)
        .orElse(if (w.roots.map(w.executionRoot).distinct.size == 1) w.roots.headOption.map(w.executionRoot) else None)
      graph -> root.map(_.directory.getPath)
    } { case (snapshot, root) =>
      if (!disposed && request == generation.get()) {
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
        def color(c: java.awt.Color): String = f"#${c.getRed}%02x${c.getGreen}%02x${c.getBlue}%02x"
        val scheme = EditorColorsManager.getInstance().getGlobalScheme
        payload.add("theme", obj("bg" -> color(scheme.getDefaultBackground), "fg" -> color(scheme.getDefaultForeground),
          "panel" -> color(javax.swing.UIManager.getColor("Panel.background"))))
        browser.foreach(_.update(payload))
      }
    }
  }
  private[ide] def showSelectionInLineage(expression: String, excluded: String,
    fromConfiguration: Boolean = false): Unit = {
    val rootPath = executionRoot
    DbtUi.readLater(this, ModalityState.any()) {
      val w = DbtWorkspace.get(project)
      val owner = w.roots.find(r => rootPath.contains(r.directory.getPath)).orElse(if (w.roots.size == 1) w.roots.headOption else None)
      val selection = owner.map(r => DbtSelection.resolve(w, r, expression, excluded))
      obj("expression" -> expression, "exclude" -> excluded, "fromConfiguration" -> fromConfiguration,
        "ids" -> strings(selection.toVector.flatMap(_.resources).map(DbtResourceIdentity.key(w, _))),
        "diagnostics" -> strings(selection.toVector.flatMap(_.problems)))
    }(data => browser.foreach(_.showFilter(data)))
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
        DbtUi.readLater(this, smartIn = Some(project))(DbtGraphData.build(DbtWorkspace.get(project), None, 1, 1).targets.get(id)) {
          _.foreach { element =>
            if (kind == "focus") {
              focus = Some(id); if (settings.getState.pinnedFocus.nonEmpty) settings.getState.pinnedFocus = id
              upstreamExtra = 0; downstreamExtra = 0; element.navigate(true); refresh(follow = false)
            } else if (kind == "edit") DbtPropertiesEditor.show(DbtWorkspace.get(project), element.symbol)
            else DbtDocumentation.show(project, element.symbol)
          }
        }
      case "expand" => if (id == "upstream") upstreamExtra += 5 else downstreamExtra += 5; refresh(follow = false)
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

private[ide] final case class DbtGraphSnapshot(payload: JsonObject, targets: Map[String, DbtSymbolElement])
private[ide] object DbtGraphData {
  private val gson = new Gson

  def build(w: DbtWorkspace, focus: Option[String], upstream: Int, downstream: Int): DbtGraphSnapshot = {
    val resources = w.symbols.filter(s => DbtResourceData.GraphKinds(s.kind) && s.isEnabled)
      .groupBy(DbtResourceIdentity.key(w, _)).values.toVector.flatMap { candidates =>
        val source = candidates.filterNot(_.metadata)
        val preferred = if (source.nonEmpty) source else candidates
        val code = preferred.filter(_.isCode)
        if (code.nonEmpty) code else preferred.filterNot(s => s.isProperty && preferred.exists(t => t != s && !t.isProperty))
      }
    // Resource ids include the declaration location when there are genuinely ambiguous declarations.
    val groups = resources.groupBy(DbtResourceIdentity.key(w, _))
    def id(s: DbtSymbol): String = {
      val k = DbtResourceIdentity.key(w, s)
      if (groups.getOrElse(k, Vector.empty).size > 1) k + "|" + s.id else k
    }
    val byId = resources.map(s => id(s) -> s).toMap
    def canonical(s: DbtSymbol): Vector[String] = groups.getOrElse(DbtResourceIdentity.key(w, s), Vector.empty).map(id)
    val edges = DbtLineage.edges(w).filter(e => DbtResourceData.GraphKinds(e.from.kind) && DbtResourceData.GraphKinds(e.to.kind))
      .flatMap(e => for (from <- canonical(e.to); to <- canonical(e.from) if from != to) yield (from, to, e.ambiguous)).distinct
    val start = focus.filter(byId.contains).orElse(focus.flatMap(k => resources.find(s => DbtResourceIdentity.key(w, s) == k).map(id)))
    def walk(down: Boolean, depth: Int): Set[String] = {
      val adjacency = edges.groupMap(e => if (down) e._1 else e._2)(e => if (down) e._2 else e._1)
      val seen = mutable.Set.empty[String] ++ start
      var frontier = start.toVector
      var level = 0
      while (frontier.nonEmpty && level < depth) {
        ProgressManager.checkCanceled()
        frontier = frontier.flatMap(s => adjacency.getOrElse(s, Vector.empty)).filter(seen.add)
        level += 1
      }
      seen.toSet
    }
    val visible = if (start.isEmpty) byId.keySet else walk(down = false, upstream) ++ walk(down = true, downstream)
    val evidence = w.project.getService(classOf[DbtExecutionEvidence])
    val testDefinitions = DbtTestDiscovery.discover(w)
    val executionRuns = w.roots.map(w.executionRoot).distinct.map(r => r.directory.getPath -> evidence.runs(r)).toMap
    val nodes = resources.filter(s => visible(id(s))).sortBy(s => (s.namespace, s.name, s.version)).map { s =>
      ProgressManager.checkCanceled()
      val data = DbtResourceData.get(w, s)
      val executionRoot = w.owner(s).map(w.executionRoot).map(_.directory.getPath)
      val runs = executionRoot.flatMap(executionRuns.get).getOrElse(Vector.empty)
      obj("id" -> id(s), "name" -> (s.name + s.version.map(" v" + _).getOrElse("")),
        "kind" -> s.kind, "schema" -> data.attributes.getOrElse("schema", ""),
        "package" -> s.namespace, "materialized" -> data.attributes.getOrElse("materialized", ""),
        "description" -> data.description, "focus" -> start.contains(id(s)),
        "folder" -> w.owner(s).flatMap(_.relative(s.file.getVirtualFile)).getOrElse("").split('/').dropRight(1).mkString("/"),
        "tags" -> strings(data.tags), "manifest" -> data.attributes.contains("artifact-project"),
        "candidate" -> (groups(DbtResourceIdentity.key(w, s)).size > 1),
        "execution" -> evidence.overlay(s, runs, testDefinitions.filter(t => executionRoot.contains(t.root))))
    }
    val links = edges.filter(e => visible(e._1) && visible(e._2)).groupBy(e => (e._1, e._2)).toVector.sortBy(_._1).map {
      case ((from, to), candidates) => obj("source" -> from, "target" -> to, "candidate" -> candidates.exists(_._3))
    }
    val boundary = Vector(false, true).flatMap { down =>
      val remaining = if (start.isEmpty) Set.empty[String] else walk(down, Int.MaxValue) -- visible
      Option.when(remaining.nonEmpty)(obj("direction" -> (if (down) "downstream" else "upstream"), "count" -> remaining.size,
        "anchors" -> strings(edges.collect {
          case (from, to, _) if visible(from) && remaining(to) => from
          case (from, to, _) if remaining(from) && visible(to) => to
        }.distinct)))
    }
    val payload = obj("nodes" -> array(nodes), "edges" -> array(links), "boundary" -> array(boundary), "focus" -> start.getOrElse(""),
      "empty" -> (if (resources.isEmpty) "No dbt resources found. Open a dbt project or check its paths and artifact settings." else ""))
    DbtGraphSnapshot(payload, byId.view.mapValues(_.element).toMap)
  }

  def documentation(w: DbtWorkspace, s: DbtSymbol): JsonObject = {
    val data = DbtResourceData.get(w, s)
    val columns = data.columns.map { c =>
      val origins = DbtColumnLineage.upstream(w, s, c.name).map { edge =>
        obj("resource" -> (edge.input.namespace + "." + edge.input.owner), "column" -> edge.input.name, "direct" -> edge.direct,
          "kind" -> edge.kind, "complete" -> edge.complete, "expression" -> edge.expression,
          "path" -> edge.input.file.getVirtualFile.getPath, "offset" -> edge.input.range.getStartOffset)
      }
      obj("name" -> c.name, "type" -> c.dataType, "description" -> c.description, "declarations" -> strings(c.declarations),
        "origins" -> array(origins))
    }
    val (upstream, downstream) = DbtDocumentation.dependencies(w, s)
    obj("name" -> DbtDocumentation.displayName(s), "kind" -> s.kind, "description" -> data.description,
      "shortName" -> (s.name + s.version.map(" v" + _).getOrElse("")), "identity" -> s.uniqueId,
      "source" -> s.file.getVirtualFile.getPath, "logical" -> s.relationName,
      "editable" -> (!s.metadata && s.file.isWritable && w.owner(s).exists(w.packageOwner(_).isEmpty)),
      "rawSql" -> data.rawSql.orNull, "compiledSql" -> data.compiledSql.orNull,
      "tags" -> strings(data.tags), "attributes" -> gson.toJsonTree(data.attributes.asJava), "columns" -> array(columns),
      "tests" -> array(data.tests.map(t => obj("name" -> t.name, "column" -> t.column))),
      "airflow" -> DbtAirflow.details(w, s), "upstream" -> strings(upstream), "downstream" -> strings(downstream))
  }
}

private[ide] object DbtGraphExport {
  private[ide] val PngFlavor = new DataFlavor("image/png;class=java.io.InputStream", "PNG image")

  private[ide] def copyPng(bytes: Array[Byte], publish: Transferable => Unit): Unit =
    Option(ImageIO.read(new ByteArrayInputStream(bytes))).foreach { image =>
      val png = bytes.clone()
      def content(withImage: Boolean): Transferable = new Transferable {
        override def getTransferDataFlavors: Array[DataFlavor] =
          if (withImage) Array(PngFlavor, DataFlavor.imageFlavor) else Array(PngFlavor)
        override def isDataFlavorSupported(flavor: DataFlavor): Boolean =
          flavor == PngFlavor || withImage && flavor == DataFlavor.imageFlavor
        override def getTransferData(flavor: DataFlavor): AnyRef =
          if (flavor == PngFlavor) new ByteArrayInputStream(png)
          else if (withImage && flavor == DataFlavor.imageFlavor) image
          else throw new UnsupportedFlavorException(flavor)
      }
      try publish(content(withImage = true))
      catch {
        // Some native image mappings fail while enumerating unrelated ImageIO writers.
        // Publish the existing PNG bytes directly, without changing global providers or mappings.
        case NonFatal(_) => publish(content(withImage = false))
      }
    }

  def show(project: Project, format: String, value: String): Unit = {
    val kind = if (format == "clipboard") "png" else format
    val prefix = if (kind == "png") "data:image/png;base64," else "data:image/svg+xml;base64,"
    if (!Set("png", "svg")(kind) || !value.startsWith(prefix) || value.length > 28000000) return
    Try(Base64.getDecoder.decode(value.substring(prefix.length))).toOption.foreach { bytes =>
      if (format == "clipboard") copyPng(bytes, CopyPasteManager.getInstance().setContents)
      else Option(FileChooserFactory.getInstance().createSaveFileDialog(new FileSaverDescriptor("Export dbt lineage", "Save the visible graph", kind), project)
        .save("lineage." + kind)).foreach(file => Files.write(file.getFile.toPath, bytes))
    }
  }
}
