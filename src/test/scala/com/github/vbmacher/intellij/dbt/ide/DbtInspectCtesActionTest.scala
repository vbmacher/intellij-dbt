package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.navigation.DbtSqlAnalysis
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.xdebugger.{XDebugSession, XDebuggerManager, XDebuggerUtil}
import org.junit.Assert._
import java.nio.file.{Files, Path}
import scala.collection.mutable.ArrayBuffer

final class DbtInspectCtesActionTest extends DbtTestCase {
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

  private def setup(): (com.intellij.psi.PsiFile, com.intellij.psi.PsiFile, Path, ArrayBuffer[DbtRunnerEvent]) = {
    project("profile: analytics\n")
    val macroFile = add("macros/packaging.sql", macroSql)
    val model = configure("models/report.sql", modelSql + "<caret>")
    val sources = "sources:\n  - name: wms_raw\n    tables:\n      - name: all_cma_pairwise_final_output_all_demo_combinations\n"
    add("models/sources.yml", sources)
    val directory = tempDirectory("dbt compiled inspector ")
    Files.createDirectories(directory.resolve("models")); Files.createDirectories(directory.resolve("macros"))
    Files.writeString(directory.resolve("dbt_project.yml"), "name: analytics\nversion: '1.0'\nprofile: analytics\n")
    Files.writeString(directory.resolve("profiles.yml"), "analytics:\n  target: dev\n  outputs:\n    dev: {type: duckdb, path: ':memory:', threads: 1}\n")
    Files.writeString(directory.resolve("models/report.sql"), modelSql)
    Files.writeString(directory.resolve("macros/packaging.sql"), macroSql)
    Files.writeString(directory.resolve("models/sources.yml"), sources)
    val executable = sys.env.getOrElse("DBT_CTE_REAL_CLI", fakeTool(directory.resolve("fake-dbt"), fakeCompiler).toString)
    configureSettings { options =>
      options.projectRoot = directory.toString
      options.profilesDirectory = directory.toString
      options.executable = executable
      options.target = "dev"
      options.notifications = false
      options.environmentVariables.put("DBT_SEND_ANONYMOUS_USAGE_STATS", "false")
    }
    val events = ArrayBuffer.empty[DbtRunnerEvent]
    getProject.getService(classOf[DbtRunner]).listen(getTestRootDisposable, e => events += e)
    (model, macroFile, directory, events)
  }

  private def withSession(model: com.intellij.psi.PsiFile)(body: (XDebugSession, DbtCteController) => Unit): Unit = {
    val action = new DbtModelRunLineMarker().getSlowInfo(model.findElementAt(0)).actions.find(_.isInstanceOf[DbtInspectCtesAction]).get
    myFixture.testAction(action)
    val manager = XDebuggerManager.getInstance(getProject)
    await("The gutter action opens Debug") { manager.getDebugSessions.nonEmpty }
    val session = manager.getDebugSessions.head
    var ui = Option.empty[com.intellij.execution.ui.RunnerLayoutUi]
    session.runWhenUiReady(value => ui = Some(value))
    try {
      await("Native Debug UI is ready") { ui.nonEmpty }
      val controller = session.getDebugProcess.asInstanceOf[DbtCteDebugProcess].controller
      try body(session, controller) catch {
        case error: AssertionError =>
          val console = session.getDebugProcess.createConsole().asInstanceOf[com.intellij.execution.impl.ConsoleViewImpl]
          throw new AssertionError(error.getMessage + "\nInspection status: " + controller.message +
            "\nProcess output:\n" + console.getEditor.getDocument.getText, error)
      }
    } finally {
      session.stop()
      await("Stop cancels any inspection command") { !getProject.getService(classOf[DbtRunner]).isRunning }
      val descriptor = session.asInstanceOf[com.intellij.xdebugger.impl.XDebugSessionImpl].getMockRunContentDescriptorIfInitialized
      val removed = com.intellij.execution.ui.RunContentManager.getInstance(getProject).removeRunContent(
        com.intellij.execution.executors.DefaultDebugExecutor.getDebugExecutorInstance, descriptor)
      if (!removed) Disposer.dispose(descriptor)
      ui.foreach(value => Disposer.dispose(value.getContentManager))
      Disposer.dispose(session.getDebugProcess.createConsole())
      PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }
  }

