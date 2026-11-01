package com.github.vbmacher.intellij.dbt.navigation

import com.github.vbmacher.intellij.dbt.project.*
import com.github.vbmacher.intellij.dbt.syntax.*
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping

/** Applies dbt namespace, version, variable and adapter-dispatch rules to the declarations visible from a root. */
object DbtResolution {
    /** Selects the requested version, one agreed latest version, or unversioned declarations when neither is known. */
    fun versioned(defs: List<DbtSymbol>, requested: String?): List<DbtSymbol> {
        val latest = defs.mapNotNull { it.attributes["latest_version"] }.distinct()
        val selected = requested ?: if (latest.size == 1) latest.firstOrNull() else null
        return defs.filter { if (selected == null) it.version == null else it.version == selected }
    }

    private fun varsIn(m: YAMLMapping, project: String, name: String): YAMLKeyValue? =
        DbtYaml.map(m, project)?.let { DbtYaml.keyValue(it, name) } ?: DbtYaml.keyValue(m, name)

    fun overriddenVariable(w: DbtWorkspace, r: DbtRoot, name: String): DbtSymbolElement? =
        w.symbols.find { it.kind == "var" && it.name == name && "settings" in it.attributes && w.owner(it) == r }?.element

    /** Settings overrides win; simultaneous nonempty project vars and external vars are unresolved rather than merged. */
    fun variable(w: DbtWorkspace, r: DbtRoot, name: String): List<PsiElement> {
        val consumer = w.packageOwner(r) ?: r
        val external = if (consumer.supports(12)) w.rootFile(consumer, "vars.yml")?.let(DbtYaml::mapping)
            ?.let { DbtYaml.map(it, "vars") } else null
        val original = DbtYaml.map(consumer.config, "vars")
        overriddenVariable(w, consumer, name)?.let { return listOf(it) }
        if (external?.keyValues?.isNotEmpty() == true && original?.keyValues?.isNotEmpty() == true) return emptyList()
        return listOfNotNull((external ?: original)?.let { varsIn(it, r.name, name) } ?: if (consumer != r) DbtYaml.map(
            r.config,
            "vars"
        )?.let { varsIn(it, r.name, name) } else null)
    }

    fun configuration(w: DbtWorkspace, file: PsiFile, key: String): List<PsiElement> {
        val generators = mapOf(
            "schema" to "generate_schema_name",
            "alias" to "generate_alias_name",
            "database" to "generate_database_name"
        )
        return DbtConfiguration.locations(w, file, key) + w.root(file)
            ?.let { r -> generators[key]?.let { w.declarations(setOf("macro"), it, r).map { s -> s.element } } }
            .orEmpty()
    }

    fun sourceTables(w: DbtWorkspace, root: DbtRoot, source: String, table: String): List<DbtSymbol> {
        val parents = w.declarations(setOf("source"), source, root)
        return w.symbols.filter { s ->
            s.kind == "source_table" && s.name == table && s.owner == source && s.isEnabled && parents.any { p ->
                p.namespace == s.namespace && w.owner(
                    p
                ) == w.owner(s) && (p.file == s.file && p.mapping?.textRange?.contains(s.range) == true || s.metadata)
            }
        }
    }

    fun builtin(w: DbtWorkspace, root: DbtRoot, host: PsiFile, name: String, page: String): List<PsiElement> {
        val targets =
            w.symbols.filter { it.kind == "builtin" && it.name == name && it.attributes["builtin-root"] == root.directory.path }
                .map { it.element }
        return targets.ifEmpty { listOf(documentation(host, name, page, root)) }
    }

    fun materializations(w: DbtWorkspace, r: DbtRoot, name: String): List<DbtSymbol> {
        val declarations = w.declarations(setOf("materialization"), name, r)
        return if (r.adapter == null) declarations else r.adapterFallbacks.asSequence()
            .map { a -> declarations.filter { it.attributes["adapter"] == a } }.firstOrNull { it.isNotEmpty() }
            .orEmpty()
    }

