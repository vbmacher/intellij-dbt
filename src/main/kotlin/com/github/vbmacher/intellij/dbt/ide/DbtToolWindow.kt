package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.lineage.DbtColumnLineage
import com.github.vbmacher.intellij.dbt.lineage.DbtLineage
import com.github.vbmacher.intellij.dbt.lineage.DbtSelection
import com.github.vbmacher.intellij.dbt.project.*
import com.github.vbmacher.intellij.dbt.project.DbtJson.array
import com.github.vbmacher.intellij.dbt.project.DbtJson.obj
import com.github.vbmacher.intellij.dbt.project.DbtJson.strings
import com.github.vbmacher.intellij.dbt.syntax.DbtYaml
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.icons.AllIcons
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.JBColor
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.Alarm
import com.intellij.util.IconUtil
import java.awt.BorderLayout
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import javax.swing.JPanel
import javax.imageio.ImageIO

class DbtToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = DbtToolWindowPanel(project)
        toolWindow.contentManager.addContent(
            ContentFactory.getInstance().createContent(panel.lineagePanel, "Lineage", false)
        )
        toolWindow.contentManager.addContent(
            ContentFactory.getInstance().createContent(panel.exposuresPanel, "Exposures", false)
        )
        Disposer.register(toolWindow.contentManager, panel)
        panel.refresh(follow = true)
    }
}

/** The dbt window owns lineage, its resource details and consumer browsing. Execution uses native windows. */
internal class DbtToolWindowPanel(private val project: Project) : Disposable {
    val lineagePanel = JPanel(BorderLayout())
    val exposuresPanel = DbtConsumersPanel(project)
    private val settings = DbtProjectSettings.get(project)
    private val runner = project.getService(DbtRunner::class.java)
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val generation = AtomicLong()
    private val documentationGeneration = AtomicLong()
    private val graphWork = Any()
    private var focus = settings.getState().pinnedFocus.takeIf { it.isNotEmpty() }
    var lastSnapshot: DbtGraphSnapshot? = null
    private var executionRoot: String? = null
    private var upstreamExtra = 0
    private var downstreamExtra = 0
    private var disposed = false
    private var followPending = false
    private val browser = try {
        if (!JBCefApp.isSupported()) null else DbtLineageBrowser(this, ::handleMessage)
    } catch (_: Exception) {
        null
    }
    private val connection = project.messageBus.connect(this)

