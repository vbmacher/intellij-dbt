package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project.*
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import org.junit.Assert.*

import java.awt.Component
import java.awt.Container
import javax.swing.JComboBox
import javax.swing.JList
import javax.swing.JPasswordField
import javax.swing.JTabbedPane
import javax.swing.JTextField

/**
 * Contract: features/project-context.md and local-mcp-server.md: owning identities, supported metadata, source authority,
 * uncertain metadata, read-only integrations and their settings.
 */
class DbtIntegrationAcceptanceTest : DbtTestCase() {
    protected override val root: DbtRoot
        get() = workspace.roots.find { r ->
            r.name == "analytics" && !r.directory.getPath().endsWith("/other")
        }!!

    private fun json(text: String): JsonObject = JsonParser.parseString(text).getAsJsonObject()

    protected override fun tearDown() {
        try {
            getProject().getService(DbtAgent::class.java).stop()
            val consumers = getProject().getService(DbtConsumers::class.java)
            consumers.setSecret("");
            consumers.clear()
        } finally {
            super.tearDown()
        }
    }

    fun testAirflowSettingsPersistLocalSourcesAndRejectCredentialBearingLinks() {
        val state = DbtConsumersState()
        state.airflowPaths = arrayListOf("/dags/one", "/dags/two")
        state.airflowUrl = "https://airflow.test/base"
        val restored = XmlSerializer.deserialize(XmlSerializer.serialize(state), DbtConsumersState::class.java)
        assertEquals(listOf("/dags/one", "/dags/two"), restored.airflowPaths.toList())
        assertEquals("https://airflow.test/base", restored.airflowUrl)
        assertTrue(DbtAirflow.safeUrl("https://user:secret@airflow.test") == null)
        assertTrue(DbtAirflow.safeUrl("file:///etc/passwd") == null)
        assertTrue(DbtAirflow.safeUrl("javascript:alert(1)") == null)
    }

    fun testMetabasePhysicalCollisionsAndStaleMetadataNeverBecomeDirectLineage() {
        project()
        val declaration =
            "sources: [{name: raw, database: warehouse, schema: landing, tables: [{name: orders, identifier: physical_orders, columns: [{name: amount}]}]}]\n"
        add("models/sources.yml", declaration)
        add("dbt_packages/pkg/dbt_project.yml", "name: pkg\nconfig-version: 2\n")
        add("dbt_packages/pkg/models/sources.yml", declaration)
        val card =
            json("""{"id":7,"name":"Revenue","dataset_query":{"query":{"source-table":42,"fields":[["field",99,null]]}}}""")
        val table =
            json("""{"id":42,"db_name":"warehouse","schema":"landing","name":"physical_orders","fields":[{"id":99,"name":"amount"}]}""")
        val w = workspace
        val matches = DbtConsumers.mapQuestions(w, "https://metabase.test", listOf(card), listOf(table))
        assertEquals(
            setOf("source.analytics.raw.orders", "source.pkg.raw.orders"),
            matches.map { it.resource.split("|").last() }.toSet()
        )
        assertTrue(matches.all { it.confidence.startsWith("inferred") })
        val local = DbtWorkspace(getProject(), w.roots, w.files, w.symbols.filter { s -> w.owner(s) == root }.map { s ->
            if (s.kind == "source_table") s.copy(attributes = s.attributes + ("stale" to "true")) else s
        })
        assertTrue(
            DbtConsumers.mapQuestions(local, "https://metabase.test", listOf(card), listOf(table))
                .all { it.confidence.startsWith("inferred") })
        table.addProperty("db_name", "unrelated_target")
        assertTrue(DbtConsumers.mapQuestions(w, "https://metabase.test", listOf(card), listOf(table)).isEmpty())
    }

