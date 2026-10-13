package com.github.vbmacher.intellij.dbt

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiFile
import com.intellij.psi.search.{GlobalSearchScope, PsiSearchScopeUtil, SearchScope}

import scala.collection.mutable

private[dbt] final case class DbtDependency(from: DbtSymbol, to: DbtSymbol, reference: DbtNavigationReference) {
  def ambiguous: Boolean = reference.candidates.size > 1
}

private[dbt] object DbtLineage {
  private val EndTags = Map("macro" -> "endmacro", "generic_test" -> "endtest", "materialization" -> "endmaterialization", "snapshot" -> "endsnapshot")
  private val Untracked = Set("documentation", "local", "parameter", "sql_alias", "sql_projection", "cte")

  def edges(w: DbtWorkspace, scope: Option[SearchScope] = None): Vector[DbtDependency] =
    w.files.filter(file => scope.forall(PsiSearchScopeUtil.isInScope(_, file))).flatMap(sourceEdges(w, _)).distinct ++
      artifactEdges(w, scope) ++ DbtParquetArtifacts.edges(w).filter(e => scope.forall(inScope(_, e)))

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

  private def artifactEdges(w: DbtWorkspace, scope: Option[SearchScope]): Vector[DbtDependency] =
    w.symbols.filter(s => s.metadata && scope.forall(PsiSearchScopeUtil.isInScope(_, s.file))).flatMap { from =>
      from.attributes.get("dependencies").toVector.flatMap(_.split(",")).filter(_.nonEmpty).flatMap { id =>
        val targets = w.owner(from).toVector.flatMap(r => w.symbols.filter(s => DbtArtifacts.uniqueId(s) == id && s.kind != "column" &&
          w.declarations(Set(s.kind), s.name, r, Some(s.namespace)).contains(s)))
        val selected = targets.filterNot(s => s.isProperty && targets.exists(_.isCode))
        val reference = new DbtNavigationReference(from.file, from.range, selected.map(_.element), "artifact dependency")
        selected.map(to => DbtDependency(from, to, reference))
      }
    }

  private def inScope(scope: SearchScope, edge: DbtDependency): Boolean = PsiSearchScopeUtil.isInScope(scope, edge.reference.getElement) ||
    edge.reference.evidence.find(_.startsWith("Artifact: ")).map(_.stripPrefix("Artifact: "))
      .flatMap(p => Option(LocalFileSystem.getInstance.findFileByPath(p)))
      .exists(v => scope match {
        case g: GlobalSearchScope => g.contains(v)
        case _ => false
      })

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
