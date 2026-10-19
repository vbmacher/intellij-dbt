package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project.{DbtColumnData, DbtWorkspace}
import com.github.vbmacher.intellij.dbt.syntax.DbtYaml
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.psi.{PsiDocumentManager, PsiErrorElement, PsiFile, PsiFileFactory}
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.YAMLFileType
import org.jetbrains.yaml.psi.YAMLMapping
import org.junit.Assert._
import scala.jdk.CollectionConverters._

final class DbtPropertiesFlowMappingTest extends DbtCodeInsightTestCase {
  private def parsed(text: String): PsiFile = {
    val file = PsiFileFactory.getInstance(getProject).createFileFromText("review.yml", YAMLFileType.YML, text)
    val errors = PsiTreeUtil.findChildrenOfType(file, classOf[PsiErrorElement]).asScala.toVector
    assertTrue(text + "\nParser errors: " + errors.map(_.getErrorDescription).mkString(", "), errors.isEmpty)
    file
  }
  private def orders(file: PsiFile): YAMLMapping = DbtYaml.items(DbtYaml.mapping(file).get, "models")
    .find(m => DbtYaml.string(m, "name").contains("orders")).get
  private def undo(): Unit = {
    val editor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(getProject).getSelectedEditor
    val manager = UndoManager.getInstance(getProject)
    assertTrue(manager.isUndoAvailable(editor))
    val dialog = com.intellij.openapi.ui.TestDialogManager.setTestDialog(com.intellij.openapi.ui.TestDialog.OK)
    try manager.undo(editor) finally com.intellij.openapi.ui.TestDialogManager.setTestDialog(dialog)
    PsiDocumentManager.getInstance(getProject).commitAllDocuments()
  }

  def testAddingDescriptionsToExistingFlowColumnsPreservesValidYamlFieldsCommentsAndUndo(): Unit = {
    project()
    add("models/orders.sql", "select 1 as id, 2 as gross_amount, 'kept' as kept")
    val before = """# retain file comment
version: 2
models:
  - name: orders
    description: Existing model
    meta: {owner: Finance}
    columns:
      - {name: id, data_type: integer, meta: {flag: keep}} # identity comment
      - {name: gross_amount, # retain amount comment
         data_type: decimal,}
      - {name: kept, data_type: varchar, description: Preserved}
  - {name: unrelated, description: Unchanged, meta: {custom: retained}}
"""
    val source = add("models/schema.yml", before)
    parsed(before)
    val w = DbtWorkspace.get(getProject)
    val resource = w.symbols.find(s => s.isCode && s.name == "orders").get
    var result: Either[String, DbtPropertyEdit] = Left("not calculated")
    WriteAction.run(() => result = DbtPropertiesEditor.plan(w, resource, "Existing model", Vector(
      DbtColumnData("id", "integer", "Order identifier"), DbtColumnData("gross_amount", "decimal", ""),
      DbtColumnData("kept", "varchar", "Preserved")), Vector.empty, Vector.empty))
    val plan = result.fold(error => throw new AssertionError(error), identity)
    assertEquals("Review must not change source", before, source.getText)
    val mapping = orders(parsed(plan.after))
    val columns = DbtYaml.items(mapping, "columns")
    assertEquals(Vector("id", "gross_amount", "kept"), columns.flatMap(DbtYaml.string(_, "name")))
    assertEquals(Vector("integer", "decimal", "varchar"), columns.flatMap(DbtYaml.string(_, "data_type")))
    assertEquals(Vector("Order identifier", "", "Preserved"), columns.flatMap(DbtYaml.string(_, "description")))
    assertEquals(Vector("name", "data_type", "meta", "description"), columns.head.getKeyValues.asScala.map(_.getKeyText).toVector)
    assertEquals(Some("keep"), DbtYaml.map(columns.head, "meta").flatMap(DbtYaml.string(_, "flag")))
    assertEquals(Some("Finance"), DbtYaml.map(mapping, "meta").flatMap(DbtYaml.string(_, "owner")))
    Vector("# retain file comment", "# identity comment", "# retain amount comment",
      "{name: unrelated, description: Unchanged, meta: {custom: retained}}").foreach(value => assertTrue(plan.after, plan.after.contains(value)))
    open(source, 0)
    plan.apply(getProject)
    assertEquals(plan.after, source.getText)
    assertEquals(Some("Order identifier"), DbtYaml.string(DbtYaml.items(orders(parsed(source.getText)), "columns").head, "description"))
    undo()
    assertEquals("One undo restores the entire prior YAML", before, source.getText)
    parsed(source.getText)
  }

  def testAddingMultipleResourceKeysToFlowMappingRetainsSeparatorsAndCustomFields(): Unit = {
    project()
    add("models/orders.sql", "select 1 as id")
    val source = add("models/schema.yml", "models: [{name: orders, columns: [{name: id, data_type: integer}], meta: {custom: retained},}]\n")
    val w = DbtWorkspace.get(getProject)
    val resource = w.symbols.find(s => s.isCode && s.name == "orders").get
    var result: Either[String, DbtPropertyEdit] = Left("not calculated")
    WriteAction.run(() => result = DbtPropertiesEditor.plan(w, resource, "Orders", Vector(DbtColumnData("id", "integer", "Identifier")),
      Vector("finance"), Vector("unique"), updateTags = true, updateTests = true))
    val plan = result.fold(error => throw new AssertionError(error), identity)
    val mapping = orders(parsed(plan.after))
    assertEquals(Some("Orders"), DbtYaml.string(mapping, "description"))
    assertEquals(Vector("finance"), DbtYaml.strings(mapping, "tags"))
    assertEquals(Vector("unique"), DbtYaml.strings(mapping, "data_tests"))
    assertEquals(Some("retained"), DbtYaml.map(mapping, "meta").flatMap(DbtYaml.string(_, "custom")))
    assertEquals(Some("id"), DbtYaml.items(mapping, "columns").headOption.flatMap(DbtYaml.string(_, "name")))
    assertEquals("Review is read-only", plan.before, source.getText)
  }
}
