package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project.*
import com.intellij.execution.*
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.*
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.impl.RunDialog
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.*
import com.intellij.execution.ui.*
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import com.intellij.ui.components.*
import com.intellij.util.execution.ParametersListUtil
import com.intellij.util.ui.FormBuilder
import java.awt.BorderLayout
import java.io.OutputStream
import javax.swing.*
import org.jdom.Element

class DbtConfigurationType : ConfigurationType {
    private val factory = object : ConfigurationFactory(this) {
        override fun getId(): String = "Dbt"
        override fun createTemplateConfiguration(project: Project): RunConfiguration =
            DbtRunConfiguration(project, this, "dbt").apply { action = "Run" }
    }

    override fun getId(): String = "Dbt"
    override fun getDisplayName(): String = "dbt"
    override fun getConfigurationTypeDescription(): String = "Run dbt commands, previews, data and unit tests"
    override fun getIcon(): javax.swing.Icon = DbtIcons.Gutter
    override fun getConfigurationFactories(): Array<ConfigurationFactory> = arrayOf(factory)
}

/**
 * Persisted native Run configuration with per-run execution overrides.
 * Captured commands retain one session's prepared context. Reviewed CLI identities are reused only
 * while their source/settings key matches; neither is persisted with the configuration.
 */
class DbtRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    RunConfigurationBase<Element>(project, factory, name) {
    var root = "";
    var selectors = emptyList<String>();
    var action = "Run";
    var exclude = emptyList<String>();
    var fullRefresh = false
    var exactTests = false;
    var namedSelector = "";
    var scope = "";
    var inlineSql = "";
    var target = "";
    var executable = "";
    var executableArguments = "";
    var profilesDirectory = ""
    internal var cliSelection: Pair<List<String>, List<String>>? = null

    // Commands launched from context actions and failed-test reruns retain their captured execution context.
    internal var selectionResolved: ((List<String>) -> Unit)? = null
    internal var captured: DbtCommand? = null;
    internal var completed: ((DbtCommand) -> Unit)? = null
    override fun getConfigurationEditor(): SettingsEditor<DbtRunConfiguration> = DbtRunEditor(project)
    override fun checkConfiguration() {
        if (root.isEmpty()) throw RuntimeConfigurationError("Choose the owning dbt project directory.")
        if (DbtCommandAction.fromLabel(action) == null) throw RuntimeConfigurationError("Choose a dbt command.")
    }

    /** Identifies the source, configuration and artifact context for one reviewed CLI selection. */
    internal fun sourceKey(w: DbtWorkspace): List<String> = listOf(
        root,
        scope,
        inlineSql,
        action,
        selectors.joinToString(" "),
        exclude.joinToString(" "),
        namedSelector,
        target,
        executable,
        executableArguments,
        profilesDirectory,
        fullRefresh.toString(),
        DbtProjectSettings.get(project).modificationCount.toString()
    ) +
            configuredRoot(w).getOrNull()?.let(DbtArtifactContext::identity).orEmpty() + w.files.filter { f ->
        w.root(f)?.let { !it.excluded(f.virtualFile) } == true
    }
        .sortedBy { it.virtualFile.path }.flatMap { listOf(it.virtualFile.path, it.modificationStamp.toString()) }

    internal fun configuredRoot(w: DbtWorkspace): Result<DbtRoot> = dbtResult {
        val owner = w.roots.find { it.directory.path == root }?.let(w::executionRoot) ?: throw IllegalArgumentException(
            "The owning dbt project is unavailable."
        )
        val options = owner.runOptions.copy()
        if (target.isNotEmpty()) options.target = target
        if (executable.isNotEmpty()) {
            options.executable = executable; options.executableArguments = executableArguments
        }
        if (profilesDirectory.isNotEmpty()) options.profilesDirectory = profilesDirectory
        options.selection = if (namedSelector.isNotEmpty()) "selector:" + namedSelector else selectors.joinToString(" ")
        options.exclude = exclude.joinToString(" "); owner.withRunOptions(options)
    }

