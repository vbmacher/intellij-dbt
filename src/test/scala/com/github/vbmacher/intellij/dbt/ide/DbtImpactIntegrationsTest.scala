package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project._
import com.google.gson.{JsonObject, JsonParser}
import org.junit.Assert._
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import scala.jdk.CollectionConverters._

final class DbtImpactIntegrationsTest extends DbtCodeInsightTestCase {
  private def workspace = DbtWorkspace.get(getProject)
  private def root = workspace.roots.find(_.name == "analytics").get
  private def model(name: String) = workspace.symbols.find(s => s.name == name && s.isCode).get
  override protected def tearDown(): Unit = try {
    getProject.getService(classOf[DbtAgent]).stop()
    getProject.getService(classOf[DbtConsumersSettings]).loadState(new DbtConsumersState)
  } finally super.tearDown()

  def testImpactComparisonDistinguishesAddRemoveMoveAndOutputChanges(): Unit = {
    val old = Vector(DbtImpactResource("model.a.orders.v1", "old.sql", "one", Vector("amount")),
      DbtImpactResource("model.a.deleted", "deleted.sql", "two", Vector("id")),
      DbtImpactResource("model.b.orders", "same.sql", "three", Vector("id")))
    val fresh = Vector(DbtImpactResource("model.a.orders.v1", "new.sql", "one", Vector("amount")),
      DbtImpactResource("model.a.added", "added.sql", "four", Vector("new_id")),
      DbtImpactResource("model.b.orders", "same.sql", "changed", Vector("id", "amount")))
    val changes = DbtImpact.compare(old, fresh)
    assertEquals(Map("model.a.orders.v1" -> "moved", "model.a.deleted" -> "removed", "model.a.added" -> "added", "model.b.orders" -> "changed"),
      changes.map(c => c.resource.id -> c.state).toMap)
    assertEquals(Vector("id", "amount"), changes.find(_.resource.id == "model.b.orders").get.columns)
  }

  def testIdenticalBaselineDoesNotFlagUnrelatedPropertyEntries(): Unit = {
    val config = project()
    val orders = add("models/orders.sql", "select 1 as id")
    val other = add("models/other.sql", "select 2 as unrelated")
    val properties = add("models/schema.yml", "models:\n- name: orders\n  columns: [{name: id}]\n- name: other\n  description: Other\n")
    val baseline = Map(config.getVirtualFile.getPath -> config.getText, orders.getVirtualFile.getPath -> orders.getText,
      other.getVirtualFile.getPath -> other.getText, properties.getVirtualFile.getPath -> properties.getText)
    val before = DbtImpact.baselineResources(workspace, baseline)
    assertTrue(DbtImpact.compare(before, DbtImpact.current(workspace)).isEmpty)
    edit(orders, "select 3 as id")
    val changed = DbtImpact.compare(before, DbtImpact.current(workspace))
    assertEquals(Vector("model.analytics.orders"), changed.map(_.resource.id.split("\\|").last))
  }

  def testSourceTableChangesPreserveUnrelatedTablesAndIdentity(): Unit = {
    val config = project()
    val properties = add("models/sources.yml", "sources: [{name: raw, schema: landing, tables: [{name: orders, columns: [{name: amount}]}, {name: customers, columns: [{name: id}]}]}]\n")
    val before = DbtImpact.baselineResources(workspace, Map(config.getVirtualFile.getPath -> config.getText, properties.getVirtualFile.getPath -> properties.getText))
    assertTrue(DbtImpact.compare(before, DbtImpact.current(workspace)).isEmpty)
    edit(properties, properties.getText.replace("name: amount", "name: gross_amount"))
    val changes = DbtImpact.compare(before, DbtImpact.current(workspace))
    assertEquals(Vector("source.analytics.raw.orders"), changes.map(_.resource.id.split("\\|").last))
    assertEquals(Vector("amount", "gross_amount"), changes.head.columns)
  }

