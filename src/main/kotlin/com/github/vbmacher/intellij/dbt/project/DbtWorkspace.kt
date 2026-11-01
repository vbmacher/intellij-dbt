package com.github.vbmacher.intellij.dbt.project

import com.github.vbmacher.intellij.dbt.syntax.DbtSyntax
import com.github.vbmacher.intellij.dbt.syntax.DbtYaml
import com.google.gson.*
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.*
import com.intellij.psi.util.*
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile

/** Project roots, source files and declarations cached until PSI, settings or VFS changes. */
class DbtWorkspace(
    val project: Project,
    val roots: List<DbtRoot>,
    val files: List<PsiFile>,
    val symbols: List<DbtSymbol>
) : com.intellij.openapi.util.UserDataHolderBase() {
    private val documentation = java.util.concurrent.ConcurrentHashMap<String, DbtResourceData>()
    /** Caches documentation by declaration identity for this workspace snapshot; a rebuilt workspace starts with an empty cache. */
    fun resourceData(symbol: DbtSymbol, read: () -> DbtResourceData): DbtResourceData =
        documentation.computeIfAbsent(symbol.id) { read() }

    private val byKindAndName by lazy { symbols.groupBy { it.kind to it.name } }
    private fun rootOf(file: VirtualFile): DbtRoot? =
        roots.filter { VfsUtilCore.isAncestor(it.directory, file, false) }.maxByOrNull { it.directory.path.length }

    fun root(file: PsiFile): DbtRoot? = file.virtualFile?.let(::rootOf)
    /** Uses explicit metadata ownership before source-file ancestry, with artifact-path ancestry as the final fallback. */
    fun owner(symbol: DbtSymbol): DbtRoot? =
        symbol.attributes["resource-root"]?.let { p -> roots.find { it.directory.path == p } } ?: root(symbol.file)
        ?: symbol.attributes["artifact_path"]?.let { LocalFileSystem.getInstance().findFileByPath(it) }?.let(::rootOf)

    fun rootFile(root: DbtRoot, name: String): PsiFile? =
        files.find { it.virtualFile.parent == root.directory && it.name == name }

    fun packageOwner(r: DbtRoot): DbtRoot? =
        roots.filter { p -> p != r && p.relative(r.directory)?.let { p.pathContains(p.packagePath, it) } == true }
            .maxByOrNull { it.directory.path.length }

    fun executionRoot(r: DbtRoot): DbtRoot = packageOwner(r)?.let(::executionRoot) ?: r
    private fun samePackageTree(from: DbtRoot, target: DbtRoot): Boolean =
        packageOwner(target) == (packageOwner(from) ?: from)

    private fun projectDependency(from: DbtRoot, namespace: String): Boolean =
        rootFile(from, "dependencies.yml")?.let(DbtYaml::mapping)
            ?.let { DbtYaml.items(it, "projects").any { p -> DbtYaml.string(p, "name") == namespace } } == true

    fun namespaceVisible(from: DbtRoot, target: DbtRoot, qualified: Boolean): Boolean =
        from == target || samePackageTree(from, target) || qualified && from.supports(6) && projectDependency(
            from,
            target.name
        )

    private fun visibleSymbol(symbol: DbtSymbol, from: DbtRoot, qualified: Boolean): Boolean {
        val public = symbol.attributes["access"] == "public"
        return if (symbol.metadata) owner(symbol) == from && (symbol.namespace == from.name || qualified && projectDependency(
            from,
            symbol.namespace
        ) && public)
        else root(symbol.file)?.let { target ->
            if (target == from) true else if (samePackageTree(
                    from,
                    target
                )
            ) symbol.attributes["access"] != "private" else qualified && from.supports(6) && projectDependency(
                from,
                target.name
            ) && public
        } == true
    }

    /**
     * Resolves visible, enabled declarations, preferring the current namespace for unqualified names.
     * Preserves ambiguity and removes property duplicates when a matching authored declaration is present.
     */
    fun declarations(kind: Set<String>, name: String, from: DbtRoot, namespace: String? = null): List<DbtSymbol> {
        ProgressManager.checkCanceled()
        val candidates = kind.flatMap { byKindAndName[it to name].orEmpty() }.filter { s ->
            ProgressManager.checkCanceled(); (namespace == null || namespace == s.namespace) && visibleSymbol(
            s,
            from,
            namespace != null
        )
        }

        fun usable(selected: List<DbtSymbol>): List<DbtSymbol> =
            selected.filter { from.supportsKind(it.kind) && (it.kind != "function" || functionSupported(it, from)) }
                .filter { s ->
                    s.isEnabled || s.kind == "source" && symbols.any { t ->
                        t.kind == "source_table" && t.owner == s.name && t.namespace == s.namespace && owner(
                            t
                        ) == owner(s) && t.isEnabled && s.mapping?.textRange?.contains(t.range) == true
                    }
                }.filterNot { s -> s.isProperty && selected.any { !it.isProperty && it.sameResource(s) } }

        val local = if (namespace == null) usable(candidates.filter { it.namespace == from.name }) else emptyList()
        return local.ifEmpty { usable(candidates) }
    }

    private fun functionSupported(symbol: DbtSymbol, from: DbtRoot): Boolean {
        val language = if (symbol.isCode) symbol.file.virtualFile.extension else symbol.attributes["language"]
            ?: symbol.attributes["node_language"]
        val adapter = from.adapter
        val languages = when (language) {
            null -> true
            "js", "javascript" -> from.supports(12) && (adapter == null || adapter in setOf("snowflake", "bigquery"))
            "py", "python" -> adapter == null || adapter in setOf("snowflake", "bigquery", "databricks")
            "sql" -> adapter == null || adapter in setOf(
                "snowflake",
                "bigquery",
                "databricks",
                "redshift",
                "postgres"
            ) && !(from.engine == "v2" && adapter == "postgres")

            else -> false
        }
        return languages && ("overload" !in symbol.attributes || from.supports(12) && (adapter == null || adapter == "snowflake" || adapter == "postgres" && (language == null || language == "sql")))
    }

    fun properties(symbol: DbtSymbol): List<DbtSymbol> =
        symbols.filter { it.isProperty && it.sameResource(symbol) && root(it.file) == root(symbol.file) }

    fun symbolAt(file: PsiFile, offset: Int): List<DbtSymbol> =
        symbols.filter { it.file == file && it.range.containsOffset(offset) }

    fun enclosing(kind: String, file: PsiFile, offset: Int): List<DbtSymbol> =
        symbols.filter { it.kind == kind && it.file == file && it.mapping?.textRange?.containsOffset(offset) == true }

    fun columns(relation: DbtSymbol): List<DbtSymbol> = symbols.filter {
        it.kind == "column" && it.namespace == relation.namespace && it.owner == relation.relationName && it.version == relation.version && owner(
            it
        ) == owner(relation)
    }

    fun relations(column: DbtSymbol): List<DbtSymbol> = symbols.filter {
        it.kind in DbtSymbol.RelationKinds && it.relationName == column.owner && it.namespace == column.namespace && it.version == column.version && owner(
            it
        ) == owner(column)
    }

    fun codeSymbols(file: PsiFile): List<DbtSymbol> = symbols.filter { it.file == file && it.isCode }
    fun model(file: PsiFile): DbtSymbol? = codeSymbols(file).find { it.kind == "model" }
    fun model(path: String): DbtSymbol? =
        symbols.find { it.isCode && it.kind == "model" && it.file.virtualFile.path == path }

    fun macros(call: DbtSyntax.Call, from: DbtRoot): List<DbtSymbol> =
        call.callee.let { (namespace, name) -> declarations(setOf("macro"), name, from, namespace) }

    fun definitions(call: DbtSyntax.Call, kinds: Set<String>, from: DbtRoot): List<DbtSymbol> =
        call.qualifiedName?.let { (namespace, name) -> declarations(kinds, name, from, namespace) }.orEmpty()

    /** Treats missing generation time, newer resource files or unsaved resource documents as stale metadata. */
    fun outdated(root: DbtRoot, generatedAt: Long?): Boolean = generatedAt == null || files.any { f ->
        this.root(f)
            ?.let { r -> (r == root || packageOwner(r) == root) && r.resource(f) } == true && (f.virtualFile.timeStamp > generatedAt || PsiDocumentManager.getInstance(
            project
        ).getDocument(f)?.let { FileDocumentManager.getInstance().isDocumentUnsaved(it) } == true)
    }

    companion object {
        const val ProjectFileName = "dbt_project.yml"
        private val FileIndex = Key.create<CachedValue<List<DbtSymbol>>>("dbt.file.declarations")

        private data class PropertyIdentity(
            val kind: String,
            val namespace: String,
            val root: DbtRoot?,
            val physicalName: String
        )

        fun get(project: Project): DbtWorkspace = CachedValuesManager.getManager(project).getCachedValue(project) {
            val workspace = build(project)
            val inputs = DbtDeclarationSource.EP.extensionList.flatMap { it.inputs(workspace) }
                .mapNotNull { DbtYaml.psiFile(project, it) }
            CachedValueProvider.Result.create(
                workspace,
                *(listOf<Any>(
                    PsiModificationTracker.MODIFICATION_COUNT,
                    VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
                    DbtProjectSettings.get(project)
                ) + inputs).toTypedArray()
            )
        }

        private fun child(project: Project, directory: VirtualFile, name: String): PsiFile? =
            DbtYaml.psiFile(project, directory.findChild(name))

        private fun loadRoot(project: Project, directory: VirtualFile): DbtRoot? =
            child(project, directory, ProjectFileName)?.let(DbtYaml::mapping)?.let { DbtRoot(directory, it) }

        fun rootForFile(project: Project, file: VirtualFile?): DbtRoot? =
            if (file == null || project.isDisposed || !file.isValid) null else generateSequence(file.parent) { it.parent }.takeWhile { it.isValid }
                .firstNotNullOfOrNull { loadRoot(project, it) }

        fun declarationsInFile(file: PsiFile): List<DbtSymbol> = file.virtualFile?.let { vf ->
            val root = generateSequence(vf.parent) { it.parent }.firstNotNullOfOrNull { loadRoot(file.project, it) }
            root?.takeUnless { it.excluded(vf) }?.let { fileSymbols(file.project, file, it.directory) }
        }.orEmpty()

        private fun discoverRoots(project: Project): List<DbtRoot> {
            val bases = (listOfNotNull(project.basePath?.let {
                LocalFileSystem.getInstance().findFileByPath(it)
            }) + ProjectRootManager.getInstance(project).contentRoots).distinct()
            val enclosing = mutableMapOf<VirtualFile, DbtRoot?>()
            fun enclosingRoot(directory: VirtualFile?): DbtRoot? {
                if (directory == null) return null
                if (directory in enclosing) return enclosing[directory]
                val root = loadRoot(project, directory) ?: enclosingRoot(directory.parent)
                enclosing[directory] = root
                return root
            }

            fun discoverable(file: VirtualFile): Boolean = file.name !in setOf(
                ".git",
                ".idea",
                ".venv",
                "venv",
                "target",
                "logs"
            ) && (enclosingRoot(file.parent)?.let { r ->
                !r.excluded(file) || r.relative(file)?.let { r.pathContains(r.packagePath, it) } == true
            } ?: true)

            val configs = mutableListOf<VirtualFile>()
            bases.forEach { b ->
                VfsUtilCore.iterateChildrenRecursively(
                    b,
                    { discoverable(it) }) { f -> ProgressManager.checkCanceled(); if (f.name == ProjectFileName) configs += f; true }
            }
            return configs.distinct().mapNotNull { loadRoot(project, it.parent) }
        }

        private fun projectFiles(project: Project, roots: List<DbtRoot>): List<PsiFile> {
            val extensions = setOf("sql", "jinja", "jinja2", "py", "yml", "yaml", "md", "csv", "json", "js")
            val result = mutableListOf<VirtualFile>()
            roots.forEach { r ->
                VfsUtilCore.iterateChildrenRecursively(
                    r.directory,
                    { f -> r.traversable(f) && !(f.isDirectory && f != r.directory && f.findChild(ProjectFileName) != null) }) { f ->
                    ProgressManager.checkCanceled(); if (!f.isDirectory && f.extension.orEmpty() in extensions) result += f; true
                }
            }
            return result.distinct().mapNotNull { DbtYaml.psiFile(project, it) }
        }

        private fun fileSymbols(project: Project, file: PsiFile, directory: VirtualFile): List<DbtSymbol> =
            CachedValuesManager.getManager(project).getCachedValue(file, FileIndex, {
                val dependencies = listOf<Any>(
                    file,
                    VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
                    DbtProjectSettings.get(project)
                ) + listOfNotNull(child(project, directory, ProjectFileName))
                CachedValueProvider.Result.create(loadRoot(project, directory)?.let { DbtDeclarations.inFile(file, it) }
                    .orEmpty(), *dependencies.toTypedArray())
            }, false)

        private fun build(project: Project): DbtWorkspace {
            val roots = discoverRoots(project);
            val files = projectFiles(project, roots)
            val initial = DbtWorkspace(project, roots, files, emptyList())
            val symbols =
                files.flatMap { file -> initial.root(file)?.let { fileSymbols(project, file, it.directory) }.orEmpty() }
            val properties = symbols.filter { it.isProperty }.groupBy { p ->
                PropertyIdentity(
                    p.kind,
                    p.namespace,
                    initial.root(p.file),
                    p.attributes["defined_in"] ?: (p.name + p.version?.let { "_v$it" }.orEmpty())
                )
            }
            val remapped = symbols.map { s ->
                if (s.isCode) {
                    val matches =
                        properties[PropertyIdentity(s.kind, s.namespace, initial.root(s.file), s.name)].orEmpty()
                    val resolved = if (matches.size != 1) s else s.copy(
                        name = matches.first().name,
                        version = matches.first().version,
                        attributes = (matches.first().attributes - "property") + s.attributes,
                        mapping = matches.first().mapping
                    )
                    resolved.copy(
                        attributes = DbtConfiguration.inherited(
                            initial,
                            resolved.file,
                            resolved.name
                        ) + resolved.attributes
                    )
                } else if (s.isProperty || s.kind == "source_table") s.copy(
                    attributes = DbtConfiguration.propertyAttributes(
                        initial,
                        s
                    ) + s.attributes
                ) else s
            }
            return DbtDeclarationSource.EP.extensionList.fold(
                DbtWorkspace(
                    project,
                    roots,
                    files,
                    remapped
                )
            ) { w, source -> DbtWorkspace(project, roots, files, w.symbols + source.declarations(w)) }
        }
    }
}

