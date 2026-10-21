package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project.{DbtProjectSettings, DbtResourceIdentity}
import com.intellij.mock.Mock
import com.intellij.openapi.actionSystem.{ActionToolbar, ActionUiKind, AnActionEvent, CommonDataKeys, Presentation}
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.fileEditor.{FileEditorManager, FileEditorManagerEvent, FileEditorManagerListener}
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.{RegisterToolWindowTask, ToolWindow, ToolWindowManager}
import com.intellij.testFramework.ServiceContainerUtil
import com.intellij.toolWindow.ToolWindowHeadlessManagerImpl
import com.intellij.ui.content.{ContentFactory, TabbedPaneContentUI}
import com.intellij.ui.content.impl.ContentManagerImpl
import com.intellij.ui.jcef.JBCefApp
import java.awt.Container
import java.nio.file.{Files, Paths}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import javax.swing.{JComboBox, JComponent, JLabel, JList}
import org.junit.Assert._

/** The Exposures tab reuses one view, follows resource context and only fetches Metabase on request. */
final class DbtConsumersPanelTest extends DbtTestCase {
  override protected def tearDown(): Unit = try {
    val service = getProject.getService(classOf[DbtConsumers])
    service.setSecret("")
    service.clear()
  } finally super.tearDown()

  private def window(): ToolWindow = {
    val manager = new ToolWindowHeadlessManagerImpl(getProject)
    ServiceContainerUtil.replaceService(getProject, classOf[ToolWindowManager], manager, getTestRootDisposable)
    val window = manager.registerToolWindow(RegisterToolWindowTask.notClosable("dbt"))
    Disposer.register(getTestRootDisposable, () => manager.unregisterToolWindow("dbt"))
    val testMode = Registry.get("ide.browser.jcef.testMode.enabled")
    val enabled = Registry.get("ide.browser.jcef.enabled")
    val wasTestMode = testMode.asBoolean()
    val wasEnabled = enabled.asBoolean()
    try {
      testMode.setValue(true)
      enabled.setValue(false)
      new DbtToolWindowFactory().createToolWindowContent(getProject, window)
    } finally {
      enabled.setValue(wasEnabled)
      JBCefApp.isSupported()
      testMode.setValue(wasTestMode)
    }
    window
  }

  private def rows(panel: JComponent): Vector[String] = {
    val list = components(panel).collectFirst { case list: JList[_] => list.asInstanceOf[JList[DbtConsumer]] }.get
    (0 until list.getModel.getSize).map { i =>
      val row = list.getCellRenderer.getListCellRendererComponent(list, list.getModel.getElementAt(i), i, false, false)
      components(row.asInstanceOf[Container]).collect { case label: JLabel => label.getText }.mkString(" · ")
    }.toVector
  }

  private def action(panel: JComponent, name: String): AnActionEvent => Unit = {
    val toolbar = components(panel).collectFirst { case t: ActionToolbar => t }.get
    val action = toolbar.getActionGroup.getChildren(null).find(_.getTemplatePresentation.getText == name).get
    assertNotNull(action.getTemplatePresentation.getIcon)
    event => { action.update(event); if (event.getPresentation.isEnabled) action.actionPerformed(event) }
  }

  private def event(): AnActionEvent = AnActionEvent.createEvent(
    SimpleDataContext.builder().add(CommonDataKeys.PROJECT, getProject).build(), new Presentation, "test", ActionUiKind.NONE, null)

  private def enabled(panel: JComponent, name: String): Boolean = {
    val e = event()
    val toolbar = components(panel).collectFirst { case t: ActionToolbar => t }.get
    toolbar.getActionGroup.getChildren(null).find(_.getTemplatePresentation.getText == name).get.update(e)
    e.getPresentation.isEnabled
  }

  private def exposure(name: String, model: String): String =
    s"""  - name: $name
       |    type: dashboard
       |    url: https://example.org/orders
       |    depends_on:
       |      - ref('$model')
       |    owner:
       |      name: Finance Analyst
       |      email: finance@example.org
       |""".stripMargin

