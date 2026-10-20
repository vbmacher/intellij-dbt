package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.navigation.{DbtCte, DbtSqlAnalysis, DbtNavigation}
import com.github.vbmacher.intellij.dbt.project._
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.{ActionUpdateThread, AnAction, AnActionEvent, CommonDataKeys}
import com.intellij.openapi.application.{ModalityState, ReadAction, WriteIntentReadAction}
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.{JBLabel, JBList, JBScrollPane}
import com.intellij.ui.content.ContentFactory
import com.intellij.util.concurrency.AppExecutorUtil
import java.awt.{BorderLayout, FlowLayout}
import java.util.concurrent.Callable
import javax.swing.{DefaultListModel, JButton, JCheckBox, JPanel, JSplitPane}

private[ide] object DbtCteInspection {
  def at(w: DbtWorkspace, file: com.intellij.psi.PsiFile, analysis: DbtSqlAnalysis, offset: Int): Option[DbtCte] = {
    val resolved = DbtNavigation.at(file, offset).flatMap(_.candidates).collect {
      case element: DbtSymbolElement if element.symbol.kind == "cte" && element.getContainingFile == file => element
    }.flatMap(element => analysis.ctes.filter(c => c.name.equalsIgnoreCase(element.getName) &&
      c.range.getStartOffset == element.getTextRange.getStartOffset)).distinct
    if (resolved.size == 1) resolved.headOption
    else if (resolved.nonEmpty) None
    else analysis.ctes.filter(_.range.containsOffset(offset)).sortBy(_.range.getLength).headOption
  }

  /** Preserve authored template setup and comments preceding the SQL WITH keyword. */
  private def prefix(text: String, analysis: DbtSqlAnalysis, selected: Vector[DbtCte], target: DbtCte): Either[String, String] = {
    val first = analysis.ctes.filter(c => !analysis.ctes.exists(p => p.body.contains(c.range))).sortBy(_.range.getStartOffset).headOption
    first.toRight("The outer WITH clause is unavailable.").flatMap { initial =>
      val blocks = com.github.vbmacher.intellij.dbt.syntax.DbtSyntax.blocks(text)
      DbtNavigation.withPrefix(text, initial.range.getStartOffset).toRight("Template setup before WITH cannot be extracted safely.").flatMap { prefix =>
        val omittedSetup = blocks.exists(b => b.tag && b.range.getStartOffset >= prefix.length &&
          b.range.getStartOffset < target.range.getEndOffset && !selected.exists(_.range.contains(b.range)))
        if (omittedSetup) Left("Template setup between CTE declarations cannot be omitted safely. Move it before WITH or preview the whole model.")
        else Right(prefix)
      }
    }
  }

  def sql(text: String, analysis: DbtSqlAnalysis, cte: DbtCte, count: Boolean = false): Either[String, String] =
    plan(text, analysis, cte, count).map(_.sql)

  def plan(text: String, analysis: DbtSqlAnalysis, cte: DbtCte, count: Boolean = false): Either[String, DbtCteQuery] = {
    if (analysis.issues.exists(_.startsWith("Explicit CTE column lists"))) return Left("Explicit CTE column lists cannot be inspected safely. Preview the complete model instead.")
    val selected = scala.collection.mutable.LinkedHashSet.empty[DbtCte]
    val visiting = scala.collection.mutable.Set.empty[DbtCte]
    def visit(current: DbtCte): Either[String, Unit] = {
      if (visiting(current)) Left("Recursive CTE inspection is unsupported.")
      else {
        visiting += current
        val parents = analysis.ctes.filter(p => p.body.contains(current.range))
        val result = current.dependencies.foldLeft[Either[String, Unit]](Right(())) { (previous, name) => previous.flatMap { _ =>
          val candidates = analysis.ctes.filter(c => c.name.equalsIgnoreCase(name) && c.range.getStartOffset < current.range.getStartOffset &&
            (analysis.ctes.filter(p => p.body.contains(c.range)).toSet.subsetOf(parents.toSet)))
          candidates.sortBy(_.range.getStartOffset).lastOption.toRight("A CTE dependency is unavailable: " + name).flatMap(visit)
        }}
        visiting -= current
        result.map(_ => { selected += current; () })
      }
    }
    if (analysis.issues.exists(_.toLowerCase.contains("recursive"))) Left("Recursive CTE inspection is unsupported.")
    else visit(cte).flatMap(_ => prefix(text, analysis, selected.toVector, cte)).map { setup =>
      val name = if (com.github.vbmacher.intellij.dbt.syntax.DbtSyntax.isIdentifier(cte.name)) cte.name else "\"" + cte.name.replace("\"", "\"\"") + "\""
      val query = new StringBuilder(setup + "with ")
      val spans = Vector.newBuilder[DbtPreviewSpan]
      if (setup.nonEmpty) spans += DbtPreviewSpan(0, 0, setup.length)
      selected.toVector.zipWithIndex.foreach { case (frame, index) =>
        if (index > 0) query.append(",\n")
        spans += DbtPreviewSpan(query.length, frame.range.getStartOffset, frame.range.getLength)
        query.append(frame.range.substring(text))
      }
      query.append("\nselect " + (if (count) "count(*) as full_row_count" else "*") + " from " + name)
      DbtCteQuery(query.toString, spans.result())
    }
  }
  def context(w: DbtWorkspace, model: DbtSymbol): Option[DbtCteContext] = w.owner(model).map(w.executionRoot).map { root =>
    val manager = FileDocumentManager.getInstance()
    val document = Option(manager.getCachedDocument(model.file.getVirtualFile))
    val source = document.map(_.getText).getOrElse(model.file.getText)
    val execution = root.runOptions.values.map(value => String.valueOf(value)) ++ Vector(root.directory.getPath, root.executionDirectory.toString,
      DbtExecutionContext.activeTarget(root).getOrElse(""), DbtExecutionContext.profiles(root).directory.toString,
      root.config.getContainingFile.getText, root.settings.map(_.getText).getOrElse(""),
      root.psiFile(root.profilesFile).map(f => java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
        .digest(f.getText.getBytes(java.nio.charset.StandardCharsets.UTF_8)))).getOrElse(""))
    val dependencies = w.files.filter(f => w.root(f).exists(r => w.executionRoot(r) == root &&
      (r.resource(f) || f.getName == "dbt_project.yml" || f.getName == "profiles.yml" || f.getName == ".dbt-navigation.yml")))
      .map(f => f.getVirtualFile.getPath -> Option(manager.getCachedDocument(f.getVirtualFile)).map(_.getModificationStamp).getOrElse(f.getModificationStamp))
      .sortBy(_._1)
    DbtCteContext(source, document.map(_.getModificationStamp).getOrElse(model.file.getModificationStamp), execution, dependencies)
  }
  def command(w: DbtWorkspace, resource: DbtSymbol, sql: String,
    spans: Vector[DbtPreviewSpan] = Vector.empty): Either[String, DbtCommand] = for {
    owner <- w.owner(resource).toRight("Owning dbt project is unavailable.")
    _ <- Either.cond(w.packageOwner(owner).isEmpty, (), "Inline SQL previews cannot preserve installed-package context. Preview the complete model instead.")
    _ <- Either.cond(DbtNavigation.projectedColumns(sql).nonEmpty, (), "Inline preview requires one supported SELECT query without data or schema modification.")
    root = w.executionRoot(owner)
    _ <- Either.cond(root.supports(5), (), "CTE preview requires a CLI supporting dbt show --inline (Core 1.5+).")
    selected <- DbtRunSelection.available(w).find(_.path == resource.file.getVirtualFile.getPath).toRight("Choose a source model.")
    base <- DbtCommand.create(w, selected, "Preview", fullRefresh = false)
  } yield {
    base.copy(request = base.request.copy(scope = DbtCommandScope.Inline(sql), debugSql = true),
      previewSource = Some(DbtPreviewSource(resource.file.getVirtualFile.getPath, resource.file.getText, sql, spans,
        s"sql_operation.${root.name}.inline_query", resource.file.getVirtualFile.getUrl)))
  }
}

