package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project.*
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.fixtures.TempDirTestFixture
import org.junit.Assert.*

import java.nio.file.Files

/** Airflow DAG integration: static DAG parsing and task details for dbt resources in real project directories. */
class DbtAirflowTest : DbtTestCase() {
    private var contentRoot: VirtualFile? = null

    override fun createTempDirTestFixture(): TempDirTestFixture =
        IdeaTestFixtureFactory.getFixtureFactory().createTempDirTestFixture()

    override fun project(config: String, path: String): PsiFile {
        val file = super.project(config, path)
        contentRoot = file.virtualFile.parent
        PsiTestUtil.addContentRoot(getModule(), contentRoot!!)
        return file
    }

    override fun tearDown() {
        try {
            contentRoot?.let { PsiTestUtil.removeContentEntry(getModule(), it) }
        } finally {
            super.tearDown()
        }
    }

    private fun resource(w: DbtWorkspace, suffix: String) =
        w.symbols.find { s -> s.isCode && s.file.virtualFile.path.endsWith(suffix) }!!

    private val settings get() = getProject().getService(DbtConsumersSettings::class.java).getState()

    private fun enable(path: String) {
        settings.airflowPaths = java.util.ArrayList<String>(listOf(path))
        DbtProjectSettings.get(getProject()).changed()
    }

    fun testRelatedSymbolUsesEditorContextWithoutAResolvedPsiElement() {
        val config = project()
        val model = configure("models/orders.sql", "select 1<caret>")
        add("dags/daily.py", "Cfg(dag_id='finance', task_id='build', dbt_selector='orders')")
        enable(config.virtualFile.parent.findChild("dags")!!.path)
        settings.airflowUrl = "https://airflow.test/"
        val context = com.intellij.openapi.actionSystem.impl.SimpleDataContext.builder()
            .add(com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT, getProject())
            .add(com.intellij.openapi.actionSystem.CommonDataKeys.PSI_FILE, model)
            .add(com.intellij.openapi.actionSystem.CommonDataKeys.EDITOR, myFixture.editor).build()
        val items = DbtAirflowRelatedProvider().getItems(context)
        assertEquals(setOf("finance / build", "Open finance / build"), items.map { it.customName }.toSet())
        assertTrue(items.any { i -> (i.element)?.let { it.containingFile.name == "daily.py" } == true })
    }

    fun testAutomaticSelectionsKeepVersionsPackagesAndIndependentRootsSeparate() {
        val config = project()
        add("models/orders_v1.sql", "select 1 as id")
        add("models/orders_v2.sql", "select 2 as id")
        add("models/schema.yml", "models: [{name: orders, versions: [{v: 1}, {v: 2}]}]\n")
        add("dbt_packages/pkg/dbt_project.yml", "name: pkg\nconfig-version: 2\n")
        add("dbt_packages/pkg/models/orders.sql", "select 3 as id")
        add("other/dbt_project.yml", "name: analytics\nconfig-version: 2\n")
        add("other/models/orders.sql", "select 4 as id")
        add(
            "dags/finance.py",
            """Cfg(name="finance", dbt_jobs=[
        {"name":"version_two", "selector":"fqn:analytics.orders.v2"},
        {"name":"package_only", "selector":"package:pkg"},
        {"name":"runtime", "selector":f"tag:{team}"}])"""
        )
        add("other/dags/independent.py", "Cfg(name='independent', dbt_selector='orders')")
        enable(config.virtualFile.parent.path)
        val w = workspace
        val v2 = resource(w, "/models/orders_v2.sql");
        val v1 = resource(w, "/models/orders_v1.sql")
        val pkg = resource(w, "/dbt_packages/pkg/models/orders.sql");
        val other = resource(w, "/other/models/orders.sql")
        assertEquals(setOf("version_two", "runtime"), DbtAirflow.context(w, v2).map { it.task }.toSet())
        assertEquals(setOf("runtime"), DbtAirflow.context(w, v1).map { it.task }.toSet())
        assertEquals(setOf("package_only", "runtime"), DbtAirflow.context(w, pkg).map { it.task }.toSet())
        assertEquals(setOf("independent"), DbtAirflow.context(w, other).map { it.task }.toSet())
        val versionTask = DbtAirflow.context(w, v2).find { it.task == "version_two" }!!
        assertTrue(DbtAirflow.navigation(w, v1, versionTask.id, "definition").isFailure)
        assertTrue(DbtAirflow.navigation(w, other, versionTask.id, "definition").isFailure)
        val details = DbtGraphData.documentation(w, v2).getAsJsonObject("airflow")
        assertEquals(1, details.get("selectedCount").asInt)
        assertEquals(1, details.get("unresolvedCount").asInt)
    }

