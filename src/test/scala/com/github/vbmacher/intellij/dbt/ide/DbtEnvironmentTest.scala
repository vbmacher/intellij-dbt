package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project._
import com.google.gson.JsonParser
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.{IdeaTestFixtureFactory, TempDirTestFixture}
import org.junit.Assert._

import java.awt.Container
import java.nio.file.{Files, Paths}
import javax.swing.{JComboBox, JScrollPane, JTabbedPane, JTextArea, JTextField, JTable, JCheckBox}

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
    val form = components(tabs.getComponentAt(0)).collectFirst { case scroll: JScrollPane => scroll }.get.getViewport.getView.asInstanceOf[Container]
    try {
      val roots = components(tabs).collectFirst { case combo: JComboBox[_] if combo.getName == "dbt.root" => combo.asInstanceOf[JComboBox[String]] }.get
      val field = components(form).collectFirst { case field: TextFieldWithBrowseButton if field.getName == "dbt.environmentFiles" => field }.get
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

  def testSettingsResolveDraftContextWithTheSameRulesAsCommandPreparation(): Unit = {
    val owner = setupRoot()
    add(".env", "DBT_PROFILES_DIR=dotenv-profiles\nTOKEN=dotenv-secret\n")
    add("run/dbt_project.yml", "name: command_project\nprofile: analytics\n")
    add("run/.env", "DBT_PROFILES_DIR=wrong-directory\n")
    add("run/dotenv-profiles/profiles.yml", "analytics: {target: dotenv-default}\n")
    add("run/settings-profiles/profiles.yml", "analytics: {target: settings-default}\n")
    add("run/explicit-profiles/profiles.yml", "analytics: {target: explicit-default}\n")
    add("run/.venv/bin/dbt", "")
    val other = workspace.roots.find(_.directory.getPath.endsWith("/run")).get
    val settings = DbtProjectSettings.get(getProject)
    val configurable = new DbtSettingsConfigurable(getProject)
    val tabs = configurable.createComponent().asInstanceOf[JTabbedPane]
    val fields = components(tabs)
    def field(name: String) = fields.collectFirst { case c: javax.swing.JComponent if c.getName == "dbt." + name => c }.get
    def browse(name: String) = field(name).asInstanceOf[TextFieldWithBrowseButton]
    val roots = field("root").asInstanceOf[JComboBox[String]]
    val summary = field("executionContext").asInstanceOf[JTextArea]
    val environment = field("environmentVariables").asInstanceOf[JTable].getModel.asInstanceOf[javax.swing.table.DefaultTableModel]
    val own = owner.directory.getPath
    try {
      roots.setSelectedItem(own)
      assertFalse(configurable.isModified)
      browse("projectRoot").setText("run")
      field("artifacts").asInstanceOf[JTextField].setText("build-output")
      val commandDirectory = owner.executionDirectory.resolve("run").toString
      await("Draft resolves owning-root dotenv against the command directory")(summary.getText.contains("Target: dotenv-default (profile default)"))
      assertTrue(summary.getText, summary.getText.contains(commandDirectory + "/.venv/bin/dbt"))
      assertTrue(summary.getText.contains("Environment files: " + own + "/.env"))
      environment.addRow(Array[AnyRef]("DBT_PROFILES_DIR", "settings-profiles"))
      environment.addRow(Array[AnyRef]("TOKEN", "settings-secret"))
      field("variables").asInstanceOf[JTextArea].setText("{\"token\":\"vars-secret\"}")
      fields.collectFirst { case text: JTextField if Option(text.getToolTipText).exists(_.startsWith("Passed before every")) => text }.get
        .setText("run dbt --token prefix-secret")
      await("Settings environment overrides dotenv without publishing draft values")(summary.getText.contains("Target: settings-default (profile default)"))
      val options = owner.runOptions.copy()
      options.projectRoot = "run"
      options.artifactDirectory = "build-output"
      options.environmentVariables = new java.util.TreeMap[String, String]()
      options.environmentVariables.put("DBT_PROFILES_DIR", "settings-profiles")
      val selection = DbtRunSelection.all(workspace, own).get
      val command = DbtCommand.create(workspace, selection, "Compile", fullRefresh = false, options = Some(options)).toOption.get
      assertTrue(summary.getText.contains("Command directory: " + command.directory))
      assertTrue(summary.getText.contains("Executable: " + command.prefix.head))
      assertTrue(summary.getText.contains("Profiles file for commands: " + command.environment("DBT_PROFILES_DIR") + "/profiles.yml"))
      assertTrue(summary.getText.contains("Artifact directory: " + command.artifactDirectory))
      assertEquals("Default: settings-default", field("target").asInstanceOf[com.intellij.ui.components.JBTextField].getEmptyText.getText)
      Vector("dotenv-secret", "settings-secret", "vars-secret", "prefix-secret").foreach(secret => assertFalse(summary.getText.contains(secret)))
      assertEquals("", settings.options(own).projectRoot)
      assertEquals("target", settings.options(own).artifactDirectory)
      assertTrue(settings.options(own).environmentVariables.isEmpty)
      val environmentTable = field("environmentVariables").asInstanceOf[JTable]
      assertTrue(environmentTable.editCellAt(0, 1))
      environmentTable.getEditorComponent.asInstanceOf[JTextField].setText("explicit-profiles")
      await("Context follows the active cell without committing it")(summary.getText.contains("Target: explicit-default (profile default)"))
      assertTrue(environmentTable.isEditing)
      environmentTable.getCellEditor.cancelCellEditing()
      await("Canceling a cell edit restores the previous context")(summary.getText.contains("Target: settings-default (profile default)"))
      browse("profiles").setText("explicit-profiles")
      await("Explicit profiles directory overrides environment defaults")(summary.getText.contains("Target: explicit-default (profile default)"))
      field("target").asInstanceOf[JTextField].setText("ci")
      await("Explicit target is distinguished from profile defaults")(summary.getText.contains("Target: ci (settings override)"))
      roots.setSelectedItem(other.directory.getPath)
      assertFalse(summary.getText.contains("settings-default"))
      assertTrue(summary.getText.contains("Command directory: " + other.directory.getPath))
      roots.setSelectedItem(own)
      assertTrue(summary.getText.contains("Target: ci (settings override)"))
      field("loadEnvironmentFiles").asInstanceOf[JCheckBox].doClick()
      await("Disabled environment loading is explicit")(summary.getText.contains("Environment files: Disabled"))
      assertTrue(summary.getText.contains("Target: ci (settings override)"))
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