    /**
     * Resolves the command and its test definitions under workspace read access, without starting a process.
     * Reuses reviewed CLI identities only while source/settings match; unavailable scopes and incomplete
     * exact-test reruns fail instead of widening scope.
     */
    internal fun prepare(): Result<Pair<DbtCommand, List<DbtTestDefinition>>> = dbtResult {
        val w = DbtWorkspace.get(project);
        val owner = configuredRoot(w).getOrThrow()
        val selected =
            (if (scope.isEmpty()) DbtRunSelection.all(w, owner.directory.path) else DbtRunSelection.available(w)
                .find { s ->
                    s.path == scope &&
                            DbtRunSelection.owner(w, s)
                                ?.let { w.executionRoot(it).directory == owner.directory } == true
                })
                ?: throw IllegalArgumentException("Choose an available model or directory in the owning dbt project.")
        val definitions = DbtTestDiscovery.discover(w, mapOf(owner.directory.path to owner.runOptions))
            .filter { it.root == owner.directory.path }
        val base = captured ?: run {
            val ids = cliSelection?.takeIf { it.first == sourceKey(w) }?.second
            when {
                action == "Resolve Selection" -> DbtCommand.resolveSelection(w, owner, selected)
                exactTests -> DbtCommand.create(w, selected, action, fullRefresh, owner.runOptions)
                selectors.isNotEmpty() || exclude.isNotEmpty() || namedSelector.isNotEmpty() -> DbtCommand.selection(
                    w,
                    owner,
                    action,
                    fullRefresh,
                    selected,
                    ids
                )

                else -> DbtCommand.create(w, selected, action, fullRefresh, owner.runOptions)
            }.getOrThrow()
        }
        val preview = if (inlineSql.isEmpty() || captured != null) base else {
            require(action == "Preview" && !selected.directory) { "Inline SQL requires Preview and one source model." }
            val model =
                w.model(selected.path) ?: throw IllegalArgumentException("The preview source model is unavailable.")
            val query = DbtCteInspection.command(w, model, inlineSql).getOrThrow()
            base.copy(
                request = base.request.copy(scope = query.request.scope, debugSql = true),
                previewSource = query.previewSource
            )
        }
        val command = if (!exactTests) preview else {
            val intended = definitions.filter { it.selector in selectors }
            require(selectors.isNotEmpty()) { "Exact test identities unavailable. Generate Docs for this project and target, then select the tests again." }
            require(selectors.none { s -> intended.none { it.selector == s } }) { "A selected test is no longer available. Select its declaration again." }
            DbtTestDiscovery.select(preview, intended).getOrThrow()
        }
        command to definitions
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState =
        object : RunProfileState {
            override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
                FileDocumentManager.getInstance().saveAllDocuments()
                return if ((captured?.action ?: action) in setOf("Test", "Build")) {
                    val session = DbtTestConsole(this@DbtRunConfiguration, executor)
                    prepareAsync(session.view) { result ->
                        result.fold({ (command, definitions) ->
                            session.start(
                                command,
                                definitions
                            )
                        }, { session.fail(it.message.orEmpty()) })
                    }
                    DefaultExecutionResult(
                        session.view,
                        session.process
                    ).apply { setRestartActions(session.properties.createRerunFailedTestsAction(session.view)) }
                } else {
                    val session = DbtCommandConsole(this@DbtRunConfiguration)
                    prepareAsync(session) { result ->
                        result.fold(
                            { (command, _) -> session.start(command) },
                            { session.fail(it.message.orEmpty()) })
                    }
                    DefaultExecutionResult(session, session.process, *session.console.createConsoleActions())
                }
            }
        }

    /** Prepares a configuration clone in a committed smart read; disposing [parent] cancels pending work. */
    private fun prepareAsync(parent: Disposable, done: (Result<Pair<DbtCommand, List<DbtTestDefinition>>>) -> Unit) {
        val snapshot = clone() as DbtRunConfiguration
        DbtUi.readLater(parent, smartIn = project, committedIn = project, read = { snapshot.prepare() }, done = done)
    }

