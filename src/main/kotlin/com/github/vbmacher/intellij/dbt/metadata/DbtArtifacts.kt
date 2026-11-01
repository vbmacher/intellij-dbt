package com.github.vbmacher.intellij.dbt.metadata

import com.github.vbmacher.intellij.dbt.lineage.*
import com.github.vbmacher.intellij.dbt.navigation.DbtNavigationReference
import com.github.vbmacher.intellij.dbt.project.*
import com.google.gson.JsonParser
import com.intellij.json.psi.*
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.search.PsiSearchScopeUtil
import com.intellij.psi.search.SearchScope
import com.intellij.psi.util.*
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant

/** Shared artifact ownership and staleness checks; source declarations remain authoritative over metadata copies. */
internal object DbtArtifacts {
    fun stale(w: DbtWorkspace, root: DbtRoot, generatedAt: String?): Boolean =
        w.outdated(root, generatedAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() })

    fun visible(w: DbtWorkspace, root: DbtRoot, s: DbtSymbol): Boolean =
        w.owner(s)?.let { w.namespaceVisible(root, it, true) } == true

    fun authored(root: DbtRoot, namespace: String, path: String?): Boolean =
        namespace == root.name && path?.let { root.directory.findFileByRelativePath(it) != null } == true

    fun withSource(symbol: DbtSymbol, source: String?, sourceRange: () -> TextRange): List<DbtSymbol> =
        (if (symbol.kind == "source_table") listOfNotNull(source?.let {
            DbtSymbol(
                "source",
                it,
                symbol.namespace,
                symbol.file,
                sourceRange(),
                attributes = symbol.attributes - "unique_id",
                metadata = true
            )
        }) else emptyList()) + symbol

    fun identified(w: DbtWorkspace, root: DbtRoot, id: String): List<DbtSymbol> = w.symbols.filter {
        it.uniqueId == id && it.kind != "column" && it in w.declarations(
            setOf(it.kind),
            it.name,
            root,
            it.namespace
        )
    }
}

/**
 * Adds declarations, documentation and dependency edges from configured manifest and catalog files.
 * Existing authored resources are not shadowed. Stale catalogs can enrich documentation but do not
 * contribute column declarations.
 * Malformed or unsupported artifacts remain unavailable and can suppress missing-target diagnostics.
 */
class DbtArtifactSource : DbtDeclarationSource, DbtDependencySource {
    override fun inputs(workspace: DbtWorkspace): List<VirtualFile> = workspace.roots.flatMap { r ->
        listOf("manifest", "state-manifest", "catalog").mapNotNull(r::setting).mapNotNull(r::psiFile)
            .map { it.virtualFile }
    }

