package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project._
import com.intellij.openapi.vfs.{LocalFileSystem, VirtualFile}
import com.intellij.psi.PsiFile
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.{IdeaTestFixtureFactory, TempDirTestFixture}
import org.junit.Assert._

import java.nio.file.Files
import scala.jdk.CollectionConverters._

/** Airflow DAG integration: static DAG parsing and task details for dbt resources in real project directories. */
final class DbtAirflowTest extends DbtTestCase {
  private var contentRoot: VirtualFile = _

  override protected def createTempDirTestFixture(): TempDirTestFixture = IdeaTestFixtureFactory.getFixtureFactory.createTempDirTestFixture()

  override protected def project(config: String, path: String): PsiFile = {
    val file = super.project(config, path)
    contentRoot = file.getVirtualFile.getParent
    PsiTestUtil.addContentRoot(getModule, contentRoot)
    file
  }

  override protected def tearDown(): Unit = try if (contentRoot != null) PsiTestUtil.removeContentEntry(getModule, contentRoot)
  finally super.tearDown()

  private def resource(w: DbtWorkspace, suffix: String) = w.symbols.find(s => s.isCode && s.file.getVirtualFile.getPath.endsWith(suffix)).get

  private def settings = getProject.getService(classOf[DbtConsumersSettings]).getState

  private def enable(path: String): Unit = {
    settings.airflowPaths = new java.util.ArrayList[String](Vector(path).asJava)
    DbtProjectSettings.get(getProject).changed()
  }

  def testRelatedSymbolUsesEditorContextWithoutAResolvedPsiElement(): Unit = {
    val config = project()
    val model = configure("models/orders.sql", "select 1<caret>")
    add("dags/daily.py", "Cfg(dag_id='finance', task_id='build', dbt_selector='orders')")
    enable(config.getVirtualFile.getParent.findChild("dags").getPath)
    settings.airflowUrl = "https://airflow.test/"
    val context = com.intellij.openapi.actionSystem.impl.SimpleDataContext.builder()
      .add(com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT, getProject)
      .add(com.intellij.openapi.actionSystem.CommonDataKeys.PSI_FILE, model)
      .add(com.intellij.openapi.actionSystem.CommonDataKeys.EDITOR, myFixture.getEditor).build()
    val items = new DbtAirflowRelatedProvider().getItems(context).asScala
    assertEquals(Set("finance / build", "Open finance / build"), items.map(_.getCustomName).toSet)
    assertTrue(items.exists(i => Option(i.getElement).exists(_.getContainingFile.getName == "daily.py")))
  }

  def testAutomaticSelectionsKeepVersionsPackagesAndIndependentRootsSeparate(): Unit = {
    val config = project()
    add("models/orders_v1.sql", "select 1 as id")
    add("models/orders_v2.sql", "select 2 as id")
    add("models/schema.yml", "models: [{name: orders, versions: [{v: 1}, {v: 2}]}]\n")
    add("dbt_packages/pkg/dbt_project.yml", "name: pkg\nconfig-version: 2\n")
    add("dbt_packages/pkg/models/orders.sql", "select 3 as id")
    add("other/dbt_project.yml", "name: analytics\nconfig-version: 2\n")
    add("other/models/orders.sql", "select 4 as id")
    add("dags/finance.py",
      """Cfg(name="finance", dbt_jobs=[
        {"name":"version_two", "selector":"fqn:analytics.orders.v2"},
        {"name":"package_only", "selector":"package:pkg"},
        {"name":"runtime", "selector":f"tag:{team}"}])""")
    add("other/dags/independent.py", "Cfg(name='independent', dbt_selector='orders')")
    enable(config.getVirtualFile.getParent.getPath)
    val w = workspace
    val v2 = resource(w, "/models/orders_v2.sql");
    val v1 = resource(w, "/models/orders_v1.sql")
    val pkg = resource(w, "/dbt_packages/pkg/models/orders.sql");
    val other = resource(w, "/other/models/orders.sql")
    assertEquals(Set("version_two", "runtime"), DbtAirflow.context(w, v2).map(_.task).toSet)
    assertEquals(Set("runtime"), DbtAirflow.context(w, v1).map(_.task).toSet)
    assertEquals(Set("package_only", "runtime"), DbtAirflow.context(w, pkg).map(_.task).toSet)
    assertEquals(Set("independent"), DbtAirflow.context(w, other).map(_.task).toSet)
    val versionTask = DbtAirflow.context(w, v2).find(_.task == "version_two").get
    assertTrue(DbtAirflow.navigation(w, v1, versionTask.id, "definition").isLeft)
    assertTrue(DbtAirflow.navigation(w, other, versionTask.id, "definition").isLeft)
    val details = DbtGraphData.documentation(w, v2).getAsJsonObject("airflow")
    assertEquals(1, details.get("selectedCount").getAsInt)
    assertEquals(1, details.get("unresolvedCount").getAsInt)
  }

