package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.navigation.{DbtCte, DbtNavigation, DbtSqlAnalysis}
import com.github.vbmacher.intellij.dbt.project._
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.{ApplicationManager, ModalityState, ReadAction, WriteIntentReadAction}
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.Callable

/** Query-driven state shared by native debugger controls; it never represents a paused warehouse query. */
private[ide] final class DbtCteController(project: com.intellij.openapi.project.Project, val path: String,
  initial: Vector[DbtCte], changed: () => Unit, output: (String, com.intellij.openapi.util.Key[_]) => Unit,
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
    if (disposed || project.isDisposed) None else ReadAction.computeBlocking[Option[DbtCteContext], RuntimeException](() => {
      val w = DbtWorkspace.get(project)
      w.symbols.find(s => s.isCode && s.kind == "model" && s.file.getVirtualFile.getPath == path).flatMap(DbtCteInspection.context(w, _))
    })
  private val connection = project.getMessageBus.connect(this)
  connection.subscribe(PsiModificationTracker.TOPIC, new PsiModificationTracker.Listener {
    override def modificationCountChanged(): Unit = invalidateIfChanged()
  })
  connection.subscribe(DbtProjectSettings.Changed, new DbtSettingsListener {
    override def settingsChanged(): Unit = invalidateIfChanged()
  })
  com.intellij.openapi.editor.EditorFactory.getInstance.getEventMulticaster.addDocumentListener(
    new com.intellij.openapi.editor.event.DocumentListener {
      override def documentChanged(event: com.intellij.openapi.editor.event.DocumentEvent): Unit =
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
  def referencedModel(): Option[(String, Vector[DbtCte])] = ReadAction.computeBlocking[Option[(String, Vector[DbtCte])], RuntimeException](() => {
    val w = DbtWorkspace.get(project)
    w.symbols.find(s => s.isCode && s.file.getVirtualFile.getPath == path).flatMap { model =>
      DbtSqlAnalysis.get(w, model.file).flatMap(a => a.ctes.filter(c => !a.ctes.exists(p => p.body.contains(c.range))).lift(selected)).flatMap { cte =>
        val models = DbtNavigation.inFile(model.file).filter(r => cte.range.contains(r.getRangeInElement)).flatMap(_.candidates)
          .collect { case e: DbtSymbolElement if e.symbol.kind == "model" && e.symbol.isCode => e.symbol }.distinctBy(_.id)
        if (models.size != 1) None else models.headOption.flatMap(m => DbtSqlAnalysis.get(w, m.file).map(a =>
          m.file.getVirtualFile.getPath -> a.ctes.filter(c => !a.ctes.exists(p => p.body.contains(c.range)))))
      }
    }
  })
  private def run(count: Boolean, rerunSelected: Boolean = false, limitOverride: Option[Int] = None): Unit =
    if (!disposed && !runner.isRunning && pending.isEmpty && !session.stopped) WriteIntentReadAction.run(() => {
      invalidateIfChanged()
      FileDocumentManager.getInstance.saveAllDocuments()
      com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
      val expected = currentContext()
      val selectedName = frames.lift(selected).map(_.name)
      val nextIndex = session.position + 1
      val token = generation + 1; generation = token; pending = Some(token); observed = expected
      message = "Preparing a separate CTE query…"
      ReadAction.nonBlocking(new Callable[Either[String, DbtCtePrepared]] {
        override def call(): Either[String, DbtCtePrepared] = {
          val w = DbtWorkspace.get(project)
          for {
            model <- w.symbols.find(s => s.isCode && s.kind == "model" && s.file.getVirtualFile.getPath == path).toRight("The model source is unavailable.")
            snapshot <- DbtCteInspection.context(w, model).toRight("The owning project is unavailable.")
            analysis <- DbtSqlAnalysis.get(w, model.file).toRight("This SQL cannot be inspected.")
            available = analysis.ctes.filter(c => !analysis.ctes.exists(p => p.body.contains(c.range)))
            index = if (count || rerunSelected) selectedName.map(name => available.indexWhere(_.name == name)).getOrElse(0) else nextIndex
            cte <- available.lift(index).toRight("End of CTE list. Restart to query again.")
            query <- DbtCteInspection.plan(snapshot.source, analysis, cte, count)
            command <- DbtCteInspection.command(w, model, query.sql, query.spans)
          } yield DbtCtePrepared(snapshot, available, index,
            limitOverride.fold(command)(limit => command.copy(request = command.request.copy(limit = limit.max(1).min(1000)))))
        }
      }).expireWith(this).finishOnUiThread(ModalityState.defaultModalityState(), prepared => {
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
      }).submit(AppExecutorUtil.getAppExecutorService)
    })
  override def dispose(): Unit = { disposed = true; stop() }
}
