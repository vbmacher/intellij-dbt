package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project.*
import com.github.vbmacher.intellij.dbt.project.DbtJson.array
import com.github.vbmacher.intellij.dbt.project.DbtJson.strings
import com.github.vbmacher.intellij.dbt.lineage.*
import com.github.vbmacher.intellij.dbt.navigation.DbtNavigation
import com.google.gson.*
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.execution.services.ServiceViewManager
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.*
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager

/** Read-only tool schemas and workspace projections; call dispatch expects validated arguments and IDE read access. */
internal object DbtAgentProtocol {
    const val Version = "1.0"

    /** Modern versions carry protocol metadata in every request; legacy versions negotiate it with initialize. */
    val ModernVersions = listOf("2026-07-28")
    val LegacyVersions = listOf("2025-11-25", "2025-06-18", "2025-03-26")
    val SupportedVersions = ModernVersions + LegacyVersions
    const val DefaultLegacyVersion = "2025-03-26"
    const val Instructions =
        "Read-only access to the dbt projects open in the IDE. Call list_projects first and pass its project " +
                "directory to the other tools. No tool runs dbt, executes SQL or changes files."
    const val ResponseLimit = 2000000
    val Tools = listOf(
        Triple("list_projects", "List owning project identities and source locations", emptyList<String>()),
        Triple("list_resources", "List resource identities in one owning project", listOf("project")),
        Triple(
            "resolve_reference",
            "Resolve a source reference at an offset or by exact resource name",
            listOf("project", "reference")
        ),
        Triple(
            "get_documentation",
            "Source-first documentation and columns with provenance",
            listOf("resource_id", "project")
        ),
        Triple("get_lineage", "Static resource edges with exact source identities", listOf("resource_id", "project")),
        Triple(
            "get_column_lineage",
            "Static column derivations and partial analysis flags",
            listOf("resource_id", "project", "column")
        ),
        Triple("resolve_selection", "Preview a local selector with unresolved terms", listOf("project", "selection"))
    )
    private val OptionalArguments = mapOf(
        "resolve_reference" to listOf("file", "offset"),
        "get_lineage" to listOf("direction"),
        "get_column_lineage" to listOf("direction"),
        "resolve_selection" to listOf("exclude")
    )

    fun allowedArguments(tool: String): List<String> =
        Tools.find { it.first == tool }?.third.orEmpty() + OptionalArguments[tool].orEmpty()

    fun obj(vararg values: Pair<String, Any?>): JsonObject = DbtJson.obj(*values)
    fun error(code: Int, message: String): JsonObject = obj("message" to message, "code" to code)

    /** A JSON-RPC error response; id is omitted when the request id could not be read. */
    fun errorResponse(id: JsonElement?, code: Int, message: String, data: JsonElement? = null): JsonObject {
        val response = obj("jsonrpc" to "2.0"); id?.let { response.add("id", it) };
        val e = error(code, message); data?.let { e.add("data", it) }; response.add("error", e); return response
    }

    val serverInfo: JsonObject get() = obj("name" to "dbt-workspace", "version" to Version)

    /** The common mcpServers JSON block that MCP clients accept for remote HTTP servers. */
    fun clientConfiguration(url: String, token: String): String {
        val server = obj("type" to "http", "url" to url, "headers" to obj("Authorization" to ("Bearer " + token)))
        return GsonBuilder().setPrettyPrinting().create().toJson(obj("mcpServers" to obj("dbt-workspace" to server)))
    }

    val schemas: JsonArray
        get() = array(Tools.map { (name, description, required) ->
            val properties = JsonObject()
            allowedArguments(name).filterNot { it == "offset" }.forEach { key ->
                val value = obj("type" to "string", "maxLength" to 16384); if (key == "direction") value.add(
                "enum",
                strings(listOf("upstream", "downstream"))
            ); properties.add(key, value)
            }
            if ("offset" in allowedArguments(name)) properties.add(
                "offset",
                obj("type" to "integer", "minimum" to 0, "maximum" to Int.MAX_VALUE)
            )
            obj(
                "name" to name,
                "description" to description,
                "inputSchema" to obj(
                    "type" to "object",
                    "properties" to properties,
                    "required" to strings(required),
                    "additionalProperties" to false
                ),
                "annotations" to obj("readOnlyHint" to true, "destructiveHint" to false)
            )
        })

