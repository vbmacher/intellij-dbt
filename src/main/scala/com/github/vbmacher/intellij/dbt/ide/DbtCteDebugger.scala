package com.github.vbmacher.intellij.dbt.ide

import com.intellij.notification.NotificationType
import com.github.vbmacher.intellij.dbt.navigation.{DbtCte, DbtNavigation, DbtSqlAnalysis}
import com.github.vbmacher.intellij.dbt.project._
import com.github.vbmacher.intellij.dbt.syntax.DbtYaml
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.ui.{ConsoleViewContentType, ExecutionConsole, RunnerLayoutUi}
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.{ActionUpdateThread, AnAction, AnActionEvent, DefaultActionGroup}
import com.intellij.openapi.application.{ApplicationManager, WriteIntentReadAction}
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.{DocumentEvent, DocumentListener}
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.{FileType, PlainTextFileType}
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.{Disposer, Key}
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.ui.{ColoredTextContainer, SimpleTextAttributes}
import com.intellij.xdebugger._
import com.intellij.xdebugger.breakpoints._
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider
import com.intellij.xdebugger.frame._
import com.intellij.xdebugger.ui.XDebugTabLayouter
import java.awt.BorderLayout
import javax.swing.JPanel
import org.jdom.Element
import scala.jdk.CollectionConverters._

private[ide] object DbtCteController {
  private final case class Active(name: String, command: DbtCommand, context: DbtCteContext, count: Boolean)
}

