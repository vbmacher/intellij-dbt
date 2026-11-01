package com.github.vbmacher.intellij.dbt.project

import com.github.vbmacher.intellij.dbt.syntax.DbtYaml
import com.intellij.openapi.components.*
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.openapi.util.TextRange
import com.intellij.util.EnvironmentUtil
import com.intellij.util.messages.Topic
import com.intellij.util.xmlb.XmlSerializerUtil
import com.intellij.testFramework.LightVirtualFile
import com.intellij.psi.PsiFile
import org.jetbrains.yaml.psi.YAMLMapping
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** Persisted execution settings for one discovered project root. Use [copy] to capture options for an operation. */
class DbtRunOptions {
    var executable: String = "dbt"
    var executableArguments: String = ""
    var environmentFiles: String = ""
    var loadEnvironmentFiles: Boolean = true
    var engine: String = "core"
    var dbtVersion: String = ""
    var profilesFile: String = ""
    var adapter: String = ""
    var variables: String = ""
    var catalogPath: String = ""
    var stateManifestPath: String = ""
    var infoSchemaDirectory: String = ""
    var projectRoot: String = ""
    var profilesDirectory: String = ""
    var artifactDirectory: String = "target"
    var manifestPath: String = ""
    var target: String = ""
    var previewLimit: Int = 10
    var colors: Boolean = true
    var notifications: Boolean = true
    var selection: String = ""
    var exclude: String = ""
    var stateDirectory: String = ""
    var defer: Boolean = false
    var favorState: Boolean = false
    var formatter: String = "sqlfmt"
    var formatterExecutable: String = ""
    var formatOnSave: Boolean = false
    var environmentVariables: MutableMap<String, String> = java.util.TreeMap()
    var builtinSources: MutableMap<String, String> = java.util.TreeMap()

    @get:com.intellij.util.xmlb.annotations.Transient
    val values: List<Any>
        get() = listOf(
            executable,
            executableArguments,
            environmentFiles,
            projectRoot,
            profilesDirectory,
            artifactDirectory,
            manifestPath,
            target,
            previewLimit,
            colors,
            notifications,
            selection,
            exclude,
            stateDirectory,
            defer,
            favorState,
            formatter,
            formatterExecutable,
            formatOnSave,
            loadEnvironmentFiles,
            engine,
            dbtVersion,
            profilesFile,
            adapter,
            variables,
            environmentVariables,
            builtinSources,
            catalogPath,
            stateManifestPath,
            infoSchemaDirectory
        )

    fun copy(): DbtRunOptions = XmlSerializerUtil.createCopy(this)
}

/** Project-wide settings with per-root execution options and shared lineage display preferences. */
class DbtSettingsState {
    var projects: MutableMap<String, DbtRunOptions> = java.util.TreeMap()
    var upstreamDepth = 5
    var downstreamDepth = 5
    var direction = "LR"
    var pinnedFocus = ""

    @get:com.intellij.util.xmlb.annotations.Transient
    val upstream: Int get() = upstreamDepth.coerceIn(1, 20)

    @get:com.intellij.util.xmlb.annotations.Transient
    val downstream: Int get() = downstreamDepth.coerceIn(1, 20)
}

@Service(Service.Level.PROJECT)
@State(name = "DbtWorkspaceSettings", storages = [Storage("dbt-workspace.xml")])
class DbtProjectSettings(private val project: Project) : SimpleModificationTracker(),
    PersistentStateComponent<DbtSettingsState> {
    private var currentState = DbtSettingsState()
    override fun getState(): DbtSettingsState = currentState
    override fun loadState(value: DbtSettingsState) {
        currentState = value; changed()
    }

    /** Call after mutating settings to invalidate dependent caches and notify settings listeners. */
    fun changed() {
        incModificationCount(); project.messageBus.syncPublisher(Changed).settingsChanged()
    }

    @Synchronized
    fun options(root: String): DbtRunOptions = currentState.projects.getOrPut(root) { DbtRunOptions() }

    companion object {
        val Changed: Topic<DbtSettingsListener> = Topic.create("dbt settings changed", DbtSettingsListener::class.java)
        fun get(project: Project): DbtProjectSettings = project.getService(DbtProjectSettings::class.java)
    }
}

