package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.navigation.DbtNavigation
import com.github.vbmacher.intellij.dbt.navigation.DbtNavigationReference
import com.github.vbmacher.intellij.dbt.navigation.DbtSqlAnalysis
import com.github.vbmacher.intellij.dbt.project.*
import com.github.vbmacher.intellij.dbt.syntax.DbtSyntax
import com.github.vbmacher.intellij.dbt.syntax.DbtYaml
import com.intellij.lang.refactoring.RefactoringSupportProvider
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchScopeUtil
import com.intellij.psi.search.SearchScope
import com.intellij.refactoring.listeners.RefactoringElementListener
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.refactoring.safeDelete.NonCodeUsageSearchInfo
import com.intellij.refactoring.safeDelete.SafeDeleteProcessorDelegateBase
import com.intellij.refactoring.safeDelete.usageInfo.SafeDeleteReferenceSimpleDeleteUsageInfo
import com.intellij.usageView.UsageInfo
import com.intellij.util.IncorrectOperationException
import com.intellij.util.containers.MultiMap

/** Shares identity-based usage discovery between native Rename and Safe Delete; textual matches alone are insufficient. */
internal object DbtRefactoring {
    val Kinds = setOf("model", "macro", "cte", "sql_alias", "sql_projection", "column")
    fun models(w: DbtWorkspace, column: DbtSymbol) = w.relations(column).filter { it.kind == "model" && it.isCode }
    fun references(
        w: DbtWorkspace,
        targets: List<PsiElement>,
        scope: SearchScope? = null
    ): List<DbtNavigationReference> =
        w.files.filter { scope?.let { s -> PsiSearchScopeUtil.isInScope(s, it) } ?: true }
            .flatMap { DbtNavigation.inFile(it) }.filter { r -> targets.any(r::isReferenceTo) }
}

/** Native rename drives preview/conflicts/undo; every usage is resolved by the existing reference providers. */
class DbtRenameProcessor : RenamePsiElementProcessor() {
    private class FamilyEdits(val ranges: List<SmartPsiFileRange>) {
        var applied = false
    }

    private val familyEdits = Key.create<FamilyEdits>("dbt.rename.family.edits")
    override fun renameElement(
        element: PsiElement,
        name: String,
        usages: Array<UsageInfo>,
        listener: RefactoringElementListener?
    ) {
        val family = element.getUserData(familyEdits)
        val edits = if (family?.applied == true) emptyMap() else (family?.ranges?.mapNotNull { pointer ->
            val range = pointer.range
            val file = pointer.containingFile
            if (range != null && file != null) file to TextRange(range.startOffset, range.endOffset) else null
        } ?: usages.mapNotNull { usage ->
            val source = usage.element ?: return@mapNotNull null
            val file = source.containingFile ?: return@mapNotNull null
            val range = usage.rangeInElement ?: return@mapNotNull null
            file to range.shiftRight(if (source is PsiFile) 0 else source.textOffset)
        }).distinct().groupBy { it.first }
        edits.forEach { (file, changes) ->
            val documents = PsiDocumentManager.getInstance(file.project)
            val document = documents.getDocument(file)!!
            changes.map { it.second }.sortedByDescending { it.startOffset }.forEach { range ->
                val existing = document.getText(range)
                val old = (element as DbtSymbolElement).name
                val quote = DbtSymbolElement.quote(existing)
                val raw = if (quote != null) existing.drop(1).dropLast(1) else existing
                if (raw == old) document.replaceString(
                    range.startOffset,
                    range.endOffset,
                    if (quote == null) name else "$quote$name$quote"
                )
                else if (raw != name) throw IncorrectOperationException("A resolving usage changed; reopen the refactoring preview.")
            }
            documents.commitDocument(document)
        }
        family?.applied = true
        (element as PsiNamedElement).setName(name)
        listener?.elementRenamed(element)
    }

    override fun canProcessElement(element: PsiElement) =
        element is DbtSymbolElement && element.symbol.kind in DbtRefactoring.Kinds