  private def capture(session: XDebugSession, name: String): Unit = sys.env.get("DBT_CTE_SCREENSHOT_DIR").foreach { directory =>
    val image = new java.awt.image.BufferedImage(1200, 850, java.awt.image.BufferedImage.TYPE_INT_RGB)
    val graphics = image.createGraphics()
    def layout(component: java.awt.Component): Unit = component match {
      case container: java.awt.Container => container.doLayout(); container.getComponents.foreach(layout)
      case _ => ()
    }
    var nativeUi = Option.empty[com.intellij.execution.ui.RunnerLayoutUi]
    session.runWhenUiReady(value => nativeUi = Some(value))
    await("Native UI is available for capture") { nativeUi.nonEmpty }
    val rows = nativeUi.get.findContent("dbt.cte.rows")
    nativeUi.get.selectAndFocus(rows, false, false)
    val position = session.getCurrentPosition
    val descriptor = new com.intellij.openapi.fileEditor.OpenFileDescriptor(getProject, position.getFile, position.getOffset)
    val editor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(getProject).openTextEditor(descriptor, true)
    descriptor.navigateIn(editor)
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    assertTrue(components(rows.getComponent).exists { case table: javax.swing.JTable => table.getRowCount == 1; case _ => false })
    val panels = Vector(editor.getComponent -> 300, nativeUi.get.getComponent -> 550)
    var y = 0
    try panels.foreach { case (component, height) =>
      val size = component.getSize
      component.setSize(1200, height); layout(component)
      val area = graphics.create(0, y, 1200, height)
      try component.printAll(area) finally area.dispose()
      component.setSize(size); layout(component); y += height
    } finally graphics.dispose()
    Files.createDirectories(java.nio.file.Paths.get(directory))
    javax.imageio.ImageIO.write(image, "png", java.nio.file.Paths.get(directory, name + ".png").toFile)
  }

  def testMacroOnlyGutterCompilesMapsSourceAndStepsThroughGeneratedSql(): Unit = {
    val (model, macroFile, _, events) = setup()
    val debugger = XDebuggerManager.getInstance(getProject)
    val breakpointType = XDebuggerUtil.getInstance.findBreakpointType(classOf[DbtCteBreakpointType])
    assertTrue(breakpointType.canPutAt(model.getVirtualFile, 2, getProject))
    assertTrue(breakpointType.canPutAt(macroFile.getVirtualFile, 1, getProject))
    val breakpoint = WriteAction.compute[com.intellij.xdebugger.breakpoints.XLineBreakpoint[DbtCteBreakpointProperties], RuntimeException](
      () => debugger.getBreakpointManager.addLineBreakpoint(breakpointType, model.getVirtualFile.getUrl, 2, null))
    try withSession(model) { (session, controller) =>
      await("Debug compiles before exposing CTE steps", 40) { controller.compilation.nonEmpty && !controller.running }
      val compilation = controller.compilation.get
      assertEquals(Vector("first", "second"), controller.frames.map(_.name))
      assertTrue(controller.result.isEmpty)
      assertEquals(1, events.count(e => e.running && e.command.exists(_.action == "Compile")))
      assertFalse(events.exists(_.command.exists(_.action == "Preview")))
      session.resume()
      await("Resume stops after the first mapped CTE result", 40) {
        controller.position == 0 && controller.result.nonEmpty && session.isSuspended && !controller.running
      }
      assertEquals(model.getVirtualFile, session.getCurrentPosition.getFile)
      assertEquals(2, session.getCurrentPosition.getLine)
      capture(session, "cte-source-breakpoint")
      val previews = events.flatMap(_.command).filter(_.action == "Preview").distinct
      assertEquals(1, previews.size)
      assertTrue(previews.head.request.scope.asInstanceOf[DbtCommandScope.Inline].sql.contains("first as"))
      assertFalse(previews.head.environment.contains("DBT_IDE_CTE_TRACE"))
      session.stepInto()
      await("Step Into shows compiled SQL without querying") { session.isSuspended && session.getCurrentPosition.getFile == compilation.file }
      assertFalse(compilation.file.isWritable)
      capture(session, "cte-compiled-step")
      assertEquals(1, events.flatMap(_.command).filter(_.action == "Preview").distinct.size)
      session.stepOver(false)
      await("Step Over queries the next compiled CTE with its dependency", 40) {
        controller.position == 1 && controller.result.nonEmpty && session.isSuspended && !controller.running
      }
      val second = events.flatMap(_.command).filter(_.action == "Preview").distinct.last
      val sql = second.request.scope.asInstanceOf[DbtCommandScope.Inline].sql
      assertTrue(sql, sql.contains("first as") && sql.contains("second as"))
      assertFalse(sql, sql.contains("{{"))
      session.stepOut()
      await("Step Out returns to the source breakpoint") { session.isSuspended && session.getCurrentPosition.getFile == model.getVirtualFile }
    } finally WriteAction.run(() => debugger.getBreakpointManager.removeBreakpoint(breakpoint))
  }

