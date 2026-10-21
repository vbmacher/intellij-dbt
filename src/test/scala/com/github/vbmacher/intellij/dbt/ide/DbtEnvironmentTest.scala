package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project._
import com.google.gson.JsonParser
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.{IdeaTestFixtureFactory, TempDirTestFixture}
import org.junit.Assert._

import java.awt.Container
import java.nio.file.{Files, Paths}
import javax.swing.{JComboBox, JScrollPane, JTabbedPane}

/** Contract: features/configuration.md and external-command.md: configured environment reaches child tools. */
final class DbtEnvironmentTest extends DbtTestCase {
  override protected def createTempDirTestFixture(): TempDirTestFixture = IdeaTestFixtureFactory.getFixtureFactory.createTempDirTestFixture()

  private def setupRoot(): DbtRoot = {
    val config = project("profile: analytics\n")
    PsiTestUtil.addContentRoot(getModule, config.getVirtualFile.getParent)
    add("profiles.yml", "analytics: {target: dev, outputs: {dev: {type: athena}}}\n")
    add("models/orders.sql", "select 1")
    workspace.roots.head
  }

  private def command(root: DbtRoot, action: String = "Run"): DbtCommand = {
    val result = if (action == "Resolve Selection") DbtCommand.resolveSelection(workspace, root, None)
    else {
      val selection = if (action == "Preview") DbtRunSelection.available(workspace).find(_.path == root.directory.getPath + "/models/orders.sql").get
      else DbtRunSelection.all(workspace, root.directory.getPath).get
      DbtCommand.create(workspace, selection, action, fullRefresh = false)
    }
    assertTrue(action + ": " + result, result.isRight)
    result.toOption.get
  }

  def testDotenvSyntaxKeepsValuesLiteralAndIgnoresInvalidAssignments(): Unit = {
    val text = "\ufeff# comment\r\n" +
      """ export ATHENA_WORK_GROUP = dev # comment
EMPTY=
COMMENT= # empty
SINGLE=' spaces # = $HOME\n ' # comment
DOUBLE="line\nnext\t\"quoted\"\\path" # comment
HASH=abc#def
EQUALS=a=b=c
LITERAL=${HOME}/$(touch sentinel)
REPEATED=first
REPEATED=last
NO_VALUE
9INVALID=value
BROKEN="unterminated
"""
    assertEquals(Map("ATHENA_WORK_GROUP" -> "dev", "EMPTY" -> "", "COMMENT" -> "",
      "SINGLE" -> " spaces # = $HOME\\n ", "DOUBLE" -> "line\nnext\t\"quoted\"\\path",
      "HASH" -> "abc#def", "EQUALS" -> "a=b=c", "LITERAL" -> "${HOME}/$(touch sentinel)",
      "REPEATED" -> "last"), DbtEnvironment.parse(text))
  }

  def testFilesAndIntelliJOverridesComposeForEveryCommandAndFormatter(): Unit = {
    val root = setupRoot()
    configureSettings { o =>
      o.environmentFiles = ".env; .env.local; missing.env"
      o.variables = "{\"start_date\":\"2026-03-01\",\"nested\":{\"list\":[1,true,null]}}"
      Map("ATHENA_WORK_GROUP" -> "from-settings", "NUMBER" -> "123", "FLAG" -> "false",
        "RUN_EMPTY" -> "", "DBT_PROFILES_DIR" -> "profiles").foreach { case (name, value) => o.environmentVariables.put(name, value) }
    }
    add(".env", "ATHENA_WORK_GROUP=base\nBASE_ONLY=base\nEMPTY=value\nPATH=/configured/bin\nDBT_TARGET_PATH=ignored\n")
    val local = add(".env.local", "ATHENA_WORK_GROUP=local\nEMPTY=\nLOCAL_ONLY=local\n")
    add("profiles/profiles.yml", "analytics: {target: athena, outputs: {athena: {type: athena}}}\n")
    add(".venv/bin/dbt", "")
    for (action <- Vector("Run", "Build", "Compile", "Test", "Preview", "Generate Docs", "Source Freshness", "Resolve Selection")) {
      val cmd = command(root, action)
      val index = cmd.arguments.indexOf("--vars")
      assertTrue(action + " includes configured variables", index >= 0)
      assertEquals(JsonParser.parseString(root.runOptions.variables), JsonParser.parseString(cmd.arguments(index + 1)))
      val env = cmd.environment
      assertEquals("from-settings", env("ATHENA_WORK_GROUP"))
      assertEquals("base", env("BASE_ONLY"))
      assertEquals("local", env("LOCAL_ONLY"))
      assertEquals("", env("EMPTY"))
      assertEquals("", env("RUN_EMPTY"))
      assertEquals("123", env("NUMBER"))
      assertEquals("false", env("FLAG"))
      assertEquals(root.executionDirectory.resolve(".venv/bin").toString + java.io.File.pathSeparator + "/configured/bin", env("PATH"))
      assertEquals(root.executionDirectory.resolve("profiles").toString, env("DBT_PROFILES_DIR"))
      assertEquals(root.artifactDirectory.toString, env("DBT_TARGET_PATH"))
      assertEquals("athena", command(root, action).target)
    }
    root.runOptions.formatterExecutable = "sqlfmt"
    assertEquals("from-settings", DbtToolCommand.create(root.psiFile("models/orders.sql").get).toOption.get.environment("ATHENA_WORK_GROUP"))
    edit(local, "LOCAL_ONLY=changed\n")
    assertEquals("changed", command(root).environment("LOCAL_ONLY"))
    root.runOptions.profilesDirectory = root.directory.getPath
    assertEquals("dev", command(root).target)
  }

