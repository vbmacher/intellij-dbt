package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.navigation.DbtSqlAnalysis
import com.github.vbmacher.intellij.dbt.project.*
import com.google.gson.JsonParser
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JButton
import org.junit.Assert.*

class DbtCteControllerTest : DbtTestCase() {
    private data class Fixture(
        val panel: DbtCteController, val file: com.intellij.psi.PsiFile, val root: DbtRoot, val directory: Path,
        val runner: DbtRunner, val events: MutableList<DbtRunnerEvent>
    )

    private var breakpoints = emptySet<String>()

    private fun click(panel: DbtCteController, name: String): Unit = when (name) {
        "Step" -> panel.step()
        "Stop" -> panel.stop()
        "Restart" -> panel.restart()
        "Step Back" -> panel.back()
        "Count all rows" -> panel.count()
        "Continue to breakpoint" -> panel.resume()
        else -> error("Unknown controller action: $name")
    }

    private fun messages(panel: DbtCteController): String = panel.message

    private fun rows(panel: DbtCteController): Int = panel.result?.rows?.size ?: 0

    private fun controller(
        path: String,
        frames: List<com.github.vbmacher.intellij.dbt.navigation.DbtCte>
    ): DbtCteController =
        DbtCteController(getProject(), path, frames, {}, { _, _ -> }, { c -> c.name in breakpoints })

