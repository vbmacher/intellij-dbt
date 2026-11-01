package com.github.vbmacher.intellij.dbt.lineage

import com.github.vbmacher.intellij.dbt.navigation.*
import com.github.vbmacher.intellij.dbt.project.*
import com.github.vbmacher.intellij.dbt.syntax.DbtSyntax
import com.github.vbmacher.intellij.dbt.syntax.DbtYaml
import com.google.gson.JsonElement
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.psi.*
import com.intellij.psi.search.PsiSearchScopeUtil
import com.intellij.psi.search.SearchScope
import org.jetbrains.yaml.psi.*
import java.nio.file.FileSystems
import java.nio.file.Paths

/** A resource at [from] uses [to]; ambiguous navigation retains one edge per candidate and marks the reference ambiguous. */
data class DbtDependency(val from: DbtSymbol, val to: DbtSymbol, val reference: DbtNavigationReference) {
    val ambiguous: Boolean get() = reference.candidates.size > 1
}

/** Contributes artifact-backed edges alongside source references; implementations must honor a non-null search scope. */
interface DbtDependencySource {
    fun dependencies(workspace: DbtWorkspace, scope: SearchScope?): List<DbtDependency>

    companion object {
        val EP: ExtensionPointName<DbtDependencySource> =
            ExtensionPointName.create("com.github.vbmacher.intellij-dbt.dependencySource")
    }
}

object DbtLineage {
    private val EdgeCache = Key.create<List<DbtDependency>>("dbt.lineage.edges")
    private val EndTags = mapOf(
        "macro" to "endmacro",
        "generic_test" to "endtest",
        "materialization" to "endmaterialization",
        "snapshot" to "endsnapshot"
    )
    private val Untracked = setOf("documentation", "local", "parameter", "sql_alias", "sql_projection", "cte")
    private val ParentsOperator = Regex("^[0-9]*\\+")
    private val ChildrenOperator = Regex("\\+[0-9]*$")
    /** Caches the full graph for this workspace; scoped queries recompute source edges and request scoped contributions from extensions. */
    fun edges(w: DbtWorkspace, scope: SearchScope? = null): List<DbtDependency> {
        if (scope == null) w.getUserData(EdgeCache)?.let { return it }
        val result =
            w.files.filter { scope == null || PsiSearchScopeUtil.isInScope(scope, it) }.flatMap { sourceEdges(w, it) }
                .distinct() + DbtDependencySource.EP.extensionList.flatMap { it.dependencies(w, scope) }
        if (scope == null) w.putUserData(EdgeCache, result)
        return result
    }

    private fun sourceEdges(w: DbtWorkspace, file: PsiFile): List<DbtDependency> {
        ProgressManager.checkCanceled()
        val declarations = w.symbols.filter { it.file == file && (it.isCode || it.isProperty || it.kind in EndTags) };
        val blocks = DbtSyntax.inFile(file)
        return DbtNavigation.inFile(file, false).flatMap { ref ->
            val range = ref.rangeInElement
            val containing =
                declarations.filter { it.mapping?.let { m -> m.containingFile == file && m.textRange.contains(range) } == true }
            val blockOwner = declarations.filter {
                it.kind in EndTags && "block-start" in it.attributes && DbtSyntax.encloses(
                    blocks,
                    it.range.startOffset,
                    EndTags.getValue(it.kind),
                    range
                )
            }.maxByOrNull { it.range.startOffset }
            val owner = if (containing.isNotEmpty()) containing.minByOrNull { it.mapping!!.textLength } else blockOwner
                ?: declarations.firstOrNull { it.isCode }
            if (owner == null) emptyList() else ref.candidates.filterIsInstance<DbtSymbolElement>().map { it.symbol }
                .filter {
                    it.kind !in Untracked && owner.id != it.id && !(owner.isProperty && it.isCode && owner.sameResource(
                        it
                    ))
                }.map { DbtDependency(owner, it, ref) }
        }
    }

