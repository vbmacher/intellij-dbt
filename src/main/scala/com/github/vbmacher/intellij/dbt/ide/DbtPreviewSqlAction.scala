package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project.DbtWorkspace
import com.intellij.openapi.actionSystem.{AnAction, AnActionEvent, CommonDataKeys}
import com.intellij.openapi.application.{ModalityState, ReadAction, WriteIntentReadAction}
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.Callable

final class DbtPreviewSqlAction extends AnAction("Preview selected dbt SQL") {
  override def update(event: AnActionEvent): Unit = event.getPresentation.setEnabledAndVisible(
    event.getProject != null && Option(event.getData(CommonDataKeys.EDITOR)).exists(_.getSelectionModel.hasSelection) &&
      Option(event.getData(CommonDataKeys.PSI_FILE)).exists(_.getName.endsWith(".sql")))
  override def actionPerformed(event: AnActionEvent): Unit = WriteIntentReadAction.run(() => {
    val project = event.getProject; val file = event.getData(CommonDataKeys.PSI_FILE); val editor = event.getData(CommonDataKeys.EDITOR)
    if (project != null && file != null && editor != null) {
      val sql = Option(editor.getSelectionModel.getSelectedText).filter(_.trim.nonEmpty)
      FileDocumentManager.getInstance().saveAllDocuments()
      com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
      val before = ReadAction.computeBlocking[Option[DbtCteContext], RuntimeException](() => {
        val w = DbtWorkspace.get(project)
        w.codeSymbols(file).find(_.kind == "model").flatMap(DbtCteInspection.context(w, _))
      })
      ReadAction.nonBlocking(new Callable[Either[String, DbtCommand]] {
        override def call(): Either[String, DbtCommand] = {
          val w = DbtWorkspace.get(project)
          for {
            text <- sql.toRight("Select SQL to preview.")
            _ <- Either.cond(com.github.vbmacher.intellij.dbt.navigation.DbtNavigation.projectedColumns(text).nonEmpty,
              (), "Select one supported SELECT query to preview. Statements that modify data or schema cannot be previewed.")
            model <- w.codeSymbols(file).find(_.kind == "model").toRight("Choose a dbt source model.")
            _ <- Either.cond(before == DbtCteInspection.context(w, model), (), "Source or execution settings changed; select the query again.")
            command <- DbtCteInspection.command(w, model, text)
          } yield command
        }
      }).expireWith(project).finishOnUiThread(ModalityState.defaultModalityState(), (result: Either[String, DbtCommand]) => {
        val current = ReadAction.computeBlocking[Option[DbtCteContext], RuntimeException](() => {
          val w = DbtWorkspace.get(project)
          w.codeSymbols(file).find(_.kind == "model").flatMap(DbtCteInspection.context(w, _))
        })
        val checked = result.flatMap(command => Either.cond(before.nonEmpty && before == current, command,
          "Source or execution settings changed; no query was launched."))
        checked match {
          case Left(error) => com.intellij.openapi.ui.Messages.showInfoMessage(project, error, "Preview selected dbt SQL")
          case Right(command) =>
            if (project.getService(classOf[DbtRunner]).start(command)) {
              val window = ToolWindowManager.getInstance(project).getToolWindow("dbt")
              if (window != null) { Option(window.getContentManager.findContent("Runner")).foreach(window.getContentManager.setSelectedContent(_)); window.show() }
            }
        }
      }).submit(AppExecutorUtil.getAppExecutorService)
    }
  })
}
