package com.github.vbmacher.intellij.dbt.ide

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.psi.PsiDocumentManager
import org.junit.Assert._
import scala.jdk.CollectionConverters._

final class DbtStaticSuppressionTest extends DbtCodeInsightTestCase {
  private def setup(): Unit = {
    project()
    add("models/orders.sql", "select 1 as id")
    add("macros/money.sql", "{% macro money(amount, scale=2) %}{{ amount }}{% endmacro %}")
    myFixture.enableInspections(new DbtStaticInspection)
  }
  private def diagnostics: Vector[HighlightInfo] = myFixture.doHighlighting().asScala
    .filter(h => Option(h.getDescription).exists(d => d.startsWith("Unknown macro argument") || d.startsWith("Unknown column"))).toVector
  private def nativeSuppression(name: String): IntentionAction = {
    val warning = diagnostics.find(h => h.startOffset <= myFixture.getCaretOffset && h.endOffset >= myFixture.getCaretOffset).get
    val element = myFixture.getFile.findElementAt(myFixture.getCaretOffset)
    val action = warning.findRegisteredQuickFix[IntentionAction]((descriptor, _) =>
      descriptor.getOptions(element, myFixture.getEditor).asScala.find(_.getText == name).orNull)
    assertNotNull("Native inspection options must expose " + name, action)
    action
  }
  private def undo(): Unit = {
    val manager = UndoManager.getInstance(getProject)
    val selected = FileEditorManager.getInstance(getProject).getSelectedEditor
    val editor = if (manager.isUndoAvailable(selected)) selected else null
    assertTrue(manager.isUndoAvailable(editor))
    manager.undo(editor)
    PsiDocumentManager.getInstance(getProject).commitAllDocuments()
  }
  def testNativeStatementSuppressionRemovesColumnAndMacroWarningsAndUndoRestoresThem(): Unit = {
    setup()
    val file = configure("models/report.sql", "select o.miss<caret>ing from {{ ref('orders') }} o where {{ money(1, typo=2) }} = 1")
    val before = file.getText
    assertEquals(2, diagnostics.size)
    assertTrue(diagnostics.forall(_.getSeverity == HighlightSeverity.WARNING))
    myFixture.launchAction(nativeSuppression("Suppress for statement"))
    assertEquals("-- noinspection DbtStaticAnalysis\n" + before, file.getText)
    assertTrue(diagnostics.isEmpty)
    undo()
    assertEquals(before, file.getText)
    assertEquals(2, diagnostics.size)
  }
  def testStatementDirectiveStopsAtSqlSemicolonAndIgnoresQuotedSemicolons(): Unit = {
    setup()
    configure("models/statements.sql", "--noinspection OtherInspection, DbtStaticAnalysis\n" +
      "select ';' as value, {{ money(1, ignored=2) }} as amount;\n" +
      "select {{ money(1, visible<caret>=2) }} as amount")
    val warnings = diagnostics
    assertEquals(1, warnings.size)
    assertEquals("visible", myFixture.getFile.getText.substring(warnings.head.startOffset, warnings.head.endOffset))
  }
  def testNativeFileSuppressionCoversAllStatementsAndOneUndoRestoresSource(): Unit = {
    setup()
    val file = configure("models/statements.sql", "select {{ money(1, first=2) }};\nselect {{ money(1, sec<caret>ond=2) }}")
    val before = file.getText
    assertEquals(2, diagnostics.size)
    myFixture.launchAction(nativeSuppression("Suppress for file"))
    assertEquals("--file: noinspection DbtStaticAnalysis\n" + before, file.getText)
    assertTrue(diagnostics.isEmpty)
    undo()
    assertEquals(before, file.getText)
    assertEquals(2, diagnostics.size)
  }
  def testSuppressionTextInsideSqlStringsAndUnrelatedIdsDoNotHideProblems(): Unit = {
    setup()
    configure("models/literals.sql", "--noinspection DifferentInspection\n" +
      "select '--file: noinspection DbtStaticAnalysis; --noinspection ALL' as label, {{ money(1, ty<caret>po=2) }} as amount")
    assertEquals(1, diagnostics.size)
    assertTrue(diagnostics.head.getDescription.contains("typo"))
  }
  def testFileDirectiveInsideLaterStatementDoesNotSuppressEntireFile(): Unit = {
    setup()
    configure("models/later.sql", "select {{ money(1, first=2) }};\n--file: noinspection DbtStaticAnalysis\nselect {{ money(1, sec<caret>ond=2) }}")
    assertEquals(2, diagnostics.size)
  }
  def testSuppressionActionsAreNotOfferedOutsideDbtSqlResources(): Unit = {
    setup()
    val file = configure("notes.sql", "select <caret>1")
    assertTrue(new DbtStaticInspection().getSuppressActions(file).isEmpty)
  }
  def testNativeSeveritySettingRemainsEffectiveAndCanStillBeSuppressed(): Unit = {
    setup()
    configure("models/severity.sql", "select {{ money(1, ty<caret>po=2) }}")
    val initial = diagnostics.head
    val key = com.intellij.codeInsight.daemon.HighlightDisplayKey.find(initial.getInspectionToolId)
    assertNotNull(key)
    val profile = com.intellij.profile.codeInspection.InspectionProjectProfileManager.getInstance(getProject).getCurrentProfile
    val previous = profile.getErrorLevel(key, myFixture.getFile)
    try {
      profile.setErrorLevel(key, com.intellij.codeHighlighting.HighlightDisplayLevel.ERROR, getProject)
      com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(getProject).restart(myFixture.getFile, "dbt inspection severity changed")
      assertEquals(HighlightSeverity.ERROR, diagnostics.head.getSeverity)
      myFixture.launchAction(nativeSuppression("Suppress for statement"))
      assertTrue(diagnostics.isEmpty)
    } finally profile.setErrorLevel(key, previous, getProject)
  }
}
