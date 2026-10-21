package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project._
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.command.WriteCommandAction
import org.junit.Assert._

import java.nio.file.Files
import scala.jdk.CollectionConverters._

/** dbt commands built from a chosen resource, directory or project, including configured wrapper executables. */
final class DbtRunSelectionTest extends DbtTestCase {
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
    val projectDirectory = DbtRunSelection.available(w).find(_.path == root).get
    for (selection <- Vector(all, projectDirectory); action <- Vector("Run", "Build", "Test", "Compile", "Generate Docs")) {
      val command = DbtCommand.create(w, selection, action, fullRefresh = true).toOption.get
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

  def testPackageRootRemainsScopedWhenItsConsumerIsTheExecutionProject(): Unit = {
    project()
    add("models/local.sql", "select 1")
    val config = add("dbt_packages/util/dbt_project.yml", "name: util\nconfig-version: 2\n")
    add("dbt_packages/util/models/staging/orders.sql", "select 2")
    val w = workspace
    val chosen = DbtRunSelection.available(w).find(_.path == config.getVirtualFile.getParent.getPath).get
    val command = DbtCommand.create(w, chosen, "Compile", fullRefresh = false).toOption.get
    assertTrue(command.arguments.contains("--select"))
    assertTrue(command.arguments.contains("fqn:util.staging.orders"))
    assertFalse(command.arguments.contains("fqn:analytics.local"))
    assertEquals(w.roots.find(_.name == "analytics").get.directory.getPath, command.directory)
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


  private def setupWrapper(): DbtRoot = {
    project("profile: analytics\n")
    add("profiles.yml", "analytics: {target: dev, outputs: {dev: {type: duckdb}}}\n")
    add("models/orders.sql", "select 1")
    val root = workspace.roots.head
    root.runOptions.executable = "scripts/dbt-wrapper"
    root.runOptions.executableArguments = "analytics"
    root
  }

  def testEveryActionPassesConfiguredArgumentsBeforeTheDbtCommand(): Unit = {
    val root = setupWrapper()
    val file = root.psiFile("models/orders.sql").get
    DbtCommand.Actions.foreach { action =>
      val selectedPath = if (action == "Source Freshness") root.directory.getPath else file.getVirtualFile.getPath
      val command = DbtCommand.create(workspace, DbtRunSelection.available(workspace).find(_.path == selectedPath).get, action, fullRefresh = false).toOption.get
      val subcommand = if (action == "Generate Docs") "docs" else if (action == "Preview") "show" else if (action == "Source Freshness") "source" else action.toLowerCase
      assertEquals(Vector(root.executionDirectory.resolve("scripts/dbt-wrapper").toString, "analytics", subcommand), command.arguments.take(3))
      assertTrue(command.arguments.sliding(2).exists(_ == Vector("--target", "dev")))
      if (action == "Generate Docs") assertEquals("generate", command.arguments(3))
      else if (action == "Source Freshness") { assertEquals("freshness", command.arguments(3)); assertFalse(command.arguments.contains("--select")) }
      else assertTrue(command.arguments.sliding(2).exists(_ == Vector("--select", "fqn:analytics.orders")))
      assertEquals(root.directory.getPath, command.directory)
    }
  }

  def testRootsKeepIndependentArgumentsAndDirectorySelection(): Unit = {
    val root = setupWrapper()
    add("other/dbt_project.yml", "name: marketing\nconfig-version: 2\n")
    val file = add("other/models/orders.sql", "select 1")
    val other = workspace.root(file).get
    other.runOptions.executable = "custom-dbt"
    other.runOptions.executableArguments = "'marketing data'"
    val selected = DbtRunSelection.available(workspace).find(_.path == other.directory.getPath + "/models").get
    val command = DbtCommand.create(workspace, selected, "Run", fullRefresh = false).toOption.get
    assertEquals(Vector("custom-dbt", "marketing data", "run"), command.arguments.take(3))
    assertTrue(command.arguments.sliding(2).exists(_ == Vector("--select", "fqn:marketing.orders")))
    assertEquals(other.directory.getPath, command.directory)
    assertEquals("analytics", root.runOptions.executableArguments)
  }

  def testProjectRootDoesNotAddSelectorsToTheWrapperCommand(): Unit = {
    val root = setupWrapper()
    add("dbt_packages/util/dbt_project.yml", "name: util\nconfig-version: 2\n")
    add("dbt_packages/util/models/example.sql", "select 2")
    val selected = DbtRunSelection.available(workspace).find(_.path == root.directory.getPath).get
    val command = DbtCommand.create(workspace, selected, "Compile", fullRefresh = false).toOption.get
    assertEquals(Vector(root.executionDirectory.resolve("scripts/dbt-wrapper").toString,
      "analytics", "compile", "--target", "dev", "--use-colors"), command.arguments)
  }

  def testWrapperFindsLocalDbtAndPreservesQuotedArgumentsWithoutAShell(): Unit = {
    val root = setupWrapper()
    val directory = tempDirectory("dbt wrapper with spaces ")
    Files.writeString(directory.resolve("dbt_project.yml"), "name: analytics\nconfig-version: 2\n")
    val bin = Files.createDirectories(directory.resolve(".venv/bin"))
    fakeTool(bin.resolve("dbt"), "#!/bin/sh\nprintf '%s\\n' \"$@\"\n")
    val wrapper = fakeTool(directory.resolve("runner script"), "#!/bin/sh\nexec dbt \"$@\"\n")
    root.runOptions.projectRoot = directory.toString
    root.runOptions.executable = "./runner script"
    root.runOptions.executableArguments = "\"a project\" '$(echo untouched)'"
    val command = DbtCommand.create(workspace, DbtRunSelection.available(workspace).find(_.path == root.directory.getPath).get, "Generate Docs", fullRefresh = false).toOption.get
    assertEquals(Vector(wrapper.toString, "a project", "$(echo untouched)", "docs", "generate"), command.arguments.take(5))
    assertTrue(command.environment("PATH").startsWith(bin.toString + java.io.File.pathSeparator))
    val process = new GeneralCommandLine(command.arguments.asJava).withWorkDirectory(command.directory)
      .withEnvironment(command.environment.asJava).createProcess()
    val output = new String(process.getInputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
    assertEquals(0, process.waitFor())
    assertEquals(command.arguments.drop(1), output.linesIterator.toVector)
  }
}