    /** Visits each declaration once up to [depth] edges; downstream follows consumers, upstream follows their dependencies. The start is excluded. */
    fun traverse(edges: List<DbtDependency>, start: DbtSymbol, downstream: Boolean, depth: Int): List<DbtSymbol> {
        val seen = mutableSetOf(start.id);
        var frontier = listOf(start);
        val result = mutableListOf<DbtSymbol>();
        var remaining = depth
        while (frontier.isNotEmpty() && remaining > 0) {
            ProgressManager.checkCanceled()
            val next = frontier.flatMap { s ->
                edges.filter { (if (downstream) it.to.id else it.from.id) == s.id }
                    .map { if (downstream) it.from else it.to }
            }.filter { seen.add(it.id) }
            result += next; frontier = next; remaining--
        }
        return result
    }

    fun operators(expression: String): Pair<String?, String?> =
        ParentsOperator.find(expression)?.value to ChildrenOperator.find(expression)?.value

    fun withoutOperators(term: String): String =
        ChildrenOperator.replaceFirst(ParentsOperator.replaceFirst(term, ""), "")

    fun neighbours(w: DbtWorkspace, seeds: List<DbtSymbol>, parents: String?, children: String?): List<DbtSymbol> {
        if (parents == null && children == null) return emptyList()
        val all = edges(w)
        return (parents?.let { p ->
            seeds.flatMap {
                traverse(
                    all,
                    it,
                    false,
                    p.dropLast(1).toIntOrNull() ?: Int.MAX_VALUE
                )
            }
        }.orEmpty()) + children?.let { p ->
            seeds.flatMap {
                traverse(
                    all,
                    it,
                    true,
                    p.drop(1).toIntOrNull() ?: Int.MAX_VALUE
                )
            }
        }.orEmpty()
    }
}

data class DbtColumnEdge(
    val resource: DbtSymbol,
    val column: String,
    val input: DbtSymbol,
    val direct: Boolean,
    val expression: String,
    val complete: Boolean,
    val kind: String = "value"
)

/** Separates value provenance from filter/join context. Description inheritance requires one complete, direct value input and no existing description. */
object DbtColumnLineage {
    private val Cache = Key.create<List<DbtColumnEdge>>("dbt.column.lineage")
    fun edges(w: DbtWorkspace): List<DbtColumnEdge> {
        w.getUserData(Cache)?.let { return it }
        val result = w.symbols.filter { it.kind == "model" && it.isCode && it.isEnabled }.flatMap { resource ->
            ProgressManager.checkCanceled()
            DbtSqlAnalysis.get(w, resource.file)?.let { analysis ->
                fun inputs(column: DbtSqlColumn, seen: Set<Int>): List<Pair<DbtSymbol, Boolean>> =
                    column.inputs.flatMap { input ->
                        val local =
                            if (input.kind == "sql_projection" && input.file == resource.file) analysis.intermediates.find { it.range == input.range && it.name == input.name } else null
                        when {
                            local != null && local.range.startOffset !in seen -> inputs(
                                local,
                                seen + local.range.startOffset
                            ).map { (s, direct) -> s to (direct && column.direct && column.complete) }; local != null -> emptyList(); else -> listOf(
                            input to (column.direct && column.complete)
                        )
                        }
                    }
                analysis.columns.flatMap { c ->
                    inputs(c, emptySet()).map { (input, direct) ->
                        DbtColumnEdge(
                            resource,
                            c.name,
                            input,
                            direct,
                            c.expression,
                            c.complete
                        )
                    } + c.contextInputs.map { (input, kind) ->
                        DbtColumnEdge(
                            resource,
                            c.name,
                            input,
                            false,
                            "$kind dependency",
                            c.complete,
                            kind
                        )
                    }
                }
            }.orEmpty()
        }
        w.putUserData(Cache, result); return result
    }

    private fun of(w: DbtWorkspace, resource: DbtSymbol, e: DbtColumnEdge): Boolean =
        e.resource.sameResource(resource) && w.owner(e.resource) == w.owner(resource)

    fun upstream(w: DbtWorkspace, resource: DbtSymbol, column: String): List<DbtColumnEdge> =
        edges(w).filter { of(w, resource, it) && it.column.equals(column, true) }

