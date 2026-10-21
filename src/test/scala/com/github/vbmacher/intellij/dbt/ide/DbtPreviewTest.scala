package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.navigation.DbtSqlAnalysis
import com.google.gson.{JsonArray, JsonObject}
import com.intellij.openapi.actionSystem.{ActionUpdateThread, AnAction, AnActionEvent}
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import org.junit.Assert._

import java.awt.datatransfer.DataFlavor
import java.util.concurrent.{CompletableFuture, TimeUnit}
import javax.swing.{JButton, JComboBox, JLabel, JSpinner, JTable, JTextField}
import scala.collection.mutable.ArrayBuffer

/** Query preview: action availability, the result grid and its exports, and mapping adapter errors to authored SQL. */
final class DbtPreviewTest extends DbtTestCase {
  private def component[A](grid: DbtResultGrid, kind: Class[A]): A = components(grid).find(kind.isInstance).map(kind.cast).get

  def testExportSnapshotRetainsFilteredSortedReorderedViewAfterTheGridIsCleared(): Unit = {
    val grid = new DbtResultGrid(getProject)
    val rows = """{"show":[{"id":10,"text":"a,\"b\"\n日本語","kind":"keep"},{"id":2,"text":"","kind":"keep"},{"id":1,"text":null,"kind":"hide"}]}"""
    grid.showResult(DbtQueryResult.parse(rows, "prod", 20, 17, "select * from orders").toOption.get)
    val table = component(grid, classOf[JTable])
    table.getRowSorter.toggleSortOrder(0)
    component(grid, classOf[JTextField]).setText("keep")
    table.moveColumn(1, 0)
    val snapshot = grid.exportSnapshot(',')
    grid.clear("Query canceled")
    val exported = CompletableFuture.supplyAsync(() => snapshot()).get(5, TimeUnit.SECONDS)
    assertEquals("\"text\",\"id\",\"kind\"\r\n\"\",2,\"keep\"\r\n\"a,\"\"b\"\"\n日本語\",10,\"keep\"\r\n", exported)
    assertEquals(0, table.getRowCount)
  }

  def testLargeWideRowsStayBoundedAndExportWithoutTouchingTheClearedGrid(): Unit = {
    val rows = new JsonArray
    val wide = "日本語\t\"quoted\"\n" + "x" * 2048
    (0 until 1002).foreach { index =>
      val row = new JsonObject
      row.addProperty("id", index);
      row.addProperty("wide", wide)
      if (index >= 1000) row.addProperty("outside_limit", true)
      rows.add(row)
    }
    val raw = new JsonObject;
    raw.add("show", rows)
    val result = DbtQueryResult.parse(raw.toString, "analytics-dev", 1000, 81, "select wide").toOption.get
    assertEquals(1000, result.rows.size)
    assertEquals(Vector("id", "wide"), result.columns)
    assertEquals("analytics-dev", result.target);
    assertEquals(81L, result.elapsedMillis)
    val grid = new DbtResultGrid(getProject);
    grid.showResult(result)
    val snapshot = grid.exportSnapshot('\t')
    grid.clear("Closed preview")
    val text = CompletableFuture.supplyAsync(() => snapshot()).get(10, TimeUnit.SECONDS)
    assertTrue(text.contains("999\t\"日本語\t\"\"quoted\"\"\n"))
    assertFalse(text.contains("outside_limit"));
    assertFalse(text.contains("1000\t"))
    assertEquals("Original raw process output remains available", raw.toString, result.raw)
  }