  def testActionSelectsTheExposuresTabAndReusesItForEachResource(): Unit = {
    project()
    val orders = add("models/orders.sql", "select 1 as id")
    val customers = add("models/customers.sql", "select 2 as id")
    val definitions = add("models/exposures.yml", "version: 2\nexposures:\n" + exposure("orders_dashboard", "orders") + exposure("customers_dashboard", "customers"))
    open(orders, 0)
    val toolWindow = window()
    val manager = toolWindow.getContentManager
    assertEquals(Vector("Lineage", "Exposures"), manager.getContents.toVector.map(_.getDisplayName))
    val content = manager.findContent("Exposures")
    val panel = content.getComponent.asInstanceOf[DbtConsumersPanel]
    def show(file: com.intellij.psi.PsiFile): Unit = {
      open(file, 0)
      val context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, getProject)
        .add(CommonDataKeys.PSI_FILE, file).add(CommonDataKeys.EDITOR, myFixture.getEditor).build()
      new DbtConsumersAction().actionPerformed(AnActionEvent.createEvent(context, new Presentation, "test", ActionUiKind.NONE, null))
    }
    show(orders)
    await("Orders consumers appear in the selected tool-window tab")(manager.getSelectedContent == content && rows(panel).size == 1 && rows(panel).head.startsWith("orders_dashboard"))
    manager.setSelectedContent(manager.findContent("Lineage"))
    show(customers)
    await("The existing tab switches to customers")(manager.getSelectedContent == content && rows(panel).size == 1 && rows(panel).head.startsWith("customers_dashboard"))
    assertEquals(2, manager.getContentCount)
    assertSame(panel, manager.findContent("Exposures").getComponent)
    DbtLineageSelection.show(getProject, root.directory.getPath, "orders", "")
    assertEquals("Lineage", manager.getSelectedContent.getDisplayName)
    show(customers)
    assertSame(content, manager.getSelectedContent)

