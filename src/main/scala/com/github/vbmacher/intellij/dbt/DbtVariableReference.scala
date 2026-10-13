package com.github.vbmacher.intellij.dbt

import com.intellij.openapi.util.TextRange
import com.intellij.psi.{PsiElement, PsiFile, PsiReferenceBase}
import com.intellij.psi.util.{CachedValueProvider, CachedValuesManager}

final class DbtVariableReference(element: PsiElement, range: TextRange, val name: String)
  extends PsiReferenceBase[PsiElement](element, range, true) {

  override def resolve(): PsiElement = {
    val workspace = DbtWorkspace.get(element.getProject)
    val file = element.getContainingFile
    workspace.root(file).filter(_.resource(file)).flatMap { root =>
      val call = DbtVariableReference.calls(file).find(_.range == range)
      val shadowed = workspace.declarations(Set("macro"), "var", root).nonEmpty || call.exists(c =>
        DbtLocals.references(file, DbtSyntax.inFile(file), workspace).exists(_.getRangeInElement.containsOffset(c.callOffset)))
      if (shadowed) None else {
        val targets = DbtVariableContexts.roots(workspace, file, root, call.map(_.callOffset)).flatMap(r => DbtNavigation.variable(workspace, r, name)).distinct
        if (targets.size == 1) targets.headOption else None
      }
    }.orNull
  }

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
