package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.navigation.DbtSqlAnalysis
import com.github.vbmacher.intellij.dbt.project.DbtWorkspace
import com.google.gson.JsonObject
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import java.awt.{Component, Container}
import javax.swing.{JButton, JLabel}
import org.junit.Assert._

final class DbtPreviewEvidenceTest extends DbtCodeInsightTestCase {
  private val node = "sql_operation.analytics.inline_query"
  private def event(name: String, sql: String = "", message: String = "", id: String = "run-1",
    owner: String = node, level: String = "debug", status: String = "executing"): String = {
    val root = new JsonObject; val info = new JsonObject; val data = new JsonObject; val identity = new JsonObject
    info.addProperty("name", name); info.addProperty("invocation_id", id); info.addProperty("level", level); info.addProperty("msg", message)
    identity.addProperty("unique_id", owner); identity.addProperty("node_status", status); data.add("node_info", identity)
    if (sql.nonEmpty) data.addProperty("sql", sql)
    if (name == "ShowNode") data.addProperty("unique_id", owner)
    root.add("info", info); root.add("data", data); root.toString
  }
  private def command(source: String, query: DbtCteQuery, path: String = "/source/report.sql", url: String = "file:///source/report.sql"): DbtCommand =
    DbtCommand(Vector("dbt", "show", "--inline", query.sql), "/source", Map.empty, "Preview", "dev", false, "/target", Vector.empty,
      previewSource = Some(DbtPreviewSource(path, source, query.sql, query.spans, node, url)))
  private def position(sql: String, needle: String): (Int, Int) = {
    val offset = sql.indexOf(needle); assertTrue(offset >= 0)
    val before = sql.take(offset)
    (before.count(_ == '\n') + 1, offset - before.lastIndexOf('\n'))
  }
  private def failed(command: DbtCommand, adapter: String, needle: String, suffix: String = ""): DbtPreviewEvidence = {
    val at = position(adapter, needle)
    DbtPreviewEvidence.capture(command, Vector(event("MainReportVersion"), event("SQLQuery", adapter),
      event("CatchableExceptionOnRun", message = s"SQL compilation error: line ${at._1} at position ${at._2 - 1}"), suffix).mkString("\n"),
      failed = true, canceled = false)
  }

  def testExtractedDependencySpansMapAnAdapterWrappedErrorToTheExactAuthoredCte(): Unit = {
    project()
    val source = "-- authored setup\nwith unused as (select 8),\nbase as (\n  select missing as id\n),\nreport as (select id from base)\nselect * from report"
    val file = configure("models/report.sql", source + "<caret>")
    val analysis = DbtSqlAnalysis.get(DbtWorkspace.get(getProject), file).get
    val query = DbtCteInspection.plan(source, analysis, analysis.ctes.find(_.name == "report").get).toOption.get
    assertFalse(query.sql.contains("unused"))
    val sql = "/* adapter query comment */\nselect * from (\n" + query.sql + "\n) as preview limit 10"
    val report = failed(command(source, query, file.getVirtualFile.getPath), sql, "missing")
    assertEquals(Some(sql), report.adapterSql)
    assertEquals(source.indexOf("missing"), report.location.get.offset)
    assertEquals(4, report.location.get.line)
    assertEquals(10, report.location.get.column)
    assertTrue(report.displaySql.contains("Submitted SQL (before dbt compilation)"))
    assertTrue(report.displaySql.contains("execution may have failed"))
  }

  def testSuccessfulSqlRequiresTheMatchingPreviewIdentityAndCurrentInvocation(): Unit = {
    val query = DbtCteQuery("select 1 as id", Vector(DbtPreviewSpan(0, 0, 14)))
    val cmd = command(query.sql, query)
    val sql = query.sql + " limit 10"
    val logs = Vector(event("MainReportVersion"), event("SQLQuery", "select 999", owner = "model.other.inline_query"),
      event("SQLQuery", "BEGIN"), event("SQLQuery", sql), event("ShowNode")).mkString("\n")
    val report = DbtPreviewEvidence.capture(cmd, logs, failed = false, canceled = false)
    assertEquals(Some(sql), report.adapterSql)
    assertTrue(report.location.isEmpty)
    assertTrue(DbtPreviewEvidence.capture(cmd, logs.replace("\"ShowNode\"", "\"Other\""), false, false).adapterSql.isEmpty)
    assertTrue(DbtPreviewEvidence.capture(cmd, logs + "\n" + event("SQLQuery", sql, id = "older-run"), false, false).adapterSql.isEmpty)
    assertTrue(DbtPreviewEvidence.capture(cmd, logs.linesIterator.drop(1).mkString("\n"), false, false).adapterSql.isEmpty)
    assertTrue(DbtPreviewEvidence.capture(cmd, logs, failed = true, canceled = true).adapterSql.isEmpty)
    val compiling = Vector(event("MainReportVersion"), event("SQLQuery", sql, status = "compiling"),
      event("CatchableExceptionOnRun", message = "line 1 at position 7", status = "compiling")).mkString("\n")
    assertTrue("Compile-time macro queries cannot stand in for the preview's adapter SQL",
      DbtPreviewEvidence.capture(cmd, compiling, failed = true, canceled = false).adapterSql.isEmpty)
  }