    override fun writeExternal(element: Element) {
        super.writeExternal(element)
        listOf(
            "dbtRoot" to root,
            "scope" to scope,
            "target" to target,
            "executable" to executable,
            "arguments" to executableArguments,
            "profilesDirectory" to profilesDirectory,
            "command" to action,
            "exactTests" to exactTests.toString(),
            "fullRefresh" to fullRefresh.toString(),
            "namedSelector" to namedSelector
        ).forEach { (k, v) -> element.setAttribute(k, v) }
        element.removeChildren("inlineSql"); if (inlineSql.isNotEmpty()) element.addContent(
            Element("inlineSql").setText(
                inlineSql
            )
        )
        listOf(
            "exclude" to exclude,
            "select" to selectors
        ).forEach { (name, terms) ->
            element.removeChildren(name); terms.forEach {
            element.addContent(
                Element(name).setAttribute(
                    "term",
                    it
                )
            )
        }
        }
    }

    override fun readExternal(element: Element) {
        super.readExternal(element)
        fun attribute(name: String, default: String = ""): String = element.getAttributeValue(name) ?: default
        fun terms(name: String): List<String> = element.getChildren(name).mapNotNull { it.getAttributeValue("term") }
        root = attribute("dbtRoot"); scope = attribute("scope"); inlineSql =
            element.getChildText("inlineSql").orEmpty(); target = attribute("target"); executable =
            attribute("executable")
        executableArguments = attribute("arguments"); profilesDirectory = attribute("profilesDirectory"); cliSelection =
            null; selectionResolved = null
        action = attribute("command", "Run"); exactTests = attribute("exactTests") == "true"; fullRefresh =
            attribute("fullRefresh") == "true"
        namedSelector = attribute("namedSelector"); exclude = terms("exclude"); selectors = terms("select"); captured =
            null
    }

