package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.navigation.DbtSqlAnalysis
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import org.junit.Assert.*

import java.awt.datatransfer.DataFlavor
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JSpinner
import javax.swing.JTable
import javax.swing.JTextField

/** Query preview: action availability, the result grid and its exports, and mapping adapter errors to authored SQL. */
class DbtPreviewTest : DbtTestCase() {
    fun testLoadedEnvironmentChangesInvalidateTheCteSnapshot() {
        project()
        val model = add(
            "models/orders.sql",
            "with chosen as (select '{{ env_var(\"TENANT\") }}' as tenant) select * from chosen"
        )
        val environment = add(".env", "TENANT=before\n")
        val before = workspace
        val context = DbtCteInspection.context(before, before.model(model)!!)!!
        edit(environment, "TENANT=after\n")
        val after = workspace
        assertEquals(
            "after",
            com.github.vbmacher.intellij.dbt.project.DbtExecutionContext.environment(after.root(model)!!)["TENANT"]
        )
        assertNotEquals(context, DbtCteInspection.context(after, after.model(model)!!)!!)
        configureSettings { it.loadEnvironmentFiles = false }
        val disabled = workspace
        val disabledContext = DbtCteInspection.context(disabled, disabled.model(model)!!)!!
        edit(environment, "TENANT=ignored\n")
        val unchanged = workspace
        assertEquals(disabledContext, DbtCteInspection.context(unchanged, unchanged.model(model)!!)!!)
    }

    private fun <A> component(grid: DbtResultGrid, kind: Class<A>): A =
        kind.cast(components(grid).first { kind.isInstance(it) })

    fun testExportSnapshotRetainsFilteredSortedReorderedViewAfterTheGridIsCleared() {
        val grid = DbtResultGrid(getProject())
        val rows =
            """{"show":[{"id":10,"text":"a,\"b\"\n日本語","kind":"keep"},{"id":2,"text":"","kind":"keep"},{"id":1,"text":null,"kind":"hide"}]}"""
        grid.showResult(DbtQueryResult.parse(rows, "prod", 20, 17, "select * from orders").getOrThrow())
        val table = component(grid, JTable::class.java)
        table.rowSorter.toggleSortOrder(0)
        component(grid, JTextField::class.java).setText("keep")
        table.moveColumn(1, 0)
        val snapshot = grid.exportSnapshot(',')
        grid.clear("Query canceled")
        val exported = CompletableFuture.supplyAsync { snapshot() }.get(5, TimeUnit.SECONDS)
        assertEquals("\"text\",\"id\",\"kind\"\r\n\"\",2,\"keep\"\r\n\"a,\"\"b\"\"\n日本語\",10,\"keep\"\r\n", exported)
        assertEquals(0, table.rowCount)
    }

    fun testExportRequiresConfirmationBeforeReplacingAnExistingFile() {
        val grid = DbtResultGrid(getProject())
        grid.showResult(DbtQueryResult.parse("""{"show":[{"id":7}]}""", "dev", 10, 1, "select 7").getOrThrow())
        val directory = tempDirectory("preview export")
        val destination = directory.resolve("report.csv")
        java.nio.file.Files.writeString(destination, "existing report")
        var prompts = 0
        var answer = com.intellij.openapi.ui.Messages.NO
        val previous = TestDialogManager.setTestDialog(object : TestDialog {
            override fun show(text: String): Int {
                prompts += 1; return answer
            }
        })
        try {
            grid.exportTo(destination)
            assertEquals(1, prompts)
            assertEquals("existing report", java.nio.file.Files.readString(destination))
            answer = com.intellij.openapi.ui.Messages.YES
            grid.exportTo(destination)
            await("Confirmed export replaces the report") {
                java.nio.file.Files.readString(destination).startsWith("\"id\"")
            }
            assertEquals(2, prompts)
            val fresh = directory.resolve("new.csv")
            grid.exportTo(fresh)
            await("A new destination needs no replacement prompt") { java.nio.file.Files.exists(fresh) }
            assertEquals(2, prompts)
            java.nio.file.Files.writeString(destination, "confirmed by native save dialog")
            grid.exportTo(destination, overwriteConfirmed = true)
            await("Native save confirmation is not repeated") {
                java.nio.file.Files.readString(destination).startsWith("\"id\"")
            }
            assertEquals(2, prompts)
        } finally {
            TestDialogManager.setTestDialog(previous)
        }
    }