    override fun findReferences(
        element: PsiElement,
        scope: SearchScope,
        searchInCommentsAndStrings: Boolean
    ): Collection<PsiReference> {
        val w = DbtWorkspace.get(element.project)
        val aliases = if (element is DbtSymbolElement && element.symbol.kind == "sql_projection") exported(
            w,
            element
        ).flatMap { (resource, _) ->
            val columns = w.columns(resource).filter { it.name == element.name }
            if (columns.any { it.mapping != null }) emptyList() else columns.map { it.element }
        } else emptyList()
        return DbtRefactoring.references(w, listOf(element) + aliases, scope)
    }

    private fun exported(w: DbtWorkspace, e: DbtSymbolElement) =
        w.codeSymbols(e.containingFile).filter { it.kind == "model" }.flatMap { model ->
            DbtSqlAnalysis.get(w, model.file)?.columns?.filter { it.range == e.textRange && it.name == e.name }
                ?.map { model to it } ?: emptyList()
        }

    override fun prepareRenaming(element: PsiElement, name: String, renames: MutableMap<PsiElement, String>) {
        val e = element as? DbtSymbolElement ?: return
        val w = DbtWorkspace.get(e.project)
        when {
            e.symbol.kind in setOf("model", "macro") -> {
                val family = w.symbols.filter { s ->
                    s.kind == e.symbol.kind && s.name == e.symbol.name && s.namespace == e.symbol.namespace &&
                            w.owner(s) == w.owner(e.symbol) && (s.isCode || s.isProperty || s.kind == "macro" && "block-start" in s.attributes)
                }
                if (e.symbol.kind == "model") {
                    val all = (family + e.symbol).map { it.element }
                    val ranges = DbtRefactoring.references(w, all).filter { r ->
                        r.element.findElementAt(r.rangeInElement.startOffset)
                            ?.let { "defined_in" in DbtYaml.ancestors(it) } != true
                    }.distinctBy { it.element to it.rangeInElement }.mapNotNull { r ->
                        val range = r.rangeInElement
                        val raw = range.substring(r.element.text)
                        val nameRange = when (raw) {
                            e.name -> range; "\"${e.name}\"", "'${e.name}'" -> TextRange(
                                range.startOffset + 1,
                                range.endOffset - 1
                            ); else -> null
                        }
                        nameRange?.let {
                            SmartPointerManager.getInstance(e.project).createSmartPsiFileRangePointer(r.element, it)
                        }
                    }
                    val plan = FamilyEdits(ranges)
                    all.forEach { it.putUserData(familyEdits, plan) }; e.putUserData(familyEdits, plan)
                }
                family.distinctBy { it.file to it.range }.filter { it.id != e.symbol.id }
                    .forEach { renames[it.element] = name }
                w.properties(e.symbol).distinctBy { it.file to it.range }.forEach { renames[it.element] = name }
            }

            e.symbol.kind == "sql_projection" -> exported(w, e).forEach { (resource, _) ->
                w.columns(resource).filter { it.name == e.name && it.mapping != null }
                    .forEach { renames[it.element] = name }
            }

            e.symbol.kind == "column" -> DbtRefactoring.models(w, e.symbol).forEach { model ->
                DbtSqlAnalysis.get(w, model.file)?.columns?.firstOrNull { it.name == e.name }?.let { column ->
                    if (model.file == e.containingFile && column.range == e.textRange && !column.outputAlias) e.prepareOutputAliasRename()
                    else if (model.file != e.containingFile || column.range != e.textRange) renames[DbtSymbol(
                        "sql_projection",
                        column.name,
                        model.namespace,
                        model.file,
                        column.range,
                        attributes = if (column.outputAlias) emptyMap() else mapOf("insert-output-alias" to "true")
                    ).element] = name
                }
            }
        }
    }

