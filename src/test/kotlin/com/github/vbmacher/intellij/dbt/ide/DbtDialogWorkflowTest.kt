package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project.DbtProjectSettings
import com.intellij.execution.services.ServiceViewContributor
import com.intellij.execution.services.ServiceViewManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.fixtures.TempDirTestFixture
import com.intellij.usages.impl.UsageViewImpl
import org.junit.Assert.*
import java.awt.Container
import java.awt.datatransfer.DataFlavor
import java.nio.file.Files
import java.nio.file.Paths
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JTextArea

/** Native result/service workflows, without a dbt executable, scheduler or external server. */
class DbtDialogWorkflowTest : DbtTestCase() {
    private var contentRoot: VirtualFile? = null
    override fun createTempDirTestFixture(): TempDirTestFixture =
        IdeaTestFixtureFactory.getFixtureFactory().createTempDirTestFixture()

    override fun project(config: String, path: String): PsiFile {
        val file = super.project(config, path)
        contentRoot = file.virtualFile.parent
        PsiTestUtil.addContentRoot(getModule(), contentRoot!!)
        return file
    }

    override fun tearDown() {
        try {
            getProject().getService(DbtAgent::class.java).stop()
            if (contentRoot != null) PsiTestUtil.removeContentEntry(getModule(), contentRoot!!)
        } finally {
            super.tearDown()
        }
    }

    private fun event(action: AnAction): AnActionEvent = AnActionEvent.createEvent(
        SimpleDataContext.builder().add(CommonDataKeys.PROJECT, getProject()).build(),
        Presentation(),
        "test",
        ActionUiKind.NONE,
        null
    )

    private fun capture(component: JComponent, name: String) {
        System.getenv("DBT_DIALOG_SCREENSHOT_DIR")?.let { directory ->
            val parent = component.parent
            val index = if (parent == null) -1 else parent.getComponentZOrder(component)
            val constraints = (parent?.layout as? java.awt.BorderLayout)?.getConstraints(component)
            val rootPane = javax.swing.JRootPane()
            rootPane.setGlassPane(com.intellij.openapi.wm.impl.IdeGlassPaneImpl(rootPane, false))
            rootPane.contentPane.add(component)
            val host = javax.swing.JPanel(java.awt.BorderLayout())
            host.add(rootPane)
            host.addNotify()
            try {
                for (width in listOf(640, 1000)) {
                    host.setSize(width, 480)
                    fun layout(c: Container) {
                        c.doLayout(); c.components.filterIsInstance<Container>().forEach(::layout)
                    }
                    layout(host); layout(host)
                    val toolbars = components(component).filterIsInstance<ActionToolbar>()
                    val updates = toolbars.map { it.updateActionsAsync() }
                    await("Native result toolbars finish updating") { updates.all { it.isDone } }
                    val trees = components(component).filterIsInstance<javax.swing.JTree>()
                    trees.forEach { tree -> com.intellij.util.ui.tree.TreeUtil.expandAll(tree) }
                    await("Native result rows are visible") { trees.all { it.rowCount > 0 } }
                    layout(component)
                    val image = java.awt.image.BufferedImage(width, 480, java.awt.image.BufferedImage.TYPE_INT_RGB)
                    val graphics = image.createGraphics()
                    try {
                        component.printAll(graphics)
                    } finally {
                        graphics.dispose()
                    }
                    Files.createDirectories(Paths.get(directory))
                    assertTrue(
                        javax.imageio.ImageIO.write(
                            image,
                            "png",
                            Paths.get(directory, "$name-$width.png").toFile()
                        )
                    )
                }
            } finally {
                host.removeNotify()
                rootPane.contentPane.remove(component)
                if (parent != null) parent.add(component, constraints, index)
            }
        }
    }