    fun testMetabaseDisconnectedFreshnessAndCredentialSettingsStayExplicitAndSecretFree() {
        project()
        val service = getProject().getService(DbtConsumers::class.java)
        assertTrue(service.snapshot.status.contains("Disconnected"))
        assertEquals(0L, service.snapshot.refreshedAt)
        assertTrue(service.refresh(workspace).status.contains("Configure"))
        assertTrue(
            DbtConsumersCache(
                emptyList<DbtConsumer>(),
                System.currentTimeMillis() - 16 * 60 * 1000,
                "Metadata refreshed"
            ).stale
        )
        assertFalse(DbtConsumersCache(emptyList<DbtConsumer>(), System.currentTimeMillis(), "Metadata refreshed").stale)
        service.setSecret("acceptance-secret-must-not-export")
        assertTrue(service.hasSecret)
        val settings = getProject().getService(DbtConsumersSettings::class.java)
        val configurable = DbtConsumersConfigurable(getProject())
        val panel = configurable.createComponent() as javax.swing.JPanel
        val key = components(panel).filterIsInstance<JPasswordField>().first()
        assertEquals("acceptance-secret-must-not-export", String(key.password))
        assertFalse(configurable.isModified())
        configurable.apply()
        assertTrue(service.hasSecret)
        assertFalse(configurable.isModified())
        key.setText("replacement-key")
        assertTrue(configurable.isModified())
        configurable.apply()
        assertEquals("replacement-key", service.apiKey)
        assertFalse(configurable.isModified())
        key.setText("")
        configurable.reset()
        assertEquals("replacement-key", String(key.password))
        val serialized = JDOMUtil.writeElement(XmlSerializer.serialize(settings.getState()))
        assertFalse(serialized.contains("acceptance-secret-must-not-export"))
        assertFalse(service.snapshot.toString().contains("acceptance-secret-must-not-export"))
        key.setText("")
        assertTrue(configurable.isModified())
        configurable.apply()
        assertFalse(service.hasSecret)
        assertFalse(configurable.isModified())
        configurable.disposeUIResources()
    }

    fun testMetabaseStalledResponseBodyTimesOutWithoutReplacingTheCache() {
        project()
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        server.createContext("/api/card", { exchange ->
            exchange.sendResponseHeaders(200, 1000)
            exchange.getResponseBody().write('['.code)
            exchange.getResponseBody().flush()
            started.countDown()
            try {
                release.await(5, java.util.concurrent.TimeUnit.SECONDS)
            } finally {
                exchange.close()
            }
        })
        server.start()
        val service = getProject().getService(DbtConsumers::class.java)
        try {
            getProject().getService(DbtConsumersSettings::class.java).getState().metabaseUrl =
                "http://127.0.0.1:" + server.getAddress().getPort()
            service.setSecret("bounded-fixture-key")
            val before = service.snapshot
            val start = System.nanoTime()
            val result = service.refresh(workspace, java.time.Duration.ofMillis(500))
            assertTrue(
                "Server sent headers and stalled the response body",
                started.await(1, java.util.concurrent.TimeUnit.SECONDS)
            )
            assertTrue(
                "Response body deadline must bound the call",
                System.nanoTime() - start < java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
            )
            assertEquals(before.refreshedAt, result.refreshedAt)
            assertEquals(before.consumers, result.consumers)
            assertTrue(result.status.contains("unavailable"))
            assertFalse(result.toString().contains("bounded-fixture-key"))
        } finally {
            release.countDown();
            server.stop(0)
        }
    }