/**
 * Extends workspace discovery in registration order; later sources see declarations supplied by earlier sources.
 * Return external artifact files from [inputs] so PSI changes invalidate the workspace. [documentation] enriches
 * source data without replacing populated fields; [canReportMissing] can suppress claims based on unusable metadata.
 */
interface DbtDeclarationSource {
    fun declarations(workspace: DbtWorkspace): List<DbtSymbol>
    fun inputs(workspace: DbtWorkspace): List<VirtualFile> = emptyList()
    fun documentation(workspace: DbtWorkspace, symbol: DbtSymbol): DbtResourceData? = null
    fun canReportMissing(workspace: DbtWorkspace, root: DbtRoot): Boolean = true
    fun artifactStatus(root: DbtRoot): List<String> = emptyList()

    companion object {
        val EP: ExtensionPointName<DbtDeclarationSource> =
            ExtensionPointName.create("com.github.vbmacher.intellij-dbt.declarationSource")
    }
}

/** Selects read limits and validation strictness; baseline comparison additionally validates identities and dependency shapes. */
sealed class DbtManifestPolicy(val maxBytes: Int) {
    data object Navigation : DbtManifestPolicy(Int.MAX_VALUE)
    data object TestDiscovery : DbtManifestPolicy(25000000)
    data object Baseline : DbtManifestPolicy(50000000)
}

