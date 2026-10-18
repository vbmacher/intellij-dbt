package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project.DbtWorkspace
import com.intellij.formatting.service.{AsyncDocumentFormattingService, AsyncFormattingRequest, FormattingService}
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.{EmptyProgressIndicator, ProgressManager}
import com.intellij.psi.{PsiDocumentManager, PsiFile}

private[ide] object DbtFormatting {
  def result(process: DbtToolProcess, command: DbtToolCommand, before: String,
    indicator: com.intellij.openapi.progress.ProgressIndicator): Either[String, String] =
    process.run(command, before, indicator).flatMap { output =>
      if (output.getExitCode != 0) Left("Formatter failed (exit " + output.getExitCode + "). Check the project configuration. " +
        output.getStderr.take(4000))
      else if (output.getStdout.trim.isEmpty && before.trim.nonEmpty) Left("The formatter returned empty SQL; source is unchanged.")
      else Right(output.getStdout)
    }
}

final class DbtFormattingService extends AsyncDocumentFormattingService {
  override def getName: String = "dbt SQL formatter"
  override def getNotificationGroupId: String = "dbt Workspace"
  override def getFeatures: java.util.Set[FormattingService.Feature] = java.util.Set.of(FormattingService.Feature.AD_HOC_FORMATTING)
  override def canFormat(file: PsiFile): Boolean = file.getName.endsWith(".sql") && file.isWritable &&
    DbtWorkspace.rootForFile(file.getProject, file.getVirtualFile).exists(r => r.resource(file) &&
      !r.excluded(file.getVirtualFile) && r.runOptions.formatterExecutable.nonEmpty)
  override def createFormattingTask(request: AsyncFormattingRequest): AsyncDocumentFormattingService.FormattingTask = {
    val file = request.getContext.getContainingFile
    val before = request.getDocumentText
    val command = ReadAction.computeBlocking[Either[String, DbtToolCommand], RuntimeException](() => DbtToolCommand.create(file))
    val process = new DbtToolProcess
    new AsyncDocumentFormattingService.FormattingTask {
      override def run(): Unit = {
        val indicator = Option(ProgressManager.getInstance.getProgressIndicator).getOrElse(new EmptyProgressIndicator)
        command.flatMap(DbtFormatting.result(process, _, before, indicator)) match {
          case Left(error) => request.onError("dbt formatter", error)
          case Right(text) =>
            val current = ReadAction.computeBlocking[Boolean, RuntimeException](() => !file.getProject.isDisposed &&
              Option(PsiDocumentManager.getInstance(file.getProject).getDocument(file)).exists(_.getText == before))
            if (current) request.onTextReady(text)
            else request.onError("dbt formatter", "The document changed during formatting. Run Reformat Code again; source is unchanged.")
        }
      }
      override def cancel(): Boolean = { process.cancel(); true }
      override def isRunUnderProgress: Boolean = true
    }
  }
}
