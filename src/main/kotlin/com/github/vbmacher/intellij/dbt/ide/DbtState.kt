package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project.*
import com.github.vbmacher.intellij.dbt.lineage.DbtLineage
import com.github.vbmacher.intellij.dbt.lineage.DbtSelection
import com.github.vbmacher.intellij.dbt.syntax.DbtYaml
import com.google.gson.*
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.notification.NotificationType
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.ui.popup.JBPopupFactory
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import org.jetbrains.yaml.psi.*

internal data class DbtStateChange(val id: String, val status: String, val reason: String, val relation: String)
internal data class DbtStateDependency(
    val id: String,
    val resolution: String,
    val relation: String,
    val evidence: String,
    val reason: String
)

internal data class DbtStatePreview(
    val baseline: String,
    val selected: List<DbtSymbol>,
    val dependencies: List<DbtStateDependency>,
    val problems: List<String>
)

/**
 * Compares authored resources with a validated baseline manifest without running dbt or checking the warehouse.
 * Unknown resolved defaults and dynamic expressions remain explicit uncertainty rather than proof of no change.
 */
internal object DbtState {
    private val ConfigKeys = setOf(
        "materialized",
        "enabled",
        "alias",
        "database",
        "schema",
        "tags",
        "access",
        "group",
        "unique_key",
        "on_schema_change",
        "incremental_strategy",
        "partition_by",
        "cluster_by",
        "contract",
        "persist_docs",
        "grants",
        "quoting",
        "full_refresh",
        "meta",
        "pre_hook",
        "post_hook"
    )
    private val ColumnKeys = setOf("description", "data_type", "quote", "constraints", "meta", "tags")
    private fun entries(manifest: DbtManifest): Map<String, JsonObject> =
        manifest.resources.associate { it.id to it.data }

    /** Resolve real existing ancestors too, so a not-yet-created output directory cannot hide a symlink alias. */
    fun canonical(path: Path): Result<Path> = dbtResult {
        fun resolve(p: Path): Path = if (Files.exists(
                p,
                LinkOption.NOFOLLOW_LINKS
            )
        ) p.toRealPath() else p.parent?.let { resolve(it).resolve(p.fileName) } ?: p
        resolve(path.toAbsolutePath().normalize()).normalize()
    }.fold(
        { Result.success(it) },
        { Result.failure(IllegalArgumentException("Cannot resolve baseline/output paths safely; check symlinks and permissions.")) })

    /** Resolves symlink aliases and rejects baseline/output overlap, including aliases of manifest.json. */
    fun separate(path: Path, output: Path): Result<Path> = dbtResult {
        val state = canonical(path).getOrThrow();
        val target = canonical(output).getOrThrow()
        val stateManifest = canonical(path.resolve("manifest.json")).getOrThrow();
        val targetManifest = canonical(output.resolve("manifest.json")).getOrThrow()
        require(!state.startsWith(target) && !target.startsWith(state) && !stateManifest.startsWith(target) && stateManifest != targetManifest) {
            "Baseline state must be separate from the active artifact directory, including symlink aliases."
        }; state
    }

    fun baseline(root: DbtRoot): Result<Path> = dbtResult {
        val configured = root.runOptions.stateDirectory.trim();
        val path = root.executionDirectory.resolve(configured).normalize().toAbsolutePath()
        require(configured.isNotEmpty()) { "Choose a baseline state directory." }
        val resolved = separate(path, root.artifactDirectory).getOrThrow()
        require(!Files.isRegularFile(path)) { "Choose a baseline directory containing manifest.json, not a file." }
        require(
            Files.isRegularFile(path.resolve("manifest.json")) || root.psiFile(
                path.resolve("manifest.json").toString()
            ) != null
        ) { "Baseline directory must contain manifest.json." }
        resolved
    }