/** Query-driven inspection; cached results belong to one exact source/target context. */
private[ide] final class DbtCteSession {
  var position: Int = -1
  var stopped: Boolean = false
  var breakpoints: Set[String] = Set.empty
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
  override def update(event: AnActionEvent): Unit = event.getPresentation.setEnabledAndVisible(
    event.getProject != null && Option(event.getData(CommonDataKeys.PSI_FILE)).exists(_.getName.endsWith(".sql")))
  override def actionPerformed(event: AnActionEvent): Unit = {
    val project = event.getProject
    val file = event.getData(CommonDataKeys.PSI_FILE)
    if (project == null || file == null) return
    ReadAction.nonBlocking(new Callable[Option[(String, Vector[DbtCte])]] {
      override def call(): Option[(String, Vector[DbtCte])] = {
        val w = DbtWorkspace.get(project)
        for (model <- w.codeSymbols(file).find(_.kind == "model"); analysis <- DbtSqlAnalysis.get(w, file))
          yield model.file.getVirtualFile.getPath -> analysis.ctes.filter(c => !analysis.ctes.exists(p => p.body.contains(c.range)))
      }
    }).expireWith(project).finishOnUiThread(ModalityState.defaultModalityState(), (result: Option[(String, Vector[DbtCte])]) => result.foreach { case (path, frames) =>
      val window = ToolWindowManager.getInstance(project).getToolWindow("dbt")
      if (window != null) {
        val panel = new DbtCtePanel(project, path, frames)
        val content = ContentFactory.getInstance().createContent(panel, "Inspect: " + file.getName, true)
        content.setDisposer(panel); window.getContentManager.addContent(content)
        window.getContentManager.setSelectedContent(content); window.show()
      }
    }).submit(AppExecutorUtil.getAppExecutorService)
  }
}