  def testTaskLinksOpenPythonLocationsAndRevalidateAfterUnsavedEdits(): Unit = {
    val config = project();
    add("models/orders.sql", "select 1 as id")
    val dag = add("dags/finance.py",
      """# scheduler configuration
Cfg(dag_id="finance /daily", task_id="build & report", dbt_selector="orders")
""")
    enable(config.getVirtualFile.getParent.findChild("dags").getPath)
    settings.airflowUrl = "https://airflow.test/base/"
    val w = workspace;
    val model = resource(w, "/models/orders.sql");
    val task = DbtAirflow.context(w, model).head
    val definition = DbtAirflow.navigation(w, model, task.id, "definition").toOption.get
    assertEquals(dag.getVirtualFile.getPath, definition.path)
    assertEquals(dag.getText.indexOf("dbt_selector"), definition.offset)
    assertEquals(Some("https://airflow.test/base/dags/finance%20%2Fdaily/grid?task_id=build%20%26%20report"),
      DbtAirflow.navigation(w, model, task.id, "browser").toOption.get.url)
    assertEquals(2, DbtAirflow.details(w, model).getAsJsonArray("tasks").get(0).getAsJsonObject.get("line").getAsInt)
    assertTrue(DbtAirflow.navigation(w, model, "unrelated", "definition").isLeft)
    edit(dag, "Cfg(dag_id='changed', task_id='build', dbt_selector='orders')")
    val fresh = workspace;
    val freshModel = resource(fresh, "/models/orders.sql")
    assertTrue(DbtAirflow.navigation(fresh, freshModel, task.id, "definition").isLeft)
    assertEquals("changed", DbtAirflow.context(fresh, freshModel).head.dag)
  }

  def testExternalCheckoutRecursionSkipDirectoriesAndDiskRefresh(): Unit = {
    project();
    add("models/orders.sql", "select 1")
    val directory = tempDirectory("dbt-airflow-external-")
    val nested = Files.createDirectories(directory.resolve("nested"))
    val dag = nested.resolve("daily.py")
    Files.writeString(dag, "Cfg(name='daily', dbt_selector='orders')")
    Files.writeString(directory.resolve("unrelated.py"), "raise RuntimeError('never execute DAG code')")
    Files.writeString(Files.createDirectories(directory.resolve(".venv")).resolve("hidden.py"), "Cfg(dbt_selector='orders')")
    enable(directory.toString)
    assertEquals(Vector("daily"), DbtAirflow.tasks(workspace).map(_.dag))
    val virtual = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(directory)
    virtual.refresh(false, true)
    Files.writeString(dag, "Cfg(name='changed', dbt_selector='orders')")
    virtual.refresh(false, true)
    assertEquals(Vector("changed"), DbtAirflow.tasks(workspace).map(_.dag))
    Files.delete(dag);
    virtual.refresh(false, true)
    assertTrue(DbtAirflow.tasks(workspace).isEmpty)
    Files.writeString(nested.resolve("new.py"), "Cfg(name='new', dbt_selector='orders')")
    virtual.refresh(false, true)
    assertEquals(Vector("new"), DbtAirflow.tasks(workspace).map(_.dag))
  }

