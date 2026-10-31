package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project.{DbtDeclarationSource, DbtSymbol, DbtWorkspace}
import com.intellij.notification.{Notification, NotificationsManager}
import com.intellij.openapi.actionSystem.{ActionUiKind, AnActionEvent, CommonDataKeys, Presentation}
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.{ProgressIndicator, ProgressManager}
import org.junit.Assert._

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

final class DbtResourceActionTest extends DbtTestCase {
  private def notifications: Vector[Notification] = NotificationsManager.getNotificationsManager
    .getNotificationsOfType(classOf[Notification], getProject).toVector.filter(_.getTitle == "dbt action stopped")

  private def resolve(change: (com.intellij.psi.PsiFile, ProgressIndicator) => Unit, expected: Boolean, uncommitted: Boolean = false): Unit = {
    project()
    val file = configure("models/orders.sql", "select 1 as id<caret>")
    if (uncommitted) com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(getProject, new Runnable {
      override def run(): Unit = myFixture.getEditor.getDocument.insertString(0, "-- unsaved\n")
    })
    val entered = new AtomicBoolean(false)
    val release = new AtomicBoolean(false)
    val indicator = new AtomicReference[ProgressIndicator]()
    var performed = Vector.empty[String]
    DbtDeclarationSource.EP.getPoint.registerExtension(new DbtDeclarationSource {
      override def declarations(workspace: DbtWorkspace): Vector[DbtSymbol] = {
        assertFalse("Workspace resolution must run off the UI thread", ApplicationManager.getApplication.isDispatchThread)
        indicator.set(com.intellij.openapi.progress.util.ProgressWrapper.unwrapAll(ProgressManager.getInstance.getProgressIndicator))
        entered.set(true)
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
        while (!release.get()) {
          ProgressManager.checkCanceled()
          assertTrue("The fixture must release resource resolution", System.nanoTime() < deadline)
          java.util.concurrent.locks.LockSupport.parkNanos(1000000L)
        }
        Vector.empty
      }
    }, getTestRootDisposable)
    val action = new DbtResourceAction((_, symbol) => {
      assertTrue(ApplicationManager.getApplication.isDispatchThread)
      assertEquals(myFixture.getEditor.getDocument.getText, symbol.file.getText)
      performed :+= symbol.name
    }) {}
    val data = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, getProject)
      .add(CommonDataKeys.PSI_FILE, file).add(CommonDataKeys.EDITOR, myFixture.getEditor).build()
    val event = AnActionEvent.createEvent(data, new Presentation, "test", ActionUiKind.NONE, null)
    try {
      ApplicationManager.getApplication.invokeLater(() => {
        try {
          await("Resource resolution enters its background read")(entered.get())
          assertTrue("The UI remains responsive while resolution is pending", performed.isEmpty)
          change(file, indicator.get())
        } finally release.set(true)
      })
      action.actionPerformed(event)
      if (expected) {
        await("The unchanged resource action is delivered")(performed.nonEmpty)
        assertEquals(Vector("orders"), performed)
      } else {
        await("The stopped action explains its outcome")(notifications.nonEmpty)
        assertEquals(Vector.empty, performed)
      }
    } finally {
      release.set(true)
      notifications.foreach(_.expire())
    }
  }

  def testResourceResolutionRunsInTheBackgroundAndReturnsToTheUi(): Unit = resolve((_, _) => (), expected = true)

  def testEditingTheSourceDiscardsThePendingAction(): Unit =
    resolve((file, _) => edit(file, "select 2 as changed"), expected = false)

  def testMovingTheCaretDiscardsThePendingAction(): Unit =
    resolve((_, _) => myFixture.getEditor.getCaretModel.moveToOffset(0), expected = false)

  def testCancelingResolutionDoesNotRunTheAction(): Unit = resolve((_, indicator) => indicator.cancel(), expected = false)

  def testPendingEditorTextIsCommittedBeforeResolution(): Unit = resolve((_, _) => (), expected = true, uncommitted = true)
}