/** Query-driven state shared by native debugger controls; it never represents a paused warehouse query. */
private[ide] final class DbtCteController(project: Project, val path: String,
                                          initial: Vector[DbtCte], changed: () => Unit, output: (String, Key[_]) => Unit,
                                          breakpoint: DbtCte => Boolean, val compileSource: Boolean = false) extends Disposable {

  import DbtCteController.Active

  private val session = new DbtCteSession
  private val runner = project.getService(classOf[DbtRunner])
  var frames: Vector[DbtCte] = initial
  var selected: Int = 0
  var message: String = "Ready. Each Step runs a separate query; no query has run."
  var result: Option[DbtQueryResult] = None
  var evidence: Option[DbtPreviewEvidence] = None
  private var generation = 0L
  private var pending = Option.empty[Long]
  private var disposed = false
  private var observed = Option.empty[DbtCteContext]
  var compilation: Option[DbtCteCompilation] = None
  private var compiling = Option.empty[DbtCteCompileJob]
  private var compileGeneration = 0L

  private var active = Option.empty[Active]
  private var continuing = false

  def running: Boolean = pending.nonEmpty || active.nonEmpty || compiling.nonEmpty

  def stopped: Boolean = disposed || session.stopped

  def position: Int = session.position

  def choose(index: Int): Unit = if (frames.isDefinedAt(index) && !running && index != selected) {
    selected = index;
    result = session.result(frames(index).name);
    evidence = None
  }

  def step(): Unit = run(count = false)

  def resume(): Unit = if (!running && !runner.isRunning) {
    continuing = true;
    run(count = false)
  }

  def count(): Unit = run(count = true)

  def rerun(limit: Int): Unit = run(count = false, rerunSelected = true, limitOverride = Some(limit))

  def back(): Unit = {
    invalidateIfChanged()
    if (!running && !runner.isRunning && !session.stopped && session.position > 0) {
      evidence = None;
      session.position -= 1;
      selected = session.position
      result = session.result(frames(selected).name)
      message = "Cached result; no query executed.";
      changed()
    }
  }

  def restart(): Unit = {
    cancelPreparation();
    if (active.nonEmpty || compiling.nonEmpty) runner.stop()
    compiling = None
    active = None;
    continuing = false;
    session.restart();
    selected = 0
    result = None;
    evidence = None;
    message = "Restarted. Step explicitly to query.";
    changed()
  }

  def stop(): Unit = {
    cancelPreparation();
    continuing = false;
    session.stop()
    if (active.nonEmpty || compiling.nonEmpty) runner.stop()
    compiling = None
    active = None;
    result = None;
    evidence = None;
    message = "Inspection stopped";
    changed()
  }

  runner.listen(this, onRunnerEvent)

  def compile(): Unit = if (compileSource && !running && !runner.isRunning && !stopped) {
    DbtUi.saveAndCommit(project)
    val token = generation + 1
    generation = token
    pending = Some(token)
    observed = currentContext()
    message = "Compiling the model and mapping source breakpoints. Compilation may run macro queries…"
    changed()
    DbtUi.readLater(this, smartIn = Some(project), committedIn = Some(project)) {
      val w = DbtWorkspace.get(project)
      w.model(path).toRight("The model source is unavailable.").flatMap(DbtCteCompileJob.prepare(w, _))
    } { prepared =>
      if (disposed || generation != token || !pending.contains(token) || stopped) prepared.foreach(_.dispose())
      else {
        pending = None
        prepared match {
          case Left(error) => message = error; changed()
          case Right(job) if !observed.contains(job.context) || !currentContext().contains(job.context) =>
            job.dispose(); message = "Source or execution settings changed. Restart to compile again."; changed()
          case Right(job) =>
            Disposer.register(this, job)
            compiling = Some(job)
            compileGeneration = token
            if (!runner.start(job.command, output)) {
              compiling = None;
              job.dispose();
              message = "Another dbt command is running.";
              changed()
            }
        }
      }
    }
  }

  private[ide] def onRunnerEvent(event: DbtRunnerEvent): Unit = {
    compiling.filter(job => !event.running && event.command.exists(_ eq job.command)).foreach { job =>
      compiling = None
      val token = compileGeneration
      if (stopped || token != generation || !currentContext().contains(job.context)) {
        job.dispose();
        changed()
      } else if (!event.exitCode.contains(0)) {
        job.dispose();
        message = event.message + ". No inspection query was launched. Restart to compile again.";
        changed()
      } else {
        pending = Some(token)
        DbtUi.readLater(this)(job.load()) { loaded =>
          job.dispose()
          if (!disposed && generation == token && pending.contains(token) && !stopped) {
            pending = None
            if (!currentContext().contains(job.context)) {
              message = "Source or execution settings changed; compilation discarded. Restart to compile again."
            } else loaded match {
              case Left(error) => message = error
              case Right(value) =>
                compilation = Some(value);
                frames = value.frames;
                selected = 0;
                session.restart()
                message = "Compiled. Step Over queries the next CTE. Step Into shows compiled SQL."
            }
            changed()
          }
        }
      }
    }
    active.filter(a => !event.running && event.command.exists(_ eq a.command)).foreach { finished =>
      active = None
      if (!disposed && !session.stopped && currentContext().contains(finished.context)) {
        evidence = event.previewEvidence;
        result = event.result;
        message = event.message
        event.result.foreach(r => if (!finished.count) session.record(finished.name, r))
        if (continuing && !finished.count && event.result.nonEmpty && !frames.lift(selected).exists(breakpoint) &&
          session.position + 1 < frames.size) run(count = false)
        else {
          continuing = false;
          changed()
        }
      } else {
        session.invalidate();
        continuing = false;
        result = None;
        evidence = None
        message = "Source or execution settings changed; query results discarded.";
        changed()
      }
    }
  }

  private def cancelPreparation(): Unit = {
    generation += 1;
    pending = None
  }

  private def currentContext(): Option[DbtCteContext] =
    if (disposed || project.isDisposed) None
    else DbtUi.read {
      val w = DbtWorkspace.get(project);
      w.model(path).flatMap(DbtCteInspection.context(w, _))
    }

  private val connection = project.getMessageBus.connect(this)
  connection.subscribe(PsiModificationTracker.TOPIC, new PsiModificationTracker.Listener {
    override def modificationCountChanged(): Unit = invalidateIfChanged()
  })
  connection.subscribe(DbtProjectSettings.Changed, new DbtSettingsListener {
    override def settingsChanged(): Unit = invalidateIfChanged()
  })
  EditorFactory.getInstance.getEventMulticaster.addDocumentListener(
    new DocumentListener {
      override def documentChanged(event: DocumentEvent): Unit =
        if (FileDocumentManager.getInstance.getFile(event.getDocument) != null) invalidateIfChanged()
    }, this)

  private def invalidateIfChanged(): Unit = {
    if (!ApplicationManager.getApplication.isDispatchThread) ApplicationManager.getApplication.invokeLater(() => invalidateIfChanged())
    else if (!disposed && observed.exists(old => !currentContext().contains(old))) {
      cancelPreparation();
      continuing = false;
      session.invalidate();
      session.position = -1;
      selected = 0
      if (active.nonEmpty || compiling.nonEmpty) runner.stop()
      if (compileSource) {
        discardCompilation();
        frames = Vector.empty
      }
      active = None;
      observed = currentContext();
      result = None;
      evidence = None
      message = "Source or execution settings changed. Step explicitly to refresh the CTE list.";
      changed()
    }
  }

  def referencedModel(): Option[(String, Vector[DbtCte])] = DbtUi.read {
    val w = DbtWorkspace.get(project)
    w.symbols.find(s => s.isCode && s.file.getVirtualFile.getPath == path).flatMap { model =>
      DbtSqlAnalysis.get(w, model.file).flatMap(a => DbtCteInspection.frames(a).lift(selected)).flatMap { cte =>
        val models = DbtNavigation.inFile(model.file).filter(r => cte.range.contains(r.getRangeInElement)).flatMap(_.candidates)
          .collect { case e: DbtSymbolElement if e.symbol.kind == "model" && e.symbol.isCode => e.symbol }.distinctBy(_.id)
        if (models.size != 1) None else models.headOption.flatMap(m => DbtSqlAnalysis.get(w, m.file).map(a =>
          m.file.getVirtualFile.getPath -> DbtCteInspection.frames(a)))
      }
    }
  }

  private def run(count: Boolean, rerunSelected: Boolean = false, limitOverride: Option[Int] = None): Unit =
    if (compileSource && compilation.isEmpty) compile()
    else if (!disposed && !runner.isRunning && pending.isEmpty && !session.stopped) WriteIntentReadAction.run(() => {
      invalidateIfChanged()
      DbtUi.saveAndCommit(project)
      val expected = currentContext()
      val selectedName = frames.lift(selected).map(_.name)
      val nextIndex = session.position + 1
      val token = generation + 1;
      generation = token;
      pending = Some(token);
      observed = expected
      message = "Preparing a separate CTE query…"
      DbtUi.readLater(this) {
        val w = DbtWorkspace.get(project)
        for {
          model <- w.model(path).toRight("The model source is unavailable.")
          snapshot <- DbtCteInspection.context(w, model).toRight("The owning project is unavailable.")
          _ <- Either.cond(!compileSource || compilation.exists(_.context == snapshot), (), "Source changed. Restart to compile again.")
          analysis <- compilation.map(c => Right(c.analysis)).getOrElse(DbtSqlAnalysis.result(w, model.file))
          available = DbtCteInspection.frames(analysis)
          index = if (count || rerunSelected) selectedName.map(name => available.indexWhere(_.name == name)).getOrElse(0) else nextIndex
          cte <- available.lift(index).toRight("End of CTE list. Restart to query again.")
          query <- DbtCteInspection.plan(compilation.map(_.sql).getOrElse(snapshot.source), analysis, cte, count)
          base <- DbtCteInspection.command(w, model, query.sql, query.spans)
          command = compilation.fold(base)(compiled => base.copy(
            request = base.request.copy(scope = DbtCommandScope.Inline(DbtCteCompilation.inline(query.sql))),
            previewSource = base.previewSource.map(_.copy(path = compiled.file.getPath, text = compiled.sql,
              fileUrl = compiled.file.getUrl, navigationFile = Some(compiled.file)))))
        } yield DbtCtePrepared(snapshot, available, index,
          limitOverride.fold(command)(limit => command.copy(request = command.request.copy(limit = DbtCommandRequest.limit(limit)))))
      } { prepared =>
        if (!disposed && generation == token && pending.contains(token) && !session.stopped) {
          pending = None
          prepared match {
            case Left(error) => continuing = false; message = error; changed()
            case Right(value) if !expected.contains(value.context) || !currentContext().contains(value.context) =>
              continuing = false;
              session.invalidate();
              result = None;
              evidence = None
              message = "Source or execution settings changed; no query was launched.";
              changed()
            case Right(value) =>
              session.synchronize(value.frames, value.context.execution.mkString("\u0000"))
              frames = value.frames;
              session.position = value.index;
              selected = value.index
              val name = frames(selected).name
              active = Some(Active(name, value.command, value.context, count));
              result = None;
              evidence = None
              message = "Query running…"
              if (!runner.start(value.command, output)) {
                active = None;
                continuing = false;
                message = "Another dbt command is running.";
                changed()
              }
          }
        }
      }
    })

  override def dispose(): Unit = {
    disposed = true;
    stop()
    discardCompilation()
  }

  private def discardCompilation(): Unit = {
    compilation.foreach(c => com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).closeFile(c.file))
    compilation = None
  }
}