    init {
        Disposer.register(this, exposuresPanel)
        if (browser != null) lineagePanel.add(browser.component, BorderLayout.CENTER) else {
            val unavailable =
                JBTextArea("The graph requires the IDE's embedded JCEF browser. Use Call Hierarchy for dependencies, Documentation for resource details, and Run configurations for dbt commands.")
            unavailable.isEditable = false; unavailable.lineWrap = true; unavailable.wrapStyleWord = true
            lineagePanel.add(unavailable, BorderLayout.CENTER)
        }
        runner.listen(this) { event ->
            browser?.runnerState(event.running, event.message); if (!event.running) schedule(
            follow = false
        )
        }
        connection.subscribe(DbtLineageSelection.Topic, object : DbtLineageSelectionListener {
            override fun showSelection(root: String, expression: String, excluded: String) {
                executionRoot = root; showSelectionInLineage(expression, excluded, fromConfiguration = true)
            }
        })
        connection.subscribe(DbtProjectSettings.Changed, object : DbtSettingsListener {
            override fun settingsChanged() = schedule(follow = false)
        })
        connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun fileOpened(source: FileEditorManager, file: VirtualFile) = schedule(follow = false)
            override fun selectionChanged(event: FileEditorManagerEvent) = schedule(follow = true)
        })
        connection.subscribe(PsiModificationTracker.TOPIC, object : PsiModificationTracker.Listener {
            override fun modificationCountChanged() = schedule(follow = false)
        })
        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) = schedule(follow = false)
        })
        connection.subscribe(LafManagerListener.TOPIC, LafManagerListener { schedule(follow = false) })
        connection.subscribe(
            EditorColorsManager.TOPIC,
            com.intellij.openapi.editor.colors.EditorColorsListener { schedule(follow = false) })
    }

    private fun schedule(follow: Boolean) {
        ApplicationManager.getApplication().invokeLater {
            if (!disposed) {
                followPending = followPending || follow
                alarm.cancelAllRequests()
                alarm.addRequest({ val next = followPending; followPending = false; refresh(next) }, 180)
            }
        }
    }

    /** Captures editor context and builds a graph off the EDT; only the newest request may replace the visible snapshot. */
    fun refresh(follow: Boolean) {
        if (disposed) return
        val request = generation.incrementAndGet()
        val file = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
        val offset = FileEditorManager.getInstance(project).selectedTextEditor?.caretModel?.offset ?: 0
        exposuresPanel.showResource(file, offset)
        val oldFocus = focus
        val state = settings.getState()
        DbtUi.readLater(this, ModalityState.any(), project, graphWork, read = {
            val w = DbtWorkspace.get(project)
            val editorModel = file?.let { DbtYaml.psiFile(project, it) }?.let(w::model)
            val nextFocus = if (follow && state.pinnedFocus.isEmpty() || oldFocus == null) editorModel?.let {
                DbtResourceIdentity.key(
                    w,
                    it
                )
            } ?: oldFocus else oldFocus
            val graph = DbtGraphData.build(
                w, nextFocus, state.upstream + if (nextFocus == oldFocus) upstreamExtra else 0,
                state.downstream + if (nextFocus == oldFocus) downstreamExtra else 0
            )
            val root = nextFocus?.let { graph.targets[it] }?.let { w.owner(it.symbol) }?.let(w::executionRoot)
                ?: if (w.roots.map(w::executionRoot).distinct().size == 1) w.roots.firstOrNull()
                    ?.let(w::executionRoot) else null
            graph to root?.directory?.path
        }) { (snapshot, root) ->
            if (!disposed && request == generation.get()) {
                lastSnapshot = snapshot; executionRoot = root
                val payload = snapshot.payload
                val next = payload.get("focus")?.asString?.takeIf { it.isNotEmpty() }
                if (next != focus) {
                    upstreamExtra = 0; downstreamExtra = 0
                }
                focus = next
                payload.addProperty("direction", state.direction); payload.addProperty("running", runner.isRunning)
                payload.addProperty("pinned", state.pinnedFocus.isNotEmpty()); payload.addProperty(
                    "canGenerateDocs",
                    root != null
                )
                payload.addProperty(
                    "status",
                    if (runner.isRunning) "dbt command running…" else "Click a resource for details on the right; double-click to open its definition."
                )
                fun color(c: java.awt.Color) = "#%02x%02x%02x".format(c.red, c.green, c.blue)
                val scheme = EditorColorsManager.getInstance().globalScheme
                payload.add(
                    "theme",
                    obj(
                        "bg" to color(scheme.defaultBackground),
                        "fg" to color(scheme.defaultForeground),
                        "panel" to color(javax.swing.UIManager.getColor("Panel.background"))
                    )
                )
                browser?.update(payload)
            }
        }
    }

    fun showSelectionInLineage(expression: String, excluded: String, fromConfiguration: Boolean = false) {
        val rootPath = executionRoot
        DbtUi.readLater(this, ModalityState.any(), read = {
            val w = DbtWorkspace.get(project)
            val owner = w.roots.firstOrNull { rootPath == it.directory.path } ?: w.roots.singleOrNull()
            val selection = owner?.let { DbtSelection.resolve(w, it, expression, excluded) }
            obj(
                "expression" to expression,
                "exclude" to excluded,
                "fromConfiguration" to fromConfiguration,
                "ids" to strings(selection?.resources?.map { DbtResourceIdentity.key(w, it) } ?: emptyList()),
                "diagnostics" to strings(selection?.problems ?: emptyList()))
        }) { browser?.showFilter(it) }
    }

    fun handleMessage(raw: String) {
        if (disposed) return
        val message = JsonParser.parseString(raw).asJsonObject
        fun field(name: String) = message.get(name)?.asString.orEmpty()
        val id = field("id")
        when (field("type")) {
            "ready" -> refresh(follow = true)
            "select", "column" -> {
                val request = documentationGeneration.incrementAndGet()
                val column = if (field("type") == "column") field("value") else ""
                DbtUi.readLater(this, smartIn = project, read = {
                    val w = DbtWorkspace.get(project)
                    val html = DbtGraphData.build(w, null, 1, 1).targets[id]?.let { element ->
                        if (column.isEmpty()) DbtDocumentation.render(w, element.symbol, linkTestSources = true)
                        else if (DbtResourceData.get(
                                w,
                                element.symbol
                            ).columns.any { it.name == column }
                        ) DbtColumnDocumentationTarget(element.symbol, column).render(w) else null
                    } ?: "<p>Documentation is no longer available for this resource or column.</p>"
                    obj("id" to id, "column" to column, "html" to html)
                }) { if (request == documentationGeneration.get()) browser?.showDocumentation(it) }
            }

            "test-source" -> {
                val sourceId = field("value")
                DbtUi.readLater(this, smartIn = project, read = {
                    val w = DbtWorkspace.get(project)
                    DbtGraphData.build(w, null, 1, 1).targets[id]?.let {
                        DbtResourceData.get(
                            w,
                            it.symbol
                        ).tests.firstOrNull { t -> t.sourceId == sourceId }?.source
                    }
                        ?.let {
                            com.intellij.openapi.fileEditor.OpenFileDescriptor(
                                project,
                                it.containingFile.virtualFile,
                                it.textOffset
                            )
                        }
                }) { it?.navigate(true) }
            }

            "focus", "edit" -> {
                val kind = field("type")
                DbtUi.readLater(
                    this,
                    smartIn = project,
                    read = { DbtGraphData.build(DbtWorkspace.get(project), null, 1, 1).targets[id] }) { element ->
                    element?.let {
                        if (kind == "focus") {
                            focus = id
                            if (settings.getState().pinnedFocus.isNotEmpty()) settings.getState().pinnedFocus = id
                            upstreamExtra = 0; downstreamExtra = 0; element.navigate(true); refresh(follow = false)
                        } else DbtPropertiesEditor.show(DbtWorkspace.get(project), element.symbol)
                    }
                }
            }

            "expand" -> {
                if (id == "upstream") upstreamExtra += 5 else downstreamExtra += 5; refresh(follow = false)
            }

            "regenerate" -> executionRoot?.let { DbtRunConfiguration.edit(project, it, "Generate Docs") }
            "pin" -> {
                settings.getState().pinnedFocus =
                    if (field("value") == "true") focus ?: id else ""; refresh(follow = false)
            }

            "use-configuration-selection" -> executionRoot?.let {
                DbtRunConfiguration.edit(
                    project,
                    it,
                    "Build",
                    id.take(4000),
                    field("value").take(4000)
                )
            }

            "filter" -> showSelectionInLineage(id, field("value"))
            "external-link" -> DbtAirflow.safeUrl(field("value"))?.let(com.intellij.ide.BrowserUtil::browse)
            "export" -> DbtGraphExport.show(project, id, field("value"))
            "execution" -> DbtRunConfiguration.openEvidence(project, id)
        }
    }

    override fun dispose() {
        disposed = true; generation.incrementAndGet()
    }
}