    fun manifest(root: DbtRoot): Result<DbtManifest> = dbtResult {
        val path = baseline(root).getOrThrow()
        DbtManifestReader.get(root.config.project)
            .read(root, path.resolve("manifest.json").toString(), DbtManifestPolicy.Baseline).getOrThrow()
    }

    private fun current(w: DbtWorkspace, root: DbtRoot): Map<String, List<DbtSymbol>> = w.symbols.filter { s ->
        !s.metadata &&
                (s.isCode || s.kind in setOf("source_table", "exposure") || s.isProperty && s.kind in setOf(
                    "unit_test",
                    "metric",
                    "semantic_model",
                    "saved_query"
                )) &&
                w.owner(s)?.let { w.executionRoot(it).directory == w.executionRoot(root).directory } == true
    }.groupBy { it.uniqueId }.mapValues { (_, symbols) ->
        symbols.groupBy { w.owner(it)?.directory?.path }.values.flatMap { owned ->
            val code = owned.filter { it.isCode }; (if (code.isNotEmpty()) code else owned).distinctBy { it.id }
        }
    }

    private fun normalized(value: JsonElement): String = when {
        value.isJsonNull -> "null"; value.isJsonPrimitive -> JsonPrimitive(value.asString).toString()
        value.isJsonArray -> value.asJsonArray.joinToString(",", "[", "]", transform = ::normalized)
        else -> value.asJsonObject.entrySet().sortedBy { it.key }
            .joinToString(",", "{", "}") { JsonPrimitive(it.key).toString() + ":" + normalized(it.value) }
    }

    private fun empty(value: JsonElement): Boolean =
        value.isJsonNull || value.isJsonPrimitive && value.asString.isEmpty() || value.isJsonArray && value.asJsonArray.isEmpty || value.isJsonObject && value.asJsonObject.size() == 0

    private fun tags(value: JsonElement): List<String> = (when {
        value.isJsonArray -> value.asJsonArray.map(::normalized)
        value.isJsonPrimitive -> value.asString.split(',').map { JsonPrimitive(it.trim()).toString() }
        else -> listOf(normalized(value))
    }).sorted()

    private fun sourceDescription(w: DbtWorkspace, symbol: DbtSymbol): String =
        (listOf(symbol) + w.properties(symbol)).firstNotNullOfOrNull { s ->
            s.mapping?.let { m ->
                DbtYaml.string(m, "description") ?: DbtYaml.parentMapping(m)
                    ?.takeIf { DbtYaml.value(it, "versions") != null }?.let { DbtYaml.string(it, "description") }
            }
        } ?: symbol.attributes["description"].orEmpty()

    private data class Difference(val changed: Boolean = false, val unknown: Boolean = false) {
        fun combine(other: Difference): Difference = Difference(changed || other.changed, unknown || other.unknown)
    }

    /** Missing resolved nested fields can be defaults; they never prove a source change. */
    private fun difference(before: JsonElement, after: JsonElement): Difference {
        if (before.isJsonObject && after.isJsonObject) {
            val old = before.asJsonObject;
            val now = after.asJsonObject
            return (old.keySet() + now.keySet()).fold(Difference()) { result, key ->
                val a = old[key];
                val b = now[key]
                result.combine(
                    when {
                        a != null && b != null -> difference(
                            a,
                            b
                        ); a == null && b != null -> Difference(changed = true); a != null -> Difference(
                        unknown = !empty(
                            a
                        )
                    ); else -> Difference()
                    }
                )
            }
        }
        if (before.isJsonArray && after.isJsonArray && before.asJsonArray.size() == after.asJsonArray.size()) return before.asJsonArray.zip(
            after.asJsonArray
        ).fold(Difference()) { result, (a, b) -> result.combine(difference(a, b)) }
        return Difference(changed = normalized(before) != normalized(after))
    }

    private fun configKey(key: String): String = when (key) {
        "pre_hook" -> "pre-hook"; "post_hook" -> "post-hook"; else -> key
    }