    fun resource(w: DbtWorkspace, s: DbtSymbol): JsonObject {
        val result = obj(
            "resource_id" to s.uniqueId,
            "project" to w.owner(s)?.directory?.path.orEmpty(),
            "kind" to s.kind,
            "name" to s.name,
            "owner" to s.owner,
            "package" to s.namespace,
            "version" to s.version.orEmpty(),
            "path" to s.file.virtualFile.path,
            "provenance" to if (s.metadata) "local artifact" else "current source"
        )
        if (s.kind in DbtSymbol.ColumnKinds) w.relations(s).sortedBy { it.isProperty }.firstOrNull()
            ?.let { result.addProperty("relation_resource_id", it.uniqueId) }
        result.addProperty("offset", s.range.startOffset); result.addProperty(
            "line",
            s.file.text.take(s.range.startOffset).count { it == '\n' } + 1); result.addProperty(
            "stale",
            s.isStale
        ); return result
    }

    private fun argument(args: JsonObject, key: String): String =
        args[key]?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

    private fun response(status: String, message: String = ""): JsonObject =
        obj("status" to status, "message" to message, "schema_version" to Version)

    fun call(w: DbtWorkspace, tool: String, args: JsonObject): JsonObject {
        fun text(key: String) = argument(args, key)
        val roots = w.roots.filter { it.directory.path == text("project") || it.name == text("project") }
        val candidates = w.symbols.filter { w.owner(it) in roots && it.uniqueId == text("resource_id") }
            .sortedWith(compareBy({ it.metadata }, { it.isProperty }))
            .distinctBy { DbtResourceIdentity.key(w, it) to it.owner }

        fun resourceResult(f: (DbtSymbol) -> JsonObject): JsonObject {
            if (roots.size != 1) return response("ambiguous", "Choose an exact project directory from list_projects.")
            if (candidates.size != 1) return response(
                if (candidates.isEmpty()) "missing" else "ambiguous",
                "Resource identity did not resolve uniquely."
            ).apply { add("candidates", array(candidates.map { resource(w, it) })) }
            return f(candidates.first())
        }
        if (w.project.isDisposed) return response("disposed", "Project is closed.")
        return when (tool) {
            "list_projects" -> response(if (w.roots.size > 100) "partial" else "ok").apply {
                add(
                    "projects",
                    array(
                        w.roots.take(100).map {
                            obj(
                                "name" to it.name,
                                "project" to it.directory.path,
                                "path" to (it.directory.path + "/dbt_project.yml")
                            )
                        })
                )
            }

            "list_resources" -> if (roots.size != 1) response(
                "ambiguous",
                "Choose an exact project directory from list_projects."
            ) else {
                val resources = w.symbols.filter {
                    w.owner(it) == roots.first() && it.kind !in setOf(
                        "environment",
                        "env"
                    ) && (it.isCode || it.isProperty || it.metadata || it.kind in DbtResourceData.GraphKinds)
                }
                    .sortedWith(compareBy({ it.metadata }, { it.isProperty })).distinctBy { it.uniqueId }
                response(if (resources.size > 1000) "partial" else "ok").apply {
                    add(
                        "resources",
                        array(resources.take(1000).map { resource(w, it) })
                    )
                }
            }

            "resolve_reference" -> if (roots.size != 1) response("ambiguous", "Choose an exact owning project.") else {
                val file = w.files.find {
                    w.root(it) == roots.first() && (it.virtualFile.path == text("file") || roots.first()
                        .relative(it.virtualFile) == text("file"))
                }
                val offset = args["offset"]?.takeIf { it.isJsonPrimitive }?.let { dbtResult { it.asInt }.getOrNull() }
                val resolved =
                    (if (file != null && offset != null) DbtNavigation.at(file, offset).flatMap { it.candidates }
                        .filterIsInstance<DbtSymbolElement>().map { it.symbol } else emptyList()) +
                            (if (offset != null) emptyList() else w.declarations(
                                DbtSymbol.RefKinds,
                                text("reference"),
                                roots.first()
                            ))
                val unique = resolved.distinctBy { DbtResourceIdentity.key(w, it) to it.owner }
                response(if (unique.size == 1) "ok" else if (unique.isEmpty()) "missing" else "ambiguous").apply {
                    add(
                        "resources",
                        array(unique.take(100).map { resource(w, it) })
                    )
                }
            }

            "get_documentation" -> resourceResult { s ->
                val data = DbtResourceData.get(w, s)
                response(if (s.isStale || data.columns.size > 500) "partial" else "ok").apply {
                    add(
                        "resource",
                        resource(w, s)
                    ); addProperty("description", data.description.take(20000)); add(
                    "columns",
                    array(
                        data.columns.take(500).map {
                            obj(
                                "name" to it.name,
                                "type" to it.dataType,
                                "description" to it.description.take(1000)
                            )
                        })
                )
                }
            }

            "get_lineage" -> resourceResult { s ->
                val edges = DbtLineage.edges(w).filter { e ->
                    if (text("direction") == "downstream") DbtResourceIdentity.key(
                        w,
                        e.to
                    ) == DbtResourceIdentity.key(w, s) else DbtResourceIdentity.key(
                        w,
                        e.from
                    ) == DbtResourceIdentity.key(w, s)
                }
                response("partial", "Static edges only; dynamic consumers are unresolved.").apply {
                    add(
                        "edges",
                        array(edges.take(1000).map { obj("from" to resource(w, it.from), "to" to resource(w, it.to)) })
                    )
                }
            }

            "get_column_lineage" -> resourceResult { s ->
                val edges = DbtColumnLineage.edges(w).filter { e ->
                    if (text("direction") == "downstream") e.input.owner == s.relationName && e.input.namespace == s.namespace && e.input.version == s.version && w.owner(
                        e.input
                    ) == w.owner(s) && e.input.name == text("column")
                    else DbtResourceIdentity.key(w, e.resource) == DbtResourceIdentity.key(
                        w,
                        s
                    ) && e.column == text("column")
                }
                response(
                    "partial",
                    "Static column derivations and bounded paths; absent edges do not prove independence."
                ).apply {
                    add(
                        "edges",
                        array(
                            edges.take(1000).map { e ->
                                obj(
                                    "column" to e.column,
                                    "expression" to e.expression.take(4000),
                                    "input_column" to e.input.name,
                                    "resource" to resource(w, e.resource),
                                    "input" to resource(w, e.input),
                                    "complete" to e.complete,
                                    "direct" to e.direct
                                )
                            })
                    )
                    val paths = columnPaths(w, s, text("column"), text("direction") == "downstream"); add(
                    "paths",
                    paths["paths"]
                ); add("paths_truncated", paths["truncated"])
                }
            }

            "resolve_selection" -> if (roots.size != 1) response("ambiguous", "Choose an exact project.") else {
                val result = DbtSelection.resolve(w, roots.first(), text("selection"), text("exclude"))
                response(if (result.complete && result.resources.size <= 1000) "ok" else "partial").apply {
                    add(
                        "resources",
                        array(result.resources.take(1000).map { resource(w, it) })
                    ); add("problems", strings(result.problems))
                }
            }

            else -> response("unsupported", "Unknown tool.")
        }
    }