    override fun artifactStatus(root: DbtRoot): List<String> = listOf("manifest", "catalog").map { kind ->
        val file = root.setting(kind)?.let(root::psiFile)
        if (file == null) "$kind unavailable. Generate Docs or check the artifact directory."
        else top(root, kind)?.let { obj ->
            val metadata = child(obj, "metadata"); "$kind loaded: ${file.virtualFile.path}; generated: ${
            metadata?.let {
                text(
                    it,
                    "generated_at"
                )
            } ?: "unknown"
        }; dbt: ${metadata?.let { text(it, "dbt_version") } ?: "unknown"}; adapter: ${
            metadata?.let {
                text(
                    it,
                    "adapter_type"
                )
            } ?: "unknown"
        }"
        } ?: "$kind malformed or unsupported schema. Regenerate Docs or select a supported artifact."
    }

    override fun canReportMissing(workspace: DbtWorkspace, root: DbtRoot): Boolean =
        root.runOptions.manifestPath.isEmpty() && root.setting("manifest")?.let(root::psiFile) == null || top(
            root,
            "manifest"
        ) != null

    override fun documentation(w: DbtWorkspace, s: DbtSymbol): DbtResourceData? = w.owner(s)?.let { owner ->
        val root = w.packageOwner(owner) ?: owner
        listOf("manifest", "catalog").fold(DbtResourceData()) { data, setting ->
            val selected = artifact(root, setting)
            val entries = selected?.let { (_, top, manifest) ->
                resources(
                    top,
                    manifest,
                    "nodes",
                    "sources",
                    "macros",
                    "exposures"
                )
            }.orEmpty()
            entries.find { text(it, "unique_id") == s.uniqueId }?.let { entry ->
                val metadata = child(entry, "metadata")
                val attrs = listOf(
                    "database",
                    "schema",
                    "alias",
                    "identifier",
                    "relation_name",
                    "original_file_path",
                    "patch_path",
                    "package_name",
                    "version"
                ).mapNotNull { k -> (text(entry, k) ?: metadata?.let { text(it, k) })?.let { k to it } }.toMap() +
                        child(entry, "config")?.propertyList.orEmpty().mapNotNull { p ->
                            p.value?.takeUnless { it is JsonNullLiteral }
                                ?.let { p.name to if (it is JsonStringLiteral) it.value else it.text }
                        }.toMap() +
                        listOfNotNull(property(entry, "fqn")?.let { "fqn" to it.text }).toMap() +
                        selected?.second?.let { child(it, "metadata") }?.let { m ->
                            listOf("generated_at", "adapter_type", "dbt_version").mapNotNull { k ->
                                text(
                                    m,
                                    k
                                )?.let { k to it }
                            }.toMap()
                        }.orEmpty()
                val deps = child(entry, "depends_on")?.let { strings(it, "nodes") }.orEmpty()
                val tests = selected?.let { (_, top, manifest) -> resources(top, manifest, "nodes") }.orEmpty().filter {
                    text(it, "resource_type") == "test" && child(
                        it,
                        "depends_on"
                    )?.let { d -> s.uniqueId in strings(d, "nodes") } == true
                }.map { t ->
                    val generic = child(t, "test_metadata")
                    val name = generic?.let { text(it, "name") }?.let { n ->
                        generic.let { text(it, "namespace") }?.takeIf { it.isNotEmpty() }?.let { "$it." }.orEmpty() + n
                    } ?: text(t, "name") ?: "test"
                    DbtTestData(name, text(t, "column_name").orEmpty())
                }
                data.enrich(
                    DbtResourceData(
                        text(entry, "description") ?: metadata?.let { text(it, "comment") }.orEmpty(),
                        objects(entry, "columns").mapNotNull { c ->
                            text(c, "name")?.let {
                                DbtColumnData(
                                    it,
                                    (text(c, "data_type") ?: text(c, "type")).orEmpty(),
                                    (text(c, "description") ?: text(c, "comment")).orEmpty(),
                                    objects(c, "constraints").mapNotNull { text(it, "type") })
                            }
                        },
                        tests,
                        strings(entry, "tags"),
                        attrs + mapOf(
                            "artifact-project" to root.directory.path,
                            "artifact-target" to root.selectedTarget.orEmpty()
                        ),
                        text(entry, "raw_code") ?: text(entry, "raw_sql"),
                        text(entry, "compiled_code") ?: text(entry, "compiled_sql"),
                        objects(entry, "arguments").mapNotNull { a ->
                            text(a, "name")?.let {
                                DbtColumnData(
                                    it,
                                    text(a, "type").orEmpty(),
                                    text(a, "description").orEmpty()
                                )
                            }
                        },
                        deps
                    )
                )
            } ?: data
        }
    }

    override fun declarations(workspace: DbtWorkspace): List<DbtSymbol> {
        val knownColumns by lazy {
            workspace.symbols.filter { it.kind == "column" }.map { s ->
                ProgressManager.checkCanceled(); ColumnIdentity(
                workspace.owner(s),
                s.namespace,
                s.owner,
                s.name
            )
            }.toSet()
        }
        return workspace.roots.flatMap { root ->
            val visibleSymbols by lazy {
                workspace.symbols.filter {
                    ProgressManager.checkCanceled(); DbtArtifacts.visible(
                    workspace,
                    root,
                    it
                )
                }
            }
            val byId by lazy { visibleSymbols.groupBy { it.uniqueId }.mapValues { it.value.first() } }
            val identities by lazy {
                visibleSymbols.map {
                    DeclarationIdentity(
                        it.namespace,
                        it.name,
                        it.kind,
                        it.version,
                        if (it.kind == "source_table") it.owner else ""
                    )
                }.toSet()
            }
            listOf("manifest", "state-manifest", "catalog").flatMap { setting ->
                artifact(root, setting)?.let { (file, top, manifest) ->
                    val outdated =
                        DbtArtifacts.stale(workspace, root, child(top, "metadata")?.let { text(it, "generated_at") })
                    resources(
                        top,
                        manifest,
                        "nodes",
                        "sources",
                        "macros",
                        "docs",
                        "metrics",
                        "exposures",
                        "groups",
                        "semantic_models",
                        "saved_queries",
                        "functions",
                        "checks"
                    ).flatMap { entry ->
                        ProgressManager.checkCanceled()
                        val id = text(entry, "unique_id").orEmpty();
                        val rawKind = text(entry, "resource_type") ?: id.split('.').firstOrNull();
                        val kind = rawKind?.let { if (it == "source") "source_table" else it }
                        val sourceName =
                            text(entry, "source_name") ?: if (rawKind == "source") id.split('.').getOrNull(2) else null
                        val catalogSource = byId[id];
                        val namespace = text(entry, "package_name") ?: catalogSource?.namespace ?: root.name
                        val name = text(entry, "name") ?: catalogSource?.relationName;
                        val version = text(entry, "version")
                        val existing =
                            name != null && kind != null && (kind != "source_table" || sourceName != null) && DeclarationIdentity(
                                namespace,
                                name,
                                kind,
                                version,
                                if (kind == "source_table") sourceName!! else ""
                            ) in identities
                        val enabled = child(entry, "config")?.let { property(it, "enabled") }?.text != "false"
                        val dependencies =
                            child(entry, "depends_on")?.let { strings(it, "nodes") + strings(it, "macros") }.orEmpty()
                        val attrs = mapOf(
                            "unique_id" to id,
                            "artifact" to setting,
                            "resource-root" to root.directory.path,
                            "stale" to outdated.toString(),
                            "dependencies" to dependencies.joinToString(",")
                        ) + listOfNotNull(
                            text(entry, "access")?.let { "access" to it },
                            text(entry, "latest_version")?.let { "latest_version" to it }).toMap()
                        val declaration =
                            if (kind != null && name != null && enabled && !existing && !DbtArtifacts.authored(
                                    root,
                                    namespace,
                                    text(entry, "original_file_path")
                                ) && setting != "catalog"
                            ) listOf(
                                DbtSymbol(
                                    kind,
                                    name,
                                    namespace,
                                    file,
                                    range(entry, "name"),
                                    if (kind == "source_table") sourceName.orEmpty() else "",
                                    version,
                                    attrs,
                                    metadata = true
                                )
                            ) else emptyList()
                        val columns =
                            if (setting == "catalog" && !outdated) objects(entry, "columns").mapNotNull { col ->
                                text(
                                    col,
                                    "name"
                                )?.let { n ->
                                    name?.takeIf {
                                        ColumnIdentity(
                                            catalogSource?.let(workspace::owner) ?: root, namespace, it, n
                                        ) !in knownColumns
                                    }?.let { owner ->
                                        DbtSymbol(
                                            "column",
                                            n,
                                            namespace,
                                            file,
                                            range(col, "name"),
                                            owner,
                                            catalogSource?.version,
                                            attrs + (catalogSource?.let(workspace::owner)
                                                ?.let { mapOf("resource-root" to it.directory.path) } ?: emptyMap()),
                                            metadata = true)
                                    }
                                }
                            } else emptyList()
                        declaration.flatMap {
                            DbtArtifacts.withSource(it, sourceName) {
                                range(
                                    entry,
                                    "source_name"
                                )
                            }
                        } + columns
                    }
                }.orEmpty()
            }
        }
    }

    override fun dependencies(w: DbtWorkspace, scope: SearchScope?): List<DbtDependency> = w.symbols.filter {
        (it.metadata || it.kind in DbtResourceData.GraphKinds) && !it.isProperty && (scope == null || PsiSearchScopeUtil.isInScope(
            scope,
            it.file
        ))
    }.flatMap { from ->
        val recorded = from.attributes["dependencies"]?.split(',').orEmpty();
        val ids = if (from.metadata) recorded else DbtResourceData.get(w, from).dependencies
        ids.filter { it.isNotEmpty() }.flatMap { id ->
            val targets = w.owner(from)?.let { DbtArtifacts.identified(w, it, id) }.orEmpty();
            val selected = targets.filterNot { it.isProperty && targets.any { t -> t.isCode } }
            val reference =
                DbtNavigationReference(from.file, from.range, selected.map { it.element }, "artifact dependency")
            selected.map { DbtDependency(from, it, reference) }
        }
    }

    companion object {
        private data class ColumnIdentity(
            val root: DbtRoot?,
            val namespace: String,
            val owner: String,
            val name: String
        )

        private data class DeclarationIdentity(
            val namespace: String,
            val name: String,
            val kind: String,
            val version: String?,
            val owner: String
        )

        private fun property(obj: JsonObject, name: String): JsonValue? = obj.findProperty(name)?.value
        private fun child(obj: JsonObject, name: String): JsonObject? = property(obj, name) as? JsonObject
        private fun range(obj: JsonObject, name: String): TextRange = property(obj, name)?.textRange ?: obj.textRange
        private fun text(obj: JsonObject, name: String): String? = when (val p = property(obj, name)) {
            is JsonStringLiteral -> p.value; is JsonNumberLiteral -> p.text; else -> null
        }

        private fun objects(obj: JsonObject, name: String): List<JsonObject> = when (val p = property(obj, name)) {
            is JsonObject -> p.propertyList.mapNotNull { it.value as? JsonObject }; is JsonArray -> p.valueList.filterIsInstance<JsonObject>(); else -> emptyList()
        }

        private fun strings(obj: JsonObject, name: String): List<String> =
            (property(obj, name) as? JsonArray)?.valueList?.filterIsInstance<JsonStringLiteral>()?.map { it.value }
                .orEmpty()

        private fun artifact(root: DbtRoot, setting: String): Triple<JsonFile, JsonObject, DbtManifest?>? {
            val file = root.setting(setting)?.let(root::psiFile) as? JsonFile ?: return null
            if (PsiTreeUtil.hasErrorElements(file)) return null
            val top = file.topLevelValue as? JsonObject ?: return null
            return if (setting == "catalog") if (child(top, "metadata")?.let {
                    text(
                        it,
                        "dbt_schema_version"
                    )
                } == "https://schemas.getdbt.com/dbt/catalog/v1.json") Triple(file, top, null) else null
            else DbtManifestReader.get(root.config.project)
                .read(root, file.virtualFile.path, DbtManifestPolicy.Navigation).getOrNull()
                ?.let { Triple(file, top, it) }
        }

        private fun resources(top: JsonObject, manifest: DbtManifest?, vararg sections: String): List<JsonObject> =
            if (manifest != null) manifest.resourcesIn(*sections)
                .mapNotNull { r -> child(top, r.section)?.let { child(it, r.id) } } else sections.flatMap {
                objects(
                    top,
                    it
                )
            }

        private fun top(root: DbtRoot, setting: String): JsonObject? = artifact(root, setting)?.second
    }
}