    private fun yaml(value: YAMLValue): JsonElement = when (value) {
        is YAMLScalar -> if (value.text.lowercase(java.util.Locale.ROOT) in setOf(
                "null",
                "~"
            )
        ) JsonNull.INSTANCE else JsonPrimitive(value.textValue)

        is YAMLMapping -> JsonObject().apply {
            value.keyValues.forEach { k ->
                k.value?.let {
                    add(
                        k.keyText,
                        yaml(it)
                    )
                }
            }
        }

        is YAMLSequence -> JsonArray().apply { value.items.mapNotNull { it.value }.forEach { add(yaml(it)) } }
        else -> JsonNull.INSTANCE
    }

    private fun sourceConfig(w: DbtWorkspace, symbol: DbtSymbol): Map<String, JsonElement> {
        val values = symbol.attributes.filterKeys { it in ConfigKeys }
            .map { (k, v) -> configKey(k) to JsonPrimitive(v) as JsonElement }.toMap().toMutableMap()
        (w.properties(symbol) + symbol).distinctBy { it.id }.mapNotNull { it.mapping }.forEach { mapping ->
            DbtYaml.map(
                mapping,
                "config"
            )?.keyValues?.forEach { key -> key.value?.let { values[configKey(key.keyText)] = yaml(it) } }
        }
        return values
    }

    /** The diff labels distinguish recorded baseline values from authored, unresolved source values. */
    fun comparison(w: DbtWorkspace, root: DbtRoot, id: String): Result<Pair<String, String>> =
        manifest(root).map { baseline ->
            val before = entries(baseline)[id]?.let { entry ->
                DbtJson.obj(
                    "raw_code" to (DbtJson.text(entry, "raw_code") ?: DbtJson.text(entry, "raw_sql")).orEmpty(),
                    "description" to DbtJson.text(entry, "description").orEmpty(),
                    "config" to (DbtJson.child(entry, "unrendered_config") ?: DbtJson.child(entry, "config")
                    ?: JsonObject()),
                    "columns" to (DbtJson.child(entry, "columns") ?: JsonObject())
                )
            }
            val after = current(w, root)[id]?.singleOrNull()?.let { symbol ->
                val config = JsonObject(); sourceConfig(w, symbol).toSortedMap().forEach { (k, v) -> config.add(k, v) }
                val columns = JsonObject(); w.columns(symbol).filterNot { it.metadata }
                .forEach { column -> column.mapping?.let { columns.add(column.name, yaml(it)) } }
                DbtJson.obj(
                    "raw_code" to (if (symbol.isCode) symbol.file.text else ""),
                    "description" to sourceDescription(w, symbol),
                    "config" to config,
                    "columns" to columns
                )
            }
            val gson = GsonBuilder().setPrettyPrinting().create()
            (before?.let { gson.toJson(it) }.orEmpty()) to after?.let { gson.toJson(it) }.orEmpty()
        }

    fun compare(w: DbtWorkspace, root: DbtRoot): Result<List<DbtStateChange>> =
        manifest(root).map { compareManifest(w, root, it) }