    fun testLargeWideRowsStayBoundedAndExportWithoutTouchingTheClearedGrid() {
        val rows = JsonArray()
        val wide = "日本語\t\"quoted\"\n" + "x".repeat(2048)
        (0 until 1002).forEach { index ->
            val row = JsonObject()
            row.addProperty("id", index);
            row.addProperty("wide", wide)
            if (index >= 1000) row.addProperty("outside_limit", true)
            rows.add(row)
        }
        val raw = JsonObject();
        raw.add("show", rows)
        val result = DbtQueryResult.parse(raw.toString(), "analytics-dev", 1000, 81, "select wide").getOrThrow()
        assertEquals(1000, result.rows.size)
        assertEquals(listOf("id", "wide"), result.columns)
        assertEquals("analytics-dev", result.target);
        assertEquals(81L, result.elapsedMillis)
        val grid = DbtResultGrid(getProject());
        grid.showResult(result)
        val snapshot = grid.exportSnapshot('\t')
        grid.clear("Closed preview")
        val text = CompletableFuture.supplyAsync { snapshot() }.get(10, TimeUnit.SECONDS)
        assertTrue(text.contains("999\t\"日本語\t\"\"quoted\"\"\n"))
        assertFalse(text.contains("outside_limit"));
        assertFalse(text.contains("1000\t"))
        assertEquals("Original raw process output remains available", raw.toString(), result.raw)
    }

    fun testMalformedSqlFluffPositionsNeverBecomeRoundedSourceEdits() {
        val template =
            """[{"filepath":"stdin","violations":[{"code":"CP01","start_file_pos":POSITION,"end_file_pos":6,"fixes":[{"type":"replace","edit":"SELECT","start_file_pos":0,"end_file_pos":6}]}]}]"""
        listOf("0.5", "2147483648", "\"0\"", "null").forEach { position ->
            assertTrue(position, DbtSqlFluff.parse("select 1", template.replace("POSITION", position)).isFailure)
        }
        val badFix = template.replace("POSITION", "0").replace("\"end_file_pos\":6}]", "\"end_file_pos\":5.5}]")
        val diagnostic = DbtSqlFluff.parse("select 1", badFix).getOrThrow().first()
        assertTrue("A malformed fix retains the diagnostic but offers no source edit", diagnostic.edits.isEmpty())
    }

    fun testNestedCteShadowingDoesNotPullAnUnrelatedOuterDefinitionIntoThePreview() {
        project()
        val file = configure(
            "models/scopes.sql",
            """with a as (select 99 as id),
container as (with a as (select 1 as id), picked as (select * from a) select * from picked)
select * from container<caret>"""
        )
        val w = workspace
        val analysis = DbtSqlAnalysis.get(w, file)!!
        val container = analysis.ctes.find { it.name == "container" }!!
        assertTrue("The nested a shadows the unrelated outer a", container.dependencies.isEmpty())
        val outerQuery = DbtCteInspection.sql(file.text, analysis, container).getOrThrow()
        assertFalse(outerQuery, outerQuery.contains("99"))
        assertTrue(outerQuery, outerQuery.contains("with a as (select 1 as id)"))
        val picked = DbtCteInspection.at(w, file, analysis, file.text.lastIndexOf("picked"))!!
        assertEquals("picked", picked.name)
        val innerQuery = DbtCteInspection.sql(file.text, analysis, picked).getOrThrow()
        assertEquals("with a as (select 1 as id),\npicked as (select * from a)\nselect * from picked", innerQuery)
    }

    // Let the native editor fixture supply its normal action DataContext. Do not inject PSI_FILE:
    // real editor contexts compute PSI lazily, which requires background action updates.
    private fun presentation(action: AnAction): com.intellij.openapi.actionSystem.Presentation {
        assertEquals(
            "PSI-dependent preview actions must update in the background",
            ActionUpdateThread.BGT,
            action.actionUpdateThread
        )
        val probe = object : AnAction() {
            override fun getActionUpdateThread(): ActionUpdateThread = action.actionUpdateThread

            override fun update(event: AnActionEvent): Unit = action.update(event)

            override fun actionPerformed(event: AnActionEvent) {}
        }
        return myFixture.testAction(probe)
    }