    panel.showResource(Some(orders.getVirtualFile), 0)
    panel.showResource(Some(customers.getVirtualFile), 0)
    await("The most recent context wins")(rows(panel).size == 1 && rows(panel).head.startsWith("customers_dashboard"))
    edit(definitions, "version: 2\nexposures:\n" + exposure("renamed_dashboard", "customers"))
    panel.showResource(Some(customers.getVirtualFile), 0)
    await("Local declarations refresh without remote metadata")(rows(panel).size == 1 && rows(panel).head.startsWith("renamed_dashboard"))
    assertEquals(0L, getProject.getService(classOf[DbtConsumers]).snapshot.refreshedAt)
  }

  def testColumnFilteringAndFailedRefreshKeepTheirMeaningInTheTab(): Unit = {
    project()
    val model = add("models/fct_orders.sql", "{{ config(database='warehouse', schema='analytics', alias='fct_orders') }}\nselect 1 as id, 100 as gross_amount\n")
    add("models/schema.yml", "version: 2\nmodels:\n  - name: fct_orders\n    columns:\n      - name: id\n      - name: gross_amount\n")
    add("models/exposures.yml", "version: 2\nexposures:\n" + exposure("orders_dashboard", "fct_orders"))
    open(model, 0)
    val toolWindow = window()
    val content = toolWindow.getContentManager.findContent("Exposures")
    toolWindow.getContentManager.setSelectedContent(content)
    val panel = content.getComponent.asInstanceOf[DbtConsumersPanel]
    panel.showResource(Some(model.getVirtualFile), 0)
    await("Local exposure is available while disconnected")(rows(panel).size == 1)
    val fail = new AtomicBoolean(false)
    val requests = new AtomicInteger
    val server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0)
    val responses = Map(
      "/api/card" -> """[{"id":7,"name":"Revenue by amount","dataset_query":{"query":{"source-table":42,"fields":[["field",99,null]]}}},{"id":8,"name":"Orders overview","dataset_query":{"native":{"query":"select count(*) from warehouse.analytics.fct_orders"}}}]""",
      "/api/database" -> """[{"id":1}]""",
      "/api/database/1/metadata" -> """{"tables":[{"id":42,"db_name":"warehouse","schema":"analytics","name":"fct_orders","fields":[{"id":99,"name":"gross_amount"}]}]}""",
      "/api/dashboard" -> """[{"id":3,"name":"Finance dashboard"}]""",
      "/api/dashboard/3" -> """{"dashcards":[{"card_id":7}]}"""
    )
    server.createContext("/api/", exchange => {
      requests.incrementAndGet()
      val bad = fail.get()
      val bytes = (if (bad) "unavailable" else responses(exchange.getRequestURI.getPath)).getBytes(java.nio.charset.StandardCharsets.UTF_8)
      exchange.sendResponseHeaders(if (bad) 503 else 200, bytes.length)
      exchange.getResponseBody.write(bytes)
      exchange.close()
    })
    server.start()
    val service = getProject.getService(classOf[DbtConsumers])
    try {
      getProject.getService(classOf[DbtConsumersSettings]).getState.metabaseUrl = "http://127.0.0.1:" + server.getAddress.getPort
      service.setSecret("local-screenshot-fixture")
      assertEquals(0, requests.get())
      val refresh = action(panel, "Refresh Metabase metadata")
      assertFalse(enabled(panel, "Open consumer"))
      assertFalse(enabled(panel, "Go to exposure definition"))
      val list = components(panel).collectFirst { case list: JList[_] => list }.get
      list.setSelectedIndex(0)
      assertTrue(enabled(panel, "Open consumer"))
      assertTrue(enabled(panel, "Go to exposure definition"))
      refresh(event())
      await("Explicit refresh populates the Exposures tab")(rows(panel).size == 4 && enabled(panel, "Refresh Metabase metadata"))
      assertEquals(0, list.getSelectedIndex)
      list.setSelectedIndex(1)
      assertTrue(enabled(panel, "Open consumer"))
      assertFalse(enabled(panel, "Go to exposure definition"))
      list.clearSelection()
      val refreshed = service.snapshot
      assertTrue(rows(panel).exists(_.contains("direct physical metadata")))
      val requestCount = requests.get()
      val column = components(panel).collectFirst { case c: JComboBox[_] => c }.get
      val capture = screenshotCapture(toolWindow)
      capture("downstream-consumers-all")
      column.setSelectedItem("id")
      assertEquals(2, rows(panel).size)
      assertTrue(rows(panel).forall(_.contains("model-level; column use unknown")))
      assertTrue(rows(panel).exists(_.startsWith("Orders overview")))
      capture("downstream-consumers-column")
      panel.showResource(Some(model.getVirtualFile), 0)
      await("Same-resource refresh preserves the column filter")(column.getSelectedItem == "id" && rows(panel).size == 2)
      assertEquals(requestCount, requests.get())

      column.setSelectedItem("All columns")
      fail.set(true)
      refresh(event())
      await("Failed refresh displays its error and retains consumers")(service.snapshot.status.contains("HTTP 503") && enabled(panel, "Refresh Metabase metadata"))
      assertEquals(refreshed.consumers, service.snapshot.consumers)
      assertEquals(refreshed.refreshedAt, service.snapshot.refreshedAt)
      assertEquals(4, rows(panel).size)
      assertTrue(components(panel).collect { case label: JLabel => label.getText }.exists(_.contains("HTTP 503")))
      capture("downstream-consumers-refresh-error")

      panel.showResource(None, 0)
      await("Missing resource context shows all consumers")(components(panel).collect { case label: JLabel => label.getText }.contains("All resources"))
      assertEquals(4, rows(panel).size)
      assertEquals("All columns", column.getSelectedItem)
      assertFalse(getProject.getService(classOf[DbtRunner]).isRunning)
    } finally server.stop(0)
  }

  def testEditorSwitchUpdatesExposuresIndependentlyOfPinnedLineage(): Unit = {
    project()
    val orders = add("models/orders.sql", "select 1 as id")
    val customers = add("models/customers.sql", "select 2 as id")
    add("models/exposures.yml", "version: 2\nexposures:\n" + exposure("orders_dashboard", "orders") + exposure("customers_dashboard", "customers"))
    val w = workspace
    val pinned = DbtResourceIdentity.key(w, w.symbols.find(s => s.file == orders && s.isCode).get)
    DbtProjectSettings.get(getProject).getState.pinnedFocus = pinned
    var selected = orders.getVirtualFile
    val editors = new Mock.MyFileEditorManager {
      override def getProject: com.intellij.openapi.project.Project = DbtConsumersPanelTest.this.getProject
      override def getComposite(file: VirtualFile): com.intellij.openapi.fileEditor.impl.EditorComposite = null
      override def getOpenFiles: Array[VirtualFile] = Array(selected)
      override def getSelectedFiles: Array[VirtualFile] = Array(selected)
    }
    ServiceContainerUtil.replaceService(getProject, classOf[FileEditorManager], editors, getTestRootDisposable)
    val panel = window().getContentManager.findContent("Exposures").getComponent
    await("Initial editor scopes consumers")(rows(panel).size == 1 && rows(panel).head.startsWith("orders_dashboard"))
    selected = customers.getVirtualFile
    getProject.getMessageBus.syncPublisher(FileEditorManagerListener.FILE_EDITOR_MANAGER)
      .selectionChanged(new FileEditorManagerEvent(editors, orders.getVirtualFile, null, null, selected, null, null))
    await("Editor selection updates consumers while Lineage stays pinned")(rows(panel).size == 1 && rows(panel).head.startsWith("customers_dashboard"))
    assertEquals(pinned, DbtProjectSettings.get(getProject).getState.pinnedFocus)
    assertEquals(0L, getProject.getService(classOf[DbtConsumers]).snapshot.refreshedAt)
  }

  /** Render production tab contents in IntelliJ's tabbed content UI, without an installed IDE frame. */
  private def screenshotCapture(window: ToolWindow): String => Unit = {
    sys.env.get("DBT_CONSUMERS_SCREENSHOT_DIR") match {
      case None => _ => ()
      case Some(directory) =>
        val manager = new ContentManagerImpl(new TabbedPaneContentUI(javax.swing.SwingConstants.TOP), false, getProject, getTestRootDisposable)
        window.getContentManager.getContents.foreach { content =>
          manager.addContent(ContentFactory.getInstance().createContent(content.getComponent, content.getDisplayName, false))
        }
        manager.setSelectedContent(manager.findContent("Exposures"))
        val view = manager.getComponent
        name => {
          view.addNotify()
          view.setSize(1200, 420)
          def layout(c: Container): Unit = { c.doLayout(); c.getComponents.collect { case child: Container => child }.foreach(layout) }
          layout(view); layout(view)
          val toolbars = components(view).collect { case toolbar: ActionToolbar => toolbar }
          val updates = toolbars.map(_.updateActionsAsync())
          await("Toolbar icons are rendered")(updates.forall(_.isDone) && toolbars.forall(_.hasVisibleActions))
          layout(view)
          val image = new java.awt.image.BufferedImage(view.getWidth, view.getHeight, java.awt.image.BufferedImage.TYPE_INT_RGB)
          val graphics = image.createGraphics()
          graphics.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
          try view.printAll(graphics) finally { graphics.dispose(); view.removeNotify() }
          Files.createDirectories(Paths.get(directory))
          assertTrue(javax.imageio.ImageIO.write(image, "png", Paths.get(directory, name + ".png").toFile))
        }
    }
  }
}
