package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.lineage.{DbtDependency, DbtLineage}
import com.github.vbmacher.intellij.dbt.navigation.DbtNavigation
import com.github.vbmacher.intellij.dbt.project.{DbtArtifactContext, DbtProjectSettings, DbtResourceData, DbtSymbol, DbtSymbolElement, DbtWorkspace}
import com.intellij.ide.DataManager
import com.intellij.codeInsight.hint.HintManager
import com.intellij.notification.{NotificationGroupManager, NotificationType}
import com.intellij.diff.{DiffContentFactory, DiffManager}
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.{ActionUpdateThread, AnAction, AnActionEvent, CommonDataKeys, DataContext, PlatformCoreDataKeys}
import com.intellij.openapi.application.{ModalityState, ReadAction}
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.{FileDocumentManager, FileEditorManager, OpenFileDescriptor}
import com.intellij.openapi.fileTypes.{FileType, FileTypeManager}
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.{ProgressIndicator, Task}
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.{JBPopup, JBPopupFactory, ListItemDescriptor}
import com.intellij.openapi.vfs.{LocalFileSystem, VirtualFileManager}
import com.intellij.psi.{PsiDocumentManager, PsiFile}
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.popup.list.GroupedItemsListRenderer
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.{JBUI, WrapLayout}
import java.awt.{BorderLayout, FlowLayout, GridBagConstraints, Insets}
import java.awt.datatransfer.StringSelection
import java.nio.file.Paths
import java.util.concurrent.Callable
import javax.swing._
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
  def caret(context: DataContext): Option[(Project, Option[PsiFile], Int)] =
    Option(CommonDataKeys.PROJECT.getData(context)).map { project =>
      val offset = Option(CommonDataKeys.EDITOR.getData(context)).map(_.getCaretModel.getOffset).getOrElse(0)
      (project, Option(CommonDataKeys.PSI_FILE.getData(context)), offset)
    }
}

/** Computes navigation items at the caret in a background read action and offers them in a popup. */
abstract class DbtPopupAction(title: String) extends DbtEditorAction {
  private[ide] def items(w: DbtWorkspace, file: PsiFile, offset: Int): Vector[DbtNavigationItem]

  protected def nothingFound(project: Project, editor: Editor): Unit = ()

