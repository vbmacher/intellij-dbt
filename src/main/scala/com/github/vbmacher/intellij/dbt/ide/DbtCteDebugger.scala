package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.navigation.{DbtCte, DbtNavigation, DbtSqlAnalysis}
import com.github.vbmacher.intellij.dbt.project._
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
import com.intellij.psi.PsiManager
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

/** Query-driven state shared by native debugger controls; it never represents a paused warehouse query. */
private[ide] final class DbtCteController(project: Project, val path: String,
  initial: Vector[DbtCte], changed: () => Unit, output: (String, Key[_]) => Unit,
  breakpoint: DbtCte => Boolean) extends Disposable {
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
  private final case class Active(name: String, command: DbtCommand, context: DbtCteContext, count: Boolean)
  private var active = Option.empty[Active]
  private var continuing = false
  def running: Boolean = pending.nonEmpty || active.nonEmpty
  def stopped: Boolean = disposed || session.stopped
  def position: Int = session.position
  def choose(index: Int): Unit = if (frames.isDefinedAt(index) && !running && index != selected) {
    selected = index; result = session.result(frames(index).name); evidence = None
  }
  def step(): Unit = run(count = false)
  def resume(): Unit = if (!running && !runner.isRunning) { continuing = true; run(count = false) }
  def count(): Unit = run(count = true)
  def rerun(limit: Int): Unit = run(count = false, rerunSelected = true, limitOverride = Some(limit))
  def back(): Unit = {
    invalidateIfChanged()
    if (!running && !runner.isRunning && !session.stopped && session.position > 0) {
      evidence = None; session.position -= 1; selected = session.position
      result = session.result(frames(selected).name)
      message = "Cached result; no query executed."; changed()
    }
  }
  def restart(): Unit = {
    cancelPreparation(); if (active.nonEmpty) runner.stop()
    active = None; continuing = false; session.restart(); selected = 0
    result = None; evidence = None; message = "Restarted. Step explicitly to query."; changed()
  }
  def stop(): Unit = {
    cancelPreparation(); continuing = false; session.stop()
    if (active.nonEmpty) runner.stop()
    active = None; result = None; evidence = None; message = "Inspection stopped"; changed()
  }
  runner.listen(this, onRunnerEvent)
  private[ide] def onRunnerEvent(event: DbtRunnerEvent): Unit = active.filter(a => !event.running && event.command.exists(_ eq a.command)).foreach { finished =>
    active = None
    if (!disposed && !session.stopped && currentContext().contains(finished.context)) {
      evidence = event.previewEvidence; result = event.result; message = event.message
      event.result.foreach(r => if (!finished.count) session.record(finished.name, r))
      if (continuing && !finished.count && event.result.nonEmpty && !frames.lift(selected).exists(breakpoint) &&
        session.position + 1 < frames.size) run(count = false)
      else { continuing = false; changed() }
    } else {
      session.invalidate(); continuing = false; result = None; evidence = None
      message = "Source or execution settings changed; query results discarded."; changed()
    }
  }
  private def cancelPreparation(): Unit = { generation += 1; pending = None }
  private def currentContext(): Option[DbtCteContext] =
    if (disposed || project.isDisposed) None
    else DbtUi.read { val w = DbtWorkspace.get(project); w.model(path).flatMap(DbtCteInspection.context(w, _)) }
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
      cancelPreparation(); continuing = false; session.invalidate(); session.position = -1; selected = 0
      if (active.nonEmpty) runner.stop()
      active = None; observed = currentContext(); result = None; evidence = None
      message = "Source or execution settings changed. Step explicitly to refresh the CTE list."; changed()
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
    if (!disposed && !runner.isRunning && pending.isEmpty && !session.stopped) WriteIntentReadAction.run(() => {
      invalidateIfChanged()
      DbtUi.saveAndCommit(project)
      val expected = currentContext()
      val selectedName = frames.lift(selected).map(_.name)
      val nextIndex = session.position + 1
      val token = generation + 1; generation = token; pending = Some(token); observed = expected
      message = "Preparing a separate CTE query…"
      DbtUi.readLater(this) {
        val w = DbtWorkspace.get(project)
        for {
          model <- w.model(path).toRight("The model source is unavailable.")
          snapshot <- DbtCteInspection.context(w, model).toRight("The owning project is unavailable.")
          analysis <- DbtSqlAnalysis.get(w, model.file).toRight("This SQL cannot be inspected.")
          available = DbtCteInspection.frames(analysis)
          index = if (count || rerunSelected) selectedName.map(name => available.indexWhere(_.name == name)).getOrElse(0) else nextIndex
          cte <- available.lift(index).toRight("End of CTE list. Restart to query again.")
          query <- DbtCteInspection.plan(snapshot.source, analysis, cte, count)
          command <- DbtCteInspection.command(w, model, query.sql, query.spans)
        } yield DbtCtePrepared(snapshot, available, index,
          limitOverride.fold(command)(limit => command.copy(request = command.request.copy(limit = DbtCommandRequest.limit(limit)))))
      } { prepared =>
        if (!disposed && generation == token && pending.contains(token) && !session.stopped) {
          pending = None
          prepared match {
            case Left(error) => continuing = false; message = error; changed()
            case Right(value) if !expected.contains(value.context) || !currentContext().contains(value.context) =>
              continuing = false; session.invalidate(); result = None; evidence = None
              message = "Source or execution settings changed; no query was launched."; changed()
            case Right(value) =>
              session.synchronize(value.frames, value.context.execution.mkString("\u0000"))
              frames = value.frames; session.position = value.index; selected = value.index
              val name = frames(selected).name
              active = Some(Active(name, value.command, value.context, count)); result = None; evidence = None
              message = "Query running…"
              if (!runner.start(value.command, output)) {
                active = None; continuing = false; message = "Another dbt command is running."; changed()
              }
          }
        }
      }
    })
  override def dispose(): Unit = { disposed = true; stop() }
}