    fun testCtePreviewAndInspectionAreAvailableFromTheNativeSqlEditorContext() {
        project()
        configure("models/report.sql", "with a as (select 1 as id) select * from <caret>a")
        listOf(DbtPreviewCteAction(), DbtInspectCtesAction()).forEach { action ->
            val state = presentation(action)
            assertTrue(action.javaClass.name, state.isEnabledAndVisible)
        }
        myFixture.editor.selectionModel.setSelection(0, myFixture.editor.document.textLength)
        assertTrue(presentation(DbtPreviewSqlAction()).isEnabledAndVisible)
        myFixture.editor.selectionModel.removeSelection()
        assertFalse(presentation(DbtPreviewSqlAction()).isEnabledAndVisible)
    }

    fun testPreviewActionsRemainHiddenInANonSqlEditor() {
        project()
        configure("notes.md", "<caret>with a as (select 1) select * from a")
        listOf(DbtPreviewCteAction(), DbtInspectCtesAction(), DbtPreviewSqlAction()).forEach { action ->
            assertFalse(action.javaClass.name, presentation(action).isEnabledAndVisible)
        }
    }

    fun testCtePreviewExplainsTheParseFailureAndOffersTheCompleteModelPreview() {
        project()
        configure("models/report.sql", "with a as (select 1 as id)\nselect * from a where )<caret>")
        var message: String? = null
        val previous = TestDialogManager.setTestDialog(object : TestDialog {
            override fun show(text: String): Int {
                message = text
                return com.intellij.openapi.ui.Messages.NO
            }
        })
        try {
            myFixture.testAction(DbtPreviewCteAction())
            await("The preview explains why SQL analysis failed") { message != null }
            val text = message!!
            assertTrue(text, text.startsWith("Cannot preview this CTE."))
            assertTrue(text, text.contains("could not parse this model's SQL"))
            assertTrue(text, text.contains("line 2, column"))
            assertTrue(text, text.contains("syntax the plugin does not support"))
            assertTrue(text, text.contains("No preview query was run."))
            assertTrue(text, text.contains("Choose Preview model"))
            assertFalse(getProject().getService(DbtRunner::class.java).isRunning)
        } finally {
            TestDialogManager.setTestDialog(previous)
        }
    }

    fun testSqlAnalysisExplainsTheSizeLimitAndUnsupportedStatements() {
        project()
        val large = add("models/large.sql", "--" + "x".repeat(1000000))
        val update = add("models/update.sql", "update orders set id = 1")
        assertEquals(
            "This file exceeds the SQL analysis limit of 1,000,000 characters.",
            DbtSqlAnalysis.result(workspace, large).exceptionOrNull()?.message
        )
        assertEquals(
            "SQL analysis requires a SELECT query. This file contains a different SQL statement.",
            DbtSqlAnalysis.result(workspace, update).exceptionOrNull()?.message
        )
        assertTrue(DbtSqlAnalysis.get(workspace, update) == null)
    }

    fun testSqlAnalysisExplainsFilesOutsideResourcePathsAndMissingProviders() {
        project()
        val outside = add("notes/report.sql", "select 1")
        val markdown = add("notes/report.md", "select 1")
        assertEquals(
            "This file is outside the dbt project's supported SQL resource paths or is excluded.",
            DbtSqlAnalysis.result(workspace, outside).exceptionOrNull()?.message
        )
        assertEquals(
            "No SQL analysis provider is available for this file.",
            DbtSqlAnalysis.result(workspace, markdown).exceptionOrNull()?.message
        )
    }

    fun testFailedAnalysisDoesNotPreventCtePreviewAfterCorrectingTheSql() {
        project()
        val file = configure("models/report.sql", "with a as (select 1 as id)\nselect * from a where )<caret>")
        assertTrue(DbtSqlAnalysis.result(workspace, file).isFailure)
        edit(file, "with a as (select 1 as id) select * from a")
        val analysis = DbtSqlAnalysis.result(workspace, file).getOrThrow()
        assertEquals(listOf("a"), analysis.ctes.map { it.name })
        assertEquals(
            "with a as (select 1 as id)\nselect * from a",
            DbtCteInspection.sql(file.text, analysis, analysis.ctes.first()).getOrThrow()
        )
    }