    /** Paths reuse proven input identities; unresolved origins stop a path instead of guessing a relation. */
    private fun columnPaths(w: DbtWorkspace, start: DbtSymbol, column: String, downstream: Boolean): JsonObject {
        val edges = DbtColumnLineage.edges(w);
        val relations = w.symbols.filter { it.kind in DbtSymbol.RelationKinds }
            .sortedWith(compareBy({ it.metadata }, { it.isProperty })).distinctBy { DbtResourceIdentity.key(w, it) }
        val paths = JsonArray();
        var work = 0;
        var truncated = false
        fun node(s: DbtSymbol, name: String): JsonObject = resource(w, s).apply { addProperty("column", name) }
        fun identity(s: DbtSymbol, name: String): String =
            DbtResourceIdentity.key(w, s) + "|" + name.lowercase(java.util.Locale.ROOT)

        fun inputMatches(input: DbtSymbol, relation: DbtSymbol, name: String): Boolean =
            input.owner == relation.relationName && input.namespace == relation.namespace && input.version == relation.version && w.owner(
                input
            ) == w.owner(relation) && input.name.equals(name, true)

        fun finish(nodes: List<JsonObject>, complete: Boolean) {
            if (paths.size() < 50) paths.add(obj("columns" to array(nodes), "complete" to complete)) else truncated =
                true
        }

        fun visit(current: DbtSymbol, name: String, nodes: List<JsonObject>, seen: Set<String>, complete: Boolean) {
            ProgressManager.checkCanceled(); work++
            if (paths.size() >= 50 || work > 1000) truncated = true
            else if (nodes.size >= 20) {
                truncated = true; finish(nodes, false)
            } else {
                val next = edges.filter { e ->
                    if (downstream) inputMatches(
                        e.input,
                        current,
                        name
                    ) else DbtResourceIdentity.key(w, e.resource) == DbtResourceIdentity.key(
                        w,
                        current
                    ) && e.column.equals(name, true)
                }
                if (next.isEmpty()) finish(nodes, complete && current.kind == "source_table" && !current.isStale)
                else next.take(100).forEach { edge ->
                    val targets = if (downstream) listOf(edge.resource to edge.column) else relations.filter {
                        inputMatches(
                            edge.input,
                            it,
                            edge.input.name
                        )
                    }.map { it to edge.input.name }
                    if (targets.size != 1) finish(nodes + node(edge.input, edge.input.name), false)
                    else {
                        val (target, targetColumn) = targets.first();
                        val id = identity(target, targetColumn)
                        if (id in seen) finish(nodes + node(target, targetColumn), false) else visit(
                            target,
                            targetColumn,
                            nodes + node(target, targetColumn),
                            seen + id,
                            complete && edge.complete && !target.isStale
                        )
                    }
                }
                if (next.size > 100) truncated = true
            }
        }
        visit(
            start,
            column,
            listOf(node(start, column)),
            setOf(identity(start, column)),
            true
        ); return obj("paths" to paths, "truncated" to truncated)
    }
}

