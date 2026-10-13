package com.github.vbmacher.intellij.dbt

import com.intellij.openapi.actionSystem.{AnAction, AnActionEvent, CommonDataKeys}
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.psi.PsiElement
import scala.collection.mutable
import scala.jdk.CollectionConverters._

private[dbt] final case class DbtDependency(from: DbtSymbol, to: DbtSymbol, reference: DbtNavigationReference) {
  def category: String = if (Set("macro", "parameter", "local").contains(to.kind)) "macro" else "resource"
  def ambiguous: Boolean = reference.candidates.size > 1
}
private[dbt] object DbtLineage {
  def edges(w: DbtWorkspace): Vector[DbtDependency] = w.files.flatMap { file =>
    ProgressManager.checkCanceled()
    val references = DbtNavigation.inFile(file, includeSelections = false)
    references.flatMap { ref =>
      val declarations = w.symbols.filter(s => s.file == file && (s.attributes.contains("code") || s.attributes.contains("property") || s.kind == "macro"))
      val containing = declarations.filter(s => s.mapping.exists(_.getTextRange.contains(ref.getRangeInElement)))
      val blocks = DbtSyntax.inFile(file)
      val macroOwner = declarations.filter(_.kind == "macro").filter { macroSymbol =>
        val end = blocks.find(b => b.range.getStartOffset > macroSymbol.range.getStartOffset && b.tag && b.tokens.headOption.exists(_.image == "endmacro"))
        macroSymbol.range.getStartOffset < ref.getRangeInElement.getStartOffset && end.exists(_.range.getEndOffset > ref.getRangeInElement.getEndOffset)
      }
        .sortBy(_.range.getStartOffset).lastOption.toVector
      val owner = if (containing.nonEmpty) containing.sortBy(_.mapping.get.getTextLength).take(1)
        else if (macroOwner.nonEmpty) macroOwner else declarations.filter(_.attributes.contains("code")).take(1)
      for (from <- owner; target <- ref.candidates.collect { case s: DbtSymbolElement => s.symbol }
        if !Set("documentation", "local", "parameter", "sql_alias", "sql_projection", "cte").contains(target.kind) && from.id != target.id &&
          !(from.attributes.contains("property") && target.attributes.contains("code") && from.kind == target.kind &&
            from.namespace == target.namespace && from.name == target.name && from.version == target.version))
        yield DbtDependency(from, target, ref)
    }
  }.distinct ++ w.symbols.filter(_.metadata).flatMap { from =>
    from.attributes.get("dependencies").toVector.flatMap(_.split(",")).filter(_.nonEmpty).flatMap { id =>
      val targets = w.symbols.filter(s => DbtArtifacts.uniqueId(s) == id && s.kind != "column")
      val selected = targets.filterNot(s => s.attributes.contains("property") && targets.exists(_.attributes.contains("code")))
      val reference = new DbtNavigationReference(from.file, from.range, selected.map(_.element), "artifact dependency")
      selected.map(to => DbtDependency(from, to, reference))
    }
  } ++ DbtParquetArtifacts.edges(w)
  def traverse(edges: Vector[DbtDependency], start: DbtSymbol, downstream: Boolean, depth: Int): Vector[DbtSymbol] = {
    val seen = mutable.Set(start.id)
    var frontier = Vector(start)
    val result = mutable.ArrayBuffer.empty[DbtSymbol]
    var remaining = depth
    while (frontier.nonEmpty && remaining > 0) {
      ProgressManager.checkCanceled()
      val next = frontier.flatMap(s => edges.filter(e => (if (downstream) e.to.id else e.from.id) == s.id)
        .map(e => if (downstream) e.from else e.to)).filter(s => seen.add(s.id))
      result ++= next
      frontier = next; remaining -= 1
    }
    result.toVector
  }
}

/** Shows both declarations and edge call sites, keeping resource and macro relationships labeled. */
final class DbtDependenciesAction extends AnAction {
  override def getActionUpdateThread: com.intellij.openapi.actionSystem.ActionUpdateThread = com.intellij.openapi.actionSystem.ActionUpdateThread.BGT
  override def actionPerformed(event: AnActionEvent): Unit = {
    val project = event.getProject
    val file = event.getData(CommonDataKeys.PSI_FILE)
    val editor = event.getData(CommonDataKeys.EDITOR)
    if (project == null || file == null || editor == null) return
    val offset = editor.getCaretModel.getOffset
    com.intellij.openapi.application.ReadAction.nonBlocking(new java.util.concurrent.Callable[Vector[(String, DbtSymbolElement)]] {
      override def call(): Vector[(String, DbtSymbolElement)] = {
        val w = DbtWorkspace.get(project)
        val at = DbtNavigation.at(file, offset).flatMap(_.candidates).collect { case e: DbtSymbolElement => e.symbol }
        val current = if (at.nonEmpty) at else w.symbolAt(file, offset) ++ w.symbols.filter(s => s.file == file && s.attributes.contains("code"))
        val selected = current.map(_.id).toSet
        DbtLineage.edges(w).filter(e => selected(e.from.id) || selected(e.to.id)).flatMap { e =>
          val parent = selected(e.from.id)
          val target = if (parent) e.to else e.from
          val document = e.reference.getElement.getViewProvider.getDocument
          val line = Option(document).map(d => d.getLineNumber(math.min(e.reference.getRangeInElement.getStartOffset, d.getTextLength)) + 1).getOrElse(1)
          Vector(s"${if (parent) "Parent" else "Child"} ${e.category}: ${target.namespace}.${target.name}${if (e.ambiguous) " (candidate)" else ""}" -> target.element,
            s"Call site: ${e.reference.getElement.getName}:$line" ->
              DbtSymbol("call site", target.name, target.namespace, e.reference.getElement, e.reference.getRangeInElement).element)
        }
      }
    }).inSmartMode(project).expireWith(project).finishOnUiThread(com.intellij.openapi.application.ModalityState.defaultModalityState(),
      (choices: Vector[(String, DbtSymbolElement)]) => {
        if (choices.isEmpty) Messages.showInfoMessage(project, "No statically resolved dependencies at this location.", "dbt Dependencies")
        else JBPopupFactory.getInstance.createPopupChooserBuilder(choices.map(_._1).asJava)
          .setTitle("dbt Dependencies").setItemChosenCallback((label: String) => choices.find(_._1 == label).foreach(_._2.navigate(true)))
          .createPopup().showInBestPositionFor(editor)
      }).submit(com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService)
  }
}