/** Hosts bundled graph assets and delivers browser messages on the EDT for the owning disposable's lifetime. */
internal class DbtLineageBrowser(parent: Disposable, private val receive: (String) -> Unit) {
    private val browser = JBCefBrowser()
    private val query = JBCefJSQuery.create(browser as JBCefBrowserBase)
    val component: javax.swing.JComponent = browser.component

    init {
        Disposer.register(parent, browser); Disposer.register(parent, query)
        query.addHandler { raw ->
            ApplicationManager.getApplication().invokeLater { if (!browser.isDisposed) receive(raw) }; null
        }
        val scripts = listOf(
            "vendor/cytoscape.min.js",
            "vendor/dagre.min.js",
            "vendor/cytoscape-dagre.min.js"
        ).joinToString("") { "<script>" + resource(it).replace("</script", "<\\/script") + "</script>" }
        browser.loadHTML(
            resource("index.html").replace(
                "<!-- SCRIPTS -->",
                scripts + "<script>window.dbtSend=function(message){" + query.inject("message") + "};</script>"
            )
        )
        browser.disableNavigation()
    }

    private fun resource(path: String): String =
        javaClass.getResourceAsStream("/lineage/$path")?.use { String(it.readAllBytes(), StandardCharsets.UTF_8) }
            ?: throw IllegalStateException("Missing bundled lineage resource $path")

