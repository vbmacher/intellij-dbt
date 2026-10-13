package com.github.vbmacher.intellij.dbt.lineage

import com.github.vbmacher.intellij.dbt.navigation.{DbtNavigation, DbtNavigationReference}
import com.github.vbmacher.intellij.dbt.project.{DbtSymbol, DbtSymbolElement, DbtWorkspace}
import com.github.vbmacher.intellij.dbt.syntax.DbtSyntax
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiFile
import com.intellij.psi.search.{PsiSearchScopeUtil, SearchScope}

import scala.collection.mutable
import scala.jdk.CollectionConverters._

/** `from` uses `to` at `reference`. */
private[dbt] final case class DbtDependency(from: DbtSymbol, to: DbtSymbol, reference: DbtNavigationReference) {
  def ambiguous: Boolean = reference.candidates.size > 1
}

/** Port of dependencies recorded outside of project sources; implementations are registered in `plugin.xml`. */
private[dbt] trait DbtDependencySource {
  /** Dependencies whose evidence is within the scope, if any. */
  def dependencies(workspace: DbtWorkspace, scope: Option[SearchScope]): Vector[DbtDependency]
}

private[dbt] object DbtDependencySource {
  val EP: ExtensionPointName[DbtDependencySource] = ExtensionPointName.create("com.github.vbmacher.intellij-dbt.dependencySource")
}

/** Dependencies between dbt declarations, statically resolved from sources and recorded by dependency sources. */
private[dbt] object DbtLineage {
  private val EndTags = Map("macro" -> "endmacro", "generic_test" -> "endtest", "materialization" -> "endmaterialization", "snapshot" -> "endsnapshot")
  private val Untracked = Set("documentation", "local", "parameter", "sql_alias", "sql_projection", "cte")

  def edges(w: DbtWorkspace, scope: Option[SearchScope] = None): Vector[DbtDependency] =
    w.files.filter(file => scope.forall(PsiSearchScopeUtil.isInScope(_, file))).flatMap(sourceEdges(w, _)).distinct ++
      DbtDependencySource.EP.getExtensionList.asScala.toVector.flatMap(_.dependencies(w, scope))

  /** Edges from the innermost declaration enclosing each reference of a file. */
  private def sourceEdges(w: DbtWorkspace, file: PsiFile): Vector[DbtDependency] = {
    ProgressManager.checkCanceled()
    val declarations = w.symbols.filter(s => s.file == file && (s.isCode || s.isProperty || EndTags.contains(s.kind)))
    val blocks = DbtSyntax.inFile(file)
    DbtNavigation.inFile(file, includeSelections = false).flatMap { ref =>
      val range = ref.getRangeInElement
      val containing = declarations.filter(s => s.mapping.exists(m => m.getContainingFile == file && m.getTextRange.contains(range)))
      val blockOwner = declarations.filter(s => EndTags.contains(s.kind) && s.attributes.contains("block-start") &&
        DbtSyntax.encloses(blocks, s.range.getStartOffset, EndTags(s.kind), range)).sortBy(_.range.getStartOffset).lastOption.toVector
      val owner = if (containing.nonEmpty) containing.sortBy(_.mapping.get.getTextLength).take(1)
      else if (blockOwner.nonEmpty) blockOwner else declarations.filter(_.isCode).take(1)
      for (from <- owner; target <- ref.candidates.collect { case s: DbtSymbolElement => s.symbol }
           if !Untracked(target.kind) && from.id != target.id && !(from.isProperty && target.isCode && from.sameResource(target)))
      yield DbtDependency(from, target, ref)
    }
  }

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
      frontier = next
      remaining -= 1
    }
    result.toVector
  }
}
