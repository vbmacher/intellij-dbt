package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project.*
import com.github.vbmacher.intellij.dbt.project.DbtJson.string
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant

internal data class DbtExecutionResult(
    val id: String, val status: String, val message: String, val failures: Long = 0L,
    val relation: String = "", val duration: Long = 0L
) {
    val failed: Boolean get() = status in setOf("failed", "error")
    val test: Boolean get() = id.startsWith("test.") || id.startsWith("unit_test.")
}

/** One execution context and invocation, with explicit partial/stale/provenance problems alongside available results. */
internal data class DbtExecutionRun(
    val context: List<String>,
    val target: String,
    val invocation: String,
    val timestamp: String,
    val artifact: String,
    val results: List<DbtExecutionResult>,
    val problem: String = "",
    val active: Boolean = false,
    val root: String = ""
)

/**
 * Separates execution evidence from resource declarations and serializes begin/stream/finish across process threads.
 * Streamed records provide progress; only changed, matching artifacts can receive persisted context/digest provenance.
 * Reloaded evidence is checked again against that provenance and current source freshness.
 */
@Service(Service.Level.PROJECT)
class DbtExecutionEvidence(private val project: Project) {
    @Volatile
    private var current: DbtExecutionRun? = null
    private var before = emptyMap<String, String?>()
    private var pending = ""
    private var finishedFingerprint: String? = null
    private var finishedPath = ""
    private val names = listOf("run_results.json", "sources.json")
    internal val active: DbtExecutionRun? get() = current?.takeIf { it.active }

    @Synchronized
    internal fun begin(command: DbtCommand) {
        current = if (command.action in setOf("Run", "Build", "Test", "Source Freshness")) {
            pending = ""
            before = names.associateWith { fingerprint(Paths.get(command.artifactDirectory).resolve(it)) }
            DbtExecutionRun(
                command.artifactContext, command.target, "", Instant.now().toString(), command.artifactDirectory,
                emptyList(), active = true, root = command.executionRoot
            )
        } else null
    }

    @Synchronized
    internal fun stream(text: String) {
        val run = current?.takeIf { it.active } ?: return
        pending = (pending + text).takeLast(1000000)
        val lines = pending.split('\n')
        pending = lines.lastOrNull().orEmpty()
        lines.dropLast(1).forEach { line ->
            runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull()?.let { top ->
                val info = obj(top, "info");
                val data = obj(top, "data");
                val node = obj(data, "node_info")
                val id = string(node, "unique_id");
                val status = normalize(string(node, "node_status"))
                val invocation = string(info, "invocation_id")
                val next = current ?: run
                current = next.copy(
                    invocation = invocation.ifEmpty { next.invocation }, results =
                    if (id.isEmpty()) next.results else next.results.filterNot { it.id == id } + DbtExecutionResult(
                        id,
                        status,
                        string(info, "msg")
                    ))
            }
        }
    }

    /**
     * Reconciles streamed progress with a newly written artifact for the captured target/invocation.
     * Missing, stale or canceled output remains partial; it cannot certify streamed success.
     */
    @Synchronized
    internal fun finish(command: DbtCommand, canceled: Boolean): DbtExecutionRun? {
        val run = current?.takeIf { it.active } ?: return null
        val name = if (command.action == "Source Freshness") "sources.json" else "run_results.json"
        val path = Paths.get(command.artifactDirectory).resolve(name)
        val parsed = if (fingerprint(path) != null && fingerprint(path) != before[name]) parse(
            path,
            command.artifactContext,
            command.target,
            run.invocation
        )
        else run.copy(
            results = run.results.map { r ->
                if (r.status in setOf(
                        "running",
                        "unknown"
                    )
                ) r.copy(status = if (canceled) "canceled" else "error") else r
            },
            problem = "No new matching result artifact was written"
        )
        val old = runCatching { Instant.parse(parsed.timestamp).toEpochMilli() }.getOrNull()
            ?.let { it < Instant.parse(run.timestamp).toEpochMilli() - 2000L } == true
        val checked =
            if (old) parsed.copy(results = emptyList(), problem = "Stale artifact predates this invocation") else parsed
        val remaining = run.results.filterNot { r -> checked.results.any { it.id == r.id } }.map { r ->
            if (r.status in setOf("running", "unknown")) r.copy(status = if (canceled) "canceled" else "error") else r
        }
        val outcome = checked.copy(
            results = checked.results + remaining, active = false, root = run.root,
            problem = if (canceled) "Run canceled; partial results" else checked.problem
        )
        finishedPath = path.toString(); finishedFingerprint = fingerprint(path)
        val recorded =
            if (!canceled && outcome.invocation.isNotEmpty() && parsed.results.isNotEmpty() && outcome.problem.isEmpty() &&
                runCatching { record(path, command.artifactContext) }.isFailure
            )
                outcome.copy(problem = "Cannot persist artifact provenance; results are available for this session") else outcome
        current = recorded
        return recorded
    }