    private fun encoded(json: String) = Base64.getEncoder().encodeToString(json.toByteArray(StandardCharsets.UTF_8))
    private fun decode(json: String) =
        "JSON.parse(new TextDecoder().decode(Uint8Array.from(atob('" + encoded(json) + "'),c=>c.charCodeAt(0))))"

    private fun icons(dark: Boolean): JsonObject {
        val result = JsonObject()
        listOf(
            "clear-search" to AllIcons.Actions.Close,
            "zoom-in" to AllIcons.General.ZoomIn,
            "zoom-out" to AllIcons.General.ZoomOut,
            "fit" to AllIcons.General.FitContent,
            "apply-filter" to AllIcons.Actions.ShortcutFilter,
            "clear-filter" to AllIcons.Actions.Close,
            "use-config" to AllIcons.Actions.Execute,
            "pin-focus" to AllIcons.Actions.PinTab,
            "copy-image" to AllIcons.Actions.Copy,
            "export-png" to AllIcons.Actions.Download,
            "export-svg" to AllIcons.Actions.Download,
            "show-docs" to AllIcons.Actions.PreviewDetails,
            "regenerate" to AllIcons.Actions.Refresh
        ).forEach { (id, icon) ->
            val bytes = ByteArrayOutputStream(); ImageIO.write(
            IconUtil.toBufferedImage(
                IconLoader.getDarkIcon(
                    icon,
                    dark
                )
            ), "png", bytes
        )
            result.addProperty(id, "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes.toByteArray()))
        }
        return result
    }

    private val lightIcons by lazy { icons(false) }
    private val darkIcons by lazy { icons(true) }
    fun update(payload: JsonObject) {
        payload.add(
            "icons",
            if (JBColor.isBright()) lightIcons else darkIcons
        ); browser.runJavaScript("window.dbtUpdate&&window.dbtUpdate(" + decode(payload.toString()) + ")")
    }

    fun showFilter(data: JsonObject) =
        browser.runJavaScript("window.dbtShowFilter&&window.dbtShowFilter(" + decode(data.toString()) + ")")

    fun showDocumentation(data: JsonObject) =
        browser.runJavaScript("window.dbtShowDocumentation&&window.dbtShowDocumentation(" + decode(data.toString()) + ")")

    fun runnerState(running: Boolean, message: String) =
        browser.runJavaScript("window.dbtRunnerState&&window.dbtRunnerState(" + running + "," + Gson().toJson(message) + ")")
}

internal fun interface DbtLineageSelectionListener {
    fun showSelection(root: String, expression: String, excluded: String)
}

internal object DbtLineageSelection {
    val Topic =
        com.intellij.util.messages.Topic.create("dbt lineage selection", DbtLineageSelectionListener::class.java)

