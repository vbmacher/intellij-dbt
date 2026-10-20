package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project._
import com.google.gson.JsonParser
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import org.junit.Assert._
import java.nio.file.{Files, Path}
import java.time.Instant
import scala.jdk.CollectionConverters._

/** Real managed-process execution and Community native test-tree fixtures. No warehouse or dbt installation is used. */
/** Contract: features/test-execution.md and execution-evidence.md: exact reruns, context and result provenance. */
final class DbtTestExecutionTest extends DbtCodeInsightTestCase {
  override protected def tearDown(): Unit = try DbtProjectSettings.get(getProject).loadState(new DbtSettingsState) finally super.tearDown()
  private def await(message: String)(condition: => Boolean): Unit =
    PlatformTestUtil.waitWithEventsDispatching(message, () => condition, 20)
  private def temporary(): Path = {
    val path = Files.createTempDirectory("dbt execution evidence ")
    Disposer.register(getTestRootDisposable, () => com.intellij.openapi.util.io.FileUtil.delete(path.toFile)); path
  }
  private def artifact(path: Path, results: String, invocation: String = "run-1", target: String = "dev", kind: String = "run-results/v6"): Unit = {
    Files.createDirectories(path.getParent)
    Files.writeString(path, s"""{"metadata":{"dbt_schema_version":"https://schemas.getdbt.com/dbt/$kind.json","invocation_id":"$invocation","generated_at":"${Instant.now}"},"args":{"target":"$target"},"results":$results}""")
  }
  private def workspace = DbtWorkspace.get(getProject)
  private def command(directory: Path, script: Path, mode: String = "run"): DbtCommand =
    DbtCommand(Vector(script.toString), DbtCommandRequest(DbtCommandAction.Test, extraArguments = Vector(mode)), directory.toString,
      Map("DBT_TARGET_PATH" -> directory.resolve("target").toString, "DBT_PROFILES_DIR" -> directory.resolve("profiles").toString),
      "dev", notifySystem = false, directory.resolve("target").toString,
      Vector(workspace.roots.head.directory.getPath, "dev", "configuration"))
  private def fake(directory: Path): Path = {
    val script = directory.resolve("fake dbt")
    Files.writeString(script, """#!/usr/bin/python3
import json,os,sys,time,datetime
with open('invocation.json','w') as f: json.dump({'args':sys.argv[1:],'profiles':os.environ.get('DBT_PROFILES_DIR')},f)
if 'show' in sys.argv:
 print(json.dumps({'show':[{'id':1}]}),flush=True)
 sys.exit(0)
for i in ['test.analytics.check_orders']:
 print(json.dumps({'info':{'invocation_id':'current-run','msg':'test started'},'data':{'node_info':{'unique_id':i,'node_status':'started'}}}),flush=True)
if 'hold' in sys.argv:
 while not os.path.exists('release'): time.sleep(.02)
if 'no-write' not in sys.argv:
 os.makedirs(os.environ['DBT_TARGET_PATH'],exist_ok=True)
 with open(os.path.join(os.environ['DBT_TARGET_PATH'],'run_results.json'),'w') as f:
  json.dump({'metadata':{'dbt_schema_version':'https://schemas.getdbt.com/dbt/run-results/v6.json','invocation_id':'current-run','generated_at':datetime.datetime.now(datetime.timezone.utc).isoformat()},'args':{'target':'dev'},'results':[{'unique_id':'test.analytics.check_orders','status':'fail' if 'fail' in sys.argv else 'pass','message':'3 unexpected rows','failures':3,'execution_time':.02,'relation_name':'audit.failed_orders'}]},f)
sys.exit(1 if 'fail' in sys.argv else 0)
""")
    script.toFile.setExecutable(true); script
  }
  def testSupportedArtifactStatesAndMalformedPartialResults(): Unit = {
    val path = temporary().resolve("run_results.json")
    artifact(path, """[{"unique_id":"model.pkg.orders.v2","status":"success"},{"unique_id":"test.pkg.a.hash","status":"fail","failures":3},{"unique_id":"test.pkg.b.hash","status":"warn"},{"unique_id":"test.pkg.c.hash","status":"error"},{"unique_id":"test.pkg.d.hash","status":"skipped"}]""")
    val parsed = DbtExecutionEvidence.parse(path, Vector("root"), "dev")
    assertEquals(Vector("passed", "failed", "warning", "error", "skipped"), parsed.results.map(_.status))
    assertEquals(3L, parsed.results(1).failures)
    assertTrue(DbtExecutionEvidence.parse(path, Vector("root"), "prod").problem.contains("target"))
    assertTrue(DbtExecutionEvidence.parse(path, Vector("root"), "dev", "another-run").problem.contains("invocation"))
    artifact(path, """[{"status":"new-unknown-status"}]""")
    assertTrue(DbtExecutionEvidence.parse(path, Vector("root"), "dev").problem.contains("Partial"))
    Files.writeString(path, "{malformed")
    assertEquals("Malformed execution artifact", DbtExecutionEvidence.parse(path, Vector.empty, "dev").problem)
  }
  def testFreshnessIsEvidenceAndMissingNeverImpliesSuccess(): Unit = {
    val path = temporary().resolve("sources.json")
    assertEquals("Not run / result unavailable", DbtExecutionEvidence.parse(path, Vector.empty, "dev").problem)
    artifact(path, """[{"unique_id":"source.pkg.raw.orders","status":"warn","message":"stale source"}]""", kind = "sources/v3")
    val run = DbtExecutionEvidence.parse(path, Vector("root"), "dev")
    val evidence = getProject.getService(classOf[DbtExecutionEvidence])
    project(); val source = add("models/sources.yml", "sources:\n- name: raw\n  tables: [{name: orders}]\n")
    val symbol = workspace.symbols.find(_.kind == "source_table").get.copy(namespace = "pkg")
    val badge = evidence.overlay(symbol, Vector(run), Vector.empty)
    assertEquals("Freshness warning", badge.get("label").getAsString)
    assertEquals("unknown", evidence.overlay(symbol.copy(version = Some("2")), Vector(run), Vector.empty).get("status").getAsString)
  }
  def testDiscoveryCoversUnitFixturesGenericDefinitionsPackagesVersionsAndDuplicateNames(): Unit = {
    project()
    add("models/orders.sql", "select 1 as id")
    add("tests/check_orders.sql", "select * from {{ ref('orders') }} where id is null")
    val fixture = add("tests/fixtures/input.csv", "id\n1\n")
    val yaml = add("models/tests.yml", """models:
- name: orders
  columns:
  - name: id
    data_tests: [unique, not_null]
unit_tests:
- name: input_is_valid
  model: orders
  given:
  - input: ref('orders')
    format: csv
    fixture: input
  expect: {rows: [{id: 1}]}
""")
    add("dbt_packages/util/dbt_project.yml", "name: util\n")
    add("dbt_packages/util/tests/check_orders.sql", "select 1")
    val tests = DbtTestDiscovery.discover(workspace)
    assertTrue(tests.exists(t => t.kind == "Unit tests" && t.fixtures.exists(_._1 == fixture.getVirtualFile)))
    assertEquals(2, tests.count(_.name.endsWith("check_orders")))
    assertTrue(tests.exists(t => t.name == "unique (id)" && t.file == yaml.getVirtualFile && t.offset == yaml.getText.indexOf("unique")))
    assertTrue("Uncompiled generic test hashes must not be guessed", tests.find(_.name == "unique (id)").get.selector.isEmpty)
    assertEquals(tests.size, tests.map(t => (t.root, t.id)).distinct.size)
  }
  def testRerunFailedKeepsExactPackageVersionIdentityTargetProfilesAndEnvironment(): Unit = {
    project(); val file = add("tests/check_orders.sql", "select 1")
    val dir = temporary(); val original = command(dir, fake(dir)).copy(prefix = Vector("wrapper", "--debug"), request = DbtCommandRequest(DbtCommandAction.Test, DbtCommandScope.Selected(Vector("old+")), exclude = Vector("tag:ignored")))
    def test(id: String, fqn: String) = DbtTestDefinition(id, "duplicate", "Data tests", "model.pkg.orders.v2", workspace.roots.head.directory.getPath,
      file.getVirtualFile, 0, fqn, Vector("model.pkg.orders.v2"))
    val tests = Vector(test("test.pkg.check_orders.hash", "fqn:pkg.tests.check_orders,resource_type:test"),
      test("test.other.check_orders.hash", "fqn:other.tests.check_orders,resource_type:test"))
    val rerun = DbtTestDiscovery.rerun(original, tests, Vector(DbtExecutionResult(tests.head.id, "failed", "rows"), DbtExecutionResult(tests(1).id, "passed", ""))).toOption.get
    assertEquals(original.environment, rerun.environment); assertEquals(original.artifactContext, rerun.artifactContext)
    assertTrue(rerun.arguments.contains(tests.head.selector)); assertFalse(rerun.arguments.contains(tests(1).selector))
    assertFalse(rerun.arguments.contains("old+")); assertFalse(rerun.arguments.contains("tag:ignored"))
    assertTrue(rerun.arguments.sliding(2).exists(_ == Vector("--indirect-selection", "empty")))
    assertEquals(Vector("--target", "dev"), rerun.arguments.sliding(2).find(_.head == "--target").get)
  }
  def testRerunFailedReplacesNamedScopeAndExclusionsWithoutChangingOtherOptions(): Unit = {
    project(); val file = add("tests/check_orders.sql", "select 1")
    val dir = temporary(); val original = command(dir, fake(dir)).copy(prefix = Vector("wrapper"), target = "prod",
      request = DbtCommandRequest(DbtCommandAction.Test, DbtCommandScope.Named("nightly"), exclude = Vector("tag:ignored"),
        indirectSelection = Some("cautious"), extraArguments = Vector("--threads", "4")))
    val test = DbtTestDefinition("test.pkg.check_orders.hash", "check_orders", "Data tests", "", workspace.roots.head.directory.getPath,
      file.getVirtualFile, 0, "fqn:pkg.tests.check_orders,resource_type:test", Vector.empty)
    val rerun = DbtTestDiscovery.rerun(original, Vector(test), Vector(DbtExecutionResult(test.id, "failed", "rows"))).toOption.get
    assertEquals(Vector("wrapper", "test", "--log-format", "json", "--select", test.selector, "--indirect-selection", "empty",
      "--threads", "4", "--target", "prod", "--use-colors"), rerun.arguments)
  }
  def testNativeConsoleStreamsTestStateNavigatesDefinitionAndPreservesFailureOutput(): Unit = {
    project(); val file = add("tests/check_orders.sql", "select 1")
    val panel = new DbtTestPanel(getProject); Disposer.register(getTestRootDisposable, panel); panel.refresh()
    await("Native discovery completes") { panel.testDefinitions.nonEmpty && panel.testView != null }
    val dir = temporary(); val script = fake(dir); val runner = getProject.getService(classOf[DbtRunner])
    assertTrue(runner.start(command(dir, script, "hold")))
    await("Native test goes running before exit") { panel.testView.getResultsViewer.getTestsRootNode.getAllTests.asScala.exists(p => p.getName == "analytics.check_orders" && p.isInProgress) }
    runner.stop()
    await("Canceled command returns idle") { !runner.isRunning }
    val canceled = panel.testView.getResultsViewer.getTestsRootNode.getAllTests.asScala.find(_.getName == "analytics.check_orders").get
    assertTrue(canceled.wasTerminated())
    assertTrue(runner.start(command(dir, script, "fail")))
    await("Failed artifact updates native tree") { !runner.isRunning }
    val proxy = panel.testView.getResultsViewer.getTestsRootNode.getAllTests.asScala.find(_.getName == "analytics.check_orders").get
    assertTrue(proxy.isDefect); assertTrue(proxy.getErrorMessage.contains("unexpected rows"))
    val location = proxy.getLocation(getProject, com.intellij.psi.search.GlobalSearchScope.projectScope(getProject))
    assertNotNull(location); assertEquals(file, location.getPsiElement.getContainingFile)
    val invocation = JsonParser.parseString(Files.readString(dir.resolve("invocation.json"))).getAsJsonObject
    assertTrue(invocation.get("profiles").getAsString.endsWith("profiles"))
  }
  def testSuccessfulNoWriteProcessNeverReusesOldSuccessAsNewRun(): Unit = {
    project(); add("tests/check_orders.sql", "select 1")
    val dir = temporary(); val script = fake(dir); val runner = getProject.getService(classOf[DbtRunner])
    artifact(dir.resolve("target/run_results.json"), """[{"unique_id":"test.analytics.check_orders","status":"pass"}]""", invocation = "old-run")
    var result = Option.empty[DbtExecutionRun]
    runner.listen(getTestRootDisposable, e => if (!e.running) result = e.execution)
    assertTrue(runner.start(command(dir, script, "no-write")))
    await("No-write command finishes") { !runner.isRunning }
    assertTrue(result.exists(_.problem.contains("No new")))
    assertFalse(result.toVector.flatMap(_.results).exists(_.status == "passed"))
    assertFalse(Files.exists(DbtExecutionEvidence.marker(dir.resolve("target/run_results.json"))))
  }
  def testTerminalDiscoveryKeepsLiveConsoleAndNextRunCanPassAndDiscoverNewTests(): Unit = {
    project(); add("tests/check_orders.sql", "select 1")
    val panel = new DbtTestPanel(getProject); Disposer.register(getTestRootDisposable, panel); panel.refresh()
    await("Test discovery completes") { panel.testView != null && panel.testDefinitions.nonEmpty }
    val dir = temporary(); val script = fake(dir); val runner = getProject.getService(classOf[DbtRunner])
    def node = panel.testView.getResultsViewer.getTestsRootNode.getAllTests.asScala.find(_.getName == "analytics.check_orders").get
    def completeRefresh(): Unit = {
      com.intellij.openapi.application.impl.NonBlockingReadActionImpl.waitForAsyncTaskCompletion()
      com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
    }
    assertTrue(runner.start(command(dir, script, "fail").copy(request = DbtCommandRequest(DbtCommandAction.Test, extraArguments = Vector("fail", "hold")))))
    await("First native run starts") { node.isInProgress }
    val failingView = panel.testView
    Files.writeString(dir.resolve("release"), "done")
    await("Failure finishes") { !runner.isRunning && node.isDefect }
    completeRefresh()
    assertSame("Terminal discovery must not dispose a tree with pending native selection", failingView, panel.testView)
    assertTrue(node.getErrorMessage.contains("unexpected rows"))

    Files.delete(dir.resolve("release"))
    assertTrue(runner.start(command(dir, script, "hold")))
    await("Second native run resets the previous failure") { node.isInProgress }
    val passingView = panel.testView
    assertNotSame(failingView, passingView)
    Files.writeString(dir.resolve("release"), "done")
    await("Second run passes") { !runner.isRunning && node.isPassed }
    completeRefresh()
    assertSame(passingView, panel.testView)
    assertFalse(node.isDefect)

    add("tests/new_check.sql", "select 2")
    panel.refresh()
    await("Changed source definitions are rediscovered") { panel.testDefinitions.exists(_.name == "analytics.new_check") }
    assertNotSame("Real discovery changes still replace the console", passingView, panel.testView)
  }
  def testNativeStatesKeepWarningsSkipsErrorsAndMissingTestsDistinct(): Unit = {
    project()
    Vector("pass", "fail", "warn", "error", "skip", "cancel", "missing").foreach(n => add(s"tests/$n.sql", "select 1"))
    val panel = new DbtTestPanel(getProject); Disposer.register(getTestRootDisposable, panel); panel.refresh()
    await("Native state fixture discovers tests") { panel.testDefinitions.size == 7 && panel.testView != null }
    val context = Vector(workspace.roots.head.directory.getPath, "dev", "configuration")
    val results = Vector("pass" -> "passed", "fail" -> "failed", "warn" -> "warning", "error" -> "error", "skip" -> "skipped", "cancel" -> "canceled")
      .map { case (name, state) => DbtExecutionResult("test.analytics." + name, state, "detail: " + name) }
    panel.update(DbtExecutionRun(context, "dev", "native-state-run", Instant.now.toString, "run_results.json", results))
    val nodes = panel.testView.getResultsViewer.getTestsRootNode.getAllTests.asScala.filterNot(_.isSuite).map(p => p.getName -> p).toMap
    assertTrue(nodes("analytics.pass").isPassed)
    assertTrue(nodes("analytics.fail").isDefect)
    assertTrue(nodes("analytics.error").isDefect)
    assertNotEquals("Native error state must differ from an assertion failure", nodes("analytics.fail").getMagnitudeInfo, nodes("analytics.error").getMagnitudeInfo)
    assertTrue(nodes("analytics.warn").isIgnored); assertTrue(nodes("analytics.warn").getPresentableName.startsWith("WARN"))
    assertTrue(nodes("analytics.skip").isIgnored); assertTrue(nodes("analytics.skip").getPresentableName.startsWith("SKIP"))
    assertTrue(nodes("analytics.cancel").wasTerminated())
    assertFalse(nodes("analytics.missing").wasLaunched()); assertFalse(nodes("analytics.missing").isPassed)
  }

