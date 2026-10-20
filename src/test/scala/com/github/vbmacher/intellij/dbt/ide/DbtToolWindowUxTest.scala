package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project._
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import org.junit.Assert._
import java.awt.{Component, Container}
import javax.swing.{JComboBox, JTextArea, JTextField}
import scala.jdk.CollectionConverters._

final class DbtToolWindowUxTest extends DbtCodeInsightTestCase {
  override protected def tearDown(): Unit = try DbtProjectSettings.get(getProject).loadState(new DbtSettingsState) finally super.tearDown()
  private def children(c: Component): Vector[Component] = Vector(c) ++ (c match {
    case p: Container => p.getComponents.toVector.flatMap(children)
    case _ => Vector.empty
  })
  private def await(message: String)(condition: => Boolean): Unit =
    PlatformTestUtil.waitWithEventsDispatching(message, () => condition, 15)
  private def combo(panel: Component, name: String): JComboBox[String] = children(panel).collectFirst {
    case c: JComboBox[_] if c.getName == name => c.asInstanceOf[JComboBox[String]]
  }.get
  private def layout(c: Component): Unit = c match {
    case p: Container => p.doLayout(); p.getComponents.foreach(layout)
    case _ => ()
  }
  private def capture(panel: Component, label: String): Unit = Option(System.getProperty("dbt.ux.screenshot")).foreach { prefix =>
    val lookAndFeel = javax.swing.UIManager.getLookAndFeel
    javax.swing.UIManager.setLookAndFeel(new com.intellij.ide.ui.laf.IntelliJLaf)
    javax.swing.SwingUtilities.updateComponentTreeUI(panel)
    try for (width <- Vector(600, 1100)) {
      panel.setSize(width, 700); layout(panel)
      val image = new java.awt.image.BufferedImage(width, 700, java.awt.image.BufferedImage.TYPE_INT_RGB)
      val graphics = image.createGraphics()
      try panel.printAll(graphics) finally graphics.dispose()
      javax.imageio.ImageIO.write(image, "png", new java.io.File(prefix + "-" + label + "-" + width + ".png"))
    } finally {
      javax.swing.UIManager.setLookAndFeel(lookAndFeel)
      javax.swing.SwingUtilities.updateComponentTreeUI(panel)
    }
  }
  def testDiscoveryFiltersKeepDependenciesAndSelectionInspectionNeverExecutes(): Unit = {
    project()
    add("models/orders.sql", "select 1 as id")
    add("tests/check_orders.sql", "select * from {{ ref('orders') }}")
    add("models/schema.yml", "models: [{name: orders, columns: [{name: id, data_tests: [unique]}]}]\n")
    add("dbt_packages/pkg/dbt_project.yml", "name: pkg\nconfig-version: 2\n")
    add("dbt_packages/pkg/tests/package_check.sql", "select 1")
    val panel = new DbtTestPanel(getProject)
    Disposer.register(getTestRootDisposable, panel); panel.refresh()
    await("Source and package tests discovered")(panel.testDefinitions.size == 3 && panel.testView != null)
    assertFalse("Discovery must not show execution progress", panel.testView.getResultsViewer.getStatusLine.isVisible)
    assertEquals(3, panel.visibleDefinitions.size)
    combo(panel, "dbt.tests.packages").setSelectedItem("Project-owned")
    assertEquals(2, panel.visibleDefinitions.size)
    combo(panel, "dbt.tests.packages").setSelectedItem("Dependencies")
    assertEquals(Vector("pkg"), panel.visibleDefinitions.map(_.packageName).distinct)
    assertEquals(3, panel.testDefinitions.size)
    combo(panel, "dbt.tests.packages").setSelectedItem("All packages")
    val proxy = panel.testView.getResultsViewer.getTestsRootNode.getAllTests.asScala.find(_.getName == "analytics.check_orders").get
    panel.testView.getResultsViewer.selectAndNotify(proxy)
    val details = children(panel).collectFirst { case a: JTextArea if a.getName == "dbt.tests.details" => a }.get
    await("Selection populates structured details")(details.getText.contains("test.analytics.check_orders"))
    assertTrue(details.getText.contains("No matching result"))
    assertFalse("Missing evidence must not claim the test has never run", details.getText.contains("Not run /"))
    assertTrue(details.getText.contains("Owning resource: model.analytics.orders"))
    capture(panel, "tests")
    val search = children(panel).collectFirst { case t: JTextField if t.getName == "dbt.tests.search" => t }.get
    search.setText("package_check")
    assertEquals(1, panel.visibleDefinitions.size)
    assertEquals("Project", combo(panel, "dbt.tests.scope").getSelectedItem)
    search.setText("missing")
    assertTrue(panel.visibleDefinitions.isEmpty)
    assertTrue(details.getText.contains("No tests match"))
    assertFalse(getProject.getService(classOf[DbtRunner]).isRunning)
  }
  def testExactTestScopesRejectUnresolvedAndCrossProjectSelectionsWithoutBroadening(): Unit = {
    project(); val file = add("tests/a.sql", "select 1").getVirtualFile
    val root = DbtWorkspace.get(getProject).roots.head
    val base = DbtCommand.create(DbtWorkspace.get(getProject), DbtRunSelection.all(DbtWorkspace.get(getProject), root.directory.getPath).get,
      "Test", fullRefresh = false).toOption.get
    val tests = Vector("a", "b").map(n => DbtTestDefinition("test.analytics." + n, n, "Data tests", "orders", root.directory.getPath,
      file, 0, "fqn:analytics." + n, Vector("model.analytics.orders")))
    val configured = base.copy(request = base.request.copy(scope = DbtCommandScope.Named("old"), exclude = Vector("old")))
    val exact = DbtTestDiscovery.select(configured, tests).toOption.get
    assertEquals(DbtCommandScope.Selected(Vector("fqn:analytics.a", "fqn:analytics.b")), exact.request.scope)
    assertEquals(Some("empty"), exact.request.indirectSelection)
    assertTrue(exact.request.exclude.isEmpty)
    assertEquals(base.target, exact.target); assertEquals(base.environment, exact.environment)
    assertTrue(DbtTestDiscovery.select(base, Vector.empty).isLeft)
    assertTrue(DbtTestDiscovery.select(base, Vector(tests.head.copy(selector = ""))).isLeft)
    assertTrue(DbtTestDiscovery.select(base, Vector(tests.head.copy(root = "/other"))).isLeft)
    assertFalse(getProject.getService(classOf[DbtRunner]).isRunning)
  }

