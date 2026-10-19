package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project._
import com.google.gson.{JsonObject, JsonParser}
import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import org.junit.Assert._
import javax.swing.JCheckBox
import scala.jdk.CollectionConverters._

/** Acceptance boundaries across project ownership, uncertain metadata and read-only clients. */
final class DbtIntegrationAcceptanceTest extends DbtCodeInsightTestCase {
  private def workspace = DbtWorkspace.get(getProject)
  private def root = workspace.roots.find(r => r.name == "analytics" && !r.directory.getPath.endsWith("/other")).get
  private def json(text: String): JsonObject = JsonParser.parseString(text).getAsJsonObject
  private def model(w: DbtWorkspace, name: String, prefix: String = ""): DbtSymbol =
    w.symbols.find(s => s.name == name && s.isCode && (if (prefix.isEmpty) w.owner(s).contains(root) else s.file.getVirtualFile.getPath.contains(prefix))).get
  override protected def tearDown(): Unit = try {
    getProject.getService(classOf[DbtAgent]).stop()
    val consumers = getProject.getService(classOf[DbtConsumers])
    consumers.setSecret(""); consumers.clear()
    getProject.getService(classOf[DbtConsumersSettings]).loadState(new DbtConsumersState)
    DbtProjectSettings.get(getProject).loadState(new DbtSettingsState)
  } finally super.tearDown()

  def testImpactColumnPathsStayWithinTheOwningProjectWhenNamesCollide(): Unit = {
    project()
    add("models/schema.yml", "models: [{name: orders, columns: [{name: amount}]}]\n")
    add("models/orders.sql", "select 1 as amount")
    add("models/report.sql", "select amount * 2 as total from {{ ref('orders') }}")
    add("other/dbt_project.yml", "name: analytics\nconfig-version: 2\n")
    add("other/models/schema.yml", "models: [{name: orders, columns: [{name: amount}]}]\n")
    add("other/models/orders.sql", "select 2 as amount")
    add("other/models/report.sql", "select amount * 3 as total from {{ ref('orders') }}")
    val w = workspace
    val changed = DbtImpact.current(w).find(_.id == DbtImpact.key(w, model(w, "orders"))).get
    val affected = DbtImpact.affectedResources(w, Vector(DbtImpactChange(changed, "changed", Vector("amount"))))
    assertEquals(Set("orders", "report"), affected.map(_._1.name).toSet)
    assertTrue(affected.forall(a => w.owner(a._1).contains(root)))
    assertTrue(affected.find(_._1.name == "report").get._2.exists(_.contains("total via")))
    assertFalse(getProject.getService(classOf[DbtRunner]).isRunning)
  }

  def testImpactDeletedLiteralReferencesRetainTraceAndUnrelatedModelsStayOut(): Unit = {
    project()
    add("models/report.sql", "select * from {{ ref('deleted') }}")
    add("models/unrelated.sql", "select 1 as id")
    add("other/dbt_project.yml", "name: analytics\nconfig-version: 2\n")
    add("other/models/report.sql", "select * from {{ ref('deleted') }}")
    val w = workspace
    val removed = DbtImpactResource(root.directory.getPath + "|model.analytics.deleted", "models/deleted.sql", "before", Vector("amount"))
    val affected = DbtImpact.affectedResources(w, Vector(DbtImpactChange(removed, "removed", removed.columns)))
    assertEquals(Vector("report"), affected.map(_._1.name))
    assertTrue(affected.forall(a => w.owner(a._1).contains(root)))
    assertEquals(removed.id, affected.head._2.head)
    assertTrue(affected.head._2.contains("unresolved literal ref after deletion"))
  }

