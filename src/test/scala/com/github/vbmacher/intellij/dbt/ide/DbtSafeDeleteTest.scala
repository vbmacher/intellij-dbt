package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.navigation.DbtSqlAnalysis
import com.github.vbmacher.intellij.dbt.project.{DbtSymbol, DbtSymbolElement}
import com.intellij.openapi.actionSystem.{ActionManager, AnActionEvent, CommonDataKeys, IdeActions, LangDataKeys, PlatformCoreDataKeys}
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.psi.{PsiDocumentManager, PsiElement, PsiFile}
import com.intellij.refactoring.BaseRefactoringProcessor.ConflictsInTestsException
import com.intellij.refactoring.safeDelete.SafeDeleteProcessor
import com.intellij.testFramework.TestActionEvent
import org.junit.Assert._

/** Native Safe Delete must preserve valid SQL, report consumers, and restore the complete edit in one undo. */
final class DbtSafeDeleteTest extends DbtTestCase {
  private def cte(file: PsiFile, name: String): DbtSymbolElement = {
    val analysis = DbtSqlAnalysis.get(workspace, file).get
    val value = analysis.ctes.find(_.name == name).get
    val nameRange = analysis.deletions.find(_._1.getStartOffset == value.range.getStartOffset).get._1
    DbtSymbol("cte", name, workspace.root(file).get.name, file, nameRange).element
  }

  private def projection(file: PsiFile, name: String): DbtSymbolElement = {
    val value = DbtSqlAnalysis.get(workspace, file).get.columns.find(_.name == name).get
    DbtSymbol("sql_projection", name, workspace.root(file).get.name, file, value.range).element
  }

  private def delete(element: PsiElement): Unit = {
    open(element.getContainingFile, element.getTextOffset)
    val processor = SafeDeleteProcessor.createInstance(getProject, null, Array(element), false, false, false)
    processor.setPreviewUsages(false)
    processor.run()
  }

  private def safeDelete = ActionManager.getInstance.getAction("SafeDelete")

