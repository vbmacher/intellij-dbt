package com.github.vbmacher.intellij.dbt.ide

import com.intellij.notification.NotificationType
import com.github.vbmacher.intellij.dbt.navigation.*
import com.github.vbmacher.intellij.dbt.project.*
import com.github.vbmacher.intellij.dbt.syntax.DbtYaml
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.execution.ui.ExecutionConsole
import com.intellij.execution.ui.RunnerLayoutUi
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.ui.ColoredTextContainer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.xdebugger.*
import com.intellij.xdebugger.breakpoints.*
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider
import com.intellij.xdebugger.frame.*
import com.intellij.xdebugger.ui.XDebugTabLayouter
import java.awt.BorderLayout
import javax.swing.JPanel
import org.jdom.Element

/**
 * Coordinates CTE preparation, optional traced compilation and independent preview queries on the UI thread.
 * Generation tokens and source/context checks discard stale asynchronous work and results.
 * Stop/disposal cancels only a command owned by this controller; the warehouse query is never suspended.
 */
internal class DbtCteController(
    private val project: Project,
    val path: String,
    initial: List<DbtCte>,
    private val changed: () -> Unit,
    private val output: (String, Key<*>) -> Unit,
    private val breakpoint: (DbtCte) -> Boolean,
    val compileSource: Boolean = false
) : Disposable {
    private data class Active(val name: String, val command: DbtCommand, val context: DbtCteContext, val count: Boolean)

    private val session = DbtCteSession();
    private val runner = project.getService(DbtRunner::class.java)
    var frames = initial;
    var selected = 0;
    var message = "Ready. Each Step runs a separate query; no query has run."
    var result: DbtQueryResult? = null;
    var evidence: DbtPreviewEvidence? = null
    private var generation = 0L;
    private var pending: Long? = null;
    private var disposed = false;
    private var observed: DbtCteContext? = null
    var compilation: DbtCteCompilation? = null;
    private var compiling: DbtCteCompileJob? = null;
    private var compileGeneration = 0L
    private var active: Active? = null;
    private var continuing = false
    val running: Boolean get() = pending != null || active != null || compiling != null
    val stopped: Boolean get() = disposed || session.stopped
    val position: Int get() = session.position
    fun choose(index: Int) {
        if (index in frames.indices && !running && index != selected) {
            selected = index; result = session.result(frames[index].name); evidence = null
        }
    }

    fun step() {
        run(false)
    }

    fun resume() {
        if (!running && !runner.isRunning) {
            continuing = true; run(false)
        }
    }

    fun count() {
        run(true)
    }

    fun rerun(limit: Int) {
        run(false, true, limit)
    }

    fun back() {
        invalidateIfChanged()
        if (!running && !runner.isRunning && !session.stopped && session.position > 0) {
            evidence = null; session.position--; selected = session.position; result =
                session.result(frames[selected].name); message = "Cached result; no query executed."; changed()
        }
    }

    fun restart() {
        cancelPreparation(); if (active != null || compiling != null) runner.stop(); compiling = null; active =
            null; continuing = false; session.restart(); selected = 0
        result = null; evidence = null; message = "Restarted. Step explicitly to query."; changed()
    }

    fun stop() {
        cancelPreparation(); continuing =
            false; session.stop(); if (active != null || compiling != null) runner.stop(); compiling = null; active =
            null
        result = null; evidence = null; message = "Inspection stopped"; changed()
    }

    init {
        runner.listen(this, ::onRunnerEvent)
        val connection = project.messageBus.connect(this)
        connection.subscribe(PsiModificationTracker.TOPIC, PsiModificationTracker.Listener { invalidateIfChanged() })
        connection.subscribe(DbtProjectSettings.Changed, object : DbtSettingsListener {
            override fun settingsChanged() {
                invalidateIfChanged()
            }
        })
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (FileDocumentManager.getInstance().getFile(event.document) != null) invalidateIfChanged()
            }
        }, this)
    }

    /** Prepares source mappings through the shared runner; compilation itself may execute dbt macro queries. */
    fun compile() {
        if (!compileSource || running || runner.isRunning || stopped) return
        DbtUi.saveAndCommit(project);
        val token = generation + 1; generation = token; pending = token; observed = currentContext()
        message = "Compiling the model and mapping source breakpoints. Compilation may run macro queries…"; changed()
        DbtUi.readLater(this, smartIn = project, committedIn = project, read = {
            dbtResult {
                val w = DbtWorkspace.get(project);
                val model = w.model(path)
                    ?: throw IllegalArgumentException("The model source is unavailable."); DbtCteCompileJob.prepare(
                w,
                model
            ).getOrThrow()
            }
        }) { prepared ->
            if (disposed || generation != token || pending != token || stopped) prepared.onSuccess { it.dispose() }
            else {
                pending = null; prepared.fold({ job ->
                    if (observed != job.context || currentContext() != job.context) {
                        job.dispose(); message =
                            "Source or execution settings changed. Restart to compile again."; changed()
                    } else {
                        Disposer.register(this, job); compiling = job; compileGeneration = token
                        if (!runner.start(job.command, output)) {
                            compiling = null; job.dispose(); message = "Another dbt command is running."; changed()
                        }
                    }
                }, { message = it.message.orEmpty(); changed() })
            }
        }
    }

    /** Accepts completion only for this controller's exact command and unchanged source/execution context. */
    fun onRunnerEvent(event: DbtRunnerEvent) {
        compiling?.takeIf { !event.running && event.command === it.command }?.let { job ->
            compiling = null;
            val token = compileGeneration
            if (stopped || token != generation || currentContext() != job.context) {
                job.dispose(); changed()
            } else if (event.exitCode != 0) {
                job.dispose(); message =
                    event.message + ". No inspection query was launched. Restart to compile again."; changed()
            } else {
                pending = token; DbtUi.readLater(this, read = { job.load() }) { loaded ->
                    job.dispose()
                    if (!disposed && generation == token && pending == token && !stopped) {
                        pending = null
                        if (currentContext() != job.context) message =
                            "Source or execution settings changed; compilation discarded. Restart to compile again."
                        else loaded.fold({ value ->
                            compilation = value; frames = value.frames; selected = 0; session.restart(); message =
                            "Compiled. Step Over queries the next CTE. Step Into shows compiled SQL."
                        }, { message = it.message.orEmpty() })
                        changed()
                    }
                }
            }
        }
        active?.takeIf { !event.running && event.command === it.command }?.let { finished ->
            active = null
            if (!disposed && !session.stopped && currentContext() == finished.context) {
                evidence = event.previewEvidence; result = event.result; message = event.message
                event.result?.let { if (!finished.count) session.record(finished.name, it) }
                if (continuing && !finished.count && event.result != null && frames.getOrNull(selected)
                        ?.let(breakpoint) != true && session.position + 1 < frames.size
                ) run(false)
                else {
                    continuing = false; changed()
                }
            } else {
                session.invalidate(); continuing = false; result = null; evidence = null; message =
                    "Source or execution settings changed; query results discarded."; changed()
            }
        }
    }

    private fun cancelPreparation() {
        generation++; pending = null
    }

    private fun currentContext(): DbtCteContext? = if (disposed || project.isDisposed) null else DbtUi.read {
        val w = DbtWorkspace.get(project); w.model(path)?.let { DbtCteInspection.context(w, it) }
    }

    private fun invalidateIfChanged() {
        if (!ApplicationManager.getApplication().isDispatchThread) ApplicationManager.getApplication()
            .invokeLater { invalidateIfChanged() }
        else if (!disposed && observed?.let { currentContext() != it } == true) {
            cancelPreparation(); continuing = false; session.invalidate(); session.position = -1; selected = 0
            if (active != null || compiling != null) runner.stop()
            if (compileSource) {
                discardCompilation(); frames = emptyList()
            }
            active = null; observed = currentContext(); result = null; evidence = null; message =
                "Source or execution settings changed. Step explicitly to refresh the CTE list."; changed()
        }
    }

    fun referencedModel(): Pair<String, List<DbtCte>>? = DbtUi.read {
        val w = DbtWorkspace.get(project)
        w.symbols.find { it.isCode && it.file.virtualFile.path == path }?.let { model ->
            DbtSqlAnalysis.get(w, model.file)?.let { DbtCteInspection.frames(it).getOrNull(selected) }?.let { cte ->
                val models = DbtNavigation.inFile(model.file).filter { cte.range.contains(it.rangeInElement) }
                    .flatMap { it.candidates }.filterIsInstance<DbtSymbolElement>()
                    .filter { it.symbol.kind == "model" && it.symbol.isCode }.map { it.symbol }.distinctBy { it.id }
                models.singleOrNull()?.let { m ->
                    DbtSqlAnalysis.get(w, m.file)?.let { m.file.virtualFile.path to DbtCteInspection.frames(it) }
                }
            }
        }
    }

    private fun run(count: Boolean, rerunSelected: Boolean = false, limitOverride: Int? = null) {
        if (compileSource && compilation == null) {
            compile(); return
        }
        if (disposed || runner.isRunning || pending != null || session.stopped) return
        WriteIntentReadAction.run {
            invalidateIfChanged(); DbtUi.saveAndCommit(project);
            val expected = currentContext();
            val selectedName = frames.getOrNull(selected)?.name
            val nextIndex = session.position + 1;
            val token = generation + 1; generation = token; pending = token; observed = expected; message =
            "Preparing a separate CTE query…"
            DbtUi.readLater(this, read = {
                dbtResult {
                    val w = DbtWorkspace.get(project);
                    val model = w.model(path) ?: throw IllegalArgumentException("The model source is unavailable.")
                    val snapshot = DbtCteInspection.context(w, model)
                        ?: throw IllegalArgumentException("The owning project is unavailable.")
                    require(!compileSource || compilation?.context == snapshot) { "Source changed. Restart to compile again." }
                    val analysis = compilation?.analysis ?: DbtSqlAnalysis.result(w, model.file).getOrThrow();
                    val available = DbtCteInspection.frames(analysis)
                    val index =
                        if (count || rerunSelected) selectedName?.let { name -> available.indexOfFirst { it.name == name } }
                            ?: 0 else nextIndex
                    val cte = available.getOrNull(index)
                        ?: throw IllegalArgumentException("End of CTE list. Restart to query again.")
                    val query =
                        DbtCteInspection.plan(compilation?.sql ?: snapshot.source, analysis, cte, count).getOrThrow()
                    val base = DbtCteInspection.command(w, model, query.sql, query.spans).getOrThrow()
                    val command = compilation?.let { compiled ->
                        base.copy(
                            request = base.request.copy(scope = DbtCommandScope.Inline(DbtCteCompilation.inline(query.sql))),
                            previewSource = base.previewSource?.copy(
                                path = compiled.file.path,
                                text = compiled.sql,
                                fileUrl = compiled.file.url,
                                navigationFile = compiled.file
                            )
                        )
                    } ?: base
                    DbtCtePrepared(
                        snapshot,
                        available,
                        index,
                        limitOverride?.let {
                            command.copy(
                                request = command.request.copy(
                                    limit = DbtCommandRequest.limit(it)
                                )
                            )
                        } ?: command)
                }
            }) { prepared ->
                if (!disposed && generation == token && pending == token && !session.stopped) {
                    pending = null
                    prepared.fold({ value ->
                        if (expected != value.context || currentContext() != value.context) {
                            continuing = false; session.invalidate(); result = null; evidence = null; message =
                                "Source or execution settings changed; no query was launched."; changed()
                        } else {
                            session.synchronize(value.frames, value.context.execution.joinToString("\u0000")); frames =
                                value.frames; session.position = value.index; selected = value.index
                            val name = frames[selected].name; active =
                                Active(name, value.command, value.context, count); result = null; evidence =
                                null; message = "Query running…"
                            if (!runner.start(value.command, output)) {
                                active = null; continuing = false; message =
                                    "Another dbt command is running."; changed()
                            }
                        }
                    }, { continuing = false; message = it.message.orEmpty(); changed() })
                }
            }
        }
    }

    override fun dispose() {
        disposed = true; stop(); discardCompilation()
    }

    private fun discardCompilation() {
        compilation?.let {
            com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).closeFile(it.file)
        }; compilation = null
    }
}

