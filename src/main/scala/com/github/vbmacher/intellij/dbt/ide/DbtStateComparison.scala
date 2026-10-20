package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project.DbtWorkspace
import com.intellij.diff.{DiffContentFactory, DiffManager}
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.application.{ModalityState, ReadAction}
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.Callable
import scala.jdk.CollectionConverters._

private[ide] object DbtStateComparison {
  def show(configuration: DbtRunConfiguration): Unit = {
    val project = configuration.getProject
    ReadAction.nonBlocking(new Callable[Either[String, (Vector[String], Vector[DbtStateChange])]] {
      override def call(): Either[String, (Vector[String], Vector[DbtStateChange])] = {
        val w = DbtWorkspace.get(project)
        for {
          root <- configuration.configuredRoot(w)
          changes <- DbtState.compare(w, root)
        } yield configuration.sourceKey(w) -> changes
      }
    }).expireWith(project).finishOnUiThread(ModalityState.defaultModalityState(), result => result match {
      case Left(error) => Messages.showErrorDialog(project, error, "dbt baseline comparison")
      case Right((_, changes)) if changes.isEmpty => Messages.showInfoMessage(project, "No locally detectable changes.", "dbt baseline comparison")
      case Right((key, changes)) =>
        val choices = changes.map(c => (c.status + ": " + c.id + " · " + c.reason) -> c.id).toMap
        JBPopupFactory.getInstance.createPopupChooserBuilder(choices.keys.toVector.sorted.asJava)
          .setTitle("Compare baseline with current source").setItemChosenCallback((label: String) => {
            ReadAction.nonBlocking(new Callable[Either[String, (String, String)]] {
              override def call(): Either[String, (String, String)] = {
                val w = DbtWorkspace.get(project)
                for {
                  _ <- Either.cond(configuration.sourceKey(w) == key, (), "Source or settings changed. Compare baseline again.")
                  root <- configuration.configuredRoot(w)
                  text <- DbtState.comparison(w, root, choices(label))
                } yield text
              }
            }).expireWith(project).finishOnUiThread(ModalityState.defaultModalityState(), text => text match {
              case Left(error) => Messages.showErrorDialog(project, error, "dbt baseline comparison")
              case Right((before, after)) =>
                val factory = DiffContentFactory.getInstance()
                DiffManager.getInstance.showDiff(project, new SimpleDiffRequest(label, factory.create(before), factory.create(after),
                  "Baseline manifest (recorded)", "Current source (unresolved defaults)"))
            }).submit(AppExecutorUtil.getAppExecutorService)
            ()
          }).createPopup().showCenteredInCurrentWindow(project)
    }).submit(AppExecutorUtil.getAppExecutorService)
    ()
  }
}