    fun testOldMetabaseRequestsCannotPublishAfterConnectionSettingsChange() {
        project()
        listOf(200, 503).forEach { status ->
            val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
            val started = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            server.createContext("/", { exchange ->
                if (exchange.getRequestURI().getPath() == "/api/card") {
                    started.countDown()
                    release.await(10, java.util.concurrent.TimeUnit.SECONDS)
                }
                val bytes = "[]".toByteArray(java.nio.charset.StandardCharsets.UTF_8)
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                try {
                    exchange.responseBody.write(bytes)
                } finally {
                    exchange.close()
                }
            })
            server.start()
            val service = getProject().getService(DbtConsumers::class.java)
            val settings = getProject().getService(DbtConsumersSettings::class.java).getState()
            try {
                settings.metabaseUrl = "http://127.0.0.1:" + server.getAddress().getPort()
                service.setSecret("old-fixture-key")
                val current = workspace
                val pending = java.util.concurrent.CompletableFuture.supplyAsync { service.refresh(current) }
                await("The old connection request starts") { started.getCount() == 0L }
                settings.metabaseUrl = "https://replacement.test"
                service.setSecret("replacement-fixture-key")
                service.clear()
                val cleared = service.snapshot
                release.countDown()
                await("The old request completes") { pending.isDone }
                assertEquals(cleared, pending.get(1, java.util.concurrent.TimeUnit.SECONDS))
                assertEquals(cleared, service.snapshot)
            } finally {
                release.countDown()
                server.stop(0)
            }
        }
    }

    fun testAgentSchemasMatchRequiredArgumentsAndRejectAdditionalOrInvalidValues() {
        project()
        add("models/orders.sql", "select 1")
        val service = getProject().getService(DbtAgent::class.java)
        val schemas =
            DbtAgentProtocol.schemas.map { it.getAsJsonObject() }.map { s -> s.get("name").asString to s }.toMap()
        DbtAgentProtocol.Tools.forEach { (name, _, required) ->
            val schema = schemas.getValue(name).getAsJsonObject("inputSchema")
            assertEquals(required.toSet(), schema.getAsJsonArray("required").map { it.getAsString() }.toSet())
            assertEquals(
                DbtAgentProtocol.allowedArguments(name).toSet(),
                schema.getAsJsonObject("properties").keySet().toSet()
            )
            assertFalse(schema.get("additionalProperties").getAsBoolean())
            assertTrue(schemas.getValue(name).getAsJsonObject("annotations").get("readOnlyHint").getAsBoolean())
        }
        listOf(
            """{"project":"analytics","reference":"orders","offset":2147483648}""",
            """{"project":"analytics","reference":"orders","offset":-1}""",
            """{"project":"analytics","reference":"orders","offset":0.5}""",
            """{"project":"analytics","reference":"orders","offset":"0"}""",
            """{"project":"analytics","reference":"orders","shell":"dbt run"}""",
            """{"project":"","reference":"orders"}"""
        ).forEach { args ->
            val request =
                json("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"resolve_reference","arguments":""" + args + "}}")
            assertTrue(service.rpc(request).getAsJsonObject("result").get("isError").getAsBoolean())
        }
        val unknownTool =
            json("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"execute_sql","arguments":{}}}""")
        assertEquals(-32602, service.rpc(unknownTool).getAsJsonObject("error").get("code").getAsInt())
        val initialized =
            service.rpc(json("""{"jsonrpc":"2.0","id":2,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}"""))
                .getAsJsonObject("result")
        assertEquals(DbtAgentProtocol.Version, initialized.getAsJsonObject("serverInfo").get("version").getAsString())
        assertEquals("2025-06-18", initialized.get("protocolVersion").getAsString())
        assertTrue(initialized.getAsJsonObject("capabilities").has("tools"))
        val negotiated =
            service.rpc(json("""{"jsonrpc":"2.0","id":2,"method":"initialize","params":{"protocolVersion":"2024-11-05"}}"""))
                .getAsJsonObject("result")
        assertEquals(DbtAgentProtocol.LegacyVersions.first(), negotiated.get("protocolVersion").getAsString())
        assertEquals(0, service.rpc(json("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")).size())
        service.dispose()
        assertEquals(
            -32603,
            service.rpc(json("""{"jsonrpc":"2.0","id":3,"method":"tools/list"}""")).getAsJsonObject("error").get("code")
                .getAsInt()
        )
    }

