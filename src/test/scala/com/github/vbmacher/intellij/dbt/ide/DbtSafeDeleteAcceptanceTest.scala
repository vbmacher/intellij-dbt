package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.navigation.DbtSqlAnalysis
import com.github.vbmacher.intellij.dbt.project.{DbtProjectSettings, DbtSettingsState, DbtSymbol, DbtSymbolElement, DbtWorkspace}
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.psi.{PsiDocumentManager, PsiElement, PsiFile}
import com.intellij.refactoring.BaseRefactoringProcessor
import com.intellij.refactoring.safeDelete.SafeDeleteProcessor
import org.junit.Assert._

/** Native Safe Delete must preserve valid SQL, report consumers, and restore the complete edit in one undo. */
final class DbtSafeDeleteAcceptanceTest extends DbtCodeInsightTestCase {
  override protected def tearDown(): Unit = try DbtProjectSettings.get(getProject).loadState(new DbtSettingsState) finally super.tearDown()
  private def w = DbtWorkspace.get(getProject)
  private def cte(file: PsiFile, name: String): DbtSymbolElement = {
    val analysis = DbtSqlAnalysis.get(w, file).get
    val value = analysis.ctes.find(_.name == name).get
    val nameRange = analysis.deletions.find(_._1.getStartOffset == value.range.getStartOffset).get._1
    DbtSymbol("cte", name, w.root(file).get.name, file, nameRange).element
  }
  private def projection(file: PsiFile, name: String): DbtSymbolElement = {
    val value = DbtSqlAnalysis.get(w, file).get.columns.find(_.name == name).get
    DbtSymbol("sql_projection", name, w.root(file).get.name, file, value.range).element
  }
  private def delete(element: PsiElement): Unit = {
    open(element.getContainingFile, element.getTextOffset)
    val processor = SafeDeleteProcessor.createInstance(getProject, null, Array(element), false, false, false)
    processor.setPreviewUsages(false)
    processor.run()
  }
  private def undo(): Unit = {
    val manager = UndoManager.getInstance(getProject)
    val editor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(getProject).getSelectedEditor
    assertTrue("Safe Delete should register one native undo", manager.isUndoAvailable(editor))
    val dialog = com.intellij.openapi.ui.TestDialogManager.setTestDialog(com.intellij.openapi.ui.TestDialog.OK)
    try manager.undo(editor) finally com.intellij.openapi.ui.TestDialogManager.setTestDialog(dialog)
    PsiDocumentManager.getInstance(getProject).commitAllDocuments()
  }
  def testUnusedCteDeletionLeavesValidWithClauseAndUndoRestoresSource(): Unit = {
    project()
    val before = "with unused as (select 0 as id), kept as (select 1 as id) select id from kept"
    val file = add("models/orders.sql", before)
    delete(cte(file, "unused"))
    assertEquals("with kept as (select 1 as id) select id from kept", file.getText.replaceAll("\\s+", " "))
    assertTrue(DbtSqlAnalysis.get(w, file).get.issues.isEmpty)
    undo(); assertEquals(before, file.getText)
  }
  def testUnusedProjectionDeletionPreservesOtherColumnsAndUndo(): Unit = {
    project()
    val before = "select 1 as unused, 2 as kept"
    val file = add("models/orders.sql", before)
    delete(projection(file, "unused"))
    assertEquals("select 2 as kept", file.getText.replaceAll("\\s+", " "))
    assertEquals(Vector("kept"), DbtSqlAnalysis.get(w, file).get.columns.map(_.name))
    undo(); assertEquals(before, file.getText)
  }
  def testUsedCteHasNativeConflictsAndLeavesSourceUnchanged(): Unit = {
    project()
    val before = "with used as (select 1 as id) select id from used"
    val file = add("models/orders.sql", before)
    assertThrows(classOf[BaseRefactoringProcessor.ConflictsInTestsException], () => delete(cte(file, "used")))
    assertEquals(before, file.getText)
  }
  def testDownstreamColumnConsumerBlocksProjectionDeletion(): Unit = {
    project()
    val before = "select 1 as id, 2 as amount"
    val file = add("models/orders.sql", before)
    val downstream = add("models/report.sql", "select amount from {{ ref('orders') }}")
    assertThrows(classOf[BaseRefactoringProcessor.ConflictsInTestsException], () => delete(projection(file, "amount")))
    assertEquals(before, file.getText)
    assertEquals("select amount from {{ ref('orders') }}", downstream.getText)
  }
  def testUnusedModelAndItsYamlPropertiesDeleteTogetherAndUndo(): Unit = {
    project()
    val source = add("models/orders.sql", "select 1 as id")
    add("models/other.sql", "select 2 as id")
    val before = "models:\n- name: orders\n  description: Orders\n- name: other\n  description: Other\n"
    val yaml = add("models/schema.yml", before)
    val root = w.root(source).get
    val model = w.codeSymbols(source).find(_.kind == "model").get
    delete(model.element)
    assertNull(root.directory.findFileByRelativePath("models/orders.sql"))
    assertFalse(yaml.getText.contains("name: orders")); assertTrue(yaml.getText.contains("name: other"))
    undo()
    assertEquals("select 1 as id", root.psiFile("models/orders.sql").get.getText)
    assertEquals(before, yaml.getText)
  }
  def testUnusedMacroDeletesOnlyItsBlockAndUndo(): Unit = {
    project()
    val unused = "{% macro unused() %}1{% endmacro %}"
    val kept = "{% macro kept() %}2{% endmacro %}"
    val before = unused + "\n" + kept + "\n"
    val file = add("macros/helpers.sql", before)
    val definition = w.symbols.find(s => s.kind == "macro" && s.name == "unused").get
    delete(definition.element)
    assertFalse(file.getText.contains("macro unused")); assertTrue(file.getText.contains(kept))
    undo(); assertEquals(before, file.getText)
  }

}