    private val node = "sql_operation.analytics.inline_query"

    private fun event(
        name: String, sql: String = "", message: String = "", id: String = "run-1",
        owner: String = node, level: String = "debug", status: String = "executing"
    ): String {
        val root = JsonObject();
        val info = JsonObject();
        val data = JsonObject();
        val identity = JsonObject()
        info.addProperty("name", name);
        info.addProperty("invocation_id", id);
        info.addProperty("level", level);
        info.addProperty("msg", message)
        identity.addProperty("unique_id", owner);
        identity.addProperty("node_status", status);
        data.add("node_info", identity)
        if (sql.isNotEmpty()) data.addProperty("sql", sql)
        if (name == "ShowNode") data.addProperty("unique_id", owner)
        root.add("info", info);
        root.add("data", data);
        return root.toString()
    }

    private fun command(
        source: String,
        query: DbtCteQuery,
        path: String = "/source/report.sql",
        url: String = "file:///source/report.sql"
    ): DbtCommand =
        DbtCommand(
            listOf("dbt"),
            DbtCommandRequest(DbtCommandAction.Preview, DbtCommandScope.Inline(query.sql)),
            "/source",
            emptyMap(),
            "dev",
            false,
            "/target",
            emptyList(),
            previewSource = (DbtPreviewSource(path, source, query.sql, query.spans, node, url))
        )

    private fun position(sql: String, needle: String): Pair<Int, Int> {
        val offset = sql.indexOf(needle);
        assertTrue(offset >= 0)
        val before = sql.take(offset)
        return (before.count { it == '\n' } + 1) to (offset - before.lastIndexOf('\n'))
    }

    private fun failed(command: DbtCommand, adapter: String, needle: String, suffix: String = ""): DbtPreviewEvidence {
        val at = position(adapter, needle)
        return DbtPreviewEvidence.capture(
            command, listOf(
                event("MainReportVersion"), event("SQLQuery", adapter),
                event(
                    "CatchableExceptionOnRun",
                    message = "SQL compilation error: line ${at.first} at position ${at.second - 1}"
                ), suffix
            ).joinToString("\n"),
            failed = true, canceled = false
        )
    }

    fun testExtractedDependencySpansMapAnAdapterWrappedErrorToTheExactAuthoredCte() {
        project()
        val source =
            "-- authored setup\nwith unused as (select 8),\nbase as (\n  select missing as id\n),\nreport as (select id from base)\nselect * from report"
        val file = configure("models/report.sql", source + "<caret>")
        val analysis = DbtSqlAnalysis.get(workspace, file)!!
        val query = DbtCteInspection.plan(source, analysis, analysis.ctes.find { it.name == "report" }!!).getOrThrow()
        assertFalse(query.sql.contains("unused"))
        val sql = "/* adapter query comment */\nselect * from (\n" + query.sql + "\n) as preview limit 10"
        val report = failed(command(source, query, file.virtualFile.path), sql, "missing")
        assertEquals((sql), report.adapterSql)
        assertEquals(source.indexOf("missing"), report.location!!.offset)
        assertEquals(4, report.location!!.line)
        assertEquals(10, report.location!!.column)
        assertTrue(report.displaySql.contains("Submitted SQL (before dbt compilation)"))
        assertTrue(report.displaySql.contains("execution may have failed"))
    }

