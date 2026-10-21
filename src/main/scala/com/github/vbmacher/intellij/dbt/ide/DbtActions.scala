package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.lineage.{DbtDependency, DbtLineage}
import com.github.vbmacher.intellij.dbt.navigation.DbtNavigation
import com.github.vbmacher.intellij.dbt.project.{DbtResourceData, DbtSymbol, DbtSymbolElement, DbtWorkspace}
import com.intellij.diff.{DiffContentFactory, DiffManager}
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.actionSystem.{ActionUpdateThread, AnAction, AnActionEvent, CommonDataKeys, DataContext}
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.{FileType, FileTypeManager}
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.{JBPopup, JBPopupFactory, ListItemDescriptor}
import com.intellij.psi.PsiFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.popup.list.GroupedItemsListRenderer
import com.intellij.util.ui.JBUI

import javax.swing.{Icon, JList}
import scala.jdk.CollectionConverters._

private[ide] final case class DbtNavigationItem(target: DbtSymbolElement, text: String,
  reference: Boolean = false, separator: Option[String] = None) {
  override def toString: String = text
}

private[ide] object DbtNavigationItem {
  /** A `file:line` label of an offset. */
  def location(file: PsiFile, offset: Int): String = {
    val line = Option(file.getViewProvider.getDocument).map(d => d.getLineNumber(math.min(offset, d.getTextLength)) + 1).getOrElse(1)
    s"${file.getName}:$line"
  }

  /** A declaration labelled with its location. */
  def declaration(target: DbtSymbolElement, separator: Option[String] = None): DbtNavigationItem =
    DbtNavigationItem(target, s"${target.getPresentation.getPresentableText} — ${location(target.symbol.file, target.getTextOffset)}",
      separator = separator)

  def popup(title: String, items: Vector[DbtNavigationItem]): JBPopup =
    JBPopupFactory.getInstance.createPopupChooserBuilder(items.asJava).setTitle(title)
      .setRenderer(new DbtNavigationItemRenderer).setNamerForFiltering((item: DbtNavigationItem) => item.text)
      .setItemChosenCallback((item: DbtNavigationItem) => item.target.navigate(true))
      .createPopup()
}

