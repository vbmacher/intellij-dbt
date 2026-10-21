package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project.DbtWorkspace
import com.intellij.openapi.actionSystem.{AnActionEvent, CommonDataKeys}
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.{VfsUtilCore, VirtualFile}

import scala.jdk.CollectionConverters._

/** Compile the whole execution project using its configured execution defaults. */
final class DbtCompileProjectAction extends DbtEditorAction {
  override def update(event: AnActionEvent): Unit = {
    val project = Option(event.getProject)
    val available = project.exists(p => DbtWorkspace.get(p).roots.nonEmpty)
    event.getPresentation.setVisible(available)
    event.getPresentation.setEnabled(available && project.exists(p => !p.getService(classOf[DbtRunner]).isRunning))
  }

  override def actionPerformed(event: AnActionEvent): Unit = Option(event.getProject).foreach { project =>
    val file = Option(event.getData(CommonDataKeys.VIRTUAL_FILE))
      .orElse(Option(event.getData(CommonDataKeys.PSI_FILE)).flatMap(f => Option(f.getVirtualFile)))
    DbtUi.saveAndCommit(project)
    DbtUi.readLater(project, smartIn = Some(project)) {
      DbtCompileProjectAction.projects(DbtWorkspace.get(project), file)
    } { roots =>
      if (roots.size == 1) compile(project, roots.head)
      else if (roots.nonEmpty) JBPopupFactory.getInstance.createPopupChooserBuilder(roots.asJava)
        .setTitle("Choose dbt Project to Compile")
        .setItemChosenCallback((root: String) => compile(project, root))
        .createPopup().showInBestPositionFor(event.getDataContext)
    }
  }

  private def compile(project: Project, root: String): Unit = DbtUi.readLater(project, smartIn = Some(project)) {
    val w = DbtWorkspace.get(project)
    DbtRunSelection.all(w, root).filter(_.root == root).toRight("The dbt project is no longer available.")
      .flatMap(DbtCommand.create(w, _, "Compile", fullRefresh = false))
  } {
    case Right(command) => DbtRunConfiguration.launch(project, command)
    case Left(error) => Messages.showErrorDialog(project, error, "Compile dbt Project")
  }
}

private[ide] object DbtCompileProjectAction {
  /** A context file chooses its owning project; otherwise all execution projects are offered. */
  def projects(w: DbtWorkspace, file: Option[VirtualFile]): Vector[String] = {
    val owner = file.flatMap(f => w.roots.filter(r => VfsUtilCore.isAncestor(r.directory, f, false))
      .sortBy(_.directory.getPath.length).lastOption).map(w.executionRoot)
    owner.map(r => Vector(r.directory.getPath)).getOrElse(
      w.roots.map(w.executionRoot).map(_.directory.getPath).distinct.sorted)
  }
}
