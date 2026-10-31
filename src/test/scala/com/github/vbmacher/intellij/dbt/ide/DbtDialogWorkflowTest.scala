package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project.DbtProjectSettings
import com.intellij.execution.services.{ServiceViewContributor, ServiceViewManager}
import com.intellij.openapi.actionSystem.{ActionToolbar, ActionUiKind, AnAction, AnActionEvent, CommonDataKeys, Presentation}
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.{IdeaTestFixtureFactory, TempDirTestFixture}
import com.intellij.usages.impl.UsageViewImpl
import org.junit.Assert._
import java.awt.Container
import java.awt.datatransfer.DataFlavor
import java.nio.file.{Files, Paths}
import javax.swing.{JButton, JComponent, JLabel, JTextArea}
import scala.jdk.CollectionConverters._

/** Native result/service workflows, without a dbt executable, scheduler or external server. */
final class DbtDialogWorkflowTest extends DbtTestCase {
  private var contentRoot: VirtualFile = _
  override protected def createTempDirTestFixture(): TempDirTestFixture = IdeaTestFixtureFactory.getFixtureFactory.createTempDirTestFixture()
  override protected def project(config: String, path: String): PsiFile = {
    val file = super.project(config, path)
    contentRoot = file.getVirtualFile.getParent
    PsiTestUtil.addContentRoot(getModule, contentRoot)
    file
  }
  override protected def tearDown(): Unit = try {
    getProject.getService(classOf[DbtAgent]).stop()
    if (contentRoot != null) PsiTestUtil.removeContentEntry(getModule, contentRoot)
  } finally super.tearDown()

  private def event(action: AnAction): AnActionEvent = AnActionEvent.createEvent(
    SimpleDataContext.builder().add(CommonDataKeys.PROJECT, getProject).build(), new Presentation, "test", ActionUiKind.NONE, null)

  private def capture(component: JComponent, name: String): Unit = sys.env.get("DBT_DIALOG_SCREENSHOT_DIR").foreach { directory =>
    val parent = component.getParent
    val index = if (parent == null) -1 else parent.getComponentZOrder(component)
    val constraints = Option(parent).map(_.getLayout).collect { case layout: java.awt.BorderLayout => layout.getConstraints(component) }.orNull
    val rootPane = new javax.swing.JRootPane
    rootPane.setGlassPane(new com.intellij.openapi.wm.impl.IdeGlassPaneImpl(rootPane, false))
    rootPane.getContentPane.add(component)
    val host = new javax.swing.JPanel(new java.awt.BorderLayout)
    host.add(rootPane)
    host.addNotify()
    try for (width <- Vector(640, 1000)) {
      host.setSize(width, 480)
      def layout(c: Container): Unit = { c.doLayout(); c.getComponents.collect { case child: Container => child }.foreach(layout) }
      layout(host); layout(host)
      val toolbars = components(component).collect { case toolbar: ActionToolbar => toolbar }
      val updates = toolbars.map(_.updateActionsAsync())
      await("Native result toolbars finish updating")(updates.forall(_.isDone))
      val trees = components(component).collect { case tree: javax.swing.JTree => tree }
      trees.foreach(tree => com.intellij.util.ui.tree.TreeUtil.expandAll(tree))
      await("Native result rows are visible")(trees.forall(_.getRowCount > 0))
      layout(component)
      val image = new java.awt.image.BufferedImage(width, 480, java.awt.image.BufferedImage.TYPE_INT_RGB)
      val graphics = image.createGraphics()
      try component.printAll(graphics) finally graphics.dispose()
      Files.createDirectories(Paths.get(directory))
      assertTrue(javax.imageio.ImageIO.write(image, "png", Paths.get(directory, s"$name-$width.png").toFile))
    } finally {
      host.removeNotify()
      rootPane.getContentPane.remove(component)
      if (parent != null) parent.add(component, constraints, index)
    }
  }

  def testMcpServicesActionsFollowOptInLifecycleAndCopyCurrentConfiguration(): Unit = {
    project()
    val service = getProject.getService(classOf[DbtAgent])
    val settings = getProject.getService(classOf[DbtConsumersSettings]).getState
    settings.agentEnabled = false
    val contributor = ServiceViewContributor.CONTRIBUTOR_EP_NAME.getExtensionList.asScala
      .collectFirst { case c: DbtAgentServiceView => c }.get
    assertEquals(service, contributor.getServices(getProject).get(0))
    assertNotNull("The bundled Services implementation is loaded", ServiceViewManager.getInstance(getProject))
    val descriptor = contributor.getServiceDescriptor(getProject, service)
    val actions = descriptor.getToolbarActions.getChildren(null).map(a => a.getTemplatePresentation.getText -> a).toMap
    def enabled(label: String): Boolean = {
      val e = event(actions(label)); actions(label).update(e); e.getPresentation.isEnabled
    }
    assertFalse(enabled("Start"))
    assertFalse(enabled("Stop"))
    assertFalse(enabled("Copy MCP client configuration"))
    settings.agentEnabled = true
    assertTrue(enabled("Start"))
    actions("Start").actionPerformed(event(actions("Start")))
    try {
      assertTrue(service.endpoint.nonEmpty)
      assertFalse(enabled("Start"))
      assertTrue(enabled("Stop"))
      assertTrue(enabled("Copy MCP client configuration"))
      actions("Copy MCP client configuration").actionPerformed(event(actions("Copy MCP client configuration")))
      val config = CopyPasteManager.getInstance().getContents[String](DataFlavor.stringFlavor)
      val (url, token) = service.endpoint.get
      assertEquals(DbtAgentProtocol.clientConfiguration(url, token), config)
      val content = descriptor.getContentComponent
      val status = components(content).collect { case area: JTextArea => area.getText }.mkString
      assertTrue(status, status.contains(url))
      assertFalse("Authorization is copied on request, not displayed in the status panel", status.contains(token))
      capture(content, "mcp-running")
      actions("Stop").actionPerformed(event(actions("Stop")))
      assertTrue(service.endpoint.isEmpty)
      assertFalse(enabled("Stop"))
      assertFalse(enabled("Copy endpoint and authorization"))
      assertEquals(config, CopyPasteManager.getInstance().getContents[String](DataFlavor.stringFlavor))
      capture(descriptor.getContentComponent, "mcp-stopped")
    } finally service.stop()
  }