    fun testAgentReadToolsRespectOwningFilesAndExclusionsWithoutStartingDbt() {
        project()
        add(".dbtignore", "models/private/**\n")
        val orders = add("models/orders.sql", "select 1 as amount")
        add("models/private/secret.sql", "select 123 as hidden")
        add("other/dbt_project.yml", "name: other\nconfig-version: 2\n")
        val other = add("other/models/report.sql", "select * from {{ ref('orders') }}")
        val w = workspace
        root.runOptions.executable = "dbt-acceptance-must-not-execute"
        val resources =
            DbtAgentProtocol.call(w, "list_resources", DbtAgentProtocol.obj("project" to root.directory.getPath()))
        val listed = resources.getAsJsonArray("resources").map { it.getAsJsonObject() }.toList()
        assertTrue(listed.any { it.get("resource_id").getAsString() == "model.analytics.orders" })
        assertFalse(listed.any { it.get("path").getAsString().contains("/private/") })
        assertFalse(listed.any { it.get("path").getAsString().contains("/other/") })
        val args = DbtAgentProtocol.obj(
            "project" to root.directory.getPath(),
            "file" to other.getVirtualFile().getPath(),
            "reference" to "orders"
        )
        args.addProperty("offset", other.getText().indexOf("orders"))
        assertEquals("missing", DbtAgentProtocol.call(w, "resolve_reference", args).get("status").getAsString())
        val docs = DbtAgentProtocol.call(
            w,
            "get_documentation",
            DbtAgentProtocol.obj("project" to root.directory.getPath(), "resource_id" to "model.analytics.orders")
        )
        assertEquals(orders.getVirtualFile().getPath(), docs.getAsJsonObject("resource").get("path").getAsString())
        val lineage = DbtAgentProtocol.call(
            w,
            "get_lineage",
            DbtAgentProtocol.obj("project" to root.directory.getPath(), "resource_id" to "model.analytics.orders")
        )
        assertEquals("partial", lineage.get("status").getAsString())
        assertFalse(getProject().getService(DbtRunner::class.java).isRunning)
    }

    fun testOneResourceSharesIdentityAcrossDeclarationsButIndependentOwnersRemainSeparate() {
        project()
        val code = add("models/orders.sql", "select 1")
        val properties = add("models/schema.yml", "models: [{name: orders}]\n")
        val other = add("other/dbt_project.yml", "name: analytics\nconfig-version: 2\n")
        val otherCode = add("other/models/orders.sql", "select 2")
        add("dbt_packages/util/dbt_project.yml", "name: util\nconfig-version: 2\n")
        val packageCode = add("dbt_packages/util/models/orders.sql", "select 3")
        val w = workspace
        val source = w.symbols.find { s -> s.file == code && s.isCode }!!
        val property = w.symbols.find { s -> s.file == properties && s.kind == "model" }!!
        val independent = w.symbols.find { s -> s.file == otherCode && s.isCode }!!
        val installed = w.symbols.find { s -> s.file == packageCode && s.isCode }!!
        assertNotEquals(source.id, property.id)
        assertEquals(DbtResourceIdentity.key(w, source), DbtResourceIdentity.key(w, property))
        assertEquals(source.uniqueId, independent.uniqueId)
        assertNotEquals(DbtResourceIdentity.key(w, source), DbtResourceIdentity.key(w, independent))
        assertNotEquals(DbtResourceIdentity.key(w, source), DbtResourceIdentity.key(w, installed))
        assertEquals(w.executionRoot(w.owner(source)!!), w.executionRoot(w.owner(installed)!!))
        assertEquals(other.getVirtualFile().getParent(), w.owner(independent)!!.directory)
    }

    private val reader get() = DbtManifestReader.get(getProject())

    private fun manifest(version: Int, nodes: String, project: String = "analytics"): String =
        """{"metadata":{"dbt_schema_version":"https://schemas.getdbt.com/dbt/manifest/v$version.json","project_name":"$project"},"nodes":$nodes}"""

