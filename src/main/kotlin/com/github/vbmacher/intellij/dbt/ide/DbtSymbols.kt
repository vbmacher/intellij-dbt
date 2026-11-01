package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.navigation.DbtNavigation
import com.github.vbmacher.intellij.dbt.navigation.DbtSqlAnalysis
import com.github.vbmacher.intellij.dbt.project.*
import com.intellij.codeInsight.TargetElementEvaluatorEx2
import com.intellij.codeInsight.TargetElementUtil
import com.intellij.find.findUsages.FindUsagesHandler
import com.intellij.find.findUsages.FindUsagesHandlerFactory
import com.intellij.find.usages.api.*
import com.intellij.find.usages.symbol.SymbolSearchTargetFactory
import com.intellij.lang.findUsages.FindUsagesProvider
import com.intellij.model.Pointer
import com.intellij.model.Symbol
import com.intellij.model.psi.ImplicitReferenceProvider
import com.intellij.model.psi.PsiSymbolReference
import com.intellij.navigation.NavigatableSymbol
import com.intellij.navigation.SymbolNavigationService
import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.SymbolDocumentationTargetProvider
import com.intellij.platform.backend.navigation.NavigationTarget
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.*
import com.intellij.psi.search.PsiSearchScopeUtil
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.util.ProcessingContext
import com.intellij.util.Processor
import com.intellij.util.Query
import org.jetbrains.yaml.psi.YAMLKeyValue

/** Supplies exact reference ranges to native navigation and Ctrl-hover for any source language. */
class DbtImplicitReferenceProvider : ImplicitReferenceProvider {
    override fun getImplicitReference(element: PsiElement, offsetInElement: Int): PsiSymbolReference? {
        val file = element as? PsiFile ?: return null
        val references = DbtNavigation.at(file, offsetInElement)
        val candidates = references.flatMap { it.candidates }.distinct()
        val first = references.firstOrNull()?.takeIf { candidates.isNotEmpty() } ?: return null
        return object : PsiSymbolReference {
            override fun getElement(): PsiElement = file
            override fun getRangeInElement(): TextRange = first.rangeInElement
            override fun resolveReference(): Collection<Symbol> =
                candidates.map { DbtPsiSymbol(it, file, offsetInElement) }
        }
    }
}

/** Plain SQL and TextMate hosts expose dbt references on the file rather than on individual tokens. */
class DbtReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(PsiFile::class.java),
            object : PsiReferenceProvider() {
                override fun getReferencesByElement(
                    element: PsiElement,
                    context: ProcessingContext
                ): Array<PsiReference> {
                    val file = element as PsiFile
                    return if (file is PsiPlainTextFile || file.language.id == "textmate") DbtNavigation.inFile(file)
                        .toTypedArray() else emptyArray()
                }
            })
    }
}

/** Query the cached workspace and resolve identities, including names that the IDE word index splits. */
class DbtReferencesSearch : QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters>(true) {
    override fun processQuery(parameters: ReferencesSearch.SearchParameters, consumer: Processor<in PsiReference>) {
        val declaration = parameters.elementToSearch
        val workspace = DbtWorkspace.get(declaration.project)
        val supported = when (declaration) {
            is DbtSymbolElement -> declaration.symbol.kind != "documentation"
            is YAMLKeyValue -> true
            else -> workspace.symbols.any { it.file == declaration.containingFile && it.range.intersects(declaration.textRange) }
        }
        if (!supported) return
        val scope = parameters.effectiveSearchScope
        val files = workspace.files + workspace.roots.flatMap { r ->
            DbtEnvironment.resolve(workspace, r, declaration.text).map { it.file }
        }
        for (file in files.distinct()) {
            ProgressManager.checkCanceled()
            if (!PsiSearchScopeUtil.isInScope(scope, file)) continue
            for (ref in DbtNavigation.inFile(file)) {
                if (ref.isReferenceTo(declaration) && file.findElementAt(ref.rangeInElement.startOffset)
                        ?.let { PsiSearchScopeUtil.isInScope(scope, it) } == true
                )
                    if (!consumer.process(ref)) return
            }
        }
    }
}

class DbtFindUsagesProvider : FindUsagesProvider {
    override fun canFindUsagesFor(element: PsiElement) = element is DbtSymbolElement
    override fun getHelpId(element: PsiElement) = "find.other.usages"
    override fun getType(element: PsiElement) = (element as? DbtSymbolElement)?.symbol?.kind ?: "dbt declaration"
    override fun getDescriptiveName(element: PsiElement) =
        if (element is DbtSymbolElement) "${element.symbol.namespace}.${element.name}${element.symbol.metadataSuffix}" else element.text

    override fun getNodeText(element: PsiElement, useFullName: Boolean) = getDescriptiveName(element)
}

/** Make dbt declarations available to native Find Usages actions. */
class DbtTargetElementEvaluator : TargetElementEvaluatorEx2() {
    @Suppress("SENSELESS_COMPARISON")
    override fun adjustReferenceOrReferencedElement(
        file: PsiFile,
        editor: Editor,
        offset: Int,
        flags: Int,
        refElement: PsiElement?
    ): PsiElement? {
        if (refElement != null || file == null || (flags and TargetElementUtil.ELEMENT_NAME_ACCEPTED) == 0) return refElement
        val workspace = DbtWorkspace.get(file.project)
        val lexical = DbtNavigation.inFile(file).flatMap { it.candidates }.filterIsInstance<DbtSymbolElement>()
            .filter { it.symbol.file == file && !it.symbol.metadata && it.symbol.range.containsOffset(offset) }
            .map { it.symbol }
        val sqlDeclarations = DbtUsages.sqlDeclarationsAt(workspace, file, offset)
        return ((sqlDeclarations + workspace.symbolAt(
            file,
            offset
        ) + lexical).distinct() + workspace.codeSymbols(file)).firstOrNull()?.element
    }
}

