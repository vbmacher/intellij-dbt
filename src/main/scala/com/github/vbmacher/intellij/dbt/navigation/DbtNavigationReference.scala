package com.github.vbmacher.intellij.dbt.navigation

import com.github.vbmacher.intellij.dbt.project.{DbtSymbolElement, DbtWorkspace}
import com.intellij.openapi.util.TextRange
import com.intellij.psi._

/** Every resolver produces candidates; ambiguity is preserved for the IDE's declaration chooser. */
final class DbtNavigationReference private[dbt] (file: PsiFile, range: TextRange, val candidates: Vector[PsiElement], val label: String,
  val evidence: Vector[String] = Vector.empty, val unresolvedMessage: Option[String] = None)
  extends PsiPolyVariantReferenceBase[PsiFile](file, range, true) {
  override def multiResolve(incompleteCode: Boolean): Array[ResolveResult] = candidates.distinct.map(new PsiElementResolveResult(_)).toArray
  override def getCanonicalText: String = label
  override def handleElementRename(newName: String): PsiElement = {
    val workspace = DbtWorkspace.get(file.getProject)
    val models = candidates.collect { case e: DbtSymbolElement if e.symbol.kind == "model" => e.symbol }
    val family = models.size == candidates.size && models.nonEmpty && models.map(s =>
      (s.namespace, s.name, workspace.owner(s))).distinct.size == 1
    if (!com.github.vbmacher.intellij.dbt.syntax.DbtSyntax.isIdentifier(newName) || candidates.size != 1 && !family)
      throw new com.intellij.util.IncorrectOperationException("Ambiguous reference '" + label + "' cannot be renamed: " + candidates.map(_.toString).mkString(", "))
    val document = PsiDocumentManager.getInstance(file.getProject).getDocument(file)
    document.replaceString(getRangeInElement.getStartOffset, getRangeInElement.getEndOffset, newName)
    PsiDocumentManager.getInstance(file.getProject).commitDocument(document)
    file
  }
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