    fun inherited(w: DbtWorkspace, resource: DbtSymbol): List<Triple<String, String, DbtSymbol>> {
        val existing = DbtResourceData.get(w, resource).columns.associate { it.name to it.description }
        return edges(w).filter { it.kind == "value" && of(w, resource, it) }.groupBy { it.column }.toSortedMap()
            .flatMap { (column, candidates) ->
                val inputs = candidates.map { it.input }.distinctBy { it.id }
                if (existing[column].orEmpty()
                        .isNotEmpty() || !candidates.all { it.direct && it.complete } || inputs.size != 1
                ) emptyList()
                else inputs.firstOrNull()?.let { input ->
                    input.attributes["description"]?.takeIf { it.isNotEmpty() && !it.contains("{{") }
                        ?.let { listOf(Triple(column, it, input)) }
                }.orEmpty()
            }
    }
}

/**
 * Static selection result; nonempty [problems] means the resources are only a partial answer.
 * Whitespace joins terms by union, commas intersect them, and exclusions are removed by root-qualified resource identity.
 * Named selectors use `selectors.yml` when present, otherwise manifest definitions; unsupported terms require dbt resolution.
 */
data class DbtSelection(val resources: List<DbtSymbol>, val problems: List<String>) {
    val complete: Boolean get() = problems.isEmpty()

