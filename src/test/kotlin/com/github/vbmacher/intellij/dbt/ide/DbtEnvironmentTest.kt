package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project.*
import com.google.gson.JsonParser
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.fixtures.TempDirTestFixture
import org.junit.Assert.*

import java.awt.Container
import java.nio.file.Files
import java.nio.file.Paths
import javax.swing.JComboBox
import javax.swing.JScrollPane
import javax.swing.JTabbedPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.JTable
import javax.swing.JCheckBox

/** Contract: features/configuration.md and external-command.md: configured environment reaches child tools. */
class DbtEnvironmentTest : DbtTestCase() {
    override fun createTempDirTestFixture(): TempDirTestFixture =
        IdeaTestFixtureFactory.getFixtureFactory().createTempDirTestFixture()

    private fun setupRoot(): DbtRoot {
        val config = project("profile: analytics\n")
        PsiTestUtil.addContentRoot(getModule(), config.virtualFile.parent)
        add("profiles.yml", "analytics: {target: dev, outputs: {dev: {type: athena}}}\n")
        add("models/orders.sql", "select 1")
        return workspace.roots.first()
    }

    private fun command(root: DbtRoot, action: String = "Run"): DbtCommand {
        val result = if (action == "Resolve Selection") DbtCommand.resolveSelection(workspace, root, null)
        else {
            val selection = if (action == "Preview") DbtRunSelection.available(workspace)
                .find { it.path == root.directory.path + "/models/orders.sql" }!!
            else DbtRunSelection.all(workspace, root.directory.path)!!
            DbtCommand.create(workspace, selection, action, fullRefresh = false)
        }
        assertTrue(action + ": " + result, result.isSuccess)
        return result.getOrThrow()
    }

    fun testDotenvSyntaxKeepsValuesLiteralAndIgnoresInvalidAssignments() {
        val text = "\ufeff# comment\r\n" +
                """ export ATHENA_WORK_GROUP = dev # comment
EMPTY=
COMMENT= # empty
SINGLE=' spaces # = ${'$'}HOME\n ' # comment
DOUBLE="line\nnext\t\"quoted\"\\path" # comment
HASH=abc#def
EQUALS=a=b=c
LITERAL=${'$'}{HOME}/${'$'}(touch sentinel)
REPEATED=first
REPEATED=last
NO_VALUE
9INVALID=value
BROKEN="unterminated
"""
        assertEquals(
            mapOf(
                "ATHENA_WORK_GROUP" to "dev", "EMPTY" to "", "COMMENT" to "",
                "SINGLE" to " spaces # = \$HOME\\n ", "DOUBLE" to "line\nnext\t\"quoted\"\\path",
                "HASH" to "abc#def", "EQUALS" to "a=b=c", "LITERAL" to "\${HOME}/\$(touch sentinel)",
                "REPEATED" to "last"
            ), DbtEnvironment.parse(text)
        )
    }

