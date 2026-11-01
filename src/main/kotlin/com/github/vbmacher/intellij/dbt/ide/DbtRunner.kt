package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project.*
import com.github.vbmacher.intellij.dbt.lineage.DbtSelection
import com.google.gson.JsonParser
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.execution.process.*
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.SystemNotifications
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.execution.ParametersListUtil
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

internal sealed class DbtCommandAction(
    val label: String,
    val command: List<String>,
    val usesState: Boolean = true,
    val structured: Boolean = false
) {
    data object Run : DbtCommandAction("Run", listOf("run"), structured = true)
    data object Build : DbtCommandAction("Build", listOf("build"), structured = true)
    data object Test : DbtCommandAction("Test", listOf("test"), structured = true)
    data object Compile : DbtCommandAction("Compile", listOf("compile"))
    data object Preview : DbtCommandAction("Preview", listOf("show"))
    data object GenerateDocs : DbtCommandAction("Generate Docs", listOf("docs", "generate"), usesState = false)
    data object SourceFreshness :
        DbtCommandAction("Source Freshness", listOf("source", "freshness"), usesState = false, structured = true)

    data object ResolveSelection :
        DbtCommandAction("Resolve Selection", listOf("ls", "--quiet", "--output", "json"), usesState = false)

    companion object {
        val toolbar: List<DbtCommandAction> by lazy {
            listOf(
                Run,
                Build,
                Test,
                Compile,
                Preview,
                GenerateDocs,
                SourceFreshness
            )
        }

        fun fromLabel(label: String): DbtCommandAction? = (toolbar + ResolveSelection).find { it.label == label }
    }
}

internal sealed class DbtCommandScope {
    data object All : DbtCommandScope()
    data class Selected(val terms: List<String>) : DbtCommandScope()
    data class Named(val name: String) : DbtCommandScope()
    data class Inline(val sql: String) : DbtCommandScope()
}

/**
 * Validated dbt action and scope, independent of its executable and working directory.
 * Argument rendering preserves token boundaries and never rereads project settings.
 */
internal data class DbtCommandRequest(
    val action: DbtCommandAction,
    val scope: DbtCommandScope = DbtCommandScope.All,
    val exclude: List<String> = emptyList(),
    val fullRefresh: Boolean = false,
    val limit: Int = 10,
    val state: String? = null,
    val defer: Boolean = false,
    val favorState: Boolean = false,
    val indirectSelection: String? = null,
    val debugSql: Boolean = false,
    val extraArguments: List<String> = emptyList()
) {
    val arguments: List<String>
        get() {
            val selection = when (val s = scope) {
                DbtCommandScope.All -> emptyList()
                is DbtCommandScope.Selected -> listOf("--select") + s.terms
                is DbtCommandScope.Named -> listOf("--selector", s.name)
                is DbtCommandScope.Inline -> listOf("--inline", s.sql)
            }
            return action.command + (if (action.structured) listOf(
                "--log-format",
                "json"
            ) else emptyList()) + selection +
                    (if (exclude.isNotEmpty()) listOf("--exclude") + exclude else emptyList()) +
                    (if (fullRefresh) listOf("--full-refresh") else emptyList()) +
                    (if (action == DbtCommandAction.Preview) listOf(
                        "--limit",
                        limit(limit).toString(),
                        "--output",
                        "json"
                    ) else emptyList()) +
                    (if (debugSql) listOf("--log-format", "json", "--log-level", "debug") else emptyList()) +
                    (state?.let { listOf("--state", it) } ?: emptyList()) +
                    (if (defer) listOf("--defer") else emptyList()) + (if (favorState) listOf("--favor-state") else emptyList()) +
                    (indirectSelection?.let { listOf("--indirect-selection", it) } ?: emptyList()) + extraArguments
        }

    companion object {
        /** Preview row limits are bounded to 1–1000 wherever they are requested, rendered or displayed. */
        fun limit(value: Int): Int = value.coerceIn(1, 1000)
    }
}

/**
 * Captured process inputs: wrapper/executable [prefix], dbt [request], environment and output provenance.
 * [directory] is the command working directory; [root] identifies the owning execution project.
 * Changing settings after preparation does not alter this command. Create a copy for a rerun or new limit.
 */