private[ide] object DbtCteDebugger {
  def compile(project: Project, path: String): XSessionStartedResult =
    XDebuggerManager.getInstance(project).newSessionBuilder(new XDebugProcessStarter {
      override def start(session: XDebugSession): XDebugProcess = new DbtCteDebugProcess(session, path, Vector.empty, compileSource = true)
    }).sessionName("dbt CTE queries · " + java.nio.file.Paths.get(path).getFileName).showTab(true).startSession()

  def open(project: Project, path: String, frames: Vector[DbtCte]): XSessionStartedResult =
    XDebuggerManager.getInstance(project).newSessionBuilder(new XDebugProcessStarter {
      override def start(session: XDebugSession): XDebugProcess = new DbtCteDebugProcess(session, path, frames)
    }).sessionName("dbt CTE queries · " + java.nio.file.Paths.get(path).getFileName).showTab(true).startSession()
}

final class DbtCteBreakpointProperties extends XBreakpointProperties[Element] {
  override def getState: Element = new Element("cte")

  override def loadState(state: Element): Unit = ()
}

final class DbtCteBreakpointType extends XLineBreakpointType[DbtCteBreakpointProperties]("dbt-cte", "dbt CTE query breakpoints") {
  override def createBreakpointProperties(file: VirtualFile, line: Int): DbtCteBreakpointProperties = new DbtCteBreakpointProperties

