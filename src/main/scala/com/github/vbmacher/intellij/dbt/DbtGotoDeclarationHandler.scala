package com.github.vbmacher.intellij.dbt

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement

/** SQL PSI may not accept contributed references on Jinja tokens; navigation still uses the same resolver. */
final class DbtGotoDeclarationHandler extends GotoDeclarationHandler {
  override def getGotoDeclarationTargets(sourceElement: PsiElement, offset: Int, editor: Editor): Array[PsiElement] =
    Option(sourceElement).flatMap(element => Option(element.getContainingFile))
      .flatMap(file => DbtVariableReference.at(file, offset))
      .flatMap(reference => Option(reference.resolve())).map(element => Array(element)).orNull
}
