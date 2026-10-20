package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project._
import com.github.vbmacher.intellij.dbt.lineage.{DbtColumnLineage, DbtLineage, DbtSelection}
import com.github.vbmacher.intellij.dbt.navigation.DbtNavigation
import com.google.gson.{JsonArray, JsonElement, JsonObject, JsonParser}
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import java.net.{InetAddress, InetSocketAddress}
import java.nio.charset.StandardCharsets
import java.security.{MessageDigest, SecureRandom}
import java.util.concurrent.{Executors, ExecutorService, ScheduledExecutorService, ThreadPoolExecutor, ArrayBlockingQueue, TimeUnit}
import com.intellij.openapi.progress.{EmptyProgressIndicator, ProgressIndicator, ProgressManager}
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

private[dbt] object DbtAgentProtocol {
  val Version = "1.0"
  val Tools: Vector[(String, String, Vector[String])] = Vector(
    ("list_projects", "List owning project identities and source locations", Vector.empty),
    ("list_resources", "List resource identities in one owning project", Vector("project")),
    ("resolve_reference", "Resolve a source reference at an offset or by exact resource name", Vector("project", "reference")),
    ("get_documentation", "Source-first documentation and columns with provenance", Vector("resource_id", "project")),
    ("get_lineage", "Static resource edges with exact source identities", Vector("resource_id", "project")),
    ("get_column_lineage", "Static column derivations and partial analysis flags", Vector("resource_id", "project", "column")),
    ("suggest_validation_selection", "Read-only Git impact; no execution", Vector("baseline")),
    ("resolve_selection", "Preview a local selector with unresolved terms", Vector("project", "selection")))
  private val OptionalArguments: Map[String, Vector[String]] = Map(
    "resolve_reference" -> Vector("file", "offset"), "get_lineage" -> Vector("direction"),
    "get_column_lineage" -> Vector("direction"), "resolve_selection" -> Vector("exclude"),
    "suggest_validation_selection" -> Vector("compare"))
  def allowedArguments(tool: String): Vector[String] = Tools.find(_._1 == tool).toVector.flatMap(_._3) ++ OptionalArguments.getOrElse(tool, Vector.empty)
  def obj(values: (String, String)*): JsonObject = { val o = new JsonObject; values.foreach { case (k, v) => o.addProperty(k, v) }; o }
  def strings(values: Iterable[String]): JsonArray = { val a = new JsonArray; values.foreach(a.add); a }
  def array(values: Iterable[JsonElement]): JsonArray = { val a = new JsonArray; values.foreach(a.add); a }
  def error(code: Int, message: String): JsonObject = { val o = obj("message" -> message); o.addProperty("code", code); o }
  def schemas: JsonArray = array(Tools.map { case (name, description, required) =>
    val schema = obj("type" -> "object")
    val properties = new JsonObject
    allowedArguments(name).filterNot(_ == "offset").foreach { name =>
      val value = obj("type" -> "string"); value.addProperty("maxLength", 16384)
      if (name == "direction") value.add("enum", strings(Vector("upstream", "downstream")))
      properties.add(name, value)
    }
    if (allowedArguments(name).contains("offset")) { val offset = obj("type" -> "integer"); offset.addProperty("minimum", 0); offset.addProperty("maximum", Int.MaxValue); properties.add("offset", offset) }
    schema.add("properties", properties); schema.add("required", strings(required)); schema.addProperty("additionalProperties", false)
    val tool = obj("name" -> name, "description" -> description)
    tool.add("inputSchema", schema)
    tool.add("annotations", { val a = new JsonObject; a.addProperty("readOnlyHint", true); a.addProperty("destructiveHint", false); a })
    tool
  })
  def resource(w: DbtWorkspace, s: DbtSymbol): JsonObject = {
    val result = obj("resource_id" -> s.uniqueId, "project" -> w.owner(s).map(_.directory.getPath).getOrElse(""),
      "kind" -> s.kind, "name" -> s.name, "owner" -> s.owner, "package" -> s.namespace, "version" -> s.version.getOrElse(""),
      "path" -> s.file.getVirtualFile.getPath, "provenance" -> (if (s.metadata) "local artifact" else "current source"))
    if (s.kind == "column" || s.kind == "sql_projection") {
      val relations = w.symbols.filter(r => DbtSymbol.RelationKinds(r.kind) && r.relationName == s.owner &&
        r.namespace == s.namespace && r.version == s.version && w.owner(r) == w.owner(s)).sortBy(_.isProperty)
      relations.headOption.foreach(r => result.addProperty("relation_resource_id", r.uniqueId))
    }
    result.addProperty("offset", s.range.getStartOffset)
    val line = s.file.getText.take(s.range.getStartOffset).count(_ == '\n') + 1
    result.addProperty("line", line); result.addProperty("stale", s.isStale); result
  }
  def call(w: DbtWorkspace, tool: String, args: JsonObject): JsonObject = {
    def text(key: String): String = Option(args.get(key)).filter(_.isJsonPrimitive).map(_.getAsString).getOrElse("")
    def response(status: String, message: String = ""): JsonObject = obj("status" -> status, "message" -> message, "schema_version" -> Version)
    val roots = w.roots.filter(r => r.directory.getPath == text("project") || r.name == text("project"))
    val candidates = w.symbols.filter(s => w.owner(s).exists(roots.contains) && s.uniqueId == text("resource_id"))
      .sortBy(s => (s.metadata, s.isProperty)).distinctBy(s => (DbtResourceIdentity.key(w, s), s.owner))
    def resourceResult(f: DbtSymbol => JsonObject): JsonObject = {
      if (roots.size != 1) response("ambiguous", "Choose an exact project directory from list_projects.")
      else if (candidates.size != 1) { val r = response(if (candidates.isEmpty) "missing" else "ambiguous", "Resource identity did not resolve uniquely."); r.add("candidates", array(candidates.map(resource(w, _)))); r }
      else f(candidates.head)
    }
    if (w.project.isDisposed) response("disposed", "Project is closed.")
    else tool match {
      case "list_projects" =>
        val r = response(if (w.roots.size > 100) "partial" else "ok"); r.add("projects", array(w.roots.take(100).map(root => obj("name" -> root.name,
          "project" -> root.directory.getPath, "path" -> (root.directory.getPath + "/dbt_project.yml"))))); r
      case "list_resources" =>
        if (roots.size != 1) response("ambiguous", "Choose an exact project directory from list_projects.")
        else { val resources = w.symbols.filter(s => w.owner(s).contains(roots.head) && s.kind != "environment" && s.kind != "env" &&
          (s.isCode || s.isProperty || s.metadata || DbtResourceData.GraphKinds(s.kind))).sortBy(s => (s.metadata, s.isProperty)).distinctBy(_.uniqueId)
          val r = response(if (resources.size > 1000) "partial" else "ok"); r.add("resources", array(resources.take(1000).map(resource(w, _)))); r }
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
          r.add("resources", array(unique.take(100).map(resource(w, _)))); r
        }
      case "get_documentation" => resourceResult { s =>
        val data = DbtResourceData.get(w, s)
        val r = response(if (s.isStale || data.columns.size > 500) "partial" else "ok")
        r.add("resource", resource(w, s)); r.addProperty("description", data.description.take(20000))
        r.add("columns", array(data.columns.take(500).map(c => obj("name" -> c.name, "type" -> c.dataType, "description" -> c.description.take(1000)))))
        r
      }
      case "get_lineage" => resourceResult { s =>
        val edges = DbtLineage.edges(w).filter(e => if (text("direction") == "downstream") DbtResourceIdentity.key(w, e.to) == DbtResourceIdentity.key(w, s)
          else DbtResourceIdentity.key(w, e.from) == DbtResourceIdentity.key(w, s))
        val r = response("partial", "Static edges only; dynamic consumers are unresolved.")
        r.add("edges", array(edges.take(1000).map { e => val edge = new JsonObject; edge.add("from", resource(w, e.from)); edge.add("to", resource(w, e.to)); edge })); r
      }
      case "get_column_lineage" => resourceResult { s =>
        val edges = DbtColumnLineage.edges(w).filter(e => if (text("direction") == "downstream")
          e.input.owner == s.relationName && e.input.namespace == s.namespace && e.input.version == s.version && w.owner(e.input) == w.owner(s) && e.input.name == text("column")
          else DbtResourceIdentity.key(w, e.resource) == DbtResourceIdentity.key(w, s) && e.column == text("column"))
        val r = response("partial", "Static column derivations and bounded paths; absent edges do not prove independence.")
        r.add("edges", array(edges.take(1000).map { e => val edge = obj("column" -> e.column, "expression" -> e.expression.take(4000), "input_column" -> e.input.name)
          edge.add("resource", resource(w, e.resource)); edge.add("input", resource(w, e.input)); edge.addProperty("complete", e.complete); edge.addProperty("direct", e.direct); edge }))
        val paths = columnPaths(w, s, text("column"), text("direction") == "downstream")
        r.add("paths", paths.get("paths")); r.add("paths_truncated", paths.get("truncated")); r

      }
      case "resolve_selection" =>
        if (roots.size != 1) response("ambiguous", "Choose an exact project.")
        else {
          val result = DbtSelection.resolve(w, roots.head, text("selection"), text("exclude"))
          val r = response(if (result.complete && result.resources.size <= 1000) "ok" else "partial"); r.add("resources", array(result.resources.take(1000).map(resource(w, _))))
          r.add("problems", strings(result.problems)); r
        }
      case "suggest_validation_selection" => impact(w.project, args)
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
    def node(s: DbtSymbol, name: String): JsonObject = { val result = resource(w, s); result.addProperty("column", name); result }
    def identity(s: DbtSymbol, name: String): String = DbtResourceIdentity.key(w, s) + "|" + name.toLowerCase(java.util.Locale.ROOT)
    def inputMatches(input: DbtSymbol, relation: DbtSymbol, name: String): Boolean =
      input.owner == relation.relationName && input.namespace == relation.namespace && input.version == relation.version &&
        w.owner(input) == w.owner(relation) && input.name.equalsIgnoreCase(name)
    def finish(nodes: Vector[JsonObject], complete: Boolean): Unit = if (paths.size() < 50) {
      val result = new JsonObject; result.add("columns", array(nodes)); result.addProperty("complete", complete); paths.add(result)
    } else truncated = true
    def visit(current: DbtSymbol, name: String, nodes: Vector[JsonObject], seen: Set[String], complete: Boolean): Unit = {
      ProgressManager.checkCanceled()
      work += 1
      if (paths.size() >= 50 || work > 1000) truncated = true
      else if (nodes.size >= 20) { truncated = true; finish(nodes, complete = false) }
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
    val result = new JsonObject; result.add("paths", paths); result.addProperty("truncated", truncated); result
  }

  private[ide] def impact(project: Project, args: JsonObject): JsonObject = {
    def text(key: String): String = Option(args.get(key)).filter(_.isJsonPrimitive).map(_.getAsString).getOrElse("")
    def response(status: String, message: String): JsonObject = obj("status" -> status, "message" -> message, "schema_version" -> Version)
    if (project.isDisposed) response("disposed", "Project is closed.")
    else if (text("compare").nonEmpty && text("compare") != "working_tree") response("unsupported", "Only working_tree comparison is supported.")
    else {
      val initial = ReadAction.computeBlocking[DbtWorkspace, RuntimeException](() => DbtWorkspace.get(project))
      DbtImpact.analyze(initial, text("baseline")).fold(error => response("error", error), result =>
        ReadAction.computeBlocking[JsonObject, RuntimeException](() => {
          if (project.isDisposed) response("disposed", "Project is closed.")
          else {
            val w = DbtWorkspace.get(project)
            val r = response("partial", "Review the proposed validation selection before explicit execution. Traces are capped at 50 steps.")
            r.addProperty("baseline", result.baseline); r.addProperty("selection", result.selection)
            r.add("affected", array(result.affected.take(500).map { case (s, trace) => val item = resource(w, s); item.add("trace", strings(trace.take(50))); item }))
            r.add("problems", strings(result.problems)); r
          }
        }))
    }
  }
}

/** Authenticated loopback HTTP JSON-RPC. There are deliberately no execution tools. */
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
    Option(server).map(s => ("http://127.0.0.1:" + s.getAddress.getPort + "/tools", token))
  }
  def start(): Either[String, (String, String)] = synchronized {
    if (disposed || project.isDisposed) Left("Project is disposed.")
    else if (!project.getService(classOf[DbtConsumersSettings]).getState.agentEnabled) Left("Enable local agent tools in Settings > dbt Integrations first.")
    else endpoint match {
      case Some(value) => Right(value)
      case None => try {
        val bytes = new Array[Byte](32); new SecureRandom().nextBytes(bytes); token = java.util.HexFormat.of().formatHex(bytes)
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 8)
        val threads: java.util.concurrent.ThreadFactory = (r: Runnable) => { val t = new Thread(r, "dbt-local-tools"); t.setDaemon(true); t }
        executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue[Runnable](8), threads, new ThreadPoolExecutor.AbortPolicy)
        timer = Executors.newSingleThreadScheduledExecutor(threads)
        server.setExecutor(executor)
        server.createContext("/tools", new HttpHandler { override def handle(exchange: HttpExchange): Unit = boundedRequest(exchange) })
        server.start(); endpoint.toRight("Local tools failed to start.")
      } catch { case NonFatal(_) => stop(); Left("Local loopback transport is unavailable.") }
    }
  }
  private def boundedRequest(exchange: HttpExchange): Unit = {
    val indicator = new EmptyProgressIndicator()
    val worker = Thread.currentThread()
    val scheduler = synchronized { timer }
    if (scheduler == null) { exchange.close(); return }
    active.add(indicator)
    val deadline = scheduler.schedule(new Runnable {
      override def run(): Unit = { indicator.cancel(); exchange.close(); worker.interrupt() }
    }, 30, TimeUnit.SECONDS)
    try ProgressManager.getInstance().runProcess(new Runnable { override def run(): Unit = handleRequest(exchange) }, indicator)
    finally { deadline.cancel(false); active.remove(indicator); Thread.interrupted(); exchange.close() }
  }
  private def handleRequest(exchange: HttpExchange): Unit = {
    def send(code: Int, body: String): Unit = {
      val bytes = body.getBytes(StandardCharsets.UTF_8)
      exchange.getResponseHeaders.set("Content-Type", "application/json; charset=utf-8")
      exchange.getResponseHeaders.set("Cache-Control", "no-store")
      exchange.sendResponseHeaders(code, bytes.length.toLong)
      val output = exchange.getResponseBody; try output.write(bytes) finally output.close()
    }
    try {
      val auth = Option(exchange.getRequestHeaders.getFirst("Authorization")).getOrElse("")
      val expected = synchronized { "Bearer " + token }
      if (exchange.getRequestMethod != "POST" || exchange.getRequestURI.getPath != "/tools") send(405, "{}")
      else if (exchange.getRequestHeaders.containsKey("Origin") || !MessageDigest.isEqual(auth.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8))) send(403, "{}")
      else {
        val input = exchange.getRequestBody.readNBytes(65537)
        if (input.length > 65536) send(413, "{}")
        else {
          val request = JsonParser.parseString(new String(input, StandardCharsets.UTF_8)).getAsJsonObject
          val result = rpc(request)
          val serialized = result.toString
          if (serialized.length > 2000000) send(413, "{\"error\":\"Response exceeds 2 MB limit; narrow the request.\"}") else send(200, serialized)
        }
      }
    } catch { case NonFatal(_) => try send(400, "{\"error\":\"Invalid or canceled tool request.\"}") catch { case NonFatal(_) => () } }
    finally exchange.close()
  }
  private[ide] def rpc(request: JsonObject): JsonObject = {
    import DbtAgentProtocol._
    val result = obj("jsonrpc" -> "2.0")
    result.add("id", Option(request.get("id")).getOrElse(com.google.gson.JsonNull.INSTANCE))
    val method = Option(request.get("method")).filter(_.isJsonPrimitive).map(_.getAsString).getOrElse("")
    if (disposed || project.isDisposed) result.add("error", error(-32000, "Project is disposed."))
    else method match {
      case "initialize" =>
        val initialized = obj("protocolVersion" -> "2024-11-05")
        initialized.add("serverInfo", obj("name" -> "dbt-workspace-local-tools", "version" -> Version))
        val capabilities = new JsonObject; capabilities.add("tools", new JsonObject); initialized.add("capabilities", capabilities)
        initialized.addProperty("transport", "authenticated-loopback-jsonrpc-http")
        result.add("result", initialized)
      case "ping" | "notifications/initialized" => result.add("result", new JsonObject)
      case "tools/list" => val listed = new JsonObject; listed.add("tools", schemas); result.add("result", listed)
      case "tools/call" =>
        val params = Option(request.get("params")).filter(_.isJsonObject).map(_.getAsJsonObject).getOrElse(new JsonObject)
        val name = Option(params.get("name")).filter(v => v.isJsonPrimitive && v.getAsJsonPrimitive.isString).map(_.getAsString).getOrElse("")
        val args = Option(params.get("arguments")).filter(_.isJsonObject).map(_.getAsJsonObject).getOrElse(new JsonObject)
        val required = Tools.find(_._1 == name).map(_._3)
        val invalid = args.entrySet().asScala.exists { entry =>
          val v = entry.getValue
          !allowedArguments(name).contains(entry.getKey) || !v.isJsonPrimitive ||
            (if (entry.getKey == "offset") !v.getAsJsonPrimitive.isNumber || scala.util.Try(v.getAsBigDecimal.intValueExact()).toOption.forall(_ < 0)
             else !v.getAsJsonPrimitive.isString || v.getAsString.length > 16384)
        }
        val badDirection = Option(args.get("direction")).exists(v => !v.isJsonPrimitive || !Set("upstream", "downstream")(v.getAsString))
        if (required.isEmpty) result.add("error", error(-32601, "Unknown read tool."))
        else if (invalid || badDirection || !required.get.forall(k => Option(args.get(k)).exists(v => v.isJsonPrimitive && v.getAsJsonPrimitive.isString && v.getAsString.nonEmpty)))
          result.add("error", error(-32602, "Arguments do not match the tool schema or exceed request limits."))
        else {
          val output = if (name == "suggest_validation_selection") DbtAgentProtocol.impact(project, args)
          else ReadAction.computeBlocking[JsonObject, RuntimeException](() => DbtAgentProtocol.call(DbtWorkspace.get(project), name, args))
          val content = new JsonObject; content.add("structuredContent", output)
          val textItem = obj("type" -> "text", "text" -> output.toString); content.add("content", array(Vector(textItem)))
          result.add("result", content)
        }
      case _ => result.add("error", error(-32601, "Unknown method."))
    }
    result
  }
  def stop(): Unit = synchronized {
    active.asScala.foreach(_.cancel())
    if (server != null) server.stop(0)
    if (executor != null) executor.shutdownNow()
    if (timer != null) timer.shutdownNow()
    server = null; executor = null; timer = null; token = ""
  }
  override def dispose(): Unit = synchronized { disposed = true; stop() }
}