interface DbtSettingsListener {
    fun settingsChanged()
}

/** Reads assignment-style environment files without shell evaluation; malformed or unsupported assignments are ignored. */
object DbtEnvironment {
    private val Assignment = Regex("""\s*(?:export\s+)?([A-Za-z_][A-Za-z_0-9]*)\s*=\s*(.*)""")
    private val DoubleQuoted = Regex("\"((?:\\\\.|[^\"\\\\])*)\"\\s*(?:#.*)?")
    private val SingleQuoted = Regex("""'([^']*)'\s*(?:#.*)?""")
    private val Escape = Regex("""\\([nrt"\\])""")
    fun parse(text: String): Map<String, String> = text.removePrefix("\ufeff").lineSequence().mapNotNull { line ->
        Assignment.matchEntire(line)?.let { m ->
            val raw = m.groupValues[2].trim()
            val double = DoubleQuoted.matchEntire(raw)
            val single = SingleQuoted.matchEntire(raw)
            val value = when {
                double != null -> Escape.replace(double.groupValues[1]) {
                    when (val ch = it.groupValues[1]) {
                        "n" -> "\n"; "r" -> "\r"; "t" -> "\t"; else -> ch
                    }
                }

                single != null -> single.groupValues[1]
                !raw.startsWith('"') && !raw.startsWith('\'') -> if (raw.startsWith('#')) "" else raw.replaceFirst(
                    Regex(
                        "\\s+#.*$"
                    ), ""
                ).trim()

                else -> null
            }
            value?.let { m.groupValues[1] to it }
        }
    }.toMap()

    fun paths(options: DbtRunOptions): List<String> =
        if (!options.loadEnvironmentFiles) emptyList() else if (options.environmentFiles.trim()
                .isNotEmpty()
        ) options.environmentFiles.split(';').map { it.trim() }.filter { it.isNotEmpty() } else listOf(".env")

    fun values(r: DbtRoot): Map<String, String> = values(r.runOptions, r::psiFile)
    /** Merges files in configured order, then applies explicit environment settings as the final overrides. */
    fun values(options: DbtRunOptions, readFile: (String) -> PsiFile?): Map<String, String> =
        paths(options).mapNotNull(readFile)
            .fold(emptyMap<String, String>()) { values, file -> values + parse(file.text) } + options.environmentVariables

    private fun files(r: DbtRoot, namePattern: String): List<DbtSymbol> = r.envFiles.flatMap { file ->
        Regex("(?m)^\\s*(?:export\\s+)?($namePattern)\\s*=").findAll(file.text).map { m ->
            DbtSymbol(
                "environment",
                m.groupValues[1],
                r.name,
                file,
                TextRange(m.groups[1]!!.range.first, m.groups[1]!!.range.last + 1)
            )
        }.toList()
    }

    fun resolve(w: DbtWorkspace, r: DbtRoot, name: String): List<DbtSymbol> {
        val found =
            w.symbols.filter { it.kind == "environment" && it.name == name && "settings" in it.attributes && w.owner(it) == r }
        return found.ifEmpty { files(r, java.util.regex.Pattern.quote(name)) }
    }

    fun declarations(r: DbtRoot): List<DbtSymbol> =
        DbtSettingsDeclarations.names(r, "environment", r.runOptions.environmentVariables.keys.toList()) + files(
            r,
            "[A-Za-z_][A-Za-z_0-9]*"
        )
}

/** Parses the JSON object supplied as variable overrides; blank input is an empty object and invalid input is a failure. */
object DbtVariableOverrides {
    fun parse(text: String): Result<JsonObject> {
        val message = "Variables must be a JSON object, for example {\"start_date\": \"2026-03-01\"}."
        if (text.trim().isEmpty()) return Result.success(JsonObject())
        val value = try {
            JsonParser.parseString(text)
        } catch (_: Exception) {
            return Result.failure(IllegalArgumentException(message))
        }
        return if (value.isJsonObject) Result.success(value.asJsonObject) else Result.failure(
            IllegalArgumentException(
                message
            )
        )
    }
}