  def testMalformedSqlFluffPositionsNeverBecomeRoundedSourceEdits(): Unit = {
    val template = """[{"filepath":"stdin","violations":[{"code":"CP01","start_file_pos":POSITION,"end_file_pos":6,"fixes":[{"type":"replace","edit":"SELECT","start_file_pos":0,"end_file_pos":6}]}]}]"""
    Vector("0.5", "2147483648", "\"0\"", "null").foreach { position =>
      assertTrue(position, DbtSqlFluff.parse("select 1", template.replace("POSITION", position)).isLeft)
    }
    val badFix = template.replace("POSITION", "0").replace("\"end_file_pos\":6}]", "\"end_file_pos\":5.5}]")
    val diagnostic = DbtSqlFluff.parse("select 1", badFix).toOption.get.head
    assertTrue("A malformed fix retains the diagnostic but offers no source edit", diagnostic.edits.isEmpty)
  }

  def testNestedCteShadowingDoesNotPullAnUnrelatedOuterDefinitionIntoThePreview(): Unit = {
    project()
    val file = configure("models/scopes.sql",
      """with a as (select 99 as id),
container as (with a as (select 1 as id), picked as (select * from a) select * from picked)
select * from container<caret>""")
    val w = workspace
    val analysis = DbtSqlAnalysis.get(w, file).get
    val container = analysis.ctes.find(_.name == "container").get
    assertTrue("The nested a shadows the unrelated outer a", container.dependencies.isEmpty)
    val outerQuery = DbtCteInspection.sql(file.getText, analysis, container).toOption.get
    assertFalse(outerQuery, outerQuery.contains("99"))
    assertTrue(outerQuery, outerQuery.contains("with a as (select 1 as id)"))
    val picked = DbtCteInspection.at(w, file, analysis, file.getText.lastIndexOf("picked")).get
    assertEquals("picked", picked.name)
    val innerQuery = DbtCteInspection.sql(file.getText, analysis, picked).toOption.get
    assertEquals("with a as (select 1 as id),\npicked as (select * from a)\nselect * from picked", innerQuery)
  }

  // Let the native editor fixture supply its normal action DataContext. Do not inject PSI_FILE:
  // real editor contexts compute PSI lazily, which requires background action updates.
  private def presentation(action: AnAction) = {
    assertEquals("PSI-dependent preview actions must update in the background", ActionUpdateThread.BGT, action.getActionUpdateThread)
    val probe = new AnAction {
      override def getActionUpdateThread: ActionUpdateThread = action.getActionUpdateThread

      override def update(event: AnActionEvent): Unit = action.update(event)

      override def actionPerformed(event: AnActionEvent): Unit = ()
    }
    myFixture.testAction(probe)
  }

  def testCtePreviewAndInspectionAreAvailableFromTheNativeSqlEditorContext(): Unit = {
    project()
    configure("models/report.sql", "with a as (select 1 as id) select * from <caret>a")
    Vector(new DbtPreviewCteAction, new DbtInspectCtesAction).foreach { action =>
      val state = presentation(action)
      assertTrue(action.getClass.getName, state.isEnabledAndVisible)
    }
    myFixture.getEditor.getSelectionModel.setSelection(0, myFixture.getEditor.getDocument.getTextLength)
    assertTrue(presentation(new DbtPreviewSqlAction).isEnabledAndVisible)
    myFixture.getEditor.getSelectionModel.removeSelection()
    assertFalse(presentation(new DbtPreviewSqlAction).isEnabledAndVisible)
  }

  def testPreviewActionsRemainHiddenInANonSqlEditor(): Unit = {
    project()
    configure("notes.md", "<caret>with a as (select 1) select * from a")
    Vector(new DbtPreviewCteAction, new DbtInspectCtesAction, new DbtPreviewSqlAction).foreach(action =>
      assertFalse(action.getClass.getName, presentation(action).isEnabledAndVisible))
  }

  private val node = "sql_operation.analytics.inline_query"

