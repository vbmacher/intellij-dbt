package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project._
import org.junit.Assert._
import scala.jdk.CollectionConverters._

final class DbtAirflowDetailsTest extends DbtCodeInsightTestCase {
  private def workspace = DbtWorkspace.get(getProject)
  private def resource(w: DbtWorkspace, suffix: String) = w.symbols.find(s => s.isCode && s.file.getVirtualFile.getPath.endsWith(suffix)).get
  private def taskNames(w: DbtWorkspace, s: DbtSymbol) = DbtAirflow.context(w, s).map(_.task).toSet

  def testDetailsKeepVersionsPackagesAndIndependentRootsSeparate(): Unit = {
    project()
    add("models/orders_v1.sql", "select 1 as id")
    add("models/orders_v2.sql", "select 2 as id")
    add("models/schema.yml", "models: [{name: orders, versions: [{v: 1}, {v: 2}]}]\n")
    add("dbt_packages/pkg/dbt_project.yml", "name: pkg\nconfig-version: 2\n")
    add("dbt_packages/pkg/models/orders.sql", "select 3 as id")
    add("other/dbt_project.yml", "name: analytics\nconfig-version: 2\n")
    add("other/models/orders.sql", "select 4 as id")
    add(".dbt-airflow.yml", """airflow_url: https://airflow.test/base/
tasks:
- {dag: finance, task: version_two, project: analytics, select: 'fqn:analytics.orders.v2'}
- {dag: finance, task: package_only, project: pkg, select: orders}
- {dag: finance, task: runtime, project: analytics, select: '{{ params.selection }}'}
""")
    add("other/.dbt-airflow.yml", "tasks: [{dag: independent, task: sibling_only, project: analytics, select: orders}]\n")
    val w = workspace
    val v2 = resource(w, "/models/orders_v2.sql")
    val v1 = resource(w, "/models/orders_v1.sql")
    val pkg = resource(w, "/dbt_packages/pkg/models/orders.sql")
    val other = resource(w, "/other/models/orders.sql")
    assertEquals(Set("version_two", "runtime"), taskNames(w, v2))
    assertEquals(Set("runtime"), taskNames(w, v1))
    assertEquals(Set("package_only"), taskNames(w, pkg))
    assertEquals(Set("sibling_only"), taskNames(w, other))
    val packageTask = DbtAirflow.context(w, pkg).head
    val versionTask = DbtAirflow.context(w, v2).find(_.task == "version_two").get
    assertTrue(DbtAirflow.navigation(w, v2, packageTask.id, "definition").isLeft)
    assertTrue(DbtAirflow.navigation(w, v1, versionTask.id, "browser").isLeft)
    assertTrue(DbtAirflow.navigation(w, other, versionTask.id, "definition").isLeft)
    val details = DbtGraphData.documentation(w, v2).getAsJsonObject("airflow")
    assertEquals(1, details.get("selectedCount").getAsInt)
    assertEquals(1, details.get("unresolvedCount").getAsInt)
    val dynamic = details.getAsJsonArray("tasks").asScala.find(_.getAsJsonObject.get("task").getAsString == "runtime").get.getAsJsonObject
    assertFalse(dynamic.get("selected").getAsBoolean)
    assertEquals("Unresolved selection — association unknown", dynamic.get("status").getAsString)
  }

  def testTaskLinksRevalidateMappingIdentityAndRetainEvidence(): Unit = {
    project()
    add("models/orders.sql", "select 1 as id")
    val mapping = add(".dbt-airflow.yml", """airflow_url: https://airflow.test/base/
tasks:
- {dag: 'finance /daily', task: 'build & report', project: analytics, select: orders, upstream_ingestion: [landing.load]}
""")
    val w = workspace; val model = resource(w, "/models/orders.sql")
    val task = DbtAirflow.context(w, model).head
    val definition = DbtAirflow.navigation(w, model, task.id, "definition").toOption.get
    assertEquals(mapping.getVirtualFile.getPath, definition.path)
    assertEquals(mapping.getText.indexOf("{dag:"), definition.offset)
    assertTrue(definition.url.isEmpty)
    assertEquals(Some("https://airflow.test/base/dags/finance%20%2Fdaily/grid?task_id=build%20%26%20report"),
      DbtAirflow.navigation(w, model, task.id, "browser").toOption.get.url)
    val details = DbtAirflow.details(w, model).getAsJsonArray("tasks").get(0).getAsJsonObject
    assertEquals(3, details.get("line").getAsInt)
    assertEquals("landing.load", details.getAsJsonArray("ingestion").get(0).getAsString)
    assertTrue(DbtAirflow.navigation(w, model, "unrelated-mapping", "definition").isLeft)
    assertTrue(DbtAirflow.navigation(w, model, task.id, "execute").isLeft)
    edit(mapping, "tasks: [{dag: changed, task: build, project: analytics, select: orders}]\n")
    val fresh = workspace; val freshModel = resource(fresh, "/models/orders.sql")
    assertTrue(DbtAirflow.navigation(fresh, freshModel, task.id, "definition").isLeft)
    val updated = DbtAirflow.context(fresh, freshModel).head
    assertEquals("changed", updated.dag)
    assertTrue(DbtAirflow.navigation(fresh, freshModel, updated.id, "browser").left.toOption.get.contains("No Airflow URL"))
  }

  def testAmbiguousAndForeignProjectMappingsDoNotBecomeResourceContext(): Unit = {
    project()
    add("models/orders.sql", "select 1")
    add("dbt_packages/duplicate/dbt_project.yml", "name: analytics\nconfig-version: 2\n")
    add("dbt_packages/duplicate/models/orders.sql", "select 2")
    add("other/dbt_project.yml", "name: other\nconfig-version: 2\n")
    add("other/models/orders.sql", "select 3")
    add(".dbt-airflow.yml", """tasks:
- {dag: ambiguous, task: build, project: analytics, select: orders}
- {dag: foreign, task: build, project: other, select: orders}
""")
    val w = workspace
    assertTrue(DbtAirflow.tasks(w).forall(_.problems.nonEmpty))
    assertTrue(DbtAirflow.context(w, resource(w, "/models/orders.sql")).isEmpty)
    assertTrue(DbtAirflow.context(w, resource(w, "/dbt_packages/duplicate/models/orders.sql")).isEmpty)
    assertTrue(DbtAirflow.context(w, resource(w, "/other/models/orders.sql")).isEmpty)
  }

  def testDetailsBoundLargeTaskCollectionsWithoutLosingCounts(): Unit = {
    project()
    add("models/orders.sql", "select 1")
    add(".dbt-airflow.yml", "tasks:\n" + (1 to 52).map(i => s"- {dag: daily, task: build_$i, project: analytics, select: orders}\n").mkString)
    val w = workspace; val model = resource(w, "/models/orders.sql")
    val details = DbtAirflow.details(w, model)
    assertEquals(50, details.getAsJsonArray("tasks").size())
    assertEquals(52, details.get("selectedCount").getAsInt)
    assertTrue(details.get("truncated").getAsBoolean)
    assertEquals(52, DbtAirflow.context(w, model).size)
  }
}
