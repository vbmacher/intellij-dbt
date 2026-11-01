package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project.DbtEnvironment
import com.github.vbmacher.intellij.dbt.project.DbtExecutionContext
import com.github.vbmacher.intellij.dbt.project.DbtProjectSettings
import com.github.vbmacher.intellij.dbt.project.DbtRoot
import com.github.vbmacher.intellij.dbt.project.DbtRunOptions
import com.github.vbmacher.intellij.dbt.project.DbtVariableOverrides
import com.github.vbmacher.intellij.dbt.syntax.DbtYaml
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComponentValidator
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.TitledSeparator
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Rectangle
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Paths
import javax.swing.*
import javax.swing.table.DefaultTableModel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Edits per-root execution defaults and project-wide lineage settings in the General tab.
 *
 * Each root keeps a session-local [Draft], including incomplete text and active table-cell edits.
 * Switching roots preserves that draft without publishing settings. [apply] validates every root
 * before updating persisted defaults; [reset] rebuilds drafts from saved state. Context summaries
 * read local files only and omit environment, variable, and wrapper-argument values.
 */
class DbtSettingsConfigurable(private val project: Project) : Configurable, Configurable.NoMargin,
    Configurable.NoScroll {
    private val settings = DbtProjectSettings.get(project)
    private val tabs = JTabbedPane()
    private val integrations = DbtConsumersConfigurable(project) { tabs.setSelectedIndex(1) }
    private val validation = DbtSettingsValidation()
    private val root = JComboBox<String>()
    private val executable = object : TextFieldWithBrowseButton() {
        override fun getIconTooltip(): String = "Choose a dbt executable, script, or custom tool"
    }
    private val executableArguments = JBTextField()
    private val environmentFiles = TextFieldWithBrowseButton()
    private val loadEnvironmentFiles = JBCheckBox("Load environment files")
    private val engine = JComboBox<String>(arrayOf("core", "v2"))
    private val dbtVersion = JBTextField()
    private val profilesFile = TextFieldWithBrowseButton()
    private val adapter = JBTextField()
    private val variables = JTextArea(3, 20)
    private val variablesPane = JBScrollPane(variables)
    private val environmentModel = DefaultTableModel(arrayOf<Any>("Variable name", "Value"), 0)
    private val environmentVariables = JTable(environmentModel)
    private val catalog = TextFieldWithBrowseButton()
    private val stateManifest = TextFieldWithBrowseButton()
    private val infoSchema = TextFieldWithBrowseButton()
    private val builtinModel = DefaultTableModel(arrayOf<Any>("Builtin name", "Local source file"), 0)
    private val builtinSources = JTable(builtinModel)

    private fun mappings(table: JTable, model: DefaultTableModel): JPanel {
        table.setPreferredScrollableViewportSize(Dimension(300, 65))
        table.putClientProperty("terminateEditOnFocusLost", java.lang.Boolean.TRUE)
        val panel = ToolbarDecorator.createDecorator(table).disableUpDownActions()
            .setAddAction { model.addRow(arrayOf<Any>("", "")) }
            .setRemoveAction {
                if (!table.isEditing || table.cellEditor.stopCellEditing()) table.selectedRows.reversed()
                    .forEach(model::removeRow)
            }.createPanel()
        panel.setMinimumSize(JBUI.size(0, 85))
        panel.setPreferredSize(JBUI.size(300, 120))
        panel.putClientProperty("dbt.input", table)
        return panel
    }

    private fun fillMappings(table: JTable, model: DefaultTableModel, rows: List<Pair<String, String>>) {
        if (table.isEditing) table.cellEditor.cancelCellEditing()
        model.setRowCount(0)
        rows.forEach { (name, value) -> model.addRow(arrayOf<Any>(name, value)) }
    }

    /** Read an active cell editor too, so switching roots never silently discards unfinished edits. */
    private fun readMappings(table: JTable, model: DefaultTableModel): List<Pair<String, String>> {
        fun value(row: Int, column: Int): String {
            val current = if (table.isEditing && row == table.editingRow && column == table.editingColumn) {
                val editor = table.editorComponent
                if (editor is javax.swing.text.JTextComponent) editor.text else table.cellEditor.cellEditorValue
            } else model.getValueAt(row, column)
            return current?.toString().orEmpty()
        }
        return (0 until model.rowCount).map { row -> value(row, 0) to value(row, 1) }
    }

    private val projectRoot = object : TextFieldWithBrowseButton() {
        override fun getIconTooltip(): String = "Choose a dbt project root directory"
    }
    private val profiles = object : TextFieldWithBrowseButton() {
        override fun getIconTooltip(): String = "Choose a dbt profiles directory"
    }
    private val artifacts = JBTextField()
    private val manifest = TextFieldWithBrowseButton()
    private val target = JBTextField()
    private val stateDirectory = TextFieldWithBrowseButton()
    private val defer = JBCheckBox("Defer unselected upstream resources to baseline state")
    private val favorState = JBCheckBox("Favor baseline relations (dbt Core 1.4+)")
    private val formatter = JComboBox<String>(arrayOf("sqlfmt", "sqlfluff"))
    private val formatterExecutable = TextFieldWithBrowseButton()
    private val formatOnSave = JBCheckBox("Format dbt SQL on save")
    private val limit = JSpinner(SpinnerNumberModel(10, 1, 1000, 1))
    private val colors = JBCheckBox("Colored output")
    private val notifications = JBCheckBox("Desktop notifications when dbt commands finish")
    private val upstream = JSpinner(SpinnerNumberModel(5, 1, 20, 1))
    private val downstream = JSpinner(SpinnerNumberModel(5, 1, 20, 1))
    private val direction = JComboBox<String>(arrayOf("LR", "TB", "RL", "BT"))
    private val deferHelp = JBLabel()
    private val favorHelp = JBLabel()
    private val schemaHelp = JBLabel()
    private val formatterHelp = JBLabel()
    private val navigationHelp =
        JBLabel("<html>These settings guide editor navigation; they do not change the dbt executable.</html>")

    init {
        navigationHelp.setForeground(UIUtil.getContextHelpForeground())
        navigationHelp.setAllowAutoWrapping(true).setCopyable(true)
    }

    private val executionContext = JTextArea(7, 20)

    init {
        executionContext.setName("dbt.executionContext")
        executionContext.setEditable(false)
        executionContext.setLineWrap(true)
        executionContext.setWrapStyleWord(true)
        executionContext.setFont(UIUtil.getLabelFont())
        executionContext.accessibleContext.setAccessibleName("Resolved execution context")
    }

    private val contextHelpText =
        "Environment: IDE → files → settings overrides; resolved profiles and artifact paths take final precedence.\n" +
                "Environment, variable and wrapper argument values are not repeated.\n" +
                "Run configurations may override these defaults. No commands or connection checks are run."
    private val contextHelp = JBLabel("<html>" + contextHelpText.replace("\n", "<br>") + "</html>")

    init {
        executionContext.accessibleContext.setAccessibleDescription(contextHelpText)
    }

    private var selectedRoot: String = ""

    /** The preview text remains separate from its numeric value until validation accepts it. */
    private data class Draft(
        val options: DbtRunOptions,
        val environment: List<Pair<String, String>>,
        val builtins: List<Pair<String, String>>,
        val preview: String
    ) {
        val values: List<Any> get() = listOf(options.values, environment, builtins, preview)
    }

    private fun savedDraft(options: DbtRunOptions): Draft = Draft(
        options.copy(),
        options.environmentVariables.toList().sortedBy { it.first },
        options.builtinSources.toList().sortedBy { it.first },
        options.previewLimit.toString()
    )

    private var drafts = emptyMap<String, Draft>()
    private var baseline = emptyList<Any?>()
    private var contextDisposed = false
    private val contextUpdate = Timer(150) { if (!contextDisposed) updateResolvedContext() }

    init {
        contextUpdate.isRepeats = false
    }

    private fun displayNames(combo: JComboBox<String>, names: Map<String, String>) =
        combo.setRenderer(object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>,
                value: Any?,
                index: Int,
                selected: Boolean,
                focus: Boolean
            ): java.awt.Component =
                super.getListCellRendererComponent(
                    list,
                    names.getOrDefault(java.lang.String.valueOf(value), java.lang.String.valueOf(value)),
                    index,
                    selected,
                    focus
                )
        })

    private val contextFields: List<JBTextField>
        get() = listOf(
            target, (profiles.textField as JBTextField),
            (projectRoot.textField as JBTextField), (environmentFiles.textField as JBTextField),
            (profilesFile.textField as JBTextField), (manifest.textField as JBTextField),
            (catalog.textField as JBTextField), dbtVersion
        )

    /** Resolve local defaults using command-preparation rules without launching a tool or exposing values. */
    private fun resolvedContext(draft: Draft): Result<Pair<List<Pair<JBTextField, String>>, String>> {
        fun failure(message: String): Result<Pair<List<Pair<JBTextField, String>>, String>> =
            Result.failure(IllegalArgumentException(message))

        val options = draft.options
        val names = draft.environment.map { it.first.trim() }
        if (names.any { !it.matches(Regex("[A-Za-z_][A-Za-z_0-9]*")) } || names.distinct().size != names.size)
            return failure("Correct the incomplete, invalid or duplicate names in Environment variables to resolve this context.")
        val directory = rootDirectory(selectedRoot)
        val config =
            directory?.findChild("dbt_project.yml")?.let { DbtYaml.psiFile(project, it) }?.let(DbtYaml::mapping)
        if (directory == null || config == null) return failure("No readable dbt_project.yml was found for this root. Choose a dbt project to resolve its context.")
        return try {
            if (options.executable.isEmpty() || options.artifactDirectory.isEmpty())
                failure("Enter an executable and artifact directory to resolve this context.")
            else {
                options.environmentVariables = java.util.TreeMap(names.zip(draft.environment.map { it.second }).toMap())
                val readFile: (String) -> com.intellij.psi.PsiFile? = { DbtRoot.psiFile(directory, project, it) }
                val base = Paths.get(selectedRoot)
                val commandDirectory = base.resolve(options.projectRoot).normalize()
                val profile = DbtExecutionContext.profiles(commandDirectory, options, config, readFile)
                val commandProfiles = profile.directory.resolve("profiles.yml").toString()
                val artifactDirectory = commandDirectory.resolve(options.artifactDirectory).normalize()
                val environment = DbtEnvironment.paths(options)
                    .map { path -> base.resolve(path).normalize().toString() to (readFile(path) != null) }
                val defaults = listOf(
                    target to (profile.defaultTarget ?: "Profile default is not resolved locally"),
                    (profiles.textField as JBTextField) to profile.directory.toString(),
                    (projectRoot.textField as JBTextField) to selectedRoot,
                    (environmentFiles.textField as JBTextField) to (if (options.loadEnvironmentFiles) base.resolve(".env")
                        .toString() else "Environment file loading is off"),
                    (profilesFile.textField as JBTextField) to commandProfiles,
                    (manifest.textField as JBTextField) to artifactDirectory.resolve("manifest.json").toString(),
                    (catalog.textField as JBTextField) to artifactDirectory.resolve("catalog.json").toString(),
                    dbtVersion to DbtRoot.configuredVersion(config, "")
                        .let { if (it == "unknown") "Cannot infer from require-dbt-version; enter the installed version" else it })
                val executable = DbtExecutionContext.executable(commandDirectory, options.executable)
                val executableDisplay =
                    if (executable.contains('/') || executable.contains('\\')) executable else executable + " (looked up on PATH when run)"
                val targetDisplay = if (options.target.isNotEmpty()) options.target + " (settings override)"
                else profile.defaultTarget?.let { it + " (profile default)" }
                    ?: "Not resolved locally; dbt may resolve it or report an error"
                val envDisplay =
                    if (!options.loadEnvironmentFiles) "Disabled" else if (environment.isEmpty()) "None configured" else environment.joinToString(
                        "; "
                    ) { (path, found) -> path + if (found) "" else " (missing; skipped)" }
                val text = listOf(
                    "Command directory: " + commandDirectory +
                            (if (readFile(
                                    commandDirectory.resolve("dbt_project.yml").toString()
                                ) == null
                            ) " (dbt_project.yml not found locally)" else ""),
                    "Executable: " + executableDisplay,
                    "Arguments before dbt command: " + (if (options.executableArguments.isEmpty()) "None" else "Configured; see Show command options"),
                    "Profiles file for commands: " + commandProfiles + (if (readFile(commandProfiles) == null) " (not found locally)" else ""),
                    "Target: " + targetDisplay,
                    "Artifact directory: " + artifactDirectory,
                    "Environment files: " + envDisplay
                ).joinToString("\n")
                Result.success(defaults to text)
            }
        } catch (_: InvalidPathException) {
            failure("Correct invalid paths in the settings or DBT_PROFILES_DIR to resolve this context.")
        }
    }

    private fun updateResolvedContext() {
        contextUpdate.stop()
        if (selectedRoot.isNotEmpty()) {
            contextFields.forEach { field ->
                field.emptyText.text = "Resolve after correcting the execution settings"
                if (!ComponentValidator.getInstance(field).filter { it.validationInfo != null }.isPresent)
                    field.accessibleContext.accessibleDescription = "Resolve after correcting the execution settings"
            }
            val draft = readDraft()
            val resolved =
                ReadAction.computeBlocking<Result<Pair<List<Pair<JBTextField, String>>, String>>, RuntimeException> {
                    resolvedContext(draft)
                }
            val text = resolved.fold({ (defaults, text) ->
                defaults.forEach { (field, value) ->
                    field.emptyText.text = "Default: " + value
                    if (!ComponentValidator.getInstance(field).filter { it.validationInfo != null }.isPresent)
                        field.accessibleContext.accessibleDescription = "When blank: " + value
                }
                text
            }, { it.message.orEmpty() })
            if (executionContext.text != text) {
                executionContext.text = text
                executionContext.caretPosition = 0
            }
        }
    }

    private fun previewLimit(text: String): Int? {
        val number =
            dbtResult { (DbtUi.input(limit) as JFormattedTextField).formatter.stringToValue(text) as? Number }.getOrNull()
        return number?.takeIf { it.toDouble() == it.toInt().toDouble() && it.toInt() in 1..1000 }?.toInt()
    }

    private fun withHelp(field: JComponent, hint: JBLabel): JPanel {
        val result = object : JPanel(BorderLayout(0, JBUI.scale(4))) {
            override fun getMinimumSize(): Dimension = Dimension(0, preferredSize.height)
        }
        hint.setForeground(UIUtil.getContextHelpForeground())
        hint.setAllowAutoWrapping(true).setCopyable(true)
        result.add(field, BorderLayout.NORTH)
        result.add(hint, BorderLayout.CENTER)
        result.putClientProperty("dbt.input", field)
        return result
    }

    /** Look up the selected project's config without discovering or indexing the workspace. */
    private fun rootDirectory(owner: String): com.intellij.openapi.vfs.VirtualFile? =
        ProjectRootManager.getInstance(project).contentRoots.asSequence().mapNotNull { base ->
            if (base.path == owner) base
            else if (owner.startsWith(base.path + "/")) base.findFileByRelativePath(owner.removePrefix(base.path + "/"))
            else null
        }.firstOrNull() ?: LocalFileSystem.getInstance().findFileByPath(owner)

    private fun version(owner: String, configured: String): String =
        ReadAction.computeBlocking<String, RuntimeException> {
            rootDirectory(owner)?.findChild("dbt_project.yml")?.let { DbtYaml.psiFile(project, it) }
                ?.let(DbtYaml::mapping)
                ?.let { DbtRoot.configuredVersion(it, configured) } ?: configured.ifEmpty { "unknown" }
        }

    private fun updatePrerequisites() {
        val core = engine.selectedItem == "core"
        val hasState = stateDirectory.text.trim().isNotEmpty()
        val favors = core && DbtRoot.supportsVersion(version(selectedRoot, dbtVersion.text.trim()), 4)
        // An incompatible selection can still be cleared; it cannot be newly selected.
        defer.setEnabled(core && hasState || defer.isSelected)
        favorState.setEnabled(favors && hasState && defer.isSelected || favorState.isSelected)
        infoSchema.setEnabled(!core)
        formatOnSave.setEnabled(formatterExecutable.text.trim().isNotEmpty() || formatOnSave.isSelected)
        listOf(
            Triple(defer, deferHelp, "Requires dbt Core and a baseline directory containing manifest.json."),
            Triple(favorState, favorHelp, "Requires deferral and dbt Core 1.4 or later."),
            Triple(infoSchema, schemaHelp, "Used only with dbt v2. The path is kept when dbt Core is selected."),
            Triple(formatOnSave, formatterHelp, "Choose a formatter executable to enable formatting on save.")
        ).forEach { (field, hint, text) ->
            hint.setText("<html>" + text + "</html>")
            DbtUi.input(field).accessibleContext.setAccessibleDescription(text)
        }
    }

    override fun getDisplayName(): String = "dbt Workspace"

    override fun createComponent(): JComponent {
        displayNames(engine, mapOf("core" to "dbt Core", "v2" to "dbt v2"))
        displayNames(
            direction,
            mapOf("LR" to "Left to right", "TB" to "Top to bottom", "RL" to "Right to left", "BT" to "Bottom to top")
        )
        val panel = DbtSettingsConfigurable.formPanel()
        // Each tab scrolls independently; supply the usual settings padding here.
        panel.setBorder(JBUI.Borders.empty(11, 16, 16, 16))
        executable.addBrowseFolderListener(
            project, FileChooserDescriptorFactory.singleFile()
                .withTitle("Select dbt executable")
                .withDescription("Choose a dbt executable, wrapper script, or custom tool.")
        )
        projectRoot.addBrowseFolderListener(
            project, FileChooserDescriptorFactory.singleDir()
                .withTitle("Select dbt project root")
                .withDescription("Choose the directory containing dbt_project.yml.")
        )
        profiles.addBrowseFolderListener(
            project, FileChooserDescriptorFactory.singleDir()
                .withTitle("Select dbt profiles directory")
                .withDescription("Choose the directory containing profiles.yml.")
        )
        environmentFiles.setName("dbt.environmentFiles")
        environmentFiles.addBrowseFolderListener(
            project, FileChooserDescriptorFactory.singleFile()
                .withTitle("Select environment file").withShowHiddenFiles(true)
        )
        environmentFiles.setToolTipText("Environment files separated by semicolons, relative to the owning dbt root or absolute. Later files override earlier files. Blank uses .env. Environment variables from IntelliJ settings override files.")
        listOf(
            "root" to root,
            "target" to target,
            "profiles" to profiles,
            "projectRoot" to projectRoot,
            "executable" to executable,
            "artifacts" to artifacts,
            "stateDirectory" to stateDirectory,
            "defer" to defer,
            "favorState" to favorState,
            "formatterExecutable" to formatterExecutable,
            "formatOnSave" to formatOnSave,
            "engine" to engine,
            "dbtVersion" to dbtVersion,
            "profilesFile" to profilesFile,
            "adapter" to adapter,
            "variables" to variables,
            "environmentVariables" to environmentVariables,
            "catalogPath" to catalog,
            "stateManifestPath" to stateManifest,
            "infoSchemaDirectory" to infoSchema,
            "builtinSources" to builtinSources,
            "loadEnvironmentFiles" to loadEnvironmentFiles
        ).forEach { (name, field) -> field.setName("dbt." + name) }
        listOf(
            profilesFile to "Select profiles YAML",
            catalog to "Select catalog JSON", stateManifest to "Select state manifest JSON"
        ).forEach { (field, title) ->
            field.addBrowseFolderListener(project, FileChooserDescriptorFactory.singleFile().withTitle(title))
        }
        infoSchema.addBrowseFolderListener(
            project,
            FileChooserDescriptorFactory.singleDir().withTitle("Select Information Schema v1 directory")
        )
        dbtVersion.emptyText.setText("From require-dbt-version, or 1.10")
        profilesFile.setToolTipText("Optional profiles file for editor navigation, relative to the owning dbt root or absolute. Commands use Profiles directory.")
        adapter.setToolTipText("Optional adapter for editor navigation. Blank uses the selected profile output type.")
        variables.setToolTipText("JSON object of dbt variable overrides, for example {\"start_date\": \"2026-03-01\"}. Passed to dbt as --vars and used by editor navigation.")
        variablesPane.setMinimumSize(JBUI.size(0, 60))
        variablesPane.setPreferredSize(JBUI.size(300, 65))
        variables.setLineWrap(true)
        variables.setWrapStyleWord(true)
        environmentVariables.setToolTipText("Override inherited environment and environment files for dbt commands and formatters. Empty values are allowed.")
        catalog.setToolTipText("Optional catalog JSON, relative to the owning dbt root or absolute. Blank uses catalog.json in the artifact directory.")
        stateManifest.setToolTipText("Optional manifest for navigation to state resources, relative to the owning dbt root or absolute. Commands use Baseline state directory.")
        infoSchema.setToolTipText("Information Schema v1 directory, relative to the owning dbt root or absolute. Requires engine v2.")
        loadEnvironmentFiles.addActionListener { environmentFiles.setEnabled(loadEnvironmentFiles.isSelected) }
        stateDirectory.addBrowseFolderListener(
            project, FileChooserDescriptorFactory.singleDir()
                .withTitle("Select baseline state directory")
                .withDescription("Choose a separate directory containing manifest.json.")
        )
        manifest.addBrowseFolderListener(
            project,
            FileChooserDescriptorFactory.singleFile().withTitle("Select dbt manifest JSON")
        )
        manifest.setToolTipText("Optional manifest file, absolute or relative to the execution project. Blank uses manifest.json in the artifact directory.")
        formatterExecutable.addBrowseFolderListener(
            project,
            FileChooserDescriptorFactory.singleFile().withTitle("Select SQL formatter executable")
        )
        executable.setToolTipText("dbt, a wrapper script, or a tool such as uv, poetry, bash or zsh. Runs in the execution project directory.")
        target.setToolTipText("Optional. Blank uses the default target from the profiles.yml used by commands. Dynamic defaults are resolved by dbt when it runs.")
        profiles.setToolTipText("Optional. Blank uses DBT_PROFILES_DIR, a profiles.yml in the command directory, then ~/.dbt. Relative paths use the command directory.")
        projectRoot.setToolTipText("Optional. Blank runs commands in the owning dbt root. Relative overrides use the owning root.")
        artifacts.setToolTipText("Where dbt writes artifacts. Relative paths use the command directory.")
        adapter.emptyText.setText("From the selected navigation profile output")
        executableArguments.emptyText.setText("For uv: run dbt; for zsh: scripts/run_dbt.zsh")
        executableArguments.setToolTipText("Passed before every dbt command. Example: uv + run dbt → uv run dbt test. Quote paths with spaces; shell expressions are not evaluated.")
        upstream.setToolTipText("How many dependency levels to show before the focused resource in Lineage. 1 shows its direct inputs; 2 also shows their inputs. Accepts 1–20.")
        downstream.setToolTipText("How many dependent levels to show after the focused resource in Lineage. 1 shows resources that use it directly; 2 also shows resources that use those. Accepts 1–20.")
        fun form(rows: List<Pair<String, JComponent>>): JPanel {
            val result = JPanel(GridBagLayout())
            DbtUi.labeledRows(result, JBUI.insets(4, 5, 4, 10), rows)
            return result
        }

        fun collapsed(name: String, title: String, rows: List<Pair<String, JComponent>>): JPanel {
            val result = JPanel(BorderLayout(0, JBUI.scale(4)))
            result.setName("dbt." + name)
            val content = form(rows)
            val show = JBCheckBox("Show " + title)
            show.setMnemonic(title.first().uppercaseChar())
            content.setVisible(false)
            show.addActionListener {
                content.setVisible(show.isSelected)
                if (name == "resolvedContext" && show.isSelected) updateResolvedContext()
                result.revalidate()
                result.repaint()
            }
            result.add(show, BorderLayout.NORTH)
            result.add(content, BorderLayout.CENTER)
            result.putClientProperty("dbt.disclosure", show)
            return result
        }

        val sections = listOf(
            DbtSettingsConfigurable.separator("Execution"),
            form(listOf("Executable or tool" to executable, "Target" to target, "Profiles directory" to profiles)),
            collapsed(
                "resolvedContext",
                "resolved execution context",
                listOf("" to withHelp(JBScrollPane(executionContext), contextHelp))
            ),
            collapsed(
                "commandOptions", "command options", listOf(
                    "Arguments before dbt command" to executableArguments,
                    "Project root override" to projectRoot, "Artifact directory" to artifacts,
                    "Preview row limit" to limit, "" to colors, "" to notifications
                )
            ),
            collapsed(
                "environmentOptions", "environment and variables", listOf(
                    "" to loadEnvironmentFiles,
                    "Environment files" to environmentFiles,
                    "Environment variables" to mappings(environmentVariables, environmentModel),
                    "Variables (JSON)" to variablesPane
                )
            ),
            collapsed(
                "baselineOptions", "baseline state", listOf(
                    "Baseline state directory" to stateDirectory,
                    "" to withHelp(defer, deferHelp),
                    "" to withHelp(favorState, favorHelp)
                )
            ),
            DbtSettingsConfigurable.separator("Navigation"),
            form(listOf("Engine" to engine, "dbt version (optional)" to dbtVersion, "Adapter (optional)" to adapter)),
            navigationHelp,
            collapsed(
                "metadataOptions", "advanced metadata", listOf(
                    "Profiles file for navigation (optional)" to profilesFile,
                    "Local builtin definitions" to mappings(builtinSources, builtinModel),
                    "Catalog file (optional)" to catalog, "Manifest file (optional)" to manifest,
                    "State manifest for navigation (optional)" to stateManifest,
                    "Information Schema v1 directory (optional)" to withHelp(infoSchema, schemaHelp)
                )
            ),
            DbtSettingsConfigurable.separator("Formatting"),
            form(
                listOf(
                    "SQL formatter" to formatter,
                    "Formatter executable (optional)" to formatterExecutable,
                    "" to withHelp(formatOnSave, formatterHelp)
                )
            ),
            DbtSettingsConfigurable.separator("Lineage — shared across dbt roots"),
            form(
                listOf(
                    "Upstream levels to show" to upstream,
                    "Downstream levels to show" to downstream,
                    "Layout direction" to direction
                )
            )
        )
        val labels = sections.filterIsInstance<JPanel>().filter { it !is TitledSeparator && it.layout is GridBagLayout }
            .flatMap { it.components.filterIsInstance<JLabel>() }
        val labelWidth = labels.maxOf { it.preferredSize.width }
        labels.forEach { label -> label.preferredSize = Dimension(labelWidth, label.preferredSize.height) }
        sections.forEachIndexed { row, section ->
            val c = GridBagConstraints()
            c.gridx = 0; c.gridy = row; c.weightx = 1.0
            c.fill = GridBagConstraints.HORIZONTAL
            c.anchor = GridBagConstraints.NORTHWEST
            c.insets = JBUI.insets(if (row > 0 && section is TitledSeparator) 12 else 4, 0, 4, 0)
            panel.add(section, c)
        }

        val filler = GridBagConstraints()
        filler.gridy = sections.size
        filler.weighty = 1.0
        panel.add(JPanel(), filler)
        root.addActionListener {
            if (selectedRoot.isNotEmpty()) drafts += selectedRoot to readDraft()
            validation.clear()
            selectRoot()
        }
        engine.addActionListener { updatePrerequisites() }
        defer.addActionListener { updatePrerequisites() }
        favorState.addActionListener { updatePrerequisites() }
        formatOnSave.addActionListener { updatePrerequisites() }
        val prerequisitesChanged = object : DocumentListener {
            override fun insertUpdate(event: DocumentEvent) = updatePrerequisites()
            override fun removeUpdate(event: DocumentEvent) = updatePrerequisites()
            override fun changedUpdate(event: DocumentEvent) = updatePrerequisites()
        }
        listOf(dbtVersion.document, stateDirectory.textField.document, formatterExecutable.textField.document)
            .forEach { it.addDocumentListener(prerequisitesChanged) }
        val contextChanged = object : DocumentListener {
            override fun insertUpdate(event: DocumentEvent) = contextUpdate.restart()
            override fun removeUpdate(event: DocumentEvent) = contextUpdate.restart()
            override fun changedUpdate(event: DocumentEvent) = contextUpdate.restart()
        }
        listOf(
            executable.textField,
            executableArguments,
            target,
            profiles.textField,
            projectRoot.textField,
            artifacts,
            environmentFiles.textField,
            profilesFile.textField,
            manifest.textField,
            catalog.textField,
            dbtVersion
        )
            .forEach { it.document.addDocumentListener(contextChanged) }
        loadEnvironmentFiles.addActionListener { contextUpdate.restart() }
        environmentModel.addTableModelListener { contextUpdate.restart() }
        var environmentEditor: javax.swing.text.JTextComponent? = null
        environmentVariables.addPropertyChangeListener("tableCellEditor") {
            environmentEditor?.document?.removeDocumentListener(contextChanged)
            environmentEditor = environmentVariables.editorComponent as? javax.swing.text.JTextComponent
            environmentEditor?.document?.addDocumentListener(contextChanged)
            contextUpdate.restart()
        }

        val owner = JPanel(BorderLayout(0, JBUI.scale(4)))
        owner.setBorder(JBUI.Borders.empty(11, 16, 4, 16))
        root.setMinimumSize(Dimension(0, root.minimumSize.height))
        owner.add(form(listOf("Owning dbt root" to root)), BorderLayout.NORTH)
        val scope =
            JBLabel("<html>Execution, Navigation and Formatting apply to this root. Lineage is shared across roots.</html>")
        scope.setForeground(UIUtil.getContextHelpForeground())
        scope.setAllowAutoWrapping(true).setCopyable(true)
        owner.add(scope, BorderLayout.SOUTH)
        val general = JPanel(BorderLayout())
        general.add(owner, BorderLayout.NORTH)
        general.add(JBScrollPane(panel), BorderLayout.CENTER)
        tabs.addTab("General", general)
        tabs.addTab("Integrations", JBScrollPane(integrations.createComponent()))
        reset()
        return tabs
    }

    private fun selectRoot() {
        selectedRoot = root.selectedItem?.toString().orEmpty()
        fill(drafts.getOrElse(selectedRoot) { savedDraft(DbtRunOptions()) })
    }

    private fun fill(draft: Draft) {
        val o = draft.options
        executable.setText(o.executable)
        executableArguments.setText(o.executableArguments)
        environmentFiles.setText(o.environmentFiles)
        loadEnvironmentFiles.setSelected(o.loadEnvironmentFiles)
        environmentFiles.setEnabled(o.loadEnvironmentFiles)
        engine.setSelectedItem(o.engine)
        dbtVersion.setText(o.dbtVersion)
        profilesFile.setText(o.profilesFile)
        adapter.setText(o.adapter)
        variables.setText(o.variables)
        fillMappings(environmentVariables, environmentModel, draft.environment)
        catalog.setText(o.catalogPath)
        stateManifest.setText(o.stateManifestPath)
        infoSchema.setText(o.infoSchemaDirectory)
        fillMappings(builtinSources, builtinModel, draft.builtins)
        projectRoot.setText(o.projectRoot)
        profiles.setText(o.profilesDirectory)
        artifacts.setText(o.artifactDirectory)
        manifest.setText(o.manifestPath)
        target.setText(o.target)
        stateDirectory.setText(o.stateDirectory)
        defer.setSelected(o.defer)
        favorState.setSelected(o.favorState)
        formatter.setSelectedItem(o.formatter)
        formatterExecutable.setText(o.formatterExecutable)
        formatOnSave.setSelected(o.formatOnSave)
        limit.setValue(o.previewLimit.coerceIn(1, 1000))
        (DbtUi.input(limit) as JFormattedTextField).setText(draft.preview)
        colors.setSelected(o.colors)
        notifications.setSelected(o.notifications)
        updatePrerequisites()
        updateResolvedContext()
    }

    private fun readDraft(): Draft {
        val o = drafts.getOrElse(selectedRoot) { savedDraft(DbtRunOptions()) }.options.copy()
        o.executable = executable.text.trim()
        o.executableArguments = executableArguments.text.trim()
        o.environmentFiles = environmentFiles.text.trim()
        o.loadEnvironmentFiles = loadEnvironmentFiles.isSelected
        o.engine = engine.selectedItem.toString()
        o.dbtVersion = dbtVersion.text.trim()
        o.profilesFile = profilesFile.text.trim()
        o.adapter = adapter.text.trim()
        o.variables = variables.text.trim()
        o.catalogPath = catalog.text.trim()
        o.stateManifestPath = stateManifest.text.trim()
        o.infoSchemaDirectory = infoSchema.text.trim()
        o.projectRoot = projectRoot.text.trim()
        o.profilesDirectory = profiles.text.trim()
        o.artifactDirectory = artifacts.text.trim()
        o.manifestPath = manifest.text.trim()
        o.target = target.text.trim()
        o.stateDirectory = stateDirectory.text.trim()
        o.defer = defer.isSelected
        o.favorState = favorState.isSelected
        o.formatter = formatter.selectedItem.toString()
        o.formatterExecutable = formatterExecutable.text.trim()
        o.formatOnSave = formatOnSave.isSelected
        val preview = (DbtUi.input(limit) as JFormattedTextField).text
        o.previewLimit = previewLimit(preview) ?: (limit.value as Number).toInt()
        o.colors = colors.isSelected
        o.notifications = notifications.isSelected
        return Draft(
            o,
            readMappings(environmentVariables, environmentModel),
            readMappings(builtinSources, builtinModel),
            preview
        )
    }

    private fun snapshot(): List<Any?> = listOf(
        (drafts + (selectedRoot to readDraft())).toList().sortedBy { it.first }
            .map { (path, draft) -> path to draft.values },
        (DbtUi.input(upstream) as JFormattedTextField).text,
        (DbtUi.input(downstream) as JFormattedTextField).text, direction.selectedItem
    )

    override fun reset() {
        selectedRoot = ""
        validation.clear()
        drafts = settings.getState().projects.mapValues { savedDraft(it.value) }
        // Root paths are recorded by normal workspace discovery, so opening Settings never scans the project on the EDT.
        root.removeAllItems()
        val paths = (drafts.keys.toList() + listOfNotNull(project.basePath)).distinct().sorted()
        paths.forEach(root::addItem)
        selectRoot()
        upstream.setValue(settings.getState().upstream)
        downstream.setValue(settings.getState().downstream)
        direction.setSelectedItem(settings.getState().direction)
        integrations.reset()
        baseline = snapshot()
    }

    override fun isModified(): Boolean = snapshot() != baseline || integrations.isModified()

    override fun disposeUIResources() {
        contextDisposed = true
        contextUpdate.stop()
        executable.dispose()
        environmentFiles.dispose()
        profilesFile.dispose()
        catalog.dispose()
        stateManifest.dispose()
        infoSchema.dispose()
        projectRoot.dispose()
        profiles.dispose()
        stateDirectory.dispose()
        manifest.dispose()
        formatterExecutable.dispose()
        integrations.disposeUIResources()
        validation.dispose()
    }

    private fun reject(owner: String, field: JComponent, message: String, row: Int = -1, column: Int = 0): Nothing {
        if (owner != selectedRoot) root.selectedItem = owner
        tabs.selectedIndex = 0
        generateSequence<java.awt.Component>(field) { it.parent }.forEach { component ->
            val show = (component as? JComponent)?.getClientProperty("dbt.disclosure") as? JCheckBox
            if (show != null && !show.isSelected) show.doClick()
        }
        if (field is JTable && row >= 0) field.changeSelection(row, column, false, false)
        validation.reject(field, "$owner — $message")
    }

    private fun checkedMappings(
        owner: String,
        rows: List<Pair<String, String>>,
        table: JTable,
        allowEmpty: Boolean
    ): MutableMap<String, String> {
        val values = java.util.TreeMap<String, String>()
        val title = table.accessibleContext.accessibleName
        rows.forEachIndexed { row, (rawName, rawValue) ->
            val name = rawName.trim()
            fun invalid(column: Int, problem: String): Nothing =
                reject(owner, table, "$title, row ${row + 1}: $problem", row, column)
            if (name.isEmpty()) invalid(0, "Enter a name, or remove this row.")
            if (allowEmpty && !name.matches(Regex("[A-Za-z_][A-Za-z_0-9]*")))
                invalid(
                    0,
                    "Names must start with a letter or underscore and contain only letters, digits or underscores."
                )
            if (values.containsKey(name)) invalid(
                0,
                "This name is already used in an earlier row. Rename or remove the duplicate."
            )
            if (!allowEmpty && rawValue.trim().isEmpty()) invalid(1, "Choose a local source file, or remove this row.")
            if (!allowEmpty) try {
                Paths.get(rawValue.trim())
            } catch (_: InvalidPathException) {
                invalid(1, "Use a valid local source file path.")
            }
            values[name] = if (allowEmpty) rawValue else rawValue.trim()
        }
        return values
    }

    /** Validate a detached draft; failures reveal and focus the offending root, section, and input. */
    private fun checkedOptions(owner: String, draft: Draft): DbtRunOptions {
        val options = draft.options.copy()
        if (options.executable.isEmpty()) reject(
            owner,
            executable,
            "Executable or tool: enter a command or choose an executable."
        )
        if (options.artifactDirectory.isEmpty()) reject(
            owner,
            artifacts,
            "Artifact directory: enter an output directory, such as target."
        )
        val paths = listOf(
            Triple(projectRoot, "Project root override", options.projectRoot),
            Triple(profiles, "Profiles directory", options.profilesDirectory),
            Triple(artifacts, "Artifact directory", options.artifactDirectory),
            Triple(manifest, "Manifest file", options.manifestPath),
            Triple(stateDirectory, "Baseline state directory", options.stateDirectory),
            Triple(profilesFile, "Profiles file for navigation", options.profilesFile),
            Triple(catalog, "Catalog file", options.catalogPath),
            Triple(stateManifest, "State manifest for navigation", options.stateManifestPath)
        ) +
                (if (options.engine == "v2") listOf(
                    Triple(
                        infoSchema,
                        "Information Schema v1 directory",
                        options.infoSchemaDirectory
                    )
                ) else emptyList())
        paths.forEach { (field, label, path) ->
            try {
                Paths.get(path)
            } catch (_: InvalidPathException) {
                reject(owner, field, "$label: enter a valid local path.")
            }
        }

        options.previewLimit = previewLimit(draft.preview)
            ?: reject(owner, limit, "Preview row limit: enter a whole number from 1 to 1000.")
        DbtVariableOverrides.parse(options.variables)
            .onFailure { reject(owner, variables, "Variables (JSON): " + it.message.orEmpty()) }
        options.environmentVariables =
            checkedMappings(owner, draft.environment, environmentVariables, allowEmpty = true)
        options.builtinSources = checkedMappings(owner, draft.builtins, builtinSources, allowEmpty = false)
        if (options.defer && options.engine != "core") reject(
            owner,
            defer,
            "Deferral requires Engine set to dbt Core. Clear deferral or choose dbt Core."
        )
        if (options.favorState && (!options.defer || options.engine != "core" || !DbtRoot.supportsVersion(
                version(
                    owner,
                    options.dbtVersion
                ), 4
            ))
        )
            reject(
                owner,
                favorState,
                "Favor baseline relations requires deferral and dbt Core 1.4 or later. Clear the option or restore its prerequisites."
            )
        if (options.formatOnSave && options.formatterExecutable.isEmpty())
            reject(
                owner,
                formatterExecutable,
                "Formatter executable: enter a command, or clear Format dbt SQL on save."
            )
        if (options.defer) {
            if (options.stateDirectory.isEmpty()) reject(
                owner,
                stateDirectory,
                "Baseline state directory: choose a directory containing manifest.json before enabling deferral."
            )
            try {
                val execution = Paths.get(owner).resolve(options.projectRoot).normalize()
                val state = execution.resolve(options.stateDirectory).normalize()
                DbtState.separate(state, execution.resolve(options.artifactDirectory))
                    .onFailure { reject(owner, stateDirectory, it.message.orEmpty()) }
                val manifestPath = state.resolve("manifest.json")
                val localManifest = rootDirectory(owner)?.takeIf { manifestPath.startsWith(Paths.get(owner)) }
                    ?.findFileByRelativePath(Paths.get(owner).relativize(manifestPath).toString())
                if (!Files.isRegularFile(manifestPath) && localManifest?.isDirectory != false)
                    reject(
                        owner,
                        stateDirectory,
                        "Baseline state directory: choose a directory containing manifest.json."
                    )
            } catch (_: InvalidPathException) {
                reject(owner, stateDirectory, "Baseline state directory: use a valid local directory path.")
            }
        }

        return options
    }

    override fun apply() {
        validation.clear()
        listOf(upstream, downstream).forEach { spinner ->
            try {
                spinner.commitEdit()
            } catch (_: java.text.ParseException) {
                reject(
                    selectedRoot,
                    spinner,
                    spinner.accessibleContext.accessibleName + ": enter a number within the allowed range."
                )
            }
        }
        drafts += selectedRoot to readDraft()
        val checked =
            drafts.toList().sortedBy { it.first }.associate { (owner, draft) -> owner to checkedOptions(owner, draft) }
        if (integrations.isModified()) integrations.apply()
        settings.getState().projects = java.util.TreeMap(checked)
        settings.getState().upstreamDepth = (upstream.value as Number).toInt()
        settings.getState().downstreamDepth = (downstream.value as Number).toInt()
        settings.getState().direction = direction.selectedItem.toString()
        settings.changed()
        baseline = snapshot()
    }


    companion object {
        internal fun formPanel(): JPanel = object : JPanel(GridBagLayout()), Scrollable {
            override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
            override fun getScrollableTracksViewportWidth(): Boolean = true
            override fun getScrollableTracksViewportHeight(): Boolean = false
            override fun getScrollableUnitIncrement(visible: Rectangle, orientation: Int, direction: Int): Int =
                JBUI.scale(16)

            override fun getScrollableBlockIncrement(visible: Rectangle, orientation: Int, direction: Int): Int =
                if (orientation == SwingConstants.VERTICAL) visible.height else visible.width
        }

        internal fun separator(title: String): TitledSeparator {
            val result = TitledSeparator(title)
            result.label.border = JBUI.Borders.emptyRight(4)
            return result
        }

        fun showIntegrations(project: Project) {
            val configurable = DbtSettingsConfigurable(project)
            ShowSettingsUtil.getInstance()
                .editConfigurable(project, configurable, Runnable { configurable.tabs.selectedIndex = 1 })
        }
    }

}