    fun testFilesAndIntelliJOverridesComposeForEveryCommandAndFormatter() {
        val root = setupRoot()
        configureSettings { o ->
            o.environmentFiles = ".env; .env.local; missing.env"
            o.variables = "{\"start_date\":\"2026-03-01\",\"nested\":{\"list\":[1,true,null]}}"
            mapOf(
                "ATHENA_WORK_GROUP" to "from-settings", "NUMBER" to "123", "FLAG" to "false",
                "RUN_EMPTY" to "", "DBT_PROFILES_DIR" to "profiles"
            ).forEach { (name, value) -> o.environmentVariables.put(name, value) }
        }
        add(
            ".env",
            "ATHENA_WORK_GROUP=base\nBASE_ONLY=base\nEMPTY=value\nPATH=/configured/bin\nDBT_TARGET_PATH=ignored\n"
        )
        val local = add(".env.local", "ATHENA_WORK_GROUP=local\nEMPTY=\nLOCAL_ONLY=local\n")
        add("profiles/profiles.yml", "analytics: {target: athena, outputs: {athena: {type: athena}}}\n")
        add(".venv/bin/dbt", "")
        for (action in listOf(
            "Run",
            "Build",
            "Compile",
            "Test",
            "Preview",
            "Generate Docs",
            "Source Freshness",
            "Resolve Selection"
        )) {
            val cmd = command(root, action)
            val index = cmd.arguments.indexOf("--vars")
            assertTrue(action + " includes configured variables", index >= 0)
            assertEquals(
                JsonParser.parseString(root.runOptions.variables),
                JsonParser.parseString(cmd.arguments[index + 1])
            )
            val env = cmd.environment
            assertEquals("from-settings", env.getValue("ATHENA_WORK_GROUP"))
            assertEquals("base", env.getValue("BASE_ONLY"))
            assertEquals("local", env.getValue("LOCAL_ONLY"))
            assertEquals("", env.getValue("EMPTY"))
            assertEquals("", env.getValue("RUN_EMPTY"))
            assertEquals("123", env.getValue("NUMBER"))
            assertEquals("false", env.getValue("FLAG"))
            assertEquals(
                root.executionDirectory.resolve(".venv/bin")
                    .toString() + java.io.File.pathSeparator + "/configured/bin", env.getValue("PATH")
            )
            assertEquals(root.executionDirectory.resolve("profiles").toString(), env.getValue("DBT_PROFILES_DIR"))
            assertEquals(root.artifactDirectory.toString(), env.getValue("DBT_TARGET_PATH"))
            assertEquals("athena", command(root, action).target)
        }
        root.runOptions.formatterExecutable = "sqlfmt"
        assertEquals(
            "from-settings",
            DbtToolCommand.create(root.psiFile("models/orders.sql")!!)
                .getOrThrow().environment.getValue("ATHENA_WORK_GROUP")
        )
        edit(local, "LOCAL_ONLY=changed\n")
        assertEquals("changed", command(root).environment.getValue("LOCAL_ONLY"))
        root.runOptions.profilesDirectory = root.directory.path
        assertEquals("dev", command(root).target)
    }

    fun testEnvironmentSettingsKeepRootsAndSavedPreferencesIndependent() {
        val root = setupRoot()
        configureSettings { it.environmentFiles = "configured.env" }
        add("configured.env", "ATHENA_WORK_GROUP=configured\n")
        add("custom env", "ATHENA_WORK_GROUP=custom\n")
        val last = add("last.env", "ATHENA_WORK_GROUP=last\n")
        add("other/dbt_project.yml", "name: other\nconfig-version: 2\n")
        add("other/.env", "ATHENA_WORK_GROUP=other\n")
        val other = workspace.roots.find { it.name == "other" }!!
        assertEquals("configured", command(root).environment.getValue("ATHENA_WORK_GROUP"))
        val configurable = DbtSettingsConfigurable(getProject())
        val tabs = configurable.createComponent() as JTabbedPane
        val form = components(tabs.getComponentAt(0)).filterIsInstance<JScrollPane>().first().viewport.view as Container
        try {
            val roots = components(tabs).filterIsInstance<JComboBox<*>>().first { it.name == "dbt.root" }
            val field = components(form).filterIsInstance<TextFieldWithBrowseButton>()
                .first { it.name == "dbt.environmentFiles" }
            roots.setSelectedItem(root.directory.path)
            assertEquals("configured.env", field.text)
            field.setText("custom env; " + last.virtualFile.path)
            assertTrue(configurable.isModified())
            roots.setSelectedItem(other.directory.path)
            assertEquals("", field.text)
            roots.setSelectedItem(root.directory.path)
            assertEquals("custom env; " + last.virtualFile.path, field.text)
            configurable.apply()
            assertFalse(configurable.isModified())
            field.setText("discarded.env")
            configurable.reset()
            roots.setSelectedItem(root.directory.path)
            assertEquals("custom env; " + last.virtualFile.path, field.text)
            assertEquals("last", command(root).environment.getValue("ATHENA_WORK_GROUP"))
            assertEquals("other", command(other).environment.getValue("ATHENA_WORK_GROUP"))
            val xml = com.intellij.util.xmlb.XmlSerializer.serialize(DbtProjectSettings.get(getProject()).getState())
            val restored = com.intellij.util.xmlb.XmlSerializer.deserialize(xml, DbtSettingsState::class.java)
            assertEquals(field.text, restored.projects.getValue(root.directory.path).environmentFiles)
            assertEquals("", restored.projects.getValue(other.directory.path).environmentFiles)
        } finally {
            configurable.disposeUIResources()
        }
    }