    fun compareManifest(w: DbtWorkspace, root: DbtRoot, baseline: DbtManifest): List<DbtStateChange> {
        val old = entries(baseline);
        val now = current(w, root)
        return (old.keys + now.keys).sorted().mapNotNull { id ->
            ProgressManager.checkCanceled()
            val previous = old[id];
            val symbols = now[id]
            when {
                previous == null && symbols != null -> DbtStateChange(id, "added", "No baseline declaration", "")
                previous != null && symbols == null -> {
                    val generatedTest = id.startsWith("test.") && (previous.has("test_metadata") || DbtJson.text(
                        previous,
                        "original_file_path"
                    )?.let { it.endsWith(".yml") || it.endsWith(".yaml") } == true)
                    DbtStateChange(
                        id,
                        if (generatedTest) "unknown" else "removed",
                        if (generatedTest) "Generated test identity cannot be proven from current source" else "No current source declaration",
                        DbtJson.text(previous, "relation_name").orEmpty()
                    )
                }

                previous != null && symbols?.size == 1 -> {
                    val symbol = symbols.single();
                    val changed = mutableListOf<String>();
                    val unknown = mutableListOf<String>()
                    if (symbol.isCode) {
                        val oldCode = (DbtJson.text(previous, "raw_code") ?: DbtJson.text(
                            previous,
                            "raw_sql"
                        ))?.takeIf { it.isNotEmpty() }
                        if (oldCode == null) unknown += "Baseline source code unavailable" else if (oldCode.trim() != symbol.file.text.trim()) changed += "Source SQL/code changed"
                    }
                    val config = sourceConfig(w, symbol)
                    val oldConfig = DbtJson.child(previous, "unrendered_config") ?: DbtJson.child(previous, "config")
                    if (oldConfig == null) unknown += "Baseline resolved config unavailable" else {
                        (ConfigKeys.map(::configKey) + config.keys).distinct().sorted().forEach { key ->
                            val before = oldConfig[key] ?: if (symbol.kind == "source_table" && key in setOf(
                                    "database",
                                    "schema"
                                )
                            ) previous["unrendered_$key"] else null
                            val after = config[key]
                            if (after != null) {
                                if (normalized(after).contains("{{") || normalized(after).contains("{%")) unknown += "Dynamic config: $key"
                                else if (before == null) changed += "Config added: $key"
                                else if (key in setOf("pre-hook", "post-hook") && DbtJson.child(
                                        previous,
                                        "unrendered_config"
                                    ) == null && !empty(before)
                                ) unknown += "Hook normalization requires dbt: $key"
                                else {
                                    val result =
                                        if (key == "tags" && !after.isJsonNull && !before.isJsonNull) Difference(
                                            changed = tags(before) != tags(after)
                                        ) else difference(before, after)
                                    if (result.changed) changed += "Config changed: $key"
                                    if (result.unknown) unknown += "Resolved nested config defaults unknown: $key"
                                }
                            } else if (before != null && !empty(before)) unknown += "Current resolved config unknown: $key"
                        }
                    }
                    val description = sourceDescription(w, symbol)
                    if (description.contains("{{")) unknown += "Dynamic description" else if (DbtJson.text(
                            previous,
                            "description"
                        )?.let { it != description } == true
                    ) changed += "Description changed"
                    val columns = DbtJson.child(previous, "columns")
                    if (columns == null) unknown += "Baseline column declarations unavailable" else {
                        val declared =
                            w.columns(symbol).filter { !it.metadata && it.mapping != null }.distinctBy { it.name }
                        if (columns.keySet() != declared.map { it.name }
                                .toSet()) changed += "Column declarations changed"
                        declared.forEach { column ->
                            DbtJson.child(columns, column.name)?.let { oldColumn ->
                                ColumnKeys.sorted().forEach { key ->
                                    val before = oldColumn[key]?.takeUnless { it.isJsonNull }
                                    val after = column.mapping?.let { DbtYaml.value(it, key) }?.let(::yaml)
                                        ?: column.attributes[key]?.let(::JsonPrimitive)
                                    if (after != null && normalized(after).contains("{{")) unknown += "Dynamic column metadata: ${column.name}.$key"
                                    else {
                                        val result = when {
                                            before != null && after != null -> difference(
                                                before,
                                                after
                                            ); before != null -> Difference(changed = !empty(before)); after != null -> Difference(
                                                changed = !empty(after)
                                            ); else -> Difference()
                                        }
                                        if (result.changed) changed += "Column metadata changed: ${column.name}.$key"
                                        if (result.unknown) unknown += "Resolved column defaults unknown: ${column.name}.$key"
                                    }
                                }
                            }
                        }
                    }
                    val reasons = changed + unknown.distinct().map { "Unresolved: $it" }
                    if (changed.isNotEmpty() || unknown.isNotEmpty()) DbtStateChange(
                        id,
                        if (changed.isNotEmpty()) "modified" else "unknown",
                        reasons.joinToString("; "),
                        DbtJson.text(previous, "relation_name").orEmpty()
                    ) else null
                }

                else -> DbtStateChange(id, "unknown", "Ambiguous current owning project/resource identity", "")
            }
        }
    }

