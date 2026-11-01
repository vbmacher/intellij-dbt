package com.github.vbmacher.intellij.dbt.project

import com.github.vbmacher.intellij.dbt.syntax.DbtSyntax
import com.github.vbmacher.intellij.dbt.syntax.DbtYaml
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import org.jetbrains.yaml.psi.YAMLMapping

data class DbtColumnData(
    val name: String,
    val dataType: String = "",
    val description: String = "",
    val declarations: List<String> = emptyList()
)

data class DbtTestData(val name: String, val column: String = "", val source: PsiElement? = null) {
    val sourceId: String?
        get() = source?.takeIf { it.isValid }?.let { it.containingFile.virtualFile.path + ":" + it.textOffset }
}

/** Documentation assembled from authored declarations and optional metadata; absent values remain empty rather than inferred. */
data class DbtResourceData(
    val description: String = "",
    val columns: List<DbtColumnData> = emptyList(),
    val tests: List<DbtTestData> = emptyList(),
    val tags: List<String> = emptyList(),
    val attributes: Map<String, String> = emptyMap(),
    val rawSql: String? = null,
    val compiledSql: String? = null,
    val arguments: List<DbtColumnData> = emptyList(),
    val dependencies: List<String> = emptyList()
) {
    /**
     * Fills missing fields while keeping this instance authoritative. Columns merge by name, dependencies are deduplicated,
     * and repeated test declarations retain the greater occurrence count from either source.
     */
    fun enrich(other: DbtResourceData): DbtResourceData = copy(
        description = description.ifEmpty { other.description },
        columns = columns.map { c ->
            other.columns.find { it.name == c.name }?.let {
                c.copy(
                    dataType = c.dataType.ifEmpty { it.dataType },
                    description = c.description.ifEmpty { it.description })
            } ?: c
        } + other.columns.filterNot { o -> columns.any { it.name == o.name } },
        tests = tests + other.tests.groupBy { it.name to it.column }.toList()
            .sortedWith(compareBy({ it.first.first }, { it.first.second }))
            .flatMap { (key, declarations) -> declarations.drop(tests.count { (it.name to it.column) == key }) },
        tags = tags.ifEmpty { other.tags },
        attributes = other.attributes + attributes,
        rawSql = rawSql ?: other.rawSql,
        compiledSql = compiledSql ?: other.compiledSql,
        arguments = arguments.ifEmpty { other.arguments },
        dependencies = (dependencies + other.dependencies).distinct()
    )

    companion object {
        val GraphKinds = setOf("model", "source_table", "seed", "snapshot", "exposure")
        /** Caches source-first data for the workspace lifetime; expands only unambiguous doc calls in complete literal concatenations. */
        fun get(w: DbtWorkspace, s: DbtSymbol): DbtResourceData = w.resourceData(s) {
            ProgressManager.checkCanceled()
            val properties = if (s.isProperty || s.mapping != null) listOf(s) else w.properties(s)
            val declared = w.columns(s).distinctBy { it.name }
            val columns = declared.map { c ->
                DbtColumnData(
                    c.name,
                    c.attributes["data_type"].orEmpty(),
                    c.attributes["description"].orEmpty(),
                    c.mapping?.let { columnDeclarations(it, c.name) }.orEmpty()
                )
            }
            val declaredTests = declared.flatMap { c -> c.mapping?.let { testData(it, c.name) }.orEmpty() }
            val source = properties.mapNotNull { it.mapping }.fold(
                DbtResourceData(
                    description = s.attributes["description"].orEmpty(),
                    columns = columns,
                    tests = declaredTests,
                    tags = s.attributes["tags"]?.split(',')?.filter { it.isNotEmpty() }.orEmpty(),
                    attributes = s.attributes,
                    rawSql = if (s.isCode && s.kind in setOf(
                            "model",
                            "snapshot"
                        ) && s.file.name.endsWith(".sql")
                    ) s.file.text else null
                )
            ) { data, m ->
                val columns = DbtYaml.items(m, "columns").mapNotNull { c ->
                    DbtYaml.string(c, "name")?.let {
                        DbtColumnData(
                            it,
                            DbtYaml.string(c, "data_type").orEmpty(),
                            DbtYaml.string(c, "description").orEmpty(),
                            columnDeclarations(c, it)
                        )
                    }
                }
                val args = DbtYaml.items(m, "arguments").mapNotNull { a ->
                    DbtYaml.string(a, "name")?.let {
                        DbtColumnData(
                            it,
                            DbtYaml.string(a, "type").orEmpty(),
                            DbtYaml.string(a, "description").orEmpty()
                        )
                    }
                }
                data.enrich(
                    DbtResourceData(
                        DbtYaml.string(m, "description").orEmpty(),
                        columns,
                        testData(m, "") + DbtYaml.items(m, "columns")
                            .flatMap { testData(it, DbtYaml.string(it, "name").orEmpty()) },
                        DbtYaml.strings(
                            m,
                            "tags",
                            DbtYaml.map(m, "config")?.let { DbtYaml.strings(it, "tags") }.orEmpty()
                        ),
                        DbtYaml.attributes(m),
                        arguments = args
                    )
                )
            }
            val enriched = DbtDeclarationSource.EP.extensionList.fold(source) { data, adapter ->
                adapter.documentation(w, s)?.let(data::enrich) ?: data
            }

            fun docBody(call: DbtSyntax.Call): String? {
                val bodies = w.owner(s)?.let { w.definitions(call, setOf("doc"), it) }.orEmpty().map { d ->
                    DbtSyntax.tagged(DbtSyntax.inFile(d.file), "docs", d.range)
                        ?.let { (a, b) -> d.file.text.substring(a.range.endOffset, b.range.startOffset).trim() }
                        .orEmpty()
                }
                return if (bodies.size == 1) bodies.firstOrNull() else null
            }

            fun docs(text: String): String =
                DbtSyntax.blocks(text).filterNot { it.tag }.reversed().fold(text) { result, block ->
                    val calls = DbtSyntax.calls(block).filter { it.name == "doc" && it.closed }
                    val resolved = calls.mapNotNull { c -> docBody(c)?.let { c.expressionRange to it } }
                    val tokens =
                        block.tokens.filterNot { t -> resolved.any { (range, _) -> range.contains(t.range) && range.startOffset != t.range.startOffset } }
                            .map { t ->
                                resolved.find { it.first.startOffset == t.range.startOffset }
                                    ?.let { (range, body) -> DbtSyntax.Token(body, range, true) } ?: t
                            }
                    val literal = block.range.substring(text)
                        .endsWith("}}") && tokens.isNotEmpty() && tokens.size % 2 == 1 && tokens.withIndex()
                        .all { (i, t) -> if (i % 2 == 0) t.string else !t.string && t.image == "~" }
                    if (calls.isNotEmpty() && literal) result.substring(
                        0,
                        block.range.startOffset
                    ) + tokens.filterIndexed { i, _ -> i % 2 == 0 }.joinToString("") { it.image } + result.substring(
                        block.range.endOffset
                    ) else result
                }
            enriched.copy(
                description = docs(enriched.description),
                columns = enriched.columns.map { it.copy(description = docs(it.description)) })
        }

        private fun columnDeclarations(column: YAMLMapping, name: String): List<String> =
            DbtYaml.items(column, "constraints").mapNotNull { DbtYaml.string(it, "type") } + testData(
                column,
                name
            ).map { it.name }

        private fun testData(m: YAMLMapping, column: String): List<DbtTestData> =
            listOf("data_tests", "tests").flatMap { key ->
                DbtYaml.elements(m, key).map { test ->
                    if (test is YAMLMapping) {
                        val kv = test.keyValues.firstOrNull(); DbtTestData(kv?.keyText ?: "test", column, kv?.key)
                    } else DbtTestData(test.text, column, test)
                }
            }
    }
}

/** Combines owning root and artifact resource identity for selections and sets that can span multiple projects. */
object DbtResourceIdentity {
    fun key(workspace: DbtWorkspace, symbol: DbtSymbol): String =
        workspace.owner(symbol)?.directory?.path.orEmpty() + "|" + symbol.uniqueId
}