internal data class DbtCommand(
    val prefix: List<String>,
    val request: DbtCommandRequest,
    val directory: String,
    val environment: Map<String, String>,
    val target: String,
    val notifySystem: Boolean,
    val artifactDirectory: String,
    val artifactContext: List<String>,
    val colors: Boolean = true,
    val root: String = "",
    val sourceSql: String = "",
    val previewSource: DbtPreviewSource? = null
) {
    val action: String get() = request.action.label
    val arguments: List<String> by lazy {
        prefix + request.arguments + (if (target.isNotEmpty()) listOf(
            "--target",
            target
        ) else emptyList()) + if (colors) "--use-colors" else "--no-use-colors"
    }
    val commandLine: String get() = ParametersListUtil.join(arguments)
    val executionRoot: String get() = root.takeIf { it.isNotEmpty() } ?: artifactContext.firstOrNull() ?: directory

    companion object {
        val Actions: List<String> = DbtCommandAction.toolbar.map { it.label }
        private fun incremental(w: DbtWorkspace, s: DbtSymbol): Boolean =
            DbtResourceData.get(w, s).attributes["materialized"] == "incremental"

        private fun wholeProject(w: DbtWorkspace, root: DbtRoot): DbtRunSelection =
            DbtRunSelection.all(w, root.directory.path)
                ?: throw IllegalArgumentException("The execution root is unavailable.")

        /**
         * Prepares a project selection without executing dbt. CLI-reviewed IDs must have exact local identities;
         * unresolved local terms fail unless they are state terms delegated to dbt with a validated baseline.
         */
        fun selection(
            w: DbtWorkspace, root: DbtRoot, action: String, fullRefresh: Boolean,
            requested: DbtRunSelection? = null, cliIds: List<String>? = null
        ): Result<DbtCommand> = dbtResult {
            val options = root.runOptions
            if (action in setOf("Generate Docs", "Source Freshness")) return@dbtResult create(
                w,
                wholeProject(w, root),
                action,
                false,
                root.runOptions
            ).getOrThrow()
            val expression = DbtSelectionReview.expression(w, root, requested)
            val local = DbtSelection.resolve(w, root, expression, options.exclude)
            val cliMatches =
                cliIds?.let { ids -> DbtSelectionReview.knownCliResources(w, root).filter { it.id in ids } }
            val resolved = if (cliIds == null) local else {
                val matches = cliMatches.orEmpty()
                DbtSelection(
                    matches.mapNotNull { it.symbol },
                    cliIds.filterNot { id -> matches.any { it.id == id } }
                        .map { "CLI resource is unavailable locally: $it" })
            }
            val stateSelection = (expression + " " + options.exclude).split(Regex("[\\s,]+"))
                .any { it.replaceFirst(Regex("^[0-9]*\\+"), "").startsWith("state:") }
            if (options.defer || options.favorState || stateSelection || options.stateDirectory.isNotEmpty()) DbtState.manifest(
                root
            ).getOrThrow()
            require(!options.favorState || options.defer) { "Favor state requires deferral." }
            require(!options.favorState || root.engine == "core" && root.supports(4)) { "Favor state is supported only for configured dbt Core 1.4+." }
            require(resolved.complete || stateSelection && resolved.problems.all { it.contains("state:") }) {
                resolved.problems.joinToString(
                    "\n"
                )
            }
            require(
                stateSelection || (cliMatches?.isNotEmpty() ?: resolved.resources.isNotEmpty())
            ) { "Selection matches no resources." }
            require(action != "Preview") { "Use a model or CTE for Preview." }
            val everything = wholeProject(w, root)
            val incremental = stateSelection || resolved.resources.any { it.kind == "model" && incremental(w, it) }
            val base = create(w, everything, action, fullRefresh && incremental, root.runOptions).getOrThrow()
            val excluded = DbtSelectionReview.cliTerms(w, root, options.exclude).getOrThrow()
            val scope = if (expression.isEmpty() && cliIds == null) DbtCommandScope.All
            else if (stateSelection && cliIds == null) DbtCommandScope.Selected(
                expression.split(Regex("\\s+")).filter { it.isNotEmpty() })
            else DbtCommandScope.Selected((cliMatches?.map { it.selector }
                ?: resolved.resources.map { DbtSelection.selector(w, it) }).distinct())
            // Preserve explicit exclusions as well: dbt may otherwise select their tests indirectly.
            base.copy(request = base.request.copy(scope = scope, exclude = excluded))
        }

        /** Explicit CLI resolution shares the normal managed process and execution context. */
        fun resolveSelection(w: DbtWorkspace, root: DbtRoot, requested: DbtRunSelection?): Result<DbtCommand> =
            dbtResult {
                val expression = DbtSelectionReview.expression(w, root, requested)
                val exclude = root.runOptions.exclude.trim()
                val base = prepare(
                    w,
                    wholeProject(w, root),
                    DbtCommandAction.ResolveSelection,
                    false,
                    root.runOptions
                ).getOrThrow()
                val baseline = if ((expression + " " + exclude).contains("state:")) DbtState.baseline(root).getOrThrow()
                    .toString() else null
                val exclusions = DbtSelectionReview.cliTerms(w, root, exclude).getOrThrow()
                val named = Regex("^selector:([^+,\\s]+)$").matchEntire(expression)
                val selected = when {
                    expression.isEmpty() -> DbtCommandScope.All
                    named != null -> DbtCommandScope.Named(named.groupValues[1])
                    else -> {
                        val terms = DbtSelectionReview.cliTerms(w, root, expression).getOrThrow()
                        require(terms.isNotEmpty()) { "Selection matches no locally known resources." }
                        DbtCommandScope.Selected(terms)
                    }
                }
                base.copy(request = base.request.copy(scope = selected, exclude = exclusions, state = baseline))
            }

        fun create(
            w: DbtWorkspace,
            requested: DbtRunSelection,
            action: String,
            fullRefresh: Boolean,
            options: DbtRunOptions? = null
        ): Result<DbtCommand> {
            val parsed = DbtCommandAction.fromLabel(action)
                ?: return Result.failure(IllegalArgumentException("Unknown dbt command."))
            return prepare(w, requested, parsed, fullRefresh, options)
        }

        private fun prepare(
            w: DbtWorkspace, requested: DbtRunSelection, action: DbtCommandAction, fullRefresh: Boolean,
            executionOptions: DbtRunOptions?
        ): Result<DbtCommand> = dbtResult {
            val selection = (if (requested.path.isEmpty() && requested.directory) DbtRunSelection.all(w, requested.root)
                ?.takeIf { it.root == requested.root }
            else DbtRunSelection.available(w)
                .find { it.path == requested.path && it.root == requested.root && it.directory == requested.directory })
                ?: throw IllegalArgumentException("Choose an available model or directory in the dbt run configuration.")
            val owner = DbtRunSelection.owner(w, selection)
                ?: throw IllegalArgumentException("The selected dbt project is no longer available.")
            val original = w.executionRoot(owner);
            val root = executionOptions?.let(original::withRunOptions) ?: original
            val options = root.runOptions;
            val directory = root.executionDirectory
            val target = DbtExecutionContext.activeTarget(root).orEmpty()
            val all = selection.directory && (selection.path.isEmpty() || selection.path == root.directory.path)
            val selectors = if (all) emptyList() else DbtRunSelection.selectors(w, selection)
            val state = if (!action.usesState) null else {
                require(!options.defer || root.engine == "core") { "Core deferral flags are unavailable for the configured engine." }
                require(!options.favorState || options.defer && root.engine == "core" && root.supports(4)) { "Favor state requires deferral and dbt Core 1.4+." }
                if (options.stateDirectory.isNotEmpty() || options.defer || options.favorState) {
                    DbtState.manifest(root).getOrThrow(); DbtState.baseline(root).getOrThrow().toString()
                } else null
            }
            require(options.projectRoot.isEmpty() || Files.isRegularFile(directory.resolve("dbt_project.yml"))) { "Project root must contain dbt_project.yml. Check dbt Workspace settings." }
            require(action != DbtCommandAction.SourceFreshness || all) { "Choose the whole dbt project to check source freshness." }
            require(action != DbtCommandAction.Preview || !selection.directory) { "Choose a single model to preview." }
            require(action == DbtCommandAction.GenerateDocs || all || selectors.isNotEmpty()) { "The selected directory contains no enabled dbt models." }
            val prefix = listOf(DbtExecutionContext.executable(root)) + ParametersListUtil.parse(
                options.executableArguments,
                false,
                true
            )
            val incremental = DbtRunSelection.selectedModels(w, selection).any { incremental(w, it) }
            val profiles = DbtExecutionContext.profiles(root).directory.toString();
            val artifact = root.artifactDirectory
            val variables = DbtVariableOverrides.parse(options.variables).getOrThrow()
            val request = DbtCommandRequest(
                action,
                if (action == DbtCommandAction.GenerateDocs || all) DbtCommandScope.All else DbtCommandScope.Selected(
                    selectors
                ),
                fullRefresh = fullRefresh && incremental && action in setOf(
                    DbtCommandAction.Run,
                    DbtCommandAction.Build
                ),
                limit = DbtCommandRequest.limit(options.previewLimit),
                state = state,
                defer = action.usesState && options.defer,
                favorState = action.usesState && options.favorState,
                extraArguments = if (variables.size() == 0) emptyList() else listOf("--vars", variables.toString())
            )
            DbtCommand(
                prefix,
                request,
                directory.toString(),
                DbtExecutionContext.environment(root) + mapOf(
                    "DBT_PROFILES_DIR" to profiles,
                    "DBT_TARGET_PATH" to artifact.toString()
                ),
                target,
                options.notifications,
                artifact.toString(),
                DbtArtifactContext.identity(root),
                options.colors,
                root.directory.path,
                if (action == DbtCommandAction.Preview) DbtRunSelection.selectedModels(w, selection)
                    .firstOrNull()?.file?.text.orEmpty() else ""
            )
        }
    }
}

