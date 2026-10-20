package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.navigation.{DbtCte, DbtSqlAnalysis}
import com.github.vbmacher.intellij.dbt.project.DbtWorkspace
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.ui.{ConsoleView, ConsoleViewContentType, ExecutionConsole, RunnerLayoutUi}
import com.intellij.openapi.actionSystem.{AnAction, AnActionEvent, DefaultActionGroup}
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.{FileType, PlainTextFileType}
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.{VirtualFile, VirtualFileManager}
import com.intellij.ui.SimpleTextAttributes
import com.intellij.xdebugger._
import com.intellij.xdebugger.breakpoints._
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider
import com.intellij.xdebugger.frame._
import com.intellij.xdebugger.ui.XDebugTabLayouter
import java.awt.BorderLayout
import java.io.OutputStream
import javax.swing.JPanel
import org.jdom.Element
import scala.jdk.CollectionConverters._

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
  override def canPutAt(file: VirtualFile, line: Int, project: Project): Boolean = file.getExtension == "sql" &&
    ReadAction.computeBlocking[java.lang.Boolean, RuntimeException](() => {
      val w = DbtWorkspace.get(project)
      val psi = Option(com.intellij.psi.PsiManager.getInstance(project).findFile(file))
      Boolean.box(psi.exists(f => w.codeSymbols(f).exists(_.kind == "model") && DbtSqlAnalysis.get(w, f).exists(a =>
        Option(FileDocumentManager.getInstance.getDocument(file)).exists(d => a.ctes.exists(c =>
          !a.ctes.exists(p => p.body.contains(c.range)) && d.getLineNumber(c.range.getStartOffset) == line)))))
    }).booleanValue()
}

private[ide] final class DbtCteDebugProcess(session: XDebugSession, path: String, initial: Vector[DbtCte]) extends XDebugProcess(session) {
  private val project = session.getProject
  private val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).getConsole
  private val file = ReadAction.computeBlocking[Option[VirtualFile], RuntimeException](() =>
    DbtWorkspace.get(project).files.find(_.getVirtualFile.getPath == path).map(_.getVirtualFile))
  private var initialized = false
  private val process = new ProcessHandler {
    override def getProcessInput: OutputStream = null
    override def detachIsDefault: Boolean = false
    override protected def destroyProcessImpl(): Unit = { controller.stop(); notifyProcessTerminated(130) }
    override protected def detachProcessImpl(): Unit = destroyProcessImpl()
  }
  val controller: DbtCteController = new DbtCteController(project, path, initial, () => paused(),
    (text, kind) => process.notifyTextAvailable(text, kind), hasBreakpoint)
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
        case frame: DbtCteFrameSelection => controller.choose(frame.index); showResult()
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
  private final class CteFrame(val index: Int) extends XStackFrame with DbtCteFrameSelection {
    override def getSourcePosition: XSourcePosition = (for {
      source <- file
      cte <- controller.frames.lift(index)
    } yield XDebuggerUtil.getInstance.createPositionByOffset(source, cte.range.getStartOffset)).orNull
    override def getEqualityObject: AnyRef = path + ":" + index
    override def customizePresentation(container: com.intellij.ui.ColoredTextContainer): Unit =
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

private[ide] trait DbtCteFrameSelection { def index: Int }
