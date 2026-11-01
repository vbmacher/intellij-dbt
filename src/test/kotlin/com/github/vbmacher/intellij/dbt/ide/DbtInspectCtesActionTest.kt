package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.navigation.DbtSqlAnalysis
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import org.junit.Assert.*
import java.nio.file.Files
import java.nio.file.Path

class DbtInspectCtesActionTest : DbtTestCase() {
    private val modelSql = """{{ config(materialized='view') }}

{{ wms_stage_packaging_input(
    source('wms_raw', 'all_cma_pairwise_final_output_all_demo_combinations')
) }}"""
    private val macroSql = """{% macro wms_stage_packaging_input(relation) %}
with first as (select 7 as id),
second as (select id from first)
select * from second
{% endmacro %}"""
    private val fakeCompiler = """#!/usr/bin/python3
import json,os,pathlib,sys,time
args=sys.argv[1:]
if args[0]=='compile':
 while pathlib.Path('hold_compile').exists(): time.sleep(.01)
 if pathlib.Path('fail_compile').exists(): sys.exit(1)
 if pathlib.Path('no_map').exists(): sys.exit(0)
 request=json.loads(pathlib.Path(os.environ['DBT_IDE_CTE_TRACE']).read_text())
 sql='with first as (select 7 as id),\nsecond as (select id from first)\nselect * from second'
 macro=next(s['path'] for s in request['sources'] if s['relative']=='macros/packaging.sql')
 origins=[{'path':macro,'line':1,'instance':1},{'path':request['model'],'line':2,'instance':2}]
 spans=[{'start':sql.index('first as'),'end':sql.index(',\n'),'origins':origins},
        {'start':sql.index('second as'),'end':sql.index('\nselect *'),'origins':origins}]
 pathlib.Path(request['report']).write_text(json.dumps({'token':request['token'],'sql':sql,'spans':spans}))
else:
 print(json.dumps({'show':[{'id':7}]}),flush=True)
"""

    private data class Fixture(
        val model: com.intellij.psi.PsiFile,
        val macroFile: com.intellij.psi.PsiFile,
        val directory: Path,
        val events: MutableList<DbtRunnerEvent>
    )

    private fun setup(): Fixture {
        project("profile: analytics\n")
        val macroFile = add("macros/packaging.sql", macroSql)
        val model = configure("models/report.sql", modelSql + "<caret>")
        val sources =
            "sources:\n  - name: wms_raw\n    tables:\n      - name: all_cma_pairwise_final_output_all_demo_combinations\n"
        add("models/sources.yml", sources)
        val directory = tempDirectory("dbt compiled inspector ")
        Files.createDirectories(directory.resolve("models")); Files.createDirectories(directory.resolve("macros"))
        Files.writeString(directory.resolve("dbt_project.yml"), "name: analytics\nversion: '1.0'\nprofile: analytics\n")
        Files.writeString(
            directory.resolve("profiles.yml"),
            "analytics:\n  target: dev\n  outputs:\n    dev: {type: duckdb, path: ':memory:', threads: 1}\n"
        )
        Files.writeString(directory.resolve("models/report.sql"), modelSql)
        Files.writeString(directory.resolve("macros/packaging.sql"), macroSql)
        Files.writeString(directory.resolve("models/sources.yml"), sources)
        val executable =
            System.getenv("DBT_CTE_REAL_CLI") ?: fakeTool(directory.resolve("fake-dbt"), fakeCompiler).toString()
        configureSettings { options ->
            options.projectRoot = directory.toString()
            options.profilesDirectory = directory.toString()
            options.executable = executable
            options.target = "dev"
            options.notifications = false
            options.environmentVariables.put("DBT_SEND_ANONYMOUS_USAGE_STATS", "false")
        }
        val events = mutableListOf<DbtRunnerEvent>()
        getProject().getService(DbtRunner::class.java).listen(getTestRootDisposable()) { e -> events += e }
        return Fixture(model, macroFile, directory, events)
    }

