package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project.{DbtProjectSettings, DbtSettingsState, DbtWorkspace}
import com.intellij.openapi.command.WriteCommandAction
import org.junit.Assert._

final class DbtRunSelectionTest extends DbtCodeInsightTestCase {
  override protected def tearDown(): Unit = try DbtProjectSettings.get(getProject).loadState(new DbtSettingsState) finally super.tearDown()
  private def workspace = DbtWorkspace.get(getProject)

  def testPickerIncludesModelsAndDirectoriesAndExcludesDisabledAndGeneratedModels(): Unit = {
    project()
    val orders = add("models/sales/nested/orders.sql", "select 1")
    add("models/disabled.sql", "{{ config(enabled=false) }} select 1")
    add("target/generated.sql", "select 2")
    add("other/dbt_project.yml", "name: marketing\nconfig-version: 2\n")
    val other = add("other/models/orders.sql", "select 3")
    val options = DbtRunSelection.available(workspace)
    assertTrue(options.exists(s => !s.directory && s.path == orders.getVirtualFile.getPath))
    assertTrue(options.exists(s => !s.directory && s.path == other.getVirtualFile.getPath))
    val root = workspace.roots.find(_.name == "analytics").get.directory.getPath
    for (path <- Vector("", "/models", "/models/sales", "/models/sales/nested"))
      assertTrue(path, options.exists(s => s.directory && s.path == root + path))
    assertFalse(options.exists(s => s.path.endsWith("disabled.sql") || s.path.contains("/target")))
  }

  def testDirectoryCommandsUseTheChosenPathProjectTargetAndIncrementalScope(): Unit = {
    project("profile: analytics\n")
    add("profiles.yml", "analytics: {target: dev, outputs: {dev: {type: duckdb}}}\n")
    val chosen = add("models/sales/orders.sql", "{{ config(materialized='incremental') }} select 1")
    add("models/sales/nested/detail.sql", "select 2")
    add("models/sales_archive/old.sql", "select 3")
    val w = workspace
    val folder = DbtRunSelection.available(w).find(_.path == chosen.getVirtualFile.getParent.getPath).get
    assertEquals(Set("orders", "detail"), DbtRunSelection.selectedModels(w, folder).map(_.name).toSet)
    for (action <- Vector("Run", "Build", "Test", "Compile")) {
      val command = DbtCommand.create(w, folder, action, fullRefresh = true).toOption.get
      assertEquals(w.roots.head.directory.getPath, command.directory)
      assertEquals("dev", command.target)
      assertTrue(command.arguments.contains("--select"))
      assertTrue(command.arguments.contains("fqn:analytics.sales.orders"))
      assertTrue(command.arguments.contains("fqn:analytics.sales.nested.detail"))
      assertFalse(command.arguments.contains("fqn:analytics.sales_archive.old"))
      assertEquals(Set("Run", "Build")(action), command.arguments.contains("--full-refresh"))
    }
    assertTrue(DbtCommand.create(w, folder, "Preview", fullRefresh = false).isLeft)
    val docs = DbtCommand.create(w, folder, "Generate Docs", fullRefresh = false).toOption.get
    assertFalse(docs.arguments.contains("--select"))
    assertEquals(Vector("docs", "generate"), docs.arguments.slice(1, 3))
  }

  def testDeletedOrForgedSelectionCannotLaunchACommand(): Unit = {
    project()
    val file = add("models/orders.sql", "select 1")
    val selected = DbtRunSelection.available(workspace).find(_.path == file.getVirtualFile.getPath).get
    WriteCommandAction.runWriteCommandAction(getProject, new Runnable {
      override def run(): Unit = file.getVirtualFile.delete(this)
    })
    assertTrue(DbtCommand.create(workspace, selected, "Run", fullRefresh = false).isLeft)
    assertTrue(DbtCommand.create(workspace, selected.copy(path = selected.root + "/../outside.sql"), "Run", fullRefresh = false).isLeft)
    assertTrue(DbtCommand.create(workspace, selected.copy(path = ""), "Run", fullRefresh = false).isLeft)
  }

  def testFullRefreshDoesNotIncludeIncrementalModelsOutsideTheChosenDirectory(): Unit = {
    project()
    val file = add("models/chosen/orders.sql", "select 1")
    add("models/elsewhere/other.sql", "{{ config(materialized='incremental') }} select 2")
    val w = workspace
    val selected = DbtRunSelection.available(w).find(_.path == file.getVirtualFile.getParent.getPath).get
    assertFalse(DbtCommand.create(w, selected, "Run", fullRefresh = true).toOption.get.arguments.contains("--full-refresh"))
  }

  def testAllModelsRunsWithoutASelectorAndKeepsTheProjectConfiguration(): Unit = {
    project("profile: analytics\n")
    add("profiles.yml", "analytics: {target: dev, outputs: {dev: {type: duckdb}}}\n")
    add("models/orders.sql", "{{ config(materialized='incremental') }} select 1")
    add("models/nested/detail.sql", "select 2")
    val w = workspace
    val root = w.roots.head.directory.getPath
    DbtProjectSettings.get(getProject).options(root).executableArguments = "wms"
    val all = DbtRunSelection.all(w, "").get
    assertEquals("", all.path)
    assertEquals(Set("orders", "detail"), DbtRunSelection.selectedModels(w, all).map(_.name).toSet)
    for (action <- Vector("Run", "Build", "Test", "Compile", "Generate Docs")) {
      val command = DbtCommand.create(w, all, action, fullRefresh = true).toOption.get
      assertEquals(root, command.directory)
      assertEquals("dev", command.target)
      assertEquals("wms", command.arguments(1))
      assertFalse(command.arguments.contains("--select"))
      assertFalse(command.arguments.exists(_.startsWith("fqn:")))
      assertEquals(Set("Run", "Build")(action), command.arguments.contains("--full-refresh"))
    }
    assertTrue(DbtCommand.create(w, all, "Preview", fullRefresh = false).isLeft)
    assertTrue(DbtCommand.create(w, all.copy(root = root + "/missing"), "Run", fullRefresh = false).isLeft)
  }

  def testAllModelsKeepsTheExplicitProjectInAMultiProjectWorkspace(): Unit = {
    project()
    add("models/orders.sql", "select 1")
    add("other/dbt_project.yml", "name: marketing\nconfig-version: 2\n")
    add("other/models/campaigns.sql", "select 2")
    val w = workspace
    val marketing = w.roots.find(_.name == "marketing").get
    val all = DbtRunSelection.all(w, marketing.directory.getPath).get
    assertEquals(Vector("campaigns"), DbtRunSelection.selectedModels(w, all).map(_.name))
    assertEquals(marketing.directory.getPath, DbtCommand.create(w, all, "Run", fullRefresh = false).toOption.get.directory)
  }
}