  def testBaselineUsesDbtIgnoreForExistingAndDeletedHistoricalFiles(): Unit = {
    val config = project()
    add(".dbtignore", "models/private/**\nmodels/historical.sql\n")
    val public = add("models/public.sql", "select 1 as id")
    val privateFile = add("models/private/secret.sql", "select 2 as internal")
    val contents = Map(config.getVirtualFile.getPath -> config.getText, public.getVirtualFile.getPath -> public.getText,
      privateFile.getVirtualFile.getPath -> privateFile.getText, root.directory.getPath + "/models/historical.sql" -> "select 3")
    val before = DbtImpact.baselineResources(workspace, contents)
    assertEquals(Vector("model.analytics.public"), before.map(_.id.split("\\|").last))
    assertTrue(DbtImpact.compare(before, DbtImpact.current(workspace)).isEmpty)
  }

  def testBaselineIgnoreRulesComeFromTheBaselineRevision(): Unit = {
    val config = project()
    add(".dbtignore", "models/legacy.sql\n")
    val legacy = root.directory.getPath + "/models/legacy.sql"
    val contents = Map(config.getVirtualFile.getPath -> config.getText, legacy -> "select 1 as id")
    val ignores = Map(root.directory.getPath -> "")
    val changes = DbtImpact.compare(DbtImpact.baselineResources(workspace, contents, Some(ignores)), DbtImpact.current(workspace))
    assertEquals(Map("model.analytics.legacy" -> "removed"), changes.map(c => c.resource.id.split("\\|").last -> c.state).toMap)
  }

  def testBaselineProjectNamePreservesRemovedAndAddedIdentityOnProjectRename(): Unit = {
    val config = project()
    val code = add("models/orders.sql", "select 1 as amount")
    val contents = Map(config.getVirtualFile.getPath -> config.getText, code.getVirtualFile.getPath -> code.getText)
    edit(config, config.getText.replace("name: analytics", "name: renamed"))
    val changes = DbtImpact.compare(DbtImpact.baselineResources(workspace, contents), DbtImpact.current(workspace))
    assertEquals(Map("model.analytics.orders" -> "removed", "model.renamed.orders" -> "added"),
      changes.map(c => c.resource.id.split("\\|").last -> c.state).toMap)
  }

  def testVersionedDefinedInAndPackageIdentityStaySeparate(): Unit = {
    project()
    val code = add("models/physical_orders.sql", "select 1 as id")
    val properties = add("models/schema.yml", "models: [{name: orders, versions: [{v: 2, defined_in: physical_orders, columns: [{name: id}]}]}]\n")
    add("dbt_packages/pkg/dbt_project.yml", "name: pkg\nconfig-version: 2\n")
    add("dbt_packages/pkg/models/orders.sql", "select 2 as id")
    val baseline = Map(code.getVirtualFile.getPath -> code.getText, properties.getVirtualFile.getPath -> properties.getText)
    val before = DbtImpact.baselineResources(workspace, baseline)
    val local = DbtImpact.current(workspace).filter(_.id.startsWith(root.directory.getPath + "|"))
    assertEquals(Vector("model.analytics.orders.v2"), local.map(_.id.split("\\|").last))
    assertTrue(DbtImpact.compare(before, local).isEmpty)
  }

  def testVersionedModelIncludesInheritedMetadataInBaselineFingerprint(): Unit = {
    val config = project()
    val code = add("models/orders_v2.sql", "select 1 as amount")
    val properties = add("models/schema.yml", "models: [{name: orders, description: Before, columns: [{name: amount}], versions: [{v: 2}]}]\n")
    val before = DbtImpact.baselineResources(workspace, Map(config.getVirtualFile.getPath -> config.getText,
      code.getVirtualFile.getPath -> code.getText, properties.getVirtualFile.getPath -> properties.getText))
    assertTrue(DbtImpact.compare(before, DbtImpact.current(workspace)).isEmpty)
    edit(properties, properties.getText.replace("Before", "After"))
    val changed = DbtImpact.compare(before, DbtImpact.current(workspace))
    assertEquals(Vector("model.analytics.orders.v2"), changed.map(_.resource.id.split("\\|").last))
  }

