package com.github.vbmacher.intellij.dbt

import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.psi.{PsiElement, PsiReference}
import com.intellij.psi.search.{RequestResultProcessor, UsageSearchContext}
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.util.Processor
import org.jetbrains.yaml.psi.YAMLKeyValue

/** Use the IDE word index to discover files; verify every result with the same resolver as navigation. */
final class DbtReferencesSearch extends QueryExecutorBase[PsiReference, ReferencesSearch.SearchParameters](true) {
  override def processQuery(parameters: ReferencesSearch.SearchParameters, consumer: Processor[_ >: PsiReference]): Unit = {
    parameters.getElementToSearch match {
      case declaration: YAMLKeyValue if DbtProject.forProject(declaration.getProject).exists(_.isDeclaration(declaration)) =>
        // Searching for the callee also finds names containing punctuation or escaped characters.
        parameters.getOptimizer.searchWord("var", parameters.getEffectiveSearchScope, UsageSearchContext.ANY,
          true, declaration, new RequestResultProcessor(declaration) {
            override def processTextOccurrence(element: PsiElement, offsetInElement: Int,
              result: Processor[_ >: PsiReference]): Boolean = {
              if (element.getFirstChild != null) return true
              val file = element.getContainingFile
              if (file == null) return true
              val references = DbtVariableReference.inFile(file)
              if (references.isEmpty) return true
              val calls = DbtVariableReference.calls(file)
              val offset = element.getTextRange.getStartOffset + offsetInElement
              calls.zip(references).filter(_._1.callOffset == offset).forall { case (_, reference) =>
                !reference.isReferenceTo(declaration) || result.process(reference)
              }
            }
          })
      case _ =>
    }
  }
}
