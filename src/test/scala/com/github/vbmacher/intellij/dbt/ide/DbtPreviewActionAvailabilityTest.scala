package com.github.vbmacher.intellij.dbt.ide

import com.intellij.openapi.actionSystem.{ActionUpdateThread, AnAction, AnActionEvent}
import org.junit.Assert._

final class DbtPreviewActionAvailabilityTest extends DbtCodeInsightTestCase {
  // Let the native editor fixture supply its normal action DataContext. Do not inject PSI_FILE:
  // real editor contexts compute PSI lazily, which requires background action updates.
  private def presentation(action: AnAction) = {
    assertEquals("PSI-dependent preview actions must update in the background", ActionUpdateThread.BGT, action.getActionUpdateThread)
    val probe = new AnAction {
      override def getActionUpdateThread: ActionUpdateThread = action.getActionUpdateThread
      override def update(event: AnActionEvent): Unit = action.update(event)
      override def actionPerformed(event: AnActionEvent): Unit = ()
    }
    myFixture.testAction(probe)
  }

  def testCtePreviewAndInspectionAreAvailableFromTheNativeSqlEditorContext(): Unit = {
    project()
    configure("models/report.sql", "with a as (select 1 as id) select * from <caret>a")
    Vector(new DbtPreviewCteAction, new DbtInspectCtesAction).foreach { action =>
      val state = presentation(action)
      assertTrue(action.getClass.getName, state.isEnabledAndVisible)
    }
    myFixture.getEditor.getSelectionModel.setSelection(0, myFixture.getEditor.getDocument.getTextLength)
    assertTrue(presentation(new DbtPreviewSqlAction).isEnabledAndVisible)
    myFixture.getEditor.getSelectionModel.removeSelection()
    assertFalse(presentation(new DbtPreviewSqlAction).isEnabledAndVisible)
  }

  def testPreviewActionsRemainHiddenInANonSqlEditor(): Unit = {
    project()
    configure("notes.md", "<caret>with a as (select 1) select * from a")
    Vector(new DbtPreviewCteAction, new DbtInspectCtesAction, new DbtPreviewSqlAction).foreach(action =>
      assertFalse(action.getClass.getName, presentation(action).isEnabledAndVisible))
  }
}
