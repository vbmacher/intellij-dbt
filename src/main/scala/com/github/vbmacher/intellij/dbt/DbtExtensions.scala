package com.github.vbmacher.intellij.dbt

import com.intellij.codeInsight.TargetElementEvaluatorEx2
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.find.findUsages.{FindUsagesHandler, FindUsagesHandlerFactory}
import com.intellij.lang.findUsages.FindUsagesProvider
import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi._
import com.intellij.psi.search.PsiSearchScopeUtil
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.util.{ProcessingContext, Processor}
import org.jetbrains.yaml.psi.YAMLKeyValue

// IDE extension points registered in plugin.xml; all of them delegate to DbtNavigation and DbtWorkspace.

/** SQL PSI may not accept contributed references on Jinja tokens; navigation still uses the same resolver. */
final class DbtGotoDeclarationHandler extends GotoDeclarationHandler {
  override def getGotoDeclarationTargets(sourceElement: PsiElement, offset: Int, editor: Editor): Array[PsiElement] =
    Option(sourceElement).flatMap(e => Option(e.getContainingFile))
      .map(file => DbtNavigation.at(file, offset).flatMap(_.candidates).distinct)
      .filter(_.nonEmpty).map(_.toArray).orNull
}

/** Plain SQL files in Community expose references on the file rather than on individual tokens. */
final class DbtReferenceContributor extends PsiReferenceContributor {
  override def registerReferenceProviders(registrar: PsiReferenceRegistrar): Unit =
    registrar.registerReferenceProvider(PlatformPatterns.psiElement(classOf[PsiPlainTextFile]), new PsiReferenceProvider {
      override def getReferencesByElement(element: PsiElement, context: ProcessingContext): Array[PsiReference] =
        DbtNavigation.inFile(element.asInstanceOf[PsiPlainTextFile]).toArray
    })
}

/** Query the cached workspace and resolve identities, including names that the IDE word index splits. */
final class DbtReferencesSearch extends QueryExecutorBase[PsiReference, ReferencesSearch.SearchParameters](true) {
  override def processQuery(parameters: ReferencesSearch.SearchParameters, consumer: Processor[_ >: PsiReference]): Unit = {
    val declaration = parameters.getElementToSearch
    val workspace = DbtWorkspace.get(declaration.getProject)
    val supported = declaration match {
      case e: DbtSymbolElement => e.symbol.kind != "documentation"
      case _: YAMLKeyValue => true
      case _ => workspace.symbols.exists(s => s.file == declaration.getContainingFile && s.range.intersects(declaration.getTextRange))
    }
    if (!supported) return
    val scope = parameters.getEffectiveSearchScope
    val files = workspace.files ++ workspace.roots.flatMap(r => DbtNavigation.environment(r, declaration.getText).map(_.file))
    files.distinct.iterator.filter { file =>
      ProgressManager.checkCanceled()
      PsiSearchScopeUtil.isInScope(scope, file)
    }.flatMap(file => DbtNavigation.inFile(file).iterator.filter(ref => ref.isReferenceTo(declaration) &&
      Option(file.findElementAt(ref.getRangeInElement.getStartOffset)).exists(PsiSearchScopeUtil.isInScope(scope, _))))
      .forall(consumer.process)
  }
}

final class DbtFindUsagesProvider extends FindUsagesProvider {
  override def canFindUsagesFor(element: PsiElement): Boolean = element.isInstanceOf[DbtSymbolElement]

  override def getHelpId(element: PsiElement): String = "find.other.usages"

  override def getType(element: PsiElement): String = element match {
    case e: DbtSymbolElement => e.symbol.kind
    case _ => "dbt declaration"
  }

  override def getDescriptiveName(element: PsiElement): String = element match {
    case e: DbtSymbolElement => s"${e.symbol.namespace}.${e.getName}${e.symbol.metadataSuffix}"
    case _ => element.getText
  }

  override def getNodeText(element: PsiElement, useFullName: Boolean): String = getDescriptiveName(element)
}

/** Make declarations in plain-text Community resources available to native Find Usages actions. */
final class DbtTargetElementEvaluator extends TargetElementEvaluatorEx2 {
  override def adjustReferenceOrReferencedElement(file: PsiFile, editor: Editor, offset: Int,
    flags: Int, refElement: PsiElement): PsiElement = {
    if (refElement != null || file == null) return refElement
    val workspace = DbtWorkspace.get(file.getProject)
    val lexical = DbtNavigation.inFile(file).flatMap(_.candidates).collect {
      case e: DbtSymbolElement if e.symbol.file == file && !e.symbol.metadata && e.symbol.range.containsOffset(offset) => e.symbol
    }
    ((workspace.symbolAt(file, offset) ++ lexical).distinct ++ workspace.codeSymbols(file)).headOption.map(_.element).orNull
  }
}

final class DbtFindUsagesHandlerFactory extends FindUsagesHandlerFactory {
  /** Declarations at the caret; property declarations stand for the resources they describe. */
  private def targets(element: PsiElement): Vector[PsiElement] = element match {
    case e: DbtSymbolElement => if (e.symbol.kind == "documentation") Vector.empty else Vector(e)
    case _ => Option(element.getContainingFile).toVector.flatMap { file =>
      val editor = Option(FileEditorManager.getInstance(element.getProject).getSelectedTextEditor)
        .filter(e => e.getDocument == PsiDocumentManager.getInstance(element.getProject).getDocument(file))
      val offset = editor.map(_.getCaretModel.getOffset).getOrElse(element.getTextOffset)
      val workspace = DbtWorkspace.get(element.getProject)
      val exact = workspace.symbolAt(file, offset)
      (if (exact.nonEmpty) exact else workspace.codeSymbols(file)).flatMap { symbol =>
        if (symbol.isProperty) workspace.owner(symbol).toVector.flatMap(root =>
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