    fun testSuccessfulSqlRequiresTheMatchingPreviewIdentityAndCurrentInvocation() {
        val query = DbtCteQuery("select 1 as id", listOf(DbtPreviewSpan(0, 0, 14)))
        val cmd = command(query.sql, query)
        val sql = query.sql + " limit 10"
        val logs = listOf(
            event("MainReportVersion"), event("SQLQuery", "select 999", owner = "model.other.inline_query"),
            event("SQLQuery", "BEGIN"), event("SQLQuery", sql), event("ShowNode")
        ).joinToString("\n")
        val report = DbtPreviewEvidence.capture(cmd, logs, failed = false, canceled = false)
        assertEquals((sql), report.adapterSql)
        assertTrue(report.location == null)
        assertTrue(
            DbtPreviewEvidence.capture(
                cmd,
                logs.replace("\"ShowNode\"", "\"Other\""),
                false,
                false
            ).adapterSql == null
        )
        assertTrue(
            DbtPreviewEvidence.capture(
                cmd,
                logs + "\n" + event("SQLQuery", sql, id = "older-run"),
                false,
                false
            ).adapterSql == null
        )
        assertTrue(
            DbtPreviewEvidence.capture(
                cmd,
                logs.lineSequence().drop(1).joinToString("\n"),
                false,
                false
            ).adapterSql == null
        )
        assertTrue(DbtPreviewEvidence.capture(cmd, logs, failed = true, canceled = true).adapterSql == null)
        val compiling = listOf(
            event("MainReportVersion"), event("SQLQuery", sql, status = "compiling"),
            event("CatchableExceptionOnRun", message = "line 1 at position 7", status = "compiling")
        ).joinToString("\n")
        assertTrue(
            "Compile-time macro queries cannot stand in for the preview's adapter SQL",
            DbtPreviewEvidence.capture(cmd, compiling, failed = true, canceled = false).adapterSql == null
        )
    }

    fun testGeneratedSqlJinjaAmbiguousQueriesAndUnsupportedErrorLocationsNeverInventSourceOffsets() {
        val source = "select broken as id"
        val query = DbtCteQuery(source, listOf(DbtPreviewSpan(0, 0, source.length)))
        val cmd = command(source, query)
        assertTrue(failed(cmd, source + "\nselect generated", "generated").location == null)
        assertTrue(failed(cmd, source + " union all " + source, "broken").location == null)
        val jinja = "select {{ 1 }} as broken"
        assertTrue(
            failed(
                command(jinja, DbtCteQuery(jinja, listOf(DbtPreviewSpan(0, 0, jinja.length)))),
                jinja,
                "broken"
            ).location == null
        )
        val transformed = listOf(
            event("MainReportVersion"), event("SQLQuery", "select 1"), event("SQLQuery", "select 2"),
            event("CatchableExceptionOnRun", message = "line 1 at position 7")
        ).joinToString("\n")
        assertTrue(DbtPreviewEvidence.capture(cmd, transformed, true, false).adapterSql == null)
        val unavailable = DbtPreviewEvidence.capture(cmd, "query failed at CTE", true, false)
        assertTrue(unavailable.location == null)
        assertTrue(unavailable.adapterSql == null)
        assertTrue(unavailable.explanation.contains("Error location unavailable"))
        assertFalse(unavailable.displaySql.contains("Compiled SQL:"))
        assertTrue(
            DbtPreviewEvidence.errorPositions("line 99999999999999999 at position 0; at [0:1]; line 1 at position 2147483647")
                .isEmpty()
        )
        assertTrue(DbtPreviewEvidence.authoredOffset(cmd.previewSource!!, source, 1, 999) == null)
        val unicode = "select '😀' as broken"
        assertTrue(
            failed(
                command(unicode, DbtCteQuery(unicode, listOf(DbtPreviewSpan(0, 0, unicode.length)))),
                unicode,
                "broken"
            ).location == null
        )
    }

    fun testBracketErrorCoordinatesAndInvalidSpanBoundsAreConservative() {
        val sql = "select broken as id"
        val query = DbtCteQuery(sql, listOf(DbtPreviewSpan(0, 0, sql.length)))
        val cmd = command(sql, query)
        val logs = listOf(
            event("MainReportVersion"), event("SQLQuery", sql),
            event("GenericExceptionOnRun", message = "Syntax error at [1:8]", level = "error")
        ).joinToString("\n")
        assertEquals((7), DbtPreviewEvidence.capture(cmd, logs, true, false).location?.offset)
        val invalid = cmd.previewSource!!.copy(spans = listOf(DbtPreviewSpan(0, Int.MAX_VALUE, 20)))
        assertTrue(DbtPreviewEvidence.authoredOffset(invalid, sql, 1, 8) == null)
        assertTrue(
            DbtPreviewEvidence.authoredOffset(
                invalid.copy(spans = listOf(DbtPreviewSpan(-1, 0, Int.MAX_VALUE))),
                sql,
                1,
                8
            ) == null
        )
    }