    private fun withSession(model: com.intellij.psi.PsiFile, body: (XDebugSession, DbtCteController) -> Unit) {
        val action =
            DbtModelRunLineMarker().getSlowInfo(model.findElementAt(0)!!)!!.actions.find { it.let { it is DbtInspectCtesAction } }!!
        myFixture.testAction(action)
        val manager = XDebuggerManager.getInstance(getProject())
        await("The gutter action opens Debug") { manager.debugSessions.isNotEmpty() }
        val session = manager.debugSessions.first()
        var ui: com.intellij.execution.ui.RunnerLayoutUi? = null
        session.runWhenUiReady { value -> ui = value }
        try {
            await("Native Debug UI is ready") { ui != null }
            val controller = session.debugProcess.let { it as DbtCteDebugProcess }.controller
            try {
                body(session, controller)
            } catch (error: AssertionError) {
                val console =
                    session.debugProcess.createConsole().let { it as com.intellij.execution.impl.ConsoleViewImpl }
                throw AssertionError(
                    error.message + "\nInspection status: " + controller.message +
                            "\nProcess output:\n" + console.editor!!.document.text, error
                )
            }
        } finally {
            session.stop()
            await("Stop cancels any inspection command") { !getProject().getService(DbtRunner::class.java).isRunning }
            val descriptor = session.let { it as com.intellij.xdebugger.impl.XDebugSessionImpl }
                .getMockRunContentDescriptorIfInitialized()!!
            val removed = com.intellij.execution.ui.RunContentManager.getInstance(getProject()).removeRunContent(
                com.intellij.execution.executors.DefaultDebugExecutor.getDebugExecutorInstance(), descriptor
            )
            if (!removed) Disposer.dispose(descriptor)
            ui?.let { value -> Disposer.dispose(value.contentManager) }
            Disposer.dispose(session.debugProcess.createConsole())
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        }
    }

    private fun capture(session: XDebugSession, name: String) {
        System.getenv("DBT_CTE_SCREENSHOT_DIR")?.let { directory ->
            val image = java.awt.image.BufferedImage(1200, 850, java.awt.image.BufferedImage.TYPE_INT_RGB)
            val graphics = image.createGraphics()
            fun layout(component: java.awt.Component) {
                if (component is java.awt.Container) {
                    component.doLayout(); component.components.forEach(::layout)
                }
            }

            var nativeUi: com.intellij.execution.ui.RunnerLayoutUi? = null
            session.runWhenUiReady { value -> nativeUi = value }
            await("Native UI is available for capture") { nativeUi != null }
            val rows = nativeUi!!.findContent("dbt.cte.rows")!!
            nativeUi!!.selectAndFocus(rows, false, false)
            val position = session.currentPosition!!
            val descriptor =
                com.intellij.openapi.fileEditor.OpenFileDescriptor(getProject(), position.file, position.offset)
            val editor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(getProject())
                .openTextEditor(descriptor, true)!!
            descriptor.navigateIn(editor)
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            assertTrue(components(rows.component).any { it is javax.swing.JTable && it.rowCount == 1 })
            val panels = listOf(editor.component to 300, nativeUi!!.component to 550)
            var y = 0
            try {
                panels.forEach { (component, height) ->
                    val size = component.size
                    component.setSize(1200, height); layout(component)
                    val area = graphics.create(0, y, 1200, height)
                    try {
                        component.printAll(area)
                    } finally {
                        area.dispose()
                    }
                    component.setSize(size); layout(component); y += height
                }
            } finally {
                graphics.dispose()
            }
            Files.createDirectories(java.nio.file.Paths.get(directory))
            javax.imageio.ImageIO.write(image, "png", java.nio.file.Paths.get(directory, name + ".png").toFile())
        }
    }

