package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project._
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import java.nio.file.Files
import org.junit.Assert._
import scala.jdk.CollectionConverters._

final class DbtSourceTestLineageTest extends DbtCodeInsightTestCase {
  override protected def tearDown(): Unit = try DbtProjectSettings.get(getProject).loadState(new DbtSettingsState) finally super.tearDown()

  def testSourceOnlyTestFailureFlowsThroughManagedExecutionIntoTheExactModelGraphBadge(): Unit = {
    project()
    val modelFile = add("models/stg_orders.sql", "select 1 as id")
    val otherFile = add("models/other_model.sql", "select 2 as id")
    add("tests/check_orders.sql", "select * from {{ ref('stg_orders') }} where id is null")
    add("independent/dbt_project.yml", "name: analytics\nconfig-version: 2\n")
    add("independent/models/stg_orders.sql", "select 3 as id")
    add("independent/models/other_model.sql", "select 4 as id")
    add("independent/tests/check_orders.sql", "select * from {{ ref('other_model') }} where id is null")
    val w = DbtWorkspace.get(getProject)
    val root = w.root(modelFile).get
    val model = w.codeSymbols(modelFile).find(_.kind == "model").get
    val definitions = DbtTestDiscovery.discover(w)
    val test = definitions.find(t => t.root == root.directory.getPath && t.id == "test.analytics.check_orders").get
    assertEquals(Vector(model.uniqueId), test.dependencies)
    assertEquals("Source declaration", test.provenance)
    assertTrue("No manifest containing a test identity is needed", root.setting("manifest").flatMap(root.psiFile).isEmpty)

    val directory = Files.createTempDirectory("dbt source test badge ")
    Disposer.register(getTestRootDisposable, () => com.intellij.openapi.util.io.FileUtil.delete(directory.toFile))
    Files.writeString(directory.resolve("dbt_project.yml"), "name: analytics\nconfig-version: 2\n")
    val executable = directory.resolve("fake dbt")
    Files.writeString(executable, """#!/usr/bin/python3
import datetime,json,os,sys
os.makedirs(os.environ['DBT_TARGET_PATH'],exist_ok=True)
with open(os.path.join(os.environ['DBT_TARGET_PATH'],'run_results.json'),'w') as output:
 json.dump({'metadata':{'dbt_schema_version':'https://schemas.getdbt.com/dbt/run-results/v6.json','invocation_id':'source-only-test-run','generated_at':datetime.datetime.now(datetime.timezone.utc).isoformat()},'args':{'target':'dev'},'results':[{'unique_id':'test.analytics.check_orders','status':'fail','failures':3,'message':'3 invalid orders'}]},output)
sys.exit(1)
""")
    assertTrue(executable.toFile.setExecutable(true))
    root.runOptions.executable = executable.toString
    root.runOptions.projectRoot = directory.toString
    root.runOptions.target = "dev"
    root.runOptions.notifications = false
    val command = DbtCommand.create(w, DbtRunSelection.all(w, root.directory.getPath).get, "Test", fullRefresh = false).toOption.get
    val runner = new DbtRunner(getProject)
    Disposer.register(getTestRootDisposable, runner)
    com.intellij.testFramework.ServiceContainerUtil.replaceService(getProject, classOf[DbtRunner], runner, getTestRootDisposable)
    var completed = Option.empty[DbtExecutionRun]
    runner.listen(getTestRootDisposable, event => if (!event.running) completed = event.execution)
    assertTrue(runner.start(command))
    PlatformTestUtil.waitWithEventsDispatching("A source-only test writes a managed failure result", () => completed.nonEmpty && !runner.isRunning, 15)
    assertEquals(Vector("test.analytics.check_orders"), completed.get.results.map(_.id))
    assertEquals("failed", completed.get.results.head.status)
    assertEquals("", completed.get.problem)
    val graph = DbtGraphData.build(DbtWorkspace.get(getProject), None, 5, 5)
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
    val definitions = DbtTestDiscovery.discover(DbtWorkspace.get(getProject))
    assertEquals(Set("model.analytics.orders.v1", "model.util.orders", "source.analytics.raw.orders"),
      definitions.find(_.id == "test.analytics.check_exact").get.dependencies.toSet)
    assertTrue(definitions.find(_.id == "test.analytics.check_dynamic").get.dependencies.isEmpty)
  }
}
