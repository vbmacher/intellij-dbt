package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.navigation.DbtCte
import com.github.vbmacher.intellij.dbt.navigation.DbtSqlAnalysis
import com.github.vbmacher.intellij.dbt.project.*
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.testFramework.LightVirtualFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** Source path, zero-based line and expansion-instance identity emitted by one traced compilation. */
internal data class DbtCteOrigin(val path: String, val line: Int, val instance: Long)
internal data class DbtCteSpan(val range: TextRange, val origins: List<DbtCteOrigin>)

/**
 * Generated SQL, parsed CTEs and source mappings from the same captured [context].
 * A CTE may have several origins or none; consumers retain that ambiguity and can show the read-only [file].
 */
internal data class DbtCteCompilation(
    val context: DbtCteContext,
    val sql: String,
    val analysis: DbtSqlAnalysis,
    val spans: List<DbtCteSpan>
) {
    val frames: List<DbtCte> = DbtCteInspection.frames(analysis)
    val file = LightVirtualFile("dbt-compiled-inspection.sql", sql).apply { isWritable = false }
    private val originsByStep: Map<Int, List<DbtCteOrigin>> = run {
        val sorted = spans.sortedBy { it.range.startOffset };
        var first = 0
        frames.associate { frame ->
            while (first < sorted.size && sorted[first].range.endOffset <= frame.range.startOffset) first++
            val mapped = sorted.asSequence().drop(first).takeWhile { it.range.startOffset < frame.range.endOffset }
                .filter { it.range.intersectsStrict(frame.range) }.flatMap { it.origins }.distinct().toList()
            frame.range.startOffset to mapped
        }
    }

    fun origins(cte: DbtCte): List<DbtCteOrigin> = originsByStep[cte.range.startOffset].orEmpty()

    companion object {
        /** Emit already compiled text once; generated Jinja-looking literals must not be rendered again. */
        fun inline(sql: String): String =
            if (sql.contains("{{") || sql.contains("{%")) "{{ " + com.google.gson.JsonPrimitive(sql)
                .toString() + " }}" else sql
    }
}

/**
 * Owns a traced Compile command and its temporary source snapshot/report directory.
 * Preparation writes tracing inputs but launches nothing; [DbtRunner] executes the command.
 * [dispose] removes temporary files after loading, cancellation or a discarded preparation.
 */