    fun dispatch(w: DbtWorkspace, r: DbtRoot, name: String, namespace: String?): List<DbtSymbol> {
        val order = DbtYaml.items(r.config, "dispatch").find { DbtYaml.string(it, "macro_namespace") == namespace }
            ?.let { DbtYaml.strings(it, "search_order") } ?: (listOf(r.name) + listOfNotNull(namespace)).distinct()
        return if (r.adapter == null) w.symbols.filter { it.kind == "macro" && it.name.endsWith("__$name") && it.namespace in order }
        else order.asSequence().flatMap { ns ->
            r.adapterFallbacks.asSequence().map { p -> w.declarations(setOf("macro"), "${p}__$name", r, ns) }
        }.firstOrNull { it.isNotEmpty() }.orEmpty()
    }

    fun semantic(
        w: DbtWorkspace,
        root: DbtRoot,
        file: PsiFile,
        offset: Int,
        kind: String,
        name: String
    ): List<DbtSymbol> {
        val resourceKind = mapOf(
            "Dimension" to "dimension",
            "TimeDimension" to "dimension",
            "Entity" to "entity",
            "Metric" to "metric"
        )[kind] ?: kind
        val path = name.split("__");
        val field = path.last();
        val scope = w.enclosing("semantic_model", file, offset)
        val candidates =
            w.symbols.filter { it.kind == resourceKind && it.name == field && it.namespace == root.name && w.root(it.file) == root }
        if (path.size == 1 && scope.isNotEmpty() && resourceKind != "metric") return candidates.filter { s -> scope.any { it.name == s.owner } }
        if (path.size <= 1) return candidates
        val entities = w.symbols.filter { it.kind == "entity" && it.namespace == root.name && w.root(it.file) == root }
        var owners = emptySet<String>();
        var known = true
        path.dropLast(1).forEach { name ->
            val references = entities.filter { it.name == name && (owners.isEmpty() || it.owner in owners) }
            val joins =
                entities.filter { it.name == name && it.attributes["type"] in setOf("primary", "unique", "natural") }
            if (references.isEmpty() || joins.isEmpty()) known = false
            owners = joins.map { it.owner }.toSet()
        }
        return if (known) candidates.filter { it.owner in owners } else emptyList()
    }

    fun documentation(file: PsiFile, name: String, page: String, r: DbtRoot): PsiElement {
        val path = when (page) {
            "info-schema" -> "reference/info-schema"; "data-tests" -> "reference/resource-properties/data-tests"; "function" -> "docs/build/udfs"; "statement", "load-result" -> "reference/dbt-jinja-functions/statement-blocks"; "on-run-end-context" -> "reference/dbt-jinja-functions/on-run-end-context"; else -> "reference/dbt-jinja-functions/" + page.replace(
                '-',
                '_'
            )
        }
        return DbtSymbol(
            "documentation",
            name,
            "dbt",
            file,
            TextRange(0, 0),
            attributes = mapOf("url" to "https://docs.getdbt.com/$path?version=${if (page == "info-schema") "2" else r.version}"),
            metadata = true
        ).element
    }
}

class DbtBuiltinSources : DbtDeclarationSource {
    override fun declarations(w: DbtWorkspace): List<DbtSymbol> = w.roots.flatMap { root ->
        root.runOptions.builtinSources.flatMap { (name, path) ->
            root.psiFile(path)?.let { file ->
                val identifier = name.split('.').last()
                val ranges = when (file.virtualFile.extension) {
                    "py" -> DbtPython.declarations(file, identifier).map { it.textRange }; "sql" -> DbtSyntax.inFile(
                        file
                    ).filter { it.isTag("macro") }.mapNotNull { it.tokens.getOrNull(1) }
                        .filter { it.image == identifier }.map { it.range }; else -> emptyList()
                }
                ranges.map {
                    DbtSymbol(
                        "builtin",
                        name,
                        root.name,
                        file,
                        it,
                        attributes = mapOf("builtin-root" to root.directory.path)
                    )
                }
            }.orEmpty()
        }
    }
}