internal data class DbtRunnerEvent(
    val running: Boolean, val message: String, val refresh: Boolean = false, val preview: String? = null,
    val result: DbtQueryResult? = null, val execution: DbtExecutionRun? = null, val command: DbtCommand? = null,
    val previewEvidence: DbtPreviewEvidence? = null, val selectionOutput: String? = null, val exitCode: Int? = null
)

/**
 * Runs at most one managed dbt process for this IDEA project, shared by Run, tests and CTE inspection.
 * Process output is consumed on process threads; progress/completion events use the captured UI modality.
 * Session consoles observe events and delegate cancellation here rather than launching separate processes.
 */
@Service(Service.Level.PROJECT)
class DbtRunner(private val project: Project) : Disposable {
    private val evidence = project.getService(DbtExecutionEvidence::class.java)
    private val busy = AtomicBoolean(false);
    private val cancelled = AtomicBoolean(false)
    @Volatile
    private var disposed = false
    @Volatile
    private var handler: KillableProcessHandler? = null
    private val listeners = CopyOnWriteArrayList<(DbtRunnerEvent) -> Unit>()
    val isRunning: Boolean get() = busy.get()
    /** Registers an observer until [parent] is disposed; registration does not start or stop a command. */
    internal fun listen(parent: Disposable, listener: (DbtRunnerEvent) -> Unit) {
        listeners.add(listener)
        Disposer.register(parent, Disposable { listeners.remove(listener) })
    }