  def testVersionedGenericTestsKeepDistinctManifestIdsAndExactYamlRanges(): Unit = {
    project()
    add("models/orders_v1.sql", "select 1 as id")
    add("models/orders_v2.sql", "select 2 as id")
    val yaml = add("models/schema.yml", """models:
- name: orders
  latest_version: 2
  columns:
  - name: id
    data_tests: [unique]
  versions:
  - v: 1
    defined_in: orders_v1
  - v: 2
    defined_in: orders_v2
""")
    add("target/manifest.json", """{"metadata":{"dbt_schema_version":"https://schemas.getdbt.com/dbt/manifest/v12.json","project_name":"analytics","generated_at":"2099-01-01T00:00:00Z"},"nodes":{
"test.analytics.unique_orders_v1_id.hash1":{"unique_id":"test.analytics.unique_orders_v1_id.hash1","name":"unique_orders_v1_id","package_name":"analytics","resource_type":"test","original_file_path":"models/schema.yml","column_name":"id","test_metadata":{"name":"unique"},"depends_on":{"nodes":["model.analytics.orders.v1"]},"fqn":["analytics","unique_orders_v1_id"]},
"test.analytics.unique_orders_v2_id.hash2":{"unique_id":"test.analytics.unique_orders_v2_id.hash2","name":"unique_orders_v2_id","package_name":"analytics","resource_type":"test","original_file_path":"models/schema.yml","column_name":"id","test_metadata":{"name":"unique"},"depends_on":{"nodes":["model.analytics.orders.v2"]},"fqn":["analytics","unique_orders_v2_id"]}}}""")
    val discovered = DbtTestDiscovery.discover(workspace)
    val tests = discovered.filter(_.id.startsWith("test.analytics.unique_orders"))
    assertEquals("Roots/artifacts: " + workspace.roots.map(r => (r.directory.getPath, r.setting("manifest"), r.setting("manifest").flatMap(r.psiFile).map(_.getText.take(130)))) + "; tests: " + discovered, 2, tests.size)
    assertEquals(Set("model.analytics.orders.v1", "model.analytics.orders.v2"), tests.flatMap(_.dependencies).toSet)
    assertTrue(tests.forall(t => t.file == yaml.getVirtualFile && t.offset == yaml.getText.indexOf("unique")))
    val failed = Vector(DbtExecutionResult(tests.find(_.dependencies.contains("model.analytics.orders.v2")).get.id, "failed", "rows"))
    val dir = temporary(); val rerun = DbtTestDiscovery.rerun(command(dir, fake(dir)), tests, failed).toOption.get
    assertTrue(rerun.arguments.contains("fqn:analytics.unique_orders_v2_id,resource_type:test"))
    assertFalse(rerun.arguments.exists(_.contains("unique_orders_v1_id")))
  }

