package com.github.vbmacher.intellij.dbt.navigation

import com.github.vbmacher.intellij.dbt.project.*
import com.github.vbmacher.intellij.dbt.syntax.DbtSyntax
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.*
import com.intellij.psi.util.*
import com.intellij.util.IncorrectOperationException
import java.util.concurrent.ConcurrentHashMap

/** Provider input; [found] contains references from earlier providers so later providers can respect prior ownership. */
data class DbtNavigationContext(
    val file: PsiFile,
    val workspace: DbtWorkspace,
    val root: DbtRoot,
    val includeSelections: Boolean,
    val found: List<DbtNavigationReference>
)

/**
 * Language adapter registered at the reference-provider extension point.
 * Nullable analysis answers mean the provider does not handle the input; a failed [Result] means it handled
 * an input that could not be analyzed. Providers retain ambiguous candidates instead of choosing one arbitrarily.
 */
interface DbtReferenceProvider {
    fun references(context: DbtNavigationContext): List<DbtNavigationReference>
    fun relationRanges(sql: String): List<TextRange> = emptyList()
    fun sqlAnalysis(file: PsiFile, workspace: DbtWorkspace, completionOffset: Int? = null): Result<DbtSqlAnalysis>? =
        null

    fun compiledCtes(sql: String): Result<DbtSqlAnalysis>? = null
    fun sqlSuppressionLayout(file: PsiFile): DbtSqlSuppressionLayout? = null
    fun projectedColumns(sql: String): Pair<List<String>, Boolean>? = null
    fun withPrefix(text: String, end: Int): String? = null

    companion object {
        val EP: ExtensionPointName<DbtReferenceProvider> =
            ExtensionPointName.create("com.github.vbmacher.intellij-dbt.referenceProvider")

        fun <A> first(answer: (DbtReferenceProvider) -> A?): A? = EP.extensionList.firstNotNullOfOrNull(answer)
    }
}

class DbtNavigationReference(
    val file: PsiFile,
    range: TextRange,
    val candidates: List<PsiElement>,
    val label: String,
    val evidence: List<String> = emptyList(),
    val unresolvedMessage: String? = null
) : PsiPolyVariantReferenceBase<PsiFile>(file, range, true) {
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        candidates.distinct().map { PsiElementResolveResult(it) }.toTypedArray()

    override fun getCanonicalText(): String = label
    override fun handleElementRename(newName: String): PsiElement {
        val workspace = DbtWorkspace.get(file.project)
        val models =
            candidates.filterIsInstance<DbtSymbolElement>().filter { it.symbol.kind == "model" }.map { it.symbol }
        val family = models.size == candidates.size && models.isNotEmpty() && models.map {
            Triple(
                it.namespace,
                it.name,
                workspace.owner(it)
            )
        }.distinct().size == 1
        if (!DbtSyntax.isIdentifier(newName) || candidates.size != 1 && !family) throw IncorrectOperationException(
            "Ambiguous reference '$label' cannot be renamed: ${
                candidates.joinToString(
                    ", "
                )
            }"
        )
        val documents = PsiDocumentManager.getInstance(file.project)
        val document = documents.getDocument(file)!!
        document.replaceString(rangeInElement.startOffset, rangeInElement.endOffset, newName)
        documents.commitDocument(document)
        return file
    }

    override fun isReferenceTo(element: PsiElement): Boolean = candidates.any { a ->
        when {
            a is DbtSymbolElement && a.symbol.kind == "documentation" -> false
            a is DbtSymbolElement && element is DbtSymbolElement && element.symbol.isProperty && !a.symbol.isProperty -> a.isValid && element.isValid && DbtWorkspace.get(
                a.project
            ).properties(a.symbol)
                .any { p -> p.file == element.containingFile && p.kind == element.symbol.kind && p.name == element.symbol.name && p.version == element.symbol.version && p.element.textRange == element.textRange }

            a is DbtSymbolElement && element is DbtSymbolElement -> a.isValid && element.isValid && a.containingFile == element.containingFile && (a.textRange == element.textRange || a.symbol.isCode && element.symbol.isCode) && a.symbol.kind == element.symbol.kind && a.symbol.name == element.symbol.name && a.symbol.namespace == element.symbol.namespace && a.symbol.owner == element.symbol.owner && a.symbol.version == element.symbol.version
            else -> a == element || a.manager.areElementsEquivalent(a, element)
        }
    }
}

object DbtNavigation {
    fun withPrefix(text: String, end: Int): String? = DbtReferenceProvider.first { it.withPrefix(text, end) }
    fun projectedColumns(sql: String): Pair<List<String>, Boolean>? =
        DbtReferenceProvider.first { it.projectedColumns(sql) }

    fun relationRanges(sql: String): List<TextRange> =
        DbtReferenceProvider.EP.extensionList.flatMap { it.relationRanges(sql) }.distinct()

    private val ProjectFiles = setOf(
        DbtWorkspace.ProjectFileName,
        "profiles.yml",
        "packages.yml",
        "package-lock.yml",
        "dependencies.yml",
        "selectors.yml",
        "vars.yml"
    )
    private val ReferenceCache = Key.create<CachedValue<List<DbtNavigationReference>>>("dbt.navigation.references")
    fun inFile(file: PsiFile, includeSelections: Boolean = true): List<DbtNavigationReference> = when {
        !file.isPhysical || file.virtualFile == null -> emptyList()
        !includeSelections -> compute(file, false)
        else -> CachedValuesManager.getManager(file.project).getCachedValue(file, ReferenceCache, {
            CachedValueProvider.Result.create(
                compute(file, true),
                PsiModificationTracker.MODIFICATION_COUNT,
                VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
                DbtProjectSettings.get(file.project)
            )
        }, false)
    }

