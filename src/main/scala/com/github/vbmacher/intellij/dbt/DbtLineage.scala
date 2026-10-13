package com.github.vbmacher.intellij.dbt

import com.intellij.openapi.actionSystem.{AnAction, AnActionEvent, CommonDataKeys}
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.psi.PsiElement
import com.intellij.psi.search.{PsiSearchScopeUtil, SearchScope}
import scala.collection.mutable
import scala.jdk.CollectionConverters._

private[dbt] final case class DbtDependency(from: DbtSymbol, to: DbtSymbol, reference: DbtNavigationReference) {
  def category: String = if (Set("macro", "builtin", "parameter", "local").contains(to.kind)) "macro" else "resource"
  def ambiguous: Boolean = reference.candidates.size > 1
}
private[dbt] object DbtLineage {
  def edges(w: DbtWorkspace, scope: Option[SearchScope] = None): Vector[DbtDependency] = w.files.filter(file =>
    scope.forall(PsiSearchScopeUtil.isInScope(_, file))).flatMap { file =>
    ProgressManager.checkCanceled()
    val references = DbtNavigation.inFile(file, includeSelections = false)
    references.flatMap { ref =>
      val endTags = Map("macro" -> "endmacro", "generic_test" -> "endtest", "materialization" -> "endmaterialization", "snapshot" -> "endsnapshot")
      val declarations = w.symbols.filter(s => s.file == file && (s.attributes.contains("code") || s.attributes.contains("property") || endTags.contains(s.kind)))
      val containing = declarations.filter(s => s.mapping.exists(m => m.getContainingFile == file && m.getTextRange.contains(ref.getRangeInElement)))
      val blocks = DbtSyntax.inFile(file)
      val macroOwner = declarations.filter(s => endTags.contains(s.kind) && s.attributes.contains("block-start")).filter { macroSymbol =>
        val end = blocks.find(b => b.range.getStartOffset > macroSymbol.range.getStartOffset && b.tag && b.tokens.headOption.exists(t => endTags(macroSymbol.kind) == t.image))
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
  }.distinct ++ w.symbols.filter(s => s.metadata && scope.forall(PsiSearchScopeUtil.isInScope(_, s.file))).flatMap { from =>
    from.attributes.get("dependencies").toVector.flatMap(_.split(",")).filter(_.nonEmpty).flatMap { id =>
      val targets = w.owner(from).toVector.flatMap(r => w.symbols.filter(s => DbtArtifacts.uniqueId(s) == id && s.kind != "column" &&
        w.declarations(Set(s.kind), s.name, r, Some(s.namespace)).contains(s)))
      val selected = targets.filterNot(s => s.attributes.contains("property") && targets.exists(_.attributes.contains("code")))
      val reference = new DbtNavigationReference(from.file, from.range, selected.map(_.element), "artifact dependency")
      selected.map(to => DbtDependency(from, to, reference))
    }
  } ++ DbtParquetArtifacts.edges(w).filter(e => scope.forall(s => PsiSearchScopeUtil.isInScope(s, e.reference.getElement) ||
    e.reference.evidence.find(_.startsWith("Artifact: ")).map(_.stripPrefix("Artifact: "))
      .flatMap(p => Option(com.intellij.openapi.vfs.LocalFileSystem.getInstance.findFileByPath(p)))
      .exists(v => s match { case g: com.intellij.psi.search.GlobalSearchScope => g.contains(v); case _ => false })))
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

private[dbt] object DbtDependenciesAction {
  def choices(edges: Vector[DbtDependency], selected: Set[String]): Vector[DbtNavigationItem] = {
    Vector(true, false).flatMap { upstream =>
      val related = edges.filter(e => if (upstream) selected(e.from.id) else selected(e.to.id))
      val targets = related.map(e => if (upstream) e.to else e.from).distinctBy(_.id)
      targets.zipWithIndex.flatMap { case (target, index) =>
        val references = related.filter(e => (if (upstream) e.to else e.from).id == target.id)
        val candidate = if (references.exists(_.ambiguous)) " (candidate)" else ""
        val heading = if (index == 0) Some(if (upstream) "Uses" else "Used by") else None
        Vector(DbtNavigationItem(target.element, target.element.getPresentation.getPresentableText + candidate, separator = heading)) ++
          references.map { e =>
            val file = e.reference.getElement
            val range = e.reference.getRangeInElement
            val document = file.getViewProvider.getDocument
            val line = Option(document).map(d => d.getLineNumber(math.min(range.getStartOffset, d.getTextLength)) + 1).getOrElse(1)
            DbtNavigationItem(DbtSymbol("call site", target.name, target.namespace, file, range).element,
              s"${file.getName}:$line", reference = true)
          }.distinctBy(_.target.symbol.id)
      }
    }
  }
}

/** Shows resource icons with their references indented below them. */
final class DbtDependenciesAction extends AnAction {
  override def getActionUpdateThread: com.intellij.openapi.actionSystem.ActionUpdateThread = com.intellij.openapi.actionSystem.ActionUpdateThread.BGT

  override def actionPerformed(event: AnActionEvent): Unit = {
    val project = event.getProject
    val file = event.getData(CommonDataKeys.PSI_FILE)
    val editor = event.getData(CommonDataKeys.EDITOR)
    if (project == null || file == null || editor == null) return
    val offset = editor.getCaretModel.getOffset
    com.intellij.openapi.application.ReadAction.nonBlocking(new java.util.concurrent.Callable[Vector[DbtNavigationItem]] {
      override def call(): Vector[DbtNavigationItem] = {
        val w = DbtWorkspace.get(project)
        val at = DbtNavigation.at(file, offset).flatMap(_.candidates).collect { case e: DbtSymbolElement => e.symbol }
        val current = if (at.nonEmpty) at else w.symbolAt(file, offset) ++ w.symbols.filter(s => s.file == file && s.attributes.contains("code"))
        val selected = current.map(_.id).toSet
        DbtDependenciesAction.choices(DbtLineage.edges(w), selected)
      }
    }).inSmartMode(project).expireWith(project).finishOnUiThread(com.intellij.openapi.application.ModalityState.defaultModalityState(),
      (choices: Vector[DbtNavigationItem]) => {
        if (choices.isEmpty) Messages.showInfoMessage(project, "No statically resolved dependencies at this location.", "dbt Dependencies")
        else JBPopupFactory.getInstance.createPopupChooserBuilder(choices.asJava)
          .setTitle("dbt Dependencies").setRenderer(new DbtNavigationItemRenderer)
          .setNamerForFiltering((item: DbtNavigationItem) => item.text)
          .setItemChosenCallback((item: DbtNavigationItem) => item.target.navigate(true))
          .createPopup().showInBestPositionFor(editor)
      }).submit(com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService)
  }
}
