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
  def testSelectionIntersectionExcludesAndNamedSelectors(): Unit = {
    project()
    add("models/a.sql", "{{ config(tags=['finance']) }} select 1")
    add("models/b.sql", "select * from {{ ref('a') }}")
    add("models/c.sql", "select 3")
    add("selectors.yml", "selectors:\n- name: finance\n  definition:\n    union:\n    - a+\n    - exclude: b\n")
    assertEquals(Set("a", "b"), DbtSelection.resolve(w, w.roots.head, "a+").resources.map(_.name).toSet)
    assertEquals(Vector("a"), DbtSelection.resolve(w, w.roots.head, "selector:finance").resources.map(_.name))
    assertTrue(DbtSelection.resolve(w, w.roots.head, "unsupported:foo").problems.nonEmpty)
  }
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
  def testOutputColumnRenameAddsAliasWithoutRenamingPhysicalInput(): Unit = {
    project()
    val source = add("models/orders.sql", "select customer_id from {{ source('raw', 'events') }}")
    val yaml = add("models/schema.yml", "models: [{name: orders, columns: [{name: customer_id}]}]\nsources: [{name: raw, tables: [{name: events, columns: [{name: customer_id}]}]}]\n")
    val report = add("models/report.sql", "select customer_id from {{ ref('orders') }}")
    val column = w.columns(model("orders")).find(_.name == "customer_id").get
    new RenameProcessor(getProject, column.element, "customer_key", false, false).run()
    assertEquals("select customer_id as customer_key from {{ source('raw', 'events') }}", source.getText)
    assertEquals("select customer_key from {{ ref('orders') }}", report.getText)
    assertTrue(yaml.getText.contains("events, columns: [{name: customer_id}]"))
  }
  def testNativeCaretTargetsUnusedSqlDefinitionsBeforeTheModel(): Unit = {
    project()
    val file = configure("models/orders.sql", "with un<caret>used as (select 1 as id) select 2 as total")
    val evaluator = new DbtTargetElementEvaluator
    def target = evaluator.adjustReferenceOrReferencedElement(file, myFixture.getEditor, myFixture.getCaretOffset,
      com.intellij.codeInsight.TargetElementUtil.ELEMENT_NAME_ACCEPTED, null).asInstanceOf[DbtSymbolElement]
    assertEquals("cte", target.symbol.kind); assertEquals("unused", target.getName)
    myFixture.getEditor.getCaretModel.moveToOffset(file.getText.indexOf("total") + 1)
    assertEquals("sql_projection", target.symbol.kind); assertEquals("total", target.getName)
    edit(file, "select 1 from (select 2 as id) unused_alias")
    myFixture.getEditor.getCaretModel.moveToOffset(file.getText.indexOf("unused_alias") + 1)
    assertEquals("sql_alias", target.symbol.kind)
  }
  def testQuotedCteRenamePreservesQuotesAndPhysicalColumnNames(): Unit = {
    project()
    val file = add("models/orders.sql", "with \"Orders\" as (select 1 as physical_id) select physical_id from \"Orders\"")
    val definition = DbtSqlAnalysis.get(w, file).get.declarations.find(_.kind == "cte").get
    new RenameProcessor(getProject, definition.element, "customer_orders", false, false).run()
    assertEquals("with \"customer_orders\" as (select 1 as physical_id) select physical_id from \"customer_orders\"", file.getText)
  }
  def testInferredOutputRenameAddsAliasWithoutYaml(): Unit = {
    project()
    add("models/upstream.sql", "select 1 as id")
    val source = add("models/report.sql", "select id from {{ ref('upstream') }}")
    val downstream = add("models/downstream.sql", "select id from {{ ref('report') }}")
    val column = w.columns(model("report")).find(_.name == "id").get
    new RenameProcessor(getProject, column.element, "customer_id", false, false).run()
    assertEquals("select id as customer_id from {{ ref('upstream') }}", source.getText)
    assertEquals("select customer_id from {{ ref('report') }}", downstream.getText)
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
  def testUnsafePhysicalColumnsAndExplicitCteColumnListsHaveNativeConflicts(): Unit = {
    project()
    val yaml = add("models/sources.yml", "sources: [{name: raw, tables: [{name: events, columns: [{name: event_id}]}]}]")
    val column = w.symbols.find(s => s.kind == "column" && s.name == "event_id").get
    val before = yaml.getText
    assertThrows(classOf[com.intellij.refactoring.BaseRefactoringProcessor.ConflictsInTestsException],
      () => new RenameProcessor(getProject, column.element, "event_key", false, false).run())
    assertEquals(before, yaml.getText)
    val file = add("models/report.sql", "with x(x) as (select 1 as value) select * from x")
    val analysis = DbtSqlAnalysis.get(w, file).get
    val cte = analysis.declarations.find(_.kind == "cte").get
    assertEquals(file.getText.indexOf("x(x)"), cte.range.getStartOffset)
    assertTrue(analysis.issues.exists(_.startsWith("Explicit CTE column lists")))
    assertThrows(classOf[com.intellij.refactoring.BaseRefactoringProcessor.ConflictsInTestsException],
      () => new RenameProcessor(getProject, cte.element, "renamed", false, false).run())
    assertTrue(file.getText.startsWith("with x(x)"))
  }
  def testSafeDeleteFromModelPropertiesAlsoDeletesTheSource(): Unit = {
    project()
    val source = add("models/orders.sql", "select 1 as id")
    val yaml = add("models/schema.yml", "models: [{name: orders, description: Orders}, {name: kept}]\n")
    val property = w.symbols.find(s => s.isProperty && s.kind == "model" && s.name == "orders").get
    val processor = com.intellij.refactoring.safeDelete.SafeDeleteProcessor.createInstance(getProject, null, Array(property.element), false, false, false)
    processor.setPreviewUsages(false); processor.run()
    assertFalse(source.isValid)
    assertFalse(yaml.getText.contains("name: orders")); assertTrue(yaml.getText.contains("name: kept"))
  }

}