/** Native field errors share the settings UI lifetime, including their focus and accessible descriptions. */
private class DbtSettingsValidation {
    private val lifetime = Disposer.newDisposable("dbt Settings validation")
    private var disposed = false
    private var descriptions = emptyMap<JComponent, String?>()

    fun clear() {
        descriptions.forEach { (field, description) ->
            ComponentValidator.getInstance(field).ifPresent { it.updateInfo(null) }
            field.accessibleContext.accessibleDescription = description
        }
        descriptions = emptyMap()
    }

    fun reject(field: JComponent, message: String): Nothing {
        val input = DbtUi.input(field)
        descriptions += input to input.accessibleContext.accessibleDescription
        ComponentValidator.getInstance(input).orElseGet { ComponentValidator(lifetime).installOn(input) }
            .updateInfo(ValidationInfo(com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(message), input))
        input.accessibleContext.accessibleDescription = message
        SwingUtilities.invokeLater {
            if (!disposed && input.isShowing) {
                val location = if (input is JTable && input.selectedRow >= 0) input.getCellRect(
                    input.selectedRow,
                    input.selectedColumn.coerceAtLeast(0),
                    true
                )
                else Rectangle(input.size)
                input.scrollRectToVisible(location)
                input.requestFocusInWindow()
            }
        }
        throw ConfigurationException(message)
    }