object DbtInfoSchema {
    private val common = setOf(
        "unique_id",
        "name",
        "resource_type",
        "package_name",
        "original_file_path",
        "fqn",
        "alias",
        "description",
        "node_language",
        "database_name",
        "schema_name",
        "relation_name",
        "identifier",
        "enabled",
        "materialized",
        "config",
        "access",
        "group",
        "contract_enforced",
        "version",
        "latest_version",
        "deprecation_date",
        "primary_key",
        "properties_yml_file_path",
        "tags",
        "meta",
        "ingested_at"
    )
    val views = mapOf(
        "models" to common,
        "seeds" to common,
        "snapshots" to (common + "raw_code"),
        "functions" to (common + "raw_code"),
        "analyses" to (common + "raw_code"),
        "hooks" to (common + "raw_code"),
        "checks" to (common + "raw_code"),
        "sources" to (common + setOf("source_name", "source_description", "loader", "loaded_at_field")),
        "data_tests" to (common + setOf(
            "test_name",
            "test_definition_package",
            "arguments",
            "column_name",
            "node_unique_id",
            "severity",
            "warn_if",
            "error_if",
            "fail_calc",
            "store_failures",
            "store_failures_as"
        )),
        "unit_tests" to setOf(
            "unique_id",
            "name",
            "model",
            "description",
            "package_name",
            "original_file_path",
            "fqn",
            "given",
            "expect",
            "overrides",
            "versions",
            "ingested_at"
        ),
        "node_columns" to setOf(
            "node_unique_id",
            "column_name",
            "data_type_declared",
            "description",
            "tags",
            "ingested_at"
        ),
        "dag_nodes" to setOf("unique_id", "resource_type", "ingested_at")
    )

    fun enabled(root: DbtRoot, file: PsiFile): Boolean =
        root.engine == "v2" && root.kind(file.virtualFile) == "check" && DbtWorkspace.get(file.project).symbols.none { it.file == file && it.kind == "check" && !it.isEnabled } && (DbtYaml.map(
            root.config,
            "info_schema"
        )?.let { DbtYaml.string(it, "version") }?.let { it == "1" } ?: true)

    fun columns(file: PsiFile, root: DbtRoot, view: String): Map<String, List<PsiElement>> =
        views[view].orEmpty().associateWith { metadata(file, root, "information_schema_column", it, view) }

    fun view(file: PsiFile, root: DbtRoot, name: String): List<PsiElement> =
        metadata(file, root, "information_schema_view", name)

    private fun metadata(
        file: PsiFile,
        root: DbtRoot,
        kind: String,
        name: String,
        owner: String = ""
    ): List<PsiElement> {
        val w = DbtWorkspace.get(file.project)
        val found =
            w.symbols.filter { it.kind == kind && it.namespace == root.name && w.owner(it) == root && it.owner == owner && it.name == name }
        return if (found.isNotEmpty()) found.map { it.element } else listOf(
            DbtResolution.documentation(
                file,
                if (owner.isEmpty()) name else "$owner.$name",
                "info-schema",
                root
            )
        )
    }
}

/**
 * Resolves a bounded subset of literal Jinja values without template execution.
 * Lists and concatenation products are limited to 16 candidates; macro delegation stops at cycles or eight wrappers.
 * Unknown expressions produce no candidates and must not be interpreted as proof of a missing resource.
 */
object DbtStaticCalls {
    fun value(tokens: List<DbtSyntax.Token>, constants: Map<String, List<String>>): List<String> {
        fun atom(ts: List<DbtSyntax.Token>): List<String> = when {
            ts.size == 1 -> if (ts[0].string) listOf(ts[0].image) else constants[ts[0].image].orEmpty()
            ts.firstOrNull()?.image == "[" && ts.lastOrNull()?.image == "]" -> {
                val items = ts.drop(1).dropLast(1)
                    .filterNot { !it.string && it.image == "," }; if (items.size <= 16 && items.all { it.string }) items.map { it.image } else emptyList()
            }

            ts.firstOrNull()?.image == "(" && ts.lastOrNull()?.image == ")" -> value(ts.drop(1).dropLast(1), constants)
            else -> emptyList()
        }

        val operators = tokens.withIndex().filter { (_, t) -> !t.string && t.image in setOf("~", "+") }.map { it.index }
        if (operators.isEmpty()) return atom(tokens)
        val boundaries = listOf(-1) + operators + tokens.size
        return boundaries.windowed(2).map { (a, b) -> atom(tokens.subList(a + 1, b)) }
            .fold(listOf("")) { left, right -> if (left.size * right.size > 16) emptyList() else left.flatMap { a -> right.map { a + it } } }
    }