    fun testSettingsResolveDraftContextWithTheSameRulesAsCommandPreparation() {
        val owner = setupRoot()
        add(".env", "DBT_PROFILES_DIR=dotenv-profiles\nTOKEN=dotenv-secret\n")
        add("run/dbt_project.yml", "name: command_project\nprofile: analytics\n")
        add("run/.env", "DBT_PROFILES_DIR=wrong-directory\n")
        add("run/dotenv-profiles/profiles.yml", "analytics: {target: dotenv-default}\n")
        add("run/settings-profiles/profiles.yml", "analytics: {target: settings-default}\n")
        add("run/explicit-profiles/profiles.yml", "analytics: {target: explicit-default}\n")
        add("run/.venv/bin/dbt", "")
        val other = workspace.roots.first { it.directory.path.endsWith("/run") }
        val settings = DbtProjectSettings.get(getProject())
        val configurable = DbtSettingsConfigurable(getProject())
        val tabs = configurable.createComponent() as JTabbedPane
        val fields = components(tabs)
        fun field(name: String) = fields.filterIsInstance<javax.swing.JComponent>().first { it.name == "dbt." + name }
        fun browse(name: String) = field(name) as TextFieldWithBrowseButton
        val roots = field("root") as JComboBox<*>
        val summary = field("executionContext") as JTextArea
        val environment = (field("environmentVariables") as JTable).model as javax.swing.table.DefaultTableModel
        val own = owner.directory.path
        try {
            roots.setSelectedItem(own)
            assertFalse(configurable.isModified())
            browse("projectRoot").setText("run")
            (field("artifacts") as JTextField).setText("build-output")
            val commandDirectory = owner.executionDirectory.resolve("run").toString()
            await("Draft resolves owning-root dotenv against the command directory") { summary.text.contains("Target: dotenv-default (profile default)") }
            assertTrue(summary.text, summary.text.contains(commandDirectory + "/.venv/bin/dbt"))
            assertTrue(summary.text.contains("Environment files: " + own + "/.env"))
            environment.addRow(arrayOf<Any>("DBT_PROFILES_DIR", "settings-profiles"))
            environment.addRow(arrayOf<Any>("TOKEN", "settings-secret"))
            (field("variables") as JTextArea).setText("{\"token\":\"vars-secret\"}")
            fields.filterIsInstance<JTextField>().first { it.toolTipText?.startsWith("Passed before every") == true }
                .setText("run dbt --token prefix-secret")
            await("Settings environment overrides dotenv without publishing draft values") { summary.text.contains("Target: settings-default (profile default)") }
            val options = owner.runOptions.copy()
            options.projectRoot = "run"
            options.artifactDirectory = "build-output"
            options.environmentVariables = java.util.TreeMap<String, String>()
            options.environmentVariables.put("DBT_PROFILES_DIR", "settings-profiles")
            val selection = DbtRunSelection.all(workspace, own)!!
            val command =
                DbtCommand.create(workspace, selection, "Compile", fullRefresh = false, options = options).getOrThrow()
            assertTrue(summary.text.contains("Command directory: " + command.directory))
            assertTrue(summary.text.contains("Executable: " + command.prefix.first()))
            assertTrue(summary.text.contains("Profiles file for commands: " + command.environment.getValue("DBT_PROFILES_DIR") + "/profiles.yml"))
            assertTrue(summary.text.contains("Artifact directory: " + command.artifactDirectory))
            assertEquals(
                "Default: settings-default",
                (field("target") as com.intellij.ui.components.JBTextField).emptyText.text
            )
            listOf("dotenv-secret", "settings-secret", "vars-secret", "prefix-secret").forEach { secret ->
                assertFalse(
                    summary.text.contains(secret)
                )
            }
            assertEquals("", settings.options(own).projectRoot)
            assertEquals("target", settings.options(own).artifactDirectory)
            assertTrue(settings.options(own).environmentVariables.isEmpty())
            val environmentTable = field("environmentVariables") as JTable
            assertTrue(environmentTable.editCellAt(0, 1))
            (environmentTable.editorComponent as JTextField).setText("explicit-profiles")
            await("Context follows the active cell without committing it") { summary.text.contains("Target: explicit-default (profile default)") }
            assertTrue(environmentTable.isEditing)
            environmentTable.cellEditor.cancelCellEditing()
            await("Canceling a cell edit restores the previous context") { summary.text.contains("Target: settings-default (profile default)") }
            browse("profiles").setText("explicit-profiles")
            await("Explicit profiles directory overrides environment defaults") { summary.text.contains("Target: explicit-default (profile default)") }
            (field("target") as JTextField).setText("ci")
            await("Explicit target is distinguished from profile defaults") { summary.text.contains("Target: ci (settings override)") }
            roots.setSelectedItem(other.directory.path)
            assertFalse(summary.text.contains("settings-default"))
            assertTrue(summary.text.contains("Command directory: " + other.directory.path))
            roots.setSelectedItem(own)
            assertTrue(summary.text.contains("Target: ci (settings override)"))
            (field("loadEnvironmentFiles") as JCheckBox).doClick()
            await("Disabled environment loading is explicit") { summary.text.contains("Environment files: Disabled") }
            assertTrue(summary.text.contains("Target: ci (settings override)"))
        } finally {
            configurable.disposeUIResources()
        }
    }

