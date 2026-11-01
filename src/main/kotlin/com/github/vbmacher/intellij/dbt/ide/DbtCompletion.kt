package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.navigation.DbtResolution
import com.github.vbmacher.intellij.dbt.navigation.DbtSqlAnalysis
import com.github.vbmacher.intellij.dbt.project.*
import com.github.vbmacher.intellij.dbt.syntax.DbtSyntax
import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.lang.parameterInfo.*
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

internal data class DbtSuggestion(
    val text: String,
    val detail: String,
    val kind: String = "",
    val description: String = ""
)

data class DbtMacroSignature(val name: String, val parameters: List<Pair<String, String>>)

/** Offers workspace-aware names without executing dbt; qualified calls retain package and model-version boundaries. */
internal object DbtCompletion {
    val ConfigKeys = listOf(
        "materialized", "alias", "schema", "database", "tags", "enabled", "unique_key",
        "incremental_strategy", "on_schema_change", "contract", "persist_docs", "pre_hook", "post_hook"
    )
    private val Qualifier = Regex("([A-Za-z_][A-Za-z_0-9]*)\\.[A-Za-z_0-9]*$")
    /** Finds the innermost active call, including incomplete input while the user is typing. */
    fun callAt(file: PsiFile, offset: Int): DbtSyntax.Call? = DbtSyntax.inFile(file)
        .filter { it.range.startOffset <= offset && it.range.endOffset >= offset }.flatMap(DbtSyntax::calls)
        .filter { it.range.endOffset < offset && (it.expressionRange.endOffset >= offset || !it.closed) }
        .minByOrNull { it.expressionRange.length }