private[ide] final case class DbtCteContext(source: String, stamp: Long, execution: Vector[String],
  dependencies: Vector[(String, Long)])
private[ide] final case class DbtCtePrepared(context: DbtCteContext, frames: Vector[DbtCte], index: Int, command: DbtCommand)

private[ide] final class DbtCtePanel(project: Project, path: String, initial: Vector[DbtCte]) extends JPanel(new BorderLayout) with Disposable {
  private val session = new DbtCteSession
  private val runner = project.getService(classOf[DbtRunner])
  private val frames = new DefaultListModel[String]
  private val frameList = new JBList[String](frames)
  private val grid = new DbtResultGrid(value => run(count = false, rerunSelected = true, limitOverride = Some(value)))
  private val previewEvidence = new DbtPreviewEvidencePanel(project)
  private val status = new JBLabel("Each frame is a separate query and can use warehouse compute; nondeterministic values may change.")
  private val buttons = new JPanel(new FlowLayout(FlowLayout.LEFT))
  private var generation = 0L
  private var pending = Option.empty[Long]
  private var disposed = false
  private var observed = Option.empty[DbtCteContext]
  private final class Active(val name: String, val command: DbtCommand, val context: DbtCteContext, val count: Boolean)
  private var active = Option.empty[Active]
  private var continuing = false
  initial.foreach(c => frames.addElement(c.name))
  private def button(label: String)(action: => Unit): Unit = {
    val b = new JButton(label); b.addActionListener(_ => action); buttons.add(b)
  }
  button("Step") { run(count = false) }
  button("Continue to breakpoint") { if (pending.isEmpty && !runner.isRunning) { continuing = true; run(count = false) } }
  button("Step Back") {
    invalidateIfChanged()
    if (pending.isEmpty && !runner.isRunning && !session.stopped && session.position > 0) {
      previewEvidence.showEvidence(None)
      session.position -= 1; frameList.setSelectedIndex(session.position)
      session.result(frames.get(session.position)).fold(grid.clear("Cached result unavailable; explicitly Step to query again."))(grid.showResult)
    }
  }
  button("Count all rows") { run(count = true) }
  button("Restart") {
    cancelPreparation()
    if (active.nonEmpty) runner.stop()
    active = None; continuing = false; session.restart()
    previewEvidence.showEvidence(None)
    grid.clear("Restarted; Step explicitly to query.")
  }
  button("Stop") {
    cancelPreparation(); continuing = false; session.stop()
    if (active.nonEmpty) runner.stop()
    active = None; previewEvidence.showEvidence(None); grid.clear("Inspection stopped"); status.setText("Inspection stopped")
  }
  button("Step into referenced model") { stepInto() }
  private val breakpoint = new JCheckBox("Break at selected CTE")
  breakpoint.addActionListener(_ => Option(frameList.getSelectedValue).foreach { name =>
    session.breakpoints = if (breakpoint.isSelected) session.breakpoints + name else session.breakpoints - name
  })
  frameList.addListSelectionListener(_ => breakpoint.setSelected(Option(frameList.getSelectedValue).exists(session.breakpoints)))
  buttons.add(breakpoint)
  private val north = new JPanel(new BorderLayout)
  north.add(buttons, BorderLayout.NORTH); north.add(previewEvidence, BorderLayout.CENTER); north.add(status, BorderLayout.SOUTH)
  add(north, BorderLayout.NORTH); add(new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JBScrollPane(frameList), grid), BorderLayout.CENTER)
  runner.listen(this, onRunnerEvent)
  private[ide] def onRunnerEvent(event: DbtRunnerEvent): Unit = {
    grid.setRunning(event.running)
    active.filter(a => !event.running && event.command.exists(_ eq a.command)).foreach { finished =>
      active = None
      if (!disposed && !session.stopped && currentContext().contains(finished.context)) {
        previewEvidence.showEvidence(event.previewEvidence)
        event.result.foreach { result =>
          if (!finished.count) session.record(finished.name, result)
          grid.showResult(result)
        }
        if (event.result.isEmpty) grid.clear(event.message)
        status.setText(event.message)
        if (continuing && !finished.count && event.result.nonEmpty && !session.breakpoints(finished.name)) run(count = false)
        else continuing = false
      } else {
        session.invalidate(); continuing = false
        previewEvidence.showEvidence(None)
        grid.clear("Source or execution settings changed; the completed query result was discarded.")
      }
    }
  }
  private def cancelPreparation(): Unit = { generation += 1; pending = None }
  private def currentContext(): Option[DbtCteContext] =
    if (disposed || project.isDisposed) None else ReadAction.computeBlocking[Option[DbtCteContext], RuntimeException](() => {
      val w = DbtWorkspace.get(project)
      w.symbols.find(s => s.isCode && s.kind == "model" && s.file.getVirtualFile.getPath == path).flatMap(DbtCteInspection.context(w, _))
    })
  private val connection = project.getMessageBus.connect(this)
  connection.subscribe(com.intellij.psi.util.PsiModificationTracker.TOPIC, new com.intellij.psi.util.PsiModificationTracker.Listener {
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
    if (!com.intellij.openapi.application.ApplicationManager.getApplication.isDispatchThread)
      com.intellij.openapi.application.ApplicationManager.getApplication.invokeLater(() => invalidateIfChanged())
    else if (!disposed && observed.exists(old => !currentContext().contains(old))) {
      cancelPreparation(); continuing = false; session.invalidate(); session.position = -1
      if (active.nonEmpty) runner.stop()
      active = None; observed = currentContext()
      previewEvidence.showEvidence(None)
      grid.clear("Source or execution settings changed. Step explicitly to refresh the CTE stack.")
      status.setText("Source or execution settings changed. Step explicitly to refresh the CTE stack.")
    }
  }
  private def stepInto(): Unit = if (!runner.isRunning && pending.isEmpty && !disposed) {
    val index = frameList.getSelectedIndex
    val expected = currentContext()
    val token = generation
    ReadAction.nonBlocking(new Callable[Option[(String, Vector[DbtCte])]] {
      override def call(): Option[(String, Vector[DbtCte])] = {
        val w = DbtWorkspace.get(project)
        w.symbols.find(s => s.isCode && s.file.getVirtualFile.getPath == path).flatMap { model =>
          DbtSqlAnalysis.get(w, model.file).flatMap(a => a.ctes.filter(c => !a.ctes.exists(p => p.body.contains(c.range))).lift(index))
            .flatMap { cte =>
              val models = DbtNavigation.inFile(model.file).filter(r => cte.range.contains(r.getRangeInElement)).flatMap(_.candidates)
                .collect { case e: DbtSymbolElement if e.symbol.kind == "model" && e.symbol.isCode => e.symbol }.distinctBy(_.id)
              if (models.size != 1) None else DbtSqlAnalysis.get(w, models.head.file).map(a => models.head.file.getVirtualFile.getPath ->
                a.ctes.filter(c => !a.ctes.exists(p => p.body.contains(c.range))))
            }
        }
      }
    }).expireWith(this).finishOnUiThread(ModalityState.defaultModalityState(), (result: Option[(String, Vector[DbtCte])]) =>
      if (!disposed && token == generation && expected == currentContext()) result match {
        case None => status.setText("Select a CTE with one resolvable referenced source model.")
        case Some((nextPath, nextFrames)) =>
          val window = ToolWindowManager.getInstance(project).getToolWindow("dbt")
          if (window != null) {
            val panel = new DbtCtePanel(project, nextPath, nextFrames)
            val content = ContentFactory.getInstance().createContent(panel, "Inspect: " + java.nio.file.Paths.get(nextPath).getFileName, true)
            content.setDisposer(panel); window.getContentManager.addContent(content); window.getContentManager.setSelectedContent(content)
          }
      }).submit(AppExecutorUtil.getAppExecutorService)
  }
  private def run(count: Boolean, rerunSelected: Boolean = false, limitOverride: Option[Int] = None): Unit = if (!disposed && !runner.isRunning && pending.isEmpty && !session.stopped) WriteIntentReadAction.run(() => {
    invalidateIfChanged()
    FileDocumentManager.getInstance().saveAllDocuments()
    com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
    val expected = currentContext()
    val selectedName = Option(frameList.getSelectedValue)
    val nextIndex = session.position + 1
    val token = generation + 1; generation = token; pending = Some(token); observed = expected
    status.setText("Preparing CTE query…")
    ReadAction.nonBlocking(new Callable[Either[String, DbtCtePrepared]] {
      override def call(): Either[String, DbtCtePrepared] = {
        val w = DbtWorkspace.get(project)
        for {
          model <- w.symbols.find(s => s.isCode && s.kind == "model" && s.file.getVirtualFile.getPath == path).toRight("The model source is unavailable.")
          snapshot <- DbtCteInspection.context(w, model).toRight("The owning project is unavailable.")
          analysis <- DbtSqlAnalysis.get(w, model.file).toRight("This SQL cannot be inspected.")
          available = analysis.ctes.filter(c => !analysis.ctes.exists(p => p.body.contains(c.range)))
          index = if (count || rerunSelected) selectedName.map(name => available.indexWhere(_.name == name)).getOrElse(0) else nextIndex
          cte <- available.lift(index).toRight("End of CTE stack. Restart to query again.")
          query <- DbtCteInspection.plan(snapshot.source, analysis, cte, count)
          command <- DbtCteInspection.command(w, model, query.sql, query.spans)
        } yield {
          val query = limitOverride.fold(command)(limit => command.copy(request = command.request.copy(limit = limit.max(1).min(1000))))
          DbtCtePrepared(snapshot, available, index, query)
        }
      }
    }).expireWith(this).finishOnUiThread(ModalityState.defaultModalityState(), (result: Either[String, DbtCtePrepared]) => {
      if (!disposed && generation == token && pending.contains(token) && !session.stopped) {
        pending = None
        result match {
          case Left(error) => continuing = false; status.setText(error)
          case Right(prepared) if !expected.contains(prepared.context) || !currentContext().contains(prepared.context) =>
            continuing = false; session.invalidate(); grid.clear("Source or execution settings changed; no query was launched.")
            status.setText("Source or execution settings changed; Step again.")
          case Right(prepared) =>
            session.synchronize(prepared.frames, prepared.context.execution.mkString("\u0000"))
            frames.clear(); prepared.frames.foreach(c => frames.addElement(c.name))
            session.position = prepared.index; frameList.setSelectedIndex(prepared.index)
            val name = prepared.frames(prepared.index).name
            active = Some(new Active(name, prepared.command, prepared.context, count)); grid.clear("Query running…")
            previewEvidence.showEvidence(None)
            if (!runner.start(prepared.command)) { active = None; continuing = false; status.setText("Another dbt command is already running.") }
        }
      }
    }).submit(AppExecutorUtil.getAppExecutorService)
  })
  override def dispose(): Unit = {
    disposed = true; cancelPreparation(); continuing = false
    if (active.nonEmpty) runner.stop()
    active = None; session.stop()
  }
}
