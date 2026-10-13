package com.github.vbmacher.intellij.dbt

import com.intellij.openapi.actionSystem.{ActionUpdateThread, AnAction, AnActionEvent, CommonDataKeys}
import com.intellij.openapi.application.{ModalityState, ReadAction}
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.{JBPopupFactory, ListItemDescriptor}
import com.intellij.psi.PsiFile
import com.intellij.ui.popup.list.GroupedItemsListRenderer
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI

import java.util.concurrent.Callable
import javax.swing.{Icon, JList}
import scala.jdk.CollectionConverters._

private[dbt] final case class DbtNavigationItem(target: DbtSymbolElement, text: String,
  reference: Boolean = false, separator: Option[String] = None) {
  override def toString: String = text
}

private[dbt] object DbtNavigationItem {
  /** A `file:line` label of an offset. */
  def location(file: PsiFile, offset: Int): String = {
    val line = Option(file.getViewProvider.getDocument).map(d => d.getLineNumber(math.min(offset, d.getTextLength)) + 1).getOrElse(1)
    s"${file.getName}:$line"
  }
}

/** Shows resource icons with their references indented below them. */
private[dbt] final class DbtNavigationItemRenderer extends GroupedItemsListRenderer[DbtNavigationItem](
  new ListItemDescriptor[DbtNavigationItem] {
    override def getTextFor(item: DbtNavigationItem): String = item.text

    override def getTooltipFor(item: DbtNavigationItem): String =
      if (item.reference) item.target.symbol.file.getVirtualFile.getPath
      else s"${item.target.symbol.kind}: ${item.text}"

    override def getIconFor(item: DbtNavigationItem): Icon = if (item.reference) null else item.target.getIcon(0)

    override def hasSeparatorAboveOf(item: DbtNavigationItem): Boolean = item.separator.nonEmpty

    override def getCaptionAboveOf(item: DbtNavigationItem): String = item.separator.orNull
  }) {
  override protected def customizeComponent(list: JList[_ <: DbtNavigationItem], item: DbtNavigationItem,
    selected: Boolean): Unit = {
    myTextLabel.setBorder(JBUI.Borders.emptyLeft(if (item.reference) 28 else 0))
  }
}

/** Computes navigation items at the caret in a background read action and offers them in a popup. */
abstract class DbtPopupAction(title: String) extends AnAction {
  private[dbt] def items(w: DbtWorkspace, file: PsiFile, offset: Int): Vector[DbtNavigationItem]

  protected def nothingFound(project: Project): Unit = ()

  /** Symbols resolved by the reference at the caret. */
  protected def resolvedAt(file: PsiFile, offset: Int): Vector[DbtSymbol] =
    DbtNavigation.at(file, offset).flatMap(_.candidates).collect { case e: DbtSymbolElement => e.symbol }

  override def getActionUpdateThread: ActionUpdateThread = ActionUpdateThread.BGT

  override def actionPerformed(event: AnActionEvent): Unit = {
    val project = event.getProject
    val file = event.getData(CommonDataKeys.PSI_FILE)
    val editor = event.getData(CommonDataKeys.EDITOR)
    if (project == null || file == null || editor == null) return
    val offset = editor.getCaretModel.getOffset
    ReadAction.nonBlocking(new Callable[Vector[DbtNavigationItem]] {
      override def call(): Vector[DbtNavigationItem] = items(DbtWorkspace.get(project), file, offset)
    }).inSmartMode(project).expireWith(project).finishOnUiThread(ModalityState.defaultModalityState(), (choices: Vector[DbtNavigationItem]) =>
      if (choices.isEmpty) nothingFound(project)
      else JBPopupFactory.getInstance.createPopupChooserBuilder(choices.asJava).setTitle(title)
        .setRenderer(new DbtNavigationItemRenderer).setNamerForFiltering((item: DbtNavigationItem) => item.text)
        .setItemChosenCallback((item: DbtNavigationItem) => item.target.navigate(true))
        .createPopup().showInBestPositionFor(editor)
    ).submit(AppExecutorUtil.getAppExecutorService)
  }
}

/** Lists the property declarations of the resource at the caret, or of the current file. */
final class DbtPropertiesAction extends DbtPopupAction("dbt Properties") {
  override private[dbt] def items(w: DbtWorkspace, file: PsiFile, offset: Int): Vector[DbtNavigationItem] = {
    val at = resolvedAt(file, offset)
    (if (at.nonEmpty) at else w.codeSymbols(file)).flatMap(w.properties).distinct.map(s =>
      DbtNavigationItem(s.element, s"${s.element.getPresentation.getPresentableText} — ${DbtNavigationItem.location(s.file, s.range.getStartOffset)}"))
  }
}

/** Lists what the symbol at the caret uses and what uses it, with the call sites of each. */
final class DbtDependenciesAction extends DbtPopupAction("dbt Dependencies") {
  override private[dbt] def items(w: DbtWorkspace, file: PsiFile, offset: Int): Vector[DbtNavigationItem] = {
    val at = resolvedAt(file, offset)
    val current = if (at.nonEmpty) at else w.symbolAt(file, offset) ++ w.codeSymbols(file)
    DbtDependenciesAction.choices(DbtLineage.edges(w), current.map(_.id).toSet)
  }

  override protected def nothingFound(project: Project): Unit =
    Messages.showInfoMessage(project, "No statically resolved dependencies at this location.", "dbt Dependencies")
}

private[dbt] object DbtDependenciesAction {
  def choices(edges: Vector[DbtDependency], selected: Set[String]): Vector[DbtNavigationItem] = Vector(true, false).flatMap { upstream =>
    def other(e: DbtDependency): DbtSymbol = if (upstream) e.to else e.from
    val related = edges.filter(e => selected(if (upstream) e.from.id else e.to.id))
    related.map(other).distinctBy(_.id).zipWithIndex.flatMap { case (target, index) =>
      val references = related.filter(other(_).id == target.id)
      val candidate = if (references.exists(_.ambiguous)) " (candidate)" else ""
      val heading = if (index == 0) Some(if (upstream) "Uses" else "Used by") else None
      DbtNavigationItem(target.element, target.element.getPresentation.getPresentableText + candidate, separator = heading) +:
        references.map { e =>
          val range = e.reference.getRangeInElement
          DbtNavigationItem(DbtSymbol("call site", target.name, target.namespace, e.reference.getElement, range).element,
            DbtNavigationItem.location(e.reference.getElement, range.getStartOffset), reference = true)
        }.distinctBy(_.target.symbol.id)
    }
  }
}
