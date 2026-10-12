package com.github.vbmacher.intellij.dbt

import com.intellij.openapi.util.TextRange
import com.intellij.psi.{PsiElement, PsiFile, PsiReferenceBase}
import com.intellij.psi.util.{CachedValueProvider, CachedValuesManager}

final class DbtVariableReference(element: PsiElement, range: TextRange, val name: String)
  extends PsiReferenceBase[PsiElement](element, range, true) {

  override def resolve(): PsiElement = DbtProject.forProject(element.getProject)
    .filter(_.accepts(element.getContainingFile)).flatMap(_.resolve(name)).orNull

  override def getCanonicalText: String = name

  override def getVariants: Array[AnyRef] = Array.empty
}

private[dbt] object DbtVariableReference {
  def calls(file: PsiFile): Seq[DbtVarCall] = CachedValuesManager.getCachedValue(file, () =>
    CachedValueProvider.Result.create(DbtVarCalls.extract(file.getText), file))

  def inFile(file: PsiFile): Seq[DbtVariableReference] = {
    if (!DbtProject.forProject(file.getProject).exists(_.accepts(file))) return Seq.empty
    // Both contributed references and the search executor use the same host, avoiding duplicate usages.
    calls(file).map(call => new DbtVariableReference(file, call.range, call.name))
  }

  def at(file: PsiFile, offset: Int): Option[DbtVariableReference] = inFile(file).find { reference =>
    reference.getRangeInElement.shiftRight(reference.getElement.getTextRange.getStartOffset).containsOffset(offset)
  }
}