    fun testTaskLinksOpenPythonLocationsAndRevalidateAfterUnsavedEdits() {
        val config = project();
        add("models/orders.sql", "select 1 as id")
        val dag = add(
            "dags/finance.py",
            """# scheduler configuration
Cfg(dag_id="finance /daily", task_id="build & report", dbt_selector="orders")
"""
        )
        enable(config.virtualFile.parent.findChild("dags")!!.path)
        settings.airflowUrl = "https://airflow.test/base/"
        val w = workspace;
        val model = resource(w, "/models/orders.sql");
        val task = DbtAirflow.context(w, model).first()
        val definition = DbtAirflow.navigation(w, model, task.id, "definition").getOrThrow()
        assertEquals(dag.virtualFile.path, definition.path)
        assertEquals(dag.text.indexOf("dbt_selector"), definition.offset)
        assertEquals(
            ("https://airflow.test/base/dags/finance%20%2Fdaily/grid?task_id=build%20%26%20report"),
            DbtAirflow.navigation(w, model, task.id, "browser").getOrThrow().url
        )
        assertEquals(2, DbtAirflow.details(w, model).getAsJsonArray("tasks").get(0).asJsonObject.get("line").asInt)
        assertTrue(DbtAirflow.navigation(w, model, "unrelated", "definition").isFailure)
        edit(dag, "Cfg(dag_id='changed', task_id='build', dbt_selector='orders')")
        val fresh = workspace;
        val freshModel = resource(fresh, "/models/orders.sql")
        assertTrue(DbtAirflow.navigation(fresh, freshModel, task.id, "definition").isFailure)
        assertEquals("changed", DbtAirflow.context(fresh, freshModel).first().dag)
        val html = DbtDocumentation.render(fresh, freshModel)
        assertTrue(html.contains("<h3>Airflow context</h3>"))
        assertTrue(html.contains("changed / build"))
    }

    fun testExternalCheckoutRecursionSkipDirectoriesAndDiskRefresh() {
        project();
        add("models/orders.sql", "select 1")
        val directory = tempDirectory("dbt-airflow-external-")
        val nested = Files.createDirectories(directory.resolve("nested"))
        val dag = nested.resolve("daily.py")
        Files.writeString(dag, "Cfg(name='daily', dbt_selector='orders')")
        Files.writeString(directory.resolve("unrelated.py"), "raise RuntimeError('never execute DAG code')")
        Files.writeString(
            Files.createDirectories(directory.resolve(".venv")).resolve("hidden.py"),
            "Cfg(dbt_selector='orders')"
        )
        enable(directory.toString())
        assertEquals(listOf("daily"), DbtAirflow.tasks(workspace).map { it.dag })
        val virtual = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(directory)!!
        virtual.refresh(false, true)
        Files.writeString(dag, "Cfg(name='changed', dbt_selector='orders')")
        virtual.refresh(false, true)
        assertEquals(listOf("changed"), DbtAirflow.tasks(workspace).map { it.dag })
        Files.delete(dag);
        virtual.refresh(false, true)
        assertTrue(DbtAirflow.tasks(workspace).isEmpty())
        Files.writeString(nested.resolve("new.py"), "Cfg(name='new', dbt_selector='orders')")
        virtual.refresh(false, true)
        assertEquals(listOf("new"), DbtAirflow.tasks(workspace).map { it.dag })
    }

    fun testAutomaticProjectDetectionKeepsAmbiguousExternalDagsUnresolvedAndIgnoresManualYaml() {
        val config = project();
        add("models/orders.sql", "select 1")
        add("other/dbt_project.yml", "name: other\nconfig-version: 2\n");
        add("other/models/orders.sql", "select 2")
        val dag = add("dags/daily.py", "Cfg(name='daily', dbt_selector='orders')")
        enable(dag.virtualFile.parent.path)
        assertEquals(
            listOf("model.analytics.orders"),
            DbtAirflow.tasks(workspace).first().resources.map { it.uniqueId })
        add(".dbt-airflow.yml", "tasks: [{dag: ignored, task: ignored, project: analytics, select: '*'}]\n")
        assertEquals(listOf("daily"), DbtAirflow.tasks(workspace).map { it.dag })
        val external = tempDirectory("dbt-airflow-ambiguous-")
        Files.writeString(external.resolve("ambiguous.py"), "Cfg(name='ambiguous', dbt_selector='orders')")
        enable(external.toString())
        val task = DbtAirflow.tasks(workspace).first()
        assertTrue(task.problems.any { it.contains("ambiguous") })
        assertTrue(task.resources.isEmpty())
    }