    fun suggestions(file: PsiFile, offset: Int): List<DbtSuggestion> {
        if (!file.isPhysical || DbtWorkspace.rootForFile(file.project, file.virtualFile)?.let { r ->
                !r.excluded(file.virtualFile) && (r.resource(file) || file.name == "selectors.yml")
            } != true) return emptyList()
        val w = DbtWorkspace.get(file.project)
        val root = w.root(file)
            ?.takeIf { !it.excluded(file.virtualFile) && (it.resource(file) || file.name == "selectors.yml") }
            ?: return emptyList()

        fun resources(kinds: Set<String>, namespace: String? = null): List<DbtSymbol> =
            w.symbols.filter { it.kind in kinds }.map { it.name }.distinct().flatMap { name ->
                ProgressManager.checkCanceled(); w.declarations(kinds, name, root, namespace)
            }.distinctBy { it.uniqueId to w.owner(it) }

        fun suggestion(s: DbtSymbol) = DbtSuggestion(
            s.name,
            s.kind + " · " + s.namespace + (s.version?.let { " v$it" } ?: ""),
            s.kind,
            s.attributes["description"].orEmpty())

        val blocks = DbtSyntax.inFile(file)
        val block = blocks.firstOrNull { it.range.startOffset <= offset && it.range.endOffset >= offset }
        val call = callAt(file, offset)
        val argument = call?.arguments?.withIndex()
            ?.firstOrNull { (_, a) -> a.tokens.any { it.range.startOffset <= offset && it.range.endOffset >= offset } }
        val unfinishedQuote = call?.let { c ->
            var quote: Char? = null
            var escaped = false
            file.text.substring(c.range.endOffset, offset).forEach { char ->
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (quote == char) quote = null
                else if (quote == null && (char == '\'' || char == '"')) quote = char
            }
            quote != null
        } == true
        val quoted = argument?.value?.tokens?.any { it.string } == true || unfinishedQuote
        if (quoted) {
            val c = call ?: return emptyList()
            val index = argument?.index ?: c.arguments.size
            val suggestions = when (c.name) {
                "ref", "builtins.ref" -> {
                    val namespace = if (index == 1) c.literal(0) else null
                    if (argument?.value?.keyword in setOf("v", "version")) {
                        c.qualifiedName?.let { (packageName, name) ->
                            resources(DbtSymbol.RefKinds, packageName).filter { it.name == name }
                                .mapNotNull { it.version }.distinct()
                                .map { DbtSuggestion(it, "model version", "model") }
                        } ?: emptyList()
                    } else if (index == 0 && c.positional.size >= 2) {
                        w.symbols.filter { it.kind in DbtSymbol.RefKinds }.groupBy { it.namespace }.toSortedMap()
                            .mapNotNull { (packageName, symbols) ->
                                if (symbols.any {
                                        w.declarations(setOf(it.kind), it.name, root, packageName).isNotEmpty()
                                    }) DbtSuggestion(packageName, "package", "package") else null
                            }
                    } else if (c.version != null && c.version?.value == null) emptyList()
                    else resources(DbtSymbol.RefKinds, namespace).groupBy { it.name }.toSortedMap()
                        .flatMap { (_, candidates) ->
                            DbtResolution.versioned(candidates, c.version?.value).map(::suggestion)
                        }
                }

                "source", "builtins.source" -> if (index == 0) resources(setOf("source")).map(::suggestion)
                else c.literal(0)?.let { source ->
                    w.symbols.filter { it.kind == "source_table" && it.owner == source }
                        .map { it.name }.distinct().flatMap { DbtResolution.sourceTables(w, root, source, it) }
                        .map(::suggestion)
                } ?: emptyList()

                "doc" -> resources(setOf("doc"), if (index == 1) c.literal(0) else null).map(::suggestion)
                "var" -> resources(setOf("var")).map(::suggestion)
                "config" -> ConfigKeys.map { DbtSuggestion(it, "configuration", "config") }
                "adapter.dispatch" -> resources(setOf("macro"), c.literal(1)).map(::suggestion)
                    .map { it.copy(text = it.text.split("__", limit = 2).last()) }

                else -> emptyList()
            }
            return suggestions.distinctBy { it.text to it.detail }
        }
        if (block != null) {
            val prefix = file.text.substring(block.range.startOffset, offset)
            val namespace = Qualifier.find(prefix)?.groupValues?.get(1)
            val named = call?.let { w.macros(it, root) }?.flatMap { m ->
                parameters(w, m).map { (name, display) ->
                    DbtSuggestion(
                        "$name=",
                        display,
                        "parameter"
                    )
                }
            } ?: emptyList()
            val config = if (call?.name == "config") ConfigKeys.map {
                DbtSuggestion(
                    "$it=",
                    "configuration",
                    "config"
                )
            } else emptyList()
            val builtins = if (namespace == null && call == null) listOf(
                "ref",
                "source",
                "doc",
                "var",
                "config"
            ).map { DbtSuggestion(it, "dbt", "builtin") } else emptyList()
            return (named + config + builtins + resources(
                setOf("macro"),
                namespace
            ).map(::suggestion)).distinctBy { it.text }
        }
        if (file.name == "selectors.yml") return (w.symbols.filter { s ->
            w.owner(s)?.let { w.namespaceVisible(root, it, qualified = true) } == true
        }.flatMap { s ->
            listOf(DbtSuggestion("package:" + s.namespace, "selector", "selector")) +
                    (s.attributes["tags"]?.split(",")?.map { DbtSuggestion("tag:$it", "selector", "selector") }
                        ?: emptyList()) +
                    (if (s.kind == "selector") listOf(
                        DbtSuggestion(
                            "selector:" + s.name,
                            "named selector",
                            "selector"
                        )
                    ) else emptyList())
        } + resources(DbtSymbol.RefKinds).map {
            DbtSuggestion(
                it.name,
                "resource selector",
                it.kind
            )
        }).distinctBy { it.text }
        if (file.virtualFile.extension == "sql") {
            val qualifier = Qualifier.find(file.text.substring(0, offset))?.groupValues?.get(1)
            return DbtSqlAnalysis.forCompletion(w, file, offset)
                ?.scope(offset)?.relations?.filter { (alias, _) -> qualifier?.equals(alias, ignoreCase = true) ?: true }
                ?.flatMap { (alias, columns) ->
                    columns.map {
                        DbtSuggestion(
                            it.name, (it.attributes["data_type"] ?: "column") + " · " + alias,
                            "column", it.attributes["description"].orEmpty()
                        )
                    }
                }?.distinctBy { it.text to it.detail } ?: emptyList()
        }
        return emptyList()
    }

