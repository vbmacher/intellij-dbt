package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.navigation.DbtNavigation
import com.github.vbmacher.intellij.dbt.project._
import com.google.gson.{JsonArray, JsonObject}
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.util.TextRange
import com.intellij.util.concurrency.AppExecutorUtil
import org.junit.Assert._
import java.util.concurrent.{Callable, TimeUnit}
import scala.jdk.CollectionConverters._

/** Fixtures describe visible resource identities and metadata rather than the implementation's internal steps. */
final class DbtWorkspaceFeaturesTest extends DbtCodeInsightTestCase {
  override protected def tearDown(): Unit = try DbtProjectSettings.get(getProject).loadState(new DbtSettingsState) finally super.tearDown()
  private def workspace = DbtWorkspace.get(getProject)
  private def resource(name: String) = workspace.symbols.find(s => s.name == name && s.isCode).get
  private def documentedProject(): Unit = {
    project("profile: analytics\n")
    add("profiles.yml", "analytics:\n  target: dev\n  outputs:\n    dev: {type: duckdb, schema: main}\n    prod: {type: duckdb, schema: prod}\n")
    add("models/orders.sql", "{{ config(materialized='incremental') }}\nselect * from {{ source('raw', 'events') }}")
    add("models/schema.yml", """models:
- name: orders
  description: "Orders <strong> & details"
  config: {materialized: incremental, tags: [finance]}
  columns:
  - name: id
    data_type: bigint
    description: Identifier
    data_tests: [unique, not_null]
sources:
- name: raw
  schema: landing
  tables:
  - name: events
    identifier: actual_events
    description: Raw events
    columns: [{name: event_id, data_type: int}]
""")
    add("models/report.sql", "select * from {{ ref('orders') }}")
    add("target/manifest.json", """{"metadata":{"dbt_schema_version":"https://schemas.getdbt.com/dbt/manifest/v12.json","dbt_version":"1.10.0","generated_at":"2099-01-01T00:00:00Z"},"nodes":{
      "model.analytics.orders":{"unique_id":"model.analytics.orders","name":"orders","resource_type":"model","package_name":"analytics","original_file_path":"models/orders.sql","database":"warehouse","schema":"mart","alias":"physical_orders","relation_name":"\"warehouse\".\"mart\".\"physical_orders\"","config":{"materialized":"incremental"},"compiled_code":"select * from warehouse.landing.actual_events","depends_on":{"nodes":["source.analytics.raw.events"]}},
      "test.analytics.unique_orders_id":{"unique_id":"test.analytics.unique_orders_id","name":"unique_orders_id","test_metadata":{"name":"unique"},"resource_type":"test","package_name":"analytics","column_name":"id","depends_on":{"nodes":["model.analytics.orders"]}}
      },"sources":{"source.analytics.raw.events":{"unique_id":"source.analytics.raw.events","name":"events","source_name":"raw","resource_type":"source","package_name":"analytics","database":"warehouse","schema":"landing","identifier":"actual_events","relation_name":"warehouse.landing.actual_events"}}}""")
  }

  def testGraphIncludesOnlyResourceCardsWithUpstreamToDownstreamEdges(): Unit = {
    documentedProject()
    add("seeds/countries.csv", "id\n1\n")
    add("snapshots/history.sql", "{% snapshot history %}select * from {{ ref('orders') }}{% endsnapshot %}")
    add("models/exposures.yml", "exposures:\n- name: dashboard\n  type: dashboard\n  depends_on: [\"ref('report')\"]\n")
    val snapshot = DbtGraphData.build(workspace, None, 5, 5, exposures = true)
    val kinds = snapshot.payload.getAsJsonArray("nodes").asScala.map(_.getAsJsonObject.get("kind").getAsString).toSet
    assertEquals(Set("model", "source_table", "seed", "snapshot", "exposure"), kinds)
    val names = snapshot.payload.getAsJsonArray("nodes").asScala.map(_.getAsJsonObject.get("name").getAsString).toVector
    assertEquals(1, names.count(_ == "orders")); assertFalse(names.exists(_.startsWith("unique_")))
    val edges = snapshot.payload.getAsJsonArray("edges").asScala.map(_.getAsJsonObject)
    assertTrue(edges.exists(e => e.get("source").getAsString.endsWith("source.analytics.raw.events") && e.get("target").getAsString.endsWith("model.analytics.orders")))
    val noExposures = DbtGraphData.build(workspace, None, 5, 5, exposures = false)
    assertFalse(noExposures.payload.getAsJsonArray("nodes").toString.contains("dashboard"))
  }

  def testGraphDepthExpansionAndCyclesDoNotDuplicateResources(): Unit = {
    project()
    (0 to 8).foreach(n => add(s"models/m$n.sql", if (n == 0) "select 1" else s"select * from {{ ref('m${n - 1}') }}"))
    val focus = DbtGraphData.key(workspace, resource("m4"))
    val shallow = DbtGraphData.build(workspace, Some(focus), 1, 1, exposures = true).payload
    assertEquals(3, shallow.getAsJsonArray("nodes").size())
    assertEquals(2, shallow.getAsJsonArray("boundary").size())
    val expanded = DbtGraphData.build(workspace, Some(focus), 6, 6, exposures = true).payload
    assertEquals(9, expanded.getAsJsonArray("nodes").size())
    edit(resource("m0").file, "select * from {{ ref('m8') }}")
    assertEquals(9, DbtGraphData.build(workspace, Some(focus), 20, 20, exposures = true).targets.size)
  }

  def testSettingsSerializeEveryGraphOptionAndKeepRootsIndependent(): Unit = {
    project()
    val settings = DbtProjectSettings.get(getProject)
    val root = workspace.roots.head.directory.getPath
    settings.options(root).target = "dev"
    settings.options(root + "/another").target = "prod"
    for (direction <- Vector("LR", "TB", "RL", "BT"); edge <- Vector("bezier", "taxi", "round-taxi", "segments", "straight", "unbundled-bezier", "haystack")) {
      settings.getState.direction = direction; settings.getState.edgeStyle = edge
      val xml = com.intellij.util.xmlb.XmlSerializer.serialize(settings.getState)
      val restored = com.intellij.util.xmlb.XmlSerializer.deserialize(xml, classOf[DbtSettingsState])
      assertEquals(direction, restored.direction); assertEquals(edge, restored.edgeStyle)
      assertEquals("dev", restored.projects.get(root).target); assertEquals("prod", restored.projects.get(root + "/another").target)
    }
  }

  def testConfiguredArtifactDirectoryRemainsExcludedFromSourceResources(): Unit = {
    project()
    add("models/orders.sql", "select 1")
    add("models/generated/shadow.sql", "select 2")
    val settings = DbtProjectSettings.get(getProject)
    settings.options(workspace.roots.head.directory.getPath).artifactDirectory = "models/generated"
    settings.changed()
    assertTrue(workspace.symbols.exists(s => s.isCode && s.name == "orders"))
    assertFalse(workspace.symbols.exists(s => s.isCode && s.name == "shadow"))
  }

}