    fun testNativeErrorNavigationUsesTheAuthoredOffsetAndRefusesStaleSource() {
        project()
        val source = "with orders as (select missing as id) select * from orders"
        val file = configure("models/report.sql", source + "<caret>")
        val query = DbtCteQuery(source, listOf(DbtPreviewSpan(0, 0, source.length)))
        val report = failed(command(source, query, file.virtualFile.path, file.virtualFile.url), source, "missing")
        val panel = DbtPreviewEvidencePanel(getProject())
        panel.showEvidence((report))
        val button = components(panel).filterIsInstance<JButton>().first { it.text == "Go to CTE error" }
        assertTrue(button.isEnabled)
        val application = com.intellij.openapi.application.ApplicationManager.getApplication()
        application.threadingSupport!!.releaseTheAcquiredWriteIntentLockThenExecuteActionAndTakeWriteIntentLockBack<Boolean> {
            assertFalse("A normal Swing click begins without implicit read access", application.isReadAccessAllowed)
            button.doClick()
            true
        }
        val editor = FileEditorManager.getInstance(getProject()).selectedTextEditor!!
        assertEquals(source.indexOf("missing"), editor.caretModel.offset)
        WriteCommandAction.runWriteCommandAction(getProject(), object : Runnable {
            override fun run(): Unit = editor.document.insertString(0, "-- changed\n")
        })
        editor.caretModel.moveToOffset(0)
        button.doClick()
        assertEquals(0, editor.caretModel.offset)
        assertFalse(button.isEnabled)
        assertTrue(components(panel).filterIsInstance<JLabel>().map { it.text }.any { it.contains("Source changed") })
        panel.showEvidence(null)
        assertTrue(components(panel).filterIsInstance<JButton>().all { !it.isEnabled })
    }

    private fun button(grid: DbtResultGrid, text: String): JButton =
        components(grid).filterIsInstance<JButton>().first { it.text == text }

    private val result
        get() = DbtQueryResult.parse(
            """{"show":[{"id":10,"text":"a,b\nž\t日本語","optional":null,"flag":true},{"id":2,"text":"","optional":"NULL","flag":false},{"id":3,"text":"wide value","extra":1}]}""",
            "dev", 20, 12, "select * from orders"
        ).getOrThrow()

    fun testTypedNullRendererPreservesEmptyLiteralNullUnicodeAndWideValues() {
        val grid = DbtResultGrid(getProject());
        val r = result;
        grid.showResult(r)
        val table = component(grid, JTable::class.java)
        assertEquals(java.math.BigDecimal::class.java, table.getColumnClass(0))
        val nullCell = table.prepareRenderer(table.getCellRenderer(0, 2), 0, 2).let { it as JLabel }
        assertEquals("NULL", nullCell.text);
        assertEquals("SQL NULL (not an empty string)", nullCell.toolTipText)
        val empty = table.prepareRenderer(table.getCellRenderer(1, 1), 1, 1).let { it as JLabel }
        assertEquals("", empty.text)
        val text = table.prepareRenderer(table.getCellRenderer(0, 1), 0, 1).let { it as JLabel }
        assertEquals("a,b\nž\t日本語", text.text)
        assertTrue(table.columnModel.getColumn(1).preferredWidth >= 160)
        assertEquals(JTable.AUTO_RESIZE_OFF, table.autoResizeMode)
        assertEquals(true, table.getValueAt(0, 3))
    }

