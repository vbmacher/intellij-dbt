package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project.*
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.ui.ComponentValidator
import com.intellij.util.xmlb.XmlSerializer
import org.junit.Assert.*

import java.awt.Component
import java.awt.Container
import java.awt.Point
import javax.swing.*

class DbtSettingsTest : DbtTestCase() {
    private fun toggleSection(form: Container, name: String) {
        val section = components(form).filterIsInstance<JPanel>().first { panel -> panel.name == "dbt." + name }
        section.components.filterIsInstance<JCheckBox>().first().doClick()
    }

    private fun generalScrollPane(tabs: JTabbedPane): JScrollPane =
        components(tabs.getComponentAt(0)).filterIsInstance<JScrollPane>().first()

    private fun visibleInForm(field: Component, form: Container): Boolean =
        generateSequence(field) { it.parent }.takeWhile { it != form }.all { it.isVisible }

    private fun named(form: Container, name: String): JComponent =
        components(form).filterIsInstance<JComponent>().first { field -> field.name == "dbt." + name }

    private fun invalid(configurable: DbtSettingsConfigurable, message: String): ConfigurationException {
        try {
            configurable.apply(); throw AssertionError("Expected validation error: " + message)
        } catch (error: ConfigurationException) {
            assertTrue(error.messageHtml.toString(), error.messageHtml.toString().contains(message)); return error
        }
    }

    private fun layout(container: Container) {
        container.doLayout()
        container.components.filterIsInstance<Container>().forEach(::layout)
    }