/**
 * Reads manifest schemas v9–v12 with policy-specific limits and structured failures.
 * Baseline reads additionally validate resource identities and shapes. Editor text is authoritative when unsaved;
 * test discovery prefers disk otherwise and rejects manifests explicitly naming another project.
 */
class DbtJsonManifestReader : DbtManifestReader {
    private val sections = listOf(
        "nodes",
        "sources",
        "macros",
        "docs",
        "metrics",
        "exposures",
        "groups",
        "semantic_models",
        "saved_queries",
        "functions",
        "checks",
        "unit_tests",
        "selectors"
    )
    private val baselineSections =
        setOf("nodes", "sources", "exposures", "unit_tests", "metrics", "semantic_models", "saved_queries")
    private val schemas = (9..12).associate { "https://schemas.getdbt.com/dbt/manifest/v$it.json" to it }
    private val cache = listOf(
        DbtManifestPolicy.Navigation,
        DbtManifestPolicy.TestDiscovery,
        DbtManifestPolicy.Baseline
    ).associateWith { Key.create<CachedValue<Result<DbtManifest>>>("dbt.manifest.$it") }

    override fun parse(text: String, policy: DbtManifestPolicy): Result<DbtManifest> {
        val label = if (policy == DbtManifestPolicy.Baseline) "Baseline manifest" else "Manifest"
        fun fail(message: String): Result<DbtManifest> = Result.failure(IllegalArgumentException(message))
        if (text.length > policy.maxBytes) return fail("$label exceeds the ${policy.maxBytes / 1000000} MB limit.")
        val value = try {
            JsonParser.parseString(text)
        } catch (_: Exception) {
            return fail("$label is malformed.")
        }
        if (!value.isJsonObject) return fail("$label must be an object.")
        val top = value.asJsonObject
        val version = DbtJson.child(top, "metadata")?.let { DbtJson.text(it, "dbt_schema_version") }?.let(schemas::get)
        val records = sections.flatMap { section ->
            DbtJson.child(top, section)?.entrySet().orEmpty().filter { it.value.isJsonObject }
                .map { DbtManifestResource(section, it.key, it.value.asJsonObject) }
        }
        val baseline = policy == DbtManifestPolicy.Baseline;
        val checked = records.filter { it.section in baselineSections }
        val shapesValid = baselineSections.all { !top.has(it) || DbtJson.child(top, it) != null }
        val entriesValid = baselineSections.all { section ->
            DbtJson.child(top, section)?.entrySet()?.all { it.value.isJsonObject } ?: true
        }
        val valid = entriesValid && checked.map { it.id }.distinct().size == checked.size && checked.all { r ->
            val node = r.data
            (DbtJson.text(node, "unique_id")?.let { it == r.id } ?: true) && listOf(
                "config",
                "columns",
                "depends_on"
            ).all { !node.has(it) || DbtJson.child(node, it) != null } && (DbtJson.child(node, "columns")?.entrySet()
                ?.all { it.value.isJsonObject } ?: true) &&
                    (DbtJson.child(node, "depends_on")?.get("nodes")
                        ?.let { v -> v.isJsonArray && v.asJsonArray.all { it.isJsonPrimitive && it.asJsonPrimitive.isString } }
                        ?: true)
        }
        return when {
            version == null || baseline && DbtJson.child(
                top,
                "nodes"
            ) == null -> fail("$label schema is missing or unsupported; supported manifest schemas are v9-v12.")

            baseline && !shapesValid -> fail("Baseline manifest resource sections must be objects.")
            baseline && checked.size > 50000 -> fail("Baseline manifest exceeds 50000 resource entries.")
            baseline && !valid -> fail("Baseline resource identities, columns, config or dependency shapes are malformed.")
            else -> Result.success(
                DbtManifest(
                    version,
                    DbtJson.child(top, "metadata")?.let { DbtJson.text(it, "project_name") },
                    if (baseline) checked else records
                )
            )
        }
    }

