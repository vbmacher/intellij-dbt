package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project._
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.{ExecutionEnvironmentBuilder, ProgramRunner}
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.openapi.util.Disposer
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.PlatformTestUtil
import org.jdom.Element
import org.junit.Assert._
import java.nio.file.{Files, Path}
import java.time.Instant
import scala.jdk.CollectionConverters._

final class DbtNativeTestRunnerTest extends DbtCodeInsightTestCase {
  override protected def tearDown(): Unit = try DbtProjectSettings.get(getProject).loadState(new DbtSettingsState) finally super.tearDown()
  private def await(message: String)(condition: => Boolean): Unit =
    PlatformTestUtil.waitWithEventsDispatching(message, () => condition, 20)
  private def configuration(): DbtRunConfiguration = {
    val factory = ConfigurationTypeUtil.findConfigurationType(classOf[DbtConfigurationType]).getConfigurationFactories.head
    val config = factory.createTemplateConfiguration(getProject).asInstanceOf[DbtRunConfiguration]
    config.action = "Test"
    config.root = DbtWorkspace.get(getProject).roots.head.directory.getPath
    config
  }
  private def setup(mode: String = ""): (DbtRunConfiguration, DbtCommand, Path) = {
    project(); add("tests/check_orders.sql", "select 1")
    val directory = Files.createTempDirectory("dbt native tests ")
    Disposer.register(getTestRootDisposable, () => com.intellij.openapi.util.io.FileUtil.delete(directory.toFile))
    Files.writeString(directory.resolve("dbt_project.yml"), "name: analytics\nconfig-version: 2\n")
    val script = directory.resolve("external dbt.py")
    Files.writeString(script, """import json,os,sys,time,datetime
with open('invocation.json','w') as f: json.dump(sys.argv[1:],f)
print('native raw output',flush=True)
print(json.dumps({'info':{'invocation_id':'native-run','msg':'\x1b[32mSTART check_orders\x1b[0m','level':'info','ts':'2026-10-20T12:34:56Z'},'data':{'node_info':{'unique_id':'test.analytics.check_orders','node_status':'started'}}}),flush=True)
if '--hold' in sys.argv:
 while not os.path.exists('release'): time.sleep(.01)
os.makedirs(os.environ['DBT_TARGET_PATH'],exist_ok=True)
with open(os.path.join(os.environ['DBT_TARGET_PATH'],'run_results.json'),'w') as f:
 json.dump({'metadata':{'dbt_schema_version':'https://schemas.getdbt.com/dbt/run-results/v6.json','invocation_id':'native-run','generated_at':datetime.datetime.now(datetime.timezone.utc).isoformat()},'args':{'target':'dev'},'results':[{'unique_id':'test.analytics.check_orders','status':'fail' if '--fail' in sys.argv else 'pass','message':'3 unexpected rows','failures':3}]},f)
sys.exit(1 if '--fail' in sys.argv else 0)
""")
    val w = DbtWorkspace.get(getProject); val root = w.roots.head
    root.runOptions.executable = "/usr/bin/python3"
    root.runOptions.executableArguments = "\"" + script.toString + "\""
    root.runOptions.projectRoot = directory.toString
    root.runOptions.target = "dev"; root.runOptions.notifications = false
    DbtProjectSettings.get(getProject).changed()
    val updated = DbtWorkspace.get(getProject)
    val command = DbtCommand.create(updated, DbtRunSelection.all(updated, root.directory.getPath).get, "Test", fullRefresh = false).toOption.get
    val config = configuration()
    val captured = command.copy(request = command.request.copy(extraArguments = mode.split(" ").filter(_.nonEmpty).toVector))
    config.captured = Some(captured)
    (config, captured, directory)
  }
  private def tests(view: SMTRunnerConsoleView): Vector[SMTestProxy] =
    view.getResultsViewer.getTestsRootNode.getAllTests.asScala.filterNot(_.isSuite).toVector
  private def session(config: DbtRunConfiguration, command: DbtCommand): DbtTestConsole = {
    val session = new DbtTestConsole(config, DefaultRunExecutor.getRunExecutorInstance)
    Disposer.register(getTestRootDisposable, session.view)
    session.start(command, DbtTestDiscovery.discover(DbtWorkspace.get(getProject)))
    session
  }

