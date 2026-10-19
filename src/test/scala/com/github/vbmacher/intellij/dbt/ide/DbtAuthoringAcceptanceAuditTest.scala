package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.lineage.DbtColumnLineage
import com.github.vbmacher.intellij.dbt.navigation.{DbtNavigation, DbtSqlAnalysis}
import com.github.vbmacher.intellij.dbt.project._
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.progress.{EmptyProgressIndicator, ProcessCanceledException, ProgressManager}
import com.intellij.psi.PsiDocumentManager
import org.junit.Assert._
import scala.jdk.CollectionConverters._

/** User-visible regressions found while checking the authoring ticket acceptance criteria. */
final class DbtAuthoringAcceptanceAuditTest extends DbtCodeInsightTestCase {
  override protected def tearDown(): Unit = try DbtProjectSettings.get(getProject).loadState(new DbtSettingsState) finally super.tearDown()
  private def w = DbtWorkspace.get(getProject)
  private def model(name: String) = w.symbols.find(s => s.kind == "model" && s.isCode && s.name == name).get
  private def undo(): Unit = {
    val manager = UndoManager.getInstance(getProject)
    val editor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(getProject).getSelectedEditor
    val target = if (manager.isUndoAvailable(editor)) editor else null
    assertTrue("One native Undo must restore the complete action", manager.isUndoAvailable(target))
    val previous = com.intellij.openapi.ui.TestDialogManager.setTestDialog(com.intellij.openapi.ui.TestDialog.OK)
    try manager.undo(target) finally com.intellij.openapi.ui.TestDialogManager.setTestDialog(previous)
    PsiDocumentManager.getInstance(getProject).commitAllDocuments()
  }

  def testMissingPropertiesScaffoldAppliesAndOneUndoRemovesIt(): Unit = {
    project()
    val source = configure("models/orders.sql", "select 1 as id<caret>")
    var planned: Either[String, DbtPropertyEdit] = Left("not planned")
    WriteAction.run(() => planned = DbtPropertiesEditor.plan(w, model("orders"), "Orders", Vector(DbtColumnData("id", "int", "Identifier")), Vector.empty, Vector.empty))
    val plan = planned.toOption.get
    assertTrue(plan.file.isEmpty)
    assertNull(source.getVirtualFile.getParent.findChild("orders.yml"))
    plan.apply(getProject)
    assertEquals("Orders", DbtResourceData.get(w, model("orders")).description)
    assertEquals(plan.after + "\nColumns: " + w.columns(model("orders")).map(c => (c.name, c.attributes)) +
      "\nMappings: " + w.properties(model("orders")).map(_.mapping.map(_.getText)),
      "Identifier", DbtResourceData.get(w, model("orders")).columns.find(_.name == "id").get.description)
    undo(); assertNull(source.getVirtualFile.getParent.findChild("orders.yml"))
  }

  def testCompletionAndColumnAnalysisHonorCancellation(): Unit = {
    project()
    (1 to 100).foreach(i => add(s"models/orders_$i.sql", "select 1 as id"))
    val file = configure("models/report.sql", "select * from {{ ref('ord<caret>') }}")
    val offset = myFixture.getCaretOffset
    def cancels(action: () => Unit): Unit = {
      val canceled = new java.util.concurrent.atomic.AtomicBoolean
      com.intellij.openapi.application.ApplicationManager.getApplication.executeOnPooledThread(new Runnable {
        override def run(): Unit = {
          val indicator = new EmptyProgressIndicator
          try ProgressManager.getInstance.runProcess(new Runnable {
            override def run(): Unit = {
              indicator.cancel()
              com.intellij.openapi.application.ReadAction.run(() => action())
            }
          }, indicator) catch { case _: ProcessCanceledException => canceled.set(true) }
        }
      }).get(10, java.util.concurrent.TimeUnit.SECONDS)
      assertTrue("Background work must observe cancellation", canceled.get())
    }
    cancels(() => { DbtCompletion.suggestions(file, offset); () })
    cancels(() => { DbtSqlAnalysis.get(w, file); () })
    assertTrue("Cancellation must not poison future completion", DbtCompletion.suggestions(file, myFixture.getCaretOffset).exists(_.text == "orders_1"))
  }


}
