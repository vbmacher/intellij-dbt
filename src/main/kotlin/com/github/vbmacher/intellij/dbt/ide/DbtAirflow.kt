package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project.*
import com.github.vbmacher.intellij.dbt.project.DbtJson.array
import com.github.vbmacher.intellij.dbt.project.DbtJson.obj
import com.github.vbmacher.intellij.dbt.project.DbtJson.strings
import com.github.vbmacher.intellij.dbt.syntax.DbtYaml
import com.github.vbmacher.intellij.dbt.lineage.DbtLineage
import com.github.vbmacher.intellij.dbt.lineage.DbtSelection
import com.google.gson.JsonObject
import com.intellij.ide.BrowserUtil
import com.intellij.navigation.GotoRelatedItem
import com.intellij.navigation.GotoRelatedProvider
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime

/**
 * A statically discovered task and its selection evidence, scoped to an execution project.
 * [problems] can describe unresolved membership; [projectRoot] alone does not prove that a resource is selected.
 */
data class DbtAirflowTask(
    val dag: String,
    val task: String,
    val project: String,
    val selection: String,
    val exclude: String,
    val resources: List<DbtSymbol>,
    val evidence: String,
    val offset: Int,
    val ingestion: List<String>,
    val url: String?,
    val problems: List<String>,
    val projectRoot: String? = null,
    val definitionRoot: String = "",
    val line: Int = 1,
    val schedule: String = "",
    val kind: String = "dbt",
    val indirect: String = ""
) {
    val id: String get() = "$evidence:$offset|$dag|$task|$project"
}

internal data class DbtAirflowDestination(val path: String, val offset: Int, val url: String? = null)

/** Automatic static discovery of local DAG code; no Python or Airflow execution. */
object DbtAirflow {
    private val Tasks = Key.create<Triple<DbtAirflowScan, List<String>, List<DbtAirflowTask>>>("dbt.airflow.tasks")
    private fun resourceKey(w: DbtWorkspace, resource: DbtSymbol) =
        DbtResourceIdentity.key(w, resource) + "|" + resource.id

