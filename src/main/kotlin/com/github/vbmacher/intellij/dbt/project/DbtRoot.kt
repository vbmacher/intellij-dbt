package com.github.vbmacher.intellij.dbt.project

import com.github.vbmacher.intellij.dbt.syntax.DbtYaml
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.nio.file.Paths
import java.math.BigInteger

/** A dbt project directory with its dbt_project.yml and per-root IntelliJ settings. */
data class DbtRoot(val directory: VirtualFile, val config: YAMLMapping) {
    val name = DbtYaml.string(config, "name") ?: directory.name
    private val base: Path get() = Paths.get(directory.path)
    fun psiFile(path: String): PsiFile? = psiFile(directory, config.project, path)
    fun yaml(path: String): YAMLMapping? = psiFile(path)?.let(DbtYaml::mapping)
    private var executionOptions: DbtRunOptions? = null
    val runOptions: DbtRunOptions
        get() = executionOptions ?: DbtProjectSettings.get(config.project).options(directory.path)

    /** Creates an execution view with captured options; project identity remains the directory and configuration PSI. */
    fun withRunOptions(options: DbtRunOptions): DbtRoot = copy().also { it.executionOptions = options }
    /** Base for command execution and relative artifact paths; may differ from the discovered project directory. */
    val executionDirectory: Path
        get() = (runOptions.projectRoot.takeIf { it.isNotEmpty() }?.let(base::resolve) ?: base).normalize()
    val artifactDirectory: Path
        get() {
            val path = Paths.get(runOptions.artifactDirectory)
            return (if (path.isAbsolute) path else executionDirectory.resolve(path)).normalize()
        }

    fun setting(key: String): String? = when (key) {
        "manifest" -> (runOptions.manifestPath.takeIf { it.isNotEmpty() }
            ?.let { executionDirectory.resolve(it).normalize() }
            ?: artifactDirectory.resolve("manifest.json")).toString()

        "catalog" -> runOptions.catalogPath.takeIf { it.isNotEmpty() } ?: artifactDirectory.resolve("catalog.json")
            .toString()

        "engine" -> runOptions.engine
        "dbt-version" -> runOptions.dbtVersion.takeIf { it.isNotEmpty() }
        "profiles-file" -> runOptions.profilesFile.takeIf { it.isNotEmpty() }
        "adapter" -> runOptions.adapter.takeIf { it.isNotEmpty() }
        "state-manifest" -> runOptions.stateManifestPath.takeIf { it.isNotEmpty() }
        "info-schema-dir" -> runOptions.infoSchemaDirectory.takeIf { it.isNotEmpty() }
        "target" -> runOptions.target.takeIf { it.isNotEmpty() }
        else -> null
    }

    val selectedTarget: String? get() = setting("target")
    val profilesFile: String
        get() = setting("profiles-file") ?: DbtExecutionContext.profiles(this).directory.resolve("profiles.yml")
            .toString()
    val envFiles: List<PsiFile> get() = DbtEnvironment.paths(runOptions).mapNotNull(::psiFile)
    val profile: YAMLMapping?
        get() = DbtYaml.string(config, "profile")?.let { p -> yaml(profilesFile)?.let { DbtYaml.map(it, p) } }

    fun target(key: String): List<YAMLKeyValue> = profile?.let { p ->
        if (key == "name") listOfNotNull(
            if (runOptions.target.isNotEmpty()) DbtYaml.map(p, "outputs")
                ?.let { DbtYaml.keyValue(it, runOptions.target) } else DbtYaml.keyValue(p, "target"))
        else listOfNotNull((selectedTarget ?: DbtYaml.string(p, "target"))?.takeUnless { it.contains("{{") }
            ?.let { t -> DbtYaml.map(p, "outputs")?.let { DbtYaml.map(it, t) }?.let { DbtYaml.keyValue(it, key) } })
    }.orEmpty()

