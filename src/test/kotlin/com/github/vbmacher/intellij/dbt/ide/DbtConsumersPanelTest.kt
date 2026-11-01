package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project.DbtProjectSettings
import com.github.vbmacher.intellij.dbt.project.DbtResourceIdentity
import com.intellij.mock.Mock
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.RegisterToolWindowTask
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.replaceService
import com.intellij.toolWindow.ToolWindowHeadlessManagerImpl
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.TabbedPaneContentUI
import com.intellij.ui.content.impl.ContentManagerImpl
import com.intellij.ui.jcef.JBCefApp
import java.awt.Container
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import org.junit.Assert.*

/** The Exposures tab reuses one view, follows resource context and only fetches Metabase on request. */
class DbtConsumersPanelTest : DbtTestCase() {
    protected override fun tearDown() {
        try {
            val service = project.getService(DbtConsumers::class.java); service.setSecret(""); service.clear()
        } finally {
            super.tearDown()
        }
    }

    private fun window(): ToolWindow {
        val manager = ToolWindowHeadlessManagerImpl(project)
        project.replaceService(ToolWindowManager::class.java, manager, testRootDisposable)
        val window = manager.registerToolWindow(RegisterToolWindowTask.notClosable("dbt"))
        Disposer.register(testRootDisposable, com.intellij.openapi.Disposable { manager.unregisterToolWindow("dbt") })
        val testMode = Registry.get("ide.browser.jcef.testMode.enabled");
        val enabled = Registry.get("ide.browser.jcef.enabled")
        val wasTestMode = testMode.asBoolean();
        val wasEnabled = enabled.asBoolean()
        try {
            testMode.setValue(true); enabled.setValue(false); DbtToolWindowFactory().createToolWindowContent(
                project,
                window
            )
        } finally {
            enabled.setValue(wasEnabled); JBCefApp.isSupported(); testMode.setValue(wasTestMode)
        }
        return window
    }

    private fun rows(panel: JComponent): List<String> {
        @Suppress("UNCHECKED_CAST")
        val list = components(panel).filterIsInstance<JList<*>>().first() as JList<DbtConsumer>
        return (0 until list.model.size).map { i ->
            val row = list.cellRenderer.getListCellRendererComponent(list, list.model.getElementAt(i), i, false, false)
            components(row as Container).filterIsInstance<JLabel>().joinToString(" · ") { it.text }
        }
    }

    private fun action(panel: JComponent, name: String): (AnActionEvent) -> Unit {
        val toolbar = components(panel).filterIsInstance<ActionToolbar>().first()
        val action = toolbar.actionGroup.getChildren(null).first { it.templatePresentation.text == name }
        assertNotNull(action.templatePresentation.icon)
        return { event -> action.update(event); if (event.presentation.isEnabled) action.actionPerformed(event) }
    }