  def testStaleOrWrongInvocationArtifactsCannotCertifyStreamedSuccess(): Unit = {
    project(); add("tests/check_orders.sql", "select 1")
    val dir = temporary(); val cmd = command(dir, fake(dir))
    val evidence = getProject.getService(classOf[DbtExecutionEvidence])
    evidence.begin(cmd)
    evidence.stream("{\"info\":{\"invocation_id\":\"current\"},\"data\":{\"node_info\":{\"unique_id\":\"test.analytics.check_orders\",\"node_status\":\"started\"}}}\n")
    artifact(dir.resolve("target/run_results.json"), """[{"unique_id":"test.analytics.check_orders","status":"pass"}]""", invocation = "old-run")
    val outcome = evidence.finish(cmd, canceled = false).get
    assertTrue(outcome.problem.contains("invocation"))
    assertFalse(outcome.results.exists(_.status == "passed"))
    assertFalse(Files.exists(DbtExecutionEvidence.marker(dir.resolve("target/run_results.json"))))
  }

  def testStoredRowsPreviewUsesCapturedWrapperBoundaryTargetProfilesAndBoundedLimit(): Unit = {
    project(); add("tests/check_orders.sql", "select 1")
    val dir = temporary(); val script = fake(dir)
    val captured = command(dir, script).copy(prefix = Vector(script.toString, "test"), request = DbtCommandRequest(DbtCommandAction.Test, DbtCommandScope.Selected(Vector("fqn:analytics.check_orders"))),
      target = "test", root = workspace.roots.head.directory.getPath)
    assertTrue(DbtTestDiscovery.preview(captured, "", 10).isLeft)
    val relation = "\"audit\".\"failing rows\""
    val preview = DbtTestDiscovery.preview(captured, relation, 10000).toOption.get
    assertEquals(captured.environment, preview.environment)
    assertEquals(captured.root, preview.root)
    assertEquals(Vector(script.toString, "test", "show"), preview.arguments.take(3))
    assertTrue(preview.arguments.sliding(2).exists(_ == Vector("--limit", "1000")))
    assertTrue(preview.arguments.sliding(2).exists(_ == Vector("--inline", "select * from " + relation)))
    assertTrue(preview.arguments.sliding(2).exists(_ == Vector("--target", "test")))
    assertFalse(preview.arguments.contains("--select"))
    val runner = getProject.getService(classOf[DbtRunner])
    var returned = Option.empty[DbtQueryResult]
    runner.listen(getTestRootDisposable, event => returned = event.result.orElse(returned))
    assertTrue(runner.start(preview))
    await("Explicit stored rows preview finishes") { !runner.isRunning }
    assertTrue("Stored rows use the normal query result lifecycle", returned.nonEmpty)
    val invoked = JsonParser.parseString(Files.readString(dir.resolve("invocation.json"))).getAsJsonObject
    assertEquals(preview.arguments.tail, invoked.getAsJsonArray("args").asScala.map(_.getAsString).toVector)
    assertEquals(captured.environment("DBT_PROFILES_DIR"), invoked.get("profiles").getAsString)
  }

