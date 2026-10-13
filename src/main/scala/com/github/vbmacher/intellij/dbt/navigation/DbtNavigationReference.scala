package com.github.vbmacher.intellij.dbt.navigation

import com.github.vbmacher.intellij.dbt.project.{DbtSymbolElement, DbtWorkspace}
import com.intellij.openapi.util.TextRange
import com.intellij.psi._

/** Every resolver produces candidates; ambiguity is preserved for the IDE's declaration chooser. */
final class DbtNavigationReference private[dbt] (file: PsiFile, range: TextRange, val candidates: Vector[PsiElement], val label: String,
  val evidence: Vector[String] = Vector.empty)
  extends PsiPolyVariantReferenceBase[PsiFile](file, range, true) {
  override def multiResolve(incompleteCode: Boolean): Array[ResolveResult] = candidates.distinct.map(new PsiElementResolveResult(_)).toArray
  override def getCanonicalText: String = label
  override def isReferenceTo(element: PsiElement): Boolean = candidates.exists { candidate =>
    (candidate, element) match {
      case (a: DbtSymbolElement, _) if a.symbol.kind == "documentation" => false
      case (a: DbtSymbolElement, b: DbtSymbolElement) if b.symbol.isProperty && !a.symbol.isProperty =>
        a.isValid && b.isValid && DbtWorkspace.get(a.getProject).properties(a.symbol).exists(p =>
          p.file == b.getContainingFile && p.kind == b.symbol.kind && p.name == b.symbol.name &&
            p.version == b.symbol.version && p.element.getTextRange == b.getTextRange)
      case (a: DbtSymbolElement, b: DbtSymbolElement) => a.isValid && b.isValid && a.getContainingFile == b.getContainingFile &&
        (a.getTextRange == b.getTextRange || (a.symbol.isCode && b.symbol.isCode)) && a.symbol.kind == b.symbol.kind && a.symbol.name == b.symbol.name &&
        a.symbol.namespace == b.symbol.namespace && a.symbol.owner == b.symbol.owner && a.symbol.version == b.symbol.version
      case _ => candidate == element || candidate.getManager.areElementsEquivalent(candidate, element)
    }
  }
}