    fun testConsumersShareSupportedSchemasAndResourceIdentityWhileBaselineValidationIsStricter() {
        val nodes = """{"model.analytics.orders":{"unique_id":"model.analytics.orders","name":"orders"}}"""
        for (version in 9..12) for (policy in listOf(
            DbtManifestPolicy.Navigation,
            DbtManifestPolicy.TestDiscovery,
            DbtManifestPolicy.Baseline
        )) {
            val result = reader.parse(manifest(version, nodes), policy).getOrThrow()
            assertEquals(version, result.schemaVersion)
            assertEquals(listOf("model.analytics.orders"), result.resourcesIn("nodes").map { it.id })
        }
        listOf(
            DbtManifestPolicy.Navigation,
            DbtManifestPolicy.TestDiscovery,
            DbtManifestPolicy.Baseline
        ).forEach { policy ->
            assertTrue(reader.parse(manifest(99, nodes), policy).isFailure)
        }
        val incomplete = manifest(12, """{"model.analytics.orders":{"config":[]}}""")
        assertTrue(reader.parse(incomplete, DbtManifestPolicy.Navigation).isSuccess)
        assertTrue(reader.parse(incomplete, DbtManifestPolicy.Baseline).isFailure)
    }

    fun testUnsavedManifestEditsInvalidateCachedRecordsAndDiscoveryChecksTheProject() {
        project()
        val file = add("metadata/manifest.json", manifest(12, """{"model.analytics.old":{"name":"old"}}"""))
        val root = workspace.roots.first()
        val path = file.getVirtualFile().getPath()
        assertEquals(
            listOf("model.analytics.old"),
            reader.read(root, path, DbtManifestPolicy.Navigation).getOrThrow().resources.map { it.id })
        edit(file, manifest(12, """{"model.analytics.new":{"name":"new"}}"""))
        listOf(DbtManifestPolicy.Navigation, DbtManifestPolicy.TestDiscovery).forEach { policy ->
            assertEquals(
                listOf("model.analytics.new"),
                reader.read(root, path, policy).getOrThrow().resources.map { it.id })
        }
        edit(file, manifest(12, "{}", "another_project"))
        assertTrue(reader.read(root, path, DbtManifestPolicy.TestDiscovery).isFailure)
        assertTrue(reader.read(root, path, DbtManifestPolicy.Navigation).isSuccess)
    }

    private val settings get() = getProject().getService(DbtConsumersSettings::class.java).getState()

    private fun folderAction(panel: Container, name: String, perform: Boolean = true) {
        val action =
            components(panel).filterIsInstance<ActionToolbar>().flatMap { it.actionGroup.getChildren(null).toList() }
                .first { it.templatePresentation.text == name }

        val event = AnActionEvent.createEvent(
            SimpleDataContext.builder().add(CommonDataKeys.PROJECT, getProject()).build(),
            Presentation(), "test", ActionUiKind.NONE, null
        )
        assertNotNull(action.getTemplatePresentation().getIcon())
        if (perform) action.actionPerformed(event)
    }

    /** Selects `root` in the settings project chooser and returns its manifest picker. */
    private fun manifestPicker(fields: List<Component>, root: DbtRoot): TextFieldWithBrowseButton {
        fields.filterIsInstance<JComboBox<*>>()
            .first { combo -> (0 until combo.itemCount).any { combo.getItemAt(it) == root.directory.path } }.selectedItem =
            root.directory.path
        return fields.filterIsInstance<TextFieldWithBrowseButton>()
            .first { it.toolTipText?.startsWith("Optional manifest file") == true }
    }