    fun dispose() {
        if (!disposed) {
            clear(); disposed = true; Disposer.dispose(lifetime)
        }
    }
}

/**
 * Edits Metabase, local MCP, and Airflow discovery settings in the Integrations tab.
 *
 * Credentials pass through [DbtConsumers] to Password Safe rather than settings XML. Enabling MCP
 * permits an explicit server start; applying a disabled setting stops the running server.
 * Airflow paths configure local discovery, while the web URL is used only for navigation links.
 */
class DbtConsumersConfigurable(private val project: Project, private val onError: () -> Unit = {}) : Configurable {
    private val validation = DbtSettingsValidation()
    private val url = JBTextField()
    private val secret = JPasswordField()
    private val agent = JBCheckBox("Enable local read-only MCP server")
    private val airflowPaths = DefaultListModel<String>()
    private val airflowFolders = JBList<String>(airflowPaths)
    private val airflowUrl = JBTextField()
    private val panel = DbtSettingsConfigurable.formPanel()

    init {
        panel.border = JBUI.Borders.empty(11, 16, 16, 16)
    }

    private fun help(text: String): JBLabel {
        val label = JBLabel("<html>" + text + "</html>")
        label.setForeground(UIUtil.getContextHelpForeground())
        label.setAllowAutoWrapping(true).setCopyable(true)
        return label
    }

