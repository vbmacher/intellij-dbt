package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project._
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.{ExecutionEnvironmentBuilder, ProgramRunner}
import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import java.nio.file.{Files, Path}
import org.junit.Assert._
import org.jdom.Element

final class DbtNativeCommandsTest extends DbtCodeInsightTestCase {
  override protected def tearDown(): Unit = try DbtProjectSettings.get(getProject).loadState(new DbtSettingsState) finally super.tearDown()
  private def await(message: String)(condition: => Boolean): Unit = PlatformTestUtil.waitWithEventsDispatching(message, () => condition, 20)
  private def configuration(): DbtRunConfiguration = {
    val factory = ConfigurationTypeUtil.findConfigurationType(classOf[DbtConfigurationType]).getConfigurationFactories.head
    val config = new DbtRunConfiguration(getProject, factory, "native command")
    config.root = DbtWorkspace.get(getProject).roots.head.directory.getPath
    config
  }
  private def setup(): (DbtRunConfiguration, Path) = {
    project(); add("models/orders.sql", "select 1 as id")
    val dir = Files.createTempDirectory("dbt native commands ")
    Disposer.register(getTestRootDisposable, () => com.intellij.openapi.util.io.FileUtil.delete(dir.toFile))
    Files.writeString(dir.resolve("dbt_project.yml"), "name: analytics\n")
    val script = dir.resolve("dbt.py")
    Files.writeString(script, """import json,os,time,sys
print('native command output',flush=True)
if 'hold' in sys.argv:
 while not os.path.exists('release'): time.sleep(.01)
if 'show' in sys.argv: print(json.dumps({'show':[{'id':7}]}),flush=True)
""")
    val config = configuration(); config.action = "Run"
    val options = DbtWorkspace.get(getProject).roots.head.runOptions
    options.projectRoot = dir.toString; options.notifications = false
    config.executable = "/usr/bin/python3"; config.executableArguments = "\"" + script.toString + "\" hold"
    (config, dir)
  }
  private def start(config: DbtRunConfiguration): DbtCommandConsole = {
    val executor = DefaultRunExecutor.getRunExecutorInstance
    val environment = ExecutionEnvironmentBuilder.create(executor, config).build()
    val runner = ProgramRunner.getRunner(executor.getId, config)
    assertNotNull("The standard Run executor accepts ordinary dbt commands", runner)
    val result = config.getState(executor, environment).execute(executor, runner)
    val console = result.getExecutionConsole.asInstanceOf[DbtCommandConsole]
    Disposer.register(getTestRootDisposable, console)
    console
  }
  def testNativeCommandSessionStreamsBeforeExitAndStopDoesNotCancelLaterInvocations(): Unit = {
    val (config, dir) = setup()
    val first = start(config)
    def output(view: DbtCommandConsole): String = view.console.asInstanceOf[ConsoleViewImpl].getEditor.getDocument.getText
    await("Ordinary command streams into its own native Run console") { output(first).contains("native command output") }
    assertFalse(first.process.isProcessTerminated)
    first.process.destroyProcess()
    await("Run Stop cancels the managed command") { first.process.isProcessTerminated && !getProject.getService(classOf[DbtRunner]).isRunning }
    val second = start(config)
    await("Second invocation starts") { output(second).contains("native command output") }
    Disposer.dispose(first)
    assertTrue("Disposing a completed session cannot stop the next command", getProject.getService(classOf[DbtRunner]).isRunning)
    Files.writeString(dir.resolve("release"), "")
    await("Next invocation completes independently") { second.process.isProcessTerminated }
    assertEquals(0, second.process.getExitCode.intValue())
  }
  def testSavedInlinePreviewKeepsItsSourceScopeAndRunsThroughTheStandardProfile(): Unit = {
    val (config, dir) = setup()
    config.action = "Preview"; config.scope = DbtWorkspace.get(getProject).symbols.find(_.isCode).get.file.getVirtualFile.getPath
    config.inlineSql = "select 7 as id"
    val xml = new Element("configuration"); config.writeExternal(xml)
    val saved = configuration(); saved.readExternal(xml)
    Files.writeString(dir.resolve("release"), "")
    assertEquals(DbtCommandScope.Inline("select 7 as id"), saved.prepare().toOption.get._1.request.scope)
    val console = start(saved)
    await("Saved inline Preview completes") { console.process.isProcessTerminated }
    assertEquals(0, console.process.getExitCode.intValue())
    assertEquals("select 7 as id", saved.inlineSql)
  }
  def testNativeDebuggerOpensWithoutQueryingAndUsesSourceBreakpointsAndFrames(): Unit = {
    project(); val file = configure("models/inspect.sql", "with a as (select 1), b as (select * from a) select * from b<caret>")
    val frames = com.github.vbmacher.intellij.dbt.navigation.DbtSqlAnalysis.get(DbtWorkspace.get(getProject), file).get.ctes
    val typeOf = com.intellij.xdebugger.XDebuggerUtil.getInstance.findBreakpointType(classOf[DbtCteBreakpointType])
    assertNotNull(typeOf)
    assertTrue(typeOf.canPutAt(file.getVirtualFile, 0, getProject))
    val existingEditors = com.intellij.openapi.editor.EditorFactory.getInstance.getAllEditors.toSet
    val opened = DbtCteDebugger.open(getProject, file.getVirtualFile.getPath, frames)
    val session = opened.getSession
    var nativeUi = Option.empty[com.intellij.execution.ui.RunnerLayoutUi]
    session.runWhenUiReady(ui => nativeUi = Some(ui))
    try {
      await("Native debugger frontend finishes creating its tabs") { nativeUi.nonEmpty }
      await("Native Debug session is suspended before any query") { session.isSuspended && session.getCurrentPosition != null }
      assertFalse(getProject.getService(classOf[DbtRunner]).isRunning)
      assertEquals(file.getVirtualFile, session.getCurrentPosition.getFile)
      val process = session.getDebugProcess.asInstanceOf[DbtCteDebugProcess]
      assertEquals(Vector("a", "b"), process.controller.frames.map(_.name))
      assertTrue(process.controller.result.isEmpty)
      assertNotNull(opened.getRunContentDescriptor.getComponent)
      assertNotNull("CTE rows are installed in the native Debug layout", nativeUi.get.findContent("dbt.cte.rows"))
      session.stepOut()
      await("Unsupported Step Out preserves the inspection position") { session.isSuspended }
      session.runToPosition(session.getCurrentPosition, false)
      await("Unsupported Run to Cursor preserves the inspection position") { session.isSuspended }
      assertFalse("Unsupported debugger actions execute no query", getProject.getService(classOf[DbtRunner]).isRunning)
    } finally {
      session.stop()
      val descriptor = opened.getRunContentDescriptor
      val removed = com.intellij.execution.ui.RunContentManager.getInstance(getProject).removeRunContent(
        com.intellij.execution.executors.DefaultDebugExecutor.getDebugExecutorInstance, descriptor)
      if (!removed) Disposer.dispose(descriptor)
      nativeUi.foreach(ui => Disposer.dispose(ui.getContentManager))
      Disposer.dispose(session.getDebugProcess.createConsole())
      PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
      val remaining = com.intellij.openapi.editor.EditorFactory.getInstance.getAllEditors.filterNot(existingEditors)
      assertTrue(remaining.map(e => e.getDocument.getText.take(120)).mkString("Leaked editors: ", " | ", ""), remaining.isEmpty)
    }
  }
}