  def testReviewedImpactBuildUsesExactSelectionTargetProfilesAndWrapperWithoutExecuting(): Unit = {
    project()
    add("models/orders.sql", "select 1 as amount")
    add("models/report.sql", "select * from {{ ref('orders') }}")
    val w = workspace
    val options = root.runOptions
    options.target = "review_target"; options.profilesDirectory = "profiles with spaces"
    options.executable = "dbt-fixture-must-not-run"; options.executableArguments = "--wrapper 'argument with spaces'"
    val resources = Vector(model(w, "orders"), model(w, "report"))
    val selection = "fqn:analytics.orders fqn:analytics.report"
    val result = DbtImpactResult("fixture-baseline", Vector.empty, resources.map(_ -> Vector("fixture evidence")), selection, Vector.empty)
    val command = DbtImpact.validationCommand(w, result).toOption.get
    assertEquals(Vector("dbt-fixture-must-not-run", "--wrapper", "argument with spaces", "build"), command.arguments.take(4))
    assertEquals(Vector("fqn:analytics.orders", "fqn:analytics.report"), command.arguments.dropWhile(_ != "--select").drop(1))
    assertTrue(command.arguments.sliding(2).exists(_ == Vector("--target", "review_target")))
    assertTrue(command.environment("DBT_PROFILES_DIR").endsWith("/profiles with spaces"))
    assertFalse(getProject.getService(classOf[DbtRunner]).isRunning)
    assertTrue(DbtImpact.validationCommand(w, result.copy(selection = "")).isLeft)
    add("other/dbt_project.yml", "name: other\nconfig-version: 2\n")
    add("other/models/another.sql", "select 1")
    val both = workspace
    val foreign = model(both, "another", "/other/")
    assertTrue(DbtImpact.validationCommand(both, result.copy(affected = result.affected :+ (foreign -> Vector.empty))).isLeft)
  }

  def testAirflowResolvesOnlyTheDeclaredProjectTreeAndEncodesExactTaskLinks(): Unit = {
    project()
    add("models/orders.sql", "select 1")
    add("dbt_packages/pkg/dbt_project.yml", "name: pkg\nconfig-version: 2\n")
    add("dbt_packages/pkg/models/orders.sql", "select 2")
    add("other/dbt_project.yml", "name: other\nconfig-version: 2\n")
    add("other/models/orders.sql", "select 3")
    val mapping = add(".dbt-airflow.yml", """airflow_url: https://airflow.test/base/
tasks:
- {dag: 'finance /daily', task: 'build & report', project: pkg, select: orders}
- {dag: wrong_tree, task: build, project: other, select: orders}
""")
    add("other/.dbt-airflow.yml", "tasks: [{dag: independent, task: build, project: other, select: orders}]\n")
    add("dags/side_effect.py", "from pathlib import Path\nPath('dag-was-executed').write_text('unexpected')\nraise RuntimeError('never import DAG code')\n")
    val tasks = DbtAirflow.tasks(workspace)
    val packageTask = tasks.find(_.task == "build & report").get
    assertEquals(Vector("model.pkg.orders"), packageTask.resources.map(_.uniqueId))
    assertEquals(mapping.getVirtualFile.getPath, packageTask.evidence)
    assertEquals(mapping.getText.indexOf("{dag:"), packageTask.offset)
    assertEquals(Some("https://airflow.test/base/dags/finance%20%2Fdaily/grid?task_id=build%20%26%20report"), packageTask.url)
    val wrong = tasks.find(_.dag == "wrong_tree").get
    assertTrue(wrong.resources.isEmpty); assertTrue(wrong.problems.exists(_.contains("Project")))
    assertEquals(Vector("model.other.orders"), tasks.find(_.dag == "independent").get.resources.map(_.uniqueId))
    assertTrue(root.directory.findChild("dag-was-executed") == null)
  }

  def testAirflowRejectsAmbiguousProjectsAndCredentialBearingUrls(): Unit = {
    project()
    add("dbt_packages/duplicate/dbt_project.yml", "name: analytics\nconfig-version: 2\n")
    add(".dbt-airflow.yml", "airflow_url: https://user:secret@airflow.test\ntasks: [{dag: daily, task: build, project: analytics, select: '*'}]\n")
    val task = DbtAirflow.tasks(workspace).head
    assertTrue(task.resources.isEmpty); assertTrue(task.problems.exists(_.contains("ambiguous")))
    assertTrue(task.url.isEmpty)
    assertTrue(DbtAirflow.safeUrl("file:///etc/passwd").isEmpty)
    assertTrue(DbtAirflow.safeUrl("javascript:alert(1)").isEmpty)
  }