  def testAutomaticProjectDetectionKeepsAmbiguousExternalDagsUnresolvedAndIgnoresManualYaml(): Unit = {
    val config = project();
    add("models/orders.sql", "select 1")
    add("other/dbt_project.yml", "name: other\nconfig-version: 2\n");
    add("other/models/orders.sql", "select 2")
    val dag = add("dags/daily.py", "Cfg(name='daily', dbt_selector='orders')")
    enable(dag.getVirtualFile.getParent.getPath)
    assertEquals(Vector("model.analytics.orders"), DbtAirflow.tasks(workspace).head.resources.map(_.uniqueId))
    add(".dbt-airflow.yml", "tasks: [{dag: ignored, task: ignored, project: analytics, select: '*'}]\n")
    assertEquals(Vector("daily"), DbtAirflow.tasks(workspace).map(_.dag))
    val external = tempDirectory("dbt-airflow-ambiguous-")
    Files.writeString(external.resolve("ambiguous.py"), "Cfg(name='ambiguous', dbt_selector='orders')")
    enable(external.toString)
    val task = DbtAirflow.tasks(workspace).head
    assertTrue(task.problems.exists(_.contains("ambiguous")))
    assertTrue(task.resources.isEmpty)
  }

  def testIngestionMatchesPhysicalDestinationsAndFindsUpstreamPipelines(): Unit = {
    val config = project()
    add("models/sources.yml", "sources: [{name: raw, schema: landing, tables: [{name: orders, identifier: physical_orders}]}]\n")
    add("models/orders.sql", "select * from {{ source('raw', 'orders') }}")
    add("dags/load.py", "X={'bq_dataset':'landing','bq_table':'physical_orders'}\n@dag(dag_id='load',schedule='@hourly')\ndef f(): pass")
    add("dags/build.py", "Cfg(name='build', dbt_selector='orders')")
    enable(config.getVirtualFile.getParent.findChild("dags").getPath)
    val tasks = DbtAirflow.tasks(workspace)
    val load = tasks.find(_.kind == "ingestion").get
    assertEquals(Vector("source.analytics.raw.orders"), load.resources.map(_.uniqueId))
    assertTrue(load.ingestion.head.contains("exact"))
    assertEquals("@hourly", load.schedule)
    assertEquals(Vector("load"), tasks.find(_.dag == "build").get.ingestion)
  }

  def testConfiguredManifestSuppliesNamedSelectorsAndIngestionRelations(): Unit = {
    val config = project()
    add("models/orders.sql", "select 1")
    add("artifacts/production-manifest.json",
      """{
      "metadata":{"dbt_schema_version":"https://schemas.getdbt.com/dbt/manifest/v12.json"},
      "nodes":{"model.analytics.orders":{"unique_id":"model.analytics.orders","resource_type":"model","name":"orders",
        "package_name":"analytics","original_file_path":"models/orders.sql","schema":"mart","alias":"physical_orders"}},
      "selectors":{"nightly":{"definition":{"union":[{"method":"fqn","value":"orders"}]}}}
    }""")
    add("dags/finance.py", "Cfg(name='finance', dbt_selector_name='nightly')")
    add("dags/ingest.py", "X={'target_dataset':'mart','target_table':'physical_orders'}")
    DbtProjectSettings.get(getProject).options(config.getVirtualFile.getParent.getPath).manifestPath = "artifacts/production-manifest.json"
    enable(config.getVirtualFile.getParent.findChild("dags").getPath)
    val tasks = DbtAirflow.tasks(workspace)
    assertEquals(Vector("model.analytics.orders"), tasks.find(_.dag == "finance").get.resources.map(_.uniqueId))
    assertEquals(Vector("model.analytics.orders"), tasks.find(_.kind == "ingestion").get.resources.map(_.uniqueId))
    assertTrue(tasks.forall(_.problems.isEmpty))
    val selectors = add("selectors.yml", "selectors: [{name: nightly, definition: 'tag:absent'}]\n")
    assertTrue(DbtAirflow.tasks(workspace).find(_.dag == "finance").get.resources.isEmpty)
    edit(selectors, "selectors: []\n")
    assertTrue(DbtAirflow.tasks(workspace).find(_.dag == "finance").get.problems.exists(_.contains("missing")))
  }

