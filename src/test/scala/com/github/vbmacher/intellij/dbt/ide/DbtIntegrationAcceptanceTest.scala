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


}
