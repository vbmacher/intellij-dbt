package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.navigation.{DbtNavigation, DbtNavigationReference}
import com.github.vbmacher.intellij.dbt.project.{DbtEnvironment, DbtSymbolElement, DbtWorkspace}
import com.intellij.codeInsight.{TargetElementEvaluatorEx2, TargetElementUtil}
import com.intellij.find.findUsages.{FindUsagesHandler, FindUsagesHandlerFactory}
import com.intellij.lang.findUsages.FindUsagesProvider
import com.intellij.model.Symbol
import com.intellij.model.psi.{ImplicitReferenceProvider, PsiSymbolReference}
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
import scala.jdk.CollectionConverters._

// IDE extension points registered in plugin.xml; all of them delegate to DbtNavigation and DbtWorkspace.

/** Supplies exact reference ranges to native navigation and Ctrl-hover for any source language. */
final class DbtImplicitReferenceProvider extends ImplicitReferenceProvider {
  override def getImplicitReference(element: PsiElement, offsetInElement: Int): PsiSymbolReference = element match {
    case file: PsiFile =>
      val references = DbtNavigation.at(file, offsetInElement)
      val candidates = references.flatMap(_.candidates).distinct
      references.headOption.filter(_ => candidates.nonEmpty).map { first =>
        new PsiSymbolReference {
          override def getElement: PsiElement = file
          override def getRangeInElement: com.intellij.openapi.util.TextRange = first.getRangeInElement
          override def resolveReference(): java.util.Collection[_ <: Symbol] = candidates.map(new DbtPsiSymbol(_, file, offsetInElement)).asJava
        }
      }.orNull
    case _ => null
  }
}

/** Plain SQL and TextMate hosts expose dbt references on the file rather than on individual tokens. */
final class DbtReferenceContributor extends PsiReferenceContributor {
  override def registerReferenceProviders(registrar: PsiReferenceRegistrar): Unit =
    registrar.registerReferenceProvider(PlatformPatterns.psiElement(classOf[PsiFile]), new PsiReferenceProvider {
      override def getReferencesByElement(element: PsiElement, context: ProcessingContext): Array[PsiReference] = {
        val file = element.asInstanceOf[PsiFile]
        if (file.isInstanceOf[PsiPlainTextFile] || file.getLanguage.getID == "textmate") DbtNavigation.inFile(file).toArray
        else Array.empty
      }
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
    val files = workspace.files ++ workspace.roots.flatMap(r => DbtEnvironment(r, declaration.getText).map(_.file))
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

/** Make dbt declarations available to native Find Usages actions. */
final class DbtTargetElementEvaluator extends TargetElementEvaluatorEx2 {
  override def adjustReferenceOrReferencedElement(file: PsiFile, editor: Editor, offset: Int,
    flags: Int, refElement: PsiElement): PsiElement = {
    if (refElement != null || file == null || (flags & TargetElementUtil.ELEMENT_NAME_ACCEPTED) == 0) return refElement
    val workspace = DbtWorkspace.get(file.getProject)
    val lexical = DbtNavigation.inFile(file).flatMap(_.candidates).collect {
      case e: DbtSymbolElement if e.symbol.file == file && !e.symbol.metadata && e.symbol.range.containsOffset(offset) => e.symbol
    }
    val sqlDeclarations = com.github.vbmacher.intellij.dbt.navigation.DbtSqlAnalysis.get(workspace, file).toVector
      .flatMap(_.declarations).filter(_.range.containsOffset(offset))
    ((sqlDeclarations ++ workspace.symbolAt(file, offset) ++ lexical).distinct ++ workspace.codeSymbols(file)).headOption.map(_.element).orNull
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
