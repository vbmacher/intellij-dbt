package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project._
import com.github.vbmacher.intellij.dbt.project.DbtJson.{array, strings}
import com.github.vbmacher.intellij.dbt.lineage.{DbtColumnLineage, DbtLineage, DbtSelection}
import com.github.vbmacher.intellij.dbt.navigation.DbtNavigation
import com.google.gson.{JsonArray, JsonElement, JsonObject, JsonParser}
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.{AnAction, AnActionEvent}
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import java.net.{InetAddress, InetSocketAddress}
import java.nio.charset.StandardCharsets
import java.security.{MessageDigest, SecureRandom}
import java.util.concurrent.{Executors, ExecutorService, ScheduledExecutorService, ThreadPoolExecutor, ArrayBlockingQueue, TimeUnit}
import com.intellij.openapi.progress.{EmptyProgressIndicator, ProgressIndicator, ProgressManager}
import scala.jdk.CollectionConverters._
import javax.swing.JScrollPane
import scala.util.control.NonFatal

private[dbt] object DbtAgentProtocol {
  val Version = "1.0"
  /** Modern versions carry protocol metadata in every request; legacy versions negotiate it with `initialize`. */
  val ModernVersions: Vector[String] = Vector("2026-07-28")
  val LegacyVersions: Vector[String] = Vector("2025-11-25", "2025-06-18", "2025-03-26")
  val SupportedVersions: Vector[String] = ModernVersions ++ LegacyVersions
  val DefaultLegacyVersion = "2025-03-26"
  val Instructions = "Read-only access to the dbt projects open in the IDE. Call list_projects first and pass its project " +
    "directory to the other tools. No tool runs dbt, executes SQL or changes files."
  val ResponseLimit = 2000000
  val Tools: Vector[(String, String, Vector[String])] = Vector(
    ("list_projects", "List owning project identities and source locations", Vector.empty),
    ("list_resources", "List resource identities in one owning project", Vector("project")),
    ("resolve_reference", "Resolve a source reference at an offset or by exact resource name", Vector("project", "reference")),
    ("get_documentation", "Source-first documentation and columns with provenance", Vector("resource_id", "project")),
    ("get_lineage", "Static resource edges with exact source identities", Vector("resource_id", "project")),
    ("get_column_lineage", "Static column derivations and partial analysis flags", Vector("resource_id", "project", "column")),
    ("resolve_selection", "Preview a local selector with unresolved terms", Vector("project", "selection")))
  private val OptionalArguments: Map[String, Vector[String]] = Map(
    "resolve_reference" -> Vector("file", "offset"), "get_lineage" -> Vector("direction"),
    "get_column_lineage" -> Vector("direction"), "resolve_selection" -> Vector("exclude"))

  def allowedArguments(tool: String): Vector[String] = Tools.find(_._1 == tool).toVector.flatMap(_._3) ++ OptionalArguments.getOrElse(tool, Vector.empty)

  def obj(values: (String, Any)*): JsonObject = DbtJson.obj(values: _*)

  def error(code: Int, message: String): JsonObject = obj("message" -> message, "code" -> code)

  /** A JSON-RPC error response; `id` is omitted when the request id could not be read. */
  def errorResponse(id: Option[JsonElement], code: Int, message: String, data: Option[JsonElement] = None): JsonObject = {
    val response = obj("jsonrpc" -> "2.0");
    id.foreach(response.add("id", _))
    val e = error(code, message);
    data.foreach(e.add("data", _));
    response.add("error", e);
    response
  }

  def serverInfo: JsonObject = obj("name" -> "dbt-workspace", "version" -> Version)

  /** The common `mcpServers` JSON block that MCP clients accept for remote HTTP servers. */
  def clientConfiguration(url: String, token: String): String = {
    val server = obj("type" -> "http", "url" -> url, "headers" -> obj("Authorization" -> ("Bearer " + token)))
    new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(obj("mcpServers" -> obj("dbt-workspace" -> server)))
  }

  def schemas: JsonArray = array(Tools.map { case (name, description, required) =>
    val properties = new JsonObject
    allowedArguments(name).filterNot(_ == "offset").foreach { name =>
      val value = obj("type" -> "string", "maxLength" -> 16384)
      if (name == "direction") value.add("enum", strings(Vector("upstream", "downstream")))
      properties.add(name, value)
    }
    if (allowedArguments(name).contains("offset")) properties.add("offset", obj("type" -> "integer", "minimum" -> 0, "maximum" -> Int.MaxValue))
    obj("name" -> name, "description" -> description,
      "inputSchema" -> obj("type" -> "object", "properties" -> properties, "required" -> strings(required), "additionalProperties" -> false),
      "annotations" -> obj("readOnlyHint" -> true, "destructiveHint" -> false))
  })

  def resource(w: DbtWorkspace, s: DbtSymbol): JsonObject = {
    val result = obj("resource_id" -> s.uniqueId, "project" -> w.owner(s).map(_.directory.getPath).getOrElse(""),
      "kind" -> s.kind, "name" -> s.name, "owner" -> s.owner, "package" -> s.namespace, "version" -> s.version.getOrElse(""),
      "path" -> s.file.getVirtualFile.getPath, "provenance" -> (if (s.metadata) "local artifact" else "current source"))
    if (DbtSymbol.ColumnKinds(s.kind))
      w.relations(s).sortBy(_.isProperty).headOption.foreach(r => result.addProperty("relation_resource_id", r.uniqueId))
    result.addProperty("offset", s.range.getStartOffset)
    val line = s.file.getText.take(s.range.getStartOffset).count(_ == '\n') + 1
    result.addProperty("line", line);
    result.addProperty("stale", s.isStale);
    result
  }

  private def argument(args: JsonObject, key: String): String = Option(args.get(key)).filter(_.isJsonPrimitive).map(_.getAsString).getOrElse("")

  private def response(status: String, message: String = ""): JsonObject = obj("status" -> status, "message" -> message, "schema_version" -> Version)

  def call(w: DbtWorkspace, tool: String, args: JsonObject): JsonObject = {
    def text(key: String): String = argument(args, key)

    val roots = w.roots.filter(r => r.directory.getPath == text("project") || r.name == text("project"))
    val candidates = w.symbols.filter(s => w.owner(s).exists(roots.contains) && s.uniqueId == text("resource_id"))
      .sortBy(s => (s.metadata, s.isProperty)).distinctBy(s => (DbtResourceIdentity.key(w, s), s.owner))

    def resourceResult(f: DbtSymbol => JsonObject): JsonObject = {
      if (roots.size != 1) response("ambiguous", "Choose an exact project directory from list_projects.")
      else if (candidates.size != 1) {
        val r = response(if (candidates.isEmpty) "missing" else "ambiguous", "Resource identity did not resolve uniquely.");
        r.add("candidates", array(candidates.map(resource(w, _))));
        r
      }
      else f(candidates.head)
    }

    if (w.project.isDisposed) response("disposed", "Project is closed.")
    else tool match {
      case "list_projects" =>
        val r = response(if (w.roots.size > 100) "partial" else "ok");
        r.add("projects", array(w.roots.take(100).map(root => obj("name" -> root.name,
          "project" -> root.directory.getPath, "path" -> (root.directory.getPath + "/dbt_project.yml")))));
        r
      case "list_resources" =>
        if (roots.size != 1) response("ambiguous", "Choose an exact project directory from list_projects.")
        else {
          val resources = w.symbols.filter(s => w.owner(s).contains(roots.head) && s.kind != "environment" && s.kind != "env" &&
            (s.isCode || s.isProperty || s.metadata || DbtResourceData.GraphKinds(s.kind))).sortBy(s => (s.metadata, s.isProperty)).distinctBy(_.uniqueId)
          val r = response(if (resources.size > 1000) "partial" else "ok");
          r.add("resources", array(resources.take(1000).map(resource(w, _))));
          r
        }
      case "resolve_reference" =>
        if (roots.size != 1) response("ambiguous", "Choose an exact owning project.")
        else {
          val file = w.files.find(f => w.root(f).contains(roots.head) && (f.getVirtualFile.getPath == text("file") || roots.head.relative(f.getVirtualFile).contains(text("file"))))
          val offset = Option(args.get("offset")).filter(_.isJsonPrimitive).flatMap(v => scala.util.Try(v.getAsInt).toOption)
          val resolved = (for (f <- file.toVector; at <- offset.toVector; ref <- DbtNavigation.at(f, at);
                               e <- ref.candidates.collect { case s: DbtSymbolElement => s.symbol }) yield e) ++
            (if (offset.nonEmpty) Vector.empty else w.declarations(DbtSymbol.RefKinds, text("reference"), roots.head))
          val unique = resolved.distinctBy(s => (DbtResourceIdentity.key(w, s), s.owner))
          val r = response(if (unique.size == 1) "ok" else if (unique.isEmpty) "missing" else "ambiguous")
          r.add("resources", array(unique.take(100).map(resource(w, _))));
          r
        }
      case "get_documentation" => resourceResult { s =>
        val data = DbtResourceData.get(w, s)
        val r = response(if (s.isStale || data.columns.size > 500) "partial" else "ok")
        r.add("resource", resource(w, s));
        r.addProperty("description", data.description.take(20000))
        r.add("columns", array(data.columns.take(500).map(c => obj("name" -> c.name, "type" -> c.dataType, "description" -> c.description.take(1000)))))
        r
      }
      case "get_lineage" => resourceResult { s =>
        val edges = DbtLineage.edges(w).filter(e => if (text("direction") == "downstream") DbtResourceIdentity.key(w, e.to) == DbtResourceIdentity.key(w, s)
        else DbtResourceIdentity.key(w, e.from) == DbtResourceIdentity.key(w, s))
        val r = response("partial", "Static edges only; dynamic consumers are unresolved.")
        r.add("edges", array(edges.take(1000).map(e => obj("from" -> resource(w, e.from), "to" -> resource(w, e.to)))));
        r
      }
      case "get_column_lineage" => resourceResult { s =>
        val edges = DbtColumnLineage.edges(w).filter(e => if (text("direction") == "downstream")
          e.input.owner == s.relationName && e.input.namespace == s.namespace && e.input.version == s.version && w.owner(e.input) == w.owner(s) && e.input.name == text("column")
        else DbtResourceIdentity.key(w, e.resource) == DbtResourceIdentity.key(w, s) && e.column == text("column"))
        val r = response("partial", "Static column derivations and bounded paths; absent edges do not prove independence.")
        r.add("edges", array(edges.take(1000).map(e => obj("column" -> e.column, "expression" -> e.expression.take(4000), "input_column" -> e.input.name,
          "resource" -> resource(w, e.resource), "input" -> resource(w, e.input), "complete" -> e.complete, "direct" -> e.direct))))
        val paths = columnPaths(w, s, text("column"), text("direction") == "downstream")
        r.add("paths", paths.get("paths"));
        r.add("paths_truncated", paths.get("truncated"));
        r
      }
      case "resolve_selection" =>
        if (roots.size != 1) response("ambiguous", "Choose an exact project.")
        else {
          val result = DbtSelection.resolve(w, roots.head, text("selection"), text("exclude"))
          val r = response(if (result.complete && result.resources.size <= 1000) "ok" else "partial");
          r.add("resources", array(result.resources.take(1000).map(resource(w, _))))
          r.add("problems", strings(result.problems));
          r
        }
      case _ => response("unsupported", "Unknown tool.")
    }
  }

  /** Paths reuse proven input identities; unresolved origins stop a path instead of guessing a relation. */
  private def columnPaths(w: DbtWorkspace, start: DbtSymbol, column: String, downstream: Boolean): JsonObject = {
    val edges = DbtColumnLineage.edges(w)
    val relations = w.symbols.filter(s => DbtSymbol.RelationKinds(s.kind)).sortBy(s => (s.metadata, s.isProperty))
      .distinctBy(DbtResourceIdentity.key(w, _))
    val paths = new JsonArray
    var work = 0
    var truncated = false

    def node(s: DbtSymbol, name: String): JsonObject = {
      val result = resource(w, s);
      result.addProperty("column", name);
      result
    }

    def identity(s: DbtSymbol, name: String): String = DbtResourceIdentity.key(w, s) + "|" + name.toLowerCase(java.util.Locale.ROOT)

    def inputMatches(input: DbtSymbol, relation: DbtSymbol, name: String): Boolean =
      input.owner == relation.relationName && input.namespace == relation.namespace && input.version == relation.version &&
        w.owner(input) == w.owner(relation) && input.name.equalsIgnoreCase(name)

    def finish(nodes: Vector[JsonObject], complete: Boolean): Unit = if (paths.size() < 50) {
      paths.add(obj("columns" -> array(nodes), "complete" -> complete))
    } else truncated = true

    def visit(current: DbtSymbol, name: String, nodes: Vector[JsonObject], seen: Set[String], complete: Boolean): Unit = {
      ProgressManager.checkCanceled()
      work += 1
      if (paths.size() >= 50 || work > 1000) truncated = true
      else if (nodes.size >= 20) {
        truncated = true;
        finish(nodes, complete = false)
      }
      else {
        val next = edges.filter(e => if (downstream) inputMatches(e.input, current, name)
        else DbtResourceIdentity.key(w, e.resource) == DbtResourceIdentity.key(w, current) && e.column.equalsIgnoreCase(name))
        if (next.isEmpty) finish(nodes, complete && current.kind == "source_table" && !current.isStale)
        else next.take(100).foreach { edge =>
          val targets = if (downstream) Vector(edge.resource -> edge.column) else
            relations.filter(r => inputMatches(edge.input, r, edge.input.name)).map(_ -> edge.input.name)
          if (targets.size != 1) finish(nodes :+ node(edge.input, edge.input.name), complete = false)
          else {
            val (target, targetColumn) = targets.head
            val id = identity(target, targetColumn)
            if (seen(id)) finish(nodes :+ node(target, targetColumn), complete = false)
            else visit(target, targetColumn, nodes :+ node(target, targetColumn), seen + id, complete && edge.complete && !target.isStale)
          }
        }
        if (next.size > 100) truncated = true
      }
    }

    visit(start, column, Vector(node(start, column)), Set(identity(start, column)), complete = true)
    obj("paths" -> paths, "truncated" -> truncated)
  }

}