/** Maps property declarations to their owning resources before handing targets to native Find Usages. */
class DbtFindUsagesHandlerFactory : FindUsagesHandlerFactory() {
    private fun targets(element: PsiElement): List<PsiElement> {
        if (element is DbtSymbolElement) return if (element.symbol.kind == "documentation") emptyList() else listOf(
            element
        )
        val file = element.containingFile ?: return emptyList()
        val offset = DbtUsages.caretEditor(file)?.caretModel?.offset ?: element.textOffset
        val workspace = DbtWorkspace.get(element.project)
        val exact = workspace.symbolAt(file, offset)
        return exact.ifEmpty { workspace.codeSymbols(file) }.flatMap { symbol ->
            if (symbol.isProperty) workspace.owner(symbol)?.let { root ->
                workspace.declarations(setOf(symbol.kind), symbol.name, root, symbol.namespace)
                    .filter { it.version == symbol.version }.map { it.element }
            } ?: emptyList() else listOf(symbol.element)
        }
    }

    override fun canFindUsages(element: PsiElement) = targets(element).isNotEmpty()
    override fun createFindUsagesHandler(element: PsiElement, forHighlightUsages: Boolean): FindUsagesHandler? {
        val first = targets(element).firstOrNull() ?: return null
        return object : FindUsagesHandler(first) {
            override fun getPrimaryElements(): Array<PsiElement> = targets(element).toTypedArray()
        }
    }
}

/** Public Symbol contracts retain native navigation, documentation and usage search for dbt PSI targets. */
class DbtPsiSymbol(val element: PsiElement, val source: PsiFile, val offset: Int) : NavigatableSymbol {
    override fun createPointer(): Pointer<DbtPsiSymbol> {
        val target = SmartPointerManager.createPointer(element)
        return Pointer.fileRangePointer(source, TextRange(offset, offset)) { fresh, range ->
            target.dereference()?.takeIf { it.isValid }?.let { DbtPsiSymbol(it, fresh, range.startOffset) }
        }
    }

    override fun getNavigationTargets(project: Project): Collection<NavigationTarget> =
        listOf(SymbolNavigationService.getInstance().psiElementNavigationTarget(element))

    fun presentation(): TargetPresentation {
        val name = when (element) {
            is DbtSymbolElement -> element.symbol.presentableText
            is PsiNamedElement -> element.name.orEmpty()
            else -> element.text
        }
        return TargetPresentation.builder(name).icon(element.getIcon(0)).presentation()
    }

    override fun equals(other: Any?) = other is DbtPsiSymbol && element == other.element
    override fun hashCode() = element.hashCode()
}

class DbtSymbolDocumentationTargetProvider : SymbolDocumentationTargetProvider {
    override fun documentationTarget(project: Project, target: Symbol): DocumentationTarget? =
        (target as? DbtPsiSymbol)?.let { DbtDocumentation.targetAt(it.source, it.offset) }
}

/** Adds symbol search targets only when the platform has not already supplied the equivalent PSI target. */
class DbtSymbolSearchTargetFactory : SymbolSearchTargetFactory<DbtPsiSymbol> {
    override fun searchTarget(project: Project, symbol: DbtPsiSymbol): SearchTarget? = when {
        (symbol.element as? DbtSymbolElement)?.symbol?.kind == "documentation" -> null
        symbol.source.findReferenceAt(symbol.offset)?.isReferenceTo(symbol.element) == true -> null
        else -> DbtSymbolSearchTarget(symbol)
    }
}

internal class DbtSymbolSearchTarget(val symbol: DbtPsiSymbol) : SearchTarget {
    override fun createPointer(): Pointer<out SearchTarget> =
        Pointer.delegatingPointer(symbol.createPointer()) { DbtSymbolSearchTarget(it) }

    override fun presentation() = symbol.presentation()
    override val usageHandler: UsageHandler get() = UsageHandler.createEmptyUsageHandler(presentation().presentableText)
    override fun equals(other: Any?) = other is DbtSymbolSearchTarget && symbol == other.symbol
    override fun hashCode() = symbol.hashCode()
}

class DbtSymbolUsageSearcher : UsageSearcher {
    override fun collectSearchRequest(parameters: UsageSearchParameters): Query<out Usage>? =
        (parameters.target as? DbtSymbolSearchTarget)?.let { target ->
            ReferencesSearch.search(target.symbol.element, parameters.searchScope)
                .mapping<Usage> { reference -> PsiUsage.textUsage(reference.element, reference.rangeInElement) }
        }

    // Explicit defaults avoid generated calls to the SDK's override-only methods.
    override fun collectImmediateResults(parameters: UsageSearchParameters): Collection<Usage> = emptyList()

    override fun collectSearchRequests(parameters: UsageSearchParameters): Collection<Query<out Usage>> =
        listOfNotNull(collectSearchRequest(parameters))
}

internal object DbtUsages {
    fun caretEditor(file: PsiFile): Editor? = FileEditorManager.getInstance(file.project).selectedTextEditor
        ?.takeIf { it.document == PsiDocumentManager.getInstance(file.project).getDocument(file) }

    fun sqlDeclarationsAt(w: DbtWorkspace, file: PsiFile, offset: Int): List<DbtSymbol> =
        DbtSqlAnalysis.get(w, file)?.declarations?.filter { it.range.containsOffset(offset) } ?: emptyList()
}