    fun problems(w: DbtWorkspace) = w.project.getService(DbtAirflowDiscovery::class.java).scan().problems
    /** Resolves discovered selectors against [w], retaining unresolved selections and ingestion-match confidence. */
    fun tasks(w: DbtWorkspace): List<DbtAirflowTask> {
        val scan = w.project.getService(DbtAirflowDiscovery::class.java).scan()
        val settings = w.project.getService(DbtConsumersSettings::class.java).getState()
        val configuration = listOf(settings.airflowUrl)
        w.getUserData(Tasks)?.takeIf { it.first === scan && it.second == configuration }?.let { return it.third }
        val executionRoots = w.roots.filter { w.executionRoot(it) == it }
        val base = safeUrl(settings.airflowUrl)
        fun rootFor(file: String) = if (executionRoots.size == 1) executionRoots.firstOrNull() else
            executionRoots.filter { file.startsWith(it.directory.path + "/") }.maxByOrNull { it.directory.path.length }

        fun link(dag: String, task: String) = base?.let {
            it.removeSuffix("/") + "/dags/" + encode(dag) + "/grid" + if (task == dag) "" else "?task_id=" + encode(task)
        }

        val raw = scan.pipelines.flatMap { (file, pipeline) ->
            ProgressManager.checkCanceled()
            val root = rootFor(file)
            val missingProject =
                if (root == null) listOf("dbt project is missing or ambiguous. Open its project in IDEA; with multiple dbt projects, place DAG files inside the owning project.") else emptyList()
            val sites = pipeline.sites.map { site ->
                val expression =
                    if (site.named.isNotEmpty()) "selector:" + site.named else if (site.select.isEmpty() && !site.dynamic) "*" else site.select
                val dynamic =
                    site.dynamic || listOf(expression, site.exclude).any { it.contains("{{") || it.contains("\${") }
                val invalid =
                    missingProject + if (dynamic) listOf("Dynamic/runtime selection is unresolved.") else emptyList()
                val resolved = if (invalid.isNotEmpty()) null else root?.let {
                    DbtSelection.resolve(
                        w,
                        it,
                        expression,
                        site.exclude
                    )
                }
                DbtAirflowTask(
                    pipeline.name,
                    site.task,
                    root?.name.orEmpty(),
                    expression,
                    site.exclude,
                    resolved?.resources ?: emptyList(),
                    file,
                    site.offset,
                    emptyList(),
                    link(pipeline.name, site.task),
                    invalid + (resolved?.problems ?: emptyList()),
                    root?.directory?.path,
                    root?.directory?.path.orEmpty(),
                    site.line,
                    pipeline.schedule,
                    indirect = site.indirect
                )
            }
            val ingestion = if (pipeline.ingests.isEmpty()) emptyList() else {
                val relations = if (root == null) emptyList() else w.symbols.filter { s ->
                    s.kind in DbtSymbol.RelationKinds && w.owner(s)?.let { w.executionRoot(it) == root } == true
                }
                    .sortedWith(compareBy<DbtSymbol> { it.isProperty }.thenBy { it.metadata })
                    .distinctBy { DbtResourceIdentity.key(w, it) }.map { symbol ->
                        val attributes = DbtResourceData.get(w, symbol).attributes
                        Triple(
                            symbol,
                            attributes["schema"],
                            attributes["identifier"] ?: attributes["alias"] ?: symbol.name
                        )
                    }
                val matches = pipeline.ingests.flatMap { ref ->
                    val exact =
                        if (ref.dataset == null) emptyList() else relations.filter { it.second == ref.dataset && it.third == ref.table }
                    val found = if (ref.dataset != null && exact.isNotEmpty()) exact.map { it.first to "exact" }
                    else if (ref.dataset != null) relations.filter {
                        it.second?.equals(
                            ref.dataset,
                            ignoreCase = true
                        ) == true && it.third.equals(ref.table, ignoreCase = true)
                    }.map { it.first to "case-insensitive" }
                    else relations.filter { it.third == ref.table }
                        .let { byTable -> if (byTable.size == 1) byTable.map { it.first to "table-only" } else emptyList() }
                    found.map { (symbol, confidence) ->
                        symbol to (ref.dataset?.let { "$it." }
                            .orEmpty() + ref.table + " (" + confidence + "; " + ref.via + ")")
                    }
                }
                val first = pipeline.ingests.first()
                listOf(
                    DbtAirflowTask(
                        pipeline.name,
                        pipeline.name,
                        root?.name.orEmpty(),
                        "",
                        "",
                        matches.map { it.first }.distinctBy { DbtResourceIdentity.key(w, it) },
                        file,
                        first.offset,
                        matches.map { it.second }.distinct(),
                        link(pipeline.name, pipeline.name),
                        missingProject,
                        root?.directory?.path,
                        root?.directory?.path.orEmpty(),
                        first.line,
                        pipeline.schedule,
                        "ingestion"
                    )
                )
            }
            sites + ingestion
        }
        val ingestors = raw.filter { it.kind == "ingestion" }
        val edges = if (ingestors.isNotEmpty()) DbtLineage.edges(w) else emptyList()
        val result = raw.map { task ->
            if (task.kind != "dbt" || ingestors.isEmpty()) task else {
                val upstream = task.resources.flatMap {
                    listOf(it) + DbtLineage.traverse(
                        edges,
                        it,
                        downstream = false,
                        depth = Int.MAX_VALUE
                    )
                }.map { DbtResourceIdentity.key(w, it) }.toSet()
                task.copy(ingestion = ingestors.filter {
                    it.resources.any { s ->
                        DbtResourceIdentity.key(
                            w,
                            s
                        ) in upstream
                    }
                }.map { it.dag }.distinct())
            }
        }
        w.putUserData(Tasks, Triple(scan, configuration, result))
        return result
    }