object DbtJson {
    fun child(o: JsonObject, key: String): JsonObject? = o.get(key)?.takeIf { it.isJsonObject }?.asJsonObject
    fun text(o: JsonObject, key: String): String? =
        o.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    fun string(o: JsonObject, key: String): String = o.get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
    fun obj(vararg values: Pair<String, Any?>): JsonObject = JsonObject().also { o ->
        values.forEach { (k, v) ->
            when (v) {
                is JsonElement -> o.add(k, v)
                is String -> o.addProperty(k, v)
                is Boolean -> o.addProperty(k, v)
                is Number -> o.addProperty(k, v)
                null -> o.add(k, JsonNull.INSTANCE)
                else -> throw IllegalArgumentException("Unsupported JSON value for $k: ${v.javaClass}")
            }
        }
    }

    fun strings(values: Iterable<String>): JsonArray = JsonArray().also { a -> values.forEach(a::add) }
    fun array(values: Iterable<JsonElement>): JsonArray = JsonArray().also { a -> values.forEach(a::add) }
}

data class DbtManifestResource(val section: String, val id: String, val data: JsonObject)
data class DbtManifest(val schemaVersion: Int, val projectName: String?, val resources: List<DbtManifestResource>) {
    fun resourcesIn(vararg sections: String): List<DbtManifestResource> = resources.filter { it.section in sections }
}

/** Reads supported manifests as explicit success or failure; callers choose a policy for navigation, discovery or baseline comparison. */
interface DbtManifestReader {
    fun parse(text: String, policy: DbtManifestPolicy): Result<DbtManifest>
    fun read(root: DbtRoot, path: String, policy: DbtManifestPolicy): Result<DbtManifest>

    companion object {
        fun get(project: Project): DbtManifestReader = project.getService(DbtManifestReader::class.java)
    }
}