    private fun fieldWithHelp(field: JComponent, text: String): JPanel {
        val result = object : JPanel(BorderLayout(0, JBUI.scale(4))) {
            override fun getMinimumSize(): Dimension = Dimension(0, preferredSize.height)
        }
        result.add(field, BorderLayout.NORTH)
        result.add(help(text), BorderLayout.CENTER)
        result.putClientProperty("dbt.input", field)
        DbtUi.input(field).accessibleContext.setAccessibleDescription(text)
        return result
    }

    private fun section(title: String, fields: List<Pair<String, JComponent>>): JPanel {
        val result = JPanel(BorderLayout(0, JBUI.scale(8)))
        val form = JPanel(GridBagLayout())
        result.add(DbtSettingsConfigurable.separator(title), BorderLayout.NORTH)
        DbtUi.labeledRows(form, JBUI.insets(4, 5, 4, 10), fields)
        val layout = form.layout as GridBagLayout
        form.components.filterIsInstance<JLabel>().filter { it.text.isNotEmpty() }.forEach { label ->
            val constraints = layout.getConstraints(label)
            constraints.anchor = GridBagConstraints.FIRST_LINE_START
            layout.setConstraints(label, constraints)
        }
        result.add(form, BorderLayout.CENTER)
        return result
    }

    private fun stack(rows: List<JComponent>) {
        val labels = rows.flatMap { section ->
            section.components.filterIsInstance<JPanel>()
                .filter { it !is TitledSeparator && it.layout is GridBagLayout }
                .flatMap { form -> form.components.filterIsInstance<JLabel>().filter { it.text.isNotEmpty() } }
        }
        val labelWidth = labels.maxOf { it.preferredSize.width }
        labels.forEach { label -> label.preferredSize = Dimension(labelWidth, label.preferredSize.height) }
        rows.forEachIndexed { row, field ->
            val c = GridBagConstraints()
            c.gridx = 0; c.gridy = row; c.weightx = 1.0
            c.fill = GridBagConstraints.HORIZONTAL
            c.anchor = GridBagConstraints.NORTHWEST
            c.insets = JBUI.insets(if (row == 0) 4 else 12, 0, 4, 0)
            panel.add(field, c)
        }

        val filler = GridBagConstraints()
        filler.gridy = rows.size
        filler.weighty = 1.0
        panel.add(JPanel(), filler)
    }