    companion object {
        private val Methods = DbtSelectors.MethodKeys + "selector"
        fun selector(w: DbtWorkspace, resource: DbtSymbol): String = when (resource.kind) {
            "source_table" -> "source:${resource.namespace}.${resource.owner}.${resource.name}"
            "exposure" -> "exposure:${resource.namespace}.${resource.name}"
            "unit_test" -> "fqn:" + (listOf(resource.namespace) + DbtSelectors.folders(w, resource) + listOfNotNull(
                resource.mapping?.let { DbtYaml.string(it, "model") }) + resource.name).joinToString(".")

            else -> "fqn:" + DbtSelectors.fqn(w, resource).joinToString(".")
        }

        private fun methodTerm(field: (String) -> String?): String? {
            val method = field("method") ?: return null;
            val value = field("value") ?: return null
            return (if (field("childrens_parents") == "true") "@" else "") + (if (field("parents") == "true") field("parents_depth").orEmpty() + "+" else "") + "$method:$value" + (if (field(
                    "children"
                ) == "true"
            ) "+" + field("children_depth").orEmpty() else "")
        }

        fun resolve(w: DbtWorkspace, root: DbtRoot, expression: String, exclude: String = ""): DbtSelection =
            Resolver(w, root).resolve(expression, exclude)

        private class Resolver(val w: DbtWorkspace, val root: DbtRoot) {
            private val problems = mutableListOf<String>()
            private fun identity(s: DbtSymbol): String = DbtResourceIdentity.key(w, s)
            private fun union(xs: List<List<DbtSymbol>>): List<DbtSymbol> = xs.flatten().distinctBy(::identity)
            private fun intersect(xs: List<List<DbtSymbol>>): List<DbtSymbol> =
                xs.firstOrNull().orEmpty().filter { s -> xs.drop(1).all { it.any { t -> identity(t) == identity(s) } } }

            private fun unsupported(message: String = "Unsupported named selector definition."): List<DbtSymbol> {
                problems += message; return emptyList()
            }

            private val manifestSelectors by lazy {
                root.setting("manifest")?.let {
                    DbtManifestReader.get(w.project).read(root, it, DbtManifestPolicy.Navigation).getOrNull()
                        ?.resourcesIn("selectors")
                }.orEmpty()
            }

            private fun json(value: JsonElement, visiting: Set<String>): List<DbtSymbol> {
                if (value.isJsonPrimitive && value.asJsonPrimitive.isString) return terms(value.asString, visiting)
                if (value.isJsonArray) return union(value.asJsonArray.map { json(it, visiting) })
                if (!value.isJsonObject) return unsupported()
                val mapping = value.asJsonObject
                fun get(key: String): JsonElement? = mapping.get(key)?.takeUnless { it.isJsonNull }
                val combined = get("union") ?: get("intersection")
                val selected = when {
                    combined?.isJsonArray == true -> {
                        val includes =
                            combined.asJsonArray.filterNot { it.isJsonObject && it.asJsonObject.has("exclude") }.map {
                                json(
                                    it,
                                    visiting
                                )
                            }; if (mapping.has("intersection")) intersect(includes) else union(includes)
                    }

                    combined != null -> unsupported()
                    mapping.has("definition") -> json(mapping.get("definition"), visiting)
                    else -> methodTerm { get(it)?.takeIf { v -> v.isJsonPrimitive }?.asString }?.let {
                        term(
                            it,
                            visiting
                        )
                    } ?: unsupported()
                }
                val excludes = (combined?.takeIf { it.isJsonArray }?.asJsonArray?.filter { it.isJsonObject }
                    ?.mapNotNull { it.asJsonObject.get("exclude") }.orEmpty()) + listOfNotNull(get("exclude"))
                val removed = excludes.flatMap { json(it, visiting) }.map(::identity).toSet()
                return selected.filterNot { identity(it) in removed }
            }

            private fun yaml(value: YAMLValue, visiting: Set<String>): List<DbtSymbol> = when (value) {
                is YAMLScalar -> terms(value.textValue, visiting)
                is YAMLSequence -> union(value.items.mapNotNull { it.value }.map { yaml(it, visiting) })
                is YAMLMapping -> {
                    val include = DbtYaml.value(value, "union") ?: DbtYaml.value(value, "intersection")
                    val selected = when (include) {
                        is YAMLSequence -> {
                            val children = include.items.mapNotNull { it.value }
                                .filterNot { it is YAMLMapping && DbtYaml.value(it, "exclude") != null }
                                .map { yaml(it, visiting) }; if (DbtYaml.value(
                                    value,
                                    "intersection"
                                ) != null
                            ) intersect(children) else union(children)
                        }

                        null -> methodTerm { DbtYaml.string(value, it) }?.let { term(it, visiting) } ?: run {
                            val shorthand = value.keyValues.filter { it.keyText in Methods }
                            if (shorthand.size == 1) term(
                                shorthand.first().keyText + ":" + shorthand.first().value?.text.orEmpty(),
                                visiting
                            ) else unsupported()
                        }

                        else -> yaml(include, visiting)
                    }
                    val excludes = (include as? YAMLSequence)?.items.orEmpty().mapNotNull { it.value as? YAMLMapping }
                        .mapNotNull { DbtYaml.value(it, "exclude") }.flatMap { yaml(it, visiting) } + DbtYaml.value(
                        value,
                        "exclude"
                    )?.let { yaml(it, visiting) }.orEmpty()
                    val removed = excludes.map(::identity).toSet(); selected.filterNot { identity(it) in removed }
                }

                else -> unsupported("Unsupported selector value.")
            }

            private fun term(raw: String, visiting: Set<String>): List<DbtSymbol> {
                ProgressManager.checkCanceled();
                val core = DbtLineage.withoutOperators(raw);
                val method = if (':' in core) core.takeWhile { it != ':' } else "fqn"
                if (raw.startsWith('@') && raw.length > 1 && !raw.startsWith("@@")) {
                    val seeds = term(raw.drop(1), visiting);
                    val edges = DbtLineage.edges(w)
                    val children =
                        union(listOf(seeds, seeds.flatMap { DbtLineage.traverse(edges, it, true, Int.MAX_VALUE) }))
                    return union(
                        listOf(
                            children,
                            children.flatMap { DbtLineage.traverse(edges, it, false, Int.MAX_VALUE) })
                    )
                }
                if (raw.isEmpty() || core.isEmpty() || method !in Methods || !raw.matches(Regex("([0-9]*\\+)?[^+,\\s]+(\\+[0-9]*)?")) || raw.contains(
                        "{{"
                    ) || '@' in raw
                ) return unsupported("Selector '$raw' requires explicit dbt ls resolution.")
                if (method == "selector") {
                    val name = core.removePrefix("selector:")
                    if (name in visiting) return unsupported("Selector cycle: $name")
                    val selectorFile = w.rootFile(root, "selectors.yml")
                    val definitions =
                        selectorFile?.let(DbtYaml::mapping)?.let { DbtYaml.items(it, "selectors") }.orEmpty()
                            .filter { DbtYaml.string(it, "name") == name }
                    val recorded = if (selectorFile == null) manifestSelectors.filter { it.id == name } else emptyList()
                    if (definitions.size + recorded.size != 1) return unsupported("Selector '$name' is missing or ambiguous.")
                    val selected = definitions.firstOrNull()?.let { DbtYaml.value(it, "definition") }
                        ?.let { yaml(it, visiting + name) }.orEmpty() + recorded.flatMap {
                        json(
                            it.data,
                            visiting + name
                        )
                    }
                    val (parents, children) = DbtLineage.operators(raw)
                    return union(listOf(selected, DbtLineage.neighbours(w, selected, parents, children)))
                }
                val name = core.removePrefix("fqn:")
                val preferLocal =
                    method == "fqn" && DbtSyntax.isIdentifier(name) && w.declarations(DbtSymbol.RefKinds, name, root)
                        .any { it.namespace == root.name }
                val qualified = if (preferLocal) raw.replace(
                    core,
                    (if (core.startsWith("fqn:")) "fqn:" else "") + root.name + ".$name"
                ) else raw
                return DbtSelectors.select(w, root, qualified, true).filterIsInstance<DbtSymbolElement>()
                    .map { it.symbol }
            }

            private fun terms(text: String, visiting: Set<String>): List<DbtSymbol> =
                union(text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                    .map { part -> intersect(part.split(',').map { term(it, visiting) }) })

            fun resolve(expression: String, exclude: String): DbtSelection {
                val selected = terms(if (expression.trim().isEmpty()) "*" else expression, emptySet())
                val removed =
                    if (exclude.trim().isEmpty()) emptySet() else terms(exclude, emptySet()).map(::identity).toSet()
                return DbtSelection(selected.filter(DbtSelectors::selectable).filterNot { identity(it) in removed }
                    .distinctBy(::identity), problems.distinct())
            }
        }
    }
}