    fun testPreviewActionsRemainVisibleInNarrowResults() {
        val grid = DbtResultGrid(getProject(), { _ -> })
        grid.showResult(
            DbtQueryResult.parse(
                """{"show":[{"id":7,"name":"Orders"}]}""",
                "analytics-development", 1, 25, "select * from orders"
            ).getOrThrow()
        )
        capture(grid, "preview-results")
        fun layout(c: Container) {
            c.doLayout(); c.components.filterIsInstance<Container>().forEach(::layout)
        }
        for (width in listOf(480, 640, 1000)) {
            grid.setSize(width, 480)
            layout(grid); layout(grid)
            components(grid).filterIsInstance<JButton>().forEach { button ->
                val bounds = javax.swing.SwingUtilities.convertRectangle(button.parent, button.bounds, grid)
                assertTrue(
                    button.text + " stays inside the result panel",
                    bounds.x >= 0 && bounds.x + bounds.width <= width
                )
                assertEquals(
                    button.text + " at " + width + ": " + button.bounds + " in " + button.parent.bounds +
                            "; toolbar preferred " + button.parent.preferredSize, button.size, button.visibleRect.size
                )
            }
            val table = components(grid).filterIsInstance<javax.swing.JTable>().first()
            assertTrue("The toolbar leaves space for query results", table.parent.height > 100)
        }
    }