    /** Includes selected tasks and unresolved tasks from the same execution project without inventing resource membership. */
    fun context(w: DbtWorkspace, resource: DbtSymbol) = tasks(w).filter { task ->
        selected(w, resource, task) ||
                task.problems.isNotEmpty() && w.owner(resource)
            ?.let { task.projectRoot == w.executionRoot(it).directory.path } == true
    }

    fun selected(w: DbtWorkspace, resource: DbtSymbol, task: DbtAirflowTask) =
        task.resources.any { resourceKey(w, it) == resourceKey(w, resource) }

    fun details(w: DbtWorkspace, resource: DbtSymbol): JsonObject {
        val all = context(w, resource)
        val tasks = all.take(50).map { task ->
            val matches = selected(w, resource, task)
            val item = obj(
                "id" to task.id,
                "dag" to task.dag,
                "task" to task.task,
                "project" to task.project,
                "projectRoot" to task.projectRoot.orEmpty(),
                "selected" to matches,
                "status" to (if (!matches) "Unresolved selection — association unknown" else if (task.problems.isNotEmpty()) "Partial detected selection" else if (task.kind == "ingestion") "Detected ingestion destination" else "Detected selection"),
                "schedule" to task.schedule,
                "kind" to task.kind,
                "indirect" to task.indirect,
                "selection" to task.selection.take(4000),
                "exclude" to task.exclude.take(4000),
                "path" to task.evidence,
                "offset" to task.offset,
                "line" to task.line
            )
            task.url?.let { item.addProperty("url", it) }
            item.add("ingestion", strings(task.ingestion.take(100))); item.add(
            "problems",
            strings(task.problems.map { it.take(2000) })
        )
            item
        }
        return obj(
            "tasks" to array(tasks),
            "truncated" to (all.size > 50),
            "selectedCount" to all.count { selected(w, resource, it) },
            "unresolvedCount" to all.count { !selected(w, resource, it) })
    }

    internal fun navigation(
        w: DbtWorkspace,
        resource: DbtSymbol,
        mappingId: String,
        target: String
    ): Result<DbtAirflowDestination> {
        val task = context(w, resource).firstOrNull { it.id == mappingId } ?: return Result.failure(
            IllegalArgumentException("Airflow DAG selection changed or no longer applies to this resource. Refresh its details.")
        )
        return when (target) {
            "definition" -> Result.success(DbtAirflowDestination(task.evidence, task.offset))
            "browser" -> task.url?.let { Result.success(DbtAirflowDestination(task.evidence, task.offset, it)) }
                ?: Result.failure(IllegalArgumentException("No Airflow URL is configured. Set it in Settings > dbt Workspace > Integrations."))

            else -> Result.failure(IllegalArgumentException("Unknown Airflow navigation action."))
        }
    }

    private fun encode(text: String) = URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20")
    fun safeUrl(text: String): String? = try {
        URI(text).takeIf { it.scheme.orEmpty() in setOf("http", "https") && it.host != null && it.userInfo == null }
            ?.toASCIIString()
    } catch (_: Exception) {
        null
    }
}

internal data class DbtAirflowScan(
    val pipelines: List<Pair<String, DbtAirflowParser.Pipeline>>,
    val problems: List<String>
)

/** Watches external DAG checkouts as well as files already belonging to this IDEA project. */
@Service(Service.Level.PROJECT)
class DbtAirflowDiscovery(private val project: Project) : Disposable {
    private val skip = setOf("__pycache__", "node_modules", ".git", ".venv", "venv")
    private var watched = emptyList<String>()
    private var requests = emptyList<LocalFileSystem.WatchRequest>()
    private var cache = DbtAirflowScan(emptyList(), emptyList())

    private data class FileEntry(
        val stamp: Triple<FileTime, Long, Long?>,
        val pipelines: List<DbtAirflowParser.Pipeline>
    )

    private val parsed = mutableMapOf<Path, FileEntry>()
    internal val directories: List<String>
        get() = project.getService(DbtConsumersSettings::class.java).getState().airflowPaths.map { it.trim() }
            .filter { it.isNotEmpty() }.map { value ->
            val path = Paths.get(value)
            (if (path.isAbsolute) path else Paths.get(project.basePath ?: ".").resolve(path)).normalize().toString()
        }.distinct()