class DbtSelectorNavigation : DbtReferenceProvider {
    override fun references(context: DbtNavigationContext): List<DbtNavigationReference> =
        DbtSelectors.references(context.file, context.workspace, context.root, context.includeSelections)
}

object DbtSelectors {
    private val SelectableKinds = setOf(
        "model",
        "seed",
        "snapshot",
        "source_table",
        "test",
        "generic_test",
        "analysis",
        "exposure",
        "metric",
        "saved_query",
        "function",
        "check",
        "group",
        "unit_test",
        "semantic_model"
    )
    val MethodKeys = setOf(
        "fqn",
        "path",
        "package",
        "source",
        "tag",
        "group",
        "exposure",
        "metric",
        "saved_query",
        "test",
        "function",
        "check",
        "resource_type",
        "type",
        "file",
        "access",
        "config.materialized",
        "config.tags"
    )

    fun selectable(symbol: DbtSymbol): Boolean = symbol.kind in SelectableKinds && symbol.isEnabled
    fun folders(w: DbtWorkspace, s: DbtSymbol): List<String> =
        w.owner(s)?.resourceFolders(s.file.virtualFile)?.second.orEmpty()

    fun fqn(w: DbtWorkspace, s: DbtSymbol): List<String> =
        listOf(s.namespace) + folders(w, s) + s.name + listOfNotNull(s.version?.let { "v$it" })

    private fun glob(pattern: String, value: String): Boolean =
        runCatching { FileSystems.getDefault().getPathMatcher("glob:$pattern").matches(Paths.get(value)) }.getOrDefault(
            false
        )

