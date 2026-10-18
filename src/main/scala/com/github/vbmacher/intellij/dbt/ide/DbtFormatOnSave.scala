package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project.DbtWorkspace
import com.intellij.openapi.application.{ApplicationManager, ReadAction}
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.{FileDocumentManager, FileDocumentManagerListener}
import com.intellij.openapi.progress.{ProgressIndicator, Task}
import com.intellij.openapi.project.ProjectLocator
import com.intellij.psi.{PsiDocumentManager, PsiManager}
import java.util.concurrent.ConcurrentHashMap

private[ide] object DbtFormatOnSave {
  private val pending = ConcurrentHashMap.newKeySet[Document]()
  def enabled(project: com.intellij.openapi.project.Project, file: com.intellij.openapi.vfs.VirtualFile): Boolean =
    file.isWritable && file.getExtension == "sql" &&
      DbtWorkspace.rootForFile(project, file).exists(r => r.kind(file).nonEmpty && !r.excluded(file) &&
        r.runOptions.formatOnSave && r.runOptions.formatterExecutable.nonEmpty)

  /** Shared with tests: reject stale output and keep each tool result in one native undo command. */
  def applyText(project: com.intellij.openapi.project.Project, document: Document, before: String, stamp: Long, after: String): Boolean = {
    if (project.isDisposed || !document.isWritable || document.getModificationStamp != stamp || document.getText != before || before == after) false
    else {
      WriteCommandAction.writeCommandAction(project).withName("Format dbt SQL on save").run(new com.intellij.util.ThrowableRunnable[RuntimeException] {
        override def run(): Unit = {
          document.setText(after)
          PsiDocumentManager.getInstance(project).commitDocument(document)
        }
      })
      true
    }
  }
}

/** Save first, format in a cancellable background task, then save only the still-current source. */
final class DbtFormatOnSave extends FileDocumentManagerListener {
  override def beforeDocumentSaving(document: Document): Unit = {
    val manager = FileDocumentManager.getInstance()
    val selected = for {
      file <- Option(manager.getFile(document))
      project <- Option(ProjectLocator.getInstance.guessProjectForFile(file))
      snapshot <- ReadAction.computeBlocking[Option[(com.intellij.openapi.project.Project, DbtToolCommand, String, Long)], RuntimeException](() =>
        if (!project.isDisposed && DbtFormatOnSave.enabled(project, file))
          Option(PsiManager.getInstance(project).findFile(file)).flatMap(psi => DbtToolCommand.create(psi).toOption)
            .map(command => (project, command, document.getText, document.getModificationStamp))
        else None)
    } yield snapshot
    selected.foreach { case (project, command, source, stamp) =>
      if (DbtFormatOnSave.pending.add(document)) ApplicationManager.getApplication.invokeLater(() => {
        if (project.isDisposed) DbtFormatOnSave.pending.remove(document)
        else new Task.Backgroundable(project, "Format dbt SQL on save", true) {
        private var result: Either[String, String] = Left("Formatting did not finish.")
        override def run(indicator: ProgressIndicator): Unit =
          result = DbtFormatting.result(new DbtToolProcess, command, source, indicator)
        override def onSuccess(): Unit = if (!project.isDisposed) result match {
          case Left(error) => DbtToolNotifications.show(project, "dbt format on save", error)
          case Right(after) =>
            if (DbtFormatOnSave.applyText(project, document, source, stamp, after)) manager.saveDocument(document)
        }
        override def onThrowable(error: Throwable): Unit = DbtToolNotifications.show(project, "dbt format on save", error.getMessage)
        override def onFinished(): Unit = { DbtFormatOnSave.pending.remove(document); () }
      }.queue()
      })
    }
  }
}