  def testExternalDbtContentRootIsDetectedAutomatically(): Unit = {
    project();
    add("models/local.sql", "select 1")
    val directory = tempDirectory("dbt-airflow-project-")
    var externalRoot: VirtualFile = null
    try {
      Files.writeString(directory.resolve("dbt_project.yml"), "name: external\nconfig-version: 2\n")
      Files.writeString(Files.createDirectories(directory.resolve("models")).resolve("orders.sql"), "select 2")
      Files.writeString(directory.resolve("remote.py"), "Cfg(name='remote_build', dbt_selector='orders')")
      externalRoot = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(directory)
      externalRoot.refresh(false, true)
      PsiTestUtil.addContentRoot(getModule, externalRoot)
      enable(directory.toString)
      val tasks = DbtAirflow.tasks(workspace)
      assertEquals(Vector("model.external.orders"), tasks.head.resources.map(_.uniqueId))
      assertEquals(Some(directory.toString), tasks.head.projectRoot)
    } finally if (externalRoot != null) PsiTestUtil.removeContentEntry(getModule, externalRoot)
  }

  def testIngestionConfidenceAndUniqueTableFallbackRemainExplicit(): Unit = {
    val config = project()
    add("models/sources.yml",
      """sources:
- name: raw
  schema: landing
  tables: [{name: orders, identifier: PhysicalOrders}, {name: unique, identifier: unique_table}]
- name: other
  schema: another
  tables: [{name: orders, identifier: PhysicalOrders}]
""")
    add("dags/exact.py", "X={'bq_dataset':'landing','bq_table':'PhysicalOrders'}")
    add("dags/folded.py", "X={'bq_dataset':'LANDING','bq_table':'PHYSICALORDERS'}")
    add("dags/unique.py", "X={'target_table':'unique_table'}")
    add("dags/ambiguous.py", "X={'destination_table':'PhysicalOrders'}")
    enable(config.getVirtualFile.getParent.findChild("dags").getPath)
    val tasks = DbtAirflow.tasks(workspace)
    val exact = tasks.find(_.dag == "exact").get
    assertEquals(Vector("source.analytics.raw.orders"), exact.resources.map(_.uniqueId))
    assertTrue(exact.ingestion.head.contains("exact"))
    val folded = tasks.find(_.dag == "folded").get
    assertEquals(exact.resources.map(_.uniqueId), folded.resources.map(_.uniqueId))
    assertTrue(folded.ingestion.head.contains("case-insensitive"))
    val unique = tasks.find(_.dag == "unique").get
    assertEquals(Vector("source.analytics.raw.unique"), unique.resources.map(_.uniqueId))
    assertTrue(unique.ingestion.head.contains("table-only"))
    assertTrue(tasks.find(_.dag == "ambiguous").get.resources.isEmpty)
  }

