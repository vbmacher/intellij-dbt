package com.github.vbmacher.intellij.dbt.project

import com.github.vbmacher.intellij.dbt.syntax.DbtSyntax
import com.github.vbmacher.intellij.dbt.syntax.DbtYaml
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping

/** Extracts authored declarations and literal configuration from PSI; templates are inspected without executing Jinja or dbt. */
object DbtDeclarations {
    val ResourceKeys = mapOf(
        "models" to "model",
        "seeds" to "seed",
        "snapshots" to "snapshot",
        "analyses" to "analysis",
        "macros" to "macro",
        "sources" to "source",
        "exposures" to "exposure",
        "groups" to "group",
        "metrics" to "metric",
        "semantic_models" to "semantic_model",
        "saved_queries" to "saved_query",
        "functions" to "function",
        "unit_tests" to "unit_test",
        "checks" to "check"
    )
    private val childKeys = listOf(
        "columns" to "column",
        "entities" to "entity",
        "dimensions" to "dimension",
        "measures" to "measure",
        "arguments" to "parameter",
        "tables" to "source_table",
        "exports" to "export"
    )
    private val blockKinds = mapOf(
        "macro" to "macro",
        "test" to "generic_test",
        "docs" to "doc",
        "snapshot" to "snapshot",
        "materialization" to "materialization"
    )
    private val Code = mapOf("code" to "true")
    fun inFile(file: PsiFile, root: DbtRoot): List<DbtSymbol> = FileDeclarations(file, root).all
    private fun configLiterals(blocks: List<DbtSyntax.Block>): List<Pair<String, String>> =
        blocks.flatMap(DbtSyntax::calls).filter { it.name == "config" }.flatMap {
            it.arguments.mapNotNull { a ->
                a.keyword?.let { key ->
                    (a.literal ?: literalTags(a))?.let { key to it }
                }
            }
        }

    private fun literalTags(argument: DbtSyntax.Argument): String? {
        val tokens = argument.tokens
        if (argument.keyword != "tags" || tokens.size < 2 || tokens.first().string || tokens.last().string || tokens.first().image != "[" || tokens.last().image != "]") return null
        val values = tokens.subList(1, tokens.size - 1)
        val entries =
            if (values.lastOrNull()?.let { !it.string && it.image == "," } == true) values.dropLast(1) else values
        val literal = entries.withIndex()
            .all { (i, token) -> if (i % 2 == 0) token.string else !token.string && token.image == "," }
        return if (literal && (entries.isEmpty() || entries.size % 2 == 1)) entries.filterIndexed { i, _ -> i % 2 == 0 }
            .joinToString(",") { it.image } else null
    }

    private class FileDeclarations(val file: PsiFile, val root: DbtRoot) {
        private val ext = file.virtualFile.extension.orEmpty()
        private val jinja by lazy { DbtSyntax.inFile(file) }
        val all: List<DbtSymbol>
            get() {
                val yaml = root.kind(file.virtualFile) != null && ext in setOf("yml", "yaml")
                val properties = if (yaml) DbtYaml.mapping(file)?.let(::resources).orEmpty() else emptyList()
                return selectors + code + blocks + properties + metricAliases(properties)
            }

        private fun symbol(
            kind: String,
            name: String,
            range: TextRange,
            owner: String = "",
            version: String? = null,
            attributes: Map<String, String> = emptyMap(),
            mapping: YAMLMapping? = null
        ): DbtSymbol = DbtSymbol(kind, name, root.name, file, range, owner, version, attributes, mapping)