    fun testIngestionMatchesPhysicalDestinationsAndFindsUpstreamPipelines() {
        val config = project()
        add(
            "models/sources.yml",
            "sources: [{name: raw, schema: landing, tables: [{name: orders, identifier: physical_orders}]}]\n"
        )
        add("models/orders.sql", "select * from {{ source('raw', 'orders') }}")
        add(
            "dags/load.py",
            "X={'bq_dataset':'landing','bq_table':'physical_orders'}\n@dag(dag_id='load',schedule='@hourly')\ndef f(): pass"
        )
        add("dags/build.py", "Cfg(name='build', dbt_selector='orders')")
        enable(config.virtualFile.parent.findChild("dags")!!.path)
        val tasks = DbtAirflow.tasks(workspace)
        val load = tasks.find { it.kind == "ingestion" }!!
        assertEquals(listOf("source.analytics.raw.orders"), load.resources.map { it.uniqueId })
        assertTrue(load.ingestion.first().contains("exact"))
        assertEquals("@hourly", load.schedule)
        assertEquals(listOf("load"), tasks.find { it.dag == "build" }!!.ingestion)
    }

    fun testConfiguredManifestSuppliesNamedSelectorsAndIngestionRelations() {
        val config = project()
        add("models/orders.sql", "select 1")
        add(
            "artifacts/production-manifest.json",
            """{
      "metadata":{"dbt_schema_version":"https://schemas.getdbt.com/dbt/manifest/v12.json"},
      "nodes":{"model.analytics.orders":{"unique_id":"model.analytics.orders","resource_type":"model","name":"orders",
        "package_name":"analytics","original_file_path":"models/orders.sql","schema":"mart","alias":"physical_orders"}},
      "selectors":{"nightly":{"definition":{"union":[{"method":"fqn","value":"orders"}]}}}
    }"""
        )
        add("dags/finance.py", "Cfg(name='finance', dbt_selector_name='nightly')")
        add("dags/ingest.py", "X={'target_dataset':'mart','target_table':'physical_orders'}")
        DbtProjectSettings.get(getProject()).options(config.virtualFile.parent.path).manifestPath =
            "artifacts/production-manifest.json"
        enable(config.virtualFile.parent.findChild("dags")!!.path)
        val tasks = DbtAirflow.tasks(workspace)
        assertEquals(
            listOf("model.analytics.orders"),
            tasks.find { it.dag == "finance" }!!.resources.map { it.uniqueId })
        assertEquals(
            listOf("model.analytics.orders"),
            tasks.find { it.kind == "ingestion" }!!.resources.map { it.uniqueId })
        assertTrue(tasks.all { it.problems.isEmpty() })
        val selectors = add("selectors.yml", "selectors: [{name: nightly, definition: 'tag:absent'}]\n")
        assertTrue(DbtAirflow.tasks(workspace).find { it.dag == "finance" }!!.resources.isEmpty())
        edit(selectors, "selectors: []\n")
        assertTrue(DbtAirflow.tasks(workspace).find { it.dag == "finance" }!!.problems.any { it.contains("missing") })
    }

    fun testExternalDbtContentRootIsDetectedAutomatically() {
        project();
        add("models/local.sql", "select 1")
        val directory = tempDirectory("dbt-airflow-project-")
        var externalRoot: VirtualFile? = null
        try {
            Files.writeString(directory.resolve("dbt_project.yml"), "name: external\nconfig-version: 2\n")
            Files.writeString(Files.createDirectories(directory.resolve("models")).resolve("orders.sql"), "select 2")
            Files.writeString(directory.resolve("remote.py"), "Cfg(name='remote_build', dbt_selector='orders')")
            externalRoot = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(directory)!!
            externalRoot!!.refresh(false, true)
            PsiTestUtil.addContentRoot(getModule(), externalRoot!!)
            enable(directory.toString())
            val tasks = DbtAirflow.tasks(workspace)
            assertEquals(listOf("model.external.orders"), tasks.first().resources.map { it.uniqueId })
            assertEquals((directory.toString()), tasks.first().projectRoot)
        } finally {
            externalRoot?.let { PsiTestUtil.removeContentEntry(getModule(), it) }
        }
    }

