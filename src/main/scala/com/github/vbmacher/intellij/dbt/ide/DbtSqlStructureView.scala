package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.navigation.DbtSqlAnalysis
import com.github.vbmacher.intellij.dbt.project.{DbtSymbol, DbtWorkspace}
import com.intellij.ide.structureView._
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.lang.PsiStructureViewFactory
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiFile

final class DbtSqlStructureViewFactory extends PsiStructureViewFactory {
  override def getStructureViewBuilder(file: PsiFile): StructureViewBuilder = {
    val w = DbtWorkspace.get(file.getProject)
    if (!w.codeSymbols(file).exists(_.kind == "model")) null
    else new TreeBasedStructureViewBuilder {
      override def createStructureViewModel(editor: Editor): StructureViewModel =
        new StructureViewModelBase(file, editor, new DbtSqlStructureElement(file, None)) {
          override def getCurrentEditorElement: AnyRef = {
            val workspace = DbtWorkspace.get(file.getProject)
            val cte = for {
              model <- workspace.codeSymbols(file).find(_.kind == "model")
              analysis <- DbtSqlAnalysis.get(workspace, file)
              selected <- Option(editor).flatMap(e => DbtCteInspection.at(workspace, file, analysis, e.getCaretModel.getOffset))
            } yield DbtSymbol("cte", selected.name, model.namespace, file, selected.range).element
            cte.getOrElse(file)
          }
        }
    }
  }
}

private[ide] final class DbtSqlStructureElement(file: PsiFile, symbol: Option[DbtSymbol]) extends StructureViewTreeElement {
  override def getValue: AnyRef = symbol.map(_.element).getOrElse(file)
  override def getPresentation: ItemPresentation = symbol.map(_.element.getPresentation).getOrElse(new ItemPresentation {
    override def getPresentableText: String = file.getName
    override def getLocationString: String = null
    override def getIcon(open: Boolean): javax.swing.Icon = file.getIcon(0)
  })
  override def getChildren: Array[TreeElement] = {
    val w = DbtWorkspace.get(file.getProject)
    val nodes = for {
      model <- w.codeSymbols(file).find(_.kind == "model").toVector
      analysis <- DbtSqlAnalysis.get(w, file).toVector
      cte <- analysis.ctes if symbol.fold(!analysis.ctes.exists(p => p.body.contains(cte.range)))(parent =>
        analysis.ctes.find(_.range == parent.range).exists(p => p.body.contains(cte.range)) &&
          !analysis.ctes.exists(p => p.range != parent.range && parent.range.contains(p.range) && p.body.contains(cte.range)))
    } yield new DbtSqlStructureElement(file, Some(DbtSymbol("cte", cte.name, model.namespace, file, cte.range))): TreeElement
    nodes.toArray
  }
  override def canNavigate: Boolean = symbol.exists(_.element.canNavigate)
  override def canNavigateToSource: Boolean = canNavigate
  override def navigate(requestFocus: Boolean): Unit = symbol.foreach(_.element.navigate(requestFocus))
}

/** Extend a SQL plugin's existing outline rather than replacing its structure provider. */
final class DbtSqlStructureViewExtension extends StructureViewExtension {
  override def getType: Class[_ <: com.intellij.psi.PsiElement] = classOf[PsiFile]
  override def getChildren(element: com.intellij.psi.PsiElement): Array[StructureViewTreeElement] = element match {
    case file: PsiFile if Option(com.intellij.lang.Language.findLanguageByID("SQL")).exists(file.getLanguage.isKindOf) &&
      DbtWorkspace.get(file.getProject).codeSymbols(file).exists(_.kind == "model") =>
      new DbtSqlStructureElement(file, None).getChildren.map(_.asInstanceOf[StructureViewTreeElement])
    case _ => Array.empty
  }
  override def getCurrentEditorElement(editor: Editor, parent: com.intellij.psi.PsiElement): AnyRef = parent match {
    case file: PsiFile => getChildren(file).map(_.getValue).collectFirst {
      case e: com.github.vbmacher.intellij.dbt.project.DbtSymbolElement if e.symbol.range.containsOffset(editor.getCaretModel.getOffset) => e
    }.orNull
    case _ => null
  }
}