    private fun watch(paths: List<String>) {
        if (watched == paths) return
        val fs = LocalFileSystem.getInstance()
        requests.forEach(fs::removeWatchedRoot)
        requests = paths.mapNotNull { fs.addRootToWatch(it, true) }
        watched = paths
        ApplicationManager.getApplication().executeOnPooledThread {
            if (!project.isDisposed) paths.forEach { fs.refreshAndFindFileByPath(it)?.refresh(true, true) }
        }
    }

    @Synchronized
    internal fun scan(): DbtAirflowScan {
        val paths = directories
        watch(paths)
        val pipelines = mutableListOf<Pair<String, DbtAirflowParser.Pipeline>>()
        val problems = mutableListOf<String>()
        val seen = mutableSetOf<Path>()
        paths.forEach { directory ->
            val root = Paths.get(directory)
            if (!Files.isDirectory(root)) problems += "Airflow DAG folder is missing or is not a directory: $directory"
            else try {
                Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        ProgressManager.checkCanceled()
                        return if (dir.fileName?.toString() in skip) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
                    }

                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        ProgressManager.checkCanceled()
                        if (file.toString().endsWith(".py") && attrs.isRegularFile && seen.add(
                                file.toAbsolutePath().normalize()
                            )
                        ) {
                            if (attrs.size() > 1000000) problems += "Airflow DAG file exceeds 1 MB: $file" else try {
                                val virtual = LocalFileSystem.getInstance().findFileByNioFile(file)
                                val documents = FileDocumentManager.getInstance()
                                val unsaved =
                                    virtual?.let(documents::getCachedDocument)?.takeIf(documents::isDocumentUnsaved)
                                val stamp = Triple(attrs.lastModifiedTime(), attrs.size(), unsaved?.modificationStamp)
                                val entry = parsed[file]?.takeIf { it.stamp == stamp } ?: FileEntry(
                                    stamp,
                                    DbtAirflowParser.parse(file.toString(), unsaved?.text ?: Files.readString(file))
                                )
                                parsed[file] = entry
                                pipelines += entry.pipelines.map { file.toString() to it }
                            } catch (canceled: ProcessCanceledException) {
                                throw canceled
                            } catch (_: Exception) {
                                problems += "Cannot read Airflow DAG file: $file"
                            }
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, error: java.io.IOException): FileVisitResult {
                        problems += "Cannot read Airflow DAG path: $file"; return FileVisitResult.CONTINUE
                    }
                })
            } catch (canceled: ProcessCanceledException) {
                throw canceled
            } catch (_: Exception) {
                problems += "Cannot scan Airflow DAG folder: $directory"
            }
        }
        parsed.keys.filter { it.toAbsolutePath().normalize() !in seen }.forEach(parsed::remove)
        val result = DbtAirflowScan(
            pipelines.sortedWith(compareBy<Pair<String, DbtAirflowParser.Pipeline>> { it.first }.thenBy { it.second.name }),
            problems.distinct()
        )
        if (result != cache) cache = result
        return cache
    }

    @Synchronized
    override fun dispose() {
        requests.forEach(LocalFileSystem.getInstance()::removeWatchedRoot); requests = emptyList()
    }
}

/** Static discovery adapted from tchandrap/dbt-airflow-lineage (MIT; see third-party/dbt-airflow-lineage).
 * Reads literals only: DAG modules, imports and user functions are never executed.
 */
internal object DbtAirflowParser {
    data class Site(
        val select: String,
        val exclude: String,
        val named: String,
        val dynamic: Boolean,
        val task: String,
        val indirect: String,
        val offset: Int,
        val line: Int
    )

    data class Ingest(val dataset: String?, val table: String, val offset: Int, val line: Int, val via: String)
    data class Pipeline(val name: String, val schedule: String, val sites: List<Site>, val ingests: List<Ingest>)
    private data class Tok(
        val kind: String,
        val value: String,
        val offset: Int,
        val line: Int,
        val formatted: Boolean = false,
        val items: List<Tok> = emptyList()
    )

