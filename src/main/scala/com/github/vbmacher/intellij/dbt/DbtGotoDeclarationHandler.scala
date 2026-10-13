package com.github.vbmacher.intellij.dbt

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement

/** SQL PSI may not accept contributed references on Jinja tokens; navigation still uses the same resolver. */
final class DbtGotoDeclarationHandler extends GotoDeclarationHandler {
  override def getGotoDeclarationTargets(sourceElement: PsiElement, offset: Int, editor: Editor): Array[PsiElement] =
    Option(sourceElement).flatMap(element => Option(element.getContainingFile))
      .map { file =>
        val variables = DbtVariableReference.at(file, offset).flatMap(r => Option(r.resolve())).toVector
        val targets = if (variables.nonEmpty) variables else DbtNavigation.at(file, offset).flatMap(_.candidates).distinct
        if (targets.nonEmpty) targets.toArray else null
      }.orNull
}