    override fun read(root: DbtRoot, path: String, policy: DbtManifestPolicy): Result<DbtManifest> {
        val psi = root.psiFile(path)
        fun editor(): Result<DbtManifest> =
            if (psi == null) Result.failure(IllegalArgumentException("Cannot read manifest.")) else CachedValuesManager.getManager(
                psi.project
            ).getCachedValue(
                psi,
                cache.getValue(policy),
                { CachedValueProvider.Result.create(parse(psi.text, policy), psi) },
                false
            )

        fun disk(): Result<DbtManifest> = try {
            val file = Paths.get(root.directory.path).resolve(path).normalize()
            Files.newInputStream(file).use { stream ->
                val bytes =
                    stream.readNBytes(if (policy.maxBytes == Int.MAX_VALUE) Int.MAX_VALUE else policy.maxBytes + 1)
                if (bytes.size > policy.maxBytes) Result.failure(IllegalArgumentException("Manifest exceeds the ${policy.maxBytes / 1000000} MB limit.")) else parse(
                    String(bytes, StandardCharsets.UTF_8),
                    policy
                )
            }
        } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
            throw e
        } catch (_: Exception) {
            Result.failure(IllegalArgumentException("Cannot read manifest."))
        }

        val unsaved = psi?.let { PsiDocumentManager.getInstance(it.project).getDocument(it) }
            ?.let { FileDocumentManager.getInstance().isDocumentUnsaved(it) } == true
        val result =
            if (policy == DbtManifestPolicy.TestDiscovery && !unsaved) disk().let { if (it.isSuccess) it else editor() } else if (psi != null) editor() else disk()
        return result.fold({ manifest ->
            if (policy == DbtManifestPolicy.TestDiscovery && manifest.projectName?.let { it.isNotEmpty() && it != root.name } == true) Result.failure(
                IllegalArgumentException("Manifest belongs to another project.")
            ) else Result.success(manifest)
        }, { Result.failure(it) })
    }
}
