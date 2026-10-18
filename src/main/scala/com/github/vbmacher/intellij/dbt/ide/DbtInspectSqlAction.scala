package com.github.vbmacher.intellij.dbt.ide

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.notification.{NotificationGroupManager, NotificationType}
import com.intellij.openapi.actionSystem.{ActionUpdateThread, AnAction, AnActionEvent, CommonDataKeys}
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.{ProgressIndicator, Task}
import com.intellij.openapi.project.Project
import com.intellij.psi.{PsiDocumentManager, PsiFile}

private[ide] object DbtToolNotifications {
  def show(project: Project, title: String, message: String, error: Boolean = true): Unit =
    if (!project.isDisposed) NotificationGroupManager.getInstance.getNotificationGroup("dbt Workspace")
      .createNotification(title, message, if (error) NotificationType.WARNING else NotificationType.INFORMATION).notify(project)
}

final class DbtInspectSqlAction extends AnAction {
  override def getActionUpdateThread: ActionUpdateThread = ActionUpdateThread.BGT
  override def update(event: AnActionEvent): Unit = event.getPresentation.setEnabledAndVisible(
    Option(event.getProject).nonEmpty && Option(event.getData(CommonDataKeys.PSI_FILE)).exists(_.getName.endsWith(".sql")))
  override def actionPerformed(event: AnActionEvent): Unit =
    for (project <- Option(event.getProject); file <- Option(event.getData(CommonDataKeys.PSI_FILE))) inspect(project, file)

  private[ide] def inspect(project: Project, file: PsiFile): Unit = {
    val snapshot = ReadAction.computeBlocking[Either[String, (DbtToolCommand, String, Long)], RuntimeException](() => {
      val document = PsiDocumentManager.getInstance(project).getDocument(file)
      if (document == null) Left("Open the SQL source document before running SQLFluff.")
      else DbtToolCommand.create(file, lint = true).map(c => (c, document.getText, document.getModificationStamp))
    })
    snapshot match {
      case Left(error) => DbtToolNotifications.show(project, "SQLFluff inspection", error)
      case Right((command, source, stamp)) =>
        file.putUserData(DbtSqlFluff.Result, null)
        new Task.Backgroundable(project, "Inspect dbt SQL with SQLFluff", true) {
          private var result: Either[String, Vector[DbtSqlFluffDiagnostic]] = Left("SQLFluff did not finish.")
          override def run(indicator: ProgressIndicator): Unit = {
            result = new DbtToolProcess().run(command, source, indicator).flatMap { output =>
              if (output.getExitCode != 0 && output.getExitCode != 1)
                Left("SQLFluff failed (exit " + output.getExitCode + "). Check the project configuration. " + output.getStderr.take(4000))
              else DbtSqlFluff.parse(source, output.getStdout, file.getVirtualFile.getPath)
            }
          }
          override def onSuccess(): Unit = if (!project.isDisposed) {
            val current = ReadAction.computeBlocking[Boolean, RuntimeException](() => file.isValid &&
              Option(PsiDocumentManager.getInstance(project).getDocument(file)).exists(d => d.getModificationStamp == stamp && d.getText == source))
            if (!current) DbtToolNotifications.show(project, "SQLFluff inspection", "The document changed during inspection. Rerun Inspect dbt SQL.")
            else result match {
              case Left(error) => DbtToolNotifications.show(project, "SQLFluff inspection", error)
              case Right(diagnostics) =>
                file.putUserData(DbtSqlFluff.Result, DbtSqlFluffResult(source, stamp, diagnostics))
                DaemonCodeAnalyzer.getInstance(project).restart(file, "SQLFluff explicit inspection completed")
                DbtToolNotifications.show(project, "SQLFluff inspection",
                  if (diagnostics.isEmpty) "No SQLFluff violations." else s"${diagnostics.size} diagnostics are available in the editor. Use Alt+Enter for available fixes.", error = false)
            }
          }
          override def onThrowable(error: Throwable): Unit =
            DbtToolNotifications.show(project, "SQLFluff inspection", "Cannot inspect this source: " + error.getMessage)
        }.queue()
    }
  }
}
