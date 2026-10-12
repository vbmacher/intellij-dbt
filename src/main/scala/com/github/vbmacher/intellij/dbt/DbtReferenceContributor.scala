package com.github.vbmacher.intellij.dbt

import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.{PsiElement, PsiPlainTextFile, PsiReference, PsiReferenceContributor, PsiReferenceProvider, PsiReferenceRegistrar}
import com.intellij.util.ProcessingContext

/** Plain SQL files in Community expose references on the file rather than on individual tokens. */
final class DbtReferenceContributor extends PsiReferenceContributor {
  override def registerReferenceProviders(registrar: PsiReferenceRegistrar): Unit = {
    registrar.registerReferenceProvider(PlatformPatterns.psiElement(classOf[PsiPlainTextFile]), new PsiReferenceProvider {
      override def getReferencesByElement(element: PsiElement, context: ProcessingContext): Array[PsiReference] = {
        val file = element.asInstanceOf[PsiPlainTextFile]
        if (!DbtProject.forProject(file.getProject).exists(_.accepts(file))) return PsiReference.EMPTY_ARRAY
        DbtVariableReference.calls(file).map(call =>
          new DbtVariableReference(file, call.range, call.name): PsiReference).toArray
      }
    })
  }
}