    private data class KV(val key: String, val value: List<Tok>, val offset: Int, val line: Int)
    private sealed interface Value
    private data class Text(val value: String) : Value
    private data class Values(val value: List<Value>) : Value
    private data object Unset : Value
    private data object Dynamic : Value

    private val SelectKeys = setOf("dbt_selector", "dbt_select", "selector", "select", "selectors", "dbt_selectors")
    private val ExcludeKeys = setOf("dbt_exclude", "exclude", "excludes", "dbt_excludes")
    private val NameKeys = setOf("name", "dag_name_suffix", "dag_id", "dag_name", "pipeline_name")
    private val NamedKeys = setOf("dbt_selector_name", "selector_name")
    private val ScheduleKeys = setOf("schedule", "schedule_interval")
    private val IndirectKeys = setOf("dbt_indirect_selection", "indirect_selection")
    private val TableKeys =
        setOf("bq_table", "bq_table_name", "bq_table_id", "destination_table", "target_table", "target_table_name")
    private val DatasetKeys = setOf("bq_dataset", "bq_dataset_id", "destination_dataset", "target_dataset")
    private val FullKeys = setOf(
        "full_table_schema_reference",
        "target_table_fullname",
        "destination_table_fullname",
        "destination_project_dataset_table"
    )
    private val FullRef = Regex("`?(?:\\{[^}]+\\}|[\\w-]+)\\.([A-Za-z_]\\w*)\\.([A-Za-z_]\\w*)`?")
    private fun tokenize(source: String): List<Tok> {
        var at = 0
        var line = 1
        val prefix = Regex("([rRbBuUfF]{0,2})(\"\"\"|'''|\"|')")
        fun read(close: Char, depth: Int): List<Tok> {
            val out = mutableListOf<Tok>()
            while (at < source.length) {
                ProgressManager.checkCanceled()
                val c = source[at]
                if (c == close && close != '\u0000') {
                    at++; return out.toList()
                }
                if (c == '\n') {
                    line++; at++
                } else if (c.isWhitespace() || c == '\\') at++
                else if (c == '#') {
                    while (at < source.length && source[at] != '\n') at++
                } else {
                    val m =
                        prefix.find(source.substring(at, minOf(at + 6, source.length)))?.takeIf { it.range.first == 0 }
                    when {
                        m != null -> {
                            val start = at;
                            val startLine = line;
                            val quote = m.groupValues[2]
                            val raw = m.groupValues[1].lowercase().contains('r')
                            val value = StringBuilder()
                            at += m.value.length
                            while (at < source.length && !source.startsWith(quote, at)) {
                                if (source[at] == '\\' && !raw && at + 1 < source.length) {
                                    val next = source[at + 1]
                                    value.append(if (next == 'n') '\n' else if (next == 't') '\t' else next)
                                    if (next == '\n') line++
                                    at += 2
                                } else {
                                    if (source[at] == '\n') line++; value.append(source[at]); at++
                                }
                            }
                            if (at < source.length) at += quote.length
                            out += Tok(
                                "str",
                                value.toString(),
                                start,
                                startLine,
                                m.groupValues[1].lowercase().contains('f')
                            )
                        }

                        c.isLetter() || c == '_' -> {
                            val start = at
                            while (at < source.length && (source[at].isLetterOrDigit() || source[at] == '_')) at++
                            out += Tok("name", source.substring(start, at), start, line)
                        }

                        c in "([{" && depth < 100 -> {
                            val start = at;
                            val startLine = line; at++
                            val children = read(mapOf('(' to ')', '[' to ']', '{' to '}').getValue(c), depth + 1)
                            out += Tok("group", c.toString(), start, startLine, items = children)
                        }

                        else -> {
                            out += Tok("op", c.toString(), at, line); at++
                        }
                    }
                }
            }
            return out.toList()
        }
        return read('\u0000', 0)
    }