    fun testManagedProcessReceivesDefaultDotenvWithoutLoggingItsValues() {
        val root = setupRoot()
        add(".env", "ATHENA_WORK_GROUP=private-workgroup\n")
        val executable = add(
            "fake-dbt",
            """#!/usr/bin/python3
import json, os
with open('environment.json', 'w') as f:
    json.dump({'workgroup': os.environ.get('ATHENA_WORK_GROUP'), 'home': os.environ.get('HOME')}, f)
"""
        ).virtualFile
        assertTrue(Paths.get(executable.path).toFile().setExecutable(true))
        root.runOptions.executable = executable.path
        root.runOptions.notifications = false
        val runner = newRunner()
        val output = StringBuffer()
        assertTrue(runner.start(command(root)) { text, _ -> output.append(text) })
        await("dbt completes with configured environment") { !runner.isRunning }
        val invocation =
            JsonParser.parseString(Files.readString(root.executionDirectory.resolve("environment.json"))).asJsonObject
        assertEquals("private-workgroup", invocation.get("workgroup").asString)
        assertFalse(invocation.get("home").isJsonNull)
        assertFalse(output.toString().contains("private-workgroup"))
    }

    fun testExecutionDirectoryDoesNotChangeEnvironmentOwnerAndEmptyFileListDisablesLoading() {
        val root = setupRoot()
        add(".env", "ATHENA_WORK_GROUP=owner\n")
        val config = add("execution/dbt_project.yml", "name: execution\nconfig-version: 2\n")
        add("execution/.env", "ATHENA_WORK_GROUP=execution\n")
        root.runOptions.projectRoot = config.virtualFile.parent.path
        assertEquals("owner", command(root).environment.getValue("ATHENA_WORK_GROUP"))
        configureSettings { it.loadEnvironmentFiles = false }
        assertFalse(command(root).environment.contains("ATHENA_WORK_GROUP"))
        configureSettings { o -> o.loadEnvironmentFiles = true; o.environmentFiles = "missing.env" }
        assertFalse(command(root).environment.contains("ATHENA_WORK_GROUP"))
    }
}