    private val metabase = section(
        "Metabase", listOf(
            "Server URL" to fieldWithHelp(
                url,
                "Optional. Use the final HTTP or HTTPS server URL, without embedded credentials."
            ),
            "API key" to fieldWithHelp(
                secret,
                "Stored in IDE Password Safe. Clear the field and click Apply to remove the saved key."
            )
        )
    )

    init {
        secret.setToolTipText("Stored in IDE Password Safe. Clear this field and click Apply to remove the saved API key.")
        airflowFolders.setVisibleRowCount(3)
        airflowFolders.emptyText.setText("No DAG folders configured")
        airflowFolders.setToolTipText("DAG folders or Airflow project directories, scanned recursively for Python files. Relative paths use the IDEA project directory.")
        airflowUrl.setToolTipText("Optional HTTP or HTTPS Airflow web URL, without credentials. Used only for browser links.")
    }

    private val folderPanel = ToolbarDecorator.createDecorator(airflowFolders).disableUpDownActions()
        .setAddActionName("Add folder…").setRemoveActionName("Remove folder")
        .setAddAction {
            FileChooser.chooseFiles(
                FileChooserDescriptorFactory.multiDirs().withTitle("Select local Airflow DAG folders or projects"),
                project, null
            ).forEach { f -> if (!airflowPaths.contains(f.path)) airflowPaths.addElement(f.path) }
        }
        .setRemoveAction {
            airflowFolders.selectedIndices.reversed().forEach { airflowPaths.remove(it) }
        }.createPanel()