  def testNativeRunButtonRefreshesTargetAndKeepsOwningProjectWithExecutionDirectoryOverride(): Unit = {
    project(); add("tests/check_orders.sql", "select 1")
    val dir = temporary(); val script = fake(dir)
    Files.writeString(dir.resolve("dbt_project.yml"), "name: analytics\nconfig-version: 2\n")
    val root = workspace.roots.head
    root.runOptions.executable = script.toString
    root.runOptions.projectRoot = dir.toString
    root.runOptions.target = "dev"
    root.runOptions.notifications = false
    val panel = new DbtTestPanel(getProject); Disposer.register(getTestRootDisposable, panel); panel.refresh()
    await("Configured native tests become available") { panel.testView != null && panel.testDefinitions.nonEmpty }
    def buttons(component: java.awt.Component): Vector[javax.swing.JButton] = component match {
      case b: javax.swing.JButton => Vector(b)
      case c: java.awt.Container => c.getComponents.toVector.flatMap(buttons)
      case _ => Vector.empty
    }
    val run = buttons(panel).find(_.getText == "Run tests").get
    val runner = getProject.getService(classOf[DbtRunner])
    var launched = Vector.empty[DbtCommand]
    runner.listen(getTestRootDisposable, event => if (event.running) event.command.foreach(command => launched :+= command))
    run.doClick()
    await("Run button completes in overridden directory") { launched.nonEmpty && !runner.isRunning }
    assertEquals(root.directory.getPath, launched.head.root)
    assertEquals(dir.toString, launched.head.directory)
    await("Owning source tests receive results") { panel.testView.getResultsViewer.getTestsRootNode.getAllTests.asScala.exists(p => p.getName == "analytics.check_orders" && p.isPassed) }
    val before = panel.testView
    root.runOptions.target = "prod"
    DbtProjectSettings.get(getProject).changed()
    await("Changing target rebuilds the native command snapshot") { panel.testView ne before }
    run.doClick()
    await("New target command completes") { launched.size == 2 && !runner.isRunning }
    assertEquals("prod", launched.last.target)
    assertTrue(launched.last.arguments.sliding(2).exists(_ == Vector("--target", "prod")))
  }