    /**
     * Resolves the local state selection and classifies unselected upstream relations for deferral review.
     * Candidate/unknown outcomes retain missing evidence; no target relation existence is queried or assumed.
     */
    fun preview(w: DbtWorkspace, root: DbtRoot, expression: String, exclude: String = ""): Result<DbtStatePreview> =
        dbtResult {
            val path = baseline(root).getOrThrow();
            val data = manifest(root).getOrThrow();
            val old = entries(data);
            val now = current(w, root)
            val changes = compareManifest(w, root, data);
            val problems = mutableListOf<String>();
            val unknown = changes.count { it.status == "unknown" }
            if (unknown > 0) problems += "$unknown resources have unknown state; local state selection is partial."
            if (root.runOptions.defer && root.engine != "core") problems += "Core deferral flags are unavailable for the configured engine."
            if (root.runOptions.favorState && (!root.runOptions.defer || root.engine != "core" || !root.supports(4))) problems += "Favor state requires deferral and configured dbt Core 1.4+; the command is not executable with these options."
            val edges = DbtLineage.edges(w)
            fun key(s: DbtSymbol): String = DbtResourceIdentity.key(w, s)
            fun term(raw: String): List<DbtSymbol> {
                val core = DbtLineage.withoutOperators(raw)
                if (!core.startsWith("state:")) {
                    val resolved =
                        DbtSelection.resolve(w, root, raw); problems += resolved.problems; return resolved.resources
                }
                val ids = when (core.removePrefix("state:")) {
                    "modified" -> changes.filter { it.status == "modified" }.map { it.id }.toSet()
                    "new" -> changes.filter { it.status == "added" }.map { it.id }.toSet()
                    "old" -> old.keys; "unmodified" -> old.keys - changes.map { it.id }.toSet()
                    else -> {
                        problems += "Unsupported local state selector: $core"; emptySet()
                    }
                }
                val matched = now.filterKeys { it in ids }.values.flatten().filter { it.isEnabled }
                val (parents, children) = DbtLineage.operators(raw)
                val parentDepth = parents?.dropLast(1)?.toIntOrNull() ?: 100;
                val childDepth = children?.drop(1)?.toIntOrNull() ?: 100
                return (matched + matched.flatMap { s ->
                    (if (parents != null) DbtLineage.traverse(
                        edges,
                        s,
                        false,
                        minOf(parentDepth, 100)
                    ) else emptyList()) +
                            (if (children != null) DbtLineage.traverse(
                                edges,
                                s,
                                true,
                                minOf(childDepth, 100)
                            ) else emptyList())
                }).distinctBy(::key)
            }

            fun selection(text: String): List<DbtSymbol> =
                text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.flatMap { token ->
                    val groups = token.split(',').map(::term)
                    groups.firstOrNull().orEmpty()
                        .filter { s -> groups.drop(1).all { group -> group.any { key(it) == key(s) } } }
                }.distinctBy(::key)

            val chosen = selection(if (expression.trim().isEmpty()) "*" else expression)
            val removed = if (exclude.trim().isEmpty()) emptySet() else selection(exclude).map(::key).toSet()
            val selected = chosen.filterNot { key(it) in removed };
            val selectedIds = selected.map(::key).toSet()
            val upstream = selected.flatMap { DbtLineage.traverse(edges, it, false, 100) }.distinctBy(::key)
                .filterNot { key(it) in selectedIds }
            val dependencies = upstream.filter { it.kind in DbtSymbol.RelationKinds }.take(1000).map { symbol ->
                val previous = old[symbol.uniqueId]
                val relation = previous?.let { DbtJson.text(it, "relation_name") }
                    ?.takeUnless { it.contains("{{") || it.contains("{%") }.orEmpty()
                val ephemeral = symbol.attributes["materialized"] == "ephemeral"
                val resolution =
                    if (symbol.kind == "source_table") "source" else if (ephemeral || !root.runOptions.defer) "local"
                    else if (relation.isEmpty() || symbol.isStale || symbol.metadata || symbol.attributes["materialized"]?.contains(
                            "{{"
                        ) == true
                    ) "unknown" else if (root.runOptions.favorState) "deferred" else "candidate"
                val reason = when (resolution) {
                    "source" -> "source() references do not defer; use current source configuration."
                    "local" -> if (ephemeral) "Ephemeral dependency is compiled locally." else "Deferral is disabled."
                    "unknown" -> "Baseline physical relation or current materialization/identity is unavailable; no target fallback is assumed."
                    "deferred" -> "Unselected dependency uses the baseline relation with favor-state."
                    else -> "May use baseline only if the current target relation does not exist; warehouse existence was not queried."
                }
                DbtStateDependency(
                    symbol.uniqueId,
                    resolution,
                    if (resolution in setOf("source", "local")) "" else relation,
                    path.resolve("manifest.json").toString() + "#" + symbol.uniqueId,
                    reason
                )
            }
            if (upstream.size > 1000) problems += "Dependency preview is limited to 1000 resources and 100 graph steps."
            DbtStatePreview(path.toString(), selected, dependencies, problems.distinct())
        }
}

