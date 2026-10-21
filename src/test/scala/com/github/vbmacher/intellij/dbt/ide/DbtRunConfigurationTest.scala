package com.github.vbmacher.intellij.dbt.ide

import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.execution.runners.{ExecutionEnvironmentBuilder, ProgramRunner}
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import org.jdom.Element
import org.junit.Assert._

import java.nio.file.{Files, Path}

/** Native dbt Run configurations: command preparation, persisted overrides and the standard Run/Debug sessions. */
final class DbtRunConfigurationTest extends DbtTestCase {
  private def setup(): (DbtRunConfiguration, Path) = {
    project(); add("models/orders.sql", "select 1 as id")
    val dir = tempDirectory("dbt native commands ")
    Files.writeString(dir.resolve("dbt_project.yml"), "name: analytics\n")
    val script = dir.resolve("dbt.py")
    Files.writeString(script, """import json,os,time,sys
print('native command output',flush=True)
if 'hold' in sys.argv:
 while not os.path.exists('release'): time.sleep(.01)
if 'show' in sys.argv: print(json.dumps({'show':[{'id':7}]}),flush=True)
""")
    val config = runConfiguration("native command"); config.action = "Run"
    val options = workspace.roots.head.runOptions
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
    config.action = "Preview"; config.scope = workspace.symbols.find(_.isCode).get.file.getVirtualFile.getPath
    config.inlineSql = "select 7 as id"
    val xml = new Element("configuration"); config.writeExternal(xml)
    val saved = runConfiguration("native command"); saved.readExternal(xml)
    Files.writeString(dir.resolve("release"), "")
    assertEquals(DbtCommandScope.Inline("select 7 as id"), saved.prepare().toOption.get._1.request.scope)
    val console = start(saved)
    await("Saved inline Preview completes") { console.process.isProcessTerminated }
    assertEquals(0, console.process.getExitCode.intValue())
    assertEquals("select 7 as id", saved.inlineSql)
  }
  def testNativeDebuggerOpensWithoutQueryingAndUsesSourceBreakpointsAndFrames(): Unit = {
    project(); val file = configure("models/inspect.sql", "with a as (select 1), b as (select * from a) select * from b<caret>")
    val frames = com.github.vbmacher.intellij.dbt.navigation.DbtSqlAnalysis.get(workspace, file).get.ctes
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

  def testEveryCommandPreparesWithoutChangingProjectDefaultsAndRoundTripsOverrides(): Unit = {
    project("profile: analytics\n")
    add("profiles.yml", "analytics: {target: dev, outputs: {dev: {type: postgres}, prod: {type: postgres}}}\n")
    val model = add("models/orders.sql", "{{ config(materialized='incremental') }} select 1 as id")
    val w = workspace; val root = w.roots.head
    root.runOptions.target = "dev"
    val config = runConfiguration()
    config.target = "prod"; config.executable = "/usr/bin/python3"; config.executableArguments = "'wrapper with spaces.py'"
    for (action <- DbtCommand.Actions :+ "Resolve Selection") {
      config.action = action
      config.scope = if (action == "Preview") model.getVirtualFile.getPath else ""
      config.checkConfiguration()
      val command = config.prepare().fold(error => fail(action + ": " + error).asInstanceOf[DbtCommand], _._1)
      assertEquals(action, command.action); assertEquals("prod", command.target)
      assertEquals(Vector("/usr/bin/python3", "wrapper with spaces.py"), command.prefix)
      assertEquals("dev", root.runOptions.target)
      assertEquals("dbt", root.runOptions.executable)
    }
    config.action = "Build"; config.scope = model.getVirtualFile.getPath; config.fullRefresh = true
    val xml = new Element("configuration"); config.writeExternal(xml)
    val restored = runConfiguration(); restored.readExternal(xml)
    assertEquals(config.scope, restored.scope); assertEquals(config.target, restored.target)
    assertEquals(config.executableArguments, restored.executableArguments)
    assertTrue(restored.prepare().toOption.get._1.request.fullRefresh)
  }
  def testStoredConfigurationsHaveIndependentScopesAndGraphFilteringCannotRetargetThem(): Unit = {
    project(); val one = add("models/one.sql", "select 1"); val two = add("models/two.sql", "select 2")
    val first = runConfiguration(); first.action = "Run"; first.scope = one.getVirtualFile.getPath
    val second = runConfiguration(); second.action = "Compile"; second.scope = two.getVirtualFile.getPath
    val before = first.prepare().toOption.get._1
    val panel = new DbtToolWindowPanel(getProject); Disposer.register(getTestRootDisposable, panel)
    panel.handleMessage("""{"type":"filter","id":"two","value":""}""")
    assertEquals(before.arguments, first.prepare().toOption.get._1.arguments)
    assertTrue(second.prepare().toOption.get._1.arguments.contains("fqn:analytics.two"))
    assertFalse(second.prepare().toOption.get._1.arguments.contains("fqn:analytics.one"))
  }
  def testCliSelectionBecomesUnusableAfterSourceOrExecutionSettingsChange(): Unit = {
    project(); val file = add("models/orders.sql", "select 1")
    val config = runConfiguration(); config.action = "Run"; config.selectors = Vector("runtime:unknown")
    assertTrue(config.prepare().isLeft)
    config.cliSelection = Some(config.sourceKey(workspace) -> Vector("model.analytics.orders"))
    assertTrue(config.prepare().isRight)
    edit(file, "select 2")
    assertTrue("Stale CLI resolution cannot execute", config.prepare().isLeft)
    config.cliSelection = Some(config.sourceKey(workspace) -> Vector("model.analytics.orders"))
    config.target = "prod"
    assertTrue(config.prepare().isLeft)
    config.cliSelection = Some(config.sourceKey(workspace) -> Vector("missing.resource"))
    assertTrue("Unknown CLI identities must never widen execution", config.prepare().isLeft)
  }
  def testPreviewRejectsFoldersAndFreshnessRemainsProjectWide(): Unit = {
    project(); val model = add("models/orders.sql", "select 1")
    val config = runConfiguration(); config.action = "Preview"
    assertTrue(config.prepare().isLeft)
    config.scope = model.getVirtualFile.getPath
    assertTrue(config.prepare().isRight)
    config.action = "Source Freshness"
    assertTrue(config.prepare().isLeft)
    config.scope = ""
    assertEquals(DbtCommandScope.All, config.prepare().toOption.get._1.request.scope)
  }
}
