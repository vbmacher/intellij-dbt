package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project._
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.{ModalityState, ReadAction}
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.{ProgressIndicator, Task}
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.{DialogWrapper, Messages}
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiFile
import com.intellij.util.concurrency.AppExecutorUtil
import java.awt.{BorderLayout, Dimension, FlowLayout, GridBagConstraints, Insets}
import java.awt.datatransfer.StringSelection
import java.nio.file.Paths
import java.util.concurrent.Callable
import javax.swing._

/** Presentation and threading helpers: never resolves resources or launches a process. */
private[ide] object DbtUi {
  def name(path: String): String = scala.util.Try(Paths.get(path).getFileName.toString).getOrElse(path)

  /** Reads in a non-blocking read action expiring with `parent` (optionally in smart mode), then shows the result on the EDT. */
  def readLater[A](parent: Disposable, modality: ModalityState = ModalityState.defaultModalityState(),
    smartIn: Option[Project] = None, coalesce: Option[AnyRef] = None)(read: => A)(show: A => Unit): Unit = {
    val action = ReadAction.nonBlocking(new Callable[A] { override def call(): A = read }).expireWith(parent)
    val smart = smartIn.fold(action)(action.inSmartMode)
    coalesce.fold(smart)(smart.coalesceBy(_)).finishOnUiThread(modality, (result: A) => show(result))
      .submit(AppExecutorUtil.getAppExecutorService)
    ()
  }

  /** A cancellable background task; its result is shown on the EDT while the project is open. */
  def background[A](project: Project, title: String)(work: => A)(show: A => Unit): Unit =
    new Task.Backgroundable(project, title, true) {
      private var result: Option[A] = None
      override def run(indicator: ProgressIndicator): Unit = { result = Some(work) }
      override def onSuccess(): Unit = if (!project.isDisposed) result.foreach(show)
      override def onThrowable(error: Throwable): Unit =
        if (!project.isDisposed) Messages.showErrorDialog(project, "Analysis failed or was canceled. Refresh and try again.", title)
    }.queue()

  def read[A](body: => A): A = ReadAction.computeBlocking[A, RuntimeException](() => body)
  def copy(value: String): Unit = CopyPasteManager.getInstance().setContents(new StringSelection(value))
  def open(project: Project, path: String, offset: Int): Unit = Option(LocalFileSystem.getInstance().refreshAndFindFileByPath(path))
    .foreach(f => new OpenFileDescriptor(project, f, offset).navigate(true))

  /** The value shown at the list's selected index, if any. */
  def selected[A](list: JList[_], values: Seq[A]): Option[A] = Some(list.getSelectedIndex).filter(i => i >= 0 && i < values.size).map(values)
  def button(label: String)(action: => Unit): JButton = { val b = new JButton(label); b.addActionListener(_ => action); b }
  def buttons(items: JComponent*): JPanel = { val p = new JPanel(new FlowLayout(FlowLayout.LEFT)); items.foreach(p.add); p }
  def textArea(text: String = "", wordWrap: Boolean = true): JTextArea = {
    val area = new JTextArea(text); area.setEditable(false); area.setLineWrap(true); area.setWrapStyleWord(wordWrap); area
  }
  def border(center: JComponent, north: JComponent = null, south: JComponent = null): JPanel = {
    val panel = new JPanel(new BorderLayout(6, 6))
    Option(north).foreach(panel.add(_, BorderLayout.NORTH)); panel.add(center, BorderLayout.CENTER)
    Option(south).foreach(panel.add(_, BorderLayout.SOUTH)); panel
  }
  def dialog(project: Project, title: String, center: JComponent, north: JComponent = null, south: JComponent = null): Unit = {
    val panel = border(center, north, south); panel.setPreferredSize(new Dimension(850, 550))
    new DialogWrapper(project) {
      setTitle(title); init()
      override def createCenterPanel(): JComponent = panel
    }.show()
  }

  /** Grid rows of a label and a horizontally growing field. */
  def labeledRows(panel: JPanel, insets: Insets, rows: Seq[(String, JComponent)]): Unit = rows.zipWithIndex.foreach {
    case ((label, field), row) =>
      val c = new GridBagConstraints
      c.gridy = row; c.gridx = 0; c.anchor = GridBagConstraints.WEST; c.insets = insets
      panel.add(new JLabel(label), c)
      c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL
      panel.add(field, c)
  }
}

private[ide] object DbtImpactUi {
  /** The graph resource at the caret, the relation owning a column at the caret, or the file's first code resource. */
  def context(w: DbtWorkspace, source: Option[PsiFile], offset: Int): Option[DbtSymbol] = source.flatMap { file =>
    val at = w.symbolAt(file, offset)
    at.find(s => DbtResourceData.GraphKinds(s.kind)).orElse(at.find(_.kind == "column").flatMap(c =>
      w.symbols.filter(s => DbtSymbol.RelationKinds(s.kind) && s.relationName == c.owner && s.namespace == c.namespace &&
        s.version == c.version && w.owner(s) == w.owner(c)).sortBy(_.isProperty).headOption)).orElse(w.codeSymbols(file).headOption)
  }
}