/** Authenticated loopback MCP server (Streamable HTTP, JSON responses only). There are deliberately no execution tools. */
@Service(Array(Service.Level.PROJECT))
final class DbtAgent(project: Project) extends Disposable {
  private var server: HttpServer = _
  private var executor: ExecutorService = _
  private var token = ""
  private var timer: ScheduledExecutorService = _
  private val active = java.util.concurrent.ConcurrentHashMap.newKeySet[ProgressIndicator]()
  private var disposed = false

  private[ide] def activeRequestCount: Int = active.size()

  def endpoint: Option[(String, String)] = synchronized {
    Option(server).map(s => ("http://127.0.0.1:" + s.getAddress.getPort + "/mcp", token))
  }

  def start(): Either[String, (String, String)] = synchronized {
    if (disposed || project.isDisposed) Left("Project is disposed.")
    else if (!project.getService(classOf[DbtConsumersSettings]).getState.agentEnabled) Left("Enable the local MCP server in Settings > dbt Workspace > Integrations first.")
    else endpoint match {
      case Some(value) => Right(value)
      case None => try {
        val bytes = new Array[Byte](32);
        new SecureRandom().nextBytes(bytes);
        token = java.util.HexFormat.of().formatHex(bytes)
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 8)
        val threads: java.util.concurrent.ThreadFactory = (r: Runnable) => {
          val t = new Thread(r, "dbt-local-tools");
          t.setDaemon(true);
          t
        }
        executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue[Runnable](8), threads, new ThreadPoolExecutor.AbortPolicy)
        timer = Executors.newSingleThreadScheduledExecutor(threads)
        server.setExecutor(executor)
        server.createContext("/", new HttpHandler {
          override def handle(exchange: HttpExchange): Unit = boundedRequest(exchange)
        })
        server.start();
        endpoint.toRight("Local MCP server failed to start.")
      } catch {
        case NonFatal(_) => stop(); Left("Local loopback transport is unavailable.")
      }
    }
  }

  private def boundedRequest(exchange: HttpExchange): Unit = {
    val indicator = new EmptyProgressIndicator()
    val worker = Thread.currentThread()
    val scheduler = synchronized {
      timer
    }
    if (scheduler == null) {
      exchange.close();
      return
    }
    active.add(indicator)
    val deadline = scheduler.schedule(new Runnable {
      override def run(): Unit = {
        indicator.cancel();
        exchange.close();
        worker.interrupt()
      }
    }, 30, TimeUnit.SECONDS)
    try ProgressManager.getInstance().runProcess(new Runnable {
      override def run(): Unit = handleRequest(exchange)
    }, indicator)
    finally {
      deadline.cancel(false);
      active.remove(indicator);
      Thread.interrupted();
      exchange.close()
    }
  }

  private def handleRequest(exchange: HttpExchange): Unit = {
    import DbtAgentProtocol._
    def send(code: Int, body: Option[String], headers: (String, String)*): Unit = {
      headers.foreach { case (k, v) => exchange.getResponseHeaders.set(k, v) }
      exchange.getResponseHeaders.set("Cache-Control", "no-store")
      body match {
        case None => exchange.sendResponseHeaders(code, -1)
        case Some(text) =>
          val bytes = text.getBytes(StandardCharsets.UTF_8)
          exchange.getResponseHeaders.set("Content-Type", "application/json; charset=utf-8")
          exchange.sendResponseHeaders(code, bytes.length.toLong)
          val output = exchange.getResponseBody;
          try output.write(bytes) finally output.close()
      }
    }

    def fail(code: Int, rpcCode: Int, message: String, headers: (String, String)*): Unit =
      send(code, Some(errorResponse(None, rpcCode, message).toString), headers: _*)

    try {
      val auth = Option(exchange.getRequestHeaders.getFirst("Authorization")).getOrElse("")
      val expected = synchronized {
        "Bearer " + token
      }
      if (exchange.getRequestURI.getPath != "/mcp") fail(404, -32600, "Unknown endpoint; use /mcp.")
      else if (exchange.getRequestMethod != "POST") fail(405, -32600, "Only POST is supported; this server does not open SSE streams or sessions.", "Allow" -> "POST")
      else if (exchange.getRequestHeaders.containsKey("Origin")) fail(403, -32600, "Requests with an Origin header are rejected.")
      else if (!MessageDigest.isEqual(auth.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8)))
        fail(401, -32600, "Missing or invalid bearer token.", "WWW-Authenticate" -> "Bearer")
      else {
        val input = exchange.getRequestBody.readNBytes(65537)
        if (input.length > 65536) fail(413, -32600, "Request exceeds the 64 KiB limit.")
        else {
          val reply = handle(name => Option(exchange.getRequestHeaders.getFirst(name)), new String(input, StandardCharsets.UTF_8))
          reply.body.map(_.toString) match {
            case Some(text) if text.length > ResponseLimit => fail(500, -32603, "Response exceeds the 2 MB limit; narrow the request.")
            case body => send(reply.status, body)
          }
        }
      }
    } catch {
      case NonFatal(_) => try fail(500, -32603, "Request failed or was canceled.") catch {
        case NonFatal(_) => ()
      }
    }
    finally exchange.close()
  }

  /**
   * Handles one MCP Streamable HTTP POST body. Requests that carry `_meta` protocol metadata (or a modern
   * `MCP-Protocol-Version` header) follow the stateless 2026-07-28 rules; all other messages follow the
   * `initialize`-based 2025-03-26 .. 2025-11-25 rules. A `None` body means HTTP 202 Accepted.
   */
  private[ide] def handle(header: String => Option[String], text: String): DbtAgent.Reply = {
    import DbtAgentProtocol._
    import DbtAgent.Reply
    scala.util.Try(JsonParser.parseString(text)).toOption match {
      case Some(batch) if batch.isJsonArray =>
        val version = header("MCP-Protocol-Version").map(DbtAgent.decodeHeader).getOrElse(DefaultLegacyVersion)
        val items = batch.getAsJsonArray.asScala.toVector
        if (version != DefaultLegacyVersion || items.isEmpty || !items.forall(_.isJsonObject))
          Reply(400, Some(errorResponse(None, -32600, "Batches are accepted only for protocol version 2025-03-26 and must contain JSON-RPC objects.")))
        else {
          val responses = items.flatMap(item => legacy(item.getAsJsonObject, version).body)
          if (responses.isEmpty) Reply(202, None) else Reply(200, Some(array(responses)))
        }
      case Some(message) if message.isJsonObject =>
        val request = message.getAsJsonObject
        val meta = DbtJson.child(DbtJson.child(request, "params").getOrElse(new JsonObject), "_meta")
        val headerVersion = header("MCP-Protocol-Version").map(DbtAgent.decodeHeader)
        if (meta.exists(_.has(DbtAgent.ProtocolVersionKey)) || headerVersion.exists(ModernVersions.contains)) modern(header, request)
        else legacy(request, headerVersion.getOrElse(DefaultLegacyVersion))
      case Some(_) => Reply(400, Some(errorResponse(None, -32600, "Invalid JSON-RPC message.")))
      case None => Reply(400, Some(errorResponse(None, -32700, "Parse error.")))
    }
  }

  private def modern(header: String => Option[String], request: JsonObject): DbtAgent.Reply = {
    import DbtAgentProtocol._
    import DbtAgent._
    val id = Option(request.get("id"))
    val method = DbtJson.text(request, "method")
    val params = DbtJson.child(request, "params").getOrElse(new JsonObject)
    val meta = DbtJson.child(params, "_meta")
    val headerVersion = header("MCP-Protocol-Version").map(decodeHeader)
    val requested = meta.flatMap(DbtJson.text(_, ProtocolVersionKey)).orElse(headerVersion).getOrElse("")
    if (method.isEmpty) clientMessage(request)
    else if (!ModernVersions.contains(requested)) unsupportedVersion(id, requested)
    else if (id.isDefined &&
      !meta.exists(m => DbtJson.text(m, ProtocolVersionKey).isDefined && DbtJson.child(m, ClientCapabilitiesKey).isDefined))
      Reply(400, Some(errorResponse(id, -32602, "Request params._meta must contain " + ProtocolVersionKey + " and " + ClientCapabilitiesKey + ".")))
    else if (!headerVersion.contains(requested) || !header("Mcp-Method").map(decodeHeader).contains(method.get) ||
      (method.contains("tools/call") && header("Mcp-Name").map(decodeHeader) != DbtJson.text(params, "name")))
      Reply(400, Some(errorResponse(id, -32020, "MCP-Protocol-Version, Mcp-Method and Mcp-Name headers must match the request body.")))
    else if (id.isEmpty) Reply(202, None)
    else if (disposed || project.isDisposed) Reply(200, Some(errorResponse(id, -32603, "Project is disposed.")))
    else {
      def complete(result: JsonObject): Reply = {
        result.addProperty("resultType", "complete");
        Reply(200, Some(success(id.get, result)))
      }

      def cacheable(result: JsonObject): JsonObject = {
        result.addProperty("ttlMs", 300000);
        result.addProperty("cacheScope", "private");
        result
      }

      method.get match {
        case "server/discover" =>
          complete(cacheable(obj("supportedVersions" -> strings(SupportedVersions), "capabilities" -> toolCapabilities,
            "_meta" -> obj(ServerInfoKey -> serverInfo), "instructions" -> Instructions)))
        case "tools/list" => complete(cacheable(obj("tools" -> schemas)))
        case "tools/call" => toolsCall(params).fold(error => Reply(200, Some(errorResponse(id, error._1, error._2))), complete)
        case _ => Reply(404, Some(errorResponse(id, -32601, "Unknown method.")))
      }
    }
  }

  private def legacy(request: JsonObject, version: String): DbtAgent.Reply = {
    import DbtAgentProtocol._
    import DbtAgent._
    val id = Option(request.get("id"))
    val method = DbtJson.text(request, "method")
    if (method.isEmpty) clientMessage(request)
    else if (!LegacyVersions.contains(version)) unsupportedVersion(id, version)
    else if (id.isEmpty) Reply(202, None)
    else if (disposed || project.isDisposed) Reply(200, Some(errorResponse(id, -32603, "Project is disposed.")))
    else {
      val params = DbtJson.child(request, "params").getOrElse(new JsonObject)

      def ok(result: JsonObject): Reply = Reply(200, Some(success(id.get, result)))

      method.get match {
        case "initialize" =>
          val requested = DbtJson.text(params, "protocolVersion").filter(LegacyVersions.contains).getOrElse(LegacyVersions.head)
          ok(obj("protocolVersion" -> requested, "instructions" -> Instructions, "capabilities" -> toolCapabilities, "serverInfo" -> serverInfo))
        case "ping" => ok(new JsonObject)
        case "tools/list" => ok(obj("tools" -> schemas))
        case "tools/call" => toolsCall(params).fold(error => Reply(200, Some(errorResponse(id, error._1, error._2))), ok)
        case _ => Reply(200, Some(errorResponse(id, -32601, "Unknown method.")))
      }
    }
  }

  /** Runs one read tool. Unknown tools are protocol errors; invalid arguments and failed lookups are tool errors. */
  private def toolsCall(params: JsonObject): Either[(Int, String), JsonObject] = {
    import DbtAgentProtocol._
    import DbtAgent._
    val name = DbtJson.text(params, "name").getOrElse("")
    val args = DbtJson.child(params, "arguments").getOrElse(new JsonObject)
    Tools.find(_._1 == name).map(_._3) match {
      case None => Left((-32602, "Unknown tool: " + name))
      case Some(required) =>
        val invalid = args.entrySet().asScala.exists { entry =>
          val v = entry.getValue
          !allowedArguments(name).contains(entry.getKey) || !v.isJsonPrimitive ||
            (if (entry.getKey == "offset") !v.getAsJsonPrimitive.isNumber || scala.util.Try(v.getAsBigDecimal.intValueExact()).toOption.forall(_ < 0)
            else !v.getAsJsonPrimitive.isString || v.getAsString.length > 16384)
        }
        val badDirection = Option(args.get("direction")).exists(v => !v.isJsonPrimitive || !Set("upstream", "downstream")(v.getAsString))
        if (invalid || badDirection || !required.forall(k => DbtJson.text(args, k).exists(_.nonEmpty)))
          Right(toolResult(None, "Arguments do not match the tool input schema or exceed request limits.", isError = true))
        else {
          val output = DbtUi.read {
            DbtAgentProtocol.call(DbtWorkspace.get(project), name, args)
          }
          val failed = DbtJson.text(output, "status").exists(Set("error", "unsupported", "disposed"))
          val result = toolResult(Some(output), output.toString, failed)
          if (result.toString.length > ResponseLimit - 4096)
            Right(toolResult(None, "The result exceeds the 2 MB response limit. Narrow the request.", isError = true))
          else Right(result)
        }
    }
  }

  private def clientMessage(message: JsonObject): DbtAgent.Reply =
    if (message.has("id") && (message.has("result") || message.has("error"))) DbtAgent.Reply(202, None)
    else DbtAgent.Reply(400, Some(DbtAgentProtocol.errorResponse(Option(message.get("id")), -32600, "Invalid JSON-RPC message.")))

  /** Dispatches one message with legacy (2025-03-26) rules and returns the JSON-RPC response, or an empty object for notifications. */
  private[ide] def rpc(request: JsonObject): JsonObject =
    legacy(request, DbtAgentProtocol.DefaultLegacyVersion).body.map(_.getAsJsonObject).getOrElse(new JsonObject)

  def stop(): Unit = synchronized {
    active.asScala.foreach(_.cancel())
    if (server != null) server.stop(0)
    if (executor != null) executor.shutdownNow()
    if (timer != null) timer.shutdownNow()
    server = null;
    executor = null;
    timer = null;
    token = ""
  }

  override def dispose(): Unit = synchronized {
    disposed = true;
    stop()
  }
}