  def testMetabasePhysicalCollisionsAndStaleMetadataNeverBecomeDirectLineage(): Unit = {
    project()
    val declaration = "sources: [{name: raw, database: warehouse, schema: landing, tables: [{name: orders, identifier: physical_orders, columns: [{name: amount}]}]}]\n"
    add("models/sources.yml", declaration)
    add("dbt_packages/pkg/dbt_project.yml", "name: pkg\nconfig-version: 2\n")
    add("dbt_packages/pkg/models/sources.yml", declaration)
    val card = json("""{"id":7,"name":"Revenue","dataset_query":{"query":{"source-table":42,"fields":[["field",99,null]]}}}""")
    val table = json("""{"id":42,"db_name":"warehouse","schema":"landing","name":"physical_orders","fields":[{"id":99,"name":"amount"}]}""")
    val w = workspace
    val matches = DbtConsumers.mapQuestions(w, "https://metabase.test", Vector(card), Vector(table))
    assertEquals(Set("source.analytics.raw.orders", "source.pkg.raw.orders"), matches.map(_.resource.split("\\|").last).toSet)
    assertTrue(matches.forall(_.confidence.startsWith("inferred")))
    val local = new DbtWorkspace(getProject, w.roots, w.files, w.symbols.filter(s => w.owner(s).contains(root)).map(s =>
      if (s.kind == "source_table") s.copy(attributes = s.attributes.updated("stale", "true")) else s))
    assertTrue(DbtConsumers.mapQuestions(local, "https://metabase.test", Vector(card), Vector(table)).forall(_.confidence.startsWith("inferred")))
    table.addProperty("db_name", "unrelated_target")
    assertTrue(DbtConsumers.mapQuestions(w, "https://metabase.test", Vector(card), Vector(table)).isEmpty)
  }

  def testMetabaseDisconnectedFreshnessAndCredentialSettingsStayExplicitAndSecretFree(): Unit = {
    project()
    val service = getProject.getService(classOf[DbtConsumers])
    assertTrue(service.snapshot.status.contains("Disconnected"))
    assertEquals(0L, service.snapshot.refreshedAt)
    assertTrue(service.refresh(workspace).status.contains("Configure"))
    assertTrue(DbtConsumersCache(Vector.empty, System.currentTimeMillis() - 16 * 60 * 1000, "Metadata refreshed").stale)
    assertFalse(DbtConsumersCache(Vector.empty, System.currentTimeMillis(), "Metadata refreshed").stale)
    service.setSecret("acceptance-secret-must-not-export")
    assertTrue(service.hasSecret)
    val settings = getProject.getService(classOf[DbtConsumersSettings])
    val configurable = new DbtConsumersConfigurable(getProject)
    val panel = configurable.createComponent().asInstanceOf[javax.swing.JPanel]
    configurable.apply()
    assertTrue("Blank API key retains Password Safe credential", service.hasSecret)
    val serialized = JDOMUtil.writeElement(XmlSerializer.serialize(settings.getState))
    assertFalse(serialized.contains("acceptance-secret-must-not-export"))
    assertFalse(service.snapshot.toString.contains("acceptance-secret-must-not-export"))
    panel.getComponents.collectFirst { case box: JCheckBox if box.getText == "Remove saved Metabase API key" => box }.get.setSelected(true)
    configurable.apply()
    assertFalse(service.hasSecret)
  }

  def testMetabaseStalledResponseBodyTimesOutWithoutReplacingTheCache(): Unit = {
    project()
    val server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0)
    val started = new java.util.concurrent.CountDownLatch(1)
    val release = new java.util.concurrent.CountDownLatch(1)
    server.createContext("/api/card", exchange => {
      exchange.sendResponseHeaders(200, 1000)
      exchange.getResponseBody.write('['.toInt)
      exchange.getResponseBody.flush()
      started.countDown()
      try release.await(5, java.util.concurrent.TimeUnit.SECONDS)
      finally exchange.close()
    })
    server.start()
    val service = getProject.getService(classOf[DbtConsumers])
    try {
      getProject.getService(classOf[DbtConsumersSettings]).getState.metabaseUrl = "http://127.0.0.1:" + server.getAddress.getPort
      service.setSecret("bounded-fixture-key")
      val before = service.snapshot
      val start = System.nanoTime()
      val result = service.refresh(workspace, java.time.Duration.ofMillis(500))
      assertTrue("Server sent headers and stalled the response body", started.await(1, java.util.concurrent.TimeUnit.SECONDS))
      assertTrue("Response body deadline must bound the call", System.nanoTime() - start < java.util.concurrent.TimeUnit.SECONDS.toNanos(3))
      assertEquals(before.refreshedAt, result.refreshedAt)
      assertEquals(before.consumers, result.consumers)
      assertTrue(result.status.contains("unavailable"))
      assertFalse(result.toString.contains("bounded-fixture-key"))
    } finally { release.countDown(); server.stop(0) }
  }


}