    fun testMacroOnlyGutterCompilesMapsSourceAndStepsThroughGeneratedSql() {
        val (model, macroFile, _, events) = setup()
        val debugger = XDebuggerManager.getInstance(getProject())
        val breakpointType = XDebuggerUtil.getInstance().findBreakpointType(DbtCteBreakpointType::class.java)
        assertTrue(breakpointType.canPutAt(model.virtualFile, 2, getProject()))
        assertTrue(breakpointType.canPutAt(macroFile.virtualFile, 1, getProject()))
        val breakpoint =
            WriteAction.compute<com.intellij.xdebugger.breakpoints.XLineBreakpoint<DbtCteBreakpointProperties>, RuntimeException> {
                debugger.breakpointManager.addLineBreakpoint(
                    breakpointType,
                    model.virtualFile.url,
                    2,
                    null
                )
            }
        try {
            withSession(model) { session, controller ->
                await(
                    "Debug compiles before exposing CTE steps",
                    40
                ) { controller.compilation != null && !controller.running }
                val compilation = controller.compilation!!
                assertEquals(listOf("first", "second"), controller.frames.map { it.name })
                assertTrue(controller.result == null)
                assertEquals(1, events.count { e -> e.running && e.command?.action == "Compile" })
                assertFalse(events.any { it.command?.action == "Preview" })
                session.resume()
                await("Resume stops after the first mapped CTE result", 40) {
                    controller.position == 0 && controller.result != null && session.isSuspended && !controller.running
                }
                assertEquals(model.virtualFile, session.currentPosition!!.file)
                assertEquals(2, session.currentPosition!!.line)
                capture(session, "cte-source-breakpoint")
                val previews = events.mapNotNull { it.command }.filter { it.action == "Preview" }.distinct()
                assertEquals(1, previews.size)
                assertTrue(previews.first().request.scope.let { it as DbtCommandScope.Inline }.sql.contains("first as"))
                assertFalse(previews.first().environment.containsKey("DBT_IDE_CTE_TRACE"))
                session.stepInto()
                await("Step Into shows compiled SQL without querying") { session.isSuspended && session.currentPosition!!.file == compilation.file }
                assertFalse(compilation.file.isWritable)
                capture(session, "cte-compiled-step")
                assertEquals(1, events.mapNotNull { it.command }.filter { it.action == "Preview" }.distinct().size)
                session.stepOver(false)
                await("Step Over queries the next compiled CTE with its dependency", 40) {
                    controller.position == 1 && controller.result != null && session.isSuspended && !controller.running
                }
                val second = events.mapNotNull { it.command }.filter { it.action == "Preview" }.distinct().last()
                val sql = second.request.scope.let { it as DbtCommandScope.Inline }.sql
                assertTrue(sql, sql.contains("first as") && sql.contains("second as"))
                assertFalse(sql, sql.contains("{{"))
                session.stepOut()
                await("Step Out returns to the source breakpoint") { session.isSuspended && session.currentPosition!!.file == model.virtualFile }
            }
        } finally {
            WriteAction.run<RuntimeException> { debugger.breakpointManager.removeBreakpoint(breakpoint) }
        }
    }

    fun testMacroEditsDiscardCompilationAndRequireASeparateExplicitQuery() {
        val (model, macroFile, _, events) = setup()
        withSession(model) { session, controller ->
            await("Initial compilation finishes", 40) { controller.compilation != null && !controller.running }
            edit(macroFile, macroSql + "\n-- changed macro")
            await("Macro edits discard generated steps") { controller.compilation == null && controller.frames.isEmpty() }
            session.stepOver(false)
            await(
                "The first explicit step recompiles only",
                40
            ) { controller.compilation != null && !controller.running }
            assertEquals(2, events.count { e -> e.running && e.command?.action == "Compile" })
            assertFalse(events.any { it.command?.action == "Preview" })
            session.stepOver(false)
            await(
                "A further explicit step queries the fresh compilation",
                40
            ) { controller.result != null && !controller.running }
        }
    }

    fun testUnsupportedCliExplainsMissingMappingsAndRunsNoPreview() {
        val (model, _, directory, events) = setup()
        Files.writeString(directory.resolve("no_map"), "")
        withSession(model) { _, controller ->
            await("The CLI finishes without a mapping") { !controller.running && events.any { e -> !e.running && e.command?.action == "Compile" } }
            assertTrue(
                controller.message,
                controller.message.contains("did not provide source mappings") || controller.message.startsWith("Source mapping is unavailable")
            )
            assertTrue(controller.compilation == null)
            assertFalse(events.any { it.command?.action == "Preview" })
        }
    }

    fun testStopAndFailedCompilationCannotInstallAStaleMap() {
        val (model, _, directory, events) = setup()
        Files.writeString(directory.resolve("hold_compile"), "")
        withSession(model) { _, controller ->
            await("Compilation is running") { getProject().getService(DbtRunner::class.java).isRunning }
            controller.stop()
            await("Stop cancels compilation") { !getProject().getService(DbtRunner::class.java).isRunning }
            assertTrue(controller.compilation == null)
            assertFalse(events.any { it.command?.action == "Preview" })
            Files.delete(directory.resolve("hold_compile"))
            Files.writeString(directory.resolve("fail_compile"), "")
            controller.restart(); controller.step()
            await("Failed compilation cannot provide steps") { !controller.running && controller.message.contains("exit 1") }
            assertTrue(controller.compilation == null)
        }
    }