    /** Returns parameter names and display signatures from authored defaults and documentation; this does not type-check arguments. */
    fun parameters(w: DbtWorkspace, macroSymbol: DbtSymbol): List<Pair<String, String>> {
        val block =
            DbtSyntax.inFile(macroSymbol.file).firstOrNull { it.isTag("macro") && it.range.contains(macroSymbol.range) }
                ?: return emptyList()
        val documented = DbtResourceData.get(w, macroSymbol).arguments.associateBy { it.name }
        return DbtSyntax.parameters(block).map { token ->
            val remaining = block.tokens.drop(block.tokens.indexOf(token) + 1)
            var depth = 0
            val default = remaining.takeWhile { t ->
                val stop = depth == 0 && t.image in setOf(",", ")") && !t.string
                if (!t.string && t.image in setOf("(", "[", "{")) depth++
                if (!t.string && t.image in setOf(")", "]", "}")) depth--
                !stop
            }
            val display =
                token.image + (if (default.firstOrNull()?.image == "=") default.joinToString("") { if (it.string) "'${it.image}'" else it.image } else "") +
                        (documented[token.image]?.takeIf { it.dataType.isNotEmpty() }?.let { ": " + it.dataType } ?: "")
            token.image to display
        }
    }
}

class DbtCompletionContributor : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        DbtCompletion.suggestions(parameters.originalFile, parameters.offset).forEach { s ->
            result.addElement(
                LookupElementBuilder.create(s.text).withTypeText(s.detail, true)
                    .withTailText(if (s.description.isEmpty()) "" else "  " + s.description, true)
                    .withIcon(DbtSymbolElement.icon(s.kind))
            )
        }
    }
}

/** Native Parameter Info uses a unique macro definition and preserves the call anchor and current argument range. */
class DbtParameterInfoHandler : ParameterInfoHandler<PsiElement, DbtMacroSignature> {
    override fun findElementForParameterInfo(context: CreateParameterInfoContext): PsiElement? {
        val file = context.file
        val call = DbtCompletion.callAt(file, context.offset) ?: return null
        val w = DbtWorkspace.get(file.project)
        val root = w.root(file) ?: return null
        val macros = w.macros(call, root)
        if (macros.size != 1) return null
        context.itemsToShow =
            arrayOf(DbtMacroSignature(macros.first().name, DbtCompletion.parameters(w, macros.first())))
        return file
    }

    override fun showParameterInfo(element: PsiElement, context: CreateParameterInfoContext) {
        DbtCompletion.callAt(context.file, context.offset)?.let { context.showHint(element, it.range.endOffset, this) }
    }

    override fun findElementForUpdatingParameterInfo(context: UpdateParameterInfoContext): PsiElement? =
        if (DbtCompletion.callAt(context.file, context.offset) != null) context.file else null

    @Suppress("SENSELESS_COMPARISON")
    override fun updateParameterInfo(element: PsiElement, context: UpdateParameterInfoContext) {
        if (element == null) context.removeHint() else DbtCompletion.callAt(context.file, context.offset)?.let { call ->
            val signatures = context.objectsToView.filterIsInstance<DbtMacroSignature>()
            val current =
                call.arguments.firstOrNull { it.range?.let { r -> r.startOffset <= context.offset && r.endOffset >= context.offset } == true }
            val named =
                current?.keyword?.let { name -> signatures.firstOrNull()?.parameters?.indexOfFirst { it.first == name } }
            context.setCurrentParameter(
                named ?: call.arguments.count { it.range?.endOffset?.let { end -> end < context.offset } == true })
        }
    }

    override fun updateUI(signature: DbtMacroSignature, context: ParameterInfoUIContext) {
        val text = signature.name + signature.parameters.joinToString(", ", "(", ")") { it.second }
        val index = context.currentParameterIndex
        val start =
            if (index >= 0 && index < signature.parameters.size) signature.name.length + 1 + signature.parameters.take(
                index
            ).sumOf { it.second.length + 2 } else -1
        val end = if (start >= 0) start + signature.parameters[index].second.length else -1
        context.setupUIComponentPresentation(text, start, end, false, false, false, context.defaultParameterColor)
    }
}