/**
 * Opt-in, authenticated loopback MCP transport exposing read-only project knowledge through JSON responses.
 * A fresh bearer token is created per server lifetime; requests with Origin headers are rejected.
 * Requests have bounded input/output and a cancellation deadline. No tool launches dbt, executes SQL or writes files.
 */
@Service(Service.Level.PROJECT)
class DbtAgent(private val project: Project) : Disposable {
    private var server: HttpServer? = null;
    private var executor: ExecutorService? = null;
    private var token = "";
    private var timer: ScheduledExecutorService? = null
    private val active = ConcurrentHashMap.newKeySet<ProgressIndicator>();
    private var disposed = false
    internal val activeRequestCount: Int get() = active.size
    val endpoint: Pair<String, String>? get() = synchronized(this) { server?.let { "http://127.0.0.1:${it.address.port}/mcp" to token } }
    /** Starts the opted-in project server or returns its existing endpoint/token; transport failures return a failure. */
    @Synchronized
    fun start(): Result<Pair<String, String>> {
        if (disposed || project.isDisposed) return Result.failure(IllegalArgumentException("Project is disposed."))
        if (!project.getService(DbtConsumersSettings::class.java).getState().agentEnabled) return Result.failure(
            IllegalArgumentException("Enable the local MCP server in Settings > dbt Workspace > Integrations first.")
        )
        endpoint?.let { return Result.success(it) }
        return try {
            val bytes = ByteArray(32); SecureRandom().nextBytes(bytes); token =
                java.util.HexFormat.of().formatHex(bytes)
            val s = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 8); server = s
            val threads = ThreadFactory { r -> Thread(r, "dbt-local-tools").apply { isDaemon = true } }
            executor = ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                ArrayBlockingQueue<Runnable>(8),
                threads,
                ThreadPoolExecutor.AbortPolicy()
            )
            timer = Executors.newSingleThreadScheduledExecutor(threads); s.executor =
                executor; s.createContext("/") { boundedRequest(it) }; s.start(); DbtAgentServiceView.changed(project)
            endpoint?.let { Result.success(it) }
                ?: Result.failure(IllegalArgumentException("Local MCP server failed to start."))
        } catch (error: Exception) {
            if (error is com.intellij.openapi.diagnostic.ControlFlowException || error is CancellationException || error is InterruptedException) throw error
            stop(); Result.failure(IllegalArgumentException("Local loopback transport is unavailable."))
        }
    }

    private fun boundedRequest(exchange: HttpExchange) {
        val indicator = EmptyProgressIndicator();
        val worker = Thread.currentThread();
        val scheduler = synchronized(this) { timer }
        if (scheduler == null) {
            exchange.close(); return
        }
        active.add(indicator)
        val deadline =
            scheduler.schedule({ indicator.cancel(); exchange.close(); worker.interrupt() }, 30, TimeUnit.SECONDS)
        try {
            ProgressManager.getInstance().runProcess(Runnable { handleRequest(exchange) }, indicator)
        } finally {
            deadline.cancel(false); active.remove(indicator); Thread.interrupted(); exchange.close()
        }
    }

    private fun handleRequest(exchange: HttpExchange) {
        fun send(code: Int, body: String?, vararg headers: Pair<String, String>) {
            headers.forEach { (k, v) ->
                exchange.responseHeaders.set(
                    k,
                    v
                )
            }; exchange.responseHeaders.set("Cache-Control", "no-store")
            if (body == null) exchange.sendResponseHeaders(code, -1) else {
                val bytes = body.toByteArray(StandardCharsets.UTF_8); exchange.responseHeaders.set(
                    "Content-Type",
                    "application/json; charset=utf-8"
                )
                exchange.sendResponseHeaders(code, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
            }
        }

        fun fail(code: Int, rpcCode: Int, message: String, vararg headers: Pair<String, String>) {
            send(code, DbtAgentProtocol.errorResponse(null, rpcCode, message).toString(), *headers)
        }
        try {
            val auth = exchange.requestHeaders.getFirst("Authorization").orEmpty();
            val expected = synchronized(this) { "Bearer " + token }
            when {
                exchange.requestURI.path != "/mcp" -> fail(404, -32600, "Unknown endpoint; use /mcp.")
                exchange.requestMethod != "POST" -> fail(
                    405,
                    -32600,
                    "Only POST is supported; this server does not open SSE streams or sessions.",
                    "Allow" to "POST"
                )

                exchange.requestHeaders.containsKey("Origin") -> fail(
                    403,
                    -32600,
                    "Requests with an Origin header are rejected."
                )

                !MessageDigest.isEqual(
                    auth.toByteArray(StandardCharsets.UTF_8),
                    expected.toByteArray(StandardCharsets.UTF_8)
                ) -> fail(401, -32600, "Missing or invalid bearer token.", "WWW-Authenticate" to "Bearer")

                else -> {
                    val input = exchange.requestBody.readNBytes(65537)
                    if (input.size > 65536) fail(413, -32600, "Request exceeds the 64 KiB limit.") else {
                        val reply =
                            handle({ exchange.requestHeaders.getFirst(it) }, String(input, StandardCharsets.UTF_8));
                        val text = reply.body?.toString()
                        if (text != null && text.length > DbtAgentProtocol.ResponseLimit) fail(
                            500,
                            -32603,
                            "Response exceeds the 2 MB limit; narrow the request."
                        ) else send(reply.status, text)
                    }
                }
            }
        } catch (error: Exception) {
            if (error is com.intellij.openapi.diagnostic.ControlFlowException || error is CancellationException || error is InterruptedException) throw error
            try {
                fail(500, -32603, "Request failed or was canceled.")
            } catch (failure: Exception) {
                if (failure is com.intellij.openapi.diagnostic.ControlFlowException || failure is CancellationException || failure is InterruptedException) throw failure
            }
        } finally {
            exchange.close()
        }
    }

    /**
     * Dispatches a transport-authenticated JSON-RPC body using its supported protocol/header rules.
     * Authentication and request-size checks belong to the HTTP boundary; a null reply body means HTTP 202.
     */
    internal fun handle(header: (String) -> String?, text: String): Reply {
        val message = dbtResult { JsonParser.parseString(text) }.getOrNull()
        if (message == null) return Reply(400, DbtAgentProtocol.errorResponse(null, -32700, "Parse error."))
        if (message.isJsonArray) {
            val version = header("MCP-Protocol-Version")?.let(::decodeHeader) ?: DbtAgentProtocol.DefaultLegacyVersion;
            val items = message.asJsonArray.toList()
            if (version != DbtAgentProtocol.DefaultLegacyVersion || items.isEmpty() || items.any { !it.isJsonObject }) return Reply(
                400,
                DbtAgentProtocol.errorResponse(
                    null,
                    -32600,
                    "Batches are accepted only for protocol version 2025-03-26 and must contain JSON-RPC objects."
                )
            )
            val responses = items.mapNotNull { legacy(it.asJsonObject, version).body }
            return if (responses.isEmpty()) Reply(202, null) else Reply(200, array(responses))
        }
        if (!message.isJsonObject) return Reply(
            400,
            DbtAgentProtocol.errorResponse(null, -32600, "Invalid JSON-RPC message.")
        )
        val request = message.asJsonObject;
        val meta = DbtJson.child(DbtJson.child(request, "params") ?: JsonObject(), "_meta");
        val headerVersion = header("MCP-Protocol-Version")?.let(::decodeHeader)
        return if (meta?.has(ProtocolVersionKey) == true || headerVersion in DbtAgentProtocol.ModernVersions) modern(
            header,
            request
        ) else legacy(request, headerVersion ?: DbtAgentProtocol.DefaultLegacyVersion)
    }

    private fun modern(header: (String) -> String?, request: JsonObject): Reply {
        val id = request["id"];
        val method = DbtJson.text(request, "method");
        val params = DbtJson.child(request, "params") ?: JsonObject();
        val meta = DbtJson.child(params, "_meta")
        val headerVersion = header("MCP-Protocol-Version")?.let(::decodeHeader);
        val requested = meta?.let { DbtJson.text(it, ProtocolVersionKey) } ?: headerVersion.orEmpty()
        if (method == null) return clientMessage(request)
        if (requested !in DbtAgentProtocol.ModernVersions) return unsupportedVersion(id, requested)
        if (id != null && (meta == null || DbtJson.text(meta, ProtocolVersionKey) == null || DbtJson.child(
                meta,
                ClientCapabilitiesKey
            ) == null)
        ) return Reply(
            400,
            DbtAgentProtocol.errorResponse(
                id,
                -32602,
                "Request params._meta must contain $ProtocolVersionKey and $ClientCapabilitiesKey."
            )
        )
        if (headerVersion != requested || header("Mcp-Method")?.let(::decodeHeader) != method || method == "tools/call" && header(
                "Mcp-Name"
            )?.let(::decodeHeader) != DbtJson.text(params, "name")
        ) return Reply(
            400,
            DbtAgentProtocol.errorResponse(
                id,
                -32020,
                "MCP-Protocol-Version, Mcp-Method and Mcp-Name headers must match the request body."
            )
        )
        if (id == null) return Reply(202, null)
        if (disposed || project.isDisposed) return Reply(
            200,
            DbtAgentProtocol.errorResponse(id, -32603, "Project is disposed.")
        )
        fun complete(result: JsonObject): Reply {
            result.addProperty("resultType", "complete"); return Reply(200, success(id, result))
        }

        fun cacheable(result: JsonObject): JsonObject =
            result.apply { addProperty("ttlMs", 300000); addProperty("cacheScope", "private") }
        return when (method) {
            "server/discover" -> complete(
                cacheable(
                    DbtAgentProtocol.obj(
                        "supportedVersions" to strings(DbtAgentProtocol.SupportedVersions),
                        "capabilities" to toolCapabilities,
                        "_meta" to DbtAgentProtocol.obj(ServerInfoKey to DbtAgentProtocol.serverInfo),
                        "instructions" to DbtAgentProtocol.Instructions
                    )
                )
            )

            "tools/list" -> complete(cacheable(DbtAgentProtocol.obj("tools" to DbtAgentProtocol.schemas)))
            "tools/call" -> toolsCall(params).fold(::complete) { error ->
                val e = error as RpcFailure; Reply(
                200,
                DbtAgentProtocol.errorResponse(id, e.code, e.message.orEmpty())
            )
            }

            else -> Reply(404, DbtAgentProtocol.errorResponse(id, -32601, "Unknown method."))
        }
    }

    private fun legacy(request: JsonObject, version: String): Reply {
        val id = request["id"];
        val method = DbtJson.text(request, "method")
        if (method == null) return clientMessage(request)
        if (version !in DbtAgentProtocol.LegacyVersions) return unsupportedVersion(id, version)
        if (id == null) return Reply(202, null)
        if (disposed || project.isDisposed) return Reply(
            200,
            DbtAgentProtocol.errorResponse(id, -32603, "Project is disposed.")
        )
        val params = DbtJson.child(request, "params") ?: JsonObject()
        fun ok(result: JsonObject) = Reply(200, success(id, result))
        return when (method) {
            "initialize" -> {
                val requested =
                    DbtJson.text(params, "protocolVersion")?.takeIf { it in DbtAgentProtocol.LegacyVersions }
                        ?: DbtAgentProtocol.LegacyVersions.first()
                ok(
                    DbtAgentProtocol.obj(
                        "protocolVersion" to requested,
                        "instructions" to DbtAgentProtocol.Instructions,
                        "capabilities" to toolCapabilities,
                        "serverInfo" to DbtAgentProtocol.serverInfo
                    )
                )
            }

            "ping" -> ok(JsonObject()); "tools/list" -> ok(DbtAgentProtocol.obj("tools" to DbtAgentProtocol.schemas))
            "tools/call" -> toolsCall(params).fold(::ok) { error ->
                val e = error as RpcFailure; Reply(
                200,
                DbtAgentProtocol.errorResponse(id, e.code, e.message.orEmpty())
            )
            }

            else -> Reply(200, DbtAgentProtocol.errorResponse(id, -32601, "Unknown method."))
        }
    }

    private class RpcFailure(val code: Int, message: String) : IllegalArgumentException(message)

    /** Runs one read tool. Unknown tools are protocol errors; invalid arguments and failed lookups are tool errors. */
    private fun toolsCall(params: JsonObject): Result<JsonObject> {
        val name = DbtJson.text(params, "name").orEmpty();
        val args = DbtJson.child(params, "arguments") ?: JsonObject()
        val required = DbtAgentProtocol.Tools.find { it.first == name }?.third ?: return Result.failure(
            RpcFailure(
                -32602,
                "Unknown tool: $name"
            )
        )
        val invalid = args.entrySet().any { (key, v) ->
            key !in DbtAgentProtocol.allowedArguments(name) || !v.isJsonPrimitive || if (key == "offset") !v.asJsonPrimitive.isNumber || dbtResult { v.asBigDecimal.intValueExact() }.getOrNull()
                ?.let { it < 0 } != false
            else !v.asJsonPrimitive.isString || v.asString.length > 16384
        }
        val badDirection =
            args["direction"]?.let { !it.isJsonPrimitive || it.asString !in setOf("upstream", "downstream") } == true
        if (invalid || badDirection || required.any {
                DbtJson.text(args, it)?.isNotEmpty() != true
            }) return Result.success(
            toolResult(
                null,
                "Arguments do not match the tool input schema or exceed request limits.",
                true
            )
        )
        val output = DbtUi.read { DbtAgentProtocol.call(DbtWorkspace.get(project), name, args) }
        val failed = DbtJson.text(output, "status") in setOf("error", "unsupported", "disposed");
        val result = toolResult(output, output.toString(), failed)
        return Result.success(
            if (result.toString().length > DbtAgentProtocol.ResponseLimit - 4096) toolResult(
                null,
                "The result exceeds the 2 MB response limit. Narrow the request.",
                true
            ) else result
        )
    }

    private fun clientMessage(message: JsonObject): Reply =
        if (message.has("id") && (message.has("result") || message.has("error"))) Reply(202, null)
        else Reply(400, DbtAgentProtocol.errorResponse(message["id"], -32600, "Invalid JSON-RPC message."))

    /** Legacy JSON-RPC response, or an empty object for notifications. */
    internal fun rpc(request: JsonObject): JsonObject =
        legacy(request, DbtAgentProtocol.DefaultLegacyVersion).body?.asJsonObject ?: JsonObject()

    /** Cancels active reads, closes the transport/executors and invalidates the endpoint's temporary authorization. */
    @Synchronized
    fun stop() {
        active.forEach { it.cancel() }; server?.stop(0); executor?.shutdownNow(); timer?.shutdownNow(); server =
            null; executor = null; timer = null; token = ""
        if (!disposed) DbtAgentServiceView.changed(project)
    }

    @Synchronized
    override fun dispose() {
        disposed = true; stop()
    }

    companion object {
        /** An HTTP status and JSON body; no body means 202 Accepted. */
        internal data class Reply(val status: Int, val body: JsonElement?)

        const val ProtocolVersionKey = "io.modelcontextprotocol/protocolVersion"
        const val ClientCapabilitiesKey = "io.modelcontextprotocol/clientCapabilities"
        const val ServerInfoKey = "io.modelcontextprotocol/serverInfo"
        fun success(id: JsonElement, result: JsonObject): JsonObject =
            DbtAgentProtocol.obj("jsonrpc" to "2.0", "id" to id, "result" to result)

        internal fun unsupportedVersion(id: JsonElement?, requested: String): Reply = Reply(
            400,
            DbtAgentProtocol.errorResponse(
                id,
                -32022,
                "Unsupported protocol version.",
                DbtAgentProtocol.obj(
                    "supported" to strings(DbtAgentProtocol.SupportedVersions),
                    "requested" to requested
                )
            )
        )

        val toolCapabilities: JsonObject get() = DbtAgentProtocol.obj("tools" to JsonObject())
        fun toolResult(structured: JsonObject?, text: String, isError: Boolean): JsonObject {
            val result = DbtAgentProtocol.obj(
                "content" to array(
                    listOf(
                        DbtAgentProtocol.obj(
                            "type" to "text",
                            "text" to text
                        )
                    )
                )
            ); structured?.let { result.add("structuredContent", it) }; if (isError) result.addProperty(
                "isError",
                true
            ); return result
        }

        /** Header values may be sent as =?base64?<value>?= when they contain non-ASCII characters. */
        fun decodeHeader(value: String): String {
            val v =
                value.trim(); return if (v.startsWith("=?base64?") && v.endsWith("?=") && v.length >= 11) dbtResult {
                String(
                    java.util.Base64.getDecoder().decode(v.substring(9, v.length - 2)),
                    StandardCharsets.UTF_8
                )
            }.getOrDefault(v) else v
        }
    }
}

class DbtAgentAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        event.project?.let { project ->
            ServiceViewManager.getInstance(project)
                .select(project.getService(DbtAgent::class.java), DbtAgentServiceView::class.java, true, true)
        }
    }
}