    fun testMacroDefinitionBreakpointHighlightsItsSource() {
        val (model, macroFile, _, _) = setup()
        val manager = XDebuggerManager.getInstance(getProject()).breakpointManager
        val kind = XDebuggerUtil.getInstance().findBreakpointType(DbtCteBreakpointType::class.java)
        val breakpoint =
            WriteAction.compute<com.intellij.xdebugger.breakpoints.XLineBreakpoint<DbtCteBreakpointProperties>, RuntimeException> {
                manager.addLineBreakpoint(
                    kind,
                    macroFile.virtualFile.url,
                    1,
                    null
                )
            }
        try {
            withSession(model) { session, controller ->
                await("The macro expansion is ready") { controller.compilation != null && !controller.running }
                session.resume()
                await("A macro-definition breakpoint stops with its result") { controller.result != null && !controller.running && session.isSuspended }
                assertEquals(0, controller.position)
                assertEquals(macroFile.virtualFile, session.currentPosition!!.file)
                assertEquals(1, session.currentPosition!!.line)
            }
        } finally {
            WriteAction.run<RuntimeException> { manager.removeBreakpoint(breakpoint) }
        }
    }

    fun testTargetChangeCancelsCompilationBeforeInstallingTheOldMap() {
        val (model, _, directory, events) = setup()
        Files.writeString(directory.resolve("hold_compile"), "")
        withSession(model) { _, controller ->
            await("The old target is compiling") { getProject().getService(DbtRunner::class.java).isRunning }
            configureSettings { it.target = "prod" }
            await("Target change cancels the old compilation") { !getProject().getService(DbtRunner::class.java).isRunning && !controller.running }
            assertTrue(controller.compilation == null)
            Files.delete(directory.resolve("hold_compile"))
            controller.step()
            await("Only an explicit step compiles the new target") { controller.compilation != null && !controller.running }
            assertEquals("prod", events.mapNotNull { it.command }.filter { it.action == "Compile" }.last().target)
            assertFalse(events.any { it.command?.action == "Preview" })
        }
    }

    fun testSourceMapsRejectAnotherCompilationAndUnknownLocations() {
        val (model, _, _, _) = setup()
        val job = DbtCteCompileJob.prepare(workspace, workspace.model(model)!!).getOrThrow()
        try {
            Files.writeString(
                job.directory.resolve("report.json"),
                """{"token":"other","sql":"with a as (select 1) select * from a","spans":[]}"""
            )
            assertTrue(job.load().exceptionOrNull()!!.message.orEmpty().contains("does not belong"))
            val packet = com.google.gson.JsonObject()
            packet.addProperty("token", job.token)
            packet.addProperty("sql", "with a as (select 1) select * from a")
            packet.add(
                "spans",
                com.google.gson.JsonParser.parseString("""[{"start":5,"end":20,"origins":[{"path":"/unknown.sql","line":0,"instance":1}]}]""")
            )
            Files.writeString(job.directory.resolve("report.json"), packet.toString())
            assertTrue(job.load().exceptionOrNull()!!.message.orEmpty().contains("invalid source locations"))
        } finally {
            job.dispose()
        }
    }

    fun testCompiledParserRetainsRangesAndRejectsUnsupportedSql() {
        val sql =
            "\n\n\n-- header\nwith first as (select '😀\n\n\n' as id),\n\n\nsecond as (select id from first) select * from second"
        val analysis = DbtSqlAnalysis.compiled(sql).getOrThrow()
        assertEquals(listOf("first", "second"), analysis.ctes.map { it.name })
        assertEquals(listOf("first"), analysis.ctes.last().dependencies)
        assertTrue(analysis.ctes.all { c -> c.range.substring(sql).startsWith(c.name + " as") })
        assertTrue(DbtSqlAnalysis.compiled("select 1").isFailure)
        assertTrue(DbtSqlAnalysis.compiled("with a as (select 1) select * from a; delete from orders").isFailure)
        assertTrue(DbtSqlAnalysis.compiled("with recursive a as (select * from a) select * from a").isFailure)
        assertTrue(DbtSqlAnalysis.compiled("with a as (delete from orders returning *) select * from a").isFailure)
        val literal = "select '{{ literal_only }}' as id"
        assertEquals(
            literal,
            com.google.gson.JsonParser.parseString(
                DbtCteCompilation.inline(literal).removePrefix("{{ ").removeSuffix(" }}")
            ).asString
        )
    }
}