    val adapter: String? get() = setting("adapter") ?: (target("type").firstOrNull()?.value as? YAMLScalar)?.textValue
    val adapterFallbacks: List<String> get() = listOfNotNull(adapter) + (if (adapter == "redshift") listOf("postgres") else emptyList()) + "default"
    val engine: String = setting("engine") ?: "core"
    val version: String = configuredVersion(config, setting("dbt-version").orEmpty())
    /** Checks the configured Core version; an unknown Core version does not enable version-gated features. The `v2` engine enables them. */
    fun supports(minor: Int, patch: Int = 0): Boolean =
        engine == "v2" || engine == "core" && supportsVersion(version, minor, patch)

    fun supportsKind(kind: String): Boolean = when (kind) {
        "function" -> supports(11)
        "check" -> engine == "v2"
        "semantic_model", "metric" -> supports(6)
        "saved_query" -> supports(7)
        "unit_test" -> supports(8)
        else -> true
    }

    val pathKinds: List<Pair<String, String>> = listOf(
        "model" to "models",
        "seed" to "seeds",
        "snapshot" to "snapshots",
        "analysis" to "analyses",
        "macro" to "macros",
        "test" to "tests",
        "doc" to "docs",
        "function" to "functions",
        "check" to "checks"
    )
        .filter { supportsKind(it.first) }.flatMap { (kind, default) ->
            val key = mapOf("model" to "model-paths", "analysis" to "analysis-paths", "doc" to "docs-paths")[kind]
                ?: "$kind-paths"
            DbtYaml.strings(config, key, listOf(default)).map { kind to it }
        }
    val packagePath = DbtYaml.string(config, "packages-install-path") ?: "dbt_packages"
    private val ignores = ignorePatterns(psiFile(".dbtignore")?.text.orEmpty())
    private val configuredOutput = if (artifactDirectory.startsWith(base) && artifactDirectory != base) listOf(
        base.relativize(artifactDirectory).toString().replace('\\', '/')
    ) else emptyList()
    val exclusions = listOf(
        ".git",
        ".idea",
        ".venv",
        "venv",
        "logs",
        packagePath,
        DbtYaml.string(config, "target-path") ?: "target"
    ) + configuredOutput

    fun relative(file: VirtualFile): String? = VfsUtilCore.getRelativePath(file, directory, '/')
    fun isFile(file: PsiFile, path: String): Boolean =
        if (Paths.get(path).isAbsolute) Paths.get(file.virtualFile.path).normalize() == Paths.get(path)
            .normalize() else relative(file.virtualFile) == path

    fun pathContains(path: String, relative: String): Boolean =
        path == "." || relative == path || relative.startsWith(path.removeSuffix("/") + "/")

    fun folders(path: String, relative: String): List<String>? =
        if (pathContains(path, relative)) relative.removePrefix(path.removeSuffix("/") + "/").split('/')
            .dropLast(1) else null

    fun resourceFolders(file: VirtualFile): Pair<String, List<String>>? = relative(file)?.let { r ->
        pathKinds.firstNotNullOfOrNull { (kind, path) ->
            folders(
                path,
                r
            )?.let { kind to it }
        }
    }

    fun excluded(file: VirtualFile): Boolean = relative(file)?.let { excludedPath(it, file.isDirectory) } == true
    fun excludedPath(r: String, directory: Boolean = false): Boolean = excludedPath(r, directory, ignores)
    fun excludedPathWith(ignoreText: String): (String) -> Boolean {
        val parsed = ignorePatterns(ignoreText)
        return { excludedPath(it, false, parsed) }
    }

    private fun excludedPath(
        r: String,
        directory: Boolean,
        ignores: List<Triple<Boolean, Boolean, List<PathMatcher>>>
    ): Boolean {
        val parts = r.split('/')
        val paths =
            parts.indices.map { Paths.get(parts.take(it + 1).joinToString("/")) to (it < parts.size - 1 || directory) }
        return exclusions.any {
            pathContains(
                it,
                r
            )
        } || ignores.fold(false) { ignored, (negated, directoriesOnly, matchers) ->
            if (paths.any { (path, dir) -> (!directoriesOnly || dir) && matchers.any { it.matches(path) } }) !negated else ignored
        }
    }

    /** Keeps ignored directories searchable when a later negation may reinclude descendants; fixed exclusions remain pruned. */
    fun traversable(file: VirtualFile): Boolean =
        !excluded(file) || file.isDirectory && ignores.any { it.first } && relative(file)?.let { r ->
            exclusions.none {
                pathContains(
                    it,
                    r
                )
            }
        } == true