  override def actionPerformed(event: AnActionEvent): Unit = DbtEditorAction.editorContext(event).foreach { case (project, file, editor) =>
    val offset = editor.getCaretModel.getOffset
    DbtUi.readLater(project, smartIn = Some(project))(items(DbtWorkspace.get(project), file, offset)) { choices =>
      if (choices.isEmpty) nothingFound(project, editor)
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

  override protected def nothingFound(project: Project, editor: Editor): Unit =
    if (!editor.isDisposed) HintManager.getInstance().showInformationHint(editor, "No statically resolved dependencies at this location.")
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

  override def actionPerformed(event: AnActionEvent): Unit =
    DbtEditorAction.caret(event.getDataContext).foreach { case (project, source, offset) =>
      val editor = Option(event.getData(CommonDataKeys.EDITOR))
      val component = Option(event.getData(PlatformCoreDataKeys.CONTEXT_COMPONENT))
      val document = source.flatMap(f => Option(f.getViewProvider.getDocument))
      val stamp = document.map(_.getModificationStamp)
      val settings = DbtProjectSettings.get(project).getModificationCount
      val structure = VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS.getModificationCount

      def unchanged: Boolean = source.forall(_.isValid) && document.map(_.getModificationStamp) == stamp &&
        DbtProjectSettings.get(project).getModificationCount == settings &&
        VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS.getModificationCount == structure

      def stopped(message: String): Unit = NotificationGroupManager.getInstance.getNotificationGroup("dbt Workspace")
        .createNotification("dbt action stopped", message, NotificationType.INFORMATION).notify(project)

      DbtUi.background(project, "Resolve dbt resource", canceled = () => stopped("Resource resolution was canceled. Run the action again when ready.")) { indicator =>
        ReadAction.nonBlocking[(Long, Option[(DbtWorkspace, DbtSymbol)])](() => {
          val result = if (!unchanged) None
          else {
            val result = DbtResourceAction.at(project, source, offset)
            result.foreach { case (workspace, symbol) => DbtResourceData.get(workspace, symbol) }
            result
          }
          (PsiModificationTracker.getInstance(project).getModificationCount, result)
        }).expireWith(project).withDocumentsCommitted(project).wrapProgress(indicator).executeSynchronously()
      } { case (modifications, result) =>
        val sameSelection = editor match {
          case Some(e) => !e.isDisposed && e.getCaretModel.getOffset == offset &&
            FileEditorManager.getInstance(project).getSelectedTextEditor == e
          case None => component.forall(c => Option(CommonDataKeys.PSI_FILE.getData(DataManager.getInstance.getDataContext(c))) == source)
        }
        if (!unchanged || !sameSelection || PsiModificationTracker.getInstance(project).getModificationCount != modifications)
          stopped("Source, settings or selection changed during resolution. Run the action again.")
        else result.foreach(perform.tupled)
      }
    }
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

final class DbtOpenCompiledSqlAction extends DbtResourceAction(DbtCompiledSql.show(_, _, compare = false))

final class DbtCompareSqlAction extends DbtResourceAction(DbtCompiledSql.show(_, _, compare = true))

private[ide] object DbtCompiledSql {
  def show(w: DbtWorkspace, symbol: DbtSymbol, compare: Boolean): Unit = {
    def open(current: DbtWorkspace, model: DbtSymbol): Boolean = {
      val data = DbtResourceData.get(current, model)
      data.compiledSql.foreach { sql =>
        if (compare) DbtSqlEditors.compare(current.project, model.name, data.rawSql.getOrElse(model.file.getText), sql, "Authored SQL", "Compiled SQL")
        else DbtSqlEditors.open(current.project, model.name + " compiled", sql)
      }
      data.compiledSql.nonEmpty
    }

    if (!open(w, symbol)) {
      val label = if (compare) "Compile and compare" else "Compile and open"
      val model = w.model(symbol.file).filter(_.isEnabled)
      if (model.isEmpty) DbtNotifications.show(w.project, "dbt compiled SQL", "Compiled SQL is unavailable. Choose a source model to compile.", NotificationType.INFORMATION)
      else if (Messages.showYesNoDialog(w.project, "Compiled SQL is unavailable for this model and target.",
        "dbt compiled SQL", label, "Cancel", Messages.getQuestionIcon) == Messages.YES) {
        val path = symbol.file.getVirtualFile.getPath
        DbtUi.saveAndCommit(w.project)
        DbtUi.readLater(w.project) {
          val current = DbtWorkspace.get(w.project)
          DbtRunSelection.available(current).find(_.path == path).toRight("The source model is unavailable.")
            .flatMap(DbtCommand.create(current, _, "Compile", fullRefresh = false))
        } {
          case Left(error) => DbtNotifications.show(w.project, "dbt compiled SQL", error, NotificationType.ERROR)
          case Right(command) => DbtRunConfiguration.launch(w.project, command, Some(finished =>
            LocalFileSystem.getInstance.refreshIoFiles(java.util.List.of(new java.io.File(finished.artifactDirectory)), true, true, () =>
              DbtUi.readLater(w.project) {
                val current = DbtWorkspace.get(w.project)
                current.model(path).filter(m => current.owner(m).exists(r =>
                  DbtArtifactContext.identity(current.executionRoot(r)) == finished.artifactContext)).map(current -> _)
              } { result =>
                if (!result.exists { case (current, resource) => open(current, resource) })
                  DbtNotifications.show(w.project, "dbt compiled SQL", "Compilation finished, but matching compiled SQL is unavailable. Check the Run output and current source and target.")
              })))
        }
      }
    }
  }
}

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

/** Presentation and threading helpers: never resolves resources or launches a process. */
private[ide] object DbtUi {
  def name(path: String): String = scala.util.Try(Paths.get(path).getFileName.toString).getOrElse(path)

  /**
   * Reads in a cancellable non-blocking read action expiring with `parent`, optionally in smart mode, with committed
   * documents and coalesced with other reads of the same key, then shows the result on the EDT.
   */
  def readLater[A](parent: Disposable, modality: ModalityState = ModalityState.defaultModalityState(),
    smartIn: Option[Project] = None, coalesce: Option[AnyRef] = None, committedIn: Option[Project] = None)(read: => A)(
    show: A => Unit): Unit = {
    val action = ReadAction.nonBlocking(new Callable[A] {
      override def call(): A = read
    }).expireWith(parent)
    val smart = smartIn.fold(action)(action.inSmartMode)
    val committed = committedIn.fold(smart)(smart.withDocumentsCommitted)
    coalesce.fold(committed)(committed.coalesceBy(_)).finishOnUiThread(modality, (result: A) => show(result))
      .submit(AppExecutorUtil.getAppExecutorService)
    ()
  }

  /**
   * A cancellable background task; its result is shown on the EDT while the project is open. Failures are reported by
   * `failed` while the project is open, or by an error notification. `finished` runs after every outcome.
   */
  def background[A](project: Project, title: String, failed: Option[Throwable => Unit] = None, finished: () => Unit = () => (),
    canceled: () => Unit = () => ())(
    work: ProgressIndicator => A)(show: A => Unit): Unit =
    new Task.Backgroundable(project, title, true) {
      private var result: Option[A] = None

      override def run(indicator: ProgressIndicator): Unit = {
        result = Some(work(indicator))
      }

      override def onSuccess(): Unit = if (!project.isDisposed) result.foreach(show)

      override def onCancel(): Unit = if (!project.isDisposed) canceled()

      override def onThrowable(error: Throwable): Unit = if (!project.isDisposed) failed.fold(
        DbtNotifications.show(project, title, "Analysis failed. Refresh and try again.", NotificationType.ERROR))(_(error))

      override def onFinished(): Unit = finished()
    }.queue()

  /** Saves all documents and commits them, so that PSI reads and launched tools see the same source. */
  def saveAndCommit(project: Project): Unit = {
    FileDocumentManager.getInstance.saveAllDocuments()
    PsiDocumentManager.getInstance(project).commitAllDocuments()
  }

  def read[A](body: => A): A = ReadAction.computeBlocking[A, RuntimeException](() => body)

  def copy(value: String): Unit = CopyPasteManager.getInstance().setContents(new StringSelection(value))

  def open(project: Project, path: String, offset: Int): Unit = Option(LocalFileSystem.getInstance().refreshAndFindFileByPath(path))
    .foreach(f => new OpenFileDescriptor(project, f, offset).navigate(true))

  /** The value shown at the list's selected index, if any. */
  def selected[A](list: JList[_], values: Seq[A]): Option[A] =
    Some(list.getSelectedIndex).filter(i => i >= 0 && i < values.size).map(values)

  def button(label: String)(action: => Unit): JButton = {
    val b = new JButton(label);
    b.addActionListener(_ => action);
    b
  }

  def buttons(items: JComponent*): JPanel = {
    val p = new JPanel(new WrapLayout(FlowLayout.LEFT));
    items.foreach(p.add);
    p
  }

  def textArea(text: String = "", wordWrap: Boolean = true): JTextArea = {
    val area = new JTextArea(text);
    area.setEditable(false);
    area.setLineWrap(true);
    area.setWrapStyleWord(wordWrap);
    area
  }

  def border(center: JComponent, north: JComponent = null, south: JComponent = null): JPanel = {
    val panel = new JPanel(new BorderLayout(6, 6))
    Option(north).foreach(panel.add(_, BorderLayout.NORTH));
    panel.add(center, BorderLayout.CENTER)
    Option(south).foreach(panel.add(_, BorderLayout.SOUTH));
    panel
  }

  /** The editable input inside a labeled browse field, scroll pane or settings wrapper. */
  def input(field: JComponent): JComponent = field.getClientProperty("dbt.input") match {
    case target: JComponent => input(target)
    case _ => field match {
      case browse: com.intellij.openapi.ui.TextFieldWithBrowseButton => browse.getTextField
      case scroll: JScrollPane => scroll.getViewport.getView match {
        case view: JComponent => input(view)
        case _ => field
      }
      case spinner: JSpinner => spinner.getEditor match {
        case editor: JSpinner.DefaultEditor => editor.getTextField
        case _ => field
      }
      case _ => field
    }
  }

  /** Grid rows with labels associated with their editable inputs. */
  def labeledRows(panel: JPanel, insets: Insets, rows: Seq[(String, JComponent)]): Unit = rows.zipWithIndex.foreach {
    case ((label, field), row) =>
      val c = new GridBagConstraints
      c.gridy = row;
      c.gridx = 0;
      c.anchor = GridBagConstraints.WEST;
      c.insets = insets
      val hasLabels = rows.exists(_._1.nonEmpty)
      if (hasLabels) {
        val caption = new JLabel(label)
        if (label.nonEmpty) {
          caption.setBorder(JBUI.Borders.emptyRight(4))
          val target = input(field)
          caption.setLabelFor(target)
          field.getAccessibleContext.setAccessibleName(label)
          target.getAccessibleContext.setAccessibleName(label)
        }
        panel.add(caption, c)
      }
      c.gridx = if (hasLabels) 1 else 0;
      c.weightx = 1;
      c.fill = GridBagConstraints.HORIZONTAL
      panel.add(field, c)
  }
}