    private fun event(): AnActionEvent = AnActionEvent.createEvent(
        SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).build(),
        Presentation(),
        "test",
        ActionUiKind.NONE,
        null
    )

    private fun enabled(panel: JComponent, name: String): Boolean {
        val e = event()
        val toolbar = components(panel).filterIsInstance<ActionToolbar>().first()
        toolbar.actionGroup.getChildren(null).first { it.templatePresentation.text == name }.update(e)
        return e.presentation.isEnabled
    }

    private fun exposure(name: String, model: String): String =
        """  - name: $name
       |    type: dashboard
       |    url: https://example.org/orders
       |    depends_on:
       |      - ref('$model')
       |    owner:
       |      name: Finance Analyst
       |      email: finance@example.org
       |""".trimMargin()

    fun testActionSelectsTheExposuresTabAndReusesItForEachResource() {
        project()
        val orders = add("models/orders.sql", "select 1 as id")
        val customers = add("models/customers.sql", "select 2 as id")
        val definitions = add(
            "models/exposures.yml",
            "version: 2\nexposures:\n" + exposure("orders_dashboard", "orders") + exposure(
                "customers_dashboard",
                "customers"
            )
        )
        open(orders, 0)
        val toolWindow = window()
        val manager = toolWindow.getContentManager()
        assertEquals(listOf("Lineage", "Exposures"), manager.getContents().toList().map { it.getDisplayName() })
        val content = manager.findContent("Exposures")
        val panel = content.getComponent() as DbtConsumersPanel
        fun show(file: com.intellij.psi.PsiFile) {
            open(file, 0)
            val context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, getProject())
                .add(CommonDataKeys.PSI_FILE, file).add(CommonDataKeys.EDITOR, myFixture.getEditor()).build()
            DbtConsumersAction().actionPerformed(
                AnActionEvent.createEvent(
                    context,
                    Presentation(),
                    "test",
                    ActionUiKind.NONE,
                    null
                )
            )
        }
        show(orders)
        await("Orders consumers appear in the selected tool-window tab") {
            manager.getSelectedContent() == content && rows(
                panel
            ).size == 1 && rows(panel).first().startsWith("orders_dashboard")
        }
        manager.setSelectedContent(manager.findContent("Lineage"))
        show(customers)
        await("The existing tab switches to customers") {
            manager.getSelectedContent() == content && rows(panel).size == 1 && rows(
                panel
            ).first().startsWith("customers_dashboard")
        }
        assertEquals(2, manager.getContentCount())
        assertSame(panel, manager.findContent("Exposures")!!.getComponent())
        DbtLineageSelection.show(getProject(), root.directory.getPath(), "orders", "")
        assertEquals("Lineage", manager.getSelectedContent()!!.getDisplayName())
        show(customers)
        assertSame(content, manager.getSelectedContent())

        panel.showResource(orders.getVirtualFile(), 0)
        panel.showResource(customers.getVirtualFile(), 0)
        await("The most recent context wins") {
            rows(panel).size == 1 && rows(panel).first().startsWith("customers_dashboard")
        }
        edit(definitions, "version: 2\nexposures:\n" + exposure("renamed_dashboard", "customers"))
        panel.showResource(customers.getVirtualFile(), 0)
        await("Local declarations refresh without remote metadata") {
            rows(panel).size == 1 && rows(panel).first().startsWith("renamed_dashboard")
        }
        assertEquals(0L, getProject().getService(DbtConsumers::class.java).snapshot.refreshedAt)
    }

    fun testColumnFilteringAndFailedRefreshKeepTheirMeaningInTheTab() {
        project()
        val model = add(
            "models/fct_orders.sql",
            "{{ config(database='warehouse', schema='analytics', alias='fct_orders') }}\nselect 1 as id, 100 as gross_amount\n"
        )
        add(
            "models/schema.yml",
            "version: 2\nmodels:\n  - name: fct_orders\n    columns:\n      - name: id\n      - name: gross_amount\n"
        )
        add("models/exposures.yml", "version: 2\nexposures:\n" + exposure("orders_dashboard", "fct_orders"))
        open(model, 0)
        val toolWindow = window()
        val content = toolWindow.getContentManager().findContent("Exposures")
        toolWindow.getContentManager().setSelectedContent(content)
        val panel = content.getComponent() as DbtConsumersPanel
        panel.showResource(model.getVirtualFile(), 0)
        await("Local exposure is available while disconnected") { rows(panel).size == 1 }
        val fail = AtomicBoolean(false)
        val requests = AtomicInteger()
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        val responses = mapOf(
            "/api/card" to """[{"id":7,"name":"Revenue by amount","dataset_query":{"query":{"source-table":42,"fields":[["field",99,null]]}}},{"id":8,"name":"Orders overview","dataset_query":{"native":{"query":"select count(*) from warehouse.analytics.fct_orders"}}}]""",
            "/api/database" to """[{"id":1}]""",
            "/api/database/1/metadata" to """{"tables":[{"id":42,"db_name":"warehouse","schema":"analytics","name":"fct_orders","fields":[{"id":99,"name":"gross_amount"}]}]}""",
            "/api/dashboard" to """[{"id":3,"name":"Finance dashboard"}]""",
            "/api/dashboard/3" to """{"dashcards":[{"card_id":7}]}"""
        )
        server.createContext("/api/", { exchange ->
            requests.incrementAndGet()
            val bad = fail.get()
            val bytes =
                (if (bad) "unavailable" else responses.getValue(exchange.requestURI.path)).toByteArray(java.nio.charset.StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(if (bad) 503 else 200, bytes.size.toLong())
            exchange.getResponseBody().write(bytes)
            exchange.close()
        })
        server.start()
        val service = getProject().getService(DbtConsumers::class.java)
        try {
            getProject().getService(DbtConsumersSettings::class.java).getState().metabaseUrl =
                "http://127.0.0.1:" + server.getAddress().getPort()
            service.setSecret("local-screenshot-fixture")
            assertEquals(0, requests.get())
            val refresh = action(panel, "Refresh Metabase metadata")
            assertFalse(enabled(panel, "Open consumer"))
            assertFalse(enabled(panel, "Go to exposure definition"))
            val list = components(panel).filterIsInstance<JList<*>>().first()
            list.setSelectedIndex(0)
            assertTrue(enabled(panel, "Open consumer"))
            assertTrue(enabled(panel, "Go to exposure definition"))
            refresh(event())
            await("Explicit refresh populates the Exposures tab") {
                rows(panel).size == 4 && enabled(
                    panel,
                    "Refresh Metabase metadata"
                )
            }
            assertEquals(0, list.getSelectedIndex())
            list.setSelectedIndex(1)
            assertTrue(enabled(panel, "Open consumer"))
            assertFalse(enabled(panel, "Go to exposure definition"))
            list.clearSelection()
            val refreshed = service.snapshot
            assertTrue(rows(panel).any { it.contains("direct physical metadata") })
            val requestCount = requests.get()
            val column = components(panel).filterIsInstance<JComboBox<*>>().first()
            val capture = screenshotCapture(toolWindow)
            capture("downstream-consumers-all")
            column.setSelectedItem("id")
            assertEquals(2, rows(panel).size)
            assertTrue(rows(panel).all { it.contains("model-level; column use unknown") })
            assertTrue(rows(panel).any { it.startsWith("Orders overview") })
            capture("downstream-consumers-column")
            panel.showResource(model.getVirtualFile(), 0)
            await("Same-resource refresh preserves the column filter") { column.getSelectedItem() == "id" && rows(panel).size == 2 }
            assertEquals(requestCount, requests.get())

            column.setSelectedItem("All columns")
            fail.set(true)
            refresh(event())
            await("Failed refresh displays its error and retains consumers") {
                service.snapshot.status.contains("HTTP 503") && enabled(
                    panel,
                    "Refresh Metabase metadata"
                )
            }
            assertEquals(refreshed.consumers, service.snapshot.consumers)
            assertEquals(refreshed.refreshedAt, service.snapshot.refreshedAt)
            assertEquals(4, rows(panel).size)
            assertTrue(components(panel).filterIsInstance<JLabel>().map { it.text }.any { it.contains("HTTP 503") })
            capture("downstream-consumers-refresh-error")

            panel.showResource(null, 0)
            await("Missing resource context shows all consumers") {
                components(panel).filterIsInstance<JLabel>().map { it.text }.contains("All resources")
            }
            assertEquals(4, rows(panel).size)
            assertEquals("All columns", column.getSelectedItem())
            assertFalse(getProject().getService(DbtRunner::class.java).isRunning)
        } finally {
            server.stop(0)
        }
    }

    fun testEditorSwitchUpdatesExposuresIndependentlyOfPinnedLineage() {
        project()
        val orders = add("models/orders.sql", "select 1 as id")
        val customers = add("models/customers.sql", "select 2 as id")
        add(
            "models/exposures.yml",
            "version: 2\nexposures:\n" + exposure("orders_dashboard", "orders") + exposure(
                "customers_dashboard",
                "customers"
            )
        )
        val w = workspace
        val pinned = DbtResourceIdentity.key(w, w.symbols.find { s -> s.file == orders && s.isCode }!!)
        DbtProjectSettings.get(getProject()).getState().pinnedFocus = pinned
        var selected = orders.getVirtualFile()
        val editors = object : Mock.MyFileEditorManager() {
            override fun getProject(): com.intellij.openapi.project.Project = this@DbtConsumersPanelTest.project
            override fun getComposite(file: VirtualFile): com.intellij.openapi.fileEditor.impl.EditorComposite? = null
            override fun getOpenFiles(): Array<VirtualFile> = arrayOf(selected)
            override fun getSelectedFiles(): Array<VirtualFile> = arrayOf(selected)
        }
        project.replaceService(FileEditorManager::class.java, editors, getTestRootDisposable())
        val panel = window().getContentManager().findContent("Exposures").getComponent()
        await("Initial editor scopes consumers") {
            rows(panel).size == 1 && rows(panel).first().startsWith("orders_dashboard")
        }
        selected = customers.getVirtualFile()
        getProject().getMessageBus().syncPublisher(FileEditorManagerListener.FILE_EDITOR_MANAGER)
            .selectionChanged(
                FileEditorManagerEvent(
                    editors,
                    orders.getVirtualFile(),
                    null,
                    null,
                    selected,
                    null,
                    null
                )
            )
        await("Editor selection updates consumers while Lineage stays pinned") {
            rows(panel).size == 1 && rows(panel).first().startsWith("customers_dashboard")
        }
        assertEquals(pinned, DbtProjectSettings.get(getProject()).getState().pinnedFocus)
        assertEquals(0L, getProject().getService(DbtConsumers::class.java).snapshot.refreshedAt)
    }

    /** Render production tab contents in IntelliJ's tabbed content UI, without an installed IDE frame. */
    private fun screenshotCapture(window: ToolWindow): (String) -> Unit {
        val directory = System.getenv("DBT_CONSUMERS_SCREENSHOT_DIR") ?: return { _ -> }
        val manager =
            ContentManagerImpl(TabbedPaneContentUI(javax.swing.SwingConstants.TOP), false, project, testRootDisposable)
        window.contentManager.contents.forEach {
            manager.addContent(
                ContentFactory.getInstance().createContent(it.component, it.displayName, false)
            )
        }
        manager.setSelectedContent(manager.findContent("Exposures")!!)
        val view = manager.component
        return { name ->
            view.addNotify(); view.setSize(1200, 420)
            fun layout(c: Container) {
                c.doLayout(); c.components.filterIsInstance<Container>().forEach(::layout)
            }
            layout(view); layout(view)
            val toolbars = components(view).filterIsInstance<ActionToolbar>()
            val updates = toolbars.map { it.updateActionsAsync() }
            await("Toolbar icons are rendered") { updates.all { it.isDone } && toolbars.all { it.hasVisibleActions() } }
            layout(view)
            val image = java.awt.image.BufferedImage(view.width, view.height, java.awt.image.BufferedImage.TYPE_INT_RGB)
            val graphics = image.createGraphics()
            graphics.setRenderingHint(
                java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
                java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON
            )
            try {
                view.printAll(graphics)
            } finally {
                graphics.dispose(); view.removeNotify()
            }
            Files.createDirectories(Paths.get(directory))
            assertTrue(javax.imageio.ImageIO.write(image, "png", Paths.get(directory, name + ".png").toFile()))
        }
    }
}
