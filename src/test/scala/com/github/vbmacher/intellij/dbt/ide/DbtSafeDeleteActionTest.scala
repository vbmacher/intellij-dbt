package com.github.vbmacher.intellij.dbt.ide

import com.intellij.openapi.actionSystem.{ActionManager, CommonDataKeys, LangDataKeys, PlatformCoreDataKeys}
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.psi.{PsiDocumentManager, PsiElement}
import com.intellij.refactoring.BaseRefactoringProcessor
import com.intellij.testFramework.TestActionEvent
import org.junit.Assert._

/** Exercise the registered Safe Delete action rather than supplying synthetic targets to its processor. */
final class DbtSafeDeleteActionTest extends DbtCodeInsightTestCase {
  def testNativeEditorActionDeletesUnusedCteAndOneUndoRestoresIt(): Unit = {
    project()
    val before = "with unused as (select 1 as id), kept as (select 2 as id) select id from kept"
    configure("models/orders.sql", before.replace("unused", "un<caret>used"))
    myFixture.performEditorAction("SafeDelete")
    assertEquals("with kept as (select 2 as id) select id from kept", myFixture.getFile.getText.replaceAll("\\s+", " "))
    myFixture.performEditorAction(com.intellij.openapi.actionSystem.IdeActions.ACTION_UNDO)
    PsiDocumentManager.getInstance(getProject).commitAllDocuments()
    assertEquals(before, myFixture.getFile.getText)
  }

  def testNativeEditorActionReportsUsedCteAndPreservesSource(): Unit = {
    project()
    val before = "with used as (select 1 as id) select id from used"
    configure("models/orders.sql", before.replaceFirst("used", "us<caret>ed"))
    assertThrows(classOf[BaseRefactoringProcessor.ConflictsInTestsException], () => myFixture.performEditorAction("SafeDelete"))
    assertEquals(before, myFixture.getFile.getText)
  }

  def testNativeProjectFileActionMapsModelIdentityAndBlocksResolvingConsumers(): Unit = {
    project()
    val source = add("models/orders.sql", "select 1 as id")
    val yaml = add("models/schema.yml", "models: [{name: orders, description: Keep}]\n")
    val consumer = add("models/report.sql", "select * from {{ ref('orders') }}")
    val action = ActionManager.getInstance.getAction("SafeDelete")
    val context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, getProject)
      .add(CommonDataKeys.PSI_ELEMENT, source)
      .add(PlatformCoreDataKeys.PSI_ELEMENT_ARRAY, Array[PsiElement](source))
      .add(LangDataKeys.CONTEXT_LANGUAGES, Array(source.getLanguage)).build()
    val event = TestActionEvent.createTestEvent(action, context)
    action.update(event)
    assertTrue("Native Project file selection supports Safe Delete", event.getPresentation.isEnabled)
    assertThrows(classOf[BaseRefactoringProcessor.ConflictsInTestsException], () => action.actionPerformed(event))
    assertTrue(source.isValid)
    assertEquals("select 1 as id", source.getText)
    assertEquals("models: [{name: orders, description: Keep}]\n", yaml.getText)
    assertEquals("select * from {{ ref('orders') }}", consumer.getText)
  }

  def testDynamicBuiltinsRefBlocksModelDeletion(): Unit = {
    project()
    val source = add("models/orders.sql", "select 1 as id")
    add("models/report.sql", "select * from {{ builtins.ref(var('model_name')) }}")
    val action = ActionManager.getInstance.getAction("SafeDelete")
    val context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, getProject)
      .add(CommonDataKeys.PSI_ELEMENT, source)
      .add(PlatformCoreDataKeys.PSI_ELEMENT_ARRAY, Array[PsiElement](source))
      .add(LangDataKeys.CONTEXT_LANGUAGES, Array(source.getLanguage)).build()
    val event = TestActionEvent.createTestEvent(action, context)
    action.update(event)
    val conflict = intercept(() => action.actionPerformed(event))
    assertTrue(conflict.getMessage, conflict.getMessage.contains("Dynamic consumers exist"))
    assertTrue(source.isValid)
  }

  private def intercept(run: () => Unit): BaseRefactoringProcessor.ConflictsInTestsException =
    assertThrows(classOf[BaseRefactoringProcessor.ConflictsInTestsException], () => run())

  def testForeignYamlAndPlainTextDoNotGainDbtDeletionTargets(): Unit = {
    project()
    val foreign = configure("services/service.yml", "services: {name: ord<caret>ers}\n")
    assertTrue(DbtSafeDelete.targets(foreign.findElementAt(myFixture.getCaretOffset)).isEmpty)
    assertFalse(new DbtSafeDeleteSupport().isSafeDeleteAvailable(foreign.findElementAt(myFixture.getCaretOffset)))
  }
}
