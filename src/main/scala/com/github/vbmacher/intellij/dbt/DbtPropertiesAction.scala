package com.github.vbmacher.intellij.dbt

import com.intellij.openapi.actionSystem.{AnAction, AnActionEvent, ActionUpdateThread, CommonDataKeys}
import com.intellij.openapi.application.{ModalityState, ReadAction}
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.util.concurrency.AppExecutorUtil
import scala.jdk.CollectionConverters._

final class DbtPropertiesAction extends AnAction {
  override def getActionUpdateThread: ActionUpdateThread = ActionUpdateThread.BGT
  override def actionPerformed(event: AnActionEvent): Unit = {
    val project = event.getProject
    val file = event.getData(CommonDataKeys.PSI_FILE)
    val editor = event.getData(CommonDataKeys.EDITOR)
    if (project == null || file == null || editor == null) return
    val offset = editor.getCaretModel.getOffset
    ReadAction.nonBlocking(new java.util.concurrent.Callable[Vector[DbtSymbol]] {
      override def call(): Vector[DbtSymbol] = {
        val w = DbtWorkspace.get(project)
        val at = DbtNavigation.at(file, offset).flatMap(_.candidates).collect { case e: DbtSymbolElement => e.symbol }
        val own = if (at.nonEmpty) at else w.symbols.filter(s => s.file == file && s.attributes.contains("code"))
        own.flatMap(w.properties).distinct
      }
    }).inSmartMode(project).expireWith(project).finishOnUiThread(ModalityState.defaultModalityState(), (properties: Vector[DbtSymbol]) => {
      val labels = properties.map { s =>
        val document = s.file.getViewProvider.getDocument
        val line = Option(document).map(d => d.getLineNumber(math.min(s.range.getStartOffset, d.getTextLength)) + 1).getOrElse(1)
        s"${s.namespace}.${s.name}${s.version.map(" v" + _).getOrElse("")} — ${s.file.getName}:$line"
      }
      if (labels.nonEmpty) JBPopupFactory.getInstance.createPopupChooserBuilder(labels.asJava).setTitle("dbt Properties")
        .setItemChosenCallback((label: String) => labels.indexOf(label) match {
          case i if i >= 0 => properties(i).element.navigate(true)
          case _ => ()
        }).createPopup().showInBestPositionFor(editor)
    }).submit(AppExecutorUtil.getAppExecutorService)
  }
}