  def testExposureConsumersWorkOfflineAndHaveOwnerUrlAndEvidence(): Unit = {
    project()
    add("models/orders.sql", "select 1 as amount")
    add("models/report.sql", "select * from {{ ref('orders') }}")
    val exposure = add("models/exposures.yml", "exposures:\n- name: revenue_dashboard\n  type: dashboard\n  owner: {name: Finance}\n  url: https://example.test/dashboards/revenue\n  depends_on: [\"ref('report')\"]\n")
    val consumers = DbtConsumers.exposures(workspace, Some(model("orders")))
    assertEquals(1, consumers.size); assertEquals("Finance", consumers.head.owner)
    assertEquals(Some("https://example.test/dashboards/revenue"), consumers.head.url)
    assertTrue(consumers.head.evidence.startsWith(exposure.getVirtualFile.getPath))
    assertEquals("declared", consumers.head.confidence)
    assertEquals(0L, getProject.getService(classOf[DbtConsumers]).snapshot.refreshedAt)
  }

  def testMetabaseQuestionMapsPhysicalAliasAndColumnAndMarksMissingDatabaseInferred(): Unit = {
    project()
    add("models/sources.yml", "sources: [{name: raw, database: warehouse, schema: landing, tables: [{name: orders, identifier: real_orders, columns: [{name: amount}]}]}]\n")
    def json(text: String) = JsonParser.parseString(text).getAsJsonObject
    val card = json("""{"id":7,"name":"Revenue","dataset_query":{"query":{"source-table":42,"fields":[["field",99,null]]}}}""")
    val table = json("""{"id":42,"db_name":"warehouse","schema":"landing","name":"real_orders","fields":[{"id":99,"name":"amount"}]}""")
    val results = DbtConsumers.mapQuestions(workspace, "https://metabase.test", Vector(card), Vector(table))
    assertEquals(1, results.size); assertEquals(Some("amount"), results.head.column)
    assertEquals("direct physical metadata", results.head.confidence)
    assertEquals(Some("https://metabase.test/question/7"), results.head.url)
    table.remove("db_name")
    assertTrue(DbtConsumers.mapQuestions(workspace, "https://metabase.test", Vector(card), Vector(table)).head.confidence.startsWith("inferred"))
  }

