package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project._
import com.google.gson.{JsonObject, JsonParser}
import org.junit.Assert._
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import scala.jdk.CollectionConverters._

final class DbtImpactIntegrationsTest extends DbtCodeInsightTestCase {
  private def workspace = DbtWorkspace.get(getProject)
  private def root = workspace.roots.find(_.name == "analytics").get
  private def model(name: String) = workspace.symbols.find(s => s.name == name && s.isCode).get
  override protected def tearDown(): Unit = try {
  } finally super.tearDown()

  def testImpactComparisonDistinguishesAddRemoveMoveAndOutputChanges(): Unit = {
    val old = Vector(DbtImpactResource("model.a.orders.v1", "old.sql", "one", Vector("amount")),
      DbtImpactResource("model.a.deleted", "deleted.sql", "two", Vector("id")),
      DbtImpactResource("model.b.orders", "same.sql", "three", Vector("id")))
    val fresh = Vector(DbtImpactResource("model.a.orders.v1", "new.sql", "one", Vector("amount")),
      DbtImpactResource("model.a.added", "added.sql", "four", Vector("new_id")),
      DbtImpactResource("model.b.orders", "same.sql", "changed", Vector("id", "amount")))
    val changes = DbtImpact.compare(old, fresh)
    assertEquals(Map("model.a.orders.v1" -> "moved", "model.a.deleted" -> "removed", "model.a.added" -> "added", "model.b.orders" -> "changed"),
      changes.map(c => c.resource.id -> c.state).toMap)
    assertEquals(Vector("id", "amount"), changes.find(_.resource.id == "model.b.orders").get.columns)
  }

  def testIdenticalBaselineDoesNotFlagUnrelatedPropertyEntries(): Unit = {
    val config = project()
    val orders = add("models/orders.sql", "select 1 as id")
    val other = add("models/other.sql", "select 2 as unrelated")
    val properties = add("models/schema.yml", "models:\n- name: orders\n  columns: [{name: id}]\n- name: other\n  description: Other\n")
    val baseline = Map(config.getVirtualFile.getPath -> config.getText, orders.getVirtualFile.getPath -> orders.getText,
      other.getVirtualFile.getPath -> other.getText, properties.getVirtualFile.getPath -> properties.getText)
    val before = DbtImpact.baselineResources(workspace, baseline)
    assertTrue(DbtImpact.compare(before, DbtImpact.current(workspace)).isEmpty)
    edit(orders, "select 3 as id")
    val changed = DbtImpact.compare(before, DbtImpact.current(workspace))
    assertEquals(Vector("model.analytics.orders"), changed.map(_.resource.id.split("\\|").last))
  }

  def testSourceTableChangesPreserveUnrelatedTablesAndIdentity(): Unit = {
    val config = project()
    val properties = add("models/sources.yml", "sources: [{name: raw, schema: landing, tables: [{name: orders, columns: [{name: amount}]}, {name: customers, columns: [{name: id}]}]}]\n")
    val before = DbtImpact.baselineResources(workspace, Map(config.getVirtualFile.getPath -> config.getText, properties.getVirtualFile.getPath -> properties.getText))
    assertTrue(DbtImpact.compare(before, DbtImpact.current(workspace)).isEmpty)
    edit(properties, properties.getText.replace("name: amount", "name: gross_amount"))
    val changes = DbtImpact.compare(before, DbtImpact.current(workspace))
    assertEquals(Vector("source.analytics.raw.orders"), changes.map(_.resource.id.split("\\|").last))
    assertEquals(Vector("amount", "gross_amount"), changes.head.columns)
  }

  def testBaselineUsesDbtIgnoreForExistingAndDeletedHistoricalFiles(): Unit = {
    val config = project()
    add(".dbtignore", "models/private/**\nmodels/historical.sql\n")
    val public = add("models/public.sql", "select 1 as id")
    val privateFile = add("models/private/secret.sql", "select 2 as internal")
    val contents = Map(config.getVirtualFile.getPath -> config.getText, public.getVirtualFile.getPath -> public.getText,
      privateFile.getVirtualFile.getPath -> privateFile.getText, root.directory.getPath + "/models/historical.sql" -> "select 3")
    val before = DbtImpact.baselineResources(workspace, contents)
    assertEquals(Vector("model.analytics.public"), before.map(_.id.split("\\|").last))
    assertTrue(DbtImpact.compare(before, DbtImpact.current(workspace)).isEmpty)
  }

  def testBaselineProjectNamePreservesRemovedAndAddedIdentityOnProjectRename(): Unit = {
    val config = project()
    val code = add("models/orders.sql", "select 1 as amount")
    val contents = Map(config.getVirtualFile.getPath -> config.getText, code.getVirtualFile.getPath -> code.getText)
    edit(config, config.getText.replace("name: analytics", "name: renamed"))
    val changes = DbtImpact.compare(DbtImpact.baselineResources(workspace, contents), DbtImpact.current(workspace))
    assertEquals(Map("model.analytics.orders" -> "removed", "model.renamed.orders" -> "added"),
      changes.map(c => c.resource.id.split("\\|").last -> c.state).toMap)
  }

  def testVersionedDefinedInAndPackageIdentityStaySeparate(): Unit = {
    project()
    val code = add("models/physical_orders.sql", "select 1 as id")
    val properties = add("models/schema.yml", "models: [{name: orders, versions: [{v: 2, defined_in: physical_orders, columns: [{name: id}]}]}]\n")
    add("dbt_packages/pkg/dbt_project.yml", "name: pkg\nconfig-version: 2\n")
    add("dbt_packages/pkg/models/orders.sql", "select 2 as id")
    val baseline = Map(code.getVirtualFile.getPath -> code.getText, properties.getVirtualFile.getPath -> properties.getText)
    val before = DbtImpact.baselineResources(workspace, baseline)
    val local = DbtImpact.current(workspace).filter(_.id.startsWith(root.directory.getPath + "|"))
    assertEquals(Vector("model.analytics.orders.v2"), local.map(_.id.split("\\|").last))
    assertTrue(DbtImpact.compare(before, local).isEmpty)
  }

  def testVersionedModelIncludesInheritedMetadataInBaselineFingerprint(): Unit = {
    val config = project()
    val code = add("models/orders_v2.sql", "select 1 as amount")
    val properties = add("models/schema.yml", "models: [{name: orders, description: Before, columns: [{name: amount}], versions: [{v: 2}]}]\n")
    val before = DbtImpact.baselineResources(workspace, Map(config.getVirtualFile.getPath -> config.getText,
      code.getVirtualFile.getPath -> code.getText, properties.getVirtualFile.getPath -> properties.getText))
    assertTrue(DbtImpact.compare(before, DbtImpact.current(workspace)).isEmpty)
    edit(properties, properties.getText.replace("Before", "After"))
    val changed = DbtImpact.compare(before, DbtImpact.current(workspace))
    assertEquals(Vector("model.analytics.orders.v2"), changed.map(_.resource.id.split("\\|").last))
  }


}