    fun show(project: Project, root: String, expression: String, excluded: String) {
        val window = com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow("dbt") ?: return
        window.activate {
            window.contentManager.findContent("Lineage")?.let { window.contentManager.setSelectedContent(it) }
            project.messageBus.syncPublisher(Topic).showSelection(root, expression, excluded)
        }
    }
}

internal data class DbtGraphSnapshot(val payload: JsonObject, val targets: Map<String, DbtSymbolElement>)
/** Builds graph identities, candidate edges and depth boundaries from the shared workspace and execution evidence. */
internal object DbtGraphData {
    private val gson = Gson()
    fun build(w: DbtWorkspace, focus: String?, upstream: Int, downstream: Int): DbtGraphSnapshot {
        val resources = w.symbols.filter { it.kind in DbtResourceData.GraphKinds && it.isEnabled }
            .groupBy { DbtResourceIdentity.key(w, it) }.values.flatMap { candidates ->
            val source = candidates.filter { !it.metadata }
            val preferred = source.ifEmpty { candidates }
            val code = preferred.filter { it.isCode }
            code.ifEmpty { preferred.filter { s -> !s.isProperty || preferred.none { it != s && !it.isProperty } } }
        }
        val groups = resources.groupBy { DbtResourceIdentity.key(w, it) }
        fun id(s: DbtSymbol): String {
            val k = DbtResourceIdentity.key(w, s); return if (groups[k].orEmpty().size > 1) "$k|${s.id}" else k
        }

        val byId = resources.associateBy(::id)
        fun canonical(s: DbtSymbol) = groups[DbtResourceIdentity.key(w, s)].orEmpty().map(::id)
        val edges = DbtLineage.edges(w)
            .filter { it.from.kind in DbtResourceData.GraphKinds && it.to.kind in DbtResourceData.GraphKinds }
            .flatMap { e ->
                canonical(e.to).flatMap { from ->
                    canonical(e.from).filter { it != from }.map { to -> Triple(from, to, e.ambiguous) }
                }
            }.distinct()
        val start = focus?.takeIf { it in byId } ?: focus?.let { k ->
            resources.firstOrNull {
                DbtResourceIdentity.key(
                    w,
                    it
                ) == k
            }?.let(::id)
        }

        fun walk(down: Boolean, depth: Int): Set<String> {
            val adjacency = edges.groupBy({ if (down) it.first else it.second }, { if (down) it.second else it.first })
            val seen = listOfNotNull(start).toMutableSet();
            var frontier = listOfNotNull(start);
            var level = 0
            while (frontier.isNotEmpty() && level < depth) {
                ProgressManager.checkCanceled(); frontier =
                    frontier.flatMap { adjacency[it].orEmpty() }.filter(seen::add); level++
            }
            return seen
        }

        val visible = if (start == null) byId.keys else walk(false, upstream) + walk(true, downstream)
        val evidence = w.project.getService(DbtExecutionEvidence::class.java)
        val testDefinitions = DbtTestDiscovery.discover(w)
        val executionRuns =
            w.roots.map(w::executionRoot).distinct().associate { it.directory.path to evidence.runs(it) }
        val nodes = resources.filter { id(it) in visible }
            .sortedWith(compareBy<DbtSymbol> { it.namespace }.thenBy { it.name }.thenBy { it.version }).map { s ->
            ProgressManager.checkCanceled()
            val data = DbtResourceData.get(w, s)
            val executionRoot = w.owner(s)?.let(w::executionRoot)?.directory?.path
            val runs = executionRoot?.let { executionRuns[it] }.orEmpty()
            obj(
                "id" to id(s),
                "name" to (s.name + (s.version?.let { " v$it" } ?: "")),
                "kind" to s.kind,
                "schema" to data.attributes["schema"].orEmpty(),
                "package" to s.namespace,
                "materialized" to data.attributes["materialized"].orEmpty(),
                "description" to data.description,
                "focus" to (start == id(s)),
                "folder" to w.owner(s)?.relative(s.file.virtualFile).orEmpty().split('/').dropLast(1).joinToString("/"),
                "tags" to strings(data.tags),
                "manifest" to ("artifact-project" in data.attributes),
                "candidate" to (groups.getValue(DbtResourceIdentity.key(w, s)).size > 1),
                "execution" to evidence.overlay(s, runs, testDefinitions.filter { executionRoot == it.root })
            )
        }
        val links =
            edges.filter { it.first in visible && it.second in visible }.groupBy { it.first to it.second }.entries
                .sortedWith(compareBy<Map.Entry<Pair<String, String>, List<Triple<String, String, Boolean>>>> { it.key.first }.thenBy { it.key.second })
                .map { (pair, candidates) ->
                    obj("source" to pair.first, "target" to pair.second, "candidate" to candidates.any { it.third })
                }
        val boundary = listOf(false, true).mapNotNull { down ->
            val remaining = if (start == null) emptySet() else walk(down, Int.MAX_VALUE) - visible
            if (remaining.isEmpty()) null else obj(
                "direction" to (if (down) "downstream" else "upstream"),
                "count" to remaining.size,
                "anchors" to strings(edges.mapNotNull { (from, to, _) ->
                    when {
                        from in visible && to in remaining -> from; from in remaining && to in visible -> to; else -> null
                    }
                }.distinct())
            )
        }
        return DbtGraphSnapshot(
            obj(
                "nodes" to array(nodes),
                "edges" to array(links),
                "boundary" to array(boundary),
                "focus" to start.orEmpty(),
                "empty" to if (resources.isEmpty()) "No dbt resources found. Open a dbt project or check its paths and artifact settings." else ""
            ), byId.mapValues { it.value.element })
    }