    private fun fire(event: DbtRunnerEvent) {
        listeners.forEach { it(event) }
    }

    /**
     * Claims the project runner and starts [command] asynchronously; false means busy or disposed.
     * The start event is synchronous. [outputSink] receives process-thread text, while completion events
     * are delivered through the captured UI modality. Raw output remains separate from console formatting.
     */
    internal fun start(command: DbtCommand, outputSink: (String, Key<*>) -> Unit = { _, _ -> }): Boolean {
        if (disposed || !busy.compareAndSet(false, true)) return false
        val modality = ModalityState.defaultModalityState()
        fun onUi(action: () -> Unit) {
            ApplicationManager.getApplication()
                .invokeLater({ if (!disposed && !project.isDisposed) action() }, modality)
        }
        cancelled.set(false); evidence.begin(command)
        fire(DbtRunnerEvent(true, "${command.action} running", execution = evidence.active, command = command))
        AppExecutorUtil.getAppExecutorService().execute {
            if (disposed || cancelled.get()) onUi {
                busy.set(false)
                fire(
                    DbtRunnerEvent(
                        false,
                        "${command.action} cancelled",
                        execution = evidence.finish(command, true),
                        command = command,
                        exitCode = 130
                    )
                )
            } else try {
                val manifestBefore = DbtArtifactContext.fingerprint(command.artifactDirectory)
                val started = System.nanoTime()
                val process = KillableProcessHandler(
                    DbtToolCommand(
                        command.arguments,
                        command.directory,
                        command.environment
                    ).commandLine
                )
                process.setShouldKillProcessSoftly(false)
                val display = DbtConsoleOutput(outputSink); handler = process;
                val output = StringBuilder()
                process.addProcessListener(object : ProcessListener {
                    override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                        synchronized(output) { if (output.length < 10000000) output.append(event.text) }
                        display.textAvailable(event.text, outputType); evidence.stream(event.text)
                        evidence.active?.let { run ->
                            onUi {
                                fire(
                                    DbtRunnerEvent(
                                        true,
                                        "${command.action} running",
                                        execution = run
                                    )
                                )
                            }
                        }
                    }

                    override fun processTerminated(event: ProcessEvent) {
                        display.finish()
                        val wasCancelled = cancelled.get();
                        val success = event.exitCode == 0 && !wasCancelled
                        val message =
                            if (wasCancelled) "${command.action} cancelled" else "${command.action} finished (exit ${event.exitCode})"
                        val capturedOutput = synchronized(output) { output.toString() };
                        val isPreview = command.action == "Preview"
                        val previewEvidence = if (isPreview) DbtPreviewEvidence.capture(
                            command,
                            capturedOutput,
                            !success,
                            wasCancelled
                        ) else null
                        val preview = if (success && isPreview) DbtPreview.format(capturedOutput) else null
                        val result = if (success && isPreview) DbtQueryResult.parse(
                            capturedOutput,
                            command.target,
                            DbtCommandRequest.limit(command.request.limit),
                            (System.nanoTime() - started) / 1000000L,
                            previewEvidence?.let { it.adapterSql ?: it.submitted } ?: command.sourceSql)
                            .getOrNull() else null
                        val refresh = success && command.action in setOf("Run", "Build", "Compile", "Generate Docs")
                        if (refresh) {
                            if (DbtArtifactContext.fingerprint(command.artifactDirectory) != manifestBefore) dbtResult {
                                DbtArtifactContext.record(
                                    command.artifactDirectory,
                                    command.artifactContext
                                )
                            }
                            LocalFileSystem.getInstance()
                                .refreshIoFiles(listOf(java.io.File(command.artifactDirectory)), true, true, null)
                        }
                        val execution = evidence.finish(command, wasCancelled)
                        val displayedPreview =
                            if (preview != null && result == null) "Malformed or unavailable dbt preview JSON. See raw process output." else preview
                        onUi {
                            handler = null; busy.set(false)
                            fire(
                                DbtRunnerEvent(
                                    false,
                                    message,
                                    refresh,
                                    displayedPreview,
                                    result,
                                    execution,
                                    command,
                                    previewEvidence,
                                    if (success && command.action == "Resolve Selection") capturedOutput else null,
                                    event.exitCode
                                )
                            )
                            DaemonCodeAnalyzer.getInstance(project).restart("dbt execution results changed")
                            DbtNotifications.publish(project, command, message, success, wasCancelled)
                        }
                    }
                })
                onUi { process.startNotify(); if (cancelled.get()) process.destroyProcess() }
                if (disposed) {
                    process.startNotify(); process.destroyProcess()
                }
            } catch (error: Exception) {
                if (error is com.intellij.openapi.diagnostic.ControlFlowException || error is java.util.concurrent.CancellationException || error is InterruptedException) throw error
                onUi {
                    busy.set(false); handler = null
                    val message = "Cannot launch dbt: " + (error.message
                        ?: error.toString()) + ". Check the executable in dbt Workspace settings."
                    fire(
                        DbtRunnerEvent(
                            false,
                            message,
                            execution = evidence.finish(command, cancelled.get()),
                            command = command,
                            exitCode = 1
                        )
                    )
                    DbtNotifications.publish(project, command, message, false, cancelled.get())
                }
            }
        }
        return true
    }

    fun stop() {
        cancelled.set(true); handler?.destroyProcess()
    }

    override fun dispose() {
        disposed = true; stop(); listeners.clear()
    }
}