        private val selectors: List<DbtSymbol>
            get() = if (!root.isFile(
                    file,
                    "selectors.yml"
                )
            ) emptyList() else DbtYaml.mapping(file)?.let { DbtYaml.items(it, "selectors") }.orEmpty().mapNotNull { m ->
                DbtYaml.string(m, "name")?.let { symbol("selector", it, DbtYaml.nameRange(m), mapping = m) }
            }
        private val code: List<DbtSymbol>
            get() {
                val kind = root.kind(file.virtualFile);
                val name = file.virtualFile.nameWithoutExtension
                val fixture = kind == "test" && ext in setOf("csv", "sql") && root.relative(file.virtualFile)
                    ?.let { relative ->
                        root.pathKinds.any { (k, path) ->
                            k == "test" && root.pathContains(
                                path.removeSuffix("/") + "/fixtures", relative
                            )
                        }
                    } == true
                val code = when (kind) {
                    "seed" -> ext == "csv"; "model" -> ext in setOf("sql", "py"); "function" -> ext in setOf(
                        "sql",
                        "py",
                        "js"
                    ); "snapshot", "analysis", "test", "check" -> ext == "sql"; else -> false
                }
                val snapshotBlock = kind == "snapshot" && jinja.any { it.isTag("snapshot") }
                return if (fixture) listOf(
                    symbol(
                        "fixture",
                        name,
                        TextRange.EMPTY_RANGE,
                        attributes = Code
                    )
                ) else if (code && !snapshotBlock) listOf(
                    symbol(
                        kind!!,
                        name,
                        TextRange.EMPTY_RANGE,
                        attributes = Code + configLiterals(jinja)
                    )
                ) else emptyList()
            }
        private val blocks: List<DbtSymbol>
            get() = if (!root.resource(file) || ext !in setOf("sql", "md")) emptyList() else jinja.filter { it.tag }
                .flatMap { b ->
                    val tag = b.tokens.getOrNull(0);
                    val name = b.tokens.getOrNull(1);
                    val kind = tag?.let { blockKinds[it.image] }
                    if (kind == null || name == null) emptyList() else {
                        val attributes = mapOf("block-start" to b.range.endOffset.toString()) + when (kind) {
                            "snapshot" -> Code + configLiterals(jinja.dropWhile { it.range.startOffset <= b.range.startOffset }
                                .takeWhile { !it.isTag("endsnapshot") })

                            "materialization" -> mapOf(
                                "adapter" to (b.tokens.windowed(3)
                                    .firstOrNull { (key, eq, value) -> key.image == "adapter" && eq.image == "=" && value.string }
                                    ?.get(2)?.image ?: "default")
                            )

                            else -> emptyMap()
                        }
                        val parameters = if (tag.image in setOf("macro", "test")) DbtSyntax.parameters(b)
                            .map { symbol("parameter", it.image, it.range, name.image) } else emptyList()
                        listOf(symbol(kind, name.image, name.range, attributes = attributes)) + parameters
                    }
                }

        private fun resources(top: YAMLMapping): List<DbtSymbol> =
            ResourceKeys.filter { (_, kind) -> kind in setOf("metric", "saved_query") || root.supportsKind(kind) }
                .flatMap { (key, kind) ->
                    DbtYaml.items(top, key).flatMap { m ->
                        DbtYaml.string(m, "name")?.let { name ->
                            (if (kind == "model" && root.supports(12)) modelSemantics(
                                m,
                                name
                            ) else emptyList()) + (if (kind == "function" && root.supports(12)) functionOverloads(
                                m,
                                name
                            ) else emptyList()) + versions(m, kind, name)
                        }.orEmpty()
                    }
                }

        private fun modelSemantics(m: YAMLMapping, name: String): List<DbtSymbol> {
            val semantics = DbtYaml.map(m, "semantic_model")?.let { semantic ->
                val semanticName = DbtYaml.string(semantic, "name") ?: name
                val model = symbol(
                    "semantic_model",
                    semanticName,
                    DbtYaml.nameRange(semantic),
                    name,
                    attributes = mapOf("embedded" to "true"),
                    mapping = m
                )
                val columns = DbtYaml.items(m, "columns").flatMap { column ->
                    DbtYaml.string(column, "name")?.let { columnName ->
                        listOf("entity", "dimension").mapNotNull { field ->
                            DbtYaml.map(column, field)?.let { definition ->
                                symbol(
                                    field,
                                    DbtYaml.string(definition, "name") ?: columnName,
                                    DbtYaml.value(definition, "name")?.textRange ?: DbtYaml.nameRange(column),
                                    semanticName,
                                    attributes = DbtYaml.attributes(definition) + mapOf(
                                        "column" to columnName,
                                        "model" to name
                                    ),
                                    mapping = definition
                                )
                            }
                        }
                    }.orEmpty()
                }
                val derived = DbtYaml.map(m, "derived_semantics")?.let { derived ->
                    listOf(
                        "dimensions" to "dimension",
                        "entities" to "entity"
                    ).flatMap { (key, kind) ->
                        DbtYaml.items(derived, key).mapNotNull { definition ->
                            DbtYaml.string(definition, "name")?.let {
                                symbol(
                                    kind,
                                    it,
                                    DbtYaml.nameRange(definition),
                                    semanticName,
                                    attributes = DbtYaml.attributes(definition) + mapOf("model" to name),
                                    mapping = definition
                                )
                            }
                        }
                    }
                }.orEmpty()
                listOf(model) + columns + derived
            }.orEmpty()
            val metrics = DbtYaml.items(m, "metrics").mapNotNull { metric ->
                DbtYaml.string(metric, "name")?.let {
                    symbol(
                        "metric",
                        it,
                        DbtYaml.nameRange(metric),
                        name,
                        attributes = DbtYaml.attributes(metric),
                        mapping = metric
                    )
                }
            }
            return semantics + metrics
        }