private[ide] object DbtAgent {

  import DbtAgentProtocol._

  /** An HTTP status and JSON body; no body means 202 Accepted. */
  final case class Reply(status: Int, body: Option[JsonElement])

  val ProtocolVersionKey = "io.modelcontextprotocol/protocolVersion"
  val ClientCapabilitiesKey = "io.modelcontextprotocol/clientCapabilities"
  val ServerInfoKey = "io.modelcontextprotocol/serverInfo"

  def success(id: JsonElement, result: JsonObject): JsonObject = obj("jsonrpc" -> "2.0", "id" -> id, "result" -> result)

  def unsupportedVersion(id: Option[JsonElement], requested: String): Reply = Reply(400, Some(errorResponse(id, -32022,
    "Unsupported protocol version.", Some(obj("supported" -> strings(SupportedVersions), "requested" -> requested)))))

  def toolCapabilities: JsonObject = obj("tools" -> new JsonObject)

  def toolResult(structured: Option[JsonObject], text: String, isError: Boolean): JsonObject = {
    val result = obj("content" -> array(Vector(obj("type" -> "text", "text" -> text))))
    structured.foreach(result.add("structuredContent", _))
    if (isError) result.addProperty("isError", true)
    result
  }

  /** Header values may be sent as `=?base64?<value>?=` when they contain non-ASCII characters. */
  def decodeHeader(value: String): String = {
    val v = value.trim
    if (v.startsWith("=?base64?") && v.endsWith("?=") && v.length >= 11)
      scala.util.Try(new String(java.util.Base64.getDecoder.decode(v.substring(9, v.length - 2)), StandardCharsets.UTF_8)).getOrElse(v)
    else v
  }
}

