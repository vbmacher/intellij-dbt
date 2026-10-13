package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project.{DbtSymbolElement, DbtWorkspace}
import com.intellij.ide.IconProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.{PsiElement, PsiFile}

import javax.swing.Icon

/** File icons use local dbt declarations; mixed property files keep their file-type icon. */
final class DbtIconProvider extends IconProvider with DumbAware {
  override def getIcon(element: PsiElement, flags: Int): Icon = element match {
    case file: PsiFile =>
      val symbols = DbtWorkspace.declarationsInFile(file)
      val code = symbols.filter(_.isCode)
      val resources = if (code.nonEmpty) code else symbols.filter(_.owner.isEmpty)
      val icons = resources.map(s => DbtSymbolElement.icon(if (s.isTestOnlyProperty) "test" else s.kind)).distinct
      if (icons.size == 1) icons.head else null
    case _ => null
  }
}
