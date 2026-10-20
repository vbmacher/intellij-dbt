package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.navigation.DbtSqlAnalysis
import com.github.vbmacher.intellij.dbt.project.DbtWorkspace
import com.intellij.openapi.actionSystem.{ActionUpdateThread, AnAction, AnActionEvent, CommonDataKeys}
import com.intellij.openapi.application.{ModalityState, ReadAction, WriteIntentReadAction}
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.Callable

final class DbtPreviewCteAction extends AnAction("Preview dbt CTE") {
  override def getActionUpdateThread: ActionUpdateThread = ActionUpdateThread.BGT
  override def update(event: AnActionEvent): Unit = event.getPresentation.setEnabledAndVisible(
    event.getProject != null && event.getData(CommonDataKeys.EDITOR) != null &&
      Option(event.getData(CommonDataKeys.PSI_FILE)).exists(_.getName.endsWith(".sql")))
  override def actionPerformed(event: AnActionEvent): Unit = WriteIntentReadAction.run(() => {
    val project = event.getProject; val file = event.getData(CommonDataKeys.PSI_FILE); val editor = event.getData(CommonDataKeys.EDITOR)
    if (project != null && file != null && editor != null) {
      val offset = editor.getCaretModel.getOffset
      FileDocumentManager.getInstance().saveAllDocuments()
      com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
      def current(): Option[DbtCteContext] =
        ReadAction.computeBlocking[Option[DbtCteContext], RuntimeException](() => {
          val w = DbtWorkspace.get(project)
          w.codeSymbols(file).find(_.kind == "model").flatMap(DbtCteInspection.context(w, _))
        })
      val expected = current()
      ReadAction.nonBlocking(new Callable[Either[String, (DbtCteContext, DbtCommand)]] {
        override def call(): Either[String, (DbtCteContext, DbtCommand)] = {
          val w = DbtWorkspace.get(project)
          for {
            resource <- w.codeSymbols(file).find(_.kind == "model").toRight("Choose a dbt source model.")
            snapshot <- DbtCteInspection.context(w, resource).toRight("The execution project is unavailable.")
            analysis <- DbtSqlAnalysis.get(w, file).toRight("This SQL cannot be inspected safely.")
            cte <- DbtCteInspection.at(w, file, analysis, offset)
              .toRight("Place the caret at a recognized CTE definition or reference.")
            query <- DbtCteInspection.plan(snapshot.source, analysis, cte)
            command <- DbtCteInspection.command(w, resource, query.sql, query.spans)
          } yield snapshot -> command
        }
      }).expireWith(project).finishOnUiThread(ModalityState.defaultModalityState(), (result: Either[String, (DbtCteContext, DbtCommand)]) => result match {
        case Left(error) => com.intellij.openapi.ui.Messages.showInfoMessage(project, error, "Preview dbt CTE")
        case Right((snapshot, _)) if !expected.contains(snapshot) || !current().contains(snapshot) =>
          com.intellij.openapi.ui.Messages.showInfoMessage(project, "Source or execution settings changed; no query was launched. Invoke Preview CTE again.", "Preview dbt CTE")
        case Right((_, command)) =>
          DbtRunConfiguration.launch(project, command)
      }).submit(AppExecutorUtil.getAppExecutorService)
    }
  })
}
