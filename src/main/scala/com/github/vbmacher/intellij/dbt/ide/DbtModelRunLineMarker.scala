package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project.DbtWorkspace
import com.intellij.execution.lineMarker.{ExecutorAction, RunLineMarkerContributor}
import com.intellij.icons.AllIcons
import com.intellij.psi.{PsiElement, PsiFile}

final class DbtModelRunLineMarker extends RunLineMarkerContributor {
  override def getInfo(element: PsiElement): RunLineMarkerContributor.Info = null
  override def getSlowInfo(element: PsiElement): RunLineMarkerContributor.Info = {
    val file = element.getContainingFile
    if (element.getFirstChild != null || element.isInstanceOf[PsiFile] || file == null ||
      element.getTextOffset != file.getText.indexWhere(c => !c.isWhitespace) ||
      !DbtWorkspace.get(file.getProject).codeSymbols(file).exists(_.kind == "model")) null
    else new RunLineMarkerContributor.Info(AllIcons.RunConfigurations.TestState.Run, ExecutorAction.getActions(0),
      new java.util.function.Function[PsiElement, String] {
        override def apply(value: PsiElement): String = "Run dbt model"
      })
  }
}