/** Shows resource icons with their references indented below them. */
private[ide] final class DbtNavigationItemRenderer extends GroupedItemsListRenderer[DbtNavigationItem](
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

/** Editor actions update on a background thread. */
private[ide] abstract class DbtEditorAction extends AnAction {
  override def getActionUpdateThread: ActionUpdateThread = ActionUpdateThread.BGT
}

private[ide] object DbtEditorAction {
  /** The project and PSI file of an event, when both are available. */
  def fileContext(event: AnActionEvent): Option[(Project, PsiFile)] =
    for (project <- Option(event.getProject); file <- Option(event.getData(CommonDataKeys.PSI_FILE))) yield (project, file)

  /** The project, PSI file and editor of an event, when all are available. */
  def editorContext(event: AnActionEvent): Option[(Project, PsiFile, Editor)] =
    fileContext(event).flatMap { case (project, file) => Option(event.getData(CommonDataKeys.EDITOR)).map((project, file, _)) }

  /** Whether an event has a project and a SQL file, and an editor if `editor`. */
  def sql(event: AnActionEvent, editor: Boolean = false): Boolean =
    (if (editor) editorContext(event).map(_._2) else fileContext(event).map(_._2)).exists(_.getName.endsWith(".sql"))

  /** The project of a data context with its PSI file and the editor caret offset, or 0 without an editor. */
  def caret(context: DataContext): Option[(Project, Option[PsiFile], Int)] = Option(CommonDataKeys.PROJECT.getData(context)).map { project =>
    val offset = Option(CommonDataKeys.EDITOR.getData(context)).map(_.getCaretModel.getOffset).getOrElse(0)
    (project, Option(CommonDataKeys.PSI_FILE.getData(context)), offset)
  }
}

/** Computes navigation items at the caret in a background read action and offers them in a popup. */
abstract class DbtPopupAction(title: String) extends DbtEditorAction {
  private[ide] def items(w: DbtWorkspace, file: PsiFile, offset: Int): Vector[DbtNavigationItem]

  protected def nothingFound(project: Project): Unit = ()

  override def actionPerformed(event: AnActionEvent): Unit = DbtEditorAction.editorContext(event).foreach { case (project, file, editor) =>
    val offset = editor.getCaretModel.getOffset
    DbtUi.readLater(project, smartIn = Some(project))(items(DbtWorkspace.get(project), file, offset)) { choices =>
      if (choices.isEmpty) nothingFound(project)
      else DbtNavigationItem.popup(title, choices).showInBestPositionFor(editor)
    }
  }
}

/** Lists the property declarations of the resource at the caret, or of the current file. */
final class DbtPropertiesAction extends DbtPopupAction("dbt Properties") {
  override private[ide] def items(w: DbtWorkspace, file: PsiFile, offset: Int): Vector[DbtNavigationItem] = {
    val at = DbtNavigation.resolvedAt(file, offset)
    (if (at.nonEmpty) at else w.codeSymbols(file)).flatMap(w.properties).distinct.map(s => DbtNavigationItem.declaration(s.element))
  }
}

/** Lists what the symbol at the caret uses and what uses it, with the call sites of each. */
final class DbtDependenciesAction extends DbtPopupAction("dbt Dependencies") {
  override private[ide] def items(w: DbtWorkspace, file: PsiFile, offset: Int): Vector[DbtNavigationItem] = {
    val at = DbtNavigation.resolvedAt(file, offset)
    val current = if (at.nonEmpty) at else w.symbolAt(file, offset) ++ w.codeSymbols(file)
    DbtDependenciesAction.choices(DbtLineage.edges(w), current.map(_.id).toSet)
  }

  override protected def nothingFound(project: Project): Unit =
    Messages.showInfoMessage(project, "No statically resolved dependencies at this location.", "dbt Dependencies")
}

private[ide] object DbtDependenciesAction {
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

/** Acts on the resource at the caret, or of the current file. */
private[ide] abstract class DbtResourceAction(perform: (DbtWorkspace, DbtSymbol) => Unit) extends DbtEditorAction {
  private def resource(event: AnActionEvent): Option[(DbtWorkspace, DbtSymbol)] =
    DbtEditorAction.caret(event.getDataContext).flatMap { case (project, file, offset) => DbtResourceAction.at(project, file, offset) }

  override def update(event: AnActionEvent): Unit = event.getPresentation.setEnabledAndVisible(resource(event).nonEmpty)

  override def actionPerformed(event: AnActionEvent): Unit = DbtUi.read(resource(event)).foreach(perform.tupled)
}

private[ide] object DbtResourceAction {
  /** The resource at an offset of a file, or of the file itself. */
  def at(project: Project, file: Option[PsiFile], offset: Int): Option[(DbtWorkspace, DbtSymbol)] = {
    val w = DbtWorkspace.get(project)
    at(w, file, offset).map(w -> _)
  }

  /** The graph resource at the caret, the relation owning a column at the caret, or the file's first code resource. */
  def at(w: DbtWorkspace, source: Option[PsiFile], offset: Int): Option[DbtSymbol] = source.flatMap { file =>
    val here = w.symbolAt(file, offset)
    here.find(s => DbtResourceData.GraphKinds(s.kind))
      .orElse(here.find(_.kind == "column").flatMap(w.relations(_).sortBy(_.isProperty).headOption))
      .orElse(w.codeSymbols(file).headOption)
  }
}

final class DbtEditPropertiesAction extends DbtResourceAction(DbtPropertiesEditor.show)

final class DbtGenerateColumnsAction extends DbtResourceAction(DbtPropertiesEditor.generate(_, _, inherit = false))

final class DbtInheritDescriptionsAction extends DbtResourceAction(DbtPropertiesEditor.generate(_, _, inherit = true))

final class DbtOpenCompiledSqlAction extends DbtResourceAction((w, symbol) => DbtResourceData.get(w, symbol).compiledSql match {
  case Some(sql) => DbtSqlEditors.open(w.project, symbol.name + " compiled", sql)
  case None => Messages.showInfoMessage(w.project,
    "Compiled SQL is unavailable. Run Compile or Generate Docs for this project and target.", "dbt compiled SQL")
})

final class DbtCompareSqlAction extends DbtResourceAction((w, symbol) => {
  val data = DbtResourceData.get(w, symbol)
  data.compiledSql match {
    case Some(sql) =>
      DbtSqlEditors.compare(w.project, symbol.name, data.rawSql.getOrElse(symbol.file.getText), sql, "Authored SQL", "Compiled SQL")
    case None =>
      Messages.showInfoMessage(w.project, "Compile this model for the matching target before comparing SQL.", "dbt SQL comparison")
  }
})

/** Read-only SQL shown in editors and diffs. */
private[ide] object DbtSqlEditors {
  private def sqlType: FileType = FileTypeManager.getInstance.getFileTypeByExtension("sql")

  def open(project: Project, name: String, sql: String): Unit = {
    val file = new LightVirtualFile(name.stripSuffix(".sql") + ".sql", sqlType, sql)
    file.setWritable(false)
    FileEditorManager.getInstance(project).openFile(file, true)
    ()
  }

  def compare(project: Project, name: String, before: String, after: String, left: String, right: String): Unit = {
    val factory = DiffContentFactory.getInstance()
    DiffManager.getInstance.showDiff(project, new SimpleDiffRequest(name,
      factory.create(project, before, sqlType), factory.create(project, after, sqlType), left, right))
  }
}