    companion object {
        internal val factory: ConfigurationFactory
            get() = ConfigurationTypeUtil.findConfigurationType(
                DbtConfigurationType::class.java
            ).configurationFactories.first()

        internal fun terms(text: String): List<String> = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        private fun create(
            project: Project,
            action: String,
            root: String
        ): Pair<RunnerAndConfigurationSettings, DbtRunConfiguration> {
            val settings = RunManager.getInstance(project)
                .createConfiguration("dbt " + action.lowercase() + " · " + DbtUi.name(root), factory)
            return settings to settings.configuration as DbtRunConfiguration
        }

        fun openEvidence(project: Project, key: String) {
            val parts = key.split('|', limit = 3)
            if (parts.size < 2) return
            DbtUi.readLater(project, read = {
                val w = DbtWorkspace.get(project)
                w.roots.find { it.directory.path == parts[0] }?.let(w::executionRoot)?.let { owner ->
                    val tests = DbtTestDiscovery.discover(w)
                        .filter { it.root == owner.directory.path && (it.id == parts[1] || parts[1] in it.dependencies) }
                    val ids = tests.map { it.id }.toSet() + parts[1];
                    val runs = project.getService(DbtExecutionEvidence::class.java).runs(owner)
                    val run =
                        runs.find { it.results.any { r -> r.id in ids } } ?: runs.firstOrNull() ?: DbtExecutionRun(
                            emptyList(),
                            "",
                            "",
                            "",
                            "",
                            emptyList(),
                            "No execution evidence available"
                        )
                    Triple(owner.directory.path, tests, run.copy(results = run.results.filter { it.id in ids }))
                }
            }) { value ->
                value?.let { (root, tests, execution) ->
                    val configuration = DbtRunConfiguration(project, factory, "dbt recorded results").apply {
                        this.root = root; action = "Test"; selectors =
                        tests.map { it.selector }.distinct(); exactTests = true
                    }
                    val executor = DefaultRunExecutor.getRunExecutorInstance();
                    val session = DbtTestConsole(configuration, executor)
                    val environment = ExecutionEnvironmentBuilder.create(executor, configuration).build()
                    RunContentBuilder(
                        DefaultExecutionResult(session.view, session.process),
                        environment
                    ).showRunContent(null); session.showEvidence(execution, tests)
                }
            }
        }

        fun edit(project: Project, root: String, action: String, selection: String = "", excluded: String = "") {
            val (settings, configuration) = create(project, action, root)
            configuration.root = root; configuration.action = action; configuration.selectors =
                terms(selection); configuration.exclude = terms(excluded)
            if (RunDialog.editConfiguration(project, settings, "dbt run configuration")) {
                val manager =
                    RunManager.getInstance(project); manager.addConfiguration(settings); manager.selectedConfiguration =
                    settings
            }
        }

        /** Selection review must complete while its modal configuration editor is open. */
        internal fun resolveSelection(configuration: DbtRunConfiguration) {
            val executor = DefaultRunExecutor.getRunExecutorInstance();
            val environment =
                ExecutionEnvironmentBuilder.create(executor, configuration).build(); environment.assignNewExecutionId()
            val result = configuration.getState(executor, environment).execute(executor, environment.runner)
            RunContentBuilder(result!!, environment).showRunContent(null)
        }

        internal fun launch(project: Project, command: DbtCommand, completed: ((DbtCommand) -> Unit)? = null) {
            val (settings, configuration) = create(project, command.action, command.root)
            configuration.root = command.root; configuration.captured = command; configuration.completed =
                completed; configuration.target = command.target
            configuration.executable = command.prefix.firstOrNull().orEmpty(); configuration.executableArguments =
                ParametersListUtil.join(command.prefix.drop(1))
            configuration.profilesDirectory = command.environment["DBT_PROFILES_DIR"].orEmpty(); configuration.scope =
                command.previewSource?.path.orEmpty()
            if (command.action == "Preview" && configuration.scope.isEmpty()) configuration.scope = DbtUi.read {
                val w = DbtWorkspace.get(project)
                DbtRunSelection.available(w).find { s ->
                    !s.directory && DbtRunSelection.selectors(w, s)
                        .any { term -> (command.request.scope as? DbtCommandScope.Selected)?.terms?.contains(term) == true }
                }?.path.orEmpty()
            }
            configuration.action = command.action; configuration.fullRefresh =
                command.request.fullRefresh; configuration.exclude = command.request.exclude
            when (val scope = command.request.scope) {
                is DbtCommandScope.Selected -> configuration.selectors =
                    scope.terms; is DbtCommandScope.Named -> configuration.namedSelector = scope.name
                is DbtCommandScope.Inline -> configuration.inlineSql = scope.sql; else -> Unit
            }
            val manager =
                RunManager.getInstance(project); manager.setTemporaryConfiguration(settings); manager.selectedConfiguration =
                settings
            ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
        }
    }
}

class DbtRunProducer : LazyRunConfigurationProducer<DbtRunConfiguration>() {
    override fun getConfigurationFactory(): ConfigurationFactory = DbtRunConfiguration.factory
    public override fun setupConfigurationFromContext(
        configuration: DbtRunConfiguration,
        context: ConfigurationContext,
        source: Ref<PsiElement>
    ): Boolean {
        val tests = DbtTestRunLineMarker.testsAt(source.get())?.takeIf { it.isNotEmpty() }
        if (tests != null) {
            if (tests.any { it.selector.isEmpty() }) return false
            configuration.root = tests.first().root; configuration.action = "Test"; configuration.selectors =
                tests.map { it.selector }.distinct(); configuration.exactTests = true
            configuration.name = if (tests.size == 1) tests.first().name else "dbt tests"; return true
        }
        val w = DbtWorkspace.get(configuration.project)
        val file = context.dataContext.getData(CommonDataKeys.VIRTUAL_FILE) ?: source.get()?.containingFile?.virtualFile
        ?: return false
        val selected = if (file.isDirectory) DbtRunSelection.available(w).find { it.path == file.path }
            ?.let { s -> DbtRunSelection.owner(w, s)?.let { it to file.path } }
        else w.model(file.path)?.takeIf { it.isEnabled }?.let(w::owner)?.takeUnless { it.excluded(file) }
            ?.let { it to file.path }
        val (owner, path) = selected ?: return false;
        val root = w.executionRoot(owner)
        configuration.root = root.directory.path; configuration.scope = path; configuration.action =
            "Run"; configuration.name = "dbt run · " + DbtUi.name(path); return true
    }

