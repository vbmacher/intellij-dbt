package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.navigation.{DbtCte, DbtNavigation, DbtSqlAnalysis}
import com.github.vbmacher.intellij.dbt.project._
import com.github.vbmacher.intellij.dbt.syntax.DbtSyntax
import com.google.gson.{JsonObject, JsonParser}
import com.intellij.openapi.actionSystem.{ActionUpdateThread, AnAction, AnActionEvent, CommonDataKeys}
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.{FileDocumentManager, FileEditorManager, OpenFileDescriptor}
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiFile
import com.intellij.ui.components.JBLabel
import java.awt.FlowLayout
import javax.swing.{JButton, JPanel}
import scala.util.Try

final class DbtPreviewSqlAction extends AnAction("Preview selected dbt SQL") {
  override def getActionUpdateThread: ActionUpdateThread = ActionUpdateThread.BGT
  override def update(event: AnActionEvent): Unit = event.getPresentation.setEnabledAndVisible(
    DbtEditorAction.sql(event, editor = true) && event.getData(CommonDataKeys.EDITOR).getSelectionModel.hasSelection)
  override def actionPerformed(event: AnActionEvent): Unit = DbtCteInspection.launchPreview[DbtCommand](event, "Preview selected dbt SQL") {
    (file, editor) =>
      val sql = Option(editor.getSelectionModel.getSelectedText).filter(_.trim.nonEmpty)
      (w, before) => for {
        text <- sql.toRight("Select SQL to preview.")
        _ <- Either.cond(DbtNavigation.projectedColumns(text).nonEmpty,
          (), "Select one supported SELECT query to preview. Statements that modify data or schema cannot be previewed.")
        model <- w.model(file).toRight("Choose a dbt source model.")
        _ <- Either.cond(before == DbtCteInspection.context(w, model), (), "Source or execution settings changed; select the query again.")
        command <- DbtCteInspection.command(w, model, text)
      } yield command
  } { (command, before, current) =>
    Either.cond(before.nonEmpty && before == current(), command, "Source or execution settings changed; no query was launched.")
  }
}

final class DbtPreviewCteAction extends AnAction("Preview dbt CTE") {
  override def getActionUpdateThread: ActionUpdateThread = ActionUpdateThread.BGT
  override def update(event: AnActionEvent): Unit = event.getPresentation.setEnabledAndVisible(DbtEditorAction.sql(event, editor = true))
  override def actionPerformed(event: AnActionEvent): Unit =
    DbtCteInspection.launchPreview[(DbtCteContext, DbtCommand)](event, "Preview dbt CTE") { (file, editor) =>
      val offset = editor.getCaretModel.getOffset
      (w, _) => for {
        resource <- w.model(file).toRight("Choose a dbt source model.")
        snapshot <- DbtCteInspection.context(w, resource).toRight("The execution project is unavailable.")
        analysis <- DbtSqlAnalysis.get(w, file).toRight("This SQL cannot be inspected safely.")
        cte <- DbtCteInspection.at(w, file, analysis, offset)
          .toRight("Place the caret at a recognized CTE definition or reference.")
        query <- DbtCteInspection.plan(snapshot.source, analysis, cte)
        command <- DbtCteInspection.command(w, resource, query.sql, query.spans)
      } yield snapshot -> command
    } { case ((snapshot, command), expected, current) =>
      Either.cond(expected.contains(snapshot) && current().contains(snapshot), command,
        "Source or execution settings changed; no query was launched. Invoke Preview CTE again.")
    }
}