/** Creates read-only navigation targets containing setting names; secret values are excluded from generated PSI. */
object DbtSettingsDeclarations {
    fun names(root: DbtRoot, kind: String, names: List<String>): List<DbtSymbol> {
        if (names.isEmpty()) return emptyList()
        val header = "Configure these values in Settings → dbt Workspace.\n\n"
        val sorted = names.sorted()
        val virtual = LightVirtualFile(
            "dbt.$kind.${DbtHash.sha256(root.directory.path).take(12)}.settings.txt",
            PlainTextFileType.INSTANCE,
            header + sorted.joinToString("\n")
        )
        virtual.isWritable = false
        val file = DbtYaml.psiFile(root.config.project, virtual) ?: return emptyList()
        var offset = header.length
        return sorted.map { name ->
            val range = TextRange(offset, offset + name.length); offset += name.length + 1
            DbtSymbol(
                kind,
                name,
                root.name,
                file,
                range,
                metadata = true,
                attributes = mapOf("resource-root" to root.directory.path, "settings" to "true")
            )
        }
    }
}

class DbtEnvironmentSource : DbtDeclarationSource {
    override fun declarations(workspace: DbtWorkspace): List<DbtSymbol> = workspace.roots.flatMap { root ->
        DbtEnvironment.declarations(root) + (DbtVariableOverrides.parse(root.runOptions.variables).getOrNull()
            ?.let { DbtSettingsDeclarations.names(root, "var", it.keySet().toList()) } ?: emptyList())
    }
}

data class DbtProfileContext(val directory: Path, val defaultTarget: String?)
/** Resolves command inputs from captured root options, environment files and local profiles without invoking dbt. */
object DbtExecutionContext {
    fun profiles(root: DbtRoot): DbtProfileContext =
        profiles(root.executionDirectory, root.runOptions, root.config, root::psiFile)

    /** Uses explicit profiles directory, then `DBT_PROFILES_DIR`, local `profiles.yml`, then `~/.dbt`; templated targets stay unknown. */
    fun profiles(
        executionDirectory: Path,
        options: DbtRunOptions,
        config: YAMLMapping,
        readFile: (String) -> PsiFile?
    ): DbtProfileContext {
        fun yaml(path: Path): YAMLMapping? = readFile(path.toString())?.let(DbtYaml::mapping)
        val environment = System.getenv() + DbtEnvironment.values(options, readFile)
        val configured = options.profilesDirectory.takeIf { it.isNotEmpty() }
            ?: environment["DBT_PROFILES_DIR"]?.takeIf { it.isNotEmpty() }
        val directory = configured?.let(Paths::get)?.let { if (it.isAbsolute) it else executionDirectory.resolve(it) }
            ?: if (readFile(
                    executionDirectory.resolve("profiles.yml").toString()
                ) != null
            ) executionDirectory else Paths.get(System.getProperty("user.home"), ".dbt")
        val executionConfig = yaml(executionDirectory.resolve("dbt_project.yml")) ?: config
        val profile = DbtYaml.string(executionConfig, "profile")
            ?.let { name -> yaml(directory.resolve("profiles.yml"))?.let { DbtYaml.map(it, name) } }
        return DbtProfileContext(
            directory,
            profile?.let { DbtYaml.string(it, "target") }?.takeUnless { it.contains("{{") })
    }

    fun executable(root: DbtRoot): String = executable(root.executionDirectory, root.runOptions.executable)
    fun executable(directory: Path, value: String): String {
        val configured = value.trim()
        return if (configured == "dbt") virtualEnvironmentExecutable(directory)?.toString()
            ?: "dbt" else if (configured.contains('/') || configured.contains('\\')) directory.resolve(configured)
            .normalize().toString() else configured
    }