internal data class DbtCteCompileJob(
    val command: DbtCommand, val context: DbtCteContext, val directory: Path,
    val token: String, val sources: Map<String, String>
) : Disposable {
    /**
     * Reads the completed report, checking its token, SQL bounds and origins against the captured source files.
     * Unsupported CLI tracing, malformed mappings and unsupported compiled SQL return a failure.
     */
    fun load(): Result<DbtCteCompilation> = dbtResult {
        val lineCounts = sources.mapValues { (_, text) -> text.count { it == '\n' } }
        val report = directory.resolve("report.json")
        require(Files.isRegularFile(report)) {
            "The configured CLI did not provide source mappings. " +
                    "Use a Python dbt Core 1.8+ executable that preserves PYTHONPATH; native/Fusion CLIs are not supported for source-mapped inspection."
        }
        require(Files.size(report) <= 30000000) { "The compilation source map exceeds 30 MB." }
        val root = dbtResult { JsonParser.parseString(Files.readString(report)).asJsonObject }
            .getOrElse { throw IllegalArgumentException("The compiler returned an invalid source map.") }
        require(DbtJson.text(root, "token") == token) { "The source map does not belong to this compilation." }
        DbtJson.text(root, "error")?.let { throw IllegalArgumentException(it) }
        val sql = DbtJson.text(root, "sql") ?: throw IllegalArgumentException("The compiler returned no SQL.")
        val analysis = DbtSqlAnalysis.compiled(sql).getOrThrow()
        val spans = dbtResult {
            root.getAsJsonArray("spans")?.map { value ->
                val span = value.asJsonObject;
                val start = span["start"].asInt;
                val end = span["end"].asInt
                require(start >= 0 && start < end && end <= sql.length)
                val origins = span.getAsJsonArray("origins").map { item ->
                    val origin = item.asJsonObject;
                    val path = origin["path"].asString;
                    val line = origin["line"].asInt
                    require(lineCounts[path]?.let { line in 0..it } == true)
                    DbtCteOrigin(path, line, origin["instance"].asLong)
                }
                DbtCteSpan(TextRange(start, end), origins)
            }.orEmpty()
        }.getOrElse { throw IllegalArgumentException("The compiler returned invalid source locations.") }
        DbtCteCompilation(context, sql, analysis, spans)
    }

    override fun dispose() {
        if (Files.isDirectory(directory)) Files.walk(directory).use { files ->
            files.sorted(Comparator.comparingInt<Path> { it.nameCount }.reversed()).forEach { Files.deleteIfExists(it) }
        }
    }

    companion object {
        fun prepare(w: DbtWorkspace, resource: DbtSymbol): Result<DbtCteCompileJob> = dbtResult {
            val owner = w.owner(resource) ?: throw IllegalArgumentException("The owning dbt project is unavailable.")
            require(w.executionRoot(owner).engine == "core") { "Source-mapped CTE inspection requires the Python dbt Core 1.8+ CLI." }
            val selection = DbtRunSelection.available(w).find { it.path == resource.file.virtualFile.path }
                ?: throw IllegalArgumentException("Choose a source model.")
            val base = DbtCommand.create(w, selection, "Compile", false).getOrThrow()
            val context = DbtCteInspection.context(w, resource)
                ?: throw IllegalArgumentException("The execution context is unavailable.")
            dbtResult {
                val directory = Files.createTempDirectory("dbt-cte-compile-")
                try {
                    for (name in listOf("cte_trace.py", "sitecustomize.py")) {
                        val stream = DbtCteCompileJob::class.java.getResourceAsStream("/dbt/$name")
                            ?: throw IllegalArgumentException("Missing compiler tracing resource: $name")
                        stream.use { Files.copy(it, directory.resolve(name)) }
                    }
                    val sourceJson = JsonArray()
                    val sources = w.files.filter { it.name.endsWith(".sql") }.mapNotNull { file ->
                        w.root(file)?.takeIf { w.executionRoot(it) == w.executionRoot(owner) }?.let { root ->
                            val entry = JsonObject(); entry.addProperty("package", root.name)
                            entry.addProperty(
                                "relative",
                                VfsUtilCore.getRelativePath(file.virtualFile, root.directory, '/')
                            )
                            entry.addProperty("path", file.virtualFile.path); entry.addProperty(
                            "text",
                            file.text
                        ); sourceJson.add(entry)
                            file.virtualFile.path to file.text
                        }
                    }.toMap()
                    require(sources.values.sumOf { it.length.toLong() } <= 20000000) { "Compilation source snapshot exceeds 20 MB." }
                    val token = UUID.randomUUID().toString();
                    val request = JsonObject()
                    request.addProperty("token", token); request.addProperty("model", resource.file.virtualFile.path)
                    request.addProperty("report", directory.resolve("report.json").toString()); request.add(
                        "sources",
                        sourceJson
                    )
                    Files.writeString(directory.resolve("request.json"), request.toString())
                    val pythonPath =
                        (base.environment["PYTHONPATH"] ?: System.getenv("PYTHONPATH"))?.takeIf { it.isNotEmpty() }
                    val environment = base.environment + mapOf(
                        "DBT_IDE_CTE_TRACE" to directory.resolve("request.json").toString(),
                        "PYTHONPATH" to (directory.toString() + pythonPath?.let { java.io.File.pathSeparator + it }
                            .orEmpty()),
                        "DBT_PARTIAL_PARSE" to "false", "DBT_TARGET_PATH" to directory.resolve("target").toString()
                    )
                    DbtCteCompileJob(
                        base.copy(
                            environment = environment,
                            artifactDirectory = directory.resolve("target").toString()
                        ), context, directory, token, sources
                    )
                } catch (error: Exception) {
                    DbtCteCompileJob(base, context, directory, "", emptyMap()).dispose(); throw error
                }
            }.getOrElse { throw IllegalArgumentException("Cannot prepare source-mapped compilation: " + it.message) }
        }
    }
}
