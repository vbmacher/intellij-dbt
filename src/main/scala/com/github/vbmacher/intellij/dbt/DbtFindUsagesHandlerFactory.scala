package com.github.vbmacher.intellij.dbt

import com.intellij.find.findUsages.{FindUsagesHandler, FindUsagesHandlerFactory}
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.psi.PsiElement

final class DbtFindUsagesHandlerFactory extends FindUsagesHandlerFactory {
  private def targets(element: PsiElement): Vector[PsiElement] = element match {
    case e: DbtSymbolElement => if (e.symbol.kind == "documentation") Vector.empty else Vector(e)
    case _ => Option(element.getContainingFile).toVector.flatMap { file =>
      val editor = Option(FileEditorManager.getInstance(element.getProject).getSelectedTextEditor)
        .filter(e => e.getDocument == com.intellij.psi.PsiDocumentManager.getInstance(element.getProject).getDocument(file))
      val offset = editor.map(_.getCaretModel.getOffset).getOrElse(element.getTextOffset)
      val workspace = DbtWorkspace.get(element.getProject)
      val exact = workspace.symbolAt(file, offset)
      val definitions = if (exact.nonEmpty) exact else workspace.symbols.filter(s => s.file == file && s.attributes.contains("code"))
      definitions.flatMap { symbol =>
        if (symbol.attributes.contains("property")) workspace.owner(symbol).toVector.flatMap(root =>
          workspace.declarations(Set(symbol.kind), symbol.name, root, Some(symbol.namespace)).filter(_.version == symbol.version)).map(_.element)
        else Vector(symbol.element)
      }
    }
  }

  override def canFindUsages(element: PsiElement): Boolean = targets(element).nonEmpty

  override def createFindUsagesHandler(element: PsiElement, forHighlightUsages: Boolean): FindUsagesHandler =
    targets(element).headOption.map { first =>
      new FindUsagesHandler(first) {
        override def getPrimaryElements: Array[PsiElement] = targets(element).toArray
      }
    }.orNull
}