  def testRunnerProjectChoiceRestoresItsTargetAndSelectorWithoutFollowingInspection(): Unit = {
    project(); add("models/orders.sql", "select 1")
    add("other/dbt_project.yml", "name: other\nconfig-version: 2\n")
    add("other/models/report.sql", "select 2")
    val w = DbtWorkspace.get(getProject)
    val analytics = w.roots.find(_.name == "analytics").get
    val other = w.roots.find(_.name == "other").get
    analytics.runOptions.target = "dev"; analytics.runOptions.selection = "orders"
    other.runOptions.target = "qa"; other.runOptions.selection = "report"
    DbtProjectSettings.get(getProject).getState.runnerRoot = analytics.directory.getPath
    val runner = new DbtRunner(getProject)
    Disposer.register(getTestRootDisposable, runner)
    com.intellij.testFramework.ServiceContainerUtil.replaceService(getProject, classOf[DbtRunner], runner, getTestRootDisposable)
    val panel = new DbtToolWindowPanel(getProject)
    Disposer.register(getTestRootDisposable, panel); panel.refresh(follow = false)
    val projectChoice = combo(panel.runnerPanel, "dbt.project")
    val target = combo(panel.runnerPanel, "dbt.target")
    val selector = children(panel.runnerPanel).collectFirst { case f: JTextField if f.getName == "dbt.selection" => f }.get
    await("Initial project selection restored")(selector.getText == "orders")
    projectChoice.setSelectedItem(other.directory.getPath)
    await("Switching execution project restores its options")(selector.getText == "report" && target.getEditor.getItem == "qa")
    panel.handleMessage("""{"type":"filter","id":"orders","value":""}""")
    assertEquals("report", selector.getText)
    assertEquals("orders", analytics.runOptions.selection)
    projectChoice.setSelectedItem(analytics.directory.getPath)
    await("Switching back preserves previous input")(selector.getText == "orders" && target.getEditor.getItem == "dev")
    assertFalse(getProject.getService(classOf[DbtRunner]).isRunning)
  }
}
