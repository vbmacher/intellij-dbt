package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project.*
import com.github.vbmacher.intellij.dbt.project.DbtJson.string
import com.github.vbmacher.intellij.dbt.lineage.DbtLineage
import com.github.vbmacher.intellij.dbt.syntax.DbtYaml
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.ide.BrowserUtil
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.components.*
import com.intellij.openapi.Disposable
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong
import javax.swing.*

class DbtConsumersState {
    var metabaseUrl = ""
    var agentEnabled = false
    var airflowPaths: MutableList<String> = arrayListOf()
    var airflowUrl = ""
}

@Service(Service.Level.PROJECT)
@State(name = "DbtIntegrations", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class DbtConsumersSettings : PersistentStateComponent<DbtConsumersState> {
    private var currentState = DbtConsumersState()
    override fun getState(): DbtConsumersState = currentState
    override fun loadState(value: DbtConsumersState) {
        currentState = value
    }
}

/** A declared or inferred downstream consumer; [confidence] and [evidence] keep uncertain ownership explicit. */
data class DbtConsumer(
    val name: String, val kind: String, val owner: String, val url: String?, val resource: String,
    val column: String?, val confidence: String, val evidence: String
)

data class DbtConsumersCache(val consumers: List<DbtConsumer>, val refreshedAt: Long, val status: String) {
    val stale: Boolean get() = refreshedAt == 0L || System.currentTimeMillis() - refreshedAt > 15 * 60 * 1000
}

/**
 * Caches Metabase metadata without polling; network access requires an explicit Refresh action.
 * Credentials stay in Password Safe. A generation change prevents requests for an old connection from publishing results.
 */
@Service(Service.Level.PROJECT)
class DbtConsumers(private val project: Project) {
    @Volatile
    private var cache = DbtConsumersCache(
        emptyList(),
        0,
        "Disconnected. Configure Metabase URL and API key in Settings > dbt Workspace > Integrations."
    )
    private var generation = 0L
    val snapshot: DbtConsumersCache get() = cache
    private val credential get() = CredentialAttributes("dbt Workspace Metabase " + project.locationHash)
    internal val apiKey: String get() = PasswordSafe.instance.getPassword(credential).orEmpty()
    val hasSecret: Boolean get() = apiKey.isNotEmpty()
    fun setSecret(secret: String) =
        PasswordSafe.instance.set(credential, if (secret.isEmpty()) null else Credentials("api-key", secret))

    @Synchronized
    /** Invalidates both the displayed cache and any refresh already using the previous connection settings. */
    fun clear() {
        generation++; cache = DbtConsumersCache(emptyList(), 0, "Disconnected. Refresh Metabase metadata explicitly.")
    }

    @Synchronized
    private fun publish(expectedGeneration: Long, value: () -> DbtConsumersCache): DbtConsumersCache {
        if (generation == expectedGeneration) cache = value()
        return cache
    }

    fun refresh(w: DbtWorkspace): DbtConsumersCache = refresh(w, Duration.ofSeconds(10))
    internal fun refresh(w: DbtWorkspace, requestTimeout: Duration) =
        refresh(requestTimeout, synchronized(this) { generation })

    private fun refresh(requestTimeout: Duration, expectedGeneration: Long): DbtConsumersCache = try {
        val url = project.getService(DbtConsumersSettings::class.java).getState().metabaseUrl
        val key = apiKey.takeIf { it.isNotEmpty() }
        if (DbtAirflow.safeUrl(url) == null || key == null) publish(expectedGeneration) { cache.copy(status = "Disconnected. Configure Metabase URL and API key in Settings > dbt Workspace > Integrations.") }
        else {
            val client =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER)
                    .build()
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60)
            fun get(path: String): Result<JsonElement> = try {
                com.intellij.openapi.progress.ProgressManager.checkCanceled()
                if (System.nanoTime() > deadline) Result.failure(IllegalArgumentException("Metabase refresh exceeded the 60 second limit.")) else {
                    val requestDeadline = minOf(deadline, System.nanoTime() + requestTimeout.toNanos())
                    val request =
                        HttpRequest.newBuilder(URI.create(url.removeSuffix("/") + path)).timeout(requestTimeout)
                            .header("x-api-key", key).GET().build()
                    val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
                    val stream = response.body()
                    val body = java.util.concurrent.CompletableFuture.supplyAsync { stream.readNBytes(4000001) }
                    val bytes = try {
                        body.get(
                            maxOf(1L, requestDeadline - System.nanoTime()),
                            java.util.concurrent.TimeUnit.NANOSECONDS
                        )
                    } finally {
                        body.cancel(true); stream.close()
                    }
                    when {
                        response.statusCode() == 401 || response.statusCode() == 403 -> Result.failure(
                            IllegalArgumentException("Permission denied. Check the Metabase API key and metadata permissions.")
                        )

                        response.statusCode() != 200 -> Result.failure(IllegalArgumentException("Metabase metadata request failed (HTTP ${response.statusCode()})."))
                        bytes.size > 4000000 -> Result.failure(IllegalArgumentException("Metabase response exceeds the 4 MB metadata limit."))
                        else -> Result.success(
                            JsonParser.parseString(
                                String(
                                    bytes,
                                    java.nio.charset.StandardCharsets.UTF_8
                                )
                            )
                        )
                    }
                }
            } catch (canceled: com.intellij.openapi.progress.ProcessCanceledException) {
                throw canceled
            } catch (_: Exception) {
                Result.failure(IllegalArgumentException("Metabase is unavailable or returned invalid metadata."))
            }

            fun collect(): Result<DbtConsumersCache> {
                val cards = get("/api/card").getOrElse { return Result.failure(it) }
                val databases = get("/api/database").getOrElse { return Result.failure(it) }
                val list =
                    if (databases.isJsonArray) databases.asJsonArray.toList() else databases.asJsonObject.getAsJsonArray(
                        "data"
                    )?.toList() ?: emptyList()
                if (list.size > 20) return Result.failure(IllegalArgumentException("Metabase exceeds the 20 database metadata limit."))
                val tables = mutableListOf<JsonObject>()
                for (db in list) {
                    val metadata = get("/api/database/" + db.asJsonObject.get("id").asInt + "/metadata").getOrElse {
                        return Result.failure(it)
                    }
                    tables += metadata.asJsonObject.getAsJsonArray("tables")?.map { it.asJsonObject } ?: emptyList()
                }
                if (!cards.isJsonArray || cards.asJsonArray.size() > 1000) return Result.failure(
                    IllegalArgumentException("Metabase question response is unsupported or exceeds 1000 questions.")
                )
                val dashboards = get("/api/dashboard").getOrElse { return Result.failure(it) }
                val questions = DbtUi.read {
                    mapQuestions(
                        DbtWorkspace.get(project),
                        url,
                        cards.asJsonArray.map { it.asJsonObject },
                        tables
                    )
                }
                val dashboardList = if (dashboards.isJsonArray) dashboards.asJsonArray.toList() else emptyList()
                val dashboardConsumers = dashboardList.take(100).flatMap { d ->
                    val dashboard = d.asJsonObject
                    val detail = get("/api/dashboard/" + dashboard.get("id").asInt).getOrNull()
                    if (detail == null) emptyList() else {
                        val cardIds = detail.asJsonObject.getAsJsonArray("dashcards")
                            ?.mapNotNull { it.asJsonObject.get("card_id")?.takeUnless { v -> v.isJsonNull }?.asString }
                            ?.toSet() ?: emptySet()
                        questions.filter { it.evidence.takeWhile { c -> c != ':' } in cardIds }.map { q ->
                            q.copy(
                                name = string(dashboard, "name"),
                                kind = "Metabase dashboard",
                                url = url.removeSuffix("/") + "/dashboard/" + dashboard.get("id").asInt,
                                confidence = "inferred via question",
                                evidence = q.name + ": " + q.evidence
                            )
                        }
                    }
                }
                return Result.success(
                    DbtConsumersCache(
                        (questions + dashboardConsumers).distinct(), System.currentTimeMillis(),
                        if (dashboardList.size > 100) "Partial: first 100 dashboards only." else "Metadata refreshed. Dashboard details unavailable to this key are omitted."
                    )
                )
            }

            val refreshed = collect()
            publish(expectedGeneration) { refreshed.getOrElse { cache.copy(status = it.message.orEmpty()) } }
        }
    } catch (canceled: com.intellij.openapi.progress.ProcessCanceledException) {
        throw canceled
    } catch (_: Exception) {
        publish(expectedGeneration) { cache.copy(status = "Metabase returned unsupported or invalid metadata; previous cache retained.") }
    }

    companion object {
        fun exposures(w: DbtWorkspace, resource: DbtSymbol? = null): List<DbtConsumer> {
            val candidates = resource?.let {
                listOf(it) + DbtLineage.traverse(
                    DbtLineage.edges(w),
                    it,
                    downstream = true,
                    depth = 100
                )
            }?.map { DbtResourceIdentity.key(w, it) }?.toSet() ?: emptySet()
            return w.symbols.filter {
                it.kind == "exposure" && (resource == null || DbtResourceIdentity.key(
                    w,
                    it
                ) in candidates)
            }
                .sortedWith(compareBy<DbtSymbol> { it.metadata }.thenBy { it.isStale })
                .distinctBy { DbtResourceIdentity.key(w, it) }.map { s ->
                    val m = s.mapping
                    val owner = m?.let { DbtYaml.map(it, "owner") }?.let { DbtYaml.string(it, "name") }.orEmpty()
                    DbtConsumer(
                        s.name,
                        "dbt exposure",
                        owner,
                        m?.let { DbtYaml.string(it, "url") }?.let(DbtAirflow::safeUrl),
                        DbtResourceIdentity.key(w, s),
                        null,
                        if (s.isStale) "declared (stale local artifact)" else if (s.metadata) "declared (local artifact)" else "declared",
                        s.file.virtualFile.path + ":" + s.range.startOffset
                    )
                }
        }

        private fun normalize(s: String) = s.replace("\"", "").replace("`", "").lowercase(java.util.Locale.ROOT)
        internal fun mapQuestions(
            w: DbtWorkspace,
            base: String,
            cards: List<JsonObject>,
            tables: List<JsonObject>
        ): List<DbtConsumer> {
            val relations = w.symbols.filter { it.kind in DbtSymbol.RelationKinds && !it.isProperty }
                .map { it to DbtResourceData.get(w, it) }

            fun physical(resource: DbtSymbol, data: DbtResourceData): String? {
                val attrs = data.attributes
                return attrs["relation_name"] ?: attrs["database"]?.let { db ->
                    attrs["schema"]?.let { schema ->
                        "$db.$schema." + (attrs["identifier"] ?: attrs["alias"] ?: resource.name)
                    }
                }
            }
            return cards.flatMap { card ->
                val query = card.getAsJsonObject("dataset_query")
                val native = query?.getAsJsonObject("native")?.let { string(it, "query") }.orEmpty()
                val mbql = query?.getAsJsonObject("query")
                val source = mbql?.get("source-table")?.takeIf { it.isJsonPrimitive }?.asString
                val matchedTables = tables.filter { source == string(it, "id") }
                val fields = mutableFieldIds(listOfNotNull(mbql))
                relations.flatMap { (resource, data) ->
                    val relation = physical(resource, data)
                    val matches = matchedTables.filter { t ->
                        relation?.let { r ->
                            val p = string(t, "schema") + "." + string(t, "name")
                            if (string(t, "db_name").isNotEmpty()) normalize(r) == normalize(
                                string(
                                    t,
                                    "db_name"
                                ) + "." + p
                            ) else normalize(r).endsWith("." + normalize(p))
                        } == true
                    }
                    val nativeMatch = relation?.let { normalize(native).contains(normalize(it)) } == true
                    if (matches.isEmpty() && !nativeMatch) emptyList() else {
                        val collisions = relations.count { (other, otherData) ->
                            physical(
                                other,
                                otherData
                            )?.let { r -> relation?.let { normalize(it) == normalize(r) } } == true
                        }
                        val columns = matches.flatMap { it.getAsJsonArray("fields")?.toList() ?: emptyList() }
                            .map { it.asJsonObject }.filter { string(it, "id") in fields }.map { string(it, "name") }
                            .distinct()
                        val exact = !resource.isStale && matches.isNotEmpty() && matches.all {
                            string(
                                it,
                                "db_name"
                            ).isNotEmpty()
                        } && collisions <= 1 && resource.version == null
                        val confidence =
                            if (exact) "direct physical metadata" else "inferred; stale metadata, target/package/version or SQL ownership unproven"
                        (if (columns.isEmpty()) listOf(null) else columns).map { column ->
                            DbtConsumer(
                                string(card, "name"),
                                "Metabase question",
                                "",
                                base.removeSuffix("/") + "/question/" + string(card, "id"),
                                DbtResourceIdentity.key(w, resource),
                                column,
                                confidence,
                                string(card, "id") + ":" + (relation ?: "unknown") + (column?.let { ".$it" } ?: ""))
                        }
                    }
                }
            }
        }

        private fun mutableFieldIds(roots: List<JsonObject>): Set<String> {
            fun walk(e: JsonElement, depth: Int): List<String> = if (depth > 30) emptyList() else if (e.isJsonArray) {
                val xs = e.asJsonArray.toList()
                (if (xs.firstOrNull()?.let { it.isJsonPrimitive && it.asString == "field" } == true) listOfNotNull(
                    xs.getOrNull(1)?.takeIf { it.isJsonPrimitive }?.asString
                ) else emptyList()) + xs.flatMap { walk(it, depth + 1) }
            } else if (e.isJsonObject) e.asJsonObject.entrySet().flatMap { walk(it.value, depth + 1) } else emptyList()
            return roots.flatMap { walk(it, 0) }.toSet()
        }
    }
}