    fun constants(blocks: List<DbtSyntax.Block>, before: Int): Map<String, List<String>> {
        var scopes = listOf(emptyMap<String, List<String>>());
        var captures = emptyList<String>()
        fun visible(): Map<String, List<String>> = scopes.reversed().fold(emptyMap()) { a, b -> a + b }
        fun bind(name: String, values: List<String>) {
            scopes = listOf(scopes.first() + (name to values)) + scopes.drop(1)
        }
        blocks.takeWhile { it.range.startOffset < before }.forEach { b ->
            val ts = b.tokens;
            val tag = b.tagName.orEmpty()
            if (tag in DbtSyntax.ScopeTags) {
                val outer = visible()
                val bindings = when {
                    tag == "for" -> {
                        val targets = ts.drop(1).takeWhile { it.image != "in" }
                            .filterNot { it.image == "," }; targets.associate {
                            it.image to if (targets.size == 1) value(
                                ts.dropWhile { it.image != "in" }.drop(1),
                                outer
                            ) else emptyList()
                        }
                    }

                    tag in setOf("macro", "test", "call") -> DbtSyntax.parameters(b)
                        .associate { it.image to emptyList<String>() }

                    tag == "with" -> {
                        val assignments = DbtSyntax.assignedNames(ts); assignments.withIndex().associate { (index, i) ->
                            val end = assignments.getOrNull(index + 1)?.minus(1)
                                ?: ts.size; ts[i].image to value(ts.subList(i + 2, end), outer)
                        }
                    }

                    else -> emptyMap()
                }
                scopes = listOf(bindings) + scopes
            }
            if (tag in DbtSyntax.ScopeEndTags && scopes.size > 1) scopes = scopes.drop(1)
            if (tag == "endset" && captures.isNotEmpty()) {
                bind(captures.first(), emptyList()); captures = captures.drop(1)
            }
            if (tag == "set" && ts.getOrNull(2)?.image == "=") {
                val env = visible();
                val name = ts[1].image;
                val next = value(ts.drop(3), env)
                val preceding = blocks.takeWhile { it.range.startOffset < b.range.startOffset };
                val conditional = preceding.count { it.isTag("if") } > preceding.count { it.isTag("endif") }
                bind(
                    name,
                    if (conditional && (next.isEmpty() || env[name]?.isEmpty() == true)) emptyList() else if (conditional) (env[name].orEmpty() + next).distinct() else next
                )
            } else if (tag == "set" && ts.getOrNull(2)?.image != ".") {
                if (ts.none { it.image == "=" }) ts.getOrNull(1)?.let { captures = listOf(it.image) + captures }
                else ts.drop(1).takeWhile { it.image != "=" }.filter { !it.string && DbtSyntax.isIdentifier(it.image) }
                    .forEach { bind(it.image, emptyList()) }
            }
        }
        return visible()
    }

    fun targets(
        w: DbtWorkspace,
        root: DbtRoot,
        call: DbtSyntax.Call,
        env: Map<String, List<String>>
    ): List<PsiElement> {
        val args = call.positional.map { value(it.tokens, env) };
        val base = call.name.removePrefix("builtins.")
        if (!call.name.startsWith("builtins.") && w.declarations(setOf("macro"), base, root)
                .isNotEmpty()
        ) return emptyList()
        return when (base) {
            "ref", "function", "doc" -> {
                val kinds = if (base == "ref") DbtSymbol.RefKinds else setOf(base);
                val qualified = args.size >= 2
                val names = args.getOrNull(if (qualified) 1 else 0).orEmpty();
                val namespaces: List<String?> = if (qualified) args.first() else listOf(null)
                if (call.version != null && call.version?.value == null) emptyList() else names.flatMap { name ->
                    namespaces.flatMap { ns ->
                        DbtResolution.versioned(
                            w.declarations(kinds, name, root, ns),
                            call.version?.value
                        ).map { it.element }
                    }
                }.distinct()
            }

            "source" -> if (args.size == 2) args[0].flatMap { source ->
                args[1].flatMap { table ->
                    DbtResolution.sourceTables(
                        w,
                        root,
                        source,
                        table
                    ).map { it.element }
                }
            }.distinct() else emptyList()

            "var" -> args.firstOrNull().orEmpty().flatMap { DbtResolution.variable(w, root, it) }
            else -> emptyList()
        }
    }

