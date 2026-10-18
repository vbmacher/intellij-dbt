package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.lineage.{DbtColumnLineage, DbtSelection}
import com.github.vbmacher.intellij.dbt.navigation.DbtSqlAnalysis
import com.github.vbmacher.intellij.dbt.project._
import com.intellij.openapi.application.WriteAction
import com.intellij.refactoring.rename.RenameProcessor
import org.junit.Assert._

final class DbtWorkflowTest extends DbtCodeInsightTestCase {
  override protected def tearDown(): Unit = try DbtProjectSettings.get(getProject).loadState(new DbtSettingsState) finally super.tearDown()
  private def w = DbtWorkspace.get(getProject)
  private def model(name: String) = w.symbols.find(s => s.isCode && s.name == name).get
  def testNativeRenameUpdatesOnlyResolvingReferencesAndYaml(): Unit = {
    project()
    val source = add("models/orders.sql", "{{ config(alias='warehouse_orders') }} select 1 as id")
    val properties = add("models/schema.yml", "# preserve\nmodels: [{name: orders, meta: {owner: Finance}}]\n")
    val consumer = add("models/report.sql", "select * from {{ ref('orders') }}")
    add("other/dbt_project.yml", "name: other\n")
    val unrelated = add("other/models/report.sql", "select * from {{ ref('orders') }}")
    new RenameProcessor(getProject, model("orders").element, "customer_orders", false, false).run()
    assertEquals("customer_orders.sql", source.getName)
    assertTrue(consumer.getText.contains("ref('customer_orders')"))
    assertTrue(properties.getText.contains("name: customer_orders"))
    assertTrue(properties.getText.contains("# preserve"))
    assertTrue(source.getText.contains("alias='warehouse_orders'"))
    assertTrue(unrelated.getText.contains("ref('orders')"))
    val undo = com.intellij.openapi.command.undo.UndoManager.getInstance(getProject)
    assertTrue(undo.isUndoAvailable(null))
    val previous = com.intellij.openapi.ui.TestDialogManager.setTestDialog(com.intellij.openapi.ui.TestDialog.OK)
    try undo.undo(null) finally com.intellij.openapi.ui.TestDialogManager.setTestDialog(previous)
    com.intellij.psi.PsiDocumentManager.getInstance(getProject).commitAllDocuments()
    assertEquals("orders.sql", source.getName); assertTrue(consumer.getText.contains("ref('orders')"))
  }
  def testVersionFamilyRenamePreservesDefinedInAndPhysicalAliases(): Unit = {
    project()
    val first = add("models/orders_v1.sql", "select 1 as id")
    val second = add("models/custom_orders.sql", "{{ config(alias='physical_orders') }} select 2 as id")
    val yaml = add("models/schema.yml", "models:\n- name: orders\n  latest_version: 2\n  versions:\n  - v: 1\n  - {v: 2, defined_in: custom_orders}\n")
    val report = add("models/report.sql", "select * from {{ ref('orders', v=1) }} union all select * from {{ ref('orders') }}")
    val selected = w.symbols.find(s => s.isCode && s.name == "orders" && s.version.contains("1")).get
    new RenameProcessor(getProject, selected.element, "customer_orders", false, false).run()
    assertEquals("customer_orders_v1.sql", first.getName); assertEquals("custom_orders.sql", second.getName)
    assertTrue(second.getText.contains("alias='physical_orders'")); assertTrue(yaml.getText.contains("name: customer_orders"))
    assertEquals(2, "ref\\('customer_orders'".r.findAllIn(report.getText).size)
    assertTrue(yaml.getText.contains("defined_in: custom_orders"))
  }
  def testRenameFromMacroYamlCoordinatesDefinitionAndCallers(): Unit = {
    project()
    val source = add("macros/money.sql", "{% macro money(amount) %}{{ amount }}{% endmacro %}\n{% macro other() %}2{% endmacro %}")
    val yaml = add("macros/schema.yml", "macros: [{name: money, description: Converts money}]")
    val report = add("models/report.sql", "select {{ money(1) }} as amount")
    val definition = w.symbols.find(s => s.kind == "macro" && s.isProperty && s.name == "money").get
    new RenameProcessor(getProject, definition.element, "currency", false, false).run()
    assertTrue(source.getText, source.getText.contains("macro currency(amount)"))
    assertTrue(source.getText, source.getText.contains("macro other()"))
    assertTrue(yaml.getText, yaml.getText.contains("name: currency"))
    assertTrue(report.getText, report.getText.contains("currency(1)"))
  }

}
