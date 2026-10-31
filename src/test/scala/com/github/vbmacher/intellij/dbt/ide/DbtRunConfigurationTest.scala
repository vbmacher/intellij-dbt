package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project.{DbtDeclarationSource, DbtResourceData, DbtSymbol, DbtWorkspace}
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.execution.runners.{ExecutionEnvironmentBuilder, ProgramRunner}
import com.intellij.openapi.util.{Disposer, Ref}
import com.intellij.testFramework.PlatformTestUtil
import org.jdom.Element
import org.junit.Assert._

import java.nio.file.{Files, Path}

/** Native dbt Run configurations: command preparation, persisted overrides and the standard Run/Debug sessions. */
final class DbtRunConfigurationTest extends DbtTestCase {
  private def setup(): (DbtRunConfiguration, Path) = {
    project();
    add("models/orders.sql", "select 1 as id")
    val dir = tempDirectory("dbt native commands ")
    Files.writeString(dir.resolve("dbt_project.yml"), "name: analytics\n")
    val script = dir.resolve("dbt.py")
    Files.writeString(script,
      """import json,os,time,sys
print('native command output',flush=True)
if 'hold' in sys.argv:
 while not os.path.exists('release'): time.sleep(.01)
if 'show' in sys.argv: print(json.dumps({'show':[{'id':7}]}),flush=True)
""")
    val config = runConfiguration("native command");
    config.action = "Run"
    val options = workspace.roots.head.runOptions
    options.projectRoot = dir.toString;
    options.notifications = false
    config.executable = "/usr/bin/python3";
    config.executableArguments = "\"" + script.toString + "\" hold"
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

    await("Ordinary command streams into its own native Run console") {
      output(first).contains("native command output")
    }
    assertFalse(first.process.isProcessTerminated)
    first.process.destroyProcess()
    await("Run Stop cancels the managed command") {
      first.process.isProcessTerminated && !getProject.getService(classOf[DbtRunner]).isRunning
    }
    val second = start(config)
    await("Second invocation starts") {
      output(second).contains("native command output")
    }
    Disposer.dispose(first)
    assertTrue("Disposing a completed session cannot stop the next command", getProject.getService(classOf[DbtRunner]).isRunning)
    Files.writeString(dir.resolve("release"), "")
    await("Next invocation completes independently") {
      second.process.isProcessTerminated
    }
    assertEquals(0, second.process.getExitCode.intValue())
  }

  def testSavedInlinePreviewKeepsItsSourceScopeAndRunsThroughTheStandardProfile(): Unit = {
    val (config, dir) = setup()
    config.action = "Preview";
    config.scope = workspace.symbols.find(_.isCode).get.file.getVirtualFile.getPath
    config.inlineSql = "select 7 as id"
    val xml = new Element("configuration");
    config.writeExternal(xml)
    val saved = runConfiguration("native command");
    saved.readExternal(xml)
    Files.writeString(dir.resolve("release"), "")
    assertEquals(DbtCommandScope.Inline("select 7 as id"), saved.prepare().toOption.get._1.request.scope)
    val console = start(saved)
    await("Saved inline Preview completes") {
      console.process.isProcessTerminated
    }
    assertEquals(0, console.process.getExitCode.intValue())
    assertEquals("select 7 as id", saved.inlineSql)
  }