  def testAirflowFindResultsKeepSameLineTasksAndRefreshAfterSourceAndSettingsChanges(): Unit = {
    val config = project()
    add("models/orders.sql", "select 1 as id")
    val dag = add("dags/daily.py", "jobs = [Cfg(dag_id='finance', task_id='first', dbt_selector='orders'), Cfg(dag_id='finance', task_id='second', dbt_selector='orders')]\n")
    val settings = getProject.getService(classOf[DbtConsumersSettings]).getState
    settings.airflowPaths = new java.util.ArrayList[String](Vector(config.getVirtualFile.getParent.findChild("dags").getPath).asJava)
    DbtProjectSettings.get(getProject).changed()
    val view = DbtAirflowResults.show(getProject).asInstanceOf[UsageViewImpl]
    try {
      await("Find results show both tasks from the same Python line")(view.getUsagesCount == 2)
      val rendered = java.util.concurrent.CompletableFuture.runAsync(() => view.waitForUpdateRequestsCompletion())
      await("Find result nodes finish rendering")(rendered.isDone)
      rendered.get()
      val rows = view.getUsages.asScala.collect { case usage: DbtAirflowUsage => usage }.toVector
      view.selectUsages(Array(rows.head))
      await("The native Find tree selects the task")(Option(view.getSelectedUsages).exists(!_.isEmpty))
      components(view.getComponent).collect { case tree: javax.swing.JTree => tree }
        .foreach(tree => com.intellij.util.ui.tree.TreeUtil.expandAll(tree))
      assertEquals(Set("first", "second"), rows.map(_.task.task).toSet)
      val provider = new DbtAirflowDetailsProvider
      assertTrue(provider.isAvailableFor(view))
      val details = provider.create(view)
      try {
        details.updateLayout(getProject, java.util.List.of(rows.head.getUsageInfo))
        val text = components(details.createComponent()).collectFirst { case area: JTextArea => area }.get
        await("The task-details preview is ready")(text.getText.startsWith("DAG / task:"))
        assertTrue(text.getText, text.getText.contains("Resources:\nmodel.analytics.orders"))
        assertTrue(text.getText, text.getText.contains("Selection: orders"))
        val browser = components(details.createComponent()).collectFirst { case button: JButton => button }.get
        assertFalse(browser.isEnabled)
        capture(view.getComponent, "airflow-results")
        settings.airflowUrl = "https://airflow.test/"
        edit(dag, "Cfg(dag_id='finance', task_id='updated', dbt_selector='orders', schedule='@daily')\n")
        DbtProjectSettings.get(getProject).changed()
        assertSame(view, view.doReRunTestAccessor())
        await("Rerun replaces stale tasks and resolves newly configured links") {
          view.getUsages.asScala.collect { case usage: DbtAirflowUsage => usage.task.task }.toVector == Vector("updated")
        }
        val updated = view.getUsages.asScala.collectFirst { case usage: DbtAirflowUsage => usage }.get
        details.updateLayout(getProject, java.util.List.of(updated.getUsageInfo))
        await("The refreshed task-details preview resolves its browser link")(browser.isEnabled)
        assertTrue(browser.isEnabled)
        assertTrue(text.getText.contains("Schedule: @daily"))
        capture(details.createComponent(), "airflow-details")
        details.updateLayout(getProject, java.util.List.of())
        await("Clearing the task selection disables browser navigation")(!browser.isEnabled)
        assertFalse(browser.isEnabled)
      } finally Disposer.dispose(details)
    } finally view.close()
  }

  def testCreationLabelsValidationAndCancelPreserveSource(): Unit = {
    project()
    val file = configure("models/report.sql", "select * from {{ ref('missing_<caret>model') }}")
    val proposal = DbtCreateResourceFix.proposal(file, myFixture.getCaretOffset).get
    val before = file.getText
    val dialog = new DbtCreateResourceDialog(getProject, proposal)
    try {
      val labels = components(dialog.content).collect { case label: JLabel => label.getText -> label.getLabelFor }.toMap
      assertSame(dialog.name, labels("Logical name:"))
      assertSame(dialog.path, labels("Destination:"))
      assertSame(dialog.name, dialog.getPreferredFocusedComponent)
      assertNull(dialog.doValidate())
      dialog.name.setText("invalid name")
      assertSame(dialog.name, dialog.doValidate().component)
      dialog.name.setText("missing_model")
      dialog.path.setText("../outside.sql")
      assertSame(dialog.path, dialog.doValidate().component)
      dialog.path.setText("invalid\u0000path")
      assertEquals("Choose a valid destination path.", dialog.doValidate().message)
      dialog.path.setText(proposal.path)
      assertNull(dialog.doValidate())
      capture(dialog.content, "create-resource")
    } finally Disposer.dispose(dialog.getDisposable)
    assertEquals(before, file.getText)
    assertNull(root.directory.findFileByRelativePath(proposal.path))
  }
}