    fun testDagProjectsApplyResetAndAllowEmptyFolders() {
        project()
        settings.airflowPaths.add("dags")
        val root = workspace.roots.first()
        val configurable = DbtSettingsConfigurable(getProject())
        try {
            val tabs = configurable.createComponent() as JTabbedPane
            assertEquals(
                listOf("General", "Integrations"),
                (0 until tabs.getTabCount()).map { tabs.getTitleAt(it) }.toList()
            )
            val fields = components(tabs)
            val manifest = manifestPicker(fields, root)
            folderAction(tabs, "Add folder…", perform = false)
            val folders = fields.filterIsInstance<JList<*>>().first()
            val url =
                fields.filterIsInstance<JTextField>().first { it.toolTipText?.startsWith("Optional HTTP") == true }
            assertFalse(configurable.isModified())
            manifest.setText("artifacts/current.json")
            tabs.setSelectedIndex(1)
            url.setText("https://airflow.test")
            assertTrue(configurable.isModified())
            configurable.apply()
            assertEquals("https://airflow.test", settings.airflowUrl)
            assertEquals(
                "artifacts/current.json",
                DbtProjectSettings.get(getProject()).options(root.directory.getPath()).manifestPath
            )
            assertFalse(configurable.isModified())
            System.getenv("DBT_INTEGRATIONS_SCREENSHOT")?.let { path ->
                tabs.addNotify()
                tabs.setSize(1000, 680)
                fun layout(container: Container) {
                    container.doLayout(); container.components.filterIsInstance<Container>().forEach(::layout)
                }
                layout(tabs); layout(tabs)
                val toolbars = components(tabs.getComponentAt(1)).filterIsInstance<ActionToolbar>()
                val updates = toolbars.map { it.updateActionsAsync() }
                await("Folder toolbar icons are rendered") { updates.all { it.isDone } && toolbars.all { it.hasVisibleActions() } }
                layout(tabs)
                val image = java.awt.image.BufferedImage(
                    tabs.getWidth(),
                    tabs.getHeight(),
                    java.awt.image.BufferedImage.TYPE_INT_RGB
                )
                val graphics = image.createGraphics()
                try {
                    tabs.printAll(graphics)
                } finally {
                    graphics.dispose(); tabs.removeNotify()
                }
                assertTrue(javax.imageio.ImageIO.write(image, "png", java.io.File(path)))
            }
            folders.setSelectedIndex(0)
            folderAction(tabs, "Remove folder")
            assertTrue(configurable.isModified())
            manifest.setText("discarded.json")
            configurable.reset()
            assertEquals(1, folders.getModel().getSize())
            assertEquals("artifacts/current.json", manifest.getText())
            folders.setSelectedIndex(0)
            folderAction(tabs, "Remove folder")
            configurable.apply()
            assertTrue(settings.airflowPaths.isEmpty())
            configurable.reset()
            assertEquals(0, folders.getModel().getSize())
            assertFalse(configurable.isModified())
            manifest.setText("must-not-save.json")
            url.setText("https://user:password@airflow.test")
            try {
                configurable.apply();
                fail("Credential-bearing browser links must be rejected")
            } catch (_: ConfigurationException) {
            }
            assertEquals("https://airflow.test", settings.airflowUrl)
            assertEquals(
                "artifacts/current.json",
                DbtProjectSettings.get(getProject()).options(root.directory.getPath()).manifestPath
            )
            val secret = fields.filterIsInstance<JPasswordField>().first()
            secret.setText("unsaved-secret")
            configurable.disposeUIResources()
            assertEquals(0, secret.password.size)
        } finally {
            configurable.disposeUIResources()
        }
    }

    fun testManifestPickerSavesPerProjectAndCopiesWithRunOptions() {
        val config = project()
        val root = workspace.roots.first()
        val configurable = DbtSettingsConfigurable(getProject())
        try {
            val fields = components(configurable.createComponent())
            val manifest = manifestPicker(fields, root)
            manifest.setText("artifacts/current.json")
            assertTrue(configurable.isModified())
            configurable.apply()
            val options = DbtProjectSettings.get(getProject()).options(config.getVirtualFile().getParent().getPath())
            assertEquals("artifacts/current.json", options.manifestPath)
            assertEquals("artifacts/current.json", options.copy().manifestPath)
            configurable.reset()
            assertEquals("artifacts/current.json", manifest.getText())
        } finally {
            configurable.disposeUIResources()
        }
    }
}