/** Read-only consumer browsing hosted by the dbt tool window. */
internal class DbtConsumersPanel(private val project: Project) : JPanel(BorderLayout(6, 6)), Disposable {
    private val service = project.getService(DbtConsumers::class.java)
    private val scope = JLabel("All resources")
    private val status = JBLabel()
    private val consumerList = JBList<DbtConsumer>()
    private val column = JComboBox(arrayOf("All columns"))
    private var workspace: DbtWorkspace? = null
    private var focus: String? = null
    private var exposures = emptyList<DbtConsumer>()
    private var values = emptyList<DbtConsumer>()
    private val generation = AtomicLong()
    private var disposed = false
    private var refreshing = false
    private fun action(label: String, icon: Icon, enabled: () -> Boolean = { true }, run: () -> Unit): AnAction =
        object : DumbAwareAction(label, label, icon) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = enabled()
            }

            override fun actionPerformed(e: AnActionEvent) {
                if (enabled()) run()
            }
        }

    private val selected: DbtConsumer? get() = consumerList.selectedValue
    private val definition: DbtSymbol?
        get() = workspace?.let { w ->
            selected?.let { c ->
                w.symbols.firstOrNull {
                    DbtResourceIdentity.key(
                        w,
                        it
                    ) == c.resource && it.kind == "exposure"
                }
            }
        }
    private val actions = DefaultActionGroup()
    private val toolbar: ActionToolbar

    init {
        actions.add(
            action(
                "Refresh Metabase metadata",
                AllIcons.Actions.Refresh,
                { !refreshing }) { refreshMetabase() })
        actions.addSeparator()
        actions.add(action("Open consumer", AllIcons.General.Web, { selected?.url != null }) {
            selected?.url?.let(
                BrowserUtil::browse
            )
        })
        actions.add(
            action(
                "Go to exposure definition",
                AllIcons.Actions.EditSource,
                { definition != null }) { definition?.element?.navigate(true) })
        actions.addSeparator()
        actions.add(
            action(
                "Configure…",
                AllIcons.General.Settings
            ) { DbtSettingsConfigurable.showIntegrations(project) })
        toolbar = ActionManager.getInstance().createActionToolbar("DbtExposures", actions, true)
        toolbar.targetComponent = this
        val controls = JPanel(BorderLayout(JBUI.scale(8), 0)); controls.add(toolbar.component, BorderLayout.WEST)
        val filter = JPanel(BorderLayout(JBUI.scale(4), 0))
        val columnLabel = JLabel("Column:").also { it.labelFor = column }
        filter.add(columnLabel, BorderLayout.WEST); filter.add(column, BorderLayout.CENTER); controls.add(
            filter,
            BorderLayout.EAST
        )
        column.toolTipText = "Filter Metabase column evidence; model-level consumers remain visible"; column.isEnabled =
            false
        val header = JPanel(BorderLayout(0, JBUI.scale(6)))
        val context = JPanel(BorderLayout(0, JBUI.scale(4)))
        context.border = JBUI.Borders.empty(0, 8, 4, 8); context.add(scope, BorderLayout.NORTH); context.add(
            status,
            BorderLayout.CENTER
        )
        header.add(controls, BorderLayout.NORTH); header.add(context, BorderLayout.CENTER); add(
            header,
            BorderLayout.NORTH
        )
        consumerList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        consumerList.emptyText.text = "No consumers found for this resource"
        consumerList.emptyText.appendSecondaryText(
            "Refresh Metabase metadata to include remote consumers.",
            SimpleTextAttributes.GRAYED_ATTRIBUTES,
            null
        )
        consumerList.cellRenderer = object : ListCellRenderer<DbtConsumer> {
            private val row = JPanel(BorderLayout(0, JBUI.scale(4)))
            private val title = JLabel()
            private val detail = JLabel()

            init {
                row.border = JBUI.Borders.empty(6, 8); title.putClientProperty(
                    "html.disable",
                    true
                ); detail.putClientProperty("html.disable", true)
                row.add(title, BorderLayout.NORTH); row.add(detail, BorderLayout.CENTER)
            }

            override fun getListCellRendererComponent(
                list: JList<out DbtConsumer>,
                c: DbtConsumer,
                index: Int,
                selected: Boolean,
                focused: Boolean
            ): Component {
                row.background = if (selected) list.selectionBackground else list.background
                title.text = c.name + " · " + c.kind + if (c.owner.isNotEmpty()) " · Owner: " + c.owner else ""
                title.icon = if (c.kind == "dbt exposure") AllIcons.FileTypes.Yaml else AllIcons.General.Web
                title.foreground = if (selected) list.selectionForeground else list.foreground
                detail.text = evidence(c); detail.foreground =
                    if (selected) list.selectionForeground else UIUtil.getContextHelpForeground()
                row.toolTipText = evidence(c)
                return row
            }
        }
        consumerList.addListSelectionListener { toolbar.updateActionsAsync() }
        add(JBScrollPane(consumerList), BorderLayout.CENTER)
        column.addActionListener { update() }; update()
    }

    private fun evidence(c: DbtConsumer) =
        c.confidence + (if (column.selectedItem != "All columns" && c.column == null) " · model-level; column use unknown" else c.column?.let { " · column: $it" }
            .orEmpty()) + " · " + c.evidence

    private data class ResourceView(
        val w: DbtWorkspace,
        val target: DbtSymbol?,
        val consumers: List<DbtConsumer>,
        val columns: List<String>
    )

    /** Follows editor context independently of pinned lineage; only the latest background resolution may update this panel. */
    fun showResource(file: VirtualFile?, offset: Int) {
        val request = generation.incrementAndGet()
        DbtUi.readLater(this, smartIn = project, coalesce = this, read = {
            val w = DbtWorkspace.get(project)
            val target = DbtResourceAction.at(w, file?.let { DbtYaml.psiFile(project, it) }, offset)
            val columns =
                target?.let { DbtResourceData.get(w, it).columns.map { c -> c.name } }?.distinct() ?: emptyList()
            ResourceView(w, target, DbtConsumers.exposures(w, target), columns)
        }) { (w, target, consumers, columns) ->
            if (!disposed && request == generation.get()) {
                val nextFocus = target?.let { DbtResourceIdentity.key(w, it) }
                val previousColumn = column.selectedItem?.toString()?.takeIf { focus == nextFocus && it in columns }
                workspace = w; focus = nextFocus; exposures = consumers
                scope.text = target?.let { "Resource: " + it.uniqueId } ?: "All resources"; scope.toolTipText =
                    scope.text
                column.model =
                    DefaultComboBoxModel((listOf("All columns") + columns).toTypedArray()); column.isEnabled =
                    columns.isNotEmpty()
                previousColumn?.let { column.selectedItem = it }; update()
            }
        }
    }

    private fun update() {
        val cache = service.snapshot
        val selectedColumn = column.selectedItem?.toString()?.takeIf { it != "All columns" }
        values =
            exposures + cache.consumers.filter { c -> (focus == null || focus == c.resource) && (selectedColumn == null || c.column == null || c.column == selectedColumn) }
        val previous = selected
        consumerList.setListData(values.toTypedArray()); previous?.takeIf { it in values }
            ?.let { consumerList.setSelectedValue(it, true) }
        status.text = (if (refreshing) "Refreshing Metabase metadata…" else cache.status) + " · Last refreshed: " +
                (if (cache.refreshedAt == 0L) "never" else java.util.Date(cache.refreshedAt)
                    .toString()) + if (cache.refreshedAt != 0L && cache.stale) " (stale)" else ""
        status.toolTipText = status.text; toolbar.updateActionsAsync()
    }

    private fun refreshMetabase() {
        refreshing = true; update()
        DbtUi.background(
            project, "Refresh Metabase consumers", finished = {
                if (!disposed) {
                    refreshing = false; update()
                }
            },
            work = { service.refresh(DbtUi.read { DbtWorkspace.get(project) }) }) { if (!disposed) update() }
    }

    override fun dispose() {
        disposed = true; generation.incrementAndGet()
    }
}

class DbtConsumersAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val (project, source, offset) = DbtEditorAction.caret(event.dataContext) ?: return
        val window = ToolWindowManager.getInstance(project).getToolWindow("dbt") ?: return
        window.activate {
            window.contentManager.findContent("Exposures")?.let { content ->
                window.contentManager.setSelectedContent(content)
                (content.component as? DbtConsumersPanel)?.showResource(source?.virtualFile, offset)
            }
        }
    }
}