    /** Selects the shortest reference ranges containing the offset, retaining all equally specific references. */
    fun at(file: PsiFile, offset: Int): List<DbtNavigationReference> {
        val matches = inFile(file).filter { it.rangeInElement.containsOffset(offset) }
        val size = matches.minOfOrNull { it.rangeInElement.length } ?: return emptyList()
        return matches.filter { it.rangeInElement.length == size }
    }

    fun resolvedAt(file: PsiFile, offset: Int): List<DbtSymbol> =
        at(file, offset).flatMap { it.candidates }.filterIsInstance<DbtSymbolElement>().map { it.symbol }

    private fun compute(file: PsiFile, includeSelections: Boolean): List<DbtNavigationReference> {
        val workspace = DbtWorkspace.get(file.project)
        val root = workspace.root(file)?.takeIf { navigable(file, it) } ?: return emptyList()
        val found = DbtReferenceProvider.EP.extensionList.fold(emptyList<DbtNavigationReference>()) { found, provider ->
            found + provider.references(DbtNavigationContext(file, workspace, root, includeSelections, found))
        }
        return found.distinctBy { it.rangeInElement to it.candidates }.sortedBy { it.rangeInElement.startOffset }
    }

    private fun navigable(file: PsiFile, r: DbtRoot): Boolean =
        !r.excluded(file.virtualFile) && (r.resource(file) || file.name in ProjectFiles || r.isFile(
            file,
            r.profilesFile
        ))
}

/** Projection provenance. [direct] and [complete] distinguish safe passthroughs from expressions or incomplete schemas; [contextInputs] track non-value dependencies. */
data class DbtSqlColumn(
    val name: String,
    val range: TextRange,
    val inputs: List<DbtSymbol>,
    val direct: Boolean,
    val expression: String,
    val complete: Boolean,
    val dataType: String? = null,
    val outputAlias: Boolean = false,
    val contextInputs: List<Pair<DbtSymbol, String>> = emptyList()
)

data class DbtSqlProblem(val range: TextRange, val message: String)
data class DbtSqlSuppressionComment(val range: TextRange, val ids: String, val file: Boolean)
data class DbtSqlSuppressionStatement(val range: TextRange, val first: Int)
data class DbtSqlSuppressionLayout(
    val statements: List<DbtSqlSuppressionStatement>,
    val comments: List<DbtSqlSuppressionComment>
)

/** Visible relation candidates for a SQL range; [complete] means all relation schemas are known. */
data class DbtSqlScope(val range: TextRange, val relations: List<Pair<String, List<DbtSymbol>>>, val complete: Boolean)
data class DbtCte(
    val name: String,
    val range: TextRange,
    val body: TextRange,
    val dependencies: List<String>,
    val sql: String
)

data class DbtSqlAnalysis(
    val columns: List<DbtSqlColumn>,
    val scopes: List<DbtSqlScope>,
    val ctes: List<DbtCte>,
    val wildcards: List<Pair<TextRange, List<String>>>,
    val issues: List<String>,
    val intermediates: List<DbtSqlColumn> = emptyList(),
    val problems: List<DbtSqlProblem> = emptyList(),
    val deletions: List<Pair<TextRange, TextRange>> = emptyList(),
    val declarations: List<DbtSymbol> = emptyList(),
    val wildcardProblems: List<DbtSqlProblem> = emptyList()
) {
    fun scope(offset: Int): DbtSqlScope? =
        scopes.filter { it.range.containsOffset(offset) }.minByOrNull { it.range.length }

    companion object {
        fun compiled(sql: String): Result<DbtSqlAnalysis> = DbtReferenceProvider.first { it.compiledCtes(sql) }
            ?: Result.failure(IllegalArgumentException("No compiled SQL analysis provider is available."))

        fun suppressionLayout(file: PsiFile): DbtSqlSuppressionLayout =
            DbtReferenceProvider.first { it.sqlSuppressionLayout(file) } ?: DbtSqlSuppressionLayout(
                emptyList(),
                emptyList()
            )

        fun forCompletion(w: DbtWorkspace, file: PsiFile, offset: Int): DbtSqlAnalysis? =
            DbtReferenceProvider.first { it.sqlAnalysis(file, w, offset) }?.getOrNull()

        private val Cache = Key.create<ConcurrentHashMap<String, DbtSqlAnalysis>>("dbt.sql.analysis")
        private val active = ThreadLocal.withInitial { emptySet<String>() }
        fun get(w: DbtWorkspace, file: PsiFile): DbtSqlAnalysis? = result(w, file).getOrNull()
        /** Caches successful analysis per workspace and file; cycles and nesting beyond 32 active files return failures without caching them. */
        fun result(w: DbtWorkspace, file: PsiFile): Result<DbtSqlAnalysis> {
            ProgressManager.checkCanceled()
            val path = file.virtualFile?.path.orEmpty()
            val reason = when {
                path.isEmpty() -> "SQL analysis requires a saved file."; path in active.get() -> "SQL analysis encountered a dependency cycle."; active.get().size >= 32 -> "SQL analysis reached its nested dependency limit."; else -> null
            }
            if (reason != null) return Result.failure(IllegalArgumentException(reason))
            val cache = synchronized(w) {
                w.getUserData(Cache) ?: ConcurrentHashMap<String, DbtSqlAnalysis>().also { w.putUserData(Cache, it) }
            }
            cache[path]?.let { return Result.success(it) }
            val previous = active.get(); active.set(previous + path)
            return try {
                val result = DbtReferenceProvider.first { it.sqlAnalysis(file, w) } ?: Result.failure(
                    IllegalArgumentException("No SQL analysis provider is available for this file.")
                )
                result.onSuccess { cache[path] = it }
            } finally {
                active.set(previous)
            }
        }
    }
}