    fun select(w: DbtWorkspace, root: DbtRoot, expression: String, includeGraph: Boolean): List<PsiElement> {
        val (prefix, suffix) = DbtLineage.operators(expression);
        val core = expression.drop(prefix?.length ?: 0).dropLast(suffix?.length ?: 0);
        val split = core.split(':', limit = 2)
        val method =
            if (split.size == 2) split.first() else if ('/' in core || core.matches(Regex(".*\\.(sql|csv|py)"))) "path" else "fqn";
        val value = split.last()
        if (method == "selector") return if (root.supports(12)) w.symbols.filter {
            it.kind == "selector" && it.name == value && w.root(
                it.file
            ) == root
        }.map { it.element } else emptyList()
        val candidates =
            w.symbols.filter { selectable(it) && it in w.declarations(setOf(it.kind), it.name, root, it.namespace) }
        val selected = candidates.filter { s ->
            when (method) {
                "fqn" -> {
                    val relative =
                        w.root(s.file)?.relative(s.file.virtualFile)?.removeSuffix("." + s.file.virtualFile.extension)
                            ?.replace('/', '.').orEmpty();
                    val parts = fqn(w, s)
                    val prefixes = parts.indices.map {
                        parts.take(it + 1).joinToString(".")
                    } + parts.drop(1).indices.map { parts.drop(1).take(it + 1).joinToString(".") }
                    glob(value, parts.joinToString(".")) || glob(value, s.name) || prefixes.any {
                        glob(
                            value,
                            it
                        )
                    } || glob(value, s.namespace + "." + s.name) || glob(
                        value,
                        s.namespace + "." + relative.removePrefix("models.")
                    )
                }

                "path" -> w.root(s.file)?.relative(s.file.virtualFile)
                    ?.let { glob(value, it) || it.startsWith(value.removeSuffix("/") + "/") } == true

                "file" -> glob(value, s.file.name.replaceFirst(Regex("\\.(sql|py|csv)$"), ""))
                "package" -> glob(value, s.namespace)
                "tag", "config.tags" -> s.attributes["tags"]?.split(',')?.any { glob(value, it) } == true
                "access" -> s.attributes["access"]?.let { glob(value, it) } == true
                "config.materialized" -> s.attributes["materialized"]?.let { glob(value, it) } == true
                "source" -> s.kind == "source_table" && (glob(value, s.owner + "." + s.name) || glob(
                    value,
                    s.owner
                ) || glob(value, s.namespace + "." + s.owner + "." + s.name))

                "resource_type", "type" -> glob(
                    value,
                    if (s.kind == "source_table") "source" else s.kind
                ) || s.kind == value

                "group" -> s.kind == "group" && glob(value, s.name) || s.attributes["group"]?.let {
                    glob(
                        value,
                        it
                    )
                } == true

                "exposure", "metric", "saved_query" -> s.kind == method && (glob(value, s.name) || glob(
                    value,
                    s.namespace + "." + s.name
                ))

                "function", "check", "test" -> s.kind == method && glob(value, s.name)
                else -> false
            }
        }.distinct()
        val expanded = selected + if (includeGraph) DbtLineage.neighbours(w, selected, prefix, suffix) else emptyList()
        return expanded.filter(::selectable).map { it.element }.distinct()
    }

    fun references(file: PsiFile, w: DbtWorkspace, root: DbtRoot, includeGraph: Boolean): List<DbtNavigationReference> {
        if (file.name != "selectors.yml") return emptyList()
        val result = mutableListOf<DbtNavigationReference>()
        file.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(e: PsiElement) {
                if (e is YAMLScalar) {
                    val ancestors = DbtYaml.ancestors(e);
                    val key = DbtYaml.key(e);
                    val method = DbtYaml.parentMapping(e)?.let { DbtYaml.string(it, "method") }
                    val selectionContext =
                        "definition" in ancestors && ancestors.none { it in setOf("vars", "env", "description") }
                    val named = selectionContext && (key == "selector" || key == "value" && method == "selector")
                    val expression = when {
                        !selectionContext -> null; key == "value" -> method?.let { "$it:${e.textValue}" }; key in MethodKeys -> "$key:${e.textValue}"; key == "definition" || e.parent is YAMLSequenceItem -> e.textValue; else -> null
                    }
                    val targets by lazy {
                        if (named) w.symbols.filter { it.kind == "selector" && it.file == file && it.name == e.textValue }
                            .map { it.element } else expression?.let { select(w, root, it, includeGraph) }.orEmpty()
                    }
                    if (selectionContext && expression == e.textValue && !named) DbtYaml.decoded(e)
                        ?.let { (text, offset) ->
                            Regex("[^\\s,]+").findAll(text).forEach { term ->
                                result += DbtNavigationReference(
                                    file,
                                    TextRange(offset(term.range.first), offset(term.range.last + 1)),
                                    select(w, root, term.value, includeGraph),
                                    term.value
                                )
                            }
                        }
                    else if (named || expression != null) result += DbtNavigationReference(
                        file,
                        e.textRange,
                        targets,
                        e.textValue
                    )
                }
                super.visitElement(e)
            }
        })
        return result
    }
}