    private fun split(items: List<Tok>, separator: String = ","): List<List<Tok>> {
        val out = mutableListOf(mutableListOf<Tok>())
        items.forEach { t ->
            if (t.kind == "op" && t.value == separator) out.add(mutableListOf()) else out.last().add(t)
        }
        return out.filter { it.isNotEmpty() }.map { it.toList() }
    }

    private fun flatten(value: Value): List<String>? = when (value) {
        is Text -> listOf(value.value)
        Unset -> emptyList()
        is Values -> {
            val parts = value.value.map(::flatten); if (parts.all { it != null }) parts.filterNotNull()
                .flatten() else null
        }

        Dynamic -> null
    }

    private fun asText(value: Value) = flatten(value)?.joinToString(" ")?.trim()?.takeIf { it.isNotEmpty() }
    private fun eval(tokens: List<Tok>, env: Map<String, List<Tok>>, depth: Int = 0): Value {
        if (tokens.isEmpty() || depth > 8) return Dynamic
        val plus = split(tokens, "+")
        if (plus.size > 1) {
            val parts = plus.map { eval(it, env, depth + 1) }
            return if (parts.all { it is Text }) Text(parts.filterIsInstance<Text>().joinToString("") { it.value })
            else if (parts.all { it is Values }) Values(
                parts.filterIsInstance<Values>().flatMap { it.value }) else Dynamic
        }
        if (tokens.all { it.kind == "str" }) return if (tokens.any { it.formatted && it.value.contains('{') }) Dynamic else Text(
            tokens.joinToString("") { it.value })
        if (tokens.size == 1) {
            val t = tokens.first()
            if (t.kind == "name" && t.value == "None") return Unset
            if (t.kind == "name" && t.value in env) return eval(env.getValue(t.value), env, depth + 1)
            if (t.kind == "group" && t.value in setOf("[", "(")) {
                val parts = split(t.items)
                return if (t.value == "(" && parts.size == 1 && t.items.none { it.value == "," }) eval(
                    parts.first(),
                    env,
                    depth + 1
                )
                else Values(parts.map { eval(it, env, depth + 1) })
            }
            return Dynamic
        }
        val dot = tokens.indices.firstOrNull { tokens[it].value == "." && tokens.getOrNull(it + 1)?.value == "join" }
            ?: return Dynamic
        val group = tokens.getOrNull(dot + 2)?.takeIf { it.kind == "group" } ?: return Dynamic
        val separator = eval(tokens.take(dot), env, depth + 1)
        val list = eval(listOf(group), env, depth + 1)
        return if (separator is Text && list is Values) flatten(list)?.let { Text(it.joinToString(separator.value)) }
            ?: Dynamic else Dynamic
    }

    private fun environment(top: List<Tok>): Map<String, List<Tok>> {
        val env = mutableMapOf<String, List<Tok>>()
        top.indices.forEach { i ->
            if (top[i].kind == "name" && top.getOrNull(i + 1)?.value == "=" && top.getOrNull(i - 1)?.value != ".") {
                val expressionLine = top.getOrNull(i + 2)?.line ?: top[i].line
                val expression = top.drop(i + 2).takeWhile { it.line == expressionLine && it.value != ";" }
                if (eval(expression, env) != Dynamic) env[top[i].value] = expression
            }
        }
        return env.toMap()
    }

    private fun kvs(group: Tok): List<KV> = split(group.items).mapNotNull { el ->
        if (group.value == "(" && el.first().kind == "name" && el.getOrNull(1)?.value == "=") KV(
            el.first().value,
            el.drop(2),
            el.first().offset,
            el.first().line
        )
        else if (group.value == "{" && el.first().kind == "str" && el.getOrNull(1)?.value == ":") KV(
            el.first().value,
            el.drop(2),
            el.first().offset,
            el.first().line
        ) else null
    }

    private fun dagDefinition(items: List<Tok>): List<KV>? {
        items.indices.forEach { i ->
            val t = items[i]
            val found =
                if (t.kind == "name" && t.value in setOf("DAG", "dag") && items.getOrNull(i - 1)?.value != "def")
                    items.getOrNull(i + 1)?.takeIf { it.kind == "group" && it.value == "(" }?.let(::kvs)
                        ?.takeIf { it.isNotEmpty() }
                else if (t.kind == "group") dagDefinition(t.items) else null
            if (found != null) return found
        }
        return null
    }