    fun testMcpServicesActionsFollowOptInLifecycleAndCopyCurrentConfiguration() {
        project()
        val service = getProject().getService(DbtAgent::class.java)
        val settings = getProject().getService(DbtConsumersSettings::class.java).getState()
        settings.agentEnabled = false
        val contributor = ServiceViewContributor.CONTRIBUTOR_EP_NAME.extensionList
            .filterIsInstance<DbtAgentServiceView>().first()
        assertEquals(service, contributor.getServices(getProject()).get(0))
        assertNotNull("The bundled Services implementation is loaded", ServiceViewManager.getInstance(getProject()))
        val descriptor = contributor.getServiceDescriptor(getProject(), service)
        val actions =
            descriptor.toolbarActions!!.getChildren(null).map { a -> a.templatePresentation.text to a }.toMap()

        fun enabled(label: String): Boolean {
            val e = event(actions.getValue(label)); actions.getValue(label).update(e); return e.presentation.isEnabled
        }
        assertFalse(enabled("Start"))
        assertFalse(enabled("Stop"))
        assertFalse(enabled("Copy MCP client configuration"))
        settings.agentEnabled = true
        assertTrue(enabled("Start"))
        actions.getValue("Start").actionPerformed(event(actions.getValue("Start")))
        try {
            assertTrue(service.endpoint != null)
            assertFalse(enabled("Start"))
            assertTrue(enabled("Stop"))
            assertTrue(enabled("Copy MCP client configuration"))
            actions.getValue("Copy MCP client configuration")
                .actionPerformed(event(actions.getValue("Copy MCP client configuration")))
            val config = CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor)
            val (url, token) = service.endpoint!!
            assertEquals(DbtAgentProtocol.clientConfiguration(url, token), config)
            val content = descriptor.contentComponent!!
            val status = components(content).filterIsInstance<JTextArea>().map { area -> area.text }.joinToString("")
            assertTrue(status, status.contains(url))
            assertFalse("Authorization is copied on request, not displayed in the status panel", status.contains(token))
            capture(content, "mcp-running")
            actions.getValue("Stop").actionPerformed(event(actions.getValue("Stop")))
            assertTrue(service.endpoint == null)
            assertFalse(enabled("Stop"))
            assertFalse(enabled("Copy endpoint and authorization"))
            assertEquals(config, CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor))
            capture(descriptor.contentComponent!!, "mcp-stopped")
        } finally {
            service.stop()
        }
    }

    fun testAirflowFindResultsKeepSameLineTasksAndRefreshAfterSourceAndSettingsChanges() {
        val config = project()
        add("models/orders.sql", "select 1 as id")
        val dag = add(
            "dags/daily.py",
            "jobs = [Cfg(dag_id='finance', task_id='first', dbt_selector='orders'), Cfg(dag_id='finance', task_id='second', dbt_selector='orders')]\n"
        )
        val settings = getProject().getService(DbtConsumersSettings::class.java).getState()
        settings.airflowPaths = java.util.ArrayList<String>(listOf(config.virtualFile.parent.findChild("dags")!!.path))
        DbtProjectSettings.get(getProject()).changed()
        val view = DbtAirflowResults.show(getProject()).let { it as UsageViewImpl }
        try {
            await("Find results show both tasks from the same Python line") { view.usagesCount == 2 }
            val rendered = java.util.concurrent.CompletableFuture.runAsync { view.waitForUpdateRequestsCompletion() }
            await("Find result nodes finish rendering") { rendered.isDone }
            rendered.get()
            val rows = view.usages.filterIsInstance<DbtAirflowUsage>().toList()
            view.selectUsages(arrayOf(rows.first()))
            await("The native Find tree selects the task") { view.selectedUsages?.isNotEmpty() == true }
            components(view.component).filterIsInstance<javax.swing.JTree>()
                .forEach { tree -> com.intellij.util.ui.tree.TreeUtil.expandAll(tree) }
            assertEquals(setOf("first", "second"), rows.map { it.task.task }.toSet())
            val provider = DbtAirflowDetailsProvider()
            assertTrue(provider.isAvailableFor(view))
            val details = provider.create(view)
            try {
                details.updateLayout(getProject(), java.util.List.of(rows.first().usageInfo))
                val text = components(details.createComponent()).filterIsInstance<JTextArea>().first()
                await("The task-details preview is ready") { text.text.startsWith("DAG / task:") }
                assertTrue(text.text, text.text.contains("Resources:\nmodel.analytics.orders"))
                assertTrue(text.text, text.text.contains("Selection: orders"))
                val browser = components(details.createComponent()).filterIsInstance<JButton>().first()
                assertFalse(browser.isEnabled)
                capture(view.component, "airflow-results")
                settings.airflowUrl = "https://airflow.test/"
                edit(dag, "Cfg(dag_id='finance', task_id='updated', dbt_selector='orders', schedule='@daily')\n")
                DbtProjectSettings.get(getProject()).changed()
                // Use the native Rerun path, which resets pending usage-tree updates before reloading.
                view.refreshUsages()
                await("Rerun replaces stale tasks and resolves newly configured links") {
                    view.usages.filterIsInstance<DbtAirflowUsage>().map { usage -> usage.task.task }.toList() == listOf(
                        "updated"
                    )
                }
                val updated = view.usages.filterIsInstance<DbtAirflowUsage>().first()
                details.updateLayout(getProject(), java.util.List.of(updated.usageInfo))
                await("The refreshed task-details preview resolves its browser link") { browser.isEnabled }
                assertTrue(browser.isEnabled)
                assertTrue(text.text.contains("Schedule: @daily"))
                capture(details.createComponent(), "airflow-details")
                details.updateLayout(getProject(), java.util.List.of())
                await("Clearing the task selection disables browser navigation") { !browser.isEnabled }
                assertFalse(browser.isEnabled)
            } finally {
                Disposer.dispose(details)
            }
        } finally {
            view.close()
        }
    }

    fun testCreationLabelsValidationAndCancelPreserveSource() {
        project()
        val file = configure("models/report.sql", "select * from {{ ref('missing_<caret>model') }}")
        val proposal = DbtCreateResourceFix.proposal(file, myFixture.caretOffset)!!
        val before = file.text
        val dialog = DbtCreateResourceDialog(getProject(), proposal)
        try {
            val labels =
                components(dialog.content).filterIsInstance<JLabel>().map { label -> label.text to label.labelFor }
                    .toMap()
            assertSame(dialog.name, labels["Logical name:"])
            assertSame(dialog.path, labels["Destination:"])
            assertSame(dialog.name, dialog.preferredFocusedComponent)
            assertNull(dialog.doValidate())
            dialog.name.setText("invalid name")
            assertSame(dialog.name, dialog.doValidate()!!.component)
            dialog.name.setText("missing_model")
            dialog.path.setText("../outside.sql")
            assertSame(dialog.path, dialog.doValidate()!!.component)
            dialog.path.setText("invalid\u0000path")
            assertEquals("Choose a valid destination path.", dialog.doValidate()!!.message)
            dialog.path.setText(proposal.path)
            assertNull(dialog.doValidate())
            capture(dialog.content, "create-resource")
        } finally {
            Disposer.dispose(dialog.disposable)
        }
        assertEquals(before, file.text)
        assertNull(root.directory.findFileByRelativePath(proposal.path))
    }
}