    internal fun runs(root: DbtRoot): List<DbtExecutionRun> {
        val latest = current?.takeIf {
            it.context == DbtArtifactContext.identity(root) &&
                    (it.active || finishedPath.isNotEmpty() && fingerprint(Paths.get(finishedPath)) == finishedFingerprint)
        }
        return listOfNotNull(latest?.let { run ->
            if (DbtWorkspace.get(project)
                    .outdated(root, runCatching { Instant.parse(run.timestamp).toEpochMilli() }.getOrNull())
            )
                run.copy(problem = (run.problem + "\nStale result: project sources changed after this run").trim()) else run
        }) + names.map { read(root, it) }
    }

    internal fun overlay(
        symbol: DbtSymbol,
        runs: List<DbtExecutionRun>,
        definitions: List<DbtTestDefinition>
    ): JsonObject {
        val data = JsonObject()
        val direct =
            runs.firstNotNullOfOrNull { run -> run.results.find { it.id == symbol.uniqueId }?.let { run to it } }
        val tests = definitions.filter { symbol.uniqueId in it.dependencies }.map { it.id }.toSet()
        val failures = runs.flatMap { run -> run.results.map { run to it } }.distinctBy { it.second.id }
            .filter { (_, result) -> result.id in tests && result.failed }
        data.addProperty("status", direct?.second?.status ?: "unknown")
        data.addProperty("label", direct?.let { (run, r) ->
            (if (symbol.kind == "source_table") "Freshness " else "Build ") + r.status +
                    if (run.problem.lowercase().contains("stale")) " (stale)" else ""
        } ?: "Not run / result unavailable")
        data.addProperty("failingTests", failures.size)
        val related = (listOfNotNull(direct) + failures).distinctBy { (run, result) ->
            listOf(
                run.context,
                run.invocation,
                run.artifact,
                result.id
            )
        }
        data.addProperty("details", if (related.isNotEmpty()) related.joinToString("\n\n") { (run, result) ->
            "${result.id} · ${run.target} · ${run.timestamp} · ${run.invocation}\n${run.artifact}\n${result.message}\n${run.problem}"
        } else runs.map { it.problem }.filter { it.isNotEmpty() }.distinct().joinToString("\n"))
        return data
    }