    override fun isConfigurationFromContext(
        configuration: DbtRunConfiguration,
        context: ConfigurationContext
    ): Boolean {
        val candidate = DbtRunConfiguration(configuration.project, configurationFactory, "");
        val source = Ref<PsiElement>(context.psiLocation)
        return setupConfigurationFromContext(
            candidate,
            context,
            source
        ) && candidate.root == configuration.root && candidate.scope == configuration.scope && candidate.action == configuration.action && candidate.selectors == configuration.selectors
    }
}

class DbtRunEditor(private val project: Project) : SettingsEditor<DbtRunConfiguration>() {
    private val root = TextFieldWithBrowseButton();
    private val scope = TextFieldWithBrowseButton();
    private val inline = JBTextArea(4, 50)
    private val target = JBTextField();
    private val executable = TextFieldWithBrowseButton();
    private val arguments = JBTextField();
    private val profiles = TextFieldWithBrowseButton()
    private val selectors = JBTextField();
    private val excluded = JBTextField();
    private val named = JBTextField()
    private val action = JComboBox((DbtCommand.Actions + "Resolve Selection").toTypedArray());
    private val refresh = JCheckBox("Full Refresh (Run / Build)")
    private val effective = JBLabel();
    private val inlineLabel = JBLabel("Inline SQL:");
    private val inlinePane = JBScrollPane(inline)
    private val overrides = JPanel(BorderLayout());
    private val showOverrides = JCheckBox("Show execution overrides")
    private lateinit var configuration: DbtRunConfiguration;
    private var resolved: Pair<List<String>, List<String>>? = null;
    private var disposed = false

