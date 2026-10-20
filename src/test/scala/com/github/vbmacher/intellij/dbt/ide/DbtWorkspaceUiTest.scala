package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project._
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.openapi.util.Disposer
import org.jdom.Element
import org.junit.Assert._

/** Native Run configurations replace the custom Runner form. */
final class DbtWorkspaceUiTest extends DbtCodeInsightTestCase {
  override protected def tearDown(): Unit = try DbtProjectSettings.get(getProject).loadState(new DbtSettingsState) finally super.tearDown()
  private def configuration(): DbtRunConfiguration = {
    val factory = ConfigurationTypeUtil.findConfigurationType(classOf[DbtConfigurationType]).getConfigurationFactories.head
    val value = new DbtRunConfiguration(getProject, factory, "dbt command")
    value.root = DbtWorkspace.get(getProject).roots.head.directory.getPath
    value
  }
  def testEveryCommandPreparesWithoutChangingProjectDefaultsAndRoundTripsOverrides(): Unit = {
    project("profile: analytics\n")
    add("profiles.yml", "analytics: {target: dev, outputs: {dev: {type: postgres}, prod: {type: postgres}}}\n")
    val model = add("models/orders.sql", "{{ config(materialized='incremental') }} select 1 as id")
    val w = DbtWorkspace.get(getProject); val root = w.roots.head
    root.runOptions.target = "dev"
    val config = configuration()
    config.target = "prod"; config.executable = "/usr/bin/python3"; config.executableArguments = "'wrapper with spaces.py'"
    for (action <- DbtCommand.Actions :+ "Resolve Selection") {
      config.action = action
      config.scope = if (action == "Preview") model.getVirtualFile.getPath else ""
      config.checkConfiguration()
      val command = config.prepare().fold(error => fail(action + ": " + error).asInstanceOf[DbtCommand], _._1)
      assertEquals(action, command.action); assertEquals("prod", command.target)
      assertEquals(Vector("/usr/bin/python3", "wrapper with spaces.py"), command.prefix)
      assertEquals("dev", root.runOptions.target)
      assertEquals("dbt", root.runOptions.executable)
    }
    config.action = "Build"; config.scope = model.getVirtualFile.getPath; config.fullRefresh = true
    val xml = new Element("configuration"); config.writeExternal(xml)
    val restored = configuration(); restored.readExternal(xml)
    assertEquals(config.scope, restored.scope); assertEquals(config.target, restored.target)
    assertEquals(config.executableArguments, restored.executableArguments)
    assertTrue(restored.prepare().toOption.get._1.request.fullRefresh)
  }
  def testStoredConfigurationsHaveIndependentScopesAndGraphFilteringCannotRetargetThem(): Unit = {
    project(); val one = add("models/one.sql", "select 1"); val two = add("models/two.sql", "select 2")
    val first = configuration(); first.action = "Run"; first.scope = one.getVirtualFile.getPath
    val second = configuration(); second.action = "Compile"; second.scope = two.getVirtualFile.getPath
    val before = first.prepare().toOption.get._1
    val panel = new DbtToolWindowPanel(getProject); Disposer.register(getTestRootDisposable, panel)
    panel.handleMessage("""{"type":"filter","id":"two","value":""}""")
    assertEquals(before.arguments, first.prepare().toOption.get._1.arguments)
    assertTrue(second.prepare().toOption.get._1.arguments.contains("fqn:analytics.two"))
    assertFalse(second.prepare().toOption.get._1.arguments.contains("fqn:analytics.one"))
  }
  def testCliSelectionBecomesUnusableAfterSourceOrExecutionSettingsChange(): Unit = {
    project(); val file = add("models/orders.sql", "select 1")
    val config = configuration(); config.action = "Run"; config.selectors = Vector("runtime:unknown")
    assertTrue(config.prepare().isLeft)
    config.cliSelection = Some(config.sourceKey(DbtWorkspace.get(getProject)) -> Vector("model.analytics.orders"))
    assertTrue(config.prepare().isRight)
    edit(file, "select 2")
    assertTrue("Stale CLI resolution cannot execute", config.prepare().isLeft)
    config.cliSelection = Some(config.sourceKey(DbtWorkspace.get(getProject)) -> Vector("model.analytics.orders"))
    config.target = "prod"
    assertTrue(config.prepare().isLeft)
    config.cliSelection = Some(config.sourceKey(DbtWorkspace.get(getProject)) -> Vector("missing.resource"))
    assertTrue("Unknown CLI identities must never widen execution", config.prepare().isLeft)
  }
  def testPreviewRejectsFoldersAndFreshnessRemainsProjectWide(): Unit = {
    project(); val model = add("models/orders.sql", "select 1")
    val config = configuration(); config.action = "Preview"
    assertTrue(config.prepare().isLeft)
    config.scope = model.getVirtualFile.getPath
    assertTrue(config.prepare().isRight)
    config.action = "Source Freshness"
    assertTrue(config.prepare().isLeft)
    config.scope = ""
    assertEquals(DbtCommandScope.All, config.prepare().toOption.get._1.request.scope)
  }
}