  /** The registered Safe Delete action, updated for a Project view selection of `file`. */
  private def projectViewSafeDelete(file: PsiFile): AnActionEvent = {
    val context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, getProject)
      .add(CommonDataKeys.PSI_ELEMENT, file)
      .add(PlatformCoreDataKeys.PSI_ELEMENT_ARRAY, Array[PsiElement](file))
      .add(LangDataKeys.CONTEXT_LANGUAGES, Array(file.getLanguage)).build()
    val event = TestActionEvent.createTestEvent(safeDelete, context)
    safeDelete.update(event)
    event
  }

  def testUnusedCteDeletionLeavesValidWithClauseAndUndoRestoresSource(): Unit = {
    project()
    val before = "with unused as (select 0 as id), kept as (select 1 as id) select id from kept"
    val file = add("models/orders.sql", before)
    delete(cte(file, "unused"))
    assertEquals("with kept as (select 1 as id) select id from kept", file.getText.replaceAll("\\s+", " "))
    assertTrue(DbtSqlAnalysis.get(workspace, file).get.issues.isEmpty)
    undo();
    assertEquals(before, file.getText)
  }

  def testUnusedProjectionDeletionPreservesOtherColumnsAndUndo(): Unit = {
    project()
    val before = "select 1 as unused, 2 as kept"
    val file = add("models/orders.sql", before)
    delete(projection(file, "unused"))
    assertEquals("select 2 as kept", file.getText.replaceAll("\\s+", " "))
    assertEquals(Vector("kept"), DbtSqlAnalysis.get(workspace, file).get.columns.map(_.name))
    undo();
    assertEquals(before, file.getText)
  }

  def testUsedCteHasNativeConflictsAndLeavesSourceUnchanged(): Unit = {
    project()
    val before = "with used as (select 1 as id) select id from used"
    val file = add("models/orders.sql", before)
    assertThrows(classOf[ConflictsInTestsException], () => delete(cte(file, "used")))
    assertEquals(before, file.getText)
  }

  def testDownstreamColumnConsumerBlocksProjectionDeletion(): Unit = {
    project()
    val before = "select 1 as id, 2 as amount"
    val file = add("models/orders.sql", before)
    val downstream = add("models/report.sql", "select amount from {{ ref('orders') }}")
    assertThrows(classOf[ConflictsInTestsException], () => delete(projection(file, "amount")))
    assertEquals(before, file.getText)
    assertEquals("select amount from {{ ref('orders') }}", downstream.getText)
  }

  def testUnusedModelAndItsYamlPropertiesDeleteTogetherAndUndo(): Unit = {
    project()
    val source = add("models/orders.sql", "select 1 as id")
    add("models/other.sql", "select 2 as id")
    val before = "models:\n- name: orders\n  description: Orders\n- name: other\n  description: Other\n"
    val yaml = add("models/schema.yml", before)
    val root = workspace.root(source).get
    val model = workspace.codeSymbols(source).find(_.kind == "model").get
    delete(model.element)
    assertNull(root.directory.findFileByRelativePath("models/orders.sql"))
    assertFalse(yaml.getText.contains("name: orders"));
    assertTrue(yaml.getText.contains("name: other"))
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
    val definition = workspace.symbols.find(s => s.kind == "macro" && s.name == "unused").get
    delete(definition.element)
    assertFalse(file.getText.contains("macro unused"));
    assertTrue(file.getText.contains(kept))
    undo();
    assertEquals(before, file.getText)
  }

  def testNativeEditorActionDeletesUnusedCteAndOneUndoRestoresIt(): Unit = {
    project()
    val before = "with unused as (select 1 as id), kept as (select 2 as id) select id from kept"
    configure("models/orders.sql", before.replace("unused", "un<caret>used"))
    myFixture.performEditorAction("SafeDelete")
    assertEquals("with kept as (select 2 as id) select id from kept", myFixture.getFile.getText.replaceAll("\\s+", " "))
    myFixture.performEditorAction(IdeActions.ACTION_UNDO)
    PsiDocumentManager.getInstance(getProject).commitAllDocuments()
    assertEquals(before, myFixture.getFile.getText)
  }

  def testNativeEditorActionReportsUsedCteAndPreservesSource(): Unit = {
    project()
    val before = "with used as (select 1 as id) select id from used"
    configure("models/orders.sql", before.replaceFirst("used", "us<caret>ed"))
    assertThrows(classOf[ConflictsInTestsException], () => myFixture.performEditorAction("SafeDelete"))
    assertEquals(before, myFixture.getFile.getText)
  }

  def testNativeProjectFileActionMapsModelIdentityAndBlocksResolvingConsumers(): Unit = {
    project()
    val source = add("models/orders.sql", "select 1 as id")
    val yaml = add("models/schema.yml", "models: [{name: orders, description: Keep}]\n")
    val consumer = add("models/report.sql", "select * from {{ ref('orders') }}")
    val event = projectViewSafeDelete(source)
    assertTrue("Native Project file selection supports Safe Delete", event.getPresentation.isEnabled)
    assertThrows(classOf[ConflictsInTestsException], () => safeDelete.actionPerformed(event))
    assertTrue(source.isValid)
    assertEquals("select 1 as id", source.getText)
    assertEquals("models: [{name: orders, description: Keep}]\n", yaml.getText)
    assertEquals("select * from {{ ref('orders') }}", consumer.getText)
  }

  def testDynamicBuiltinsRefBlocksModelDeletion(): Unit = {
    project()
    val source = add("models/orders.sql", "select 1 as id")
    add("models/report.sql", "select * from {{ builtins.ref(var('model_name')) }}")
    val event = projectViewSafeDelete(source)
    val conflict = assertThrows(classOf[ConflictsInTestsException], () => safeDelete.actionPerformed(event))
    assertTrue(conflict.getMessage, conflict.getMessage.contains("Dynamic consumers exist"))
    assertTrue(source.isValid)
  }

  def testForeignYamlAndPlainTextDoNotGainDbtDeletionTargets(): Unit = {
    project()
    val foreign = configure("services/service.yml", "services: {name: ord<caret>ers}\n")
    assertTrue(DbtSafeDelete.targets(foreign.findElementAt(myFixture.getCaretOffset)).isEmpty)
    assertFalse(new DbtSafeDeleteSupport().isSafeDeleteAvailable(foreign.findElementAt(myFixture.getCaretOffset)))
  }
}
