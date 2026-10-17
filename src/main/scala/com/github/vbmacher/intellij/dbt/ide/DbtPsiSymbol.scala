package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project.DbtSymbolElement
import com.intellij.find.usages.api.{PsiUsage, SearchTarget, Usage, UsageHandler, UsageSearchParameters, UsageSearcher}
import com.intellij.find.usages.symbol.SymbolSearchTargetFactory
import com.intellij.model.Pointer
import com.intellij.navigation.{NavigatableSymbol, SymbolNavigationService}
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.platform.backend.documentation.{DocumentationTarget, SymbolDocumentationTargetProvider}
import com.intellij.platform.backend.navigation.NavigationTarget
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.{PsiElement, PsiFile, PsiNamedElement, PsiReference, SmartPointerManager}
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.util.Query
import scala.jdk.CollectionConverters._

/** Public Symbol contracts retain native navigation, documentation and usage search for dbt PSI targets. */
private[dbt] final class DbtPsiSymbol(val element: PsiElement, val source: PsiFile, val offset: Int) extends NavigatableSymbol {
  override def createPointer(): Pointer[DbtPsiSymbol] = {
    val target = SmartPointerManager.createPointer(element)
    Pointer.fileRangePointer(source, new TextRange(offset, offset), (fresh: PsiFile, range: TextRange) =>
      Option(target.dereference()).filter(_.isValid).map(e => new DbtPsiSymbol(e, fresh, range.getStartOffset)).orNull)
  }

  override def getNavigationTargets(project: Project): java.util.Collection[_ <: NavigationTarget] =
    java.util.List.of(SymbolNavigationService.getInstance().psiElementNavigationTarget(element))

  def presentation(): TargetPresentation = {
    val name = element match {
      case e: DbtSymbolElement => e.symbol.presentableText
      case e: PsiNamedElement => e.getName
      case _ => element.getText
    }
    TargetPresentation.builder(name).icon(element.getIcon(0)).presentation()
  }

  override def equals(other: Any): Boolean = other match {
    case symbol: DbtPsiSymbol => element == symbol.element
    case _ => false
  }
  override def hashCode(): Int = element.hashCode()
}

final class DbtSymbolDocumentationTargetProvider extends SymbolDocumentationTargetProvider {
  override def documentationTarget(project: Project, target: com.intellij.model.Symbol): DocumentationTarget = target match {
    case symbol: DbtPsiSymbol => new DbtDocumentationTargetProvider()
      .documentationTargets(symbol.source, symbol.offset).asScala.headOption.orNull
    case _ => null
  }
}

final class DbtSymbolSearchTargetFactory extends SymbolSearchTargetFactory[DbtPsiSymbol] {
  override def searchTarget(project: Project, symbol: DbtPsiSymbol): SearchTarget = symbol.element match {
    case e: DbtSymbolElement if e.symbol.kind == "documentation" => null
    // The platform also adds PSI usage targets. Avoid presenting the same declaration twice.
    case _ if Option(symbol.source.findReferenceAt(symbol.offset)).exists(_.isReferenceTo(symbol.element)) => null
    case _ => new DbtSymbolSearchTarget(symbol)
  }
}

private[ide] final class DbtSymbolSearchTarget(val symbol: DbtPsiSymbol) extends SearchTarget {
  override def createPointer(): Pointer[_ <: SearchTarget] = Pointer.delegatingPointer(symbol.createPointer(),
    (fresh: DbtPsiSymbol) => new DbtSymbolSearchTarget(fresh))
  override def presentation(): TargetPresentation = symbol.presentation()
  override def getUsageHandler: UsageHandler = UsageHandler.createEmptyUsageHandler(presentation().getPresentableText)
  override def equals(other: Any): Boolean = other match {
    case target: DbtSymbolSearchTarget => symbol == target.symbol
    case _ => false
  }
  override def hashCode(): Int = symbol.hashCode()
}

final class DbtSymbolUsageSearcher extends UsageSearcher {
  override def collectSearchRequest(parameters: UsageSearchParameters): Query[_ <: Usage] = parameters.getTarget match {
    case target: DbtSymbolSearchTarget => ReferencesSearch.search(target.symbol.element, parameters.getSearchScope)
      .mapping[Usage]((reference: PsiReference) => PsiUsage.textUsage(reference.getElement, reference.getRangeInElement))
    case _ => null
  }
}