        private fun functionOverloads(m: YAMLMapping, name: String): List<DbtSymbol> =
            DbtYaml.items(m, "overloads").flatMap { overload ->
                DbtYaml.string(overload, "defined_in")?.let { body ->
                    listOf(
                        symbol(
                            "function",
                            name,
                            DbtYaml.nameRange(m),
                            mapping = overload,
                            attributes = DbtYaml.attributes(m) + DbtYaml.attributes(overload) + mapOf(
                                "property" to "true",
                                "overload" to body
                            )
                        )
                    ) + DbtYaml.items(overload, "arguments").mapNotNull { argument ->
                        DbtYaml.string(argument, "name")?.let {
                            symbol(
                                "parameter",
                                it,
                                DbtYaml.nameRange(argument),
                                name,
                                attributes = mapOf("overload" to body),
                                mapping = argument
                            )
                        }
                    }
                }.orEmpty()
            }

        private fun versions(m: YAMLMapping, kind: String, name: String): List<DbtSymbol> {
            val versions = if (root.supports(5)) DbtYaml.items(m, "versions") else emptyList()
            val entries = if (versions.isEmpty()) listOf(m to null) else versions.map { it to DbtYaml.string(it, "v") }
            return entries.flatMap { (entry, version) ->
                val resource = symbol(
                    kind,
                    name,
                    DbtYaml.nameRange(m),
                    version = version,
                    mapping = entry,
                    attributes = DbtYaml.attributes(m) + DbtYaml.attributes(entry) + mapOf("property" to "true")
                )
                val children = childKeys.flatMap { (key, childKind) ->
                    val items =
                        if (key == "columns" && version != null && DbtYaml.value(entry, key) == null) DbtYaml.items(
                            m,
                            key
                        ) else DbtYaml.items(entry, key)
                    items.flatMap { c ->
                        DbtYaml.string(c, "name")?.let { n ->
                            val inherited = if (childKind == "source_table") DbtYaml.attributes(m) else emptyMap()
                            val child = symbol(
                                childKind,
                                n,
                                DbtYaml.nameRange(c),
                                name,
                                version,
                                inherited + DbtYaml.attributes(c),
                                c
                            )
                            val columns = if (childKind != "source_table") emptyList() else DbtYaml.items(c, "columns")
                                .mapNotNull { col ->
                                    DbtYaml.string(col, "name")?.let {
                                        symbol(
                                            "column",
                                            it,
                                            DbtYaml.nameRange(col),
                                            "$name.$n",
                                            attributes = DbtYaml.attributes(col),
                                            mapping = col
                                        )
                                    }
                                }
                            listOf(child) + columns
                        }.orEmpty()
                    }
                }
                listOf(resource) + children
            }
        }

        private fun metricAliases(declarations: List<DbtSymbol>): List<DbtSymbol> =
            declarations.filter { it.kind == "metric" }.flatMap { metric ->
                metric.mapping?.let { m ->
                    val parameters = DbtYaml.map(m, "type_params") ?: m
                    val inputs = DbtYaml.items(parameters, "metrics") + if (root.supports(12)) DbtYaml.items(
                        m,
                        "input_metrics"
                    ) else emptyList()
                    inputs.mapNotNull { input ->
                        DbtYaml.string(input, "alias")?.let {
                            symbol(
                                "metric_alias",
                                it,
                                DbtYaml.nameRange(input, "alias"),
                                metric.name,
                                attributes = DbtYaml.attributes(input),
                                mapping = input
                            )
                        }
                    }
                }.orEmpty()
            }
    }
}

/**
 * Resolves resource configuration through project, package, folder and resource levels.
 * More specific levels override inherited values; `+` denotes configuration keys rather than resource path segments.
 */
object DbtConfiguration {
    val sections = DbtDeclarations.ResourceKeys - setOf("macros", "unit_tests") + mapOf(
        "tests" to "test",
        "data_tests" to "test",
        "semantic-models" to "semantic_model",
        "saved-queries" to "saved_query"
    )