  def testMacroEditsDiscardCompilationAndRequireASeparateExplicitQuery(): Unit = {
    val (model, macroFile, _, events) = setup()
    withSession(model) { (session, controller) =>
      await("Initial compilation finishes", 40) { controller.compilation.nonEmpty && !controller.running }
      edit(macroFile, macroSql + "\n-- changed macro")
      await("Macro edits discard generated steps") { controller.compilation.isEmpty && controller.frames.isEmpty }
      session.stepOver(false)
      await("The first explicit step recompiles only", 40) { controller.compilation.nonEmpty && !controller.running }
      assertEquals(2, events.count(e => e.running && e.command.exists(_.action == "Compile")))
      assertFalse(events.exists(_.command.exists(_.action == "Preview")))
      session.stepOver(false)
      await("A further explicit step queries the fresh compilation", 40) { controller.result.nonEmpty && !controller.running }
    }
  }

  def testUnsupportedCliExplainsMissingMappingsAndRunsNoPreview(): Unit = {
    val (model, _, directory, events) = setup()
    Files.writeString(directory.resolve("no_map"), "")
    withSession(model) { (_, controller) =>
      await("The CLI finishes without a mapping") { !controller.running && events.exists(e => !e.running && e.command.exists(_.action == "Compile")) }
      assertTrue(controller.message, controller.message.contains("did not provide source mappings") || controller.message.startsWith("Source mapping is unavailable"))
      assertTrue(controller.compilation.isEmpty)
      assertFalse(events.exists(_.command.exists(_.action == "Preview")))
    }
  }

  def testStopAndFailedCompilationCannotInstallAStaleMap(): Unit = {
    val (model, _, directory, events) = setup()
    Files.writeString(directory.resolve("hold_compile"), "")
    withSession(model) { (_, controller) =>
      await("Compilation is running") { getProject.getService(classOf[DbtRunner]).isRunning }
      controller.stop()
      await("Stop cancels compilation") { !getProject.getService(classOf[DbtRunner]).isRunning }
      assertTrue(controller.compilation.isEmpty)
      assertFalse(events.exists(_.command.exists(_.action == "Preview")))
      Files.delete(directory.resolve("hold_compile"))
      Files.writeString(directory.resolve("fail_compile"), "")
      controller.restart(); controller.step()
      await("Failed compilation cannot provide steps") { !controller.running && controller.message.contains("exit 1") }
      assertTrue(controller.compilation.isEmpty)
    }
  }

