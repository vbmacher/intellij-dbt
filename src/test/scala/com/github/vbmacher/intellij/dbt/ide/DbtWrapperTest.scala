package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project._
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.util.Disposer
import org.junit.Assert._
import java.nio.file.Files
import scala.jdk.CollectionConverters._

final class DbtWrapperTest extends DbtCodeInsightTestCase {
  override protected def tearDown(): Unit = try DbtProjectSettings.get(getProject).loadState(new DbtSettingsState) finally super.tearDown()

  private def workspace = DbtWorkspace.get(getProject)
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
      val command = DbtCommand.create(workspace, DbtRunSelection.available(workspace).find(_.path == file.getVirtualFile.getPath).get, action, fullRefresh = false).toOption.get
      val subcommand = if (action == "Generate Docs") "docs" else if (action == "Preview") "show" else action.toLowerCase
      assertEquals(Vector(root.executionDirectory.resolve("scripts/dbt-wrapper").toString, "analytics", subcommand), command.arguments.take(3))
      assertTrue(command.arguments.sliding(2).exists(_ == Vector("--target", "dev")))
      if (action == "Generate Docs") assertEquals("generate", command.arguments(3))
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
    val directory = Files.createTempDirectory("dbt wrapper with spaces ")
    Disposer.register(getTestRootDisposable, () => com.intellij.openapi.util.io.FileUtil.delete(directory.toFile))
    Files.writeString(directory.resolve("dbt_project.yml"), "name: analytics\nconfig-version: 2\n")
    val bin = Files.createDirectories(directory.resolve(".venv/bin"))
    val dbt = Files.writeString(bin.resolve("dbt"), "#!/bin/sh\nprintf '%s\\n' \"$@\"\n")
    dbt.toFile.setExecutable(true)
    val wrapper = Files.writeString(directory.resolve("runner script"), "#!/bin/sh\nexec dbt \"$@\"\n")
    wrapper.toFile.setExecutable(true)
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