  def testReferenceSelectorMethodsAndAtExpansionResolveAutomatically(): Unit = {
    val config = project()
    add("models/staging/orders.sql", "{{ config(materialized='incremental', tags=['finance'], access='public') }} select 1")
    add("models/report.sql", "select * from {{ ref('orders') }}")
    add("models/extra.sql", "select 2")
    add("dags/methods.py",
      """Cfg(name="methods", dbt_jobs=[
      {"name":"config", "select":"config.materialized:incremental"},
      {"name":"tags", "select":"config.tags:finance"},
      {"name":"file", "select":"file:orders"},
      {"name":"access", "select":"access:public"},
      {"name":"folder", "select":"staging"},
      {"name":"graph", "select":"@orders"},
      {"name":"types", "select":"type:model", "exclude":"extra"}])""")
    enable(config.getVirtualFile.getParent.findChild("dags").getPath)
    val tasks = DbtAirflow.tasks(workspace)
    Vector("config", "tags", "file", "access", "folder").foreach(name =>
      assertEquals(name, Vector("orders"), tasks.find(_.task == name).get.resources.map(_.name)))
    assertEquals(Set("orders", "report"), tasks.find(_.task == "graph").get.resources.map(_.name).toSet)
    assertEquals(Set("orders", "report"), tasks.find(_.task == "types").get.resources.map(_.name).toSet)
    assertTrue(tasks.forall(_.problems.isEmpty))
  }

  def testDetailsBoundLargeCollectionsWithoutLosingCountsAndReportMissingFolders(): Unit = {
    val config = project();
    add("models/orders.sql", "select 1")
    add("dags/many.py", (1 to 52).map(i => s"Cfg(name='build_$i', dbt_selector='orders')\n").mkString)
    enable(config.getVirtualFile.getParent.findChild("dags").getPath)
    val w = workspace;
    val details = DbtAirflow.details(w, resource(w, "/models/orders.sql"))
    assertEquals(50, details.getAsJsonArray("tasks").size());
    assertEquals(52, details.get("selectedCount").getAsInt)
    assertTrue(details.get("truncated").getAsBoolean)
    enable(config.getVirtualFile.getParent.getPath + "/missing")
    assertTrue(DbtAirflow.problems(workspace).exists(_.contains("missing")))
  }


  private def parse(source: String) = DbtAirflowParser.parse("/dags/example.py", source)

  def testReadsConfigObjectsAndIgnoresFunctionDefaultsAndNone(): Unit = {
    val pipelines = parse(
      """
def helper(dbt_selector=None, dbt_exclude=None): pass
def get():
    return [
        DbtTransformationConfig(name="dbt_gtm", schedule="57 9 * * *",
            dbt_selector="tag:gtm_1000WIB", dbt_exclude="tag:slow tag:flaky",
            dbt_indirect_selection=DbtIndirectSelection.CAUTIOUS),
        DbtTransformationConfig(name="noop", dbt_selector=None),
    ]
""")
    assertEquals(1, pipelines.size)
    val p = pipelines.head
    assertEquals("dbt_gtm", p.name);
    assertEquals("57 9 * * *", p.schedule)
    assertEquals("tag:gtm_1000WIB", p.sites.head.select)
    assertEquals("tag:slow tag:flaky", p.sites.head.exclude)
    assertEquals("DbtIndirectSelection.CAUTIOUS", p.sites.head.indirect)
  }

  def testEvaluatesConstantsConcatenationListsJoinsAndAdjacentStrings(): Unit = {
    val p = parse(
      """
BASE = ["tag:a", "tag:b"]
NAME = "night" + "ly"
X = Cfg(name=NAME, dbt_selector=" ".join(BASE + ["m1+"]), dbt_exclude=",".join(["tag:x", "tag:y"]))
Y = Cfg(name="other", dbt_selector=("tag:" "finance"))
""")
    assertEquals("nightly", p.head.name)
    assertEquals("tag:a tag:b m1+", p.head.sites.head.select)
    assertEquals("tag:x,tag:y", p.head.sites.head.exclude)
    assertEquals("tag:finance", p(1).sites.head.select)
  }