    init {
        folderPanel.putClientProperty("dbt.input", airflowFolders)
    }

    private val airflow = section(
        "Airflow — local DAG discovery", listOf(
            "DAG folders" to fieldWithHelp(
                folderPanel,
                "Scan local folders and Airflow checkouts recursively. Remove all folders to stop discovery."
            ),
            "Web URL" to fieldWithHelp(
                airflowUrl,
                "Optional. Used only to open detected DAGs and tasks in your browser."
            )
        )
    )
    private val mcp = section(
        "AI assistants", listOf(
            "" to fieldWithHelp(
                agent,
                "Start the server explicitly from Tools → dbt Local MCP Server. Disabling this option stops it."
            )
        )
    )

    init {
        stack(listOf(metabase, airflow, mcp))
    }

    private val settings get() = project.getService(DbtConsumersSettings::class.java).getState()

    private val service get() = project.getService(DbtConsumers::class.java)

    private val folderValues: List<String> get() = (0 until airflowPaths.size()).map { airflowPaths.get(it) }

    override fun getDisplayName(): String = "Integrations"

    override fun createComponent(): JComponent {
        reset();
        return panel
    }

    override fun isModified(): Boolean =
        url.text != settings.metabaseUrl || agent.isSelected != settings.agentEnabled ||
                String(secret.password) != service.apiKey || folderValues != settings.airflowPaths.toList() || airflowUrl.text.trim() != settings.airflowUrl

