package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project._
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.fileEditor.FileEditorManager
import org.junit.Assert._

final class DbtToolWindowUxTest extends DbtCodeInsightTestCase {
  def testResourceDocumentationIncludesAllColumnsAndTestsWithoutASidebar(): Unit = {
    project(); add("models/orders.sql", "select 1")
    add("models/schema.yml", "models:\n- name: orders\n  description: Orders\n  columns:\n" +
      (1 to 20).map(i => s"  - name: column_$i\n    description: Description $i\n").mkString + "  data_tests: [unique]\n")
    val w = DbtWorkspace.get(getProject); val model = w.symbols.find(s => s.kind == "model" && s.isCode).get
    val html = DbtDocumentation.render(w, model)
    assertTrue(html.contains("column_20")); assertTrue(html.contains("Description 20")); assertTrue(html.contains("unique"))
    assertFalse(html.contains("open the Lineage documentation sidebar"))
    val target = new DbtBuiltinPsiDocumentationTargetProvider().documentationTarget(model.element, model.element)
    assertNotNull("Declarations support native Quick Documentation", target)
    assertNotNull(target.createPointer().dereference())
  }
  def testStructureViewNavigatesCteDefinitionsAndIgnoresUnrelatedSql(): Unit = {
    project()
    val file = configure("models/orders.sql", "with a as (select 1 as id), b as (select id from a) select * from b<caret>")
    val factory = new DbtSqlStructureViewFactory
    val builder = factory.getStructureViewBuilder(file).asInstanceOf[com.intellij.ide.structureView.TreeBasedStructureViewBuilder]
    val model = builder.createStructureViewModel(myFixture.getEditor)
    try {
      val nodes = model.getRoot.getChildren.map(_.asInstanceOf[com.intellij.ide.structureView.StructureViewTreeElement])
      assertEquals(Vector("a", "b"), nodes.map(_.getPresentation.getPresentableText.split("\\.").last).toVector)
      nodes(1).navigate(true)
      assertEquals(file.getText.indexOf("b as"), myFixture.getCaretOffset)
    } finally model.dispose()
    val plain = add("elsewhere/plain.sql", "select 1")
    assertNull(factory.getStructureViewBuilder(plain))
  }
  def testGeneratedSqlOpensInReadOnlyEditorAndGenerationActionsAreNative(): Unit = {
    project(); val source = configure("models/orders.sql", "select 1<caret>")
    DbtSqlEditors.open(getProject, "orders compiled", "select 2")
    val generated = FileEditorManager.getInstance(getProject).getSelectedFiles.head
    assertFalse(generated.isWritable)
    assertEquals("select 2", new String(generated.contentsToByteArray(), java.nio.charset.StandardCharsets.UTF_8))
    assertEquals("select 1", source.getText)
    Vector("Dbt.GenerateColumns", "Dbt.InheritDescriptions", "Dbt.EditProperties", "Dbt.OpenCompiledSql", "Dbt.CompareSql")
      .foreach(id => assertNotNull(id, ActionManager.getInstance.getAction(id)))
  }
  def testNativeDependencyHierarchyPreservesValueAndFilterOrigins(): Unit = {
    project()
    add("models/base.sql", "select 1 as id, 1 as active")
    val file = add("models/report.sql", "select id from {{ ref('base') }} where active = 1")
    val w = DbtWorkspace.get(getProject); val model = w.codeSymbols(file).head
    val resourceEdges = DbtHierarchy.related(w, model, downstream = false)
    assertTrue(resourceEdges.exists(_._1.name == "base"))
    val column = com.github.vbmacher.intellij.dbt.navigation.DbtSqlAnalysis.get(w, file).get.columns.head
    val symbol = DbtSymbol("sql_projection", column.name, model.namespace, file, column.range, owner = model.name)
    val origins = DbtHierarchy.related(w, symbol, downstream = false)
    assertTrue(origins.exists(_._2.startsWith("value")))
    assertTrue(origins.exists(_._2.startsWith("filter")))
  }
}