  def testGeneratedSqlJinjaAmbiguousQueriesAndUnsupportedErrorLocationsNeverInventSourceOffsets(): Unit = {
    val source = "select broken as id"
    val query = DbtCteQuery(source, Vector(DbtPreviewSpan(0, 0, source.length)))
    val cmd = command(source, query)
    assertTrue(failed(cmd, source + "\nselect generated", "generated").location.isEmpty)
    assertTrue(failed(cmd, source + " union all " + source, "broken").location.isEmpty)
    val jinja = "select {{ 1 }} as broken"
    assertTrue(failed(command(jinja, DbtCteQuery(jinja, Vector(DbtPreviewSpan(0, 0, jinja.length)))), jinja, "broken").location.isEmpty)
    val transformed = Vector(event("MainReportVersion"), event("SQLQuery", "select 1"), event("SQLQuery", "select 2"),
      event("CatchableExceptionOnRun", message = "line 1 at position 7")).mkString("\n")
    assertTrue(DbtPreviewEvidence.capture(cmd, transformed, true, false).adapterSql.isEmpty)
    val unavailable = DbtPreviewEvidence.capture(cmd, "query failed at CTE", true, false)
    assertTrue(unavailable.location.isEmpty)
    assertTrue(unavailable.adapterSql.isEmpty)
    assertTrue(unavailable.explanation.contains("Error location unavailable"))
    assertFalse(unavailable.displaySql.contains("Compiled SQL:"))
    assertTrue(DbtPreviewEvidence.errorPositions("line 99999999999999999 at position 0; at [0:1]; line 1 at position 2147483647").isEmpty)
    assertTrue(DbtPreviewEvidence.authoredOffset(cmd.previewSource.get, source, 1, 999).isEmpty)
    val unicode = "select '😀' as broken"
    assertTrue(failed(command(unicode, DbtCteQuery(unicode, Vector(DbtPreviewSpan(0, 0, unicode.length)))), unicode, "broken").location.isEmpty)
  }

  def testBracketErrorCoordinatesAndInvalidSpanBoundsAreConservative(): Unit = {
    val sql = "select broken as id"
    val query = DbtCteQuery(sql, Vector(DbtPreviewSpan(0, 0, sql.length)))
    val cmd = command(sql, query)
    val logs = Vector(event("MainReportVersion"), event("SQLQuery", sql),
      event("GenericExceptionOnRun", message = "Syntax error at [1:8]", level = "error")).mkString("\n")
    assertEquals(Some(7), DbtPreviewEvidence.capture(cmd, logs, true, false).location.map(_.offset))
    val invalid = cmd.previewSource.get.copy(spans = Vector(DbtPreviewSpan(0, Int.MaxValue, 20)))
    assertTrue(DbtPreviewEvidence.authoredOffset(invalid, sql, 1, 8).isEmpty)
    assertTrue(DbtPreviewEvidence.authoredOffset(invalid.copy(spans = Vector(DbtPreviewSpan(-1, 0, Int.MaxValue))), sql, 1, 8).isEmpty)
  }

  def testNativeErrorNavigationUsesTheAuthoredOffsetAndRefusesStaleSource(): Unit = {
    project()
    val source = "with orders as (select missing as id) select * from orders"
    val file = configure("models/report.sql", source + "<caret>")
    val query = DbtCteQuery(source, Vector(DbtPreviewSpan(0, 0, source.length)))
    val report = failed(command(source, query, file.getVirtualFile.getPath, file.getVirtualFile.getUrl), source, "missing")
    val panel = new DbtPreviewEvidencePanel(getProject)
    panel.showEvidence(Some(report))
    def components(component: Component): Vector[Component] = Vector(component) ++ (component match {
      case container: Container => container.getComponents.toVector.flatMap(components)
      case _ => Vector.empty
    })
    val button = components(panel).collectFirst { case b: JButton if b.getText == "Go to CTE error" => b }.get
    assertTrue(button.isEnabled)
    val application = com.intellij.openapi.application.ApplicationManager.getApplication
    application.getThreadingSupport.releaseTheAcquiredWriteIntentLockThenExecuteActionAndTakeWriteIntentLockBack[java.lang.Boolean](() => {
      assertFalse("A normal Swing click begins without implicit read access", application.isReadAccessAllowed)
      button.doClick()
      java.lang.Boolean.TRUE
    })
    val editor = FileEditorManager.getInstance(getProject).getSelectedTextEditor
    assertEquals(source.indexOf("missing"), editor.getCaretModel.getOffset)
    WriteCommandAction.runWriteCommandAction(getProject, new Runnable {
      override def run(): Unit = editor.getDocument.insertString(0, "-- changed\n")
    })
    editor.getCaretModel.moveToOffset(0)
    button.doClick()
    assertEquals(0, editor.getCaretModel.getOffset)
    assertFalse(button.isEnabled)
    assertTrue(components(panel).collect { case label: JLabel => label.getText }.exists(_.contains("Source changed")))
    panel.showEvidence(None)
    assertTrue(components(panel).collect { case b: JButton => b }.forall(!_.isEnabled))
  }
}