    /** CLI flag extraction uses the same spellings as the reference, including --models and -m. */
    fun shell(command: String): Triple<String, String, String>? {
        if (!Regex("\\bdbt\\s+(run|build|test|seed|snapshot|ls|list)\\b").containsMatchIn(command)) return null
        val tokens = com.intellij.util.execution.ParametersListUtil.parse(command, false, true)
        fun grab(flags: Set<String>): String {
            val values = mutableListOf<String>();
            var i = 0
            while (i < tokens.size) {
                val token = tokens[i]
                val flag = flags.firstOrNull { token.startsWith("$it=") }
                if (flag != null) values += token.drop(flag.length + 1)
                else if (token in flags) while (i + 1 < tokens.size && !tokens[i + 1].startsWith("-") && tokens[i + 1] !in setOf(
                        "&&",
                        ";",
                        "||",
                        "|"
                    )
                ) {
                    i++; values += tokens[i]
                }
                i++
            }
            return values.joinToString(" ")
        }
        return Triple(
            grab(setOf("--select", "--models", "--model", "-s", "-m")),
            grab(setOf("--exclude")),
            grab(setOf("--selector"))
        )
    }

    fun parse(file: String, source: String): List<Pipeline> {
        val top = tokenize(source)
        val env = environment(top)
        val dag = dagDefinition(top) ?: emptyList()
        val pipelines = linkedMapOf<String, Pipeline>()
        val base = file.replace('\\', '/').split('/').last().removeSuffix(".py")
        fun text(kv: KV) = asText(eval(kv.value, env)).orEmpty()
        fun find(entries: List<KV>, keys: Set<String>) = entries.firstOrNull { it.key in keys }
        fun pipeline(chain: List<List<KV>>): Pipeline {
            val flat = chain.flatten() + dag
            val name = find(flat, NameKeys)?.let(::text)?.takeIf { it.isNotEmpty() } ?: base
            val schedule = find(flat, ScheduleKeys)?.let {
                text(it).takeIf { s -> s.isNotEmpty() } ?: it.value.joinToString("") { t -> t.value }.take(60)
            }.orEmpty()
            val p = pipelines[name] ?: Pipeline(name, schedule, emptyList(), emptyList())
            val result = if (p.schedule.isEmpty()) p.copy(schedule = schedule) else p
            pipelines[name] = result
            return result
        }

        fun visit(items: List<Tok>, chain: List<List<KV>>, depth: Int) {
            if (depth > 100) return
            items.indices.forEach { i ->
                ProgressManager.checkCanceled()
                val t = items[i]
                if (t.kind == "str") shell(t.value)?.let { (select, exclude, named) ->
                    if (select.isNotEmpty() || exclude.isNotEmpty() || named.isNotEmpty()) {
                        val p = pipeline(chain)
                        val task = find(chain.asReversed().flatten(), setOf("task_id", "name"))?.let(::text)
                            ?.takeIf { it.isNotEmpty() } ?: p.name
                        val site = Site(
                            select,
                            exclude,
                            named,
                            t.value.contains("{{") || t.formatted && t.value.contains('{'),
                            task,
                            "",
                            t.offset,
                            t.line
                        )
                        pipelines[p.name] = p.copy(sites = p.sites + site)
                    }
                }
                if (t.kind == "group") {
                    val entries = if (items.getOrNull(i - 2)?.value == "def") emptyList() else kvs(t)
                    val ancestors = chain.flatten()
                    val next = if (entries.isNotEmpty()) chain + listOf(entries) else chain
                    fun inherited(keys: Set<String>) = find(entries, keys) ?: find(ancestors, keys)
                    if (entries.isNotEmpty() && t.value != "[") {
                        val dataset = inherited(DatasetKeys)?.let { eval(it.value, env) }
                            ?.let { (it as? Text)?.value?.trim()?.takeIf { s -> s.isNotEmpty() } }
                        val ingests = entries.flatMap { k ->
                            val strong = if (k.key in TableKeys) (eval(k.value, env) as? Text)?.value?.takeIf {
                                it.matches(
                                    Regex("[A-Za-z_]\\w*")
                                )
                            }
                                ?.let { listOf(Ingest(dataset, it, k.offset, k.line, k.key)) }
                                ?: emptyList() else emptyList()
                            val full =
                                if (k.key.lowercase() in FullKeys && k.value.size == 1 && k.value.first().kind == "str")
                                    FullRef.matchEntire(k.value.first().value.trim())?.let {
                                        listOf(
                                            Ingest(
                                                it.groupValues[1],
                                                it.groupValues[2],
                                                k.offset,
                                                k.line,
                                                k.key
                                            )
                                        )
                                    } ?: emptyList() else emptyList()
                            strong + full
                        }
                        if (ingests.isNotEmpty()) {
                            val p = pipeline(listOf(dag))
                            pipelines[p.name] =
                                p.copy(ingests = (p.ingests + ingests).distinctBy { it.dataset to it.table })
                        }
                        val select = find(entries, SelectKeys)
                        val named = inherited(NamedKeys)
                        if (select != null || named != null) {
                            val sv = select?.let { eval(it.value, env) } ?: Unset
                            val xv = inherited(ExcludeKeys)?.let { eval(it.value, env) } ?: Unset
                            val nv = named?.let { eval(it.value, env) } ?: Unset
                            val dynamic = flatten(sv) == null || flatten(xv) == null || flatten(nv) == null
                            val selection = asText(sv).orEmpty();
                            val selector = asText(nv).orEmpty()
                            if (selection.isNotEmpty() || selector.isNotEmpty() || flatten(sv) == null || flatten(nv) == null) {
                                val p = pipeline(next)
                                val location = select ?: named!!
                                val task =
                                    find(entries, setOf("task_id", "name"))?.let(::text)?.takeIf { it.isNotEmpty() }
                                        ?: p.name
                                val indirect = inherited(IndirectKeys)?.value?.joinToString("") { it.value }.orEmpty()
                                val site = Site(
                                    selection,
                                    asText(xv).orEmpty(),
                                    selector,
                                    dynamic,
                                    task,
                                    indirect,
                                    location.offset,
                                    location.line
                                )
                                pipelines[p.name] = p.copy(sites = p.sites + site)
                            }
                        }
                    }
                    visit(t.items, next, depth + 1)
                }
            }
        }
        visit(top, emptyList(), 0)
        return pipelines.values.filter { it.sites.isNotEmpty() || it.ingests.isNotEmpty() }
    }
}