  def testNativeDebuggerOpensWithoutQueryingAndUsesSourceBreakpointsAndFrames(): Unit = {
    project();
    val file = configure("models/inspect.sql", "with a as (\n  select 1\n), b as (select * from a)\nselect * from b<caret>")
    val typeOf = com.intellij.xdebugger.XDebuggerUtil.getInstance.findBreakpointType(classOf[DbtCteBreakpointType])
    assertNotNull(typeOf)
    assertTrue(typeOf.canPutAt(file.getVirtualFile, 0, getProject))
    assertTrue(typeOf.canPutAt(file.getVirtualFile, 2, getProject))
    assertTrue("Source lines may hold pending breakpoints before compilation", typeOf.canPutAt(file.getVirtualFile, 1, getProject))
    assertTrue("Lines without generated CTEs remain unresolved after compilation", typeOf.canPutAt(file.getVirtualFile, 3, getProject))
    val existingEditors = com.intellij.openapi.editor.EditorFactory.getInstance.getAllEditors.toSet
    val frames = com.github.vbmacher.intellij.dbt.navigation.DbtSqlAnalysis.get(workspace, file).get.ctes
    val session = DbtCteDebugger.open(getProject, file.getVirtualFile.getPath, frames).getSession
    var nativeUi = Option.empty[com.intellij.execution.ui.RunnerLayoutUi]
    session.runWhenUiReady(ui => nativeUi = Some(ui))
    try {
      await("Native debugger frontend finishes creating its tabs") {
        nativeUi.nonEmpty
      }
      await("Native Debug session is suspended before any query") {
        session.isSuspended && session.getCurrentPosition != null
      }
      assertFalse(getProject.getService(classOf[DbtRunner]).isRunning)
      assertEquals(file.getVirtualFile, session.getCurrentPosition.getFile)
      val process = session.getDebugProcess.asInstanceOf[DbtCteDebugProcess]
      assertEquals(Vector("a", "b"), process.controller.frames.map(_.name))
      assertTrue(process.controller.result.isEmpty)
      assertNotNull(nativeUi.get.getComponent)
      assertNotNull("CTE rows are installed in the native Debug layout", nativeUi.get.findContent("dbt.cte.rows"))
      session.stepOut()
      await("Unsupported Step Out preserves the inspection position") {
        session.isSuspended
      }
      session.runToPosition(session.getCurrentPosition, false)
      await("Unsupported Run to Cursor preserves the inspection position") {
        session.isSuspended
      }
      assertFalse("Unsupported debugger actions execute no query", getProject.getService(classOf[DbtRunner]).isRunning)
    } finally {
      session.stop()
      val descriptor = session.asInstanceOf[com.intellij.xdebugger.impl.XDebugSessionImpl].getMockRunContentDescriptorIfInitialized
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

  def testModelGutterOffersCteDebugOnlyForSqlModels(): Unit = {
    project()
    val sql = configure("models/inspect.sql", "with a as (select 1) select * from a<caret>")
    val contributor = new DbtModelRunLineMarker
    val info = contributor.getSlowInfo(sql.findElementAt(0))
    assertNotNull(info)
    val debug = info.actions.filter(_.isInstanceOf[DbtInspectCtesAction])
    assertEquals(1, debug.length)
    assertEquals("Debug dbt CTEs", debug.head.getTemplatePresentation.getText)
    assertTrue("Standard Run actions remain available", info.actions.exists(_.isInstanceOf[com.intellij.execution.lineMarker.ExecutorAction]))
    val python = configure("models/python_model.py", "def model(dbt, session):\n  return None<caret>")
    val pythonInfo = contributor.getSlowInfo(python.findElementAt(0))
    assertNotNull(pythonInfo)
    assertFalse(pythonInfo.actions.exists(_.isInstanceOf[DbtInspectCtesAction]))
    val test = configure("tests/check.sql", "select 1<caret>")
    assertNull(contributor.getSlowInfo(test.findElementAt(0)))
    assertFalse(new DbtTestRunLineMarker().getSlowInfo(test.findElementAt(0)).actions.exists(_.isInstanceOf[DbtInspectCtesAction]))
  }

  def testModelRunContextDoesNotDiscoverUnrelatedTests(): Unit = {
    project()
    val model = add("models/orders.sql", "select 1 as id")
    val test = add("tests/check_orders.sql", "select * from {{ ref('orders') }}")
    var testMetadataReads = 0
    DbtDeclarationSource.EP.getPoint.registerExtension(new DbtDeclarationSource {
      override def declarations(w: DbtWorkspace): Vector[DbtSymbol] = Vector.empty
      override def documentation(w: DbtWorkspace, symbol: DbtSymbol): Option[DbtResourceData] = {
        if (symbol.kind == "test") testMetadataReads += 1
        None
      }
    }, getTestRootDisposable)
    val configuration = runConfiguration()
    val producer = new DbtRunProducer
    val element = model.findElementAt(0)
    val context = new ConfigurationContext(element)
    assertTrue(DbtTestRunLineMarker.testsAt(element).forall(_.isEmpty))
    assertTrue(producer.setupConfigurationFromContext(configuration, context, new Ref(element)))
    assertEquals("Run", configuration.action)
    assertEquals(model.getVirtualFile.getPath, configuration.scope)
    assertTrue(producer.isConfigurationFromContext(configuration, context))
    assertEquals("Opening a model Run menu must not load test metadata", 0, testMetadataReads)

    val testElement = test.findElementAt(0)
    assertTrue(producer.setupConfigurationFromContext(configuration, new ConfigurationContext(testElement), new Ref(testElement)))
    assertEquals("Test", configuration.action)
    assertTrue(configuration.exactTests)
    assertEquals(Vector("fqn:analytics.check_orders"), configuration.selectors)
    assertTrue("Test contexts still discover exact test identities", testMetadataReads > 0)
  }

  def testModelRunContextKeepsPackageOwnershipAndRejectsUnavailableModels(): Unit = {
    project()
    val local = add("models/orders.sql", "select 1")
    val python = add("models/python_model.py", "def model(dbt, session):\n  return None")
    add("dbt_packages/util/dbt_project.yml", "name: util\nconfig-version: 2\n")
    val packaged = add("dbt_packages/util/models/orders.sql", "select 2")
    val disabled = add("models/disabled.sql", "{{ config(enabled=false) }} select 3")
    val generated = add("target/generated.sql", "select 4")
    val macroFile = add("macros/example.sql", "{% macro example() %}select 5{% endmacro %}")
    val producer = new DbtRunProducer
    for (file <- Vector(local, python, packaged, disabled, generated, macroFile)) {
      val configuration = runConfiguration()
      val element = file.findElementAt(0)
      val context = new ConfigurationContext(element)
      val available = producer.setupConfigurationFromContext(configuration, context, new Ref(element))
      assertEquals(file.getName, Vector(local, python, packaged).contains(file), available)
      if (available) {
        assertEquals("Package models execute in the consuming project", root.directory.getPath, configuration.root)
        assertEquals(file.getVirtualFile.getPath, configuration.scope)
        assertTrue(producer.isConfigurationFromContext(configuration, context))
        configuration.scope = generated.getVirtualFile.getPath
        assertFalse(producer.isConfigurationFromContext(configuration, context))
      }
    }
  }

  def testEveryCommandPreparesWithoutChangingProjectDefaultsAndRoundTripsOverrides(): Unit = {
    project("profile: analytics\n")
    add("profiles.yml", "analytics: {target: dev, outputs: {dev: {type: postgres}, prod: {type: postgres}}}\n")
    val model = add("models/orders.sql", "{{ config(materialized='incremental') }} select 1 as id")
    val w = workspace;
    val root = w.roots.head
    root.runOptions.target = "dev"
    val config = runConfiguration()
    config.target = "prod";
    config.executable = "/usr/bin/python3";
    config.executableArguments = "'wrapper with spaces.py'"
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
    config.action = "Build";
    config.scope = model.getVirtualFile.getPath;
    config.fullRefresh = true
    val xml = new Element("configuration");
    config.writeExternal(xml)
    val restored = runConfiguration();
    restored.readExternal(xml)
    assertEquals(config.scope, restored.scope);
    assertEquals(config.target, restored.target)
    assertEquals(config.executableArguments, restored.executableArguments)
    assertTrue(restored.prepare().toOption.get._1.request.fullRefresh)
  }

  def testStoredConfigurationsHaveIndependentScopesAndGraphFilteringCannotRetargetThem(): Unit = {
    project();
    val one = add("models/one.sql", "select 1");
    val two = add("models/two.sql", "select 2")
    val first = runConfiguration();
    first.action = "Run";
    first.scope = one.getVirtualFile.getPath
    val second = runConfiguration();
    second.action = "Compile";
    second.scope = two.getVirtualFile.getPath
    val before = first.prepare().toOption.get._1
    val panel = new DbtToolWindowPanel(getProject);
    Disposer.register(getTestRootDisposable, panel)
    panel.handleMessage("""{"type":"filter","id":"two","value":""}""")
    assertEquals(before.arguments, first.prepare().toOption.get._1.arguments)
    assertTrue(second.prepare().toOption.get._1.arguments.contains("fqn:analytics.two"))
    assertFalse(second.prepare().toOption.get._1.arguments.contains("fqn:analytics.one"))
  }

  def testCliSelectionBecomesUnusableAfterSourceOrExecutionSettingsChange(): Unit = {
    project();
    val file = add("models/orders.sql", "select 1")
    val config = runConfiguration();
    config.action = "Run";
    config.selectors = Vector("runtime:unknown")
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
    project();
    val model = add("models/orders.sql", "select 1")
    val config = runConfiguration();
    config.action = "Preview"
    assertTrue(config.prepare().isLeft)
    config.scope = model.getVirtualFile.getPath
    assertTrue(config.prepare().isRight)
    config.action = "Source Freshness"
    assertTrue(config.prepare().isLeft)
    config.scope = ""
    assertEquals(DbtCommandScope.All, config.prepare().toOption.get._1.request.scope)
  }
}
