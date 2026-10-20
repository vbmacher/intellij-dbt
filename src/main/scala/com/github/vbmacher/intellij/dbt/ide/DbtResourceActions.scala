package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project._
import com.intellij.openapi.actionSystem.{ActionUpdateThread, AnAction, AnActionEvent, CommonDataKeys}
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.ui.Messages

private[ide] abstract class DbtResourceAction extends AnAction {
  override def getActionUpdateThread: ActionUpdateThread = ActionUpdateThread.BGT
  private[ide] def resource(event: AnActionEvent): Option[(DbtWorkspace, DbtSymbol)] = Option(event.getProject).flatMap { project =>
    val w = DbtWorkspace.get(project)
    DbtImpactUi.context(w, Option(event.getData(CommonDataKeys.PSI_FILE)), Option(event.getData(CommonDataKeys.EDITOR))
      .map(_.getCaretModel.getOffset).getOrElse(0)).map(w -> _)
  }
  override def update(event: AnActionEvent): Unit = event.getPresentation.setEnabledAndVisible(resource(event).nonEmpty)
  override def actionPerformed(event: AnActionEvent): Unit =
    ReadAction.computeBlocking[Option[(DbtWorkspace, DbtSymbol)], RuntimeException](() => resource(event)).foreach { case (w, symbol) => perform(w, symbol) }
  protected def perform(w: DbtWorkspace, symbol: DbtSymbol): Unit
}
final class DbtEditPropertiesAction extends DbtResourceAction {
  override protected def perform(w: DbtWorkspace, symbol: DbtSymbol): Unit = DbtPropertiesEditor.show(w, symbol)
}
final class DbtGenerateColumnsAction extends DbtResourceAction {
  override protected def perform(w: DbtWorkspace, symbol: DbtSymbol): Unit = DbtPropertiesEditor.generate(w, symbol, inherit = false)
}
final class DbtInheritDescriptionsAction extends DbtResourceAction {
  override protected def perform(w: DbtWorkspace, symbol: DbtSymbol): Unit = DbtPropertiesEditor.generate(w, symbol, inherit = true)
}
final class DbtOpenCompiledSqlAction extends DbtResourceAction {
  override protected def perform(w: DbtWorkspace, symbol: DbtSymbol): Unit = DbtResourceData.get(w, symbol).compiledSql match {
    case Some(sql) => DbtSqlEditors.open(w.project, symbol.name + " compiled", sql)
    case None => Messages.showInfoMessage(w.project, "Compiled SQL is unavailable. Run Compile or Generate Docs for this project and target.", "dbt compiled SQL")
  }
}
final class DbtCompareSqlAction extends DbtResourceAction {
  override protected def perform(w: DbtWorkspace, symbol: DbtSymbol): Unit = {
    val data = DbtResourceData.get(w, symbol)
    data.compiledSql match {
      case Some(sql) => DbtSqlEditors.compare(w.project, symbol.name, data.rawSql.getOrElse(symbol.file.getText), sql, "Authored SQL", "Compiled SQL")
      case None => Messages.showInfoMessage(w.project, "Compile this model for the matching target before comparing SQL.", "dbt SQL comparison")
    }
  }
}