class DbtAirflowRelatedProvider : GotoRelatedProvider() {
    override fun getItems(context: DataContext): List<GotoRelatedItem> {
        val (project, file, offset) = DbtEditorAction.caret(context) ?: return emptyList()
        val w = DbtWorkspace.get(project)
        val resource = DbtResourceAction.at(w, file, offset) ?: return emptyList()
        return DbtAirflow.context(w, resource).flatMap { task ->
            val definition =
                LocalFileSystem.getInstance().findFileByPath(task.evidence)?.let { DbtYaml.psiFile(w.project, it) }
                    ?.findElementAt(task.offset)?.let { at ->
                    object : GotoRelatedItem(at, "Airflow DAGs / tasks") {
                        override fun getCustomName() = task.dag + " / " + task.task
                        override fun getCustomContainerName() = task.evidence + ":" + task.line
                    }
                }
            val browser = task.url?.let { url ->
                object : GotoRelatedItem(null, "Airflow task links", -1) {
                    override fun getCustomName() = "Open " + task.dag + " / " + task.task
                    override fun getCustomContainerName() = task.schedule
                    override fun navigate() = BrowserUtil.browse(url)
                }
            }
            listOfNotNull(definition, browser)
        }
    }
}

class DbtAirflowAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        event.project?.let(DbtAirflowResults::show)
    }
}