  def testStandardRunProfileProvidesNativeConsoleAndManagedProcessWithExternalTool(): Unit = {
    val (config, command, directory) = setup()
    val nativeEvents = scala.collection.mutable.ArrayBuffer.empty[String]
    getProject.getMessageBus.connect(getTestRootDisposable).subscribe(
      com.intellij.execution.testframework.sm.runner.SMTRunnerEventsListener.TEST_STATUS,
      new com.intellij.execution.testframework.sm.runner.SMTRunnerEventsAdapter {
        override def onTestFinished(test: SMTestProxy): Unit = { nativeEvents += test.getMetainfo; () }
      })
    val executor = DefaultRunExecutor.getRunExecutorInstance
    val programRunner = ProgramRunner.getRunner(executor.getId, config)
    assertNotNull("The IDE's standard Run action must accept dbt tests", programRunner)
    val environment = ExecutionEnvironmentBuilder.create(executor, config).build()
    val result = config.getState(executor, environment).execute(executor, programRunner)
    assertTrue("The native Run toolbar exposes failed-test reruns", result.asInstanceOf[com.intellij.execution.DefaultExecutionResult]
      .getRestartActions.exists(_.isInstanceOf[com.intellij.execution.testframework.actions.AbstractRerunFailedTestsAction]))
    val view = result.getExecutionConsole.asInstanceOf[SMTRunnerConsoleView]
    Disposer.register(getTestRootDisposable, view)
    await("Standard Run profile completes") { result.getProcessHandler.isProcessTerminated && tests(view).exists(_.isPassed) }
    assertEquals(0, result.getProcessHandler.getExitCode.intValue())
    assertTrue(tests(view).head.isPassed)
    assertTrue("Native framework listeners receive dbt results", nativeEvents.contains("test.analytics.check_orders"))
    assertEquals(command.arguments.drop(2), com.google.gson.JsonParser.parseString(Files.readString(directory.resolve("invocation.json"))).getAsJsonArray.asScala.map(_.getAsString).toVector)
    val location = tests(view).head.getLocation(getProject, GlobalSearchScope.projectScope(getProject))
    assertEquals("check_orders.sql", location.getPsiElement.getContainingFile.getName)
  }

  def testNativeStopCancelsAndFailureRerunRetainsCapturedContextAndExactIdentity(): Unit = {
    val (config, command, directory) = setup("--hold --fail")
    val first = session(config, command)
    await("Test streams before process exit") { tests(first.view).exists(_.isInProgress) }
    first.process.destroyProcess()
    await("Native Stop terminates the managed process") { first.process.isProcessTerminated && tests(first.view).exists(_.wasTerminated()) }
    assertTrue(tests(first.view).head.wasTerminated())
    assertFalse(getProject.getService(classOf[DbtRunner]).isRunning)
    val failedCommand = command.copy(request = command.request.copy(extraArguments = Vector("--fail")))
    val second = session(config, failedCommand)
    await("Failure appears in native results") { second.process.isProcessTerminated && tests(second.view).exists(_.isDefect) }
    val proxy = tests(second.view).head
    assertTrue(proxy.isDefect); assertTrue(proxy.getErrorMessage.contains("unexpected rows"))
    val action = second.properties.createRerunFailedTestsAction(second.view)
    assertNotNull("Use IntelliJ's standard failed-test action", action)
    val executor = DefaultRunExecutor.getRunExecutorInstance
    val environment = ExecutionEnvironmentBuilder.create(executor, config).build()
    val method = action.getClass.getDeclaredMethod("getRunProfile", classOf[com.intellij.execution.runners.ExecutionEnvironment])
    method.setAccessible(true)
    val profile = method.invoke(action, environment).asInstanceOf[com.intellij.execution.testframework.actions.AbstractRerunFailedTestsAction.MyRunProfile]
    val rerunConfig = profile.getPeer.asInstanceOf[DbtRunConfiguration]
    val rerun = rerunConfig.prepare().toOption.get._1
    assertEquals(failedCommand.prefix, rerun.prefix)
    assertEquals(failedCommand.target, rerun.target)
    assertEquals(failedCommand.environment, rerun.environment)
    assertEquals(Some("empty"), rerun.request.indirectSelection)
    assertFalse(rerun.request.scope == DbtCommandScope.All)
  }

  def testBuildConsoleDisplaysReadableColoredMessagesAndRetainsNativeTestResults(): Unit = {
    val (config, command, _) = setup()
    config.action = "Build"
    val console = session(config, command.copy(request = command.request.copy(action = DbtCommandAction.Build)))
    await("Build test result completes") { console.process.isProcessTerminated && tests(console.view).exists(_.isPassed) }
    console.view.getPrinter.updateOnTestSelected(console.view.getResultsViewer.getTestsRootNode)
    val view = console.view.getConsole.asInstanceOf[com.intellij.execution.impl.ConsoleViewImpl]
    await("Readable dbt output reaches the native console") {
      view.getEditor.getDocument.getText.contains("12:34:56  START check_orders")
    }
    val text = view.getEditor.getDocument.getText
    assertTrue(text.contains("native raw output"))
    assertFalse(text.contains("invocation_id"))
    assertFalse(text.contains("\u001b"))
    assertFalse(text.contains("\\u001b"))
    assertEquals("test.analytics.check_orders", tests(console.view).head.getMetainfo)
  }