    private fun capture(tabs: JTabbedPane, output: String) {
        val image = java.awt.image.BufferedImage(tabs.width, tabs.height, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.setRenderingHint(
            java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
            java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON
        )
        try {
            tabs.printAll(graphics)
        } finally {
            graphics.dispose()
        }
        assertTrue(javax.imageio.ImageIO.write(image, "png", java.io.File(output)))
    }

    fun testNavigationYamlIsIgnoredAndSettingsChangesRefreshDeclarations() {
        project("profile: analytics\n")
        add("profiles.yml", "analytics: {target: dev, outputs: {dev: {type: postgres}}}\n")
        add(".env", "DBT_SCHEMA=default\n")
        add("custom.env", "DBT_SCHEMA=ignored\n")
        add(
            ".dbt-navigation.yml", "engine: v2\ndbt-version: '1.12'\nadapter: snowflake\n" +
                    "env-files: [custom.env]\nmanifest: missing.json\nrun-config: run.yml\n"
        )
        add("run.yml", "env: {DBT_SCHEMA: ignored}\n")
        val function = add("functions/hash.sql", "select 1")
        val check = add("checks/check.sql", "select 1")
        assertEquals("core", root.engine)
        assertEquals("1.10", root.version)
        assertEquals(("postgres"), root.adapter)
        assertEquals("default", DbtEnvironment.values(root)["DBT_SCHEMA"])
        assertEquals(null, root.setting("run-config"))
        assertEquals(root.artifactDirectory.resolve("manifest.json").toString(), root.setting("manifest")!!)
        assertFalse(workspace.symbols.any { s -> s.file == function || s.file == check })

        configureSettings { o -> o.dbtVersion = "1.12"; o.adapter = "snowflake" }
        assertEquals(("snowflake"), root.adapter)
        assertTrue(workspace.symbols.any { it.file == function })
        assertFalse(workspace.symbols.any { it.file == check })
        configureSettings { it.engine = "v2" }
        assertTrue(workspace.symbols.any { it.file == check })
        configureSettings { o -> o.engine = "core"; o.dbtVersion = "1.7" }
        assertFalse(workspace.symbols.any { s -> s.file == function || s.file == check })
    }

    fun testNavigationSettingsApplyResetRootSwitchingAndPersistence() {
        project("profile: analytics\n")
        add("profiles.yml", "analytics: {target: dev, outputs: {dev: {type: postgres}}}\n")
        add("other/dbt_project.yml", "name: other\n")
        val own = root.directory.path
        val other = workspace.roots.find { it.name == "other" }!!.directory.path
        val settings = DbtProjectSettings.get(getProject())
        val configurable = DbtSettingsConfigurable(getProject())
        val tabs = configurable.createComponent().let { it as JTabbedPane }
        val form = generalScrollPane(tabs).viewport.view.let { it as Container }
        fun field(name: String): JComponent =
            components(form).filterIsInstance<JComponent>().firstOrNull { c -> c.name == "dbt." + name }
                ?: throw AssertionError("Missing settings field " + name)

        fun text(name: String, value: String) {
            when (val f = field(name)) {
                is JTextField -> f.text = value
                is JTextArea -> f.text = value
                is TextFieldWithBrowseButton -> f.text = value
                else -> error("Missing editable settings field: $name")
            }
        }

        val roots = components(tabs).filterIsInstance<JComboBox<*>>().first { c -> c.name == "dbt.root" }
            .let { c -> c.let { it as JComboBox<String> } }
        val builtinTable = components(form).filterIsInstance<JTable>().first { t -> t.name == "dbt.builtinSources" }
        try {
            roots.setSelectedItem(own)
            assertFalse(configurable.isModified())
            field("engine").let { it as JComboBox<String> }.setSelectedItem("v2")
            text("dbtVersion", "1.12")
            text("profilesFile", "config/profiles.yml")
            text("adapter", "snowflake")
            text("variables", "{\"start_date\":\"2026-03-01\",\"limits\":[1,2],\"enabled\":true,\"nothing\":null}")
            val environment =
                field("environmentVariables").let { it as JTable }.model.let { it as javax.swing.table.DefaultTableModel }
            environment.addRow(arrayOf<Any>("DBT_SCHEMA", "analytics"))
            environment.addRow(arrayOf<Any>("EMPTY", ""))
            text("catalogPath", "metadata/catalog.json")
            text("stateManifestPath", "state/manifest.json")
            text("infoSchemaDirectory", "target/info_schema/v1")
            field("loadEnvironmentFiles").let { it as JCheckBox }.doClick()
            builtinTable.model.let { it as javax.swing.table.DefaultTableModel }
                .addRow(arrayOf<Any>("adapter.get_relation", "vendor/adapter.sql"))
            assertTrue(configurable.isModified())
            assertEquals("core", settings.options(own).engine)
            roots.setSelectedItem(other)
            assertEquals("core", field("engine").let { it as JComboBox<String> }.selectedItem)
            assertEquals(0, builtinTable.rowCount)
            assertEquals("", field("variables").let { it as JTextArea }.text)
            assertEquals(0, environment.rowCount)
            roots.setSelectedItem(own)
            assertEquals("v2", field("engine").let { it as JComboBox<String> }.selectedItem)
            assertEquals(1, builtinTable.rowCount)
            System.getenv("DBT_SETTINGS_SCREENSHOT")?.let { path ->
                tabs.addNotify()
                tabs.setSize(1200, 900)
                layout(tabs)
                layout(tabs)
                val toolbars = components(form).filterIsInstance<com.intellij.openapi.actionSystem.ActionToolbar>()
                val updates = toolbars.map { it.updateActionsAsync() }
                await("Mapping toolbar icons are rendered") { updates.all { it.isDone } && toolbars.all { it.hasVisibleActions() } }
                layout(tabs)
                try {
                    assertFalse("Advanced metadata stays collapsed until requested", visibleInForm(builtinTable, form))
                    assertFalse(
                        "Environment overrides stay collapsed until requested",
                        visibleInForm(field("environmentVariables"), form)
                    )
                    capture(tabs, path)
                    toggleSection(form, "resolvedContext")
                    layout(tabs); layout(tabs)
                    capture(tabs, path.removeSuffix(".png") + "-context.png")
                    toggleSection(form, "resolvedContext")
                    toggleSection(form, "environmentOptions")
                    layout(tabs); layout(tabs)
                    capture(tabs, path.removeSuffix(".png") + "-environment.png")
                    tabs.setSize(850, 680)
                    layout(tabs); layout(tabs)
                    val scroll = generalScrollPane(tabs)
                    scroll.viewport.setViewPosition(Point(0, form.height - scroll.viewport.height))
                    assertEquals(
                        "Root selector remains fully visible at narrow widths",
                        roots.size,
                        roots.visibleRect.size
                    )
                    capture(tabs, path.removeSuffix(".png") + "-compact.png")
                    toggleSection(form, "environmentOptions")
                } finally {
                    tabs.removeNotify()
                }
            }
            configurable.apply()
            assertFalse(configurable.isModified())
            val saved = settings.options(own).copy()
            assertEquals("config/profiles.yml", saved.profilesFile)
            assertEquals("snowflake", saved.adapter)
            assertTrue(saved.variables.contains("start_date"))
            assertEquals("analytics", saved.environmentVariables.get("DBT_SCHEMA"))
            assertEquals("", saved.environmentVariables.get("EMPTY"))
            assertEquals("vendor/adapter.sql", saved.builtinSources.get("adapter.get_relation"))
            text("adapter", "discarded")
            builtinTable.setValueAt("discarded.sql", 0, 1)
            configurable.reset()
            roots.setSelectedItem(own)
            assertEquals("snowflake", field("adapter").let { it as JTextField }.text)
            assertEquals("vendor/adapter.sql", builtinTable.getValueAt(0, 1))
            val restored =
                XmlSerializer.deserialize(XmlSerializer.serialize(settings.getState()), DbtSettingsState::class.java)
            assertEquals(saved.values, restored.projects.get(own)!!.values)
            assertEquals("core", restored.projects.get(other)!!.engine)
            assertEquals("", restored.projects.get(other)!!.catalogPath)
            assertTrue(restored.projects.get(other)!!.builtinSources.isEmpty())
            assertFalse(restored.projects.get(own)!!.loadEnvironmentFiles)
            settings.loadState(restored)
            assertEquals("v2", root.engine)
            assertEquals("1.12", root.version)
            assertEquals("analytics", root.runOptions.environmentVariables.get("DBT_SCHEMA"))
            assertTrue(root.runOptions.variables.contains("start_date"))
            assertEquals("", restored.projects.get(other)!!.variables)
            assertTrue(restored.projects.get(other)!!.environmentVariables.isEmpty())
            assertEquals(("metadata/catalog.json"), root.setting("catalog"))
            assertEquals(("state/manifest.json"), root.setting("state-manifest"))
            assertEquals(("target/info_schema/v1"), root.setting("info-schema-dir"))
        } finally {
            configurable.disposeUIResources()
        }
    }

    fun testOptionalSectionsPreserveDraftsAndRootUsesFixedHeader() {
        project()
        val own = root.directory.path
        val configurable = DbtSettingsConfigurable(getProject())
        val tabs = configurable.createComponent().let { it as JTabbedPane }
        val scroll = generalScrollPane(tabs)
        val form = scroll.viewport.view.let { it as Container }
        val fields = components(tabs)
        val roots = fields.filterIsInstance<JComboBox<*>>().first { combo -> combo.name == "dbt.root" }
        val variables = fields.filterIsInstance<JTextArea>().first { text -> text.name == "dbt.variables" }
        try {
            assertTrue(SwingUtilities.isDescendingFrom(roots, tabs.getComponentAt(0)))
            assertFalse(SwingUtilities.isDescendingFrom(roots, form))
            roots.setSelectedItem(own)
            val groups = listOf("commandOptions", "environmentOptions", "baselineOptions", "metadataOptions")
            val content = listOf(
                fields.filterIsInstance<JTextField>()
                    .first { text -> text.toolTipText?.startsWith("Passed before every") == true },
                variables,
                fields.filterIsInstance<JCheckBox>().first { box -> box.text.startsWith("Defer unselected") },
                fields.filterIsInstance<JTable>().first { table -> table.name == "dbt.builtinSources" })
            groups.zip(content).forEach { (group, field) ->
                assertFalse(group, visibleInForm(field, form))
                toggleSection(form, group)
                assertTrue(group, visibleInForm(field, form))
            }
            assertFalse("Disclosure changes are not settings edits", configurable.isModified())
            variables.setText("{\"region\":\"EU\"}")
            groups.forEach { toggleSection(form, it) }
            assertTrue(configurable.isModified())
            configurable.apply()
            assertEquals("{\"region\":\"EU\"}", DbtProjectSettings.get(getProject()).options(own).variables)
            toggleSection(form, "environmentOptions")
            assertEquals("{\"region\":\"EU\"}", variables.text)
            assertFalse(configurable.isModified())
        } finally {
            configurable.disposeUIResources()
        }
    }

    fun testRunYamlFilesDoNotSupplyOverridesOrSelections() {
        project("profile: analytics\nvars: {color: project_default}\n")
        add("profiles.yml", "analytics: {target: dev, outputs: {dev: {type: postgres}}}\n")
        add(".env", "DBT_SCHEMA=project_default\n")
        add("models/orders.sql", "select 1")
        val run = add(
            "run.yml", "vars: {color: ignored}\nenv: {DBT_SCHEMA: ignored}\n" +
                    "target: ignored\nadapter: snowflake\nprofiles-file: missing.yml\nselect: orders\nexclude: orders\nselector: nightly\n"
        )
        add("run/dev.yml", run.text)
        val selection = add("run/selection.yml", "select: orders\n")
        assertTrue(com.github.vbmacher.intellij.dbt.navigation.DbtNavigation.inFile(run).isEmpty())
        assertTrue(targetsAt(selection, "orders").isEmpty())
        assertEquals("project_default", DbtEnvironment.values(root)["DBT_SCHEMA"])
        assertEquals(("dev"), DbtExecutionContext.activeTarget(root))
        assertEquals(("postgres"), root.adapter)
        val use = add("models/use.sql", "{{ var('color') }}")
        assertEquals(listOf(projectFile("dbt_project.yml")), targetsAt(use, "color").map { it.containingFile })
    }

    fun testInvalidVariableOverridesCannotBeAppliedOrRun() {
        project()
        add("models/orders.sql", "select 1")
        val configurable = DbtSettingsConfigurable(getProject())
        val form = configurable.createComponent()
        try {
            val variables =
                components(form).filterIsInstance<JTextArea>().first { text -> text.name == "dbt.variables" }
            for (invalid in listOf("[]", "null", "{broken")) {
                variables.setText(invalid)
                try {
                    configurable.apply()
                    fail("Invalid variables must be rejected")
                } catch (_: com.intellij.openapi.options.ConfigurationException) {
                }
                assertEquals("", root.runOptions.variables)
            }
            configureSettings { it.variables = "[]" }
            val selection = DbtRunSelection.all(workspace, root.directory.path)!!
            assertTrue(DbtCommand.create(workspace, selection, "Run", fullRefresh = false).isFailure)
        } finally {
            configurable.disposeUIResources()
        }
    }

    fun testMappingDraftsKeepIncompleteDuplicateAndActivelyEditedRows() {
        project()
        add("other/dbt_project.yml", "name: other\n")
        val own = root.directory.path
        val other = workspace.roots.find { it.name == "other" }!!.directory.path
        val configurable = DbtSettingsConfigurable(getProject())
        val tabs = configurable.createComponent().let { it as JTabbedPane }
        val roots = named(tabs, "root").let { it as JComboBox<String> }
        val environment = named(tabs, "environmentVariables").let { it as JTable }
        val model = environment.model.let { it as javax.swing.table.DefaultTableModel }
        val builtins = named(tabs, "builtinSources").let { it as JTable }
        val builtinsModel = builtins.model.let { it as javax.swing.table.DefaultTableModel }
        try {
            roots.setSelectedItem(own)
            model.addRow(arrayOf<Any>("ORIGINAL", ""))
            model.addRow(arrayOf<Any>(" DUPLICATE ", "中文 العربية فارسی\u200c 😀"))
            assertTrue(environment.editCellAt(0, 0))
            environment.editorComponent.let { it as JTextField }.setText("DUPLICATE")
            assertTrue(configurable.isModified())
            assertTrue("Checking modification must not commit or close the cell editor", environment.isEditing)
            roots.setSelectedItem(other)
            roots.setSelectedItem(own)
            assertEquals(2, model.rowCount)
            assertEquals("DUPLICATE", model.getValueAt(0, 0))
            assertEquals(" DUPLICATE ", model.getValueAt(1, 0))
            invalid(configurable, "already used")
            assertEquals(1, environment.selectedRow)
            assertTrue(root.runOptions.environmentVariables.isEmpty())
            model.setValueAt("", 1, 0)
            roots.setSelectedItem(other)
            roots.setSelectedItem(own)
            invalid(configurable, "Enter a name")
            assertEquals(2, model.rowCount)
            model.setValueAt("9INVALID", 1, 0)
            invalid(configurable, "Names must start")
            model.setValueAt("UNICODE_VALUE", 1, 0)
            builtinsModel.addRow(arrayOf<Any>("adapter.get_relation", ""))
            invalid(configurable, "Choose a local source file")
            assertTrue(
                "Error reveals the collapsed metadata section",
                visibleInForm(builtins, generalScrollPane(tabs).viewport.view.let { it as Container })
            )
            assertEquals(0, builtins.selectedRow)
            assertTrue(root.runOptions.environmentVariables.isEmpty())
            builtinsModel.setValueAt("bad\u0000path", 0, 1)
            invalid(configurable, "valid local source file path")
            assertEquals("bad\u0000path", builtinsModel.getValueAt(0, 1))
            builtinsModel.setValueAt("vendor/adapter.sql", 0, 1)
            model.addRow(arrayOf<Any>("", ""))
            invalid(configurable, "row 3: Enter a name")
            model.removeRow(2)
            configurable.apply()
            assertEquals("", root.runOptions.environmentVariables.get("DUPLICATE"))
            assertEquals("中文 العربية فارسی\u200c 😀", root.runOptions.environmentVariables.get("UNICODE_VALUE"))
            assertEquals("vendor/adapter.sql", root.runOptions.builtinSources.get("adapter.get_relation"))
            assertFalse(configurable.isModified())
            assertTrue(environment.editCellAt(0, 1))
            environment.editorComponent.let { it as JTextField }.setText("edited without leaving the cell")
            configurable.apply()
            assertEquals("edited without leaving the cell", root.runOptions.environmentVariables.get("DUPLICATE"))
            configurable.reset()
            roots.setSelectedItem(own)
            assertEquals("edited without leaving the cell", model.getValueAt(0, 1))
        } finally {
            configurable.disposeUIResources()
        }
    }

    fun testApplyRevealsErrorsInOtherRootsWithoutSavingAnyDraft() {
        project()
        add("other/dbt_project.yml", "name: other\n")
        val own = root.directory.path
        val other = workspace.roots.find { it.name == "other" }!!.directory.path
        val configurable = DbtSettingsConfigurable(getProject())
        val tabs = configurable.createComponent().let { it as JTabbedPane }
        val roots = named(tabs, "root").let { it as JComboBox<String> }
        try {
            listOf(
                Triple("executable", "", "Executable or tool"), Triple("artifacts", "", "Artifact directory"),
                Triple("artifacts", "bad\u0000path", "valid local path"), Triple("variables", "[]", "Variables (JSON)")
            ).forEach { (name, value, message) ->
                configurable.reset()
                roots.setSelectedItem(own)
                val field = named(tabs, name)
                val input: javax.swing.text.JTextComponent = when (field) {
                    is TextFieldWithBrowseButton -> field.textField
                    is JTextArea -> field
                    is JTextField -> field
                    else -> error("Missing editable settings field")
                }
                input.setText(value)
                roots.setSelectedItem(other)
                named(tabs, "adapter").let { it as JTextField }.setText("snowflake")
                tabs.setSelectedIndex(1)
                val error = invalid(configurable, message)
                assertTrue(error.messageHtml.toString(), error.messageHtml.toString().contains(own))
                assertEquals(own, roots.selectedItem)
                assertEquals(0, tabs.selectedIndex)
                assertEquals(value, input.text)
                assertTrue(visibleInForm(input, generalScrollPane(tabs).viewport.view.let { it as Container }))
                assertNotNull(ComponentValidator.getInstance(input).get().validationInfo)
                assertEquals(
                    com.intellij.openapi.util.text.StringUtil.unescapeXmlEntities(error.messageHtml.toString()),
                    input.accessibleContext.accessibleDescription
                )
                if (name == "variables") System.getenv("DBT_SETTINGS_SCREENSHOT")?.let { path ->
                    tabs.addNotify()
                    try {
                        tabs.setSize(1200, 900)
                        layout(tabs); layout(tabs)
                        input.scrollRectToVisible(java.awt.Rectangle(input.size))
                        capture(tabs, path.removeSuffix(".png") + "-validation.png")
                    } finally {
                        tabs.removeNotify()
                    }
                }
                assertEquals("dbt", DbtProjectSettings.get(getProject()).options(own).executable)
                assertEquals("", DbtProjectSettings.get(getProject()).options(other).adapter)
                roots.setSelectedItem(other)
                assertEquals("snowflake", named(tabs, "adapter").let { it as JTextField }.text)
            }
        } finally {
            configurable.disposeUIResources()
        }
    }

    fun testPrerequisitesFollowUnsavedEngineVersionBaselineAndFormatterChanges() {
        project("require-dbt-version: '>=1.3,<1.4'\n")
        add("baseline/manifest.json", "{}")
        val own = root.directory.path
        val configurable = DbtSettingsConfigurable(getProject())
        val tabs = configurable.createComponent().let { it as JTabbedPane }
        val roots = named(tabs, "root").let { it as JComboBox<String> }
        val engine = named(tabs, "engine").let { it as JComboBox<String> }
        val version = named(tabs, "dbtVersion").let { it as JTextField }
        val state = named(tabs, "stateDirectory").let { it as TextFieldWithBrowseButton }
        val defer = named(tabs, "defer").let { it as JCheckBox }
        val favor = named(tabs, "favorState").let { it as JCheckBox }
        val schema = named(tabs, "infoSchemaDirectory").let { it as TextFieldWithBrowseButton }
        val executable = named(tabs, "formatterExecutable").let { it as TextFieldWithBrowseButton }
        val onSave = named(tabs, "formatOnSave").let { it as JCheckBox }
        try {
            roots.setSelectedItem(own)
            assertFalse(defer.isEnabled)
            assertFalse(favor.isEnabled)
            assertFalse(schema.isEnabled)
            assertFalse(onSave.isEnabled)
            state.setText("baseline")
            assertTrue(defer.isEnabled)
            defer.doClick()
            assertFalse("Version from dbt_project.yml is below 1.4", favor.isEnabled)
            version.setText("1.4")
            assertTrue(favor.isEnabled)
            favor.doClick()
            engine.setSelectedItem("v2")
            assertTrue(schema.isEnabled)
            assertTrue("Incompatible selections can be cleared", defer.isEnabled)
            invalid(configurable, "Deferral requires Engine")
            assertTrue(defer.isSelected)
            defer.doClick()
            invalid(configurable, "Favor baseline relations requires")
            favor.doClick()
            assertFalse(defer.isEnabled)
            assertFalse(favor.isEnabled)
            schema.setText("target/info_schema/v1")
            engine.setSelectedItem("core")
            assertFalse(schema.isEnabled)
            assertEquals("target/info_schema/v1", schema.text)
            defer.doClick()
            state.setText("missing")
            invalid(configurable, "directory containing manifest.json")
            assertEquals("missing", state.text)
            state.setText("target")
            invalid(configurable, "separate from the active artifact directory")
            state.setText("baseline")
            configurable.apply()
            executable.setText("sqlfmt")
            assertTrue(onSave.isEnabled)
            onSave.doClick()
            executable.setText("")
            invalid(configurable, "Formatter executable: enter")
            assertTrue(onSave.isEnabled)
            assertTrue(onSave.isSelected)
            onSave.doClick()
            assertFalse(onSave.isEnabled)
            executable.setText("sqlfmt")
            onSave.doClick()
            configurable.apply()
            assertTrue(root.runOptions.formatOnSave)
            assertFalse(configurable.isModified())
        } finally {
            configurable.disposeUIResources()
        }
    }

    fun testTypedPreviewLimitsStayInDraftsAndAreValidatedBeforeSaving() {
        project()
        add("other/dbt_project.yml", "name: other\n")
        val own = root.directory.path
        val other = workspace.roots.find { it.name == "other" }!!.directory.path
        val configurable = DbtSettingsConfigurable(getProject())
        val tabs = configurable.createComponent().let { it as JTabbedPane }
        val roots = named(tabs, "root").let { it as JComboBox<String> }
        val label = components(tabs).filterIsInstance<JLabel>().first { label -> label.text == "Preview row limit" }
        val editor = label.labelFor.let { it as JFormattedTextField }
        try {
            roots.setSelectedItem(own)
            editor.setText("1001")
            assertTrue(configurable.isModified())
            roots.setSelectedItem(other)
            roots.setSelectedItem(own)
            assertEquals("1001", editor.text)
            invalid(configurable, "whole number from 1 to 1000")
            assertEquals(10, root.runOptions.previewLimit)
            editor.setText("25")
            configurable.apply()
            assertEquals(25, root.runOptions.previewLimit)
            assertFalse(configurable.isModified())
            editor.formatter.let { it as javax.swing.text.NumberFormatter }
                .setFormat(java.text.NumberFormat.getIntegerInstance(java.util.Locale.FRANCE))
            editor.setText("1\u202f000")
            configurable.apply()
            assertEquals(1000, root.runOptions.previewLimit)
            assertFalse(configurable.isModified())
            val upstream =
                components(tabs).filterIsInstance<JLabel>().first { label -> label.text == "Upstream levels to show" }
                    .let { label -> label.labelFor.let { it as JFormattedTextField } }
            upstream.setText("21")
            assertTrue(configurable.isModified())
            invalid(configurable, "allowed range")
            assertEquals(5, DbtProjectSettings.get(getProject()).getState().upstreamDepth)
            assertEquals("21", upstream.text)
            upstream.setText("7")
            configurable.apply()
            assertEquals(7, DbtProjectSettings.get(getProject()).getState().upstreamDepth)
            assertFalse(configurable.isModified())
        } finally {
            configurable.disposeUIResources()
        }
    }

    fun testLabelsIdentifyTheEditableInputsInBothTabs() {
        project()
        val configurable = DbtSettingsConfigurable(getProject())
        val tabs = configurable.createComponent().let { it as JTabbedPane }
        try {
            listOf(
                "Owning dbt root",
                "Executable or tool",
                "Target",
                "Environment variables",
                "Variables (JSON)",
                "Local builtin definitions",
                "Information Schema v1 directory (optional)",
                "Preview row limit",
                "Upstream levels to show",
                "Server URL",
                "API key",
                "DAG folders",
                "Web URL"
            ).forEach { text ->
                val label = components(tabs).filterIsInstance<JLabel>().first { label -> label.text == text }
                val input = label.labelFor.let { it as JComponent }
                assertNotNull(text, input)
                assertTrue(text, input.isFocusable)
                assertEquals(text, input.accessibleContext.accessibleName)
                assertFalse("Label must identify an input, not its layout wrapper", input.let { it is JPanel })
            }
            val url = components(tabs).filterIsInstance<JLabel>().first { label -> label.text == "Server URL" }
                .let { label -> label.labelFor.let { it as JTextField } }
            url.setText("https://user:secret@metabase.test")
            invalid(configurable, "Metabase Server URL")
            assertEquals(1, tabs.selectedIndex)
            assertNotNull(ComponentValidator.getInstance(url).get().validationInfo)
            assertFalse(url.accessibleContext.accessibleDescription.contains("secret"))
            url.setText("")
            val folders = components(tabs).filterIsInstance<JLabel>().first { label -> label.text == "DAG folders" }
                .let { label -> label.labelFor.let { it as JList<String> } }
            folders.model.let { it as DefaultListModel<String> }.addElement("bad\u0000path")
            tabs.setSelectedIndex(0)
            invalid(configurable, "DAG folders, row 1")
            assertEquals(1, tabs.selectedIndex)
            assertEquals(0, folders.selectedIndex)
            assertEquals("bad\u0000path", folders.model.getElementAt(0))
            assertTrue(getProject().getService(DbtConsumersSettings::class.java).getState().airflowPaths.isEmpty())
        } finally {
            configurable.disposeUIResources()
        }
    }

    fun testUnresolvedContextAndReadableChoicesDoNotModifySettings() {
        project("profile: analytics\nrequire-dbt-version: '>=1.10.5,<1.11'\n")
        add("profiles.yml", "analytics: {target: \"{{ env_var('TARGET') }}\"}\n")
        val own = root.directory.path
        configureSettings { it.profilesDirectory = own }
        val configurable = DbtSettingsConfigurable(getProject())
        val tabs = configurable.createComponent().let { it as JTabbedPane }
        val summary = named(tabs, "executionContext").let { it as JTextArea }
        val model = named(
            tabs,
            "environmentVariables"
        ).let { it as JTable }.model.let { it as javax.swing.table.DefaultTableModel }
        try {
            named(tabs, "root").let { it as JComboBox<String> }.setSelectedItem(own)
            assertFalse(summary.isEditable)
            assertTrue(summary.text, summary.text.contains("Target: Not resolved locally"))
            assertEquals(
                "Default: 1.10.5",
                named(tabs, "dbtVersion").let { it as com.intellij.ui.components.JBTextField }.emptyText.text
            )
            assertFalse(configurable.isModified())
            val engine = named(tabs, "engine").let { it as JComboBox<String> }
            assertEquals(
                "dbt Core",
                engine.renderer.getListCellRendererComponent(JList<String>(), "core", 0, false, false)
                    .let { it as JLabel }.text
            )
            val direction =
                components(tabs).filterIsInstance<JLabel>().first { label -> label.text == "Layout direction" }
                    .let { label -> label.labelFor.let { it as JComboBox<String> } }
            assertEquals(
                "Top to bottom",
                direction.renderer.getListCellRendererComponent(JList<String>(), "TB", 0, false, false)
                    .let { it as JLabel }.text
            )
            assertEquals("LR", direction.selectedItem)
            assertEquals("LR", DbtProjectSettings.get(getProject()).getState().direction)
            assertFalse(configurable.isModified())
            model.addRow(arrayOf<Any>("", "private-value"))
            await("Incomplete names replace stale context with an explanation") { summary.text.startsWith("Correct the incomplete") }
            assertFalse(summary.text.contains("private-value"))
            assertEquals(1, model.rowCount)
            assertTrue(root.runOptions.environmentVariables.isEmpty())
            model.removeRow(0)
            await("Correcting the table restores context") { summary.text.contains("Target: Not resolved locally") }
            named(tabs, "projectRoot").let { it as TextFieldWithBrowseButton }.setText("bad\u0000path")
            await("Invalid paths do not leave stale resolved defaults") { summary.text.startsWith("Correct invalid paths") }
            assertEquals(
                "Resolve after correcting the execution settings",
                named(tabs, "target").let { it as com.intellij.ui.components.JBTextField }.emptyText.text
            )
            assertEquals(
                "Resolve after correcting the execution settings",
                named(tabs, "target").accessibleContext.accessibleDescription
            )
            assertEquals("", root.runOptions.projectRoot)
            assertTrue(configurable.isModified())
        } finally {
            configurable.disposeUIResources()
        }
    }

    fun testBothTabsFitNarrowViewportsWithLongContentAndDisabledControls() {
        project()
        val longFolder = "long_project_directory_".repeat(7)
        project(path = longFolder + "/")
        val owner = workspace.roots.find { it.directory.path.endsWith(longFolder) }!!.directory.path
        val configurable = DbtSettingsConfigurable(getProject())
        val tabs = configurable.createComponent().let { it as JTabbedPane }
        named(tabs, "root").let { it as JComboBox<String> }.setSelectedItem(owner)
        val form = generalScrollPane(tabs).viewport.view.let { it as Container }
        val folders =
            components(tabs).filterIsInstance<JList<*>>().first().let { list -> list.let { it as JList<String> } }
        folders.model.let { it as DefaultListModel<String> }.addElement(owner + "/dags")
        val url = components(tabs).filterIsInstance<JLabel>().first { label -> label.text == "Server URL" }
            .let { label -> label.labelFor.let { it as JTextField } }
        url.setText("https://" + ("long-server-name-".repeat(7)) + "example.test")
        listOf(
            "resolvedContext",
            "commandOptions",
            "environmentOptions",
            "baselineOptions",
            "metadataOptions"
        ).forEach { toggleSection(form, it) }
        tabs.addNotify()
        try {
            for (width in listOf(1200, 850, 640)) for (tab in 0..1) {
                tabs.setSize(width, 680)
                tabs.setSelectedIndex(tab)
                // Wrapped native labels propagate their height() through the nested forms.
                (0 until 4).forEach { it -> layout(tabs) }
                val scroll = if (tab == 0) generalScrollPane(tabs) else tabs.getComponentAt(1).let { it as JScrollPane }
                assertEquals(
                    "Tab $tab tracks viewport width at $width",
                    scroll.viewport.extentSize.width,
                    scroll.viewport.view.width
                )
                assertFalse(
                    "Tab $tab needs no page-level horizontal scrolling at $width",
                    scroll.horizontalScrollBar.isVisible
                )
                components(tabs.getComponentAt(tab)).filterIsInstance<com.intellij.ui.components.JBLabel>()
                    .filter { label -> label.isAllowAutoWrapping }.forEach { label ->
                    assertTrue(
                        "Tab $tab at $width: help text height ${label.height}, preferred ${label.preferredSize}, parent ${label.parent.size}: " + label.text,
                        label.height >= label.preferredSize.height
                    )
                }
                components(scroll.viewport.view).filterIsInstance<JLabel>().filter { label -> label.labelFor != null }
                    .forEach { label ->
                        val input = label.labelFor.let { it as JComponent }
                        val widget = (input.parent as? JViewport) ?: input
                        val bounds = SwingUtilities.convertRectangle(widget.parent, widget.bounds, scroll.viewport.view)
                        assertTrue(label.text + " left edge", bounds.x >= 0)
                        assertTrue(label.text + " right edge", bounds.x + bounds.width <= scroll.viewport.view.width)
                        assertTrue(label.text + " remains usable", widget.width > 0 && widget.height > 0)
                    }
                if (width == 640 && tab == 0) {
                    val roots = named(tabs, "root")
                    assertEquals(roots.size, roots.visibleRect.size)
                    assertFalse(named(tabs, "defer").isEnabled)
                    assertFalse(named(tabs, "favorState").isEnabled)
                    assertFalse(named(tabs, "infoSchemaDirectory").isEnabled)
                    assertFalse(
                        named(
                            tabs,
                            "infoSchemaDirectory"
                        ).let { it as TextFieldWithBrowseButton }.textField.isEnabled
                    )
                    assertFalse(named(tabs, "formatOnSave").isEnabled)
                    System.getenv("DBT_SETTINGS_SCREENSHOT")?.let { path ->
                        capture(tabs, path.removeSuffix(".png") + "-narrow-context.png")
                        val schema = named(tabs, "infoSchemaDirectory")
                        schema.scrollRectToVisible(java.awt.Rectangle(schema.size))
                        capture(tabs, path.removeSuffix(".png") + "-narrow-disabled.png")
                    }
                }
                if (width == 640 && tab == 1) System.getenv("DBT_INTEGRATIONS_SCREENSHOT")?.let { path ->
                    capture(tabs, path.removeSuffix(".png") + "-narrow.png")
                }
            }
        } finally {
            tabs.removeNotify(); configurable.disposeUIResources()
        }
    }

    fun testPersistedSettingsDeserializeWithoutSerializingComputedKotlinProperties() {
        val options = XmlSerializer.deserialize(
            com.intellij.openapi.util.JDOMUtil.load(
                """<DbtRunOptions>
      <option name="executable" value="wrapper" />
      <option name="profilesDirectory" value="config/profiles" />
      <option name="previewLimit" value="25" />
      <option name="loadEnvironmentFiles" value="false" />
    </DbtRunOptions>"""
            ), DbtRunOptions::class.java
        )
        assertEquals("wrapper", options.executable)
        assertEquals("config/profiles", options.profilesDirectory)
        assertEquals(25, options.previewLimit)
        assertFalse(options.loadEnvironmentFiles)
        val optionNames = XmlSerializer.serialize(options).getChildren("option").map { it.getAttributeValue("name") }
        assertFalse(optionNames.contains("values"))
        val state = XmlSerializer.deserialize(
            com.intellij.openapi.util.JDOMUtil.load(
                """<DbtSettingsState>
      <option name="upstreamDepth" value="7" />
      <option name="downstreamDepth" value="9" />
      <option name="direction" value="TB" />
    </DbtSettingsState>"""
            ), DbtSettingsState::class.java
        )
        assertEquals(7, state.upstreamDepth)
        assertEquals(9, state.downstreamDepth)
        assertEquals("TB", state.direction)
        val stateNames = XmlSerializer.serialize(state).getChildren("option").map { it.getAttributeValue("name") }
        assertFalse(stateNames.contains("upstream"))
        assertFalse(stateNames.contains("downstream"))
    }

}