    private fun flushReads() {
        com.intellij.openapi.application.impl.NonBlockingReadActionImpl.waitForAsyncTaskCompletion()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    private fun setup(sql: String = "with a as (select 1 as id), b as (select id from a) select * from b"): Fixture {
        project()
        val file = configure("models/report.sql", sql + "<caret>")
        val w = workspace
        val root = w.root(file)!!
        val dir = tempDirectory("dbt cte session with spaces ")
        Files.writeString(dir.resolve("dbt_project.yml"), "name: analytics\n")
        val executable = fakeTool(
            dir.resolve("fake dbt"),
            """#!/usr/bin/python3
import json,os,sys,time
args=sys.argv[1:]
with open('calls.jsonl','a') as f: f.write(json.dumps(args)+'\n')
while os.path.exists('hold'): time.sleep(.01)
if os.path.exists('evidence'):
 def emit(name,data,level='debug',message='',stream=sys.stdout):
  data['node_info']={'unique_id':'sql_operation.analytics.inline_query','node_status':'executing'}
  print(json.dumps({'info':{'name':name,'invocation_id':'cte-preview-run','level':level,'msg':message},'data':data}),file=stream,flush=True)
 query=args[args.index('--inline')+1]
 sql='/* adapter query comment */\nselect * from (\n'+query+'\n) as preview limit '+args[args.index('--limit')+1]
 emit('MainReportVersion',{})
 emit('SQLQuery',{'sql':sql})
 if os.path.exists('fail'):
  before=sql[:sql.index('missing')]
  message='SQL compilation error: line '+str(before.count('\n')+1)+' at position '+str(len(before.rsplit('\n',1)[-1]))
  emit('CatchableExceptionOnRun',{},message=message,stream=sys.stderr)
  print('raw stderr retained',file=sys.stderr,flush=True)
  sys.exit(1)
 emit('ShowNode',{'unique_id':'sql_operation.analytics.inline_query'},level='info')
if os.path.exists('fail'):
 print('query failed at CTE',file=sys.stderr,flush=True)
 sys.exit(1)
print(json.dumps({'show':[{'value':args[args.index('--inline')+1]}]}),flush=True)
"""
        )
        root.runOptions.projectRoot = dir.toString()
        root.runOptions.executable = executable.toString()
        root.runOptions.target = "dev"
        val runner = newRunner(service = true)
        val events = mutableListOf<DbtRunnerEvent>()
        runner.listen(getTestRootDisposable()) { event -> events += event }
        val panel = controller(file.virtualFile.path, DbtSqlAnalysis.get(w, file)!!.ctes)
        Disposer.register(getTestRootDisposable(), panel)
        return Fixture(panel, file, root, dir, runner, events)
    }

    private fun calls(f: Fixture): List<List<String>> =
        if (!Files.exists(f.directory.resolve("calls.jsonl"))) emptyList()
        else Files.readAllLines(f.directory.resolve("calls.jsonl")).toList()
            .map { line -> JsonParser.parseString(line).asJsonArray.map { it.asString }.toList() }

    fun testManagedPreviewPreservesAdapterSqlAndStderrAndNavigatesItsAuthoredCteError() {
        val source = "with orders as (select missing as id) select * from orders"
        val f = setup(source)
        Files.writeString(f.directory.resolve("evidence"), "")
        Files.writeString(f.directory.resolve("fail"), "")
        click(f.panel, "Step")
        await("Failed process publishes preview evidence without a result") {
            f.events.any { e -> !e.running && e.previewEvidence != null } && !f.runner.isRunning
        }
        val failed = f.events.asReversed().find { e -> !e.running && e.previewEvidence != null }!!
        assertTrue(failed.result == null)
        assertFalse(
            "A completed failed frame must not retain a running grid label",
            messages(f.panel).contains("Query running")
        )
        val report = failed.previewEvidence!!
        f.panel.choose(f.panel.selected)
        assertEquals("Selecting the current native frame preserves failure navigation", (report), f.panel.evidence)
        assertTrue(report.raw.contains("raw stderr retained"))
        assertTrue(report.adapterSql!!.startsWith("/* adapter query comment */"))
        assertEquals(source.indexOf("missing"), report.location!!.offset)
        val reportPanel = DbtPreviewEvidencePanel(getProject())
        reportPanel.showEvidence(f.panel.evidence)
        val navigate = components(reportPanel).filterIsInstance<JButton>().first { it.text == "Go to CTE error" }
        assertTrue(navigate.isEnabled)
        navigate.doClick()
        assertEquals(
            source.indexOf("missing"), com.intellij.openapi.fileEditor.FileEditorManager.getInstance(getProject())
                .selectedTextEditor!!.caretModel.offset
        )
        assertEquals(1, calls(f).size)
        click(f.panel, "Restart")
        reportPanel.showEvidence(f.panel.evidence);
        assertFalse(navigate.isEnabled)
        Files.delete(f.directory.resolve("fail"))
        click(f.panel, "Step")
        await("Successful process retains its adapter-wrapped SQL") {
            calls(f).size == 2 && !f.runner.isRunning && rows(f.panel) == 1
        }
        val result = f.events.asReversed().firstNotNullOf { it.result }
        assertTrue(result.sql.startsWith("/* adapter query comment */"))
        assertTrue(result.sql.contains(") as preview limit " + f.root.runOptions.previewLimit))
        assertEquals(calls(f).last()[calls(f).last().indexOf("--inline") + 1], f.panel.evidence!!.submitted)
        assertTrue(calls(f).last().windowed(2).any { it == listOf("--log-format", "json") })
        assertTrue(calls(f).last().windowed(2).any { it == listOf("--log-level", "debug") })
        reportPanel.showEvidence(f.panel.evidence);
        assertFalse(navigate.isEnabled)
    }

    fun testStopDuringPreparationNeverLaunchesAQueryAndRestartRequiresExplicitStep() {
        val f = setup()
        WriteAction.run<RuntimeException> {
            click(f.panel, "Step");
            click(f.panel, "Stop")
        }
        flushReads()
        assertEquals(emptyList<List<String>>(), calls(f))
        assertFalse(f.runner.isRunning)
        assertTrue(messages(f.panel).contains("Inspection stopped"))
        click(f.panel, "Restart");
        click(f.panel, "Step")
        await("Restarted explicit step completes") {
            calls(f).size == 1 && !f.runner.isRunning && rows(f.panel) == 1
        }
        assertTrue(calls(f).first().contains("dev"))
    }

    fun testSourceAndTargetChangesDuringPreparationDiscardCommands() {
        val f = setup()
        WriteAction.run<RuntimeException> {
            click(f.panel, "Step")
            myFixture.editor.document.setText("with changed as (select 9 as id) select * from changed")
            com.intellij.psi.PsiDocumentManager.getInstance(getProject()).commitAllDocuments()
        }
        flushReads();
        assertEquals(emptyList<List<String>>(), calls(f))
        WriteAction.run<RuntimeException> {
            click(f.panel, "Step")
            f.root.runOptions.target = "prod"
            getProject().messageBus.syncPublisher(DbtProjectSettings.Changed).settingsChanged()
        }
        flushReads();
        assertEquals(emptyList<List<String>>(), calls(f))
        click(f.panel, "Step")
        await("Fresh source and target are queried") {
            calls(f).size == 1 && !f.runner.isRunning && rows(f.panel) == 1
        }
        assertTrue(calls(f).first().contains("prod"))
        assertTrue(calls(f).first()[calls(f).first().indexOf("--inline") + 1].contains("with changed"))
        assertEquals("changed", f.panel.frames.first().name)
    }

    fun testRapidStepRequestsLaunchOnlyOneQueryAndCachedBackDoesNotExecute() {
        val f = setup()
        WriteAction.run<RuntimeException> {
            click(f.panel, "Step");
            click(f.panel, "Step");
            click(f.panel, "Step")
        }
        await("Only one prepared query completes") {
            calls(f).size == 1 && !f.runner.isRunning && rows(f.panel) == 1
        }
        click(f.panel, "Step")
        await("Second frame completes") {
            calls(f).size == 2 && !f.runner.isRunning && rows(f.panel) == 1
        }
        click(f.panel, "Step Back")
        assertEquals(2, calls(f).size)
        assertTrue(f.panel.result!!.value(0, 0).toString().endsWith("from a"))
        assertFalse(f.panel.result!!.value(0, 0).toString().contains("b as"))
    }

    fun testUnrelatedCommandCompletionsCannotPopulateTheSessionAndEditsCancelItsOwnQuery() {
        val f = setup()
        Files.writeString(f.directory.resolve("hold"), "")
        click(f.panel, "Step")
        await("Session query starts") {
            calls(f).isNotEmpty() && f.runner.isRunning
        }
        val command = f.events.mapNotNull { it.command }.last()
        val unrelated = DbtQueryResult.parse("{\"show\":[{\"wrong\":999}]}", "dev", 20, 1, "select wrong").getOrThrow()
        f.panel.onRunnerEvent(
            DbtRunnerEvent(
                running = false,
                "unrelated completion",
                result = (unrelated),
                command = (command.copy())
            )
        )
        assertEquals(0, rows(f.panel))
        edit(f.file, "with changed as (select 2 as id) select * from changed")
        await("Editing cancels the active session command") {
            !f.runner.isRunning
        }
        Files.deleteIfExists(f.directory.resolve("hold"))
        assertEquals(0, rows(f.panel))
        assertTrue(messages(f.panel).contains("changed"))
    }

    fun testDisposalDuringPreparationAndActiveQueryPreventsResultsAndFurtherQueries() {
        val f = setup()
        WriteAction.run<RuntimeException> {
            click(f.panel, "Step");
            Disposer.dispose(f.panel)
        }
        flushReads();
        assertEquals(emptyList<List<String>>(), calls(f))
        val next = controller(f.file.virtualFile.path, DbtSqlAnalysis.get(workspace, f.file)!!.ctes)
        Disposer.register(getTestRootDisposable(), next)
        Files.writeString(f.directory.resolve("hold"), "")
        click(next, "Step")
        await("Second panel query starts") {
            f.runner.isRunning && calls(f).isNotEmpty()
        }
        Disposer.dispose(next)
        await("Disposal cancels only the panel-owned active command") {
            !f.runner.isRunning
        }
        assertEquals(0, rows(next))
    }

    fun testGridLimitRerunsTheSelectedFrameOnlyAfterExplicitClick() {
        val f = setup()
        click(f.panel, "Step")
        await("First frame preview completes") {
            calls(f).size == 1 && !f.runner.isRunning && rows(f.panel) == 1
        }
        assertEquals(1, calls(f).size)
        f.panel.rerun(100)
        await("Explicit limit reruns the selected frame") {
            calls(f).size == 2 && !f.runner.isRunning && rows(f.panel) == 1
        }
        assertEquals("100", calls(f).last()[calls(f).last().indexOf("--limit") + 1])
        assertEquals(
            calls(f).first()[calls(f).first().indexOf("--inline") + 1],
            calls(f).last()[calls(f).last().indexOf("--inline") + 1]
        )
    }

    fun testContinueStopsAtTheSelectedBreakpoint() {
        val f = setup()
        breakpoints = setOf("a")
        click(f.panel, "Continue to breakpoint")
        await("Continue stops after the breakpoint frame") {
            calls(f).size == 1 && !f.runner.isRunning && rows(f.panel) == 1
        }
        flushReads()
        assertEquals(1, calls(f).size)
    }

    fun testStepIntoRefAtTheEndOfACteOpensTheExactReferencedModelWithoutQuerying() {
        val f = setup("with orders as (select * from {{ ref('upstream') }}) select * from orders")
        add("models/upstream.sql", "with base as (select 1 as id) select * from base")
        val next = f.panel.referencedModel()!!
        assertTrue(next.first.endsWith("/upstream.sql"))
        assertTrue(calls(f).isEmpty())
        val nested = controller(next.first, next.second)
        Disposer.register(getTestRootDisposable(), nested)
        assertEquals(listOf("base"), nested.frames.map { it.name })
        nested.step()
        await("Only explicit nested Step queries") {
            calls(f).size == 1 && !f.runner.isRunning && rows(nested) == 1
        }
        assertEquals(
            "with base as (select 1 as id)\nselect * from base",
            calls(f).first()[calls(f).first().indexOf("--inline") + 1]
        )
    }

    fun testQueryFailureStopsContinueAndRestartRetriesTheFirstFrameWithoutOldResults() {
        val f = setup()
        Files.writeString(f.directory.resolve("fail"), "")
        click(f.panel, "Continue to breakpoint")
        await("A failed frame finishes") {
            calls(f).size == 1 && !f.runner.isRunning
        }
        flushReads()
        assertEquals("Continue must stop after a query error", 1, calls(f).size)
        assertEquals(0, rows(f.panel))
        assertTrue(messages(f.panel), messages(f.panel).contains("exit 1"))
        Files.delete(f.directory.resolve("fail"))
        click(f.panel, "Restart")
        assertEquals(1, calls(f).size)
        click(f.panel, "Step")
        await("Restart explicitly retries the first frame") {
            calls(f).size == 2 && !f.runner.isRunning && rows(f.panel) == 1
        }
        assertEquals(
            calls(f).first()[calls(f).first().indexOf("--inline") + 1],
            calls(f).last()[calls(f).last().indexOf("--inline") + 1]
        )
    }

    fun testCountingAFrameDoesNotReplaceItsCachedPreviewOrAdvanceTheStack() {
        val f = setup()
        click(f.panel, "Step")
        await("First CTE preview completes") {
            calls(f).size == 1 && !f.runner.isRunning && rows(f.panel) == 1
        }
        click(f.panel, "Count all rows")
        await("Explicit complete count finishes") {
            calls(f).size == 2 && !f.runner.isRunning && rows(f.panel) == 1
        }
        assertTrue(
            calls(f).last()[calls(f).last()
                .indexOf("--inline") + 1].endsWith("select count(*) as full_row_count from a")
        )
        click(f.panel, "Step")
        await("Step still advances to the second CTE") {
            calls(f).size == 3 && !f.runner.isRunning && rows(f.panel) == 1
        }
        assertTrue(calls(f).last()[calls(f).last().indexOf("--inline") + 1].endsWith("select * from b"))
        click(f.panel, "Step Back")
        assertEquals(3, calls(f).size)
        assertTrue(f.panel.result!!.value(0, 0).toString().endsWith("select * from a"))
        assertFalse(f.panel.result!!.value(0, 0).toString().contains("count(*)"))
    }

    fun testPlanningAtFinalSelectCteReferenceUsesItsExactDefinitionAndDependencies() {
        val f = setup()
        val w = workspace
        val analysis = DbtSqlAnalysis.get(w, f.file)!!
        val cte = DbtCteInspection.at(w, f.file, analysis, f.file.text.lastIndexOf("b"))!!
        assertEquals("b", cte.name)
        val sql = DbtCteInspection.sql(f.file.text, analysis, cte).getOrThrow()
        assertEquals("with a as (select 1 as id),\nb as (select id from a)\nselect * from b", sql)
        val model = w.codeSymbols(f.file).find { it.kind == "model" }!!
        val command = DbtCteInspection.command(w, model, sql).getOrThrow()
        assertEquals(sql, command.arguments[command.arguments.indexOf("--inline") + 1])
        assertFalse(command.arguments.contains("--select"))
    }

    fun testLeadingSetAndConfigSetupSurviveCteExtractionWithoutOmittingUnusedSql() {
        val setupSql = "{% set chosen = 'orders' %}\n{{ config(materialized='view') }}\n-- preserve setup\n"
        val f =
            setup(setupSql + "with unused as (select 0), chosen_cte as (select * from {{ ref(chosen) }}) select * from chosen_cte")
        val analysis = DbtSqlAnalysis.get(workspace, f.file)!!
        val query =
            DbtCteInspection.sql(f.file.text, analysis, analysis.ctes.find { it.name == "chosen_cte" }!!).getOrThrow()
        assertTrue(query.startsWith(setupSql + "with chosen_cte"))
        assertTrue(query.contains("{{ ref(chosen) }}"));
        assertFalse(query.contains("unused as"))
    }

    fun testTemplateSetupBetweenCteDeclarationsIsRejectedInsteadOfSilentlyLost() {
        val f =
            setup("with a as (select 1), {% set chosen = 'orders' %} b as (select * from {{ ref(chosen) }}) select * from b")
        val analysis = DbtSqlAnalysis.get(workspace, f.file)!!
        val query = DbtCteInspection.sql(f.file.text, analysis, analysis.ctes.find { it.name == "b" }!!)
        assertTrue(query.exceptionOrNull()!!.message.orEmpty().contains("Template setup between CTE"))
        assertTrue(calls(f).isEmpty())
    }

    fun testPreviewSelectedSqlReportsABusyRunner() {
        val f = setup()
        Files.writeString(f.directory.resolve("hold"), "")
        click(f.panel, "Step")
        await("The held CTE query occupies the runner") {
            calls(f).size == 1 && f.runner.isRunning
        }
        val factory =
            com.intellij.execution.configurations.ConfigurationTypeUtil.findConfigurationType(DbtConfigurationType::class.java).configurationFactories.first()
        val config = DbtRunConfiguration(getProject(), factory, "preview")
        val console = DbtCommandConsole(config)
        Disposer.register(getTestRootDisposable(), console)
        val command =
            DbtCteInspection.command(workspace, workspace.codeSymbols(f.file).first(), f.file.text).getOrThrow()
        console.start(command)
        assertTrue(console.process.isProcessTerminated)
        assertEquals(1, console.process.exitCode!!.toInt())
        assertTrue("Rejected previews must not stop the running inspector", f.runner.isRunning)
        Files.delete(f.directory.resolve("hold"))
        await("Held query finishes") {
            !f.runner.isRunning
        }
        assertEquals(1, calls(f).size)
    }

}