  def testMetabaseRefreshUsesMockApiAndRetainsCacheOnPermissionError(): Unit = {
    project()
    add("models/sources.yml", "sources: [{name: raw, database: warehouse, schema: landing, tables: [{name: orders, identifier: real_orders, columns: [{name: amount}]}]}]\n")
    val server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0)
    val denied = new java.util.concurrent.atomic.AtomicBoolean(false)
    val requests = new java.util.concurrent.CopyOnWriteArrayList[String]()
    server.createContext("/api/", exchange => {
      requests.add(exchange.getRequestURI.getPath)
      val valid = exchange.getRequestHeaders.getFirst("x-api-key") == "fixture-only-key"
      val body = exchange.getRequestURI.getPath match {
        case "/api/card" => """[{"id":7,"name":"Revenue","dataset_query":{"query":{"source-table":42,"fields":[["field",99,null]]}}}]"""
        case "/api/database" => """{"data":[{"id":1}]}"""
        case "/api/database/1/metadata" => """{"tables":[{"id":42,"db_name":"warehouse","schema":"landing","name":"real_orders","fields":[{"id":99,"name":"amount"}]}]}"""
        case "/api/dashboard" => """[{"id":5,"name":"Finance"}]"""
        case "/api/dashboard/5" => """{"dashcards":[{"card_id":7}]}"""
        case _ => "{}"
      }
      val bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8)
      exchange.sendResponseHeaders(if (denied.get() || !valid) 403 else 200, bytes.length.toLong)
      val stream = exchange.getResponseBody; try stream.write(bytes) finally stream.close()
      exchange.close()
    })
    server.start()
    val service = getProject.getService(classOf[DbtConsumers])
    val settings = getProject.getService(classOf[DbtConsumersSettings])
    try {
      settings.getState.metabaseUrl = "http://127.0.0.1:" + server.getAddress.getPort
      service.setSecret("fixture-only-key")
      val refreshed = service.refresh(workspace)
      assertEquals(Set("Metabase question", "Metabase dashboard"), refreshed.consumers.map(_.kind).toSet)
      assertTrue(refreshed.refreshedAt > 0)
      assertTrue(requests.contains("/api/database/1/metadata"))
      assertFalse(refreshed.toString.contains("fixture-only-key"))
      denied.set(true)
      val failure = service.refresh(workspace)
      assertEquals(refreshed.refreshedAt, failure.refreshedAt)
      assertEquals(refreshed.consumers, failure.consumers)
      assertTrue(failure.status.contains("Permission denied"))
    } finally { service.setSecret(""); service.clear(); server.stop(0) }
  }

  def testMetabaseNativeSqlNeverClaimsExactLineage(): Unit = {
    project()
    add("models/sources.yml", "sources: [{name: raw, database: warehouse, schema: landing, tables: [{name: orders, identifier: real_orders}]}]\n")
    val card = JsonParser.parseString("""{"id":8,"name":"SQL question","dataset_query":{"native":{"query":"select * from warehouse.landing.real_orders"}}}""").getAsJsonObject
    val result = DbtConsumers.mapQuestions(workspace, "http://metabase.test", Vector(card), Vector.empty)
    assertEquals(1, result.size); assertTrue(result.head.confidence.startsWith("inferred"))
  }

  def testAgentContractProvidesExactIdentitiesLocationsAndSourceFirstDocsWithoutRunningDbt(): Unit = {
    project()
    val code = add("models/orders.sql", "select 1 as amount")
    add("models/schema.yml", "models: [{name: orders, description: Source documentation, columns: [{name: amount, data_type: int}]}]\n")
    val args = DbtAgentProtocol.obj("project" -> root.directory.getPath, "resource_id" -> "model.analytics.orders")
    val docs = DbtAgentProtocol.call(workspace, "get_documentation", args)
    assertEquals("ok", docs.get("status").getAsString)
    assertEquals("Source documentation", docs.get("description").getAsString)
    val identity = docs.getAsJsonObject("resource")
    assertEquals(code.getVirtualFile.getPath, identity.get("path").getAsString)
    assertEquals("current source", identity.get("provenance").getAsString)
    assertEquals(1, identity.get("line").getAsInt)
    val schema = DbtAgentProtocol.schemas.asScala.map(_.getAsJsonObject.get("name").getAsString).toSet
    assertTrue(schema("get_column_lineage")); assertFalse(schema.exists(_.contains("execute")))
    assertFalse(getProject.getService(classOf[DbtRunner]).isRunning)
  }

  def testAgentColumnPathsFollowOwningRelationsAcrossMultipleModels(): Unit = {
    project()
    add("models/sources.yml", "sources: [{name: raw, tables: [{name: orders, columns: [{name: amount}]}]}]\nmodels: [{name: staging, columns: [{name: amount}]}, {name: report, columns: [{name: gross_amount}]}]\n")
    add("models/staging.sql", "select amount from {{ source('raw', 'orders') }}")
    add("models/report.sql", "select amount * 1.2 as gross_amount from {{ ref('staging') }}")
    val args = DbtAgentProtocol.obj("project" -> root.directory.getPath, "resource_id" -> "model.analytics.report", "column" -> "gross_amount", "direction" -> "upstream")
    val result = DbtAgentProtocol.call(workspace, "get_column_lineage", args)
    val paths = result.getAsJsonArray("paths").asScala.map(_.getAsJsonObject)
    assertTrue(paths.nonEmpty)
    val columns = paths.head.getAsJsonArray("columns").asScala.map(_.getAsJsonObject).toVector
    assertEquals(Vector("model.analytics.report", "model.analytics.staging", "source.analytics.raw.orders"), columns.map(_.get("resource_id").getAsString))
    assertEquals(Vector("gross_amount", "amount", "amount"), columns.map(_.get("column").getAsString))
    assertFalse(result.get("paths_truncated").getAsBoolean)
  }

  def testAgentAmbiguityMissingAndUnsupportedAreExplicit(): Unit = {
    project()
    add("models/orders.sql", "select 1")
    add("other/dbt_project.yml", "name: analytics\nconfig-version: 2\n")
    add("other/models/orders.sql", "select 2")
    val ambiguous = DbtAgentProtocol.call(workspace, "list_resources", DbtAgentProtocol.obj("project" -> "analytics"))
    assertEquals("ambiguous", ambiguous.get("status").getAsString)
    val missing = DbtAgentProtocol.call(workspace, "get_documentation", DbtAgentProtocol.obj("project" -> root.directory.getPath, "resource_id" -> "model.analytics.absent"))
    assertEquals("missing", missing.get("status").getAsString)
    val unsupported = DbtAgentProtocol.call(workspace, "execute_sql", new JsonObject)
    assertEquals("unsupported", unsupported.get("status").getAsString)
  }

  def testStoppingAgentCancelsStalledAuthenticatedRequestBody(): Unit = {
    project()
    val service = getProject.getService(classOf[DbtAgent])
    getProject.getService(classOf[DbtConsumersSettings]).getState.agentEnabled = true
    val endpoint = service.start().toOption.get
    val uri = URI.create(endpoint._1)
    val socket = new java.net.Socket("127.0.0.1", uri.getPort)
    socket.setSoTimeout(2000)
    try {
      val request = "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer " + endpoint._2 + "\r\nContent-Length: 1000\r\n\r\n{"
      socket.getOutputStream.write(request.getBytes(java.nio.charset.StandardCharsets.UTF_8)); socket.getOutputStream.flush()
      val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2)
      while (service.activeRequestCount == 0 && System.nanoTime() < deadline) Thread.sleep(10)
      assertEquals(1, service.activeRequestCount)
      service.stop()
      try assertEquals(-1, socket.getInputStream.read()) catch { case _: java.net.SocketException => () }
      assertTrue(service.endpoint.isEmpty)
    } finally { socket.close(); service.stop() }
  }

  def testAgentRequiresOptInAndTransportIsAuthenticatedScopedAndStops(): Unit = {
    project()
    add("models/orders.sql", "select 1")
    val service = getProject.getService(classOf[DbtAgent])
    assertTrue(service.start().isLeft)
    getProject.getService(classOf[DbtConsumersSettings]).getState.agentEnabled = true
    val endpoint = service.start().toOption.get
    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
    def send(token: String, body: String = """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""") = client.send(HttpRequest.newBuilder(URI.create(endpoint._1)).timeout(Duration.ofSeconds(5))
      .header("Authorization", "Bearer " + token).POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
    assertEquals(401, send("wrong").statusCode())
    val response = send(endpoint._2)
    assertEquals(200, response.statusCode()); assertTrue(response.body().contains("get_documentation"))
    assertFalse(response.body().contains(endpoint._2))
    val invalid = send(endpoint._2, """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"resolve_reference","arguments":{"project":"analytics","reference":"orders","offset":1.5}}}""")
    assertTrue(JsonParser.parseString(invalid.body()).getAsJsonObject.getAsJsonObject("result").get("isError").getAsBoolean)
    val impact = send(endpoint._2, """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"suggest_validation_selection","arguments":{"baseline":"nonexistent-fixture-revision"}}}""")
    assertEquals("error", JsonParser.parseString(impact.body()).getAsJsonObject.getAsJsonObject("result").getAsJsonObject("structuredContent").get("status").getAsString)
    val docs = send(endpoint._2, """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"get_documentation","arguments":{"project":"analytics","resource_id":"model.analytics.orders"}}}""")
    assertEquals("ok", JsonParser.parseString(docs.body()).getAsJsonObject.getAsJsonObject("result").getAsJsonObject("structuredContent").get("status").getAsString)
    assertEquals(202, send(endpoint._2, """{"jsonrpc":"2.0","method":"notifications/initialized"}""").statusCode())
    val batch = send(endpoint._2, """[{"jsonrpc":"2.0","id":5,"method":"ping"},{"jsonrpc":"2.0","method":"notifications/initialized"}]""")
    assertEquals(200, batch.statusCode()); assertEquals(1, JsonParser.parseString(batch.body()).getAsJsonArray.size())
    val get = client.send(HttpRequest.newBuilder(URI.create(endpoint._1)).header("Authorization", "Bearer " + endpoint._2).GET().build(), HttpResponse.BodyHandlers.ofString())
    assertEquals(405, get.statusCode())
    service.stop(); assertTrue(service.endpoint.isEmpty)
    val restarted = service.start().toOption.get
    assertNotEquals(endpoint._2, restarted._2)
    service.stop()
  }

  def testAgentServesStatelessMcpRequestsWithMatchingHeaders(): Unit = {
    project()
    add("models/orders.sql", "select 1")
    val service = getProject.getService(classOf[DbtAgent])
    getProject.getService(classOf[DbtConsumersSettings]).getState.agentEnabled = true
    val endpoint = service.start().toOption.get
    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
    val meta = """"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}"""
    def send(body: String, headers: (String, String)*) = {
      val builder = HttpRequest.newBuilder(URI.create(endpoint._1)).timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer " + endpoint._2)
      headers.foreach { case (k, v) => builder.header(k, v) }
      val response = client.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
      (response.statusCode(), if (response.body().isEmpty) new JsonObject else JsonParser.parseString(response.body()).getAsJsonObject)
    }
    try {
      val (discoverStatus, discover) = send("""{"jsonrpc":"2.0","id":1,"method":"server/discover","params":{""" + meta + "}}",
        "MCP-Protocol-Version" -> "2026-07-28", "Mcp-Method" -> "server/discover")
      assertEquals(200, discoverStatus)
      val discovered = discover.getAsJsonObject("result")
      assertEquals("complete", discovered.get("resultType").getAsString)
      assertTrue(discovered.getAsJsonArray("supportedVersions").asScala.map(_.getAsString).toSet.contains("2026-07-28"))
      assertEquals("dbt-workspace", discovered.getAsJsonObject("_meta").getAsJsonObject("io.modelcontextprotocol/serverInfo").get("name").getAsString)
      val (listStatus, listed) = send("""{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{""" + meta + "}}",
        "MCP-Protocol-Version" -> "2026-07-28", "Mcp-Method" -> "tools/list")
      assertEquals(200, listStatus); assertEquals("private", listed.getAsJsonObject("result").get("cacheScope").getAsString)
      val call = """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"list_projects","arguments":{},""" + meta + "}}"
      val (callStatus, called) = send(call, "MCP-Protocol-Version" -> "2026-07-28", "Mcp-Method" -> "tools/call",
        "Mcp-Name" -> ("=?base64?" + java.util.Base64.getEncoder.encodeToString("list_projects".getBytes) + "?="))
      assertEquals(200, callStatus); assertEquals("complete", called.getAsJsonObject("result").get("resultType").getAsString)
      val (mismatchStatus, mismatch) = send(call, "MCP-Protocol-Version" -> "2026-07-28", "Mcp-Method" -> "tools/call", "Mcp-Name" -> "get_lineage")
      assertEquals(400, mismatchStatus); assertEquals(-32020, mismatch.getAsJsonObject("error").get("code").getAsInt)
      val future = """{"jsonrpc":"2.0","id":4,"method":"tools/list","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2099-01-01","io.modelcontextprotocol/clientCapabilities":{}}}}"""
      val (futureStatus, unsupported) = send(future, "MCP-Protocol-Version" -> "2099-01-01", "Mcp-Method" -> "tools/list")
      assertEquals(400, futureStatus); assertEquals(-32022, unsupported.getAsJsonObject("error").get("code").getAsInt)
      assertTrue(unsupported.getAsJsonObject("error").getAsJsonObject("data").getAsJsonArray("supported").size() > 0)
      val (missingMetaStatus, missingMeta) = send("""{"jsonrpc":"2.0","id":5,"method":"tools/list"}""", "MCP-Protocol-Version" -> "2026-07-28", "Mcp-Method" -> "tools/list")
      assertEquals(400, missingMetaStatus); assertEquals(-32602, missingMeta.getAsJsonObject("error").get("code").getAsInt)
      val (unknownStatus, unknown) = send("""{"jsonrpc":"2.0","id":6,"method":"resources/list","params":{""" + meta + "}}",
        "MCP-Protocol-Version" -> "2026-07-28", "Mcp-Method" -> "resources/list")
      assertEquals(404, unknownStatus); assertEquals(-32601, unknown.getAsJsonObject("error").get("code").getAsInt)
      val (notificationStatus, _) = send("""{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":3,""" + meta + "}}",
        "MCP-Protocol-Version" -> "2026-07-28", "Mcp-Method" -> "notifications/cancelled")
      assertEquals(202, notificationStatus)
      assertEquals(400, send("{not json")._1)
    } finally service.stop()
  }
}