  override def getVisibleStandardPanels: java.util.EnumSet[XBreakpointType.StandardPanels] = java.util.EnumSet.noneOf(classOf[XBreakpointType.StandardPanels])

  override def canPutAt(file: VirtualFile, line: Int, project: Project): Boolean = file.getExtension == "sql" && DbtUi.read {
    val w = DbtWorkspace.get(project)
    DbtYaml.psiFile(project, file).exists(f => w.root(f).exists(r => r.resource(f) && !r.excluded(file)) &&
      Option(FileDocumentManager.getInstance.getDocument(file)).exists(d => line >= 0 && line < d.getLineCount &&
        d.getText(new com.intellij.openapi.util.TextRange(d.getLineStartOffset(line), d.getLineEndOffset(line))).trim.nonEmpty))
  }
}

private[ide] final class DbtCteDebugProcess(session: XDebugSession, path: String, initial: Vector[DbtCte],
                                            compileSource: Boolean = false) extends XDebugProcess(session) {
  private val project = session.getProject
  private val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).getConsole
  private val file = DbtUi.read(DbtWorkspace.get(project).files.find(_.getVirtualFile.getPath == path).map(_.getVirtualFile))
  private var initialized = false
  private val process = new DbtManagedProcess(p => {
    controller.stop();
    p.complete(130)
  })
  val controller: DbtCteController = new DbtCteController(project, path, initial, () => paused(),
    process.output, hasBreakpoint, compileSource)
  private var generatedView = false
  private var mappedCompilation = Option.empty[DbtCteCompilation]
  private var mappedFiles = Map.empty[String, VirtualFile]
  private val grid = new DbtResultGrid(project, limit => {
    session.sessionResumed();
    controller.rerun(limit)
  })
  private val evidence = new DbtPreviewEvidencePanel(project)
  private val rows = new JPanel(new BorderLayout)
  rows.add(grid, BorderLayout.CENTER);
  rows.add(evidence, BorderLayout.SOUTH)
  Disposer.register(console, controller)
  Disposer.register(console, () => if (!process.isProcessTerminated) process.destroyProcess())
  console.attachToProcess(process)

  override def getEditorsProvider: XDebuggerEditorsProvider = new XDebuggerEditorsProvider {
    override def getFileType: FileType = PlainTextFileType.INSTANCE

    override def isEvaluateExpressionFieldEnabled: Boolean = false
  }

  override protected def doGetProcessHandler: ProcessHandler = process

  override def createConsole(): ExecutionConsole = console

  override def createTabLayouter(): XDebugTabLayouter = new XDebugTabLayouter {
    override def registerAdditionalContent(ui: RunnerLayoutUi): Unit = {
      val content = ui.createContent("dbt.cte.rows", rows, "Rows", null, grid)
      ui.addContent(content)
      ()
    }
  }

  override def sessionInitialized(): Unit = {
    initialized = true;
    session.setPauseActionSupported(false)
    console.print("Each Step or Resume executes separate CTE queries. Cached Step Back runs no query. No warehouse query is paused.\n", ConsoleViewContentType.SYSTEM_OUTPUT)
    session.addSessionListener(new XDebugSessionListener {
      override def stackFrameChanged(): Unit = session.getCurrentStackFrame match {
        case frame: DbtCteDebugProcess#CteFrame => controller.choose(frame.index); showResult()
        case _ => ()
      }
    }, console)
    paused()
    if (compileSource) controller.compile()
  }

  private def showResult(): Unit = {
    controller.result.fold(grid.clear(controller.message))(grid.showResult)
    evidence.showEvidence(controller.evidence);
    grid.setRunning(controller.running)
  }

  private def paused(): Unit = if (initialized && !process.isProcessTerminated) {
    showResult()
    if (compileSource) refreshBreakpoints()
    console.print(controller.message + "\n", ConsoleViewContentType.SYSTEM_OUTPUT)
    if (!controller.stopped) session.positionReached(new XSuspendContext {
      private val stack = new XExecutionStack("CTE query steps (independent queries)") {
        private val order = controller.frames.indices.toVector.sortBy(i => if (i == controller.selected) -1 else i)

        override def getTopFrame: XStackFrame = order.headOption.map(i => new CteFrame(i)).orNull

        override def computeStackFrames(first: Int, container: XExecutionStack.XStackFrameContainer): Unit =
          container.addStackFrames(order.drop(first).map(i => new CteFrame(i): XStackFrame).asJava, true)
      }

      override def getActiveExecutionStack: XExecutionStack = stack
    })
  }

  private def breakpoints: Vector[XLineBreakpoint[DbtCteBreakpointProperties]] =
    XDebuggerManager.getInstance(project).getBreakpointManager.getBreakpoints(classOf[DbtCteBreakpointType]).asScala.toVector

  private def sourceFile(path: String): Option[VirtualFile] = mappedFiles.get(path)

  private def matches(breakpoint: XLineBreakpoint[DbtCteBreakpointProperties], origin: DbtCteOrigin): Boolean =
    breakpoint.getLine == origin.line && sourceFile(origin.path).exists(_.getUrl == breakpoint.getFileUrl)

  private def refreshBreakpoints(): Unit = {
    if (!controller.compilation.exists(c => mappedCompilation.exists(_ eq c))) {
      mappedCompilation = controller.compilation
      mappedFiles = DbtUi.read(DbtWorkspace.get(project).files.map(f => f.getVirtualFile.getPath -> f.getVirtualFile).toMap)
    }
    breakpoints.foreach { breakpoint =>
      val mapped = controller.compilation.exists(c => c.frames.exists(f => c.origins(f).exists(matches(breakpoint, _))))
      if (mapped) session.setBreakpointVerified(breakpoint)
      else session.updateBreakpointPresentation(breakpoint,
        if (controller.compilation.isEmpty) com.intellij.icons.AllIcons.Debugger.Db_set_breakpoint
        else com.intellij.icons.AllIcons.Debugger.Db_invalid_breakpoint,
        if (controller.compilation.isEmpty) "Pending compilation; source mappings are unavailable."
        else "This source line did not map to an inspectable CTE in the selected model.")
    }
  }

  private def hasBreakpoint(cte: DbtCte): Boolean = if (compileSource) {
    !session.areBreakpointsMuted && controller.compilation.exists(c => c.origins(cte).exists(o => breakpoints.exists(b => b.isEnabled && matches(b, o))))
  } else !session.areBreakpointsMuted && file.exists { source =>
    Option(FileDocumentManager.getInstance.getDocument(source)).exists { document =>
      XDebuggerManager.getInstance(project).getBreakpointManager.getBreakpoints(classOf[DbtCteBreakpointType]).asScala
        .exists(b => b.isEnabled && b.getFileUrl == source.getUrl && b.getLine == document.getLineNumber(cte.range.getStartOffset))
    }
  }

  override def getBreakpointHandlers: Array[XBreakpointHandler[_]] = Array(
    new XBreakpointHandler[XLineBreakpoint[DbtCteBreakpointProperties]](classOf[DbtCteBreakpointType]) {
      override def registerBreakpoint(breakpoint: XLineBreakpoint[DbtCteBreakpointProperties]): Unit =
        if (compileSource) refreshBreakpoints() else session.setBreakpointVerified(breakpoint)

      override def unregisterBreakpoint(breakpoint: XLineBreakpoint[DbtCteBreakpointProperties], temporary: Boolean): Unit = ()
    })

  override def startStepOver(context: XSuspendContext): Unit = {
    if (project.getService(classOf[DbtRunner]).isRunning) paused() else controller.step()
  }

  override def resume(context: XSuspendContext): Unit = {
    if (project.getService(classOf[DbtRunner]).isRunning) paused() else controller.resume()
  }

  override def startStepInto(context: XSuspendContext): Unit = if (compileSource) {
    generatedView = true
    controller.compilation.foreach(c => com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(c.file, true))
    paused()
  } else controller.referencedModel() match {
    case Some((next, frames)) => DbtCteDebugger.open(project, next, frames); paused()
    case None => console.print("Select a CTE referencing exactly one local model.\n", ConsoleViewContentType.SYSTEM_OUTPUT); paused()
  }

  private def unsupported(action: String): Unit = {
    console.print(s"$action is unavailable for independent CTE queries. Use Step Over or Resume.\n", ConsoleViewContentType.SYSTEM_OUTPUT)
    paused()
  }

  override def startStepOut(context: XSuspendContext): Unit = if (compileSource) {
    generatedView = false;
    paused()
  } else unsupported("Step Out")

  override def runToPosition(position: XSourcePosition, context: XSuspendContext): Unit = unsupported("Run to Cursor")

  override def stop(): Unit = {
    controller.stop();
    if (!process.isProcessTerminated) process.destroyProcess()
  }

  override def registerAdditionalActions(left: DefaultActionGroup, top: DefaultActionGroup, settings: DefaultActionGroup): Unit = {
    def action(label: String)(body: => Unit): Unit = left.add(new AnAction(label) {
      override def actionPerformed(event: AnActionEvent): Unit = body

      override def update(event: AnActionEvent): Unit = event.getPresentation.setEnabled((label == "Restart CTE inspection" || !controller.running) && !process.isProcessTerminated)
    })

    action("Cached Step Back") {
      controller.back()
    }
    action("Count all CTE rows") {
      session.sessionResumed();
      controller.count()
    }
    action("Restart CTE inspection") {
      controller.restart()
    }
  }

  private final class CteFrame(val index: Int) extends XStackFrame {
    override def getSourcePosition: XSourcePosition = if (compileSource) {
      (for {
        compilation <- controller.compilation
        cte <- controller.frames.lift(index)
      } yield {
        val origins = compilation.origins(cte)
        val hit = if (session.areBreakpointsMuted) None else origins.find(o => breakpoints.exists(b => b.isEnabled && matches(b, o)))
        val source = hit.orElse(origins.headOption).flatMap(o => sourceFile(o.path).map(_ -> o.line))
        if (generatedView || source.isEmpty) XDebuggerUtil.getInstance.createPositionByOffset(compilation.file, cte.range.getStartOffset)
        else XDebuggerUtil.getInstance.createPosition(source.get._1, source.get._2)
      }).orNull
    } else (for {
      source <- file
      cte <- controller.frames.lift(index)
    } yield XDebuggerUtil.getInstance.createPositionByOffset(source, cte.range.getStartOffset)).orNull

    override def getEqualityObject: AnyRef = controller.compilation
      .map(c => (path, c.file, index, generatedView): AnyRef).getOrElse(path + ":" + index)

    override def customizePresentation(container: ColoredTextContainer): Unit = {
      val unmapped = compileSource && controller.frames.lift(index).exists(c => controller.compilation.exists(_.origins(c).isEmpty))
      container.append(controller.frames.lift(index).map(_.name).getOrElse("CTE") +
        (if (unmapped) " (compiled SQL; source mapping unavailable)" else " (query step)"), SimpleTextAttributes.REGULAR_ATTRIBUTES)
    }

    override def computeChildren(node: XCompositeNode): Unit = {
      val children = new XValueChildrenList
      val text = if (index == controller.selected) controller.result.map(r => s"${r.rows.size} returned rows; target ${r.target}").getOrElse("Not queried") else "Select this CTE to view cached rows"
      children.add("Result", new XValue {
        override def computePresentation(node: XValueNode, place: XValuePlace): Unit = node.setPresentation(null, "CTE preview", text, false)
      })
      node.addChildren(children, true)
    }
  }
}