internal object DbtNotifications {
    /** A balloon of the plugin's notification group; nothing is shown for a closed project. */
    fun show(project: Project?, title: String, content: String, kind: NotificationType = NotificationType.WARNING) {
        if (project == null || !project.isDisposed) NotificationGroupManager.getInstance()
            .getNotificationGroup("dbt Workspace").createNotification(title, content, kind).notify(project)
    }

    fun publish(
        project: Project, command: DbtCommand, message: String, success: Boolean, wasCancelled: Boolean,
        system: SystemNotifications = SystemNotifications.getInstance()
    ) {
        val kind = if (success || wasCancelled) NotificationType.INFORMATION else NotificationType.ERROR
        NotificationGroupManager.getInstance().getNotificationGroup("dbt Workspace").createNotification(message, kind)
            .notify(project)
        if (command.notifySystem) system.notify("dbt Workspace", "dbt " + command.action, message)
    }
}

/** Formats the display stream; execution evidence and query results use the original output. */
internal class DbtConsoleOutput(private val sink: (String, Key<*>) -> Unit) {
    private val decoder = AnsiEscapeDecoder()
    private val acceptor = AnsiEscapeDecoder.ColoredTextAcceptor { text, attributes -> sink(text, attributes) }
    private val pending = linkedMapOf<Key<*>, StringBuilder>();
    private val continuing = mutableSetOf<Key<*>>()
    private val time = Regex("(?:T|^)([0-9]{2}:[0-9]{2}:[0-9]{2})")
    @Synchronized
    fun textAvailable(text: String, kind: Key<*>) {
        if (kind == ProcessOutputTypes.SYSTEM) emit(text, kind) else {
            val buffer = pending.getOrPut(kind) { StringBuilder() };
            var start = 0
            while (start < text.length) {
                val end = text.indexOf('\n', start);
                val chunk = text.substring(start, if (end < 0) text.length else end + 1)
                if (buffer.isNotEmpty() || kind !in continuing && chunk.trim().startsWith("{")) {
                    buffer.append(chunk)
                    if (end >= 0) {
                        emit(format(buffer.toString()), kind); buffer.setLength(0)
                    } else if (buffer.length > 1000000) {
                        emit(buffer.toString(), kind); buffer.setLength(0)
                    }
                } else emit(chunk, kind)
                if (end >= 0) continuing.remove(kind) else if (chunk.trim().isNotEmpty()) continuing.add(kind)
                start = if (end < 0) text.length else end + 1
            }
        }
    }