internal object DbtCteDebugger {
    fun compile(project: Project, path: String): XSessionStartedResult =
        XDebuggerManager.getInstance(project).newSessionBuilder(object : XDebugProcessStarter() {
            override fun start(session: XDebugSession): XDebugProcess =
                DbtCteDebugProcess(session, path, emptyList(), true)
        }).sessionName("dbt CTE queries · " + java.nio.file.Paths.get(path).fileName).showTab(true).startSession()

    fun open(project: Project, path: String, frames: List<DbtCte>): XSessionStartedResult =
        XDebuggerManager.getInstance(project).newSessionBuilder(object : XDebugProcessStarter() {
            override fun start(session: XDebugSession): XDebugProcess = DbtCteDebugProcess(session, path, frames)
        }).sessionName("dbt CTE queries · " + java.nio.file.Paths.get(path).fileName).showTab(true).startSession()
}

class DbtCteBreakpointProperties : XBreakpointProperties<Element>() {
    override fun getState(): Element = Element("cte")
    override fun loadState(state: Element) {}
}

class DbtCteBreakpointType : XLineBreakpointType<DbtCteBreakpointProperties>("dbt-cte", "dbt CTE query breakpoints") {
    override fun createBreakpointProperties(file: VirtualFile, line: Int): DbtCteBreakpointProperties =
        DbtCteBreakpointProperties()