    companion object {
        private val gson = Gson()
        fun obj(o: JsonObject, key: String): JsonObject = DbtJson.child(o, key) ?: JsonObject()
        fun objects(o: JsonObject, key: String): List<JsonObject> {
            val e = o[key] ?: return emptyList()
            return when {
                e.isJsonArray -> e.asJsonArray.toList()
                e.isJsonObject -> e.asJsonObject.entrySet().map { it.value }
                else -> emptyList()
            }.filter { it.isJsonObject }.map { it.asJsonObject }
        }

        private fun <T> bounded(path: Path, read: () -> T): T? =
            runCatching { require(Files.size(path) <= 25000000L) { "Artifact exceeds 25 MB" }; read() }.getOrNull()

        fun json(path: Path): JsonObject? =
            bounded(path) { JsonParser.parseString(Files.readString(path)).asJsonObject }

        fun fingerprint(path: Path): String? = bounded(path) { DbtHash.sha256(Files.readAllBytes(path)) }
        fun normalize(status: String): String = when (status.lowercase()) {
            "pass", "success" -> "passed"; "fail" -> "failed"; "warn" -> "warning"; "runtime error", "error" -> "error"
            "skipped", "skip" -> "skipped"; "running", "started", "compiling", "executing" -> "running"
            "cancelled", "canceled" -> "canceled"; else -> "unknown"
        }

        /** Parses supported result/freshness schemas; this alone does not establish file/context provenance. */
        internal fun parse(
            path: Path,
            context: List<String>,
            target: String,
            expectedInvocation: String = ""
        ): DbtExecutionRun {
            fun invalid(reason: String) = DbtExecutionRun(context, target, "", "", path.toString(), emptyList(), reason)
            val top = json(path)
                ?: return invalid(if (Files.exists(path)) "Malformed execution artifact" else "Not run / result unavailable")
            val metadata = obj(top, "metadata");
            val schema = string(metadata, "dbt_schema_version")
            val invocation = string(metadata, "invocation_id");
            val generated = string(metadata, "generated_at")
            val artifactTarget = string(obj(top, "args"), "target")
            if (!schema.matches(Regex("https://schemas\\.getdbt\\.com/dbt/(run-results/v[4-6]|sources/v[1-3])\\.json"))) return invalid(
                "Unsupported execution artifact schema"
            )
            if (artifactTarget.isNotEmpty() && artifactTarget != target) return invalid("Artifact target does not match the selected target")
            if (expectedInvocation.isNotEmpty() && invocation != expectedInvocation) return invalid("Artifact invocation does not match the active run")
            if (invocation.isEmpty() || runCatching { Instant.parse(generated) }.isFailure) return invalid("Artifact run identity or timestamp unavailable")
            val records = top["results"]
            val complete = when {
                records == null -> false
                records.isJsonArray -> records.asJsonArray.all { it.isJsonObject }
                records.isJsonObject -> records.asJsonObject.entrySet().all { it.value.isJsonObject }
                else -> false
            }
            val results = objects(top, "results").map { r ->
                DbtExecutionResult(
                    string(r, "unique_id"), normalize(string(r, "status")), string(r, "message"),
                    string(r, "failures").toLongOrNull() ?: 0L, string(r, "relation_name"),
                    string(r, "execution_time").toDoubleOrNull()?.let { (it * 1000).toLong() } ?: 0L)
            }
            return DbtExecutionRun(
                context, target, invocation, generated, path.toString(), results,
                if (!complete) "Partial results: malformed or missing result records"
                else if (results.any { it.id.isEmpty() || it.status == "unknown" }) "Partial results: missing identity or unsupported state" else ""
            )
        }

        fun marker(path: Path): Path = path.resolveSibling(".${path.fileName}.dbt-evidence")
        fun record(path: Path, context: List<String>) {
            fingerprint(path)?.let { digest ->
                val m = JsonObject(); m.add("context", gson.toJsonTree(context)); m.addProperty("digest", digest)
                Files.writeString(marker(path), m.toString())
            }
        }

        internal fun read(root: DbtRoot, name: String): DbtExecutionRun {
            val path = root.artifactDirectory.resolve(name);
            val context = DbtArtifactContext.identity(root)
            val run =
                parse(path, context, DbtExecutionContext.activeTarget(root).orEmpty()).copy(root = root.directory.path)
            if (run.results.isEmpty()) return run
            val matches = json(marker(path))?.let { m ->
                m["context"]?.toString() == gson.toJsonTree(context).toString() && fingerprint(path) == string(
                    m,
                    "digest"
                )
            } == true
            return if (!matches) run.copy(
                results = emptyList(),
                problem = "Artifact provenance unverified or target/configuration changed; run dbt explicitly"
            )
            else if (DbtWorkspace.get(root.config.project)
                    .outdated(root, runCatching { Instant.parse(run.timestamp).toEpochMilli() }.getOrNull())
            )
                run.copy(problem = "Stale result: project sources changed after this run") else run
        }
    }
}
