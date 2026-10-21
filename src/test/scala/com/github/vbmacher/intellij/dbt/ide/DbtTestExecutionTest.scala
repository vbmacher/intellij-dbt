package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project._
import com.google.gson.JsonParser
import org.junit.Assert._

import java.nio.file.{Files, Path}
import java.time.Instant
import scala.jdk.CollectionConverters._

/**
 * Contract: features/test-execution.md and execution-evidence.md: test discovery, exact reruns, context and result provenance.
 * Real managed-process execution and Community native test-tree fixtures. No warehouse or dbt installation is used.
 */
final class DbtTestExecutionTest extends DbtTestCase {
  private def temporary(): Path = tempDirectory("dbt execution evidence ")
  private def artifact(path: Path, results: String, invocation: String = "run-1", target: String = "dev", kind: String = "run-results/v6"): Unit = {
    Files.createDirectories(path.getParent)
    Files.writeString(path, s"""{"metadata":{"dbt_schema_version":"https://schemas.getdbt.com/dbt/$kind.json","invocation_id":"$invocation","generated_at":"${Instant.now}"},"args":{"target":"$target"},"results":$results}""")
  }
  private def command(directory: Path, script: Path, mode: String = "run"): DbtCommand =
    DbtCommand(Vector(script.toString), DbtCommandRequest(DbtCommandAction.Test, extraArguments = Vector(mode)), directory.toString,
      Map("DBT_TARGET_PATH" -> directory.resolve("target").toString, "DBT_PROFILES_DIR" -> directory.resolve("profiles").toString),
      "dev", notifySystem = false, directory.resolve("target").toString,
      Vector(workspace.roots.head.directory.getPath, "dev", "configuration"))
  private def fake(directory: Path): Path = {
    fakeTool(directory.resolve("fake dbt"), """#!/usr/bin/python3
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

  def testMixedMalformedResultRecordsAreExplicitlyPartialWithoutDroppingValidEvidence(): Unit = {
    val directory = tempDirectory("dbt partial evidence ")
    val path = directory.resolve("run_results.json")
    def write(results: String): Unit = Files.writeString(path,
      s"""{"metadata":{"dbt_schema_version":"https://schemas.getdbt.com/dbt/run-results/v6.json","invocation_id":"run-1","generated_at":"2026-10-18T10:00:00Z"},"args":{"target":"dev"},"results":$results}""")
    write("""[{"unique_id":"model.analytics.orders","status":"success"},null,7]""")
    val partial = DbtExecutionEvidence.parse(path, Vector("root"), "dev")
    assertTrue(partial.problem, partial.problem.contains("Partial results"))
    assertEquals(Vector("model.analytics.orders"), partial.results.map(_.id))
    assertEquals("passed", partial.results.head.status)
    write("null")
    assertTrue(DbtExecutionEvidence.parse(path, Vector("root"), "dev").problem.contains("Partial results"))
    write("[]")
    assertEquals("A genuinely empty result array remains distinguishable from malformed records", "",
      DbtExecutionEvidence.parse(path, Vector("root"), "dev").problem)
  }

  def testTestOnlyFailureBadgeRetainsExactProvenanceWithoutClaimingModelBuildSuccess(): Unit = {
    project()
    val file = add("models/orders.sql", "select 1 as id")
    val w = workspace
    val model = w.codeSymbols(file).find(_.kind == "model").get
    val definition = DbtTestDefinition("test.analytics.unique_orders.hash", "unique orders", "Data tests", model.uniqueId,
      w.owner(model).get.directory.getPath, file.getVirtualFile, 0, "fqn:analytics.unique_orders", Vector(model.uniqueId))
    val execution = DbtExecutionRun(Vector("root", "prod"), "prod", "exact-test-invocation", "2026-10-18T10:00:00Z",
      "/project/target/run_results.json", Vector(DbtExecutionResult(definition.id, "failed", "3 duplicate ids", 3)))
    val overlay = getProject.getService(classOf[DbtExecutionEvidence]).overlay(model, Vector(execution), Vector(definition))
    assertEquals("unknown", overlay.get("status").getAsString)
    assertEquals("Not run / result unavailable", overlay.get("label").getAsString)
    assertEquals(1, overlay.get("failingTests").getAsInt)
    val details = overlay.get("details").getAsString
    Vector("prod", "exact-test-invocation", execution.timestamp, execution.artifact, definition.id, "3 duplicate ids").foreach(value =>
      assertTrue(value + " should be present in badge details: " + details, details.contains(value)))
    val anotherVersion = model.copy(version = Some("2"))
    assertEquals(0, getProject.getService(classOf[DbtExecutionEvidence]).overlay(anotherVersion, Vector(execution), Vector(definition))
      .get("failingTests").getAsInt)
    val freshPass = execution.copy(invocation = "new-run", results = Vector(DbtExecutionResult(definition.id, "passed", "")))
    assertEquals(0, getProject.getService(classOf[DbtExecutionEvidence]).overlay(model, Vector(freshPass, execution), Vector(definition))
      .get("failingTests").getAsInt)
  }


  def testSourceOnlyTestFailureFlowsThroughManagedExecutionIntoTheExactModelGraphBadge(): Unit = {
    project()
    val modelFile = add("models/stg_orders.sql", "select 1 as id")
    val otherFile = add("models/other_model.sql", "select 2 as id")
    add("tests/check_orders.sql", "select * from {{ ref('stg_orders') }} where id is null")
    add("independent/dbt_project.yml", "name: analytics\nconfig-version: 2\n")
    add("independent/models/stg_orders.sql", "select 3 as id")
    add("independent/models/other_model.sql", "select 4 as id")
    add("independent/tests/check_orders.sql", "select * from {{ ref('other_model') }} where id is null")
    val w = workspace
    val root = w.root(modelFile).get
    val model = w.codeSymbols(modelFile).find(_.kind == "model").get
    val definitions = DbtTestDiscovery.discover(w)
    val test = definitions.find(t => t.root == root.directory.getPath && t.id == "test.analytics.check_orders").get
    assertEquals(Vector(model.uniqueId), test.dependencies)
    assertEquals("Source declaration", test.provenance)
    assertTrue("No manifest containing a test identity is needed", root.setting("manifest").flatMap(root.psiFile).isEmpty)

    val directory = tempDirectory("dbt source test badge ")
    Files.writeString(directory.resolve("dbt_project.yml"), "name: analytics\nconfig-version: 2\n")
    val executable = fakeTool(directory.resolve("fake dbt"), """#!/usr/bin/python3
import datetime,json,os,sys
os.makedirs(os.environ['DBT_TARGET_PATH'],exist_ok=True)
with open(os.path.join(os.environ['DBT_TARGET_PATH'],'run_results.json'),'w') as output:
 json.dump({'metadata':{'dbt_schema_version':'https://schemas.getdbt.com/dbt/run-results/v6.json','invocation_id':'source-only-test-run','generated_at':datetime.datetime.now(datetime.timezone.utc).isoformat()},'args':{'target':'dev'},'results':[{'unique_id':'test.analytics.check_orders','status':'fail','failures':3,'message':'3 invalid orders'}]},output)
sys.exit(1)
""")
    root.runOptions.executable = executable.toString
    root.runOptions.projectRoot = directory.toString
    root.runOptions.target = "dev"
    root.runOptions.notifications = false
    val command = DbtCommand.create(w, DbtRunSelection.all(w, root.directory.getPath).get, "Test", fullRefresh = false).toOption.get
    val runner = newRunner(service = true)
    var completed = Option.empty[DbtExecutionRun]
    runner.listen(getTestRootDisposable, event => if (!event.running) completed = event.execution)
    assertTrue(runner.start(command))
    await("A source-only test writes a managed failure result")(completed.nonEmpty && !runner.isRunning)
    assertEquals(Vector("test.analytics.check_orders"), completed.get.results.map(_.id))
    assertEquals("failed", completed.get.results.head.status)
    assertEquals("", completed.get.problem)
    val graph = DbtGraphData.build(workspace, None, 5, 5)
    val nodes = graph.payload.getAsJsonArray("nodes").asScala.map(_.getAsJsonObject).toVector
    def execution(id: String) = nodes.find(_.get("id").getAsString == id).get.getAsJsonObject("execution")
    val badge = execution(DbtResourceIdentity.key(w, model))
    assertEquals(1, badge.get("failingTests").getAsInt)
    assertEquals("unknown", badge.get("status").getAsString)
    assertTrue(badge.get("details").getAsString.contains("source-only-test-run"))
    val other = w.codeSymbols(otherFile).find(_.kind == "model").get
    assertEquals("A same-name test in an independent project must not attach this run to another model", 0,
      execution(DbtResourceIdentity.key(w, other)).get("failingTests").getAsInt)
    assertTrue(nodes.filter(_.get("id").getAsString.contains("/independent|")).forall(n => n.getAsJsonObject("execution").get("failingTests").getAsInt == 0))
  }

  def testSourceDependenciesRetainExplicitPackageVersionAndSourceIdentityWithoutGuessingDynamicRefs(): Unit = {
    project()
    add("models/orders_v1.sql", "select 1 as id")
    add("models/orders_v2.sql", "select 2 as id")
    add("models/schema.yml", "models: [{name: orders, latest_version: 2, versions: [{v: 1, defined_in: orders_v1}, {v: 2, defined_in: orders_v2}]}]\nsources: [{name: raw, tables: [{name: orders}]}]\n")
    add("dbt_packages/util/dbt_project.yml", "name: util\nconfig-version: 2\n")
    add("dbt_packages/util/models/orders.sql", "select 3 as id")
    add("tests/check_exact.sql", "select * from {{ ref('orders', v=1) }} union all select * from {{ ref('util', 'orders') }} union all select * from {{ source('raw', 'orders') }}")
    add("tests/check_dynamic.sql", "select * from {{ ref(var('runtime_model')) }}")
    val definitions = DbtTestDiscovery.discover(workspace)
    assertEquals(Set("model.analytics.orders.v1", "model.util.orders", "source.analytics.raw.orders"),
      definitions.find(_.id == "test.analytics.check_exact").get.dependencies.toSet)
    assertTrue(definitions.find(_.id == "test.analytics.check_dynamic").get.dependencies.isEmpty)
  }
}
