package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.lineage.DbtSelection
import com.github.vbmacher.intellij.dbt.project._
import com.intellij.openapi.application.{ModalityState, ReadAction}
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiFile
import com.intellij.usages.{Usage, UsageInfo2UsageAdapter, UsageTarget, UsageView, UsageViewManager, UsageViewPresentation}
import com.intellij.usageView.UsageInfo
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.Callable
import scala.jdk.CollectionConverters._

private[ide] object DbtSelectionResults {
  private def show(project: Project, label: String, resources: Vector[(PsiFile, TextRange)], problems: Vector[String]): UsageView = {
    val presentation = new UsageViewPresentation
    presentation.setTabText(label); presentation.setTabName(label)
    presentation.setDynamicUsagesString("resources"); presentation.setOpenInNewTab(true)
    presentation.setScopeText(if (problems.isEmpty) "Matched resources" else "Incomplete: " + problems.mkString("; "))
    val usages = resources.map { case (file, range) => new UsageInfo2UsageAdapter(new UsageInfo(file,
      range.getStartOffset, range.getEndOffset)): Usage }.toArray
    val view = UsageViewManager.getInstance(project).showUsages(Array.empty[UsageTarget], usages, presentation)
    if (problems.nonEmpty) Messages.showWarningDialog(project, problems.mkString("\n"), label + " — incomplete")
    view
  }
  def review(configuration: DbtRunConfiguration): Unit = {
    val project = configuration.getProject
    ReadAction.nonBlocking(new Callable[Either[String, (Vector[DbtSymbol], Vector[String], Either[String, DbtCommand])]] {
      override def call(): Either[String, (Vector[DbtSymbol], Vector[String], Either[String, DbtCommand])] = {
        val w = DbtWorkspace.get(project)
        configuration.configuredRoot(w).map { root =>
          val selection = if (configuration.scope.isEmpty) DbtRunSelection.all(w, root.directory.getPath)
            else DbtRunSelection.available(w).find(_.path == configuration.scope)
          val expression = DbtSelectionReview.expression(w, root, selection)
          val result = DbtSelection.resolve(w, root, expression, root.runOptions.exclude)
          (result.resources, result.problems, configuration.prepare().map(_._1))
        }
      }
    }).inSmartMode(project).expireWith(project).finishOnUiThread(ModalityState.defaultModalityState(), result => result match {
      case Left(error) => Messages.showErrorDialog(project, error, "dbt selection")
      case Right((resources, problems, command)) =>
        val view = show(project, "dbt local selection · " + configuration.target, resources.map(s => s.file -> s.range), problems)
        view.addButtonToLowerPane(new javax.swing.AbstractAction("Show in Lineage") {
          override def actionPerformed(event: java.awt.event.ActionEvent): Unit =
            DbtLineageSelection.show(project, configuration.root,
              if (configuration.namedSelector.nonEmpty) "selector:" + configuration.namedSelector
              else if (configuration.selectors.nonEmpty) configuration.selectors.mkString(" ")
              else command.toOption.map(_.request.scope).collect { case DbtCommandScope.Selected(terms) => terms.mkString(" ") }.getOrElse(""),
              configuration.exclude.mkString(" "))
        }, false)
        command.foreach { value =>
          val copy = new javax.swing.AbstractAction("Copy command") {
            override def actionPerformed(event: java.awt.event.ActionEvent): Unit =
              DbtImpactUi.copy(com.intellij.util.execution.ParametersListUtil.join(value.arguments.asJava))
          }
          view.addButtonToLowerPane(copy, true)
        }
    }).submit(AppExecutorUtil.getAppExecutorService)
    ()
  }
  def showCli(configuration: DbtRunConfiguration, raw: String): Unit = {
    val project = configuration.getProject
    ReadAction.nonBlocking(new Callable[Either[String, (Vector[DbtCliResource], Vector[String], Vector[String])]] {
      override def call(): Either[String, (Vector[DbtCliResource], Vector[String], Vector[String])] = for {
        ids <- DbtSelectionReview.cliResources(raw)
        root <- configuration.configuredRoot(DbtWorkspace.get(project))
      } yield {
        val known = DbtSelectionReview.knownCliResources(DbtWorkspace.get(project), root)
        (known.filter(s => ids.contains(s.id)), ids.filterNot(id => known.exists(_.id == id)), ids)
      }
    }).expireWith(project).finishOnUiThread(ModalityState.defaultModalityState(), result => result match {
      case Left(error) => Messages.showWarningDialog(project, error, "dbt CLI selection")
      case Right((resources, unknown, ids)) =>
        if (unknown.isEmpty) configuration.selectionResolved.foreach(_(ids))
        show(project, "dbt CLI selection · " + configuration.target, resources.map(s => s.file -> s.range),
          unknown.map("CLI resource unavailable locally; execution blocked: " + _))
    }).submit(AppExecutorUtil.getAppExecutorService)
    ()
  }
}