    override fun getVisibleStandardPanels(): java.util.EnumSet<XBreakpointType.StandardPanels> =
        java.util.EnumSet.noneOf(XBreakpointType.StandardPanels::class.java)

    override fun canPutAt(file: VirtualFile, line: Int, project: Project): Boolean =
        file.extension == "sql" && DbtUi.read {
            val w = DbtWorkspace.get(project)
            DbtYaml.psiFile(project, file)?.let { f ->
                w.root(f)?.let { it.resource(f) && !it.excluded(file) } == true &&
                        FileDocumentManager.getInstance().getDocument(file)?.let { d ->
                            line >= 0 && line < d.lineCount && d.getText(
                                com.intellij.openapi.util.TextRange(
                                    d.getLineStartOffset(line),
                                    d.getLineEndOffset(line)
                                )
                            ).trim().isNotEmpty()
                        } == true
            } == true
        }
}

/** Native Debug presentation of independent query steps, cached results and source/compiled SQL positions. */
internal class DbtCteDebugProcess(
    session: XDebugSession,
    private val path: String,
    initial: List<DbtCte>,
    private val compileSource: Boolean = false
) : XDebugProcess(session) {
    private val project = session.project;
    private val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
    private val file = DbtUi.read { DbtWorkspace.get(project).files.find { it.virtualFile.path == path }?.virtualFile }
    private var initialized = false
    private val process: DbtManagedProcess = DbtManagedProcess { p -> controller.stop(); p.complete(130) }
    val controller: DbtCteController =
        DbtCteController(project, path, initial, ::paused, process::output, ::hasBreakpoint, compileSource)
    private var generatedView = false;
    private var mappedCompilation: DbtCteCompilation? = null;
    private var mappedFiles = emptyMap<String, VirtualFile>()
    private val grid = DbtResultGrid(project) { limit -> session.sessionResumed(); controller.rerun(limit) }
    private val evidence = DbtPreviewEvidencePanel(project);
    private val rows = JPanel(BorderLayout())

    init {
        rows.add(grid, BorderLayout.CENTER); rows.add(evidence, BorderLayout.SOUTH); Disposer.register(
            console,
            controller
        )
        Disposer.register(
            console,
            Disposable { if (!process.isProcessTerminated) process.destroyProcess() }); console.attachToProcess(process)
    }

    override fun getEditorsProvider(): XDebuggerEditorsProvider = object : XDebuggerEditorsProvider() {
        override fun getFileType(): FileType = PlainTextFileType.INSTANCE
        override fun isEvaluateExpressionFieldEnabled(): Boolean = false
    }

    override fun doGetProcessHandler(): ProcessHandler = process
    override fun createConsole(): ExecutionConsole = console
    override fun createTabLayouter(): XDebugTabLayouter = object : XDebugTabLayouter() {
        override fun registerAdditionalContent(ui: RunnerLayoutUi) {
            val content = ui.createContent("dbt.cte.rows", rows, "Rows", null, grid); ui.addContent(content)
        }
    }

    override fun sessionInitialized() {
        initialized = true; session.setPauseActionSupported(false)
        console.print(
            "Each Step or Resume executes separate CTE queries. Cached Step Back runs no query. No warehouse query is paused.\n",
            ConsoleViewContentType.SYSTEM_OUTPUT
        )
        session.addSessionListener(object : XDebugSessionListener {
            override fun stackFrameChanged() {
                (session.currentStackFrame as? CteFrame)?.let { controller.choose(it.index); showResult() }
            }
        }, console)
        paused(); if (compileSource) controller.compile()
    }

    private fun showResult() {
        controller.result?.let(grid::showResult)
            ?: grid.clear(controller.message); evidence.showEvidence(controller.evidence); grid.setRunning(controller.running)
    }

    private fun paused() {
        if (!initialized || process.isProcessTerminated) return
        showResult(); if (compileSource) refreshBreakpoints(); console.print(
            controller.message + "\n",
            ConsoleViewContentType.SYSTEM_OUTPUT
        )
        if (!controller.stopped) session.positionReached(object : XSuspendContext() {
            private val stack = object : XExecutionStack("CTE query steps (independent queries)") {
                private val order = controller.frames.indices.sortedBy { if (it == controller.selected) -1 else it }
                override fun getTopFrame(): XStackFrame? = order.firstOrNull()?.let { CteFrame(it) }
                override fun computeStackFrames(first: Int, container: XStackFrameContainer) {
                    container.addStackFrames(order.drop(first).map { CteFrame(it) }, true)
                }
            }

            override fun getActiveExecutionStack(): XExecutionStack = stack
        })
    }

    private val breakpoints: List<XLineBreakpoint<DbtCteBreakpointProperties>>
        get() = XDebuggerManager.getInstance(
            project
        ).breakpointManager.getBreakpoints(DbtCteBreakpointType::class.java).toList()

    private fun sourceFile(path: String): VirtualFile? = mappedFiles[path]
    private fun matches(breakpoint: XLineBreakpoint<DbtCteBreakpointProperties>, origin: DbtCteOrigin): Boolean =
        breakpoint.line == origin.line && sourceFile(origin.path)?.url == breakpoint.fileUrl

    private fun refreshBreakpoints() {
        if (controller.compilation == null || controller.compilation !== mappedCompilation) {
            mappedCompilation = controller.compilation; mappedFiles =
                DbtUi.read { DbtWorkspace.get(project).files.associate { it.virtualFile.path to it.virtualFile } }
        }
        breakpoints.forEach { breakpoint ->
            val mapped = controller.compilation?.let { c ->
                c.frames.any { f ->
                    c.origins(f).any { matches(breakpoint, it) }
                }
            } == true
            if (mapped) session.setBreakpointVerified(breakpoint)
            else session.updateBreakpointPresentation(
                breakpoint,
                if (controller.compilation == null) com.intellij.icons.AllIcons.Debugger.Db_set_breakpoint else com.intellij.icons.AllIcons.Debugger.Db_invalid_breakpoint,
                if (controller.compilation == null) "Pending compilation; source mappings are unavailable." else "This source line did not map to an inspectable CTE in the selected model."
            )
        }
    }

    private fun hasBreakpoint(cte: DbtCte): Boolean {
        if (compileSource) return !session.areBreakpointsMuted() && controller.compilation?.let { c ->
            c.origins(cte).any { o -> breakpoints.any { it.isEnabled && matches(it, o) } }
        } == true
        return !session.areBreakpointsMuted() && file?.let { source ->
            FileDocumentManager.getInstance().getDocument(source)?.let { document ->
                XDebuggerManager.getInstance(project).breakpointManager.getBreakpoints(DbtCteBreakpointType::class.java)
                    .any { it.isEnabled && it.fileUrl == source.url && it.line == document.getLineNumber(cte.range.startOffset) }
            }
        } == true
    }

    override fun getBreakpointHandlers(): Array<XBreakpointHandler<*>> = arrayOf(object :
        XBreakpointHandler<XLineBreakpoint<DbtCteBreakpointProperties>>(DbtCteBreakpointType::class.java) {
        override fun registerBreakpoint(breakpoint: XLineBreakpoint<DbtCteBreakpointProperties>) {
            if (compileSource) refreshBreakpoints() else session.setBreakpointVerified(breakpoint)
        }

        override fun unregisterBreakpoint(
            breakpoint: XLineBreakpoint<DbtCteBreakpointProperties>,
            temporary: Boolean
        ) {
        }
    })

    override fun startStepOver(context: XSuspendContext?) {
        if (project.getService(DbtRunner::class.java).isRunning) paused() else controller.step()
    }

    override fun resume(context: XSuspendContext?) {
        if (project.getService(DbtRunner::class.java).isRunning) paused() else controller.resume()
    }

    override fun startStepInto(context: XSuspendContext?) {
        if (compileSource) {
            generatedView = true; controller.compilation?.let {
                com.intellij.openapi.fileEditor.FileEditorManager.getInstance(
                    project
                ).openFile(it.file, true)
            }; paused()
        } else {
            val next = controller.referencedModel(); if (next != null) DbtCteDebugger.open(
                project,
                next.first,
                next.second
            ) else console.print(
                "Select a CTE referencing exactly one local model.\n",
                ConsoleViewContentType.SYSTEM_OUTPUT
            ); paused()
        }
    }

    private fun unsupported(action: String) {
        console.print(
            "$action is unavailable for independent CTE queries. Use Step Over or Resume.\n",
            ConsoleViewContentType.SYSTEM_OUTPUT
        ); paused()
    }

    override fun startStepOut(context: XSuspendContext?) {
        if (compileSource) {
            generatedView = false; paused()
        } else unsupported("Step Out")
    }

    override fun runToPosition(position: XSourcePosition, context: XSuspendContext?) {
        unsupported("Run to Cursor")
    }

    override fun stop() {
        controller.stop(); if (!process.isProcessTerminated) process.destroyProcess()
    }

    override fun registerAdditionalActions(
        left: DefaultActionGroup,
        top: DefaultActionGroup,
        settings: DefaultActionGroup
    ) {
        fun action(label: String, body: () -> Unit) {
            left.add(object : AnAction(label) {
                override fun actionPerformed(event: AnActionEvent) {
                    body()
                }

                override fun update(event: AnActionEvent) {
                    event.presentation.isEnabled =
                        (label == "Restart CTE inspection" || !controller.running) && !process.isProcessTerminated
                }
            })
        }
        action("Cached Step Back") { controller.back() }; action("Count all CTE rows") { session.sessionResumed(); controller.count() }; action(
            "Restart CTE inspection"
        ) { controller.restart() }
    }

    private inner class CteFrame(val index: Int) : XStackFrame() {
        override fun getSourcePosition(): XSourcePosition? {
            if (compileSource) {
                val compilation = controller.compilation ?: return null;
                val cte = controller.frames.getOrNull(index) ?: return null;
                val origins = compilation.origins(cte)
                val hit = if (session.areBreakpointsMuted()) null else origins.find { o ->
                    breakpoints.any {
                        it.isEnabled && matches(
                            it,
                            o
                        )
                    }
                }
                val source = (hit ?: origins.firstOrNull())?.let { o -> sourceFile(o.path)?.let { it to o.line } }
                return if (generatedView || source == null) XDebuggerUtil.getInstance()
                    .createPositionByOffset(compilation.file, cte.range.startOffset) else XDebuggerUtil.getInstance()
                    .createPosition(source.first, source.second)
            }
            val source = file ?: return null;
            val cte = controller.frames.getOrNull(index) ?: return null
            return XDebuggerUtil.getInstance().createPositionByOffset(source, cte.range.startOffset)
        }

        override fun getEqualityObject(): Any =
            controller.compilation?.let { listOf(path, it.file, index, generatedView) } ?: "$path:$index"

        override fun customizePresentation(container: ColoredTextContainer) {
            val unmapped = compileSource && controller.frames.getOrNull(index)
                ?.let { c -> controller.compilation?.origins(c)?.isEmpty() } == true
            container.append(
                (controller.frames.getOrNull(index)?.name
                    ?: "CTE") + if (unmapped) " (compiled SQL; source mapping unavailable)" else " (query step)",
                SimpleTextAttributes.REGULAR_ATTRIBUTES
            )
        }

        override fun computeChildren(node: XCompositeNode) {
            val children = XValueChildrenList();
            val text =
                if (index == controller.selected) controller.result?.let { "${it.rows.size} returned rows; target ${it.target}" }
                    ?: "Not queried" else "Select this CTE to view cached rows"
            children.add("Result", object : XValue() {
                override fun computePresentation(node: XValueNode, place: XValuePlace) {
                    node.setPresentation(null, "CTE preview", text, false)
                }
            }); node.addChildren(children, true)
        }
    }
}