    override fun findExistingNameConflicts(element: PsiElement, name: String, conflicts: MultiMap<PsiElement, String>) {
        val e = element as? DbtSymbolElement ?: return
        val s = e.symbol
        val w = DbtWorkspace.get(e.project)
        if (s.kind == "column" && DbtRefactoring.models(w, s).none { model ->
                model.file.virtualFile.extension == "sql" && DbtSqlAnalysis.get(w, model.file)?.let { a ->
                    a.issues.isEmpty() && a.columns.count { it.name == s.name } == 1 && a.columns.any { it.name == s.name && it.complete }
                } == true
            })
            conflicts.putValue(
                e,
                "Choose a proven, unambiguous SQL model output column. Physical columns and unavailable output schemas require source editing."
            )
        if (s.kind == "column" && w.symbols.any { it.kind == "column" && it.file == s.file && it.range == s.range && it.version != s.version })
            conflicts.putValue(
                e,
                "This column declaration is shared by model versions. Create explicit version overrides before renaming it."
            )
        if (!DbtSyntax.isIdentifier(name)) conflicts.putValue(e, "Use a valid dbt identifier.")
        if (!e.isWritable) conflicts.putValue(e, "Installed packages and artifact-only declarations are read-only.")
        if (w.symbols.any {
                it.kind == s.kind && it.name == name && it.namespace == s.namespace && it.owner == s.owner && w.owner(
                    it
                ) == w.owner(s)
            })
            conflicts.putValue(e, "A ${s.kind} named '$name' already exists in this scope.")
        DbtSqlAnalysis.get(w, s.file)?.let { analysis ->
            if (analysis.issues.any { it.startsWith("Explicit CTE column lists") }) conflicts.putValue(
                e,
                "Explicit CTE column lists require source editing; their complete usages cannot be proven."
            )
            val scope = analysis.scope(e.textOffset)
            fun parents(range: TextRange) = analysis.ctes.filter { it.body.contains(range) }.map { it.range }.toSet()
            val collision = when (s.kind) {
                "cte" -> analysis.ctes.any {
                    it.name.equals(
                        name,
                        ignoreCase = true
                    ) && !it.range.contains(e.textRange) && parents(it.range) == parents(e.textRange)
                }

                "sql_alias" -> scope?.relations?.any { it.first.equals(name, ignoreCase = true) } == true
                "sql_projection" -> (analysis.columns + analysis.intermediates).any {
                    it.name.equals(
                        name,
                        ignoreCase = true
                    ) && it.range != e.textRange && analysis.scope(it.range.startOffset) == scope
                }

                else -> false
            }
            if (collision) conflicts.putValue(e, "'$name' is already declared in this SQL scope.")
        }
        val usages = DbtRefactoring.references(w, listOf(e))
        if (usages.any { r ->
                r.candidates.size != 1 && !(s.kind == "model" && r.candidates.all { target ->
                    target is DbtSymbolElement && target.symbol.kind == "model" && target.symbol.name == s.name &&
                            target.symbol.namespace == s.namespace && w.owner(target.symbol) == w.owner(s)
                })
            }) conflicts.putValue(e, "Some usages resolve ambiguously.")
        if ((s.kind == "sql_projection" || s.kind == "column" && s.file.virtualFile.extension == "sql") && (DbtSqlAnalysis.get(
                w,
                s.file
            )?.issues?.isNotEmpty() ?: true)
        )
            conflicts.putValue(e, "Column analysis is incomplete; review dynamic consumers before renaming.")
        if (s.kind in setOf("column", "sql_projection", "model", "macro") && w.files.filter { f ->
                w.root(f)?.let { from ->
                    w.owner(s)?.let { owner -> w.namespaceVisible(from, owner, qualified = true) }
                } == true
            }.any { f ->
                DbtSyntax.inFile(f).flatMap(DbtSyntax::calls)
                    .any {
                        it.name in setOf(
                            "ref",
                            "source",
                            "adapter.dispatch"
                        ) && it.arguments.any { a -> a.literal == null }
                    }
            })
            conflicts.putValue(e, "Dynamic consumers exist; the complete set of usages cannot be proven.")
    }
}

class DbtSafeDeleteSupport : RefactoringSupportProvider() {
    override fun isAvailable(element: PsiElement) = DbtSafeDelete.targets(element).isNotEmpty()
    override fun isSafeDeleteAvailable(element: PsiElement) = DbtSafeDelete.targets(element).isNotEmpty()
}