final class DbtAgentAction extends AnAction {
  override def actionPerformed(event: AnActionEvent): Unit = Option(event.getProject).foreach { project =>
    val service = project.getService(classOf[DbtAgent])
    val details = DbtUi.textArea()

    def refresh(): Unit = details.setText(service.endpoint.map { case (url, token) =>
      "Read-only MCP server is running for this IDE project.\nURL: " + url + "\nAuthorization: Bearer " + token +
        "\nTransport: Streamable HTTP · MCP versions " + DbtAgentProtocol.SupportedVersions.mkString(", ") +
        "\nToken is temporary; stop/restart rotates it. Do not publish it."
    }.getOrElse("Local MCP server is stopped. Enable the local MCP server in Settings > dbt Workspace > Integrations, then Start.\nNo AI subscription or warehouse connection is required.\nOnly read tools are exposed. See docs/integrations.md for MCP client setup."))

    refresh()
    DbtUi.dialog(project, "dbt Local MCP Server", new JScrollPane(details), south = DbtUi.buttons(
      DbtUi.button("Configure…") {
        DbtSettingsConfigurable.showIntegrations(project);
        refresh()
      },
      DbtUi.button("Start") {
        service.start().left.foreach(error => Messages.showInfoMessage(project, error, "dbt Local MCP Server"));
        refresh()
      },
      DbtUi.button("Stop") {
        service.stop();
        refresh()
      },
      DbtUi.button("Copy endpoint and authorization") {
        service.endpoint.foreach { case (url, token) => DbtUi.copy(url + "\nAuthorization: Bearer " + token) }
      },
      DbtUi.button("Copy MCP client configuration") {
        service.endpoint.foreach { case (url, token) => DbtUi.copy(DbtAgentProtocol.clientConfiguration(url, token)) }
      }))
  }
}
