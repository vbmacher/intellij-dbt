package com.github.vbmacher.intellij.dbt

import com.intellij.lang.findUsages.FindUsagesProvider
import com.intellij.psi.PsiElement

final class DbtFindUsagesProvider extends FindUsagesProvider {
  override def canFindUsagesFor(element: PsiElement): Boolean = element.isInstanceOf[DbtSymbolElement]
  override def getHelpId(element: PsiElement): String = "find.other.usages"
  override def getType(element: PsiElement): String = element match {
    case e: DbtSymbolElement => e.symbol.kind
    case _ => "dbt declaration"
  }
  override def getDescriptiveName(element: PsiElement): String = element match {
    case e: DbtSymbolElement => s"${e.symbol.namespace}.${e.getName}${if (e.symbol.attributes.get("stale").contains("true")) " (stale metadata)" else if (e.symbol.metadata) " (metadata)" else ""}"
    case _ => element.getText
  }
  override def getNodeText(element: PsiElement, useFullName: Boolean): String = getDescriptiveName(element)
}
