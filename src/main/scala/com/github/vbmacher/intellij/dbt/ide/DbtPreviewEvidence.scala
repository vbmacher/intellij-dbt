package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.syntax.DbtSyntax
import com.google.gson.{JsonObject, JsonParser}
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.fileEditor.{FileDocumentManager, FileEditorManager, OpenFileDescriptor}
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.ui.components.JBLabel
import java.awt.FlowLayout
import javax.swing.{JButton, JPanel}
import scala.util.Try

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
  private val ansi = "\u001b\\[[0-9;]*[A-Za-z]".r
  private def obj(parent: JsonObject, name: String): Option[JsonObject] =
    Option(parent.get(name)).filter(_.isJsonObject).map(_.getAsJsonObject)
  private def string(parent: JsonObject, name: String): Option[String] =
    Option(parent.get(name)).filter(e => e.isJsonPrimitive && e.getAsJsonPrimitive.isString).map(_.getAsString)
  private final case class Event(info: JsonObject, data: JsonObject) {
    def name: String = string(info, "name").getOrElse("")
    def invocation: String = string(info, "invocation_id").getOrElse("")
    def node: String = obj(data, "node_info").flatMap(string(_, "unique_id")).getOrElse("")
  }
  def capture(command: DbtCommand, output: String, failed: Boolean, canceled: Boolean): DbtPreviewEvidence = {
    val submitted = command.request.scope match {
      case DbtCommandScope.Inline(sql) => sql
      case _ => command.sourceSql
    }
    val events = ansi.replaceAllIn(output, "").linesIterator.flatMap(line => Try(JsonParser.parseString(line)).toOption)
      .filter(_.isJsonObject).flatMap { value =>
        val root = value.getAsJsonObject
        for (info <- obj(root, "info"); data <- obj(root, "data")) yield Event(info, data)
      }.toVector
    // Multiple invocations in one wrapper output are ambiguous; never borrow another invocation's SQL.
    val invocations = events.map(_.invocation).filter(_.nonEmpty).distinct
    val invocation = if (invocations.size == 1) invocations.headOption.filter(id =>
      events.exists(e => e.name == "MainReportVersion" && e.invocation == id)) else None
    val expected = command.previewSource.map(_.nodeId).filter(_.nonEmpty)
    val queries = invocation.toVector.flatMap(id => events.filter(e => e.invocation == id && e.name == "SQLQuery" &&
      expected.contains(e.node) && obj(e.data, "node_info").flatMap(string(_, "node_status")).contains("executing"))
      .flatMap(e => string(e.data, "sql")))
      .filter(sql => sql.nonEmpty && !sql.trim.endsWith("...") &&
        com.github.vbmacher.intellij.dbt.navigation.DbtNavigation.projectedColumns(sql).nonEmpty).distinct
    // Macro SQL and transaction statements are not the preview SQL. A plain query can be located
    // exactly inside its adapter wrapper; otherwise only one emitted statement is unambiguous.
    val containing = queries.filter(sql => submitted.nonEmpty && sql.contains(submitted))
    val selected = if (containing.size == 1) containing.headOption else if (queries.size == 1) queries.headOption else None
    val shown = events.filter(_.name == "ShowNode")
    val matchingShow = shown.exists(e => invocation.contains(e.invocation) && expected.contains(string(e.data, "unique_id").getOrElse("")))
    val adapter = if (canceled || !failed && !matchingShow) None else selected
    val errors = events.filter(e => invocation.contains(e.invocation) && expected.contains(e.node) &&
      (e.name == "CatchableExceptionOnRun" || string(e.info, "level").exists(level => level == "error" || level == "fatal")))
      .flatMap(e => string(e.info, "msg").orElse(string(e.data, "exc"))).distinct
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
  private val details = new JButton("Open preview SQL")
  private val navigate = new JButton("Go to CTE error")
  private val label = new JBLabel
  label.putClientProperty("html.disable", java.lang.Boolean.TRUE)
  details.addActionListener(_ => evidence.foreach { e =>
    DbtSqlEditors.open(project, "dbt submitted preview", e.submitted)
    e.adapterSql.foreach(sql => DbtSqlEditors.open(project, "dbt adapter preview", sql))
  })
  navigate.addActionListener(_ => WriteIntentReadAction.run(() => evidence.flatMap(_.location).foreach { at =>
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
