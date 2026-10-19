package com.github.vbmacher.intellij.dbt.ide

import com.intellij.lang.parameterInfo.ParameterInfoHandler
import com.intellij.psi.{PsiElement, PsiFile}
import com.intellij.testFramework.utils.parameterInfo.{MockCreateParameterInfoContext, MockParameterInfoUIContext, MockUpdateParameterInfoContext}
import org.junit.Assert._

final class DbtParameterInfoTest extends DbtCodeInsightTestCase {
  private def macroFixture(): Unit = {
    project()
    add("macros/money.sql", "{% macro money(amount, scale=2, currency='USD') %}{{ amount }}{% endmacro %}")
    add("macros/schema.yml", "macros: [{name: money, arguments: [{name: amount, type: decimal}, {name: scale, type: integer}]}]\n")
  }
  def testNativeParameterInfoActionDispatchCreatesControllerForDbtSql(): Unit = {
    macroFixture()
    val file = configure("models/report.sql", "select {{ money(12, <caret>2) }} as amount")
    val editor = myFixture.getEditor
    val language = com.intellij.psi.util.PsiUtilCore.getLanguageAtOffset(file, myFixture.getCaretOffset)
    val handlers = com.intellij.codeInsight.hint.ShowParameterInfoHandler.getHandlers(getProject,
      language, file.getViewProvider.getBaseLanguage)
    assertTrue("Registered handler for " + language + " / " + file.getFileType.getName,
      handlers.exists(_.isInstanceOf[DbtParameterInfoHandler]))
    myFixture.performEditorAction("ParameterInfo")
    com.intellij.openapi.application.impl.NonBlockingReadActionImpl.waitForAsyncTaskCompletion()
    com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
    val controller = com.intellij.codeInsight.hint.ParameterInfoControllerBase.findControllerAtOffset(editor,
      file.getText.indexOf("money(") + "money".length)
    assertNotNull("The native Parameter Info action must create a hint controller for " + language + " / " + file.getFileType.getName, controller)
    try {
      val signature = controller.getObjects.head.asInstanceOf[DbtMacroSignature]
      assertEquals("money", signature.name)
      assertEquals(Vector("amount: decimal", "scale=2: integer", "currency='USD'"), signature.parameters.map(_._2))
    } finally com.intellij.openapi.util.Disposer.dispose(controller)
  }
  def testNativeNamedArgumentHintHasExactSignatureAnchorAndHighlightRange(): Unit = {
    macroFixture()
    val file = configure("models/report.sql", "{{ money(42, currency='EUR', scale=<caret>3) }}")
    val handler = new DbtParameterInfoHandler
    val create = new DbtParameterInfoCapture(myFixture.getEditor, file)
    val owner = handler.findElementForParameterInfo(create)
    assertSame(file, owner)
    assertEquals(1, create.getItemsToShow.length)
    handler.showParameterInfo(owner, create)
    assertSame(file, create.hinted); assertEquals(file.getText.indexOf("money(") + "money".length, create.anchor)
    assertEquals('(', file.getText.charAt(create.anchor))
    val signature = create.getItemsToShow.head.asInstanceOf[DbtMacroSignature]
    val update = new MockUpdateParameterInfoContext(myFixture.getEditor, file, create.getItemsToShow)
    handler.updateParameterInfo(handler.findElementForUpdatingParameterInfo(update), update)
    assertEquals(1, update.getCurrentParameter)
    val ui = new MockParameterInfoUIContext[PsiElement](file)
    ui.setCurrentParameterIndex(update.getCurrentParameter)
    handler.updateUI(signature, ui)
    val expected = "money(amount: decimal, scale=2: integer, currency='USD')"
    assertEquals(expected, ui.getText)
    assertEquals(expected.indexOf("scale"), ui.getHighlightStart)
    assertEquals(expected.indexOf("scale") + "scale=2: integer".length, ui.getHighlightEnd)
    assertEquals("scale=2: integer", ui.getText.substring(ui.getHighlightStart, ui.getHighlightEnd))
  }
  def testNestedCommaDoesNotAdvanceThePositionalParameterAndLeavingCallClosesHint(): Unit = {
    macroFixture()
    val file = configure("models/report.sql", "{{ money(coalesce(1, 2), <caret>4) }}")
    val handler = new DbtParameterInfoHandler
    val create = new MockCreateParameterInfoContext(myFixture.getEditor, file)
    assertNotNull(handler.findElementForParameterInfo(create))
    var removed = false
    val update = new MockUpdateParameterInfoContext(myFixture.getEditor, file, create.getItemsToShow) {
      override def removeHint(): Unit = { removed = true }
    }
    handler.updateParameterInfo(handler.findElementForUpdatingParameterInfo(update), update)
    assertEquals(1, update.getCurrentParameter)
    myFixture.getEditor.getCaretModel.moveToOffset(file.getTextLength)
    val owner = handler.findElementForUpdatingParameterInfo(update)
    assertNull(owner)
    handler.updateParameterInfo(owner, update)
    assertTrue(removed)
  }
  def testAmbiguousMacroDefinitionsNeverShowAnInventedUniqueSignature(): Unit = {
    project()
    add("macros/one.sql", "{% macro collide(a) %}{{ a }}{% endmacro %}")
    add("macros/two.sql", "{% macro collide(a, b=2) %}{{ a }}{% endmacro %}")
    val file = configure("models/report.sql", "{{ collide(<caret>1) }}")
    val create = new MockCreateParameterInfoContext(myFixture.getEditor, file)
    assertNull(new DbtParameterInfoHandler().findElementForParameterInfo(create))
    assertNull(create.getItemsToShow)
  }
}
