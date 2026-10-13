package com.github.vbmacher.intellij.dbt

import com.intellij.codeInsight.TargetElementEvaluatorEx2
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement

/** Make declarations in plain-text Community resources available to native Find Usages actions. */
final class DbtTargetElementEvaluator extends TargetElementEvaluatorEx2 {
  override def adjustReferenceOrReferencedElement(file: com.intellij.psi.PsiFile, editor: Editor, offset: Int,
    flags: Int, refElement: PsiElement): PsiElement = {
    if (refElement != null) return refElement
    Option(file).flatMap { file =>
      val workspace = DbtWorkspace.get(file.getProject)
      val indexed = workspace.symbolAt(file, offset)
      val lexical = DbtNavigation.inFile(file).flatMap(_.candidates).collect {
        case e: DbtSymbolElement if e.symbol.file == file && !e.symbol.metadata && e.symbol.range.containsOffset(offset) => e.symbol
      }
      val named = (indexed ++ lexical).distinct
      val resource = workspace.symbols.filter(s => s.file == file && s.attributes.contains("code"))
      (named ++ resource).headOption.map(_.element)
    }.getOrElse(refElement)
  }
}