  def testNativeResultStatesAndMissingEvidenceNeverBecomeSuccess(): Unit = {
    val (config, command, _) = setup("--hold")
    val console = session(config, command)
    await("Test starts") { tests(console.view).exists(_.isInProgress) }
    val results = Vector("pass" -> "passed", "fail" -> "failed", "warn" -> "warning", "error" -> "error", "skip" -> "skipped", "cancel" -> "canceled")
      .map { case (name, state) => DbtExecutionResult("test.analytics." + name, state, "detail: " + name + " 'quoted' [row] | value\nsecond line") }
    console.update(DbtExecutionRun(command.artifactContext, "dev", "native-run", Instant.now.toString, "", results, active = false))
    await("Native events reach the tree") { tests(console.view).exists(p => p.getName == "test.analytics.pass" && p.isPassed) }
    val nodes = tests(console.view).map(p => p.getName -> p).toMap
    assertTrue(nodes("test.analytics.pass").isPassed)
    assertTrue(nodes("test.analytics.fail").isDefect)
    assertEquals("detail: fail 'quoted' [row] | value\nsecond line", nodes("test.analytics.fail").getErrorMessage)
    assertNotEquals(nodes("test.analytics.fail").getMagnitudeInfo, nodes("test.analytics.error").getMagnitudeInfo)
    assertTrue(nodes("test.analytics.warn").isIgnored)
    assertTrue(nodes("test.analytics.skip").isIgnored)
    assertTrue("Unreported tests must not appear as passed", tests(console.view).find(_.getName == "analytics.check_orders").get.isInProgress)
    console.update(DbtExecutionRun(command.artifactContext, "dev", "wrong-invocation", Instant.now.toString, "",
      Vector(DbtExecutionResult("test.analytics.unverified", "passed", "streamed success")), problem = "Artifact invocation does not match the active run"))
    await("Unverified streamed success is visible without passing") {
      tests(console.view).exists(p => p.getName == "test.analytics.unverified" && p.isInProgress)
    }
    assertFalse(tests(console.view).find(_.getName == "test.analytics.unverified").get.isPassed)
    console.process.destroyProcess()
    await("Held process stopped") { console.process.isProcessTerminated && tests(console.view).exists(p => p.getName == "test.analytics.cancel" && p.wasTerminated()) }
  }

  def testRunConfigurationRoundTripKeepsScopeAndBuildIntent(): Unit = {
    val (config, _, _) = setup()
    config.captured = None
    config.action = "Build"; config.selectors = Vector("fqn:analytics.check_orders")
    config.exclude = Vector("tag:slow"); config.fullRefresh = true
    val xml = new Element("configuration"); config.writeExternal(xml)
    val restored = configuration(); restored.readExternal(xml)
    assertEquals(config.root, restored.root)
    assertEquals(config.action, restored.action)
    assertEquals(config.selectors, restored.selectors)
    assertEquals(config.exclude, restored.exclude)
    assertTrue(restored.fullRefresh)
    val prepared = restored.prepare().toOption.get._1
    assertEquals("Build", prepared.action)
    assertEquals(DbtCommandScope.Selected(config.selectors), prepared.request.scope)
    restored.action = "Test"; restored.exactTests = true; restored.selectors = Vector("missing")
    assertTrue("An exact rerun must never widen to the project", restored.prepare().isLeft)
  }

  def testGutterHasNativeRunActionsAndShowsPassFailAndSourceInvalidation(): Unit = {
    val (config, command, _) = setup()
    val file = DbtWorkspace.get(getProject).symbols.find(_.kind == "test").get.file
    open(file, 0)
    val definition = DbtTestRunLineMarker.testsAt(file.findElementAt(0)).get
    assertEquals("not run", DbtTestRunLineMarker.status(file, definition))
    val contributor = new DbtTestRunLineMarker
    assertNotNull(contributor.getSlowInfo(file.findElementAt(0)))
    assertTrue(myFixture.findAllGutters().asScala.exists(g => Option(g.getTooltipText).exists(_.contains("dbt tests:"))))
    val passed = session(config, command)
    await("Passing test completes") { passed.process.isProcessTerminated && tests(passed.view).exists(_.isPassed) }
    assertEquals("passed", DbtTestRunLineMarker.status(file, definition))
    val failed = session(config, command.copy(request = command.request.copy(extraArguments = Vector("--fail"))))
    await("Failing test completes") { failed.process.isProcessTerminated && tests(failed.view).exists(_.isDefect) }
    assertEquals("failed", DbtTestRunLineMarker.status(file, definition))
    edit(file, "select 2")
    assertEquals("not run", DbtTestRunLineMarker.status(file, definition))
  }

  def testYamlUnitAndGenericTestGuttersUseExactDeclarationLines(): Unit = {
    project(); add("models/orders.sql", "select 1 as id")
    val file = configure("models/schema.yml", "models:\n- name: orders\n  columns:\n  - name: id\n    data_tests: [unique, not_null]\nunit_tests:\n- name: <caret>example\n  model: orders\n  given: []\n  expect: {rows: []}\n")
    val unit = DbtTestRunLineMarker.testsAt(file.findElementAt(myFixture.getCaretOffset)).get
    assertEquals(1, unit.size); assertEquals("Unit tests", unit.head.kind)
    val generic = DbtTestRunLineMarker.testsAt(file.findElementAt(file.getText.indexOf("unique"))).get
    assertEquals(2, generic.size)
    assertTrue("Generic hashes require manifest identities", generic.head.selector.isEmpty)
    assertEquals("Tests sharing a declaration line use one Run gutter", 2, myFixture.findAllGutters().asScala.count(g => Option(g.getTooltipText).exists(_.contains("dbt tests:"))))
  }
}