  def testOpeningTestsDuringExecutionRestoresProgressAndStop(): Unit = {
    project(); add("tests/check_orders.sql", "select 1")
    val dir = temporary(); val script = fake(dir)
    Files.writeString(dir.resolve("dbt_project.yml"), "name: analytics\nconfig-version: 2\n")
    val root = workspace.roots.head
    root.runOptions.projectRoot = dir.toString
    root.runOptions.executable = script.toString
    root.runOptions.target = "dev"; root.runOptions.notifications = false
    val base = DbtCommand.create(workspace, DbtRunSelection.all(workspace, root.directory.getPath).get, "Test", fullRefresh = false).toOption.get
    val runner = getProject.getService(classOf[DbtRunner])
    assertTrue(runner.start(base.copy(request = base.request.copy(extraArguments = Vector("hold")))))
    try {
      val panel = new DbtTestPanel(getProject); Disposer.register(getTestRootDisposable, panel); panel.refresh()
      await("Opening the tab restores active execution evidence") {
        panel.testView != null && panel.testView.getResultsViewer.getStatusLine.isVisible
      }
      def buttons(c: java.awt.Component): Vector[javax.swing.JButton] = c match {
        case b: javax.swing.JButton => Vector(b)
        case p: java.awt.Container => p.getComponents.toVector.flatMap(buttons)
        case _ => Vector.empty
      }
      val stop = buttons(panel).find(_.getText == "Stop").get
      assertTrue(stop.isEnabled)
      stop.doClick()
      await("Stop cancels the existing managed command")(!runner.isRunning)
    } finally runner.stop()
  }

}