    fun testIngestionConfidenceAndUniqueTableFallbackRemainExplicit() {
        val config = project()
        add(
            "models/sources.yml",
            """sources:
- name: raw
  schema: landing
  tables: [{name: orders, identifier: PhysicalOrders}, {name: unique, identifier: unique_table}]
- name: other
  schema: another
  tables: [{name: orders, identifier: PhysicalOrders}]
"""
        )
        add("dags/exact.py", "X={'bq_dataset':'landing','bq_table':'PhysicalOrders'}")
        add("dags/folded.py", "X={'bq_dataset':'LANDING','bq_table':'PHYSICALORDERS'}")
        add("dags/unique.py", "X={'target_table':'unique_table'}")
        add("dags/ambiguous.py", "X={'destination_table':'PhysicalOrders'}")
        enable(config.virtualFile.parent.findChild("dags")!!.path)
        val tasks = DbtAirflow.tasks(workspace)
        val exact = tasks.find { it.dag == "exact" }!!
        assertEquals(listOf("source.analytics.raw.orders"), exact.resources.map { it.uniqueId })
        assertTrue(exact.ingestion.first().contains("exact"))
        val folded = tasks.find { it.dag == "folded" }!!
        assertEquals(exact.resources.map { it.uniqueId }, folded.resources.map { it.uniqueId })
        assertTrue(folded.ingestion.first().contains("case-insensitive"))
        val unique = tasks.find { it.dag == "unique" }!!
        assertEquals(listOf("source.analytics.raw.unique"), unique.resources.map { it.uniqueId })
        assertTrue(unique.ingestion.first().contains("table-only"))
        assertTrue(tasks.find { it.dag == "ambiguous" }!!.resources.isEmpty())
    }

    fun testReferenceSelectorMethodsAndAtExpansionResolveAutomatically() {
        val config = project()
        add(
            "models/staging/orders.sql",
            "{{ config(materialized='incremental', tags=['finance'], access='public') }} select 1"
        )
        add("models/report.sql", "select * from {{ ref('orders') }}")
        add("models/extra.sql", "select 2")
        add(
            "dags/methods.py",
            """Cfg(name="methods", dbt_jobs=[
      {"name":"config", "select":"config.materialized:incremental"},
      {"name":"tags", "select":"config.tags:finance"},
      {"name":"file", "select":"file:orders"},
      {"name":"access", "select":"access:public"},
      {"name":"folder", "select":"staging"},
      {"name":"graph", "select":"@orders"},
      {"name":"types", "select":"type:model", "exclude":"extra"}])"""
        )
        enable(config.virtualFile.parent.findChild("dags")!!.path)
        val tasks = DbtAirflow.tasks(workspace)
        listOf("config", "tags", "file", "access", "folder").forEach { name ->
            assertEquals(name, listOf("orders"), tasks.find { it.task == name }!!.resources.map { it.name })
        }
        assertEquals(setOf("orders", "report"), tasks.find { it.task == "graph" }!!.resources.map { it.name }.toSet())
        assertEquals(setOf("orders", "report"), tasks.find { it.task == "types" }!!.resources.map { it.name }.toSet())
        assertTrue(tasks.all { it.problems.isEmpty() })
    }

    fun testDetailsBoundLargeCollectionsWithoutLosingCountsAndReportMissingFolders() {
        val config = project();
        add("models/orders.sql", "select 1")
        add("dags/many.py", (1..52).map { i -> "Cfg(name='build_$i', dbt_selector='orders')\n" }.joinToString(""))
        enable(config.virtualFile.parent.findChild("dags")!!.path)
        val w = workspace;
        val details = DbtAirflow.details(w, resource(w, "/models/orders.sql"))
        assertEquals(50, details.getAsJsonArray("tasks").size());
        assertEquals(52, details.get("selectedCount").asInt)
        assertTrue(details.get("truncated").asBoolean)
        enable(config.virtualFile.parent.path + "/missing")
        assertTrue(DbtAirflow.problems(workspace).any { it.contains("missing") })
    }


    private fun parse(source: String) = DbtAirflowParser.parse("/dags/example.py", source)