    fun kind(file: VirtualFile): String? = resourceFolders(file)?.first
    fun resource(file: PsiFile): Boolean = !excluded(file.virtualFile) && kind(file.virtualFile) != null

    companion object {
        fun psiFile(directory: VirtualFile, project: com.intellij.openapi.project.Project, path: String): PsiFile? {
            val base = Paths.get(directory.path)
            val requested = Paths.get(path).normalize()
            val relative =
                if (requested.isAbsolute && requested.startsWith(base)) base.relativize(requested).toString() else path
            val virtual =
                directory.findFileByRelativePath(relative) ?: if (requested.isAbsolute) LocalFileSystem.getInstance()
                    .findFileByPath(path) else null
            return DbtYaml.psiFile(project, virtual)
        }

        fun configuredVersion(config: YAMLMapping, overrideVersion: String): String {
            val requirements = DbtYaml.strings(config, "require-dbt-version")
            return if (overrideVersion.isNotEmpty()) overrideVersion else if (requirements.isEmpty()) "1.10" else requiredVersion(
                requirements
            ) ?: "unknown"
        }

        fun supportsVersion(version: String, minor: Int, patch: Int = 0): Boolean {
            val parts = version.split('.')
            val numbers = parts.take(2).mapNotNull { it.toIntOrNull() }
            return numbers.size == 2 && (numbers[0] >= 2 || numbers[0] == 1 && (numbers[1] > minor || numbers[1] == minor && (parts.getOrNull(
                2
            )?.toIntOrNull()?.let { it >= patch } ?: true)))
        }

        private val VersionConstraint =
            Regex("""(>=|<=|>|<|=)?([0-9]+)\.([0-9]+)(?:\.([0-9]+))?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?""")

        private fun requiredVersion(requirements: List<String>): String? {
            val parsed = requirements.flatMap { it.split(',') }.map { VersionConstraint.matchEntire(it.trim()) }
            if (parsed.isEmpty() || parsed.any { it == null }) return null
            val constraints = parsed.filterNotNull().map { m ->
                m.groupValues[1].ifEmpty { "=" } to listOf(
                    m.groupValues[2].toBigInteger(),
                    m.groupValues[3].toBigInteger(),
                    m.groupValues[4].ifEmpty { "0" }.toBigInteger()
                )
            }
            val order = Comparator<List<BigInteger>> { a, b ->
                a.indices.firstNotNullOfOrNull {
                    a[it].compareTo(b[it]).takeIf { n -> n != 0 }
                } ?: 0
            }
            val lowest = constraints.mapNotNull { (op, v) ->
                when (op) {
                    ">" -> listOf(v[0], v[1], v[2] + BigInteger.ONE); ">=", "=" -> v; else -> null
                }
            }.maxWithOrNull(order) ?: List(3) { BigInteger.ZERO }
            val compatible = constraints.all { (op, bound) ->
                val c = order.compare(lowest, bound); when (op) {
                ">" -> c > 0; ">=" -> c >= 0; "<" -> c < 0; "<=" -> c <= 0; else -> c == 0
            }
            }
            return lowest.joinToString(".").takeIf { compatible }
        }

        fun ignorePatterns(text: String): List<Triple<Boolean, Boolean, List<PathMatcher>>> =
            text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith('#') }.map { pattern ->
                val negated = pattern.startsWith('!');
                val raw = if (negated) pattern.drop(1) else pattern
                val relative = raw.removePrefix("/").removeSuffix("/")
                val patterns =
                    listOf(relative) + (if (!raw.startsWith('/') && !relative.contains('/')) listOf("**/$relative") else emptyList()) + (if (relative.startsWith(
                            "**/"
                        )
                    ) listOf(relative.removePrefix("**/")) else emptyList())
                Triple(
                    negated,
                    raw.endsWith('/'),
                    patterns.mapNotNull {
                        runCatching {
                            FileSystems.getDefault().getPathMatcher("glob:$it")
                        }.getOrNull()
                    })
            }.toList()
    }
}