  def testEnvironmentSettingsKeepRootsAndSavedPreferencesIndependent(): Unit = {
    val root = setupRoot()
    configureSettings(_.environmentFiles = "configured.env")
    add("configured.env", "ATHENA_WORK_GROUP=configured\n")
    add("custom env", "ATHENA_WORK_GROUP=custom\n")
    val last = add("last.env", "ATHENA_WORK_GROUP=last\n")
    add("other/dbt_project.yml", "name: other\nconfig-version: 2\n")
    add("other/.env", "ATHENA_WORK_GROUP=other\n")
    val other = workspace.roots.find(_.name == "other").get
    assertEquals("configured", command(root).environment("ATHENA_WORK_GROUP"))
    val configurable = new DbtSettingsConfigurable(getProject)
    val tabs = configurable.createComponent().asInstanceOf[JTabbedPane]
    val form = tabs.getComponentAt(0).asInstanceOf[JScrollPane].getViewport.getView.asInstanceOf[Container]
    try {
      val roots = form.getComponents.collectFirst { case combo: JComboBox[_] => combo.asInstanceOf[JComboBox[String]] }.get
      val field = form.getComponents.collectFirst { case field: TextFieldWithBrowseButton if field.getName == "dbt.environmentFiles" => field }.get
      roots.setSelectedItem(root.directory.getPath)
      assertEquals("configured.env", field.getText)
      field.setText("custom env; " + last.getVirtualFile.getPath)
      assertTrue(configurable.isModified)
      roots.setSelectedItem(other.directory.getPath)
      assertEquals("", field.getText)
      roots.setSelectedItem(root.directory.getPath)
      assertEquals("custom env; " + last.getVirtualFile.getPath, field.getText)
      configurable.apply()
      assertFalse(configurable.isModified)
      field.setText("discarded.env")
      configurable.reset()
      roots.setSelectedItem(root.directory.getPath)
      assertEquals("custom env; " + last.getVirtualFile.getPath, field.getText)
      assertEquals("last", command(root).environment("ATHENA_WORK_GROUP"))
      assertEquals("other", command(other).environment("ATHENA_WORK_GROUP"))
      val xml = com.intellij.util.xmlb.XmlSerializer.serialize(DbtProjectSettings.get(getProject).getState)
      val restored = com.intellij.util.xmlb.XmlSerializer.deserialize(xml, classOf[DbtSettingsState])
      assertEquals(field.getText, restored.projects.get(root.directory.getPath).environmentFiles)
      assertEquals("", restored.projects.get(other.directory.getPath).environmentFiles)
    } finally configurable.disposeUIResources()
  }

  def testManagedProcessReceivesDefaultDotenvWithoutLoggingItsValues(): Unit = {
    val root = setupRoot()
    add(".env", "ATHENA_WORK_GROUP=private-workgroup\n")
    val executable = add("fake-dbt",
      """#!/usr/bin/python3
import json, os
with open('environment.json', 'w') as f:
    json.dump({'workgroup': os.environ.get('ATHENA_WORK_GROUP'), 'home': os.environ.get('HOME')}, f)
""").getVirtualFile
    assertTrue(Paths.get(executable.getPath).toFile.setExecutable(true))
    root.runOptions.executable = executable.getPath
    root.runOptions.notifications = false
    val runner = newRunner()
    val output = new StringBuffer
    assertTrue(runner.start(command(root), (text, _) => {
      output.append(text);
      ()
    }))
    await("dbt completes with configured environment")(!runner.isRunning)
    val invocation = JsonParser.parseString(Files.readString(root.executionDirectory.resolve("environment.json"))).getAsJsonObject
    assertEquals("private-workgroup", invocation.get("workgroup").getAsString)
    assertFalse(invocation.get("home").isJsonNull)
    assertFalse(output.toString.contains("private-workgroup"))
  }

  def testExecutionDirectoryDoesNotChangeEnvironmentOwnerAndEmptyFileListDisablesLoading(): Unit = {
    val root = setupRoot()
    add(".env", "ATHENA_WORK_GROUP=owner\n")
    val config = add("execution/dbt_project.yml", "name: execution\nconfig-version: 2\n")
    add("execution/.env", "ATHENA_WORK_GROUP=execution\n")
    root.runOptions.projectRoot = config.getVirtualFile.getParent.getPath
    assertEquals("owner", command(root).environment("ATHENA_WORK_GROUP"))
    configureSettings(_.loadEnvironmentFiles = false)
    assertFalse(command(root).environment.contains("ATHENA_WORK_GROUP"))
    configureSettings { o => o.loadEnvironmentFiles = true; o.environmentFiles = "missing.env" }
    assertFalse(command(root).environment.contains("ATHENA_WORK_GROUP"))
  }
}