  private def event(name: String, sql: String = "", message: String = "", id: String = "run-1",
    owner: String = node, level: String = "debug", status: String = "executing"): String = {
    val root = new JsonObject;
    val info = new JsonObject;
    val data = new JsonObject;
    val identity = new JsonObject
    info.addProperty("name", name);
    info.addProperty("invocation_id", id);
    info.addProperty("level", level);
    info.addProperty("msg", message)
    identity.addProperty("unique_id", owner);
    identity.addProperty("node_status", status);
    data.add("node_info", identity)
    if (sql.nonEmpty) data.addProperty("sql", sql)
    if (name == "ShowNode") data.addProperty("unique_id", owner)
    root.add("info", info);
    root.add("data", data);
    root.toString
  }

  private def command(source: String, query: DbtCteQuery, path: String = "/source/report.sql", url: String = "file:///source/report.sql"): DbtCommand =
    DbtCommand(Vector("dbt"), DbtCommandRequest(DbtCommandAction.Preview, DbtCommandScope.Inline(query.sql)), "/source", Map.empty, "dev", false, "/target", Vector.empty,
      previewSource = Some(DbtPreviewSource(path, source, query.sql, query.spans, node, url)))

  private def position(sql: String, needle: String): (Int, Int) = {
    val offset = sql.indexOf(needle);
    assertTrue(offset >= 0)
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
    val analysis = DbtSqlAnalysis.get(workspace, file).get
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

  private def button(grid: DbtResultGrid, text: String): JButton = components(grid).collectFirst { case b: JButton if b.getText == text => b }.get

  private def result = DbtQueryResult.parse("""{"show":[{"id":10,"text":"a,b\nž\t日本語","optional":null,"flag":true},{"id":2,"text":"","optional":"NULL","flag":false},{"id":3,"text":"wide value","extra":1}]}""",
    "dev", 20, 12, "select * from orders").toOption.get

  def testTypedNullRendererPreservesEmptyLiteralNullUnicodeAndWideValues(): Unit = {
    val grid = new DbtResultGrid(getProject);
    val r = result;
    grid.showResult(r)
    val table = component(grid, classOf[JTable])
    assertEquals(classOf[java.math.BigDecimal], table.getColumnClass(0))
    val nullCell = table.prepareRenderer(table.getCellRenderer(0, 2), 0, 2).asInstanceOf[JLabel]
    assertEquals("NULL", nullCell.getText);
    assertEquals("SQL NULL (not an empty string)", nullCell.getToolTipText)
    val empty = table.prepareRenderer(table.getCellRenderer(1, 1), 1, 1).asInstanceOf[JLabel]
    assertEquals("", empty.getText)
    val text = table.prepareRenderer(table.getCellRenderer(0, 1), 0, 1).asInstanceOf[JLabel]
    assertEquals("a,b\nž\t日本語", text.getText)
    assertTrue(table.getColumnModel.getColumn(1).getPreferredWidth >= 160)
    assertEquals(JTable.AUTO_RESIZE_OFF, table.getAutoResizeMode)
    assertEquals(java.lang.Boolean.TRUE, table.getValueAt(0, 3))
  }

  def testSortingFilteringSelectionCopyAndCsvTsvKeepExactValuesAndViewOrder(): Unit = {
    val grid = new DbtResultGrid(getProject);
    grid.showResult(result)
    val table = component(grid, classOf[JTable])
    table.getRowSorter.toggleSortOrder(0)
    assertEquals(new java.math.BigDecimal("2"), table.getValueAt(0, 0))
    table.setRowSelectionInterval(0, 0);
    table.setColumnSelectionInterval(1, 2)
    button(grid, "Copy selection").doClick()
    assertEquals("\"\"\t\"NULL\"\r\n", CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor))
    val csv = grid.exportText(',')
    assertTrue(csv.startsWith("\"id\",\"text\",\"optional\",\"flag\",\"extra\"\r\n2,"))
    assertTrue(csv.contains("10,\"a,b\nž\t日本語\",NULL,true,NULL"))
    component(grid, classOf[JTextField]).setText("wide value")
    assertEquals(1, table.getRowCount)
    val tsv = grid.exportText('\t')
    assertTrue(tsv.contains("3\t\"wide value\"\tNULL\tNULL\t1"))
    assertFalse(tsv.contains("日本語"))
    component(grid, classOf[JComboBox[_]]).setSelectedItem("TSV")
    assertTrue(button(grid, "Export TSV").isEnabled)
  }