    fun testSortingFilteringSelectionCopyAndCsvTsvKeepExactValuesAndViewOrder() {
        val grid = DbtResultGrid(getProject());
        grid.showResult(result)
        val table = component(grid, JTable::class.java)
        table.rowSorter.toggleSortOrder(0)
        assertEquals(java.math.BigDecimal("2"), table.getValueAt(0, 0))
        table.setRowSelectionInterval(0, 0);
        table.setColumnSelectionInterval(1, 2)
        button(grid, "Copy selection").doClick()
        assertEquals("\"\"\t\"NULL\"\r\n", CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor))
        val csv = grid.exportText(',')
        assertTrue(csv.startsWith("\"id\",\"text\",\"optional\",\"flag\",\"extra\"\r\n2,"))
        assertTrue(csv.contains("10,\"a,b\nž\t日本語\",NULL,true,NULL"))
        component(grid, JTextField::class.java).setText("wide value")
        assertEquals(1, table.rowCount)
        val tsv = grid.exportText('\t')
        assertTrue(tsv.contains("3\t\"wide value\"\tNULL\tNULL\t1"))
        assertFalse(tsv.contains("日本語"))
        component(grid, JComboBox::class.java).setSelectedItem("TSV")
        assertTrue(button(grid, "Export TSV").isEnabled)
    }

    fun testChangingLimitSortingAndFilteringNeverRunsQueriesUntilExplicitButton() {
        val requests = mutableListOf<Int>()
        val grid = DbtResultGrid(getProject()) { value -> requests += value };
        grid.showResult(result)
        val spinner = component(grid, JSpinner::class.java)
        spinner.setValue((100))
        component(grid, JTextField::class.java).setText("wide")
        component(grid, JTable::class.java).rowSorter.toggleSortOrder(0)
        assertTrue(requests.isEmpty())
        button(grid, "Run with limit").doClick();
        assertEquals(listOf(100), requests.toList())
        grid.setRunning(true);
        assertFalse(button(grid, "Run with limit").isEnabled)
        grid.clear("Query canceled");
        assertEquals(0, component(grid, JTable::class.java).rowCount)
        assertFalse(button(grid, "Copy selection").isEnabled);
        assertFalse(button(grid, "Export CSV").isEnabled)
    }

    fun testEmptyAndMalformedResultsAreDistinctAndRowsAreBoundedWithHeterogeneousColumns() {
        val grid = DbtResultGrid(getProject())
        grid.showResult(DbtQueryResult.parse("{\"show\":[]}", "prod", 10, 5, "select empty").getOrThrow())
        assertTrue(
            components(grid).filterIsInstance<JLabel>().map { it.text }.joinToString(" ").contains("No data returned")
        )
        assertEquals("", grid.exportText(','))
        assertTrue(DbtQueryResult.parse("{\"show\":[1,2]}", "dev", 10, 1, "").isFailure)
        assertTrue(DbtQueryResult.parse("broken", "dev", 10, 1, "").isFailure)
        val bounded =
            DbtQueryResult.parse("{\"show\":[{\"first\":1},{\"hidden_only\":2}]}", "dev", 1, 1, "").getOrThrow()
        assertEquals(listOf("first"), bounded.columns);
        assertEquals(1, bounded.rows.size)
        val zero = DbtQueryResult.parse("{\"show\":[{\"id\":1}]}", "dev", 0, 1, "").getOrThrow()
        assertEquals(1, zero.limit)
        grid.showResult(result);
        grid.clear("Malformed output; see raw dbt output.")
        assertTrue(
            components(grid).filterIsInstance<JLabel>().map { it.text }.joinToString(" ").contains("Malformed output")
        )
    }

    fun testNullAwareFilteringAndNumericSortingDoNotConfuseNullWithEmptyText() {
        val grid = DbtResultGrid(getProject())
        grid.showResult(
            DbtQueryResult.parse(
                "{\"show\":[{\"v\":null},{\"v\":\"\"},{\"v\":\"NULL\"}]}",
                "dev",
                20,
                1,
                ""
            ).getOrThrow()
        )
        component(grid, JTextField::class.java).setText("NULL")
        assertEquals(2, component(grid, JTable::class.java).rowCount)
        assertEquals("\"v\"\r\nNULL\r\n\"NULL\"\r\n", grid.exportText(','))
        component(grid, JTextField::class.java).setText("")
        grid.showResult(
            DbtQueryResult.parse("{\"show\":[{\"v\":10},{\"v\":null},{\"v\":2}]}", "dev", 20, 1, "").getOrThrow()
        )
        val table = component(grid, JTable::class.java)
        table.rowSorter.toggleSortOrder(0)
        assertNull(table.getValueAt(0, 0))
        assertEquals(java.math.BigDecimal("2"), table.getValueAt(1, 0))
        assertEquals(java.math.BigDecimal("10"), table.getValueAt(2, 0))
    }

}