    override fun apply() {
        validation.clear()
        fun invalid(field: JComponent, message: String): Nothing {
            onError(); validation.reject(field, message)
        }

        val value = url.text.trim()
        if (value.isNotEmpty() && DbtAirflow.safeUrl(value) == null) invalid(
            url,
            "Metabase Server URL: use an HTTP or HTTPS URL without embedded credentials."
        )
        val airflowLink = airflowUrl.text.trim()
        if (airflowLink.isNotEmpty() && DbtAirflow.safeUrl(airflowLink) == null)
            invalid(airflowUrl, "Airflow Web URL: use an HTTP or HTTPS URL without embedded credentials.")
        folderValues.forEachIndexed { row, path ->
            try {
                Paths.get(path)
            } catch (_: InvalidPathException) {
                airflowFolders.selectedIndex = row
                invalid(airflowFolders, "DAG folders, row ${row + 1}: use a valid local directory path.")
            }
        }

        settings.airflowPaths = java.util.ArrayList<String>(folderValues)
        settings.airflowUrl = airflowLink
        DbtProjectSettings.get(project).changed()
        settings.metabaseUrl = value;
        settings.agentEnabled = agent.isSelected
        service.setSecret(String(secret.password))
        service.clear();
        if (!agent.isSelected) project.getService(DbtAgent::class.java).stop()
        else DbtAgentServiceView.changed(project)
    }

    override fun reset() {
        validation.clear()
        url.setText(settings.metabaseUrl);
        agent.setSelected(settings.agentEnabled);
        secret.setText(service.apiKey)
        airflowPaths.clear();
        settings.airflowPaths.forEach(airflowPaths::addElement)
        airflowUrl.setText(settings.airflowUrl)
    }

    override fun disposeUIResources() {
        secret.setText(""); validation.dispose()
    }
}