  def testChangingLimitSortingAndFilteringNeverRunsQueriesUntilExplicitButton(): Unit = {
    val requests = ArrayBuffer.empty[Int]
    val grid = new DbtResultGrid(getProject, value => requests += value);
    grid.showResult(result)
    val spinner = component(grid, classOf[JSpinner])
    spinner.setValue(Int.box(100))
    component(grid, classOf[JTextField]).setText("wide")
    component(grid, classOf[JTable]).getRowSorter.toggleSortOrder(0)
    assertTrue(requests.isEmpty)
    button(grid, "Run with limit").doClick();
    assertEquals(Vector(100), requests.toVector)
    grid.setRunning(true);
    assertFalse(button(grid, "Run with limit").isEnabled)
    grid.clear("Query canceled");
    assertEquals(0, component(grid, classOf[JTable]).getRowCount)
    assertFalse(button(grid, "Copy selection").isEnabled);
    assertFalse(button(grid, "Export CSV").isEnabled)
  }

  def testEmptyAndMalformedResultsAreDistinctAndRowsAreBoundedWithHeterogeneousColumns(): Unit = {
    val grid = new DbtResultGrid(getProject)
    grid.showResult(DbtQueryResult.parse("{\"show\":[]}", "prod", 10, 5, "select empty").toOption.get)
    assertTrue(components(grid).collect { case l: JLabel => l.getText }.mkString(" ").contains("No data returned"))
    assertEquals("", grid.exportText(','))
    assertTrue(DbtQueryResult.parse("{\"show\":[1,2]}", "dev", 10, 1, "").isLeft)
    assertTrue(DbtQueryResult.parse("broken", "dev", 10, 1, "").isLeft)
    val bounded = DbtQueryResult.parse("{\"show\":[{\"first\":1},{\"hidden_only\":2}]}", "dev", 1, 1, "").toOption.get
    assertEquals(Vector("first"), bounded.columns);
    assertEquals(1, bounded.rows.size)
    val zero = DbtQueryResult.parse("{\"show\":[{\"id\":1}]}", "dev", 0, 1, "").toOption.get
    assertEquals(1, zero.limit)
    grid.showResult(result);
    grid.clear("Malformed output; see raw dbt output.")
    assertTrue(components(grid).collect { case l: JLabel => l.getText }.mkString(" ").contains("Malformed output"))
  }

  def testNullAwareFilteringAndNumericSortingDoNotConfuseNullWithEmptyText(): Unit = {
    val grid = new DbtResultGrid(getProject)
    grid.showResult(DbtQueryResult.parse("{\"show\":[{\"v\":null},{\"v\":\"\"},{\"v\":\"NULL\"}]}", "dev", 20, 1, "").toOption.get)
    component(grid, classOf[JTextField]).setText("NULL")
    assertEquals(2, component(grid, classOf[JTable]).getRowCount)
    assertEquals("\"v\"\r\nNULL\r\n\"NULL\"\r\n", grid.exportText(','))
    component(grid, classOf[JTextField]).setText("")
    grid.showResult(DbtQueryResult.parse("{\"show\":[{\"v\":10},{\"v\":null},{\"v\":2}]}", "dev", 20, 1, "").toOption.get)
    val table = component(grid, classOf[JTable])
    table.getRowSorter.toggleSortOrder(0)
    assertNull(table.getValueAt(0, 0))
    assertEquals(new java.math.BigDecimal("2"), table.getValueAt(1, 0))
    assertEquals(new java.math.BigDecimal("10"), table.getValueAt(2, 0))
  }

}