private[ide] final case class DbtCtePrepared(context: DbtCteContext, frames: Vector[DbtCte], index: Int, command: DbtCommand)

/** Query-driven inspection; cached results belong to one exact source/target context. */
private[ide] final class DbtCteSession {
  var position: Int = -1
  var stopped: Boolean = false
  private var target = ""
  private var signatures = Map.empty[String, String]
  private var cache = Map.empty[String, DbtQueryResult]

  def synchronize(frames: Vector[DbtCte], nextTarget: String): Unit = {
    val next = frames.map(c => c.name -> c.sql).toMap
    var invalid = (next.keySet ++ signatures.keySet).filter(k => next.get(k) != signatures.get(k))
    var changed = true
    while (changed) {
      val expanded = invalid ++ frames.filter(_.dependencies.exists(invalid)).map(_.name)
      changed = expanded != invalid;
      invalid = expanded
    }
    cache = if (target != nextTarget) Map.empty else cache -- invalid
    signatures = next;
    target = nextTarget
    position = position.min(frames.size - 1)
  }

  def result(frame: String): Option[DbtQueryResult] = cache.get(frame)

  def record(frame: String, result: DbtQueryResult): Unit = if (!stopped) cache += frame -> result

  def restart(): Unit = {
    position = -1;
    stopped = false;
    cache = Map.empty
  }

  def invalidate(): Unit = {
    cache = Map.empty
  }

  def stop(): Unit = {
    stopped = true
  }
}

final class DbtInspectCtesAction extends AnAction("Inspect dbt CTEs") {
  override def getActionUpdateThread: ActionUpdateThread = ActionUpdateThread.BGT

  override def update(event: AnActionEvent): Unit = event.getPresentation.setEnabledAndVisible(DbtEditorAction.sql(event))

  override def actionPerformed(event: AnActionEvent): Unit = DbtEditorAction.fileContext(event).foreach { case (project, file) =>
    DbtUi.saveAndCommit(project)
    DbtUi.readLater(project, smartIn = Some(project), committedIn = Some(project)) {
      val w = DbtWorkspace.get(project)
      w.model(file).toRight("Choose a dbt source model.").map(_.file.getVirtualFile.getPath)
    } {
      case Right(path) => DbtCteDebugger.compile(project, path)
      case Left(reason) => DbtNotifications.show(project, "Inspect dbt CTEs", "Cannot inspect this model's CTEs.\n\n" + reason, NotificationType.ERROR)
    }
  }
}