    fun testReadsConfigObjectsAndIgnoresFunctionDefaultsAndNone() {
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
"""
        )
        assertEquals(1, pipelines.size)
        val p = pipelines.first()
        assertEquals("dbt_gtm", p.name);
        assertEquals("57 9 * * *", p.schedule)
        assertEquals("tag:gtm_1000WIB", p.sites.first().select)
        assertEquals("tag:slow tag:flaky", p.sites.first().exclude)
        assertEquals("DbtIndirectSelection.CAUTIOUS", p.sites.first().indirect)
    }

    fun testEvaluatesConstantsConcatenationListsJoinsAndAdjacentStrings() {
        val p = parse(
            """
BASE = ["tag:a", "tag:b"]
NAME = "night" + "ly"
X = Cfg(name=NAME, dbt_selector=" ".join(BASE + ["m1+"]), dbt_exclude=",".join(["tag:x", "tag:y"]))
Y = Cfg(name="other", dbt_selector=("tag:" "finance"))
"""
        )
        assertEquals("nightly", p.first().name)
        assertEquals("tag:a tag:b m1+", p.first().sites.first().select)
        assertEquals("tag:x,tag:y", p.first().sites.first().exclude)
        assertEquals("tag:finance", p[1].sites.first().select)
    }

    fun testNestedJobsInheritExcludesScheduleAndPipelineName() {
        val p = parse(
            """
dag_definitions = [
  {"dag_name_suffix": "hourly", "schedule": "0 * * * *", "excludes": ["tag:skip"],
   "dbt_jobs": [{"selector": "tag:hourly"}, {"name": "j2", "selector": "1+m2"}]},
]
"""
        ).first()
        assertEquals("hourly", p.name);
        assertEquals("0 * * * *", p.schedule)
        assertEquals(listOf("tag:hourly", "1+m2"), p.sites.map { it.select })
        assertEquals(listOf("tag:skip", "tag:skip"), p.sites.map { it.exclude })
        assertEquals("j2", p.sites[1].task)
    }

    fun testShellFlagsNamedSelectorsAndSourceLocations() {
        val source =
            """with DAG(dag_id="daily", schedule_interval="@daily"):
    task = BashOperator(task_id="build", bash_command="dbt build --select tag:a +m1 --exclude m2 --target prod")
"""
        val p = parse(source).first()
        assertEquals("daily", p.name);
        assertEquals("@daily", p.schedule)
        assertEquals("build", p.sites.first().task)
        assertEquals("tag:a +m1", p.sites.first().select);
        assertEquals("m2", p.sites.first().exclude)
        assertEquals(2, p.sites.first().line)
        assertTrue(source.substring(p.sites.first().offset).startsWith("\"dbt build"))
        assertEquals(Triple("", "", "nightly"), DbtAirflowParser.shell("dbt run --selector nightly"))
        assertEquals(Triple("tag:a", "", ""), DbtAirflowParser.shell("dbt ls -s tag:a"))
        assertEquals(
            Triple("tag:a tag:b", "tag:c", ""),
            DbtAirflowParser.shell("dbt run --models='tag:a tag:b' --exclude=tag:c")
        )
        assertTrue(DbtAirflowParser.shell("echo hi") == null)
        assertEquals("nightly", parse("Cfg(dbt_selector_name='nightly')").first().sites.first().named)
    }

    fun testDynamicValuesNeverBecomeAllModelsAndCommentsAreIgnored() {
        val p = parse(
            """
# Cfg(dbt_selector="must_not_exist")
X = Cfg(name="n", dbt_selector=f"tag:{team}")
Y = Cfg(name="exclude", dbt_select="orders", dbt_exclude=get_excludes())
Z = Cfg(name="named", selector_name=read_name())
"""
        )
        assertEquals(3, p.size)
        assertTrue(p.all { it.sites.first().dynamic })
        assertEquals("", p.first().sites.first().select)
        assertEquals("orders", p[1].sites.first().select)
        assertTrue(parse("def helper(select='orders'): pass\nCfg(select=None, exclude=dynamic_value)").isEmpty())
        assertTrue(parse("SELECTION = 'tag:' + team\nCfg(select=SELECTION)").first().sites.first().dynamic)
    }

    fun testDiscoversIngestionTargetsWithoutTreatingReadersAsWriters() {
        val source =
            """
T = [{"name": "tia", "bq_dataset": "raw", "bq_table": "Orders"},
     {"bq_table": "submitted", "load": {"full_table_schema_reference": f"{PROJECT}.raw.submitted"}},
     {"name": "partition_check", "dataset": "raw", "table": "ignore_me"},
     {"bq_dataset": ds, "bq_table": f"table_{n}"}]
@dag(dag_id=get_dag_id(__file__), schedule="@hourly")
def ingest(): pass
"""
        val p = parse(source).first()
        assertEquals("example", p.name);
        assertEquals("@hourly", p.schedule)
        assertEquals(
            setOf("raw" to "Orders", null to "submitted", "raw" to "submitted"),
            p.ingests.map { r -> r.dataset to r.table }.toSet()
        )
        assertTrue(p.sites.isEmpty())
        assertTrue(parse("X={'dataset':'d','table':'t'}").isEmpty())
    }
}