    fun wrapperTargets(
        w: DbtWorkspace,
        root: DbtRoot,
        macroSymbol: DbtSymbol,
        call: DbtSyntax.Call,
        constants: Map<String, List<String>>,
        visited: Set<String> = emptySet(),
        onMissing: (DbtSyntax.Argument, String) -> Unit = { _, _ -> }
    ): List<Pair<DbtSyntax.Argument, List<PsiElement>>> {
        if (macroSymbol.id in visited || visited.size >= 8) return emptyList()
        ProgressManager.checkCanceled()
        val blocks = DbtSyntax.inFile(macroSymbol.file);
        val start = blocks.indexOfFirst { it.tag && it.tokens.getOrNull(1)?.range == macroSymbol.range }
        if (start < 0) return emptyList()
        val body = blocks.drop(start + 1).takeWhile { !it.isTag("endmacro") }
        val params =
            w.symbols.filter { it.kind == "parameter" && it.owner == macroSymbol.name && it.file == macroSymbol.file }
                .sortedBy { it.range.startOffset }
        val bindings = params.withIndex().mapNotNull { (i, p) ->
            (call.arguments.find { it.keyword == p.name }
                ?: call.positional.getOrNull(i))?.let { p.name to (it to value(it.tokens, constants)) }
        }.toMap()

        fun originals(argument: DbtSyntax.Argument): List<DbtSyntax.Argument> =
            argument.tokens.mapNotNull { bindings[it.image] }.map { it.first }

        val env = bindings.mapValues { it.value.second };
        val result = mutableListOf<Pair<DbtSyntax.Argument, List<PsiElement>>>()
        body.flatMap(DbtSyntax::calls).forEach { delegated ->
            val assigned = this.constants(body, delegated.block.range.startOffset);
            val reassigned = assigned.keys;
            val local = env + assigned
            val targets = targets(w, root, delegated, local)
            if (targets.isEmpty()) missing(
                w,
                root,
                delegated,
                local
            )?.let { (argument, message) ->
                argument.tokens.filterNot { it.string }.mapNotNull { bindings[it.image] }
                    .forEach { (original, _) -> onMissing(original, message) }
            }
            delegated.arguments.forEach { a ->
                a.tokens.filter { !it.string && it.image !in reassigned }.mapNotNull { bindings[it.image] }
                    .forEach { (argument, _) -> if (targets.isNotEmpty()) result += argument to targets }
            }
            if (targets.isEmpty()) w.macros(delegated, root).forEach { next ->
                wrapperTargets(w, root, next, delegated, local, visited + macroSymbol.id) { arg, message ->
                    originals(
                        arg
                    ).forEach { onMissing(it, message) }
                }.forEach { (arg, candidates) -> originals(arg).forEach { result += it to candidates } }
            }
        }
        return result
    }

    /** Reports only closed calls with uniquely known arguments and a known namespace; dynamic or shadowed builtins stay unreported. */
    fun missing(
        w: DbtWorkspace,
        root: DbtRoot,
        call: DbtSyntax.Call,
        constants: Map<String, List<String>>
    ): Pair<DbtSyntax.Argument, String>? {
        val args = call.positional.map { value(it.tokens, constants) };
        val base = call.name.removePrefix("builtins.")
        val literal = args.all { it.size == 1 };
        val qualified = args.size == 2
        val namespaceKnown = !qualified || base == "source" || args.firstOrNull()?.firstOrNull()
            ?.let { ns -> w.roots.any { it.name == ns } || w.symbols.any { it.namespace == ns } } == true
        if (!call.closed || !literal || !namespaceKnown || call.version != null && call.version?.value == null || !call.name.startsWith(
                "builtins."
            ) && w.macros(call, root).isNotEmpty() || targets(w, root, call, constants).isNotEmpty()
        ) return null
        return when {
            base == "ref" && (args.size == 1 || args.size == 2) -> {
                val index = if (qualified) 1 else 0; call.positional.getOrNull(index)?.let {
                    it to ("Unresolved ref: '${args[index].first()}'" + call.version?.value?.let { v -> " (version $v)" }
                        .orEmpty())
                }
            }

            base == "source" && args.size == 2 -> {
                val source = args[0].first();
                val table = args[1].first(); if (w.declarations(setOf("source"), source, root)
                        .isEmpty()
                ) call.positional.firstOrNull()
                    ?.let { it to "Unresolved source: '$source'" } else call.positional.getOrNull(1)
                    ?.let { it to "Unresolved source table: '$source.$table'" }
            }

            else -> null
        }
    }
}
