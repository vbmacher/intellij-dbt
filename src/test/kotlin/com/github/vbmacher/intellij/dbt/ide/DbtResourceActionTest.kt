package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project.DbtDeclarationSource
import com.github.vbmacher.intellij.dbt.project.DbtSymbol
import com.github.vbmacher.intellij.dbt.project.DbtWorkspace
import com.intellij.notification.Notification
import com.intellij.notification.NotificationsManager
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import org.junit.Assert.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class DbtResourceActionTest : DbtTestCase() {
    private val notifications: List<Notification>
        get() = NotificationsManager.getNotificationsManager().getNotificationsOfType(Notification::class.java, project)
            .filter { it.title == "dbt action stopped" }

    private fun resolve(
        change: (com.intellij.psi.PsiFile, ProgressIndicator) -> Unit,
        expected: Boolean,
        uncommitted: Boolean = false
    ) {
        project()
        val file = configure("models/orders.sql", "select 1 as id<caret>")
        if (uncommitted) com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.insertString(
                0,
                "-- unsaved\n"
            )
        }
        val entered = AtomicBoolean(false);
        val release = AtomicBoolean(false);
        val indicator = AtomicReference<ProgressIndicator>()
        var performed = emptyList<String>()
        DbtDeclarationSource.EP.point.registerExtension(object : DbtDeclarationSource {
            override fun declarations(workspace: DbtWorkspace): List<DbtSymbol> {
                assertFalse(
                    "Workspace resolution must run off the UI thread",
                    ApplicationManager.getApplication().isDispatchThread
                )
                indicator.set(com.intellij.openapi.progress.util.ProgressWrapper.unwrapAll(ProgressManager.getInstance().progressIndicator))
                entered.set(true)
                val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
                while (!release.get()) {
                    ProgressManager.checkCanceled(); assertTrue(
                        "The fixture must release resource resolution",
                        System.nanoTime() < deadline
                    )
                    java.util.concurrent.locks.LockSupport.parkNanos(1000000L)
                }
                return emptyList()
            }
        }, testRootDisposable)
        val action = object : DbtResourceAction({ _, symbol ->
            assertTrue(ApplicationManager.getApplication().isDispatchThread)
            assertEquals(myFixture.editor.document.text, symbol.file.text); performed += symbol.name
        }) {}
        val data = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).add(CommonDataKeys.PSI_FILE, file)
            .add(CommonDataKeys.EDITOR, myFixture.editor).build()
        val event = AnActionEvent.createEvent(data, Presentation(), "test", ActionUiKind.NONE, null)
        try {
            ApplicationManager.getApplication().invokeLater {
                try {
                    await("Resource resolution enters its background read") { entered.get() }
                    assertTrue("The UI remains responsive while resolution is pending", performed.isEmpty()); change(
                        file,
                        indicator.get()
                    )
                } finally {
                    release.set(true)
                }
            }
            action.actionPerformed(event)
            if (expected) {
                await("The unchanged resource action is delivered") { performed.isNotEmpty() }; assertEquals(
                    listOf("orders"),
                    performed
                )
            } else {
                await("The stopped action explains its outcome") { notifications.isNotEmpty() }; assertEquals(
                    emptyList<String>(),
                    performed
                )
            }
        } finally {
            release.set(true); notifications.forEach { it.expire() }
        }
    }

    fun testResourceResolutionRunsInTheBackgroundAndReturnsToTheUi() = resolve({ _, _ -> }, expected = true)
    fun testEditingTheSourceDiscardsThePendingAction() =
        resolve({ file, _ -> edit(file, "select 2 as changed") }, expected = false)

    fun testMovingTheCaretDiscardsThePendingAction() =
        resolve({ _, _ -> myFixture.editor.caretModel.moveToOffset(0) }, expected = false)

    fun testCancelingResolutionDoesNotRunTheAction() = resolve({ _, indicator -> indicator.cancel() }, expected = false)
    fun testPendingEditorTextIsCommittedBeforeResolution() = resolve({ _, _ -> }, expected = true, uncommitted = true)
}