private[ide] object DbtCteInspection {
  /** Top-level CTEs: the inspectable query steps of a model. */
  def frames(analysis: DbtSqlAnalysis): Vector[DbtCte] = analysis.ctes.filter(c => !analysis.ctes.exists(p => p.body.contains(c.range)))

  /**
   * Editor preview flow: `input` reads the editor before documents are saved and committed, then prepares off the EDT
   * against the model context captured before preparation. `verify` sees that context and the current one on the EDT.
   */
  def launchPreview[T](event: AnActionEvent, title: String)(
    input: (PsiFile, Editor) => (DbtWorkspace, Option[DbtCteContext]) => Either[String, T])(
    verify: (T, Option[DbtCteContext], () => Option[DbtCteContext]) => Either[String, DbtCommand]): Unit = WriteIntentReadAction.run(() =>
    DbtEditorAction.editorContext(event).foreach { case (project, file, editor) =>
      val prepare = input(file, editor)
      DbtUi.saveAndCommit(project)
      def current(): Option[DbtCteContext] = DbtUi.read { val w = DbtWorkspace.get(project); w.model(file).flatMap(context(w, _)) }
      val before = current()
      DbtUi.readLater(project)(prepare(DbtWorkspace.get(project), before)) { result =>
        result.flatMap(verify(_, before, () => current())) match {
          case Left(error) => Messages.showInfoMessage(project, error, title)
          case Right(command) => DbtRunConfiguration.launch(project, command)
        }
      }
    })

  def at(w: DbtWorkspace, file: PsiFile, analysis: DbtSqlAnalysis, offset: Int): Option[DbtCte] = {
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
    val first = frames(analysis).sortBy(_.range.getStartOffset).headOption
    first.toRight("The outer WITH clause is unavailable.").flatMap { initial =>
      val blocks = DbtSyntax.blocks(text)
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
      val name = if (DbtSyntax.isIdentifier(cte.name)) cte.name else "\"" + cte.name.replace("\"", "\"\"") + "\""
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
      root.psiFile(root.profilesFile).map(f => DbtHash.sha256(f.getText)).getOrElse(""))
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


private[ide] final case class DbtCteContext(source: String, stamp: Long, execution: Vector[String],
  dependencies: Vector[(String, Long)])

/** Only unchanged authored spans can be navigated after CTE extraction and adapter wrapping. */
private[ide] final case class DbtPreviewSpan(queryStart: Int, sourceStart: Int, length: Int)
private[ide] final case class DbtCteQuery(sql: String, spans: Vector[DbtPreviewSpan])
private[ide] final case class DbtPreviewSource(path: String, text: String, query: String,
  spans: Vector[DbtPreviewSpan], nodeId: String, fileUrl: String)
private[ide] final case class DbtPreviewLocation(source: DbtPreviewSource, offset: Int, line: Int, column: Int)
private[ide] final case class DbtPreviewEvidence(submitted: String, adapterSql: Option[String], raw: String,
  invocation: Option[String], location: Option[DbtPreviewLocation], explanation: String) {
  def displaySql: String = adapterSql.fold("Adapter SQL unavailable: this CLI/adapter did not emit one unambiguous SQLQuery event for this preview.")(
    sql => "Adapter SQL reported by dbt (execution may have failed):\n" + sql) +
    "\n\nSubmitted SQL (before dbt compilation):\n" + submitted + "\n\n" + explanation
}

private[ide] object DbtPreviewEvidence {
  import DbtJson.{child, text}
  private final case class Event(info: JsonObject, data: JsonObject) {
    def name: String = text(info, "name").getOrElse("")
    def invocation: String = text(info, "invocation_id").getOrElse("")
    def node: String = child(data, "node_info").flatMap(text(_, "unique_id")).getOrElse("")
  }
  def capture(command: DbtCommand, output: String, failed: Boolean, canceled: Boolean): DbtPreviewEvidence = {
    val submitted = command.request.scope match {
      case DbtCommandScope.Inline(sql) => sql
      case _ => command.sourceSql
    }
    val events = DbtPreview.Ansi.replaceAllIn(output, "").linesIterator.flatMap(line => Try(JsonParser.parseString(line)).toOption)
      .filter(_.isJsonObject).flatMap { value =>
        val root = value.getAsJsonObject
        for (info <- child(root, "info"); data <- child(root, "data")) yield Event(info, data)
      }.toVector
    // Multiple invocations in one wrapper output are ambiguous; never borrow another invocation's SQL.
    val invocations = events.map(_.invocation).filter(_.nonEmpty).distinct
    val invocation = if (invocations.size == 1) invocations.headOption.filter(id =>
      events.exists(e => e.name == "MainReportVersion" && e.invocation == id)) else None
    val expected = command.previewSource.map(_.nodeId).filter(_.nonEmpty)
    val queries = invocation.toVector.flatMap(id => events.filter(e => e.invocation == id && e.name == "SQLQuery" &&
      expected.contains(e.node) && child(e.data, "node_info").flatMap(text(_, "node_status")).contains("executing"))
      .flatMap(e => text(e.data, "sql")))
      .filter(sql => sql.nonEmpty && !sql.trim.endsWith("...") &&
        DbtNavigation.projectedColumns(sql).nonEmpty).distinct
    // Macro SQL and transaction statements are not the preview SQL. A plain query can be located
    // exactly inside its adapter wrapper; otherwise only one emitted statement is unambiguous.
    val containing = queries.filter(sql => submitted.nonEmpty && sql.contains(submitted))
    val selected = if (containing.size == 1) containing.headOption else if (queries.size == 1) queries.headOption else None
    val shown = events.filter(_.name == "ShowNode")
    val matchingShow = shown.exists(e => invocation.contains(e.invocation) && expected.contains(text(e.data, "unique_id").getOrElse("")))
    val adapter = if (canceled || !failed && !matchingShow) None else selected
    val errors = events.filter(e => invocation.contains(e.invocation) && expected.contains(e.node) &&
      (e.name == "CatchableExceptionOnRun" || text(e.info, "level").exists(level => level == "error" || level == "fatal")))
      .flatMap(e => text(e.info, "msg").orElse(text(e.data, "exc"))).distinct
    val location = if (!failed || canceled) None else for {
      sql <- adapter
      source <- command.previewSource
      positions = errors.flatMap(errorPositions).distinct
      position <- if (positions.size == 1) positions.headOption else None
      offset <- authoredOffset(source, sql, position._1, position._2)
    } yield {
      val before = source.text.take(offset)
      DbtPreviewLocation(source, offset, before.count(_ == '\n') + 1, offset - before.lastIndexOf('\n'))
    }
    val explanation = if (canceled) "Preview canceled; no SQL execution or error location is certified."
      else if (!failed) "Adapter SQL is available only when the current invocation identifies its preview statement."
      else location.fold("Error location unavailable: an exact unchanged authored span and a supported unambiguous adapter location were not both available. See raw output.")(
        at => s"Error maps to authored CTE source at line ${at.line}, column ${at.column}. Navigation is disabled if the source changes.")
    DbtPreviewEvidence(submitted, adapter, output, invocation, location, explanation)
  }

  /** Recognized adapter formats only: one-based line/column and Snowflake's zero-based position. */
  private[ide] def errorPositions(message: String): Vector[(Int, Int)] = {
    val bracket = "(?i)\\bat \\[([1-9][0-9]*):([1-9][0-9]*)\\]".r.findAllMatchIn(message).flatMap(m =>
      for (line <- m.group(1).toIntOption; column <- m.group(2).toIntOption) yield line -> column)
    val snowflake = "(?i)\\bline ([1-9][0-9]*) at position ([0-9]+)\\b".r.findAllMatchIn(message).flatMap(m =>
      for (line <- m.group(1).toIntOption; position <- m.group(2).toIntOption if position < Int.MaxValue) yield line -> (position + 1))
    (bracket ++ snowflake).toVector.distinct
  }
  private[ide] def authoredOffset(source: DbtPreviewSource, adapter: String, line: Int, column: Int): Option[Int] = {
    val query = source.query
    val start = if (query.nonEmpty) adapter.indexOf(query) else -1
    val lines = adapter.split("\n", -1).toVector
    for {
      _ <- Option.when(start >= 0 && adapter.indexOf(query, start + 1) < 0 && DbtSyntax.blocks(query).isEmpty)(())
      row <- lines.lift(line - 1) if line > 0 && column > 0 && column <= row.length
      // Adapters disagree on Unicode column units. Refuse non-ASCII prefixes rather than rounding.
      _ <- Option.when(row.take(column).forall(_ <= 127))(())
      adapterOffset = lines.take(line - 1).map(_.length + 1).sum + column - 1
      queryOffset = adapterOffset - start
      span <- source.spans.find(s => queryOffset >= s.queryStart && queryOffset < s.queryStart.toLong + s.length)
      sourceOffset = span.sourceStart + queryOffset - span.queryStart
      _ <- Option.when(span.length >= 0 && span.queryStart >= 0 && span.sourceStart >= 0 &&
        span.queryStart.toLong + span.length <= query.length && span.sourceStart.toLong + span.length <= source.text.length &&
        query.substring(span.queryStart, span.queryStart + span.length) == source.text.substring(span.sourceStart, span.sourceStart + span.length))(())
    } yield sourceOffset
  }
}

/** Shared by the Runner and CTE inspector; failed queries have no result grid to hold these controls. */
private[ide] final class DbtPreviewEvidencePanel(project: Project) extends JPanel(new FlowLayout(FlowLayout.LEFT)) {
  private var evidence = Option.empty[DbtPreviewEvidence]
  private val label = new JBLabel
  label.putClientProperty("html.disable", java.lang.Boolean.TRUE)
  private val details = DbtUi.button("Open preview SQL")(evidence.foreach { e =>
    DbtSqlEditors.open(project, "dbt submitted preview", e.submitted)
    e.adapterSql.foreach(sql => DbtSqlEditors.open(project, "dbt adapter preview", sql))
  })
  private val navigate: JButton = DbtUi.button("Go to CTE error")(WriteIntentReadAction.run(() => evidence.flatMap(_.location).foreach { at =>
    val file = Option(VirtualFileManager.getInstance.findFileByUrl(at.source.fileUrl))
    val current = file.flatMap(f => Option(FileDocumentManager.getInstance.getDocument(f)))
    if (current.exists(_.getText == at.source.text)) file.foreach { f =>
      val descriptor = new OpenFileDescriptor(project, f, at.offset)
      Option(FileEditorManager.getInstance(project).openTextEditor(descriptor, true)).foreach(descriptor.navigateIn)
    }
    else { navigate.setEnabled(false); label.setText("Source changed; rerun the preview before navigating to its error.") }
  }))
  add(details); add(navigate); add(label)
  def showEvidence(value: Option[DbtPreviewEvidence]): Unit = {
    evidence = value
    details.setEnabled(value.nonEmpty); navigate.setEnabled(value.exists(_.location.nonEmpty))
    label.setText(value.map(_.explanation).getOrElse(""))
  }
  showEvidence(None)
}