    fun pathTargets(w: DbtWorkspace, root: DbtRoot, kv: YAMLKeyValue): List<PsiElement> {
        val parts = DbtYaml.ancestors(kv).reversed();
        val kind = parts.firstOrNull()?.let(sections::get);
        val namespace = parts.getOrNull(1);
        val path = parts.drop(2)
        val roots = w.roots.filter { namespace == it.name && w.namespaceVisible(root, it, false) }
        if (parts.size == 2) return roots.map { it.config.containingFile }
        return roots.flatMap { r ->
            val symbols = w.symbols.filter { s ->
                (kind == s.kind || kind == "source" && s.kind == "source_table") && w.owner(s) == r && path.lastOrNull() == s.name && (if (s.kind == "source_table") path.dropLast(
                    1
                ).lastOrNull() == s.owner else !s.isCode || r.relative(s.file.virtualFile)?.let { relative ->
                    r.pathKinds.any { (k, base) ->
                        k == s.kind && r.folders(
                            base,
                            relative
                        ) == path.dropLast(1)
                    }
                } == true)
            }
            val folders = r.pathKinds.filter { kind == it.first }.mapNotNull { (_, base) ->
                r.directory.findFileByRelativePath((listOf(base) + path).joinToString("/"))?.takeIf { it.isDirectory }
                    ?.let { PsiManager.getInstance(w.project).findDirectory(it) }
            }
            folders + symbols.filter { w.declarations(setOf(it.kind), it.name, root, namespace).contains(it) }
                .map { it.element }
        }.distinct()
    }

    fun propertyAttributes(w: DbtWorkspace, symbol: DbtSymbol): Map<String, String> =
        merge(w.owner(symbol)?.let { root ->
            val sectionNames =
                sections.filter { (_, kind) -> kind == symbol.kind || kind == "source" && symbol.kind == "source_table" }.keys.toList()
            val path = when (symbol.kind) {
                "source_table" -> listOf(
                    symbol.owner,
                    symbol.name
                ); "source" -> listOf(symbol.name); else -> root.resourceFolders(symbol.file.virtualFile)
                    ?.let { it.second + symbol.name }.orEmpty()
            }
            configurationRoots(w, root).flatMap { walk(it, sectionNames, root.name, path) }
        }.orEmpty())

    /** Combines applicable outer-to-inner configuration, including the consuming project overlay for installed packages. */
    fun inherited(w: DbtWorkspace, file: PsiFile, name: String): Map<String, String> =
        merge(w.root(file)?.let { levels(w, it, file, name) }.orEmpty())

    fun locations(w: DbtWorkspace, file: PsiFile, key: String): List<PsiElement> {
        val root = w.root(file);
        val symbols = w.codeSymbols(file)
        val name = symbols.map { it.name }.distinct().firstOrNull() ?: file.virtualFile.nameWithoutExtension
        val inherited = root?.let { levels(w, it, file, name) }.orEmpty()
            .flatMap { m -> listOf(key, "+$key").mapNotNull { DbtYaml.keyValue(m, it) } }
        val patches = symbols.flatMap(w::properties).mapNotNull { it.mapping }
            .mapNotNull { DbtYaml.map(it, "config")?.let { config -> DbtYaml.keyValue(config, key) } }
        val inline = DbtSyntax.inFile(file).flatMap(DbtSyntax::calls).filter { it.name == "config" }.flatMap { call ->
            call.arguments.filter { it.keyword == key }.mapNotNull { it.keywordRange }
                .map { DbtSymbol("configuration", key, root?.name ?: "dbt", file, it).element }
        }
        return (inherited + patches + inline).distinct()
    }

    private fun configurationRoots(w: DbtWorkspace, resource: DbtRoot): List<DbtRoot> =
        listOf(resource) + listOfNotNull(w.packageOwner(resource))

    private fun levels(w: DbtWorkspace, resource: DbtRoot, file: PsiFile, name: String): List<YAMLMapping> =
        resource.resourceFolders(file.virtualFile)?.let { (kind, folders) ->
            val sectionNames = if (kind == "test") listOf(
                "tests",
                "data_tests"
            ) else listOf(if (kind == "analysis") "analyses" else kind + "s")
            configurationRoots(w, resource).flatMap { walk(it, sectionNames, resource.name, folders + name) }
        }.orEmpty()

    private fun walk(
        config: DbtRoot,
        sectionNames: List<String>,
        namespace: String,
        path: List<String>
    ): List<YAMLMapping> {
        val top = sectionNames.mapNotNull { DbtYaml.map(config.config, it) }
        return top + path.runningFold(top.mapNotNull {
            DbtYaml.map(
                it,
                namespace
            )
        }) { current, part -> current.mapNotNull { DbtYaml.map(it, part) } }.flatten()
    }

    private fun merge(mappings: List<YAMLMapping>): Map<String, String> = mappings.fold(emptyMap()) { attrs, mapping ->
        attrs + DbtYaml.attributes(mapping).mapKeys { it.key.removePrefix("+") }
    }
}