    init {
        overrides.isVisible = false; showOverrides.addActionListener { overrides.isVisible = showOverrides.isSelected }
        action.addActionListener { updateControls() }
        listOf(selectors, excluded, named, scope.textField, executable.textField).forEach {
            it.document.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
                override fun textChanged(event: javax.swing.event.DocumentEvent) {
                    updateControls()
                }
            })
        }
        root.addBrowseFolderListener(
            project,
            FileChooserDescriptorFactory.singleDir().withTitle("Choose owning dbt project")
        )
        scope.addBrowseFolderListener(
            project,
            FileChooserDescriptorFactory.singleFileOrDir().withTitle("Choose dbt model or directory")
        )
        executable.addBrowseFolderListener(
            project,
            FileChooserDescriptorFactory.singleFile().withTitle("Choose dbt executable or wrapper")
        )
        profiles.addBrowseFolderListener(
            project,
            FileChooserDescriptorFactory.singleDir().withTitle("Choose profiles directory")
        )
        listOf(selectors, excluded).forEach { field ->
            field.toolTipText =
                "dbt selector expression. Press Ctrl+Space for names, tags, packages and named selectors."
            object : DumbAwareAction("Complete dbt selection") {
                override fun actionPerformed(event: AnActionEvent) {
                    val text = field.text;
                    val caret = field.caretPosition;
                    val source = draft()
                    DbtUi.readLater(this@DbtRunEditor, read = {
                        val w = DbtWorkspace.get(project)
                        source.configuredRoot(w)
                            .map { DbtSelectionReview.complete(text, caret, DbtSelectionReview.completions(w, it)) }
                            .getOrDefault(emptyList())
                    }) { choices ->
                        if (field.text == text && choices.isNotEmpty()) JBPopupFactory.getInstance()
                            .createPopupChooserBuilder(choices.map { it.first }).setTitle("dbt selectors")
                            .setItemChosenCallback { chosen ->
                                choices.find { it.first == chosen }
                                    ?.let { (_, value, next) -> field.text = value; field.caretPosition = next }
                            }.createPopup().showUnderneathOf(field)
                    }
                }
            }.registerCustomShortcutSet(
                ActionManager.getInstance().getAction(IdeActions.ACTION_CODE_COMPLETION).shortcutSet, field, this
            )
        }
    }

    private fun updateControls() {
        val command = action.selectedItem.toString();
        val whole = command in setOf("Generate Docs", "Source Freshness");
        val preview = command == "Preview"
        val hasNamed = named.text.trim().isNotEmpty();
        val hasSelection = selectors.text.trim().isNotEmpty()
        scope.isEnabled = !whole && (preview || !hasNamed && !hasSelection); selectors.isEnabled =
            !whole && !preview && !hasNamed
        named.isEnabled = !whole && !preview; excluded.isEnabled = !whole && !preview; refresh.isVisible =
            command in setOf("Run", "Build")
        inlineLabel.isVisible = preview; inlinePane.isVisible = preview; arguments.isEnabled =
            executable.text.trim().isNotEmpty()
        val selected =
            if (whole) "Whole execution project" else if (!preview && hasNamed) "Named selector: " + named.text.trim()
            else if (!preview && hasSelection) "Selection: " + selectors.text.trim() else scope.text.trim()
                .ifEmpty { "Whole execution project" }
        effective.text = "Effective scope: " + selected + if (!whole && !preview && excluded.text.trim()
                .isNotEmpty()
        ) " · Exclude: " + excluded.text.trim() else ""
        effective.toolTipText = effective.text
    }

    private fun draft(): DbtRunConfiguration = (configuration.clone() as DbtRunConfiguration).also(::applyEditorTo)
    override fun createEditor(): JComponent {
        (scope.textField as JBTextField).emptyText.text =
            "All models; or an absolute model/directory path"; target.emptyText.text =
            "Project setting / profile default"
        (executable.textField as JBTextField).emptyText.text = "Use project executable and arguments"
        arguments.toolTipText =
            "Prefix arguments for the overridden executable, e.g. run dbt for uv. Shell expressions are not evaluated."
        val review = DbtUi.button("Review selection") { DbtSelectionResults.review(draft()) }
        val resolve = DbtUi.button("Resolve with dbt ls") {
            val source = draft();
            val key = DbtUi.read { source.sourceKey(DbtWorkspace.get(project)) };
            val resolving = source.clone() as DbtRunConfiguration
            resolving.action = "Resolve Selection"; resolving.name = "dbt ls · " + DbtUi.name(source.root)
            resolving.selectionResolved = { ids ->
                if (!disposed) {
                    val current = draft();
                    val now = DbtUi.read { current.sourceKey(DbtWorkspace.get(project)) }; if (key == now) resolved =
                        key to ids
                }
            }
            DbtRunConfiguration.resolveSelection(resolving)
        }
        val compare = DbtUi.button("Compare baseline") { DbtStateComparison.show(draft()) };
        val buttons = DbtUi.buttons(review, resolve, compare)
        val form = FormBuilder.createFormBuilder().addLabeledComponent("Owning dbt root:", root)
            .addLabeledComponent("Scope:", scope).addLabeledComponent("Command:", action)
            .addLabeledComponent("Target:", target).addLabeledComponent("Selection:", selectors)
            .addLabeledComponent("Exclude:", excluded)
            .addLabeledComponent("Named selector (overrides Selection):", named)
            .addComponent(effective).addComponent(refresh).addComponent(buttons)
            .addLabeledComponent(inlineLabel, inlinePane).addComponent(showOverrides).panel
        overrides.add(
            FormBuilder.createFormBuilder().addLabeledComponent("Executable override:", executable)
                .addLabeledComponent("Prefix arguments:", arguments)
                .addLabeledComponent("Profiles directory override:", profiles)
                .addComponent(JBLabel("Other execution defaults: Settings → dbt Workspace")).panel, BorderLayout.CENTER
        )
        val panel = JPanel(BorderLayout());
        val content = JPanel(BorderLayout()); content.add(form, BorderLayout.NORTH); content.add(
            overrides,
            BorderLayout.CENTER
        ); panel.add(content, BorderLayout.NORTH)
        updateControls(); return JBScrollPane(panel)
    }

    override fun resetEditorFrom(value: DbtRunConfiguration) {
        configuration = value; resolved = value.cliSelection; root.text = value.root; scope.text =
            value.scope; target.text = value.target; inline.text = value.inlineSql
        executable.text = value.executable; arguments.text = value.executableArguments; profiles.text =
            value.profilesDirectory; selectors.text = value.selectors.joinToString(" ")
        excluded.text = value.exclude.joinToString(" "); named.text = value.namedSelector; action.selectedItem =
            value.action; refresh.isSelected = value.fullRefresh
        showOverrides.isSelected =
            value.executable.isNotEmpty() || value.profilesDirectory.isNotEmpty(); overrides.isVisible =
            showOverrides.isSelected; updateControls()
    }

    override fun applyEditorTo(value: DbtRunConfiguration) {
        value.root = root.text.trim(); value.scope = scope.text.trim(); value.target =
            target.text.trim(); value.inlineSql = inline.text
        value.executable = executable.text.trim(); value.executableArguments = arguments.text; value.profilesDirectory =
            profiles.text.trim()
        val terms = DbtRunConfiguration.terms(selectors.text); if (terms != value.selectors || named.text.trim()
                .isNotEmpty()
        ) value.exactTests = false
        value.selectors = terms; value.exclude = DbtRunConfiguration.terms(excluded.text); value.namedSelector =
            named.text.trim(); value.action = action.selectedItem.toString()
        val whole = value.action in setOf("Generate Docs", "Source Freshness"); if (whole) value.scope = ""
        if (whole || value.action == "Preview") {
            value.exactTests = false; value.selectors = emptyList(); value.exclude = emptyList(); value.namedSelector =
                ""
        } else if (value.namedSelector.isNotEmpty()) value.selectors = emptyList()
        if (!whole && value.action != "Preview" && (value.selectors.isNotEmpty() || value.namedSelector.isNotEmpty())) value.scope =
            ""
        if (value.action != "Preview") value.inlineSql = ""
        value.fullRefresh = refresh.isSelected && value.action in setOf("Run", "Build"); value.captured =
            null; value.completed = null; value.cliSelection = resolved
    }

    override fun disposeEditor() {
        disposed = true; listOf(root, scope, executable, profiles).forEach { it.dispose() }; super.disposeEditor()
    }
}