    @Synchronized
    fun finish() {
        pending.forEach { (kind, buffer) -> if (buffer.isNotEmpty()) emit(format(buffer.toString()), kind) }
        pending.clear(); continuing.clear()
    }

    private fun emit(text: String, kind: Key<*>) {
        decoder.escapeText(text, kind, acceptor)
    }

    private fun format(line: String): String {
        val element = dbtResult { JsonParser.parseString(line) }.getOrNull()?.takeIf { it.isJsonObject } ?: return line
        val obj = element.asJsonObject
        if (obj["data"]?.isJsonObject != true) return line
        val info = DbtJson.child(obj, "info") ?: return line
        val text = DbtJson.text(info, "msg") ?: return line
        val timestamp = DbtJson.text(info, "ts")?.let { time.find(it)?.groupValues?.get(1) }?.let { "$it  " }.orEmpty()
        val level =
            DbtJson.text(info, "level")?.uppercase(java.util.Locale.ROOT)?.takeUnless { it == "INFO" }?.let { "[$it] " }
                .orEmpty()
        return if (text.isEmpty()) "\n" else timestamp + level + text.removeSuffix("\n") + "\n"
    }
}

/** Capture ordinary failures while preserving IntelliJ's cancellation and control-flow exceptions. */
internal inline fun <T> dbtResult(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (error: Exception) {
    if (error is com.intellij.openapi.diagnostic.ControlFlowException || error is java.util.concurrent.CancellationException || error is InterruptedException) throw error
    Result.failure(error)
}