internal data class DbtCtePrepared(
    val context: DbtCteContext,
    val frames: List<DbtCte>,
    val index: Int,
    val command: DbtCommand
)

/**
 * In-memory step position and preview cache; this class never executes queries.
 * Synchronization removes changed CTEs and their descendants, or the entire cache when execution context changes.
 */
internal class DbtCteSession {
    var position = -1;
    var stopped = false;
    private var target = "";
    private var signatures = emptyMap<String, String>();
    private var cache = emptyMap<String, DbtQueryResult>()
    fun synchronize(frames: List<DbtCte>, nextTarget: String) {
        val next = frames.associate { it.name to it.sql };
        var invalid = (next.keys + signatures.keys).filter { next[it] != signatures[it] }.toSet();
        var changed = true
        while (changed) {
            val expanded =
                invalid + frames.filter { f -> f.dependencies.any { it in invalid } }.map { it.name }; changed =
                expanded != invalid; invalid = expanded
        }
        cache = if (target != nextTarget) emptyMap() else cache - invalid; signatures = next; target =
            nextTarget; position = minOf(position, frames.size - 1)
    }

    fun result(frame: String): DbtQueryResult? = cache[frame]
    fun record(frame: String, result: DbtQueryResult) {
        if (!stopped) cache = cache + (frame to result)
    }

    fun restart() {
        position = -1; stopped = false; cache = emptyMap()
    }

    fun invalidate() {
        cache = emptyMap()
    }

    fun stop() {
        stopped = true
    }
}

class DbtInspectCtesAction : AnAction("Inspect dbt CTEs") {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = DbtEditorAction.sql(event)
    }

    override fun actionPerformed(event: AnActionEvent) {
        DbtEditorAction.fileContext(event)?.let { (project, file) ->
            DbtUi.saveAndCommit(project)
            DbtUi.readLater(project, smartIn = project, committedIn = project, read = {
                dbtResult {
                    val w = DbtWorkspace.get(project); (w.model(file)
                    ?: throw IllegalArgumentException("Choose a dbt source model.")).file.virtualFile.path
                }
            }) { result ->
                result.fold(
                    { DbtCteDebugger.compile(project, it) },
                    {
                        DbtNotifications.show(
                            project,
                            "Inspect dbt CTEs",
                            "Cannot inspect this model's CTEs.\n\n" + it.message.orEmpty(),
                            NotificationType.ERROR
                        )
                    })
            }
        }
    }
}