/**
 * Native Run/Debug process handle for execution owned elsewhere.
 * Its owner decides whether Stop cancels an active runner or completes an unstarted handle with exit code 130.
 */
internal class DbtManagedProcess(private val stop: (DbtManagedProcess) -> Unit) : ProcessHandler() {
    fun complete(code: Int) {
        if (!isProcessTerminated) notifyProcessTerminated(code)
    }

    fun output(text: String, kind: Key<*>) {
        notifyTextAvailable(text, kind)
    }

    override fun getProcessInput(): OutputStream? = null
    override fun detachIsDefault(): Boolean = false
    override fun destroyProcessImpl() {
        stop(this)
    }

    override fun detachProcessImpl() {
        destroyProcessImpl()
    }
}

/**
 * Owns one native Run console, result grid and cancellation handle; only its captured command can complete it.
 * A limit rerun revalidates source and execution inputs before creating a fresh session.
 */
internal class DbtCommandConsole(private val configuration: DbtRunConfiguration) : ExecutionConsole {
    private val project = configuration.project;
    private val snapshot = configuration.clone() as DbtRunConfiguration;
    private val runner = project.getService(DbtRunner::class.java)
    val console: ConsoleView = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
    private val tabs = JTabbedPane();
    private val panel = JPanel(BorderLayout());
    private var active: DbtCommand? = null;
    private var canceled = false
    private val grid = DbtResultGrid(project) { limit ->
        active?.let { previous ->
            DbtUi.readLater(this, read = {
                dbtResult {
                    val fresh = snapshot.clone() as DbtRunConfiguration; fresh.captured = null;
                    val command = fresh.prepare().getOrThrow().first
                    val same =
                        command.artifactContext == previous.artifactContext && command.environment == previous.environment && command.sourceSql == previous.sourceSql &&
                                command.previewSource?.text == previous.previewSource?.text && command.copy(
                            request = command.request.copy(
                                limit = previous.request.limit
                            )
                        ).arguments == previous.arguments
                    require(same) { "Source or execution settings changed. Start a fresh preview before rerunning with a limit." }
                    command.copy(
                        request = command.request.copy(limit = limit),
                        previewSource = previous.previewSource ?: command.previewSource
                    )
                }
            }) { result ->
                result.fold(
                    { DbtRunConfiguration.launch(project, it) },
                    { console.print(it.message.orEmpty() + "\n", ConsoleViewContentType.ERROR_OUTPUT) })
            }
        }
    }
    private val evidence = DbtPreviewEvidencePanel(project)
    val process = DbtManagedProcess { p -> canceled = true; if (active != null) runner.stop() else p.complete(130) }