private[ide] object DbtCteDebugger {
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
    Option(PsiManager.getInstance(project).findFile(file)).exists(f => w.model(f).nonEmpty && DbtSqlAnalysis.get(w, f).exists(a =>
      Option(FileDocumentManager.getInstance.getDocument(file)).exists(d =>
        DbtCteInspection.frames(a).exists(c => d.getLineNumber(c.range.getStartOffset) == line))))
  }
}

private[ide] final class DbtCteDebugProcess(session: XDebugSession, path: String, initial: Vector[DbtCte]) extends XDebugProcess(session) {
  private val project = session.getProject
  private val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).getConsole
  private val file = DbtUi.read(DbtWorkspace.get(project).files.find(_.getVirtualFile.getPath == path).map(_.getVirtualFile))
  private var initialized = false
  private val process = new DbtManagedProcess(p => { controller.stop(); p.complete(130) })
  val controller: DbtCteController = new DbtCteController(project, path, initial, () => paused(),
    process.output, hasBreakpoint)
  private val grid = new DbtResultGrid(project, limit => { session.sessionResumed(); controller.rerun(limit) })
  private val evidence = new DbtPreviewEvidencePanel(project)
  private val rows = new JPanel(new BorderLayout)
  rows.add(grid, BorderLayout.CENTER); rows.add(evidence, BorderLayout.SOUTH)
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
    initialized = true; session.setPauseActionSupported(false)
    console.print("Each Step or Resume executes separate CTE queries. Cached Step Back runs no query. No warehouse query is paused.\n", ConsoleViewContentType.SYSTEM_OUTPUT)
    session.addSessionListener(new XDebugSessionListener {
      override def stackFrameChanged(): Unit = session.getCurrentStackFrame match {
        case frame: DbtCteDebugProcess#CteFrame => controller.choose(frame.index); showResult()
        case _ => ()
      }
    }, console)
    paused()
  }
  private def showResult(): Unit = {
    controller.result.fold(grid.clear(controller.message))(grid.showResult)
    evidence.showEvidence(controller.evidence); grid.setRunning(controller.running)
  }
  private def paused(): Unit = if (initialized && !process.isProcessTerminated) {
    showResult()
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
  private def hasBreakpoint(cte: DbtCte): Boolean = !session.areBreakpointsMuted && file.exists { source =>
    Option(FileDocumentManager.getInstance.getDocument(source)).exists { document =>
      XDebuggerManager.getInstance(project).getBreakpointManager.getBreakpoints(classOf[DbtCteBreakpointType]).asScala
        .exists(b => b.isEnabled && b.getFileUrl == source.getUrl && b.getLine == document.getLineNumber(cte.range.getStartOffset))
    }
  }
  override def getBreakpointHandlers: Array[XBreakpointHandler[_]] = Array(
    new XBreakpointHandler[XLineBreakpoint[DbtCteBreakpointProperties]](classOf[DbtCteBreakpointType]) {
      override def registerBreakpoint(breakpoint: XLineBreakpoint[DbtCteBreakpointProperties]): Unit = session.setBreakpointVerified(breakpoint)
      override def unregisterBreakpoint(breakpoint: XLineBreakpoint[DbtCteBreakpointProperties], temporary: Boolean): Unit = ()
    })
  override def startStepOver(context: XSuspendContext): Unit = {
    if (project.getService(classOf[DbtRunner]).isRunning) paused() else controller.step()
  }
  override def resume(context: XSuspendContext): Unit = {
    if (project.getService(classOf[DbtRunner]).isRunning) paused() else controller.resume()
  }
  override def startStepInto(context: XSuspendContext): Unit = controller.referencedModel() match {
    case Some((next, frames)) => DbtCteDebugger.open(project, next, frames); paused()
    case None => console.print("Select a CTE referencing exactly one local model.\n", ConsoleViewContentType.SYSTEM_OUTPUT); paused()
  }
  private def unsupported(action: String): Unit = {
    console.print(s"$action is unavailable for independent CTE queries. Use Step Over or Resume.\n", ConsoleViewContentType.SYSTEM_OUTPUT)
    paused()
  }
  override def startStepOut(context: XSuspendContext): Unit = unsupported("Step Out")
  override def runToPosition(position: XSourcePosition, context: XSuspendContext): Unit = unsupported("Run to Cursor")
  override def stop(): Unit = { controller.stop(); if (!process.isProcessTerminated) process.destroyProcess() }
  override def registerAdditionalActions(left: DefaultActionGroup, top: DefaultActionGroup, settings: DefaultActionGroup): Unit = {
    def action(label: String)(body: => Unit): Unit = left.add(new AnAction(label) {
      override def actionPerformed(event: AnActionEvent): Unit = body
      override def update(event: AnActionEvent): Unit = event.getPresentation.setEnabled((label == "Restart CTE inspection" || !controller.running) && !process.isProcessTerminated)
    })
    action("Cached Step Back") { controller.back() }
    action("Count all CTE rows") { session.sessionResumed(); controller.count() }
    action("Restart CTE inspection") { controller.restart() }
  }
  private final class CteFrame(val index: Int) extends XStackFrame {
    override def getSourcePosition: XSourcePosition = (for {
      source <- file
      cte <- controller.frames.lift(index)
    } yield XDebuggerUtil.getInstance.createPositionByOffset(source, cte.range.getStartOffset)).orNull
    override def getEqualityObject: AnyRef = path + ":" + index
    override def customizePresentation(container: ColoredTextContainer): Unit =
      container.append(controller.frames.lift(index).map(_.name).getOrElse("CTE") + " (query step)", SimpleTextAttributes.REGULAR_ATTRIBUTES)
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
      changed = expanded != invalid; invalid = expanded
    }
    cache = if (target != nextTarget) Map.empty else cache -- invalid
    signatures = next; target = nextTarget
    position = position.min(frames.size - 1)
  }
  def result(frame: String): Option[DbtQueryResult] = cache.get(frame)
  def record(frame: String, result: DbtQueryResult): Unit = if (!stopped) cache += frame -> result
  def restart(): Unit = { position = -1; stopped = false; cache = Map.empty }
  def invalidate(): Unit = { cache = Map.empty }
  def stop(): Unit = { stopped = true }
}

final class DbtInspectCtesAction extends AnAction("Inspect dbt CTEs") {
  override def getActionUpdateThread: ActionUpdateThread = ActionUpdateThread.BGT
  override def update(event: AnActionEvent): Unit = event.getPresentation.setEnabledAndVisible(DbtEditorAction.sql(event))
  override def actionPerformed(event: AnActionEvent): Unit = DbtEditorAction.fileContext(event).foreach { case (project, file) =>
    DbtUi.readLater(project) {
      val w = DbtWorkspace.get(project)
      for (model <- w.model(file); analysis <- DbtSqlAnalysis.get(w, file))
        yield model.file.getVirtualFile.getPath -> DbtCteInspection.frames(analysis)
    }(_.foreach { case (path, frames) => DbtCteDebugger.open(project, path, frames) })
  }
}