    fun documentation(w: DbtWorkspace, s: DbtSymbol): JsonObject {
        val data = DbtResourceData.get(w, s)
        val columns = data.columns.map { c ->
            val origins = DbtColumnLineage.upstream(w, s, c.name).map { edge ->
                obj(
                    "resource" to (edge.input.namespace + "." + edge.input.owner),
                    "column" to edge.input.name,
                    "direct" to edge.direct,
                    "kind" to edge.kind,
                    "complete" to edge.complete,
                    "expression" to edge.expression,
                    "path" to edge.input.file.virtualFile.path,
                    "offset" to edge.input.range.startOffset
                )
            }
            obj(
                "name" to c.name,
                "type" to c.dataType,
                "description" to c.description,
                "declarations" to strings(c.declarations),
                "origins" to array(origins)
            )
        }
        val (upstream, downstream) = DbtDocumentation.dependencies(w, s)
        return obj(
            "name" to DbtDocumentation.displayName(s),
            "kind" to s.kind,
            "description" to data.description,
            "shortName" to (s.name + (s.version?.let { " v$it" } ?: "")),
            "identity" to s.uniqueId,
            "source" to s.file.virtualFile.path,
            "logical" to s.relationName,
            "editable" to (!s.metadata && s.file.isWritable && w.owner(s)?.let { w.packageOwner(it) == null } == true),
            "rawSql" to data.rawSql,
            "compiledSql" to data.compiledSql,
            "tags" to strings(data.tags),
            "attributes" to gson.toJsonTree(data.attributes),
            "columns" to array(columns),
            "tests" to array(data.tests.map { obj("name" to it.name, "column" to it.column) }),
            "airflow" to DbtAirflow.details(w, s),
            "upstream" to strings(upstream),
            "downstream" to strings(downstream)
        )
    }
}

internal object DbtGraphExport {
    val PngFlavor = DataFlavor("image/png;class=java.io.InputStream", "PNG image")
    /** Preserves the original PNG bytes; if native image publication fails, retries with the encoded PNG flavor alone. */
    fun copyPng(bytes: ByteArray, publish: (Transferable) -> Unit) {
        val image = ImageIO.read(ByteArrayInputStream(bytes)) ?: return
        val png = bytes.clone()
        fun content(withImage: Boolean): Transferable = object : Transferable {
            override fun getTransferDataFlavors(): Array<DataFlavor> =
                if (withImage) arrayOf(PngFlavor, DataFlavor.imageFlavor) else arrayOf(PngFlavor)

            override fun isDataFlavorSupported(flavor: DataFlavor) =
                flavor == PngFlavor || withImage && flavor == DataFlavor.imageFlavor

            override fun getTransferData(flavor: DataFlavor): Any =
                if (flavor == PngFlavor) ByteArrayInputStream(png) else if (withImage && flavor == DataFlavor.imageFlavor) image else throw UnsupportedFlavorException(
                    flavor
                )
        }
        try {
            publish(content(true))
        } catch (_: Exception) {
            publish(content(false))
        }
    }

    fun show(project: Project, format: String, value: String) {
        val kind = if (format == "clipboard") "png" else format
        val prefix = if (kind == "png") "data:image/png;base64," else "data:image/svg+xml;base64,"
        if (kind !in setOf("png", "svg") || !value.startsWith(prefix) || value.length > 28000000) return
        val bytes = try {
            Base64.getDecoder().decode(value.substring(prefix.length))
        } catch (_: IllegalArgumentException) {
            return
        }
        if (format == "clipboard") copyPng(bytes, CopyPasteManager.getInstance()::setContents)
        else FileChooserFactory.getInstance()
            .createSaveFileDialog(FileSaverDescriptor("Export dbt lineage", "Save the visible graph", kind), project)
            .save("lineage.$kind")?.let { Files.write(it.file.toPath(), bytes) }
    }
}