    private fun virtualEnvironmentExecutable(directory: Path): Path? =
        listOf(".venv/bin/dbt", "venv/bin/dbt", ".venv/Scripts/dbt.exe", "venv/Scripts/dbt.exe").map(directory::resolve)
            .find { Files.isRegularFile(it) }

    fun environment(root: DbtRoot): Map<String, String> {
        val configured = DbtEnvironment.values(root)
        return configured + (virtualEnvironmentExecutable(root.executionDirectory)?.let {
            mapOf(
                "PATH" to (it.parent.toString() + java.io.File.pathSeparator + (configured["PATH"]
                    ?: EnvironmentUtil.getValue("PATH").orEmpty()))
            )
        } ?: emptyMap())
    }

    fun activeTarget(root: DbtRoot): String? = root.selectedTarget ?: profiles(root).defaultTarget
}

object DbtHash {
    fun sha256(bytes: ByteArray): String =
        java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes))

    fun sha256(text: String): String = sha256(text.toByteArray(StandardCharsets.UTF_8))
}

/**
 * Binds a generated manifest to its execution directory, target and configuration digest.
 * A missing, changed or unreadable marker fails the match; input values are stored only as a digest.
 */
object DbtArtifactContext {
    private const val Marker = ".dbt-workspace-context"
    private fun textDigest(text: String): String = DbtHash.sha256(text.removePrefix("\ufeff").replace("\r\n", "\n"))
    private fun digest(path: Path): String = textDigest(Files.readString(path, StandardCharsets.UTF_8))
    fun fingerprint(directory: String): String? =
        runCatching { digest(Paths.get(directory).resolve("manifest.json")) }.getOrNull()

    fun identity(root: DbtRoot): List<String> {
        val directory = root.executionDirectory
        val profiles = DbtExecutionContext.profiles(root).directory.normalize()
        val inputs = listOf(
            profiles.resolve("profiles.yml").toString(),
            directory.resolve("dbt_project.yml").toString(),
            directory.resolve("vars.yml").toString()
        )
        val state = root.runOptions.stateDirectory.trim()
        val baseline = if (state.isEmpty()) emptyList() else {
            val path = directory.resolve(state).resolve("manifest.json").normalize().toString()
            listOf(path, root.psiFile(path)?.modificationStamp?.toString() ?: "unavailable")
        }
        val configuration = inputs.flatMap {
            listOf(
                it,
                root.psiFile(it)?.text.orEmpty()
            )
        } + root.envFiles.flatMap { listOf(it.virtualFile.path, it.text) } + listOf(
            root.runOptions.executable,
            root.runOptions.executableArguments,
            root.runOptions.variables,
            root.runOptions.environmentVariables.toString(),
            state,
            root.runOptions.defer.toString(),
            root.runOptions.favorState.toString()
        ) + baseline
        return listOf(
            directory.toString(),
            DbtExecutionContext.activeTarget(root).orEmpty(),
            textDigest(configuration.joinToString("\u0000"))
        )
    }

    /** Writes the captured context and manifest digest only when a manifest exists; I/O failures propagate to the caller. */
    fun record(directory: String, context: List<String>) {
        val path = Paths.get(directory);
        val manifest = path.resolve("manifest.json")
        if (Files.isRegularFile(manifest)) Files.write(
            path.resolve(Marker),
            context + digest(manifest),
            StandardCharsets.UTF_8
        )
    }

    fun matches(root: DbtRoot): Boolean {
        val path = root.setting("manifest")?.let(Paths::get)
            ?.let { if (it.isAbsolute) it else Paths.get(root.directory.path).resolve(it) } ?: return false
        return runCatching {
            val marker = Files.readAllLines(path.parent.resolve(Marker), StandardCharsets.UTF_8)
            marker.lastOrNull() == root.psiFile(path.toString())?.let { textDigest(it.text) }
                .orEmpty() && marker == identity(root) + digest(path)
        }.getOrDefault(false)
    }
}