/**
 * Resolves native selections to dbt declarations and rejects unsafe or unproven deletion plans.
 * SQL ranges come from complete analysis; related YAML edits participate in the native refactoring and undo lifecycle.
 */
class DbtSafeDelete : SafeDeleteProcessorDelegateBase() {
    override fun handlesElement(element: PsiElement) = targets(element).isNotEmpty()
    override fun getElementsToSearch(
        element: PsiElement,
        module: Module?,
        all: Collection<PsiElement>
    ): Collection<PsiElement> = targets(element)

    override fun getAdditionalElementsToDelete(
        element: PsiElement,
        all: Collection<PsiElement>,
        ask: Boolean
    ): Collection<PsiElement> {
        val e = element as? DbtSymbolElement ?: return emptyList()
        val w = DbtWorkspace.get(e.project)
        return when {
            e.symbol.kind == "model" || e.symbol.kind == "macro" -> (w.properties(e.symbol) + w.symbols.filter { s ->
                e.symbol.isProperty && s.sameResource(e.symbol) && w.owner(s) == w.owner(e.symbol) && (s.isCode || s.kind == "macro" && "block-start" in s.attributes)
            })
                .filter { it.id != e.symbol.id }.distinctBy { it.id }.map { it.element }

            e.symbol.kind == "sql_projection" -> w.codeSymbols(e.containingFile).filter { s ->
                s.kind == "model" && DbtSqlAnalysis.get(
                    w,
                    s.file
                )?.columns?.any { it.name == e.name && it.range == e.textRange } == true
            }
                .flatMap(w::columns).filter { it.name == e.name && it.mapping != null }
                .map { it.copy(attributes = it.attributes + ("properties-only-delete" to "true")).element }

            else -> emptyList()
        }
    }

    override fun findUsages(
        element: PsiElement,
        all: Array<PsiElement>,
        usages: MutableList<in UsageInfo>
    ): NonCodeUsageSearchInfo? {
        DbtRenameProcessor().findReferences(element, GlobalSearchScope.projectScope(element.project), false)
            .filter { ref ->
                all.none {
                    when (it) {
                        is DbtSymbolElement -> it.containingFile == ref.element.containingFile && (it.symbol.isCode || it.symbol.mapping?.textRange?.contains(
                            ref.rangeInElement
                        ) == true)

                        is PsiFile -> it == ref.element.containingFile
                        else -> false
                    }
                }
            }.forEach { ref ->
            val range = ref.rangeInElement; usages.add(
            SafeDeleteReferenceSimpleDeleteUsageInfo(
                ref.element,
                element,
                range.startOffset,
                range.endOffset,
                false,
                false
            )
        )
        }
        return null
    }

    override fun findConflicts(
        element: PsiElement,
        all: Array<PsiElement>,
        usages: Array<UsageInfo>,
        conflicts: MultiMap<PsiElement, String>
    ) {
        conflictMessages(element).forEach { conflicts.putValue(element, it) }
    }

    private fun conflictMessages(element: PsiElement): List<String> {
        val e = element as? DbtSymbolElement ?: return emptyList()
        val w = DbtWorkspace.get(e.project)
        val messages = mutableListOf<String>()
        if (!e.isWritable) messages += "Installed packages and artifact-only declarations are read-only."
        if (e.symbol.kind !in setOf(
                "model",
                "macro"
            ) && sourceDeletion(e) == null && e.symbol.mapping == null
        ) messages += "This definition cannot be removed while preserving valid SQL."
        if (e.symbol.kind == "column" && "properties-only-delete" !in e.symbol.attributes && sourceDeletion(e) == null) messages += "The output projection is unavailable or ambiguous. Delete its YAML declaration explicitly in source."
        if (e.symbol.kind == "model" && e.symbol.version != null) messages += "Delete a versioned model family explicitly after reviewing every version."
        if (e.symbol.kind == "column" && w.symbols.any { it.kind == "column" && it.file == e.symbol.file && it.range == e.symbol.range && it.version != e.symbol.version }) messages += "This column declaration is shared by model versions. Create explicit version overrides before deleting it."
        if (e.symbol.kind in setOf("model", "macro", "column", "sql_projection")) {
            val localFiles = w.files.filter { f ->
                w.root(f)?.let { r ->
                    w.owner(e.symbol)?.let { owner -> w.executionRoot(r) == w.executionRoot(owner) }
                } == true
            }
            if (localFiles.any { f ->
                    DbtSyntax.inFile(f).flatMap(DbtSyntax::calls).any {
                        it.name in setOf(
                            "ref",
                            "source",
                            "builtins.ref",
                            "builtins.source",
                            "adapter.dispatch"
                        ) && it.arguments.any { a -> a.literal == null }
                    }
                })
                messages += "Dynamic consumers exist; the complete set of usages cannot be proven."
            if (e.symbol.kind in DbtSymbol.ColumnKinds && localFiles.filter { it.name.endsWith(".sql") }
                    .any { DbtSqlAnalysis.get(w, it)?.issues?.isNotEmpty() ?: true })
                messages += "SQL analysis is incomplete; dependent column usages cannot be proven."
        }
        return messages
    }