  def testNestedJobsInheritExcludesScheduleAndPipelineName(): Unit = {
    val p = parse(
      """
dag_definitions = [
  {"dag_name_suffix": "hourly", "schedule": "0 * * * *", "excludes": ["tag:skip"],
   "dbt_jobs": [{"selector": "tag:hourly"}, {"name": "j2", "selector": "1+m2"}]},
]
""").head
    assertEquals("hourly", p.name);
    assertEquals("0 * * * *", p.schedule)
    assertEquals(Vector("tag:hourly", "1+m2"), p.sites.map(_.select))
    assertEquals(Vector("tag:skip", "tag:skip"), p.sites.map(_.exclude))
    assertEquals("j2", p.sites(1).task)
  }

  def testShellFlagsNamedSelectorsAndSourceLocations(): Unit = {
    val source =
      """with DAG(dag_id="daily", schedule_interval="@daily"):
    task = BashOperator(task_id="build", bash_command="dbt build --select tag:a +m1 --exclude m2 --target prod")
"""
    val p = parse(source).head
    assertEquals("daily", p.name);
    assertEquals("@daily", p.schedule)
    assertEquals("build", p.sites.head.task)
    assertEquals("tag:a +m1", p.sites.head.select);
    assertEquals("m2", p.sites.head.exclude)
    assertEquals(2, p.sites.head.line)
    assertTrue(source.substring(p.sites.head.offset).startsWith("\"dbt build"))
    assertEquals(Some(("", "", "nightly")), DbtAirflowParser.shell("dbt run --selector nightly"))
    assertEquals(Some(("tag:a", "", "")), DbtAirflowParser.shell("dbt ls -s tag:a"))
    assertEquals(Some(("tag:a tag:b", "tag:c", "")), DbtAirflowParser.shell("dbt run --models='tag:a tag:b' --exclude=tag:c"))
    assertTrue(DbtAirflowParser.shell("echo hi").isEmpty)
    assertEquals("nightly", parse("Cfg(dbt_selector_name='nightly')").head.sites.head.named)
  }

  def testDynamicValuesNeverBecomeAllModelsAndCommentsAreIgnored(): Unit = {
    val p = parse(
      """
# Cfg(dbt_selector="must_not_exist")
X = Cfg(name="n", dbt_selector=f"tag:{team}")
Y = Cfg(name="exclude", dbt_select="orders", dbt_exclude=get_excludes())
Z = Cfg(name="named", selector_name=read_name())
""")
    assertEquals(3, p.size)
    assertTrue(p.forall(_.sites.head.dynamic))
    assertEquals("", p.head.sites.head.select)
    assertEquals("orders", p(1).sites.head.select)
    assertTrue(parse("def helper(select='orders'): pass\nCfg(select=None, exclude=dynamic_value)").isEmpty)
    assertTrue(parse("SELECTION = 'tag:' + team\nCfg(select=SELECTION)").head.sites.head.dynamic)
  }

  def testDiscoversIngestionTargetsWithoutTreatingReadersAsWriters(): Unit = {
    val source =
      """
T = [{"name": "tia", "bq_dataset": "raw", "bq_table": "Orders"},
     {"bq_table": "submitted", "load": {"full_table_schema_reference": f"{PROJECT}.raw.submitted"}},
     {"name": "partition_check", "dataset": "raw", "table": "ignore_me"},
     {"bq_dataset": ds, "bq_table": f"table_{n}"}]
@dag(dag_id=get_dag_id(__file__), schedule="@hourly")
def ingest(): pass
"""
    val p = parse(source).head
    assertEquals("example", p.name);
    assertEquals("@hourly", p.schedule)
    assertEquals(Set((Some("raw"), "Orders"), (None, "submitted"), (Some("raw"), "submitted")),
      p.ingests.map(r => r.dataset -> r.table).toSet)
    assertTrue(p.sites.isEmpty)
    assertTrue(parse("X={'dataset':'d','table':'t'}").isEmpty)
  }
}