    init {
        tabs.addTab("Console", console.component); panel.add(
            tabs,
            BorderLayout.CENTER
        ); console.attachToProcess(process); Disposer.register(this, console); process.startNotify()
        runner.listen(this) { event ->
            if (active != null && event.command === active) {
                grid.setRunning(event.running)
                if (!event.running) {
                    console.print("\n" + event.message + "\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                    event.execution?.let { run ->
                        console.print(
                            "Target: ${run.target} · Invocation: ${run.invocation} · ${run.timestamp}\n${run.artifact}\n",
                            ConsoleViewContentType.SYSTEM_OUTPUT
                        )
                        if (run.problem.isNotEmpty()) console.print(
                            run.problem + "\n",
                            ConsoleViewContentType.ERROR_OUTPUT
                        )
                    }
                    evidence.showEvidence(event.previewEvidence)
                    event.result?.let { result ->
                        grid.showResult(result);
                        val rows = JPanel(BorderLayout()); rows.add(grid, BorderLayout.CENTER); rows.add(
                        evidence,
                        BorderLayout.SOUTH
                    ); tabs.addTab("Rows", rows); tabs.selectedComponent = rows
                    }
                    if (event.result == null && event.previewEvidence != null) panel.add(evidence, BorderLayout.SOUTH)
                    event.selectionOutput?.let { DbtSelectionResults.showCli(configuration, it) }; process.complete(
                        event.exitCode ?: if (canceled) 130 else 1
                    )
                    if (!canceled && event.exitCode == 0) active?.let { snapshot.completed?.invoke(it) }; panel.revalidate()
                }
            }
        }
    }

    fun start(command: DbtCommand) {
        if (!canceled && !process.isProcessTerminated) {
            active = command; console.print(command.commandLine + "\n", ConsoleViewContentType.SYSTEM_OUTPUT)
            if (!runner.start(command, process::output)) {
                active = null; fail("Another dbt command is running. Stop it in Run or Debug first.")
            }
        }
    }

    fun fail(problem: String) {
        console.print(problem + "\n", ConsoleViewContentType.ERROR_OUTPUT); process.complete(1)
    }

    override fun getComponent(): JComponent = panel
    override fun getPreferredFocusableComponent(): JComponent = console.preferredFocusableComponent
    override fun dispose() {
        if (!process.isProcessTerminated) process.destroyProcess()
    }
}