    override fun preprocessUsages(project: Project, usages: Array<UsageInfo>) = usages
    private fun sourceDeletion(element: DbtSymbolElement): Pair<PsiFile, TextRange>? {
        val w = DbtWorkspace.get(element.project)
        val s = element.symbol
        if ("properties-only-delete" in s.attributes) return null
        val direct = DbtSqlAnalysis.get(w, s.file)
            ?.takeIf { it.issues.isEmpty() }?.deletions?.firstOrNull { it.first == element.textRange }
            ?.let { s.file to it.second }
        if (direct != null || s.kind != "column") return direct
        val model = DbtRefactoring.models(w, s).firstOrNull() ?: return null
        val a = DbtSqlAnalysis.get(w, model.file)?.takeIf { it.issues.isEmpty() } ?: return null
        val columns = a.columns.filter { it.name == s.name }
        return if (columns.size == 1 && columns.first().complete) a.deletions.firstOrNull { it.first == columns.first().range }
            ?.let { model.file to it.second } else null
    }

    override fun prepareForDeletion(element: PsiElement) {
        val e = element as? DbtSymbolElement ?: return
        sourceDeletion(e)?.let { (file, range) ->
            if (file == e.containingFile) e.prepareSourceDeletion(range) else {
                val manager = PsiDocumentManager.getInstance(e.project)
                manager.getDocument(file)!!
                    .deleteString(range.startOffset, range.endOffset); manager.commitAllDocuments()
            }
        }
    }

    override fun isToSearchInComments(element: PsiElement) = false
    override fun setToSearchInComments(element: PsiElement, value: Boolean) {}
    override fun isToSearchForTextOccurrences(element: PsiElement) = false
    override fun setToSearchForTextOccurrences(element: PsiElement, value: Boolean) {}

    companion object {
        internal fun targets(element: PsiElement): List<DbtSymbolElement> {
            if (element is DbtSymbolElement && element.symbol.kind in DbtRefactoring.Kinds) return listOf(element)
            if (element is PsiFile) {
                val w = DbtWorkspace.get(element.project)
                if (w.root(element)?.resource(element) != true) return emptyList()
                return (w.codeSymbols(element)
                    .filter { it.kind == "model" } + w.symbols.filter { it.file == element && it.kind == "macro" && "block-start" in it.attributes }).map { it.element }
                    .distinct()
            }
            val file = element.containingFile ?: return emptyList()
            val w = DbtWorkspace.get(file.project)
            val active = DbtUsages.caretEditor(file) ?: return emptyList()
            val offset = active.caretModel.offset
            val declarations = DbtUsages.sqlDeclarationsAt(w, file, offset)
            val authored = w.symbolAt(file, offset).filter { it.kind in DbtRefactoring.Kinds }
            val references = DbtNavigation.resolvedAt(file, offset).filter { it.kind in DbtRefactoring.Kinds }
            val exact =
                (declarations.ifEmpty { authored.ifEmpty { references } }).filter { it.kind in DbtRefactoring.Kinds }
                    .map { it.element }.distinct()
            return if (exact.size == 1) exact else emptyList()
        }
    }
}