internal object DbtStateComparison {
    fun show(configuration: DbtRunConfiguration) {
        val project = configuration.project
        DbtUi.readLater(project, read = {
            dbtResult {
                val w = DbtWorkspace.get(project);
                val root = configuration.configuredRoot(w).getOrThrow()
                configuration.sourceKey(w) to DbtState.compare(w, root).getOrThrow()
            }
        }) { result ->
            result.fold(
                { (key, changes) ->
                    if (changes.isEmpty()) DbtNotifications.show(
                        project,
                        "dbt baseline comparison",
                        "No locally detectable changes.",
                        NotificationType.INFORMATION
                    )
                    else {
                        val choices = changes.associate { (it.status + ": " + it.id + " · " + it.reason) to it.id }
                        JBPopupFactory.getInstance().createPopupChooserBuilder(choices.keys.sorted())
                            .setTitle("Compare baseline with current source").setItemChosenCallback { label ->
                            DbtUi.readLater(project, read = {
                                dbtResult {
                                    val w = DbtWorkspace.get(project)
                                    require(configuration.sourceKey(w) == key) { "Source or settings changed. Compare baseline again." }
                                    val root = configuration.configuredRoot(w).getOrThrow(); DbtState.comparison(
                                    w,
                                    root,
                                    choices.getValue(label)
                                ).getOrThrow()
                                }
                            }) { compared ->
                                compared.fold(
                                    { (before, after) ->
                                        val factory = DiffContentFactory.getInstance()
                                        DiffManager.getInstance().showDiff(
                                            project,
                                            SimpleDiffRequest(
                                                label,
                                                factory.create(before),
                                                factory.create(after),
                                                "Baseline manifest (recorded)",
                                                "Current source (unresolved defaults)"
                                            )
                                        )
                                    },
                                    {
                                        DbtNotifications.show(
                                            project,
                                            "dbt baseline comparison",
                                            it.message.orEmpty(),
                                            NotificationType.ERROR
                                        )
                                    })
                            }
                        }.createPopup().showCenteredInCurrentWindow(project)
                    }
                },
                {
                    DbtNotifications.show(
                        project,
                        "dbt baseline comparison",
                        it.message.orEmpty(),
                        NotificationType.ERROR
                    )
                })
        }
    }
}
