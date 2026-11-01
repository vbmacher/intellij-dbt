package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project.DbtWorkspace
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile

/**
 * Compiles the whole execution project using its configured defaults, without saved model selectors/exclusions.
 * A context file chooses its owner; otherwise the action offers the available execution projects.
 * Workspace preparation runs in a smart read and launches through the native Run configuration.
 */
class DbtCompileProjectAction : DbtEditorAction() {
    override fun update(event: AnActionEvent) {
        val project = event.project
        val available = project != null && DbtWorkspace.get(project).roots.isNotEmpty()
        event.presentation.isVisible = available
        event.presentation.isEnabled = available && project?.getService(DbtRunner::class.java)?.isRunning == false
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE) ?: event.getData(CommonDataKeys.PSI_FILE)?.virtualFile
        DbtUi.saveAndCommit(project)
        DbtUi.readLater(project, smartIn = project, read = { projects(DbtWorkspace.get(project), file) }) { roots ->
            if (roots.size == 1) compile(project, roots.first())
            else if (roots.isNotEmpty()) JBPopupFactory.getInstance().createPopupChooserBuilder(roots)
                .setTitle("Choose dbt Project to Compile").setItemChosenCallback { root -> compile(project, root) }
                .createPopup().showInBestPositionFor(event.dataContext)
        }
    }

    private fun compile(project: Project, root: String) {
        DbtUi.readLater(project, smartIn = project, read = {
            val w = DbtWorkspace.get(project)
            val selection = DbtRunSelection.all(w, root)?.takeIf { it.root == root }
            if (selection == null) Result.failure(IllegalArgumentException("The dbt project is no longer available."))
            else DbtCommand.create(w, selection, "Compile", fullRefresh = false)
        }) { result ->
            result.fold({ DbtRunConfiguration.launch(project, it) }, {
                DbtNotifications.show(project, "Compile dbt Project", it.message.orEmpty(), NotificationType.ERROR)
            })
        }
    }

    companion object {
        /** A context file chooses its owning project; otherwise all execution projects are offered. */
        fun projects(w: DbtWorkspace, file: VirtualFile?): List<String> {
            val owner = file?.let { f ->
                w.roots.filter { VfsUtilCore.isAncestor(it.directory, f, false) }
                    .maxByOrNull { it.directory.path.length }
            }?.let(w::executionRoot)
            return owner?.let { listOf(it.directory.path) }
                ?: w.roots.map(w::executionRoot).map { it.directory.path }.distinct().sorted()
        }
    }
}
