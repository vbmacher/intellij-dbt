package com.github.vbmacher.intellij.dbt

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.{PsiReference, PsiElement}
import com.intellij.psi.search.PsiSearchScopeUtil
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.util.Processor
import com.intellij.openapi.application.QueryExecutorBase
import org.jetbrains.yaml.psi.YAMLKeyValue

/** Query the cached workspace and resolve identities, including names that the IDE word index splits. */
final class DbtReferencesSearch extends QueryExecutorBase[PsiReference, ReferencesSearch.SearchParameters](true) {
  override def processQuery(parameters: ReferencesSearch.SearchParameters, consumer: Processor[_ >: PsiReference]): Unit = {
    val declaration = parameters.getElementToSearch
    val supported = declaration.isInstanceOf[DbtSymbolElement] || declaration.isInstanceOf[YAMLKeyValue] ||
      DbtWorkspace.get(declaration.getProject).symbols.exists(s => s.file == declaration.getContainingFile && s.range.intersects(declaration.getTextRange))
    if (!supported) return
    val workspace = DbtWorkspace.get(declaration.getProject)
    val files = workspace.files ++ workspace.roots.flatMap(r => DbtNavigation.environment(workspace, r, declaration.getText).map(_.file))
    val iterator = files.distinct.iterator
    var proceed = true
    while (proceed && iterator.hasNext) {
      ProgressManager.checkCanceled()
      val file = iterator.next()
      if (PsiSearchScopeUtil.isInScope(parameters.getEffectiveSearchScope, file)) {
        val references = DbtReferences.inFile(file)
        proceed = references.iterator.filter(ref => ref.isReferenceTo(declaration) &&
          Option(file.findElementAt(ref.getRangeInElement.getStartOffset)).exists(e =>
            PsiSearchScopeUtil.isInScope(parameters.getEffectiveSearchScope, e))).forall(consumer.process)
      }
    }
  }
}

private[dbt] object DbtReferences {
  def inFile(file: com.intellij.psi.PsiFile): Vector[PsiReference] = {
    val variables = DbtVariableReference.inFile(file).toVector
    val general = DbtNavigation.inFile(file)
    variables.filter(v => v.resolve() != null || !general.exists(_.getRangeInElement == v.getRangeInElement)) ++ general.filterNot(ref => variables.exists(v => v.getRangeInElement == ref.getRangeInElement && v.resolve() != null))
  }
}