  def testMacroDefinitionBreakpointHighlightsItsSource(): Unit = {
    val (model, macroFile, _, _) = setup()
    val manager = XDebuggerManager.getInstance(getProject).getBreakpointManager
    val kind = XDebuggerUtil.getInstance.findBreakpointType(classOf[DbtCteBreakpointType])
    val breakpoint = WriteAction.compute[com.intellij.xdebugger.breakpoints.XLineBreakpoint[DbtCteBreakpointProperties], RuntimeException](
      () => manager.addLineBreakpoint(kind, macroFile.getVirtualFile.getUrl, 1, null))
    try withSession(model) { (session, controller) =>
      await("The macro expansion is ready") { controller.compilation.nonEmpty && !controller.running }
      session.resume()
      await("A macro-definition breakpoint stops with its result") { controller.result.nonEmpty && !controller.running && session.isSuspended }
      assertEquals(0, controller.position)
      assertEquals(macroFile.getVirtualFile, session.getCurrentPosition.getFile)
      assertEquals(1, session.getCurrentPosition.getLine)
    } finally WriteAction.run(() => manager.removeBreakpoint(breakpoint))
  }

  def testTargetChangeCancelsCompilationBeforeInstallingTheOldMap(): Unit = {
    val (model, _, directory, events) = setup()
    Files.writeString(directory.resolve("hold_compile"), "")
    withSession(model) { (_, controller) =>
      await("The old target is compiling") { getProject.getService(classOf[DbtRunner]).isRunning }
      configureSettings(_.target = "prod")
      await("Target change cancels the old compilation") { !getProject.getService(classOf[DbtRunner]).isRunning && !controller.running }
      assertTrue(controller.compilation.isEmpty)
      Files.delete(directory.resolve("hold_compile"))
      controller.step()
      await("Only an explicit step compiles the new target") { controller.compilation.nonEmpty && !controller.running }
      assertEquals("prod", events.flatMap(_.command).filter(_.action == "Compile").last.target)
      assertFalse(events.exists(_.command.exists(_.action == "Preview")))
    }
  }

  def testSourceMapsRejectAnotherCompilationAndUnknownLocations(): Unit = {
    val (model, _, _, _) = setup()
    val job = DbtCteCompileJob.prepare(workspace, workspace.model(model).get).toOption.get
    try {
      Files.writeString(job.directory.resolve("report.json"), """{"token":"other","sql":"with a as (select 1) select * from a","spans":[]}""")
      assertTrue(job.load().left.toOption.get.contains("does not belong"))
      val packet = new com.google.gson.JsonObject
      packet.addProperty("token", job.token)
      packet.addProperty("sql", "with a as (select 1) select * from a")
      packet.add("spans", com.google.gson.JsonParser.parseString("""[{"start":5,"end":20,"origins":[{"path":"/unknown.sql","line":0,"instance":1}]}]"""))
      Files.writeString(job.directory.resolve("report.json"), packet.toString)
      assertTrue(job.load().left.toOption.get.contains("invalid source locations"))
    } finally job.dispose()
  }

  def testCompiledParserRetainsRangesAndRejectsUnsupportedSql(): Unit = {
    val sql = "\n\n\n-- header\nwith first as (select '😀\n\n\n' as id),\n\n\nsecond as (select id from first) select * from second"
    val analysis = DbtSqlAnalysis.compiled(sql).toOption.get
    assertEquals(Vector("first", "second"), analysis.ctes.map(_.name))
    assertEquals(Vector("first"), analysis.ctes.last.dependencies)
    assertTrue(analysis.ctes.forall(c => c.range.substring(sql).startsWith(c.name + " as")))
    assertTrue(DbtSqlAnalysis.compiled("select 1").isLeft)
    assertTrue(DbtSqlAnalysis.compiled("with a as (select 1) select * from a; delete from orders").isLeft)
    assertTrue(DbtSqlAnalysis.compiled("with recursive a as (select * from a) select * from a").isLeft)
    assertTrue(DbtSqlAnalysis.compiled("with a as (delete from orders returning *) select * from a").isLeft)
    val literal = "select '{{ literal_only }}' as id"
    assertEquals(literal, com.google.gson.JsonParser.parseString(DbtCteCompilation.inline(literal).stripPrefix("{{ ").stripSuffix(" }}")).getAsString)
  }
}
