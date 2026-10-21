package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project._
import com.google.gson.{JsonObject, JsonParser}
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import org.junit.Assert._

import java.awt.Component
import javax.swing.{JButton, JComboBox, JList, JPanel, JPasswordField, JTabbedPane, JTextField}
import scala.jdk.CollectionConverters._

/**
 * Contract: features/project-context.md and local-mcp-server.md: owning identities, supported metadata, source authority,
 * uncertain metadata, read-only integrations and their settings.
 */
final class DbtIntegrationAcceptanceTest extends DbtTestCase {
  override protected def root: DbtRoot = workspace.roots.find(r => r.name == "analytics" && !r.directory.getPath.endsWith("/other")).get

  private def json(text: String): JsonObject = JsonParser.parseString(text).getAsJsonObject

  override protected def tearDown(): Unit = try {
    getProject.getService(classOf[DbtAgent]).stop()
    val consumers = getProject.getService(classOf[DbtConsumers])
    consumers.setSecret("");
    consumers.clear()
  } finally super.tearDown()

  def testAirflowSettingsPersistLocalSourcesAndRejectCredentialBearingLinks(): Unit = {
    val state = new DbtConsumersState
    state.airflowPaths = new java.util.ArrayList[String](Vector("/dags/one", "/dags/two").asJava)
    state.airflowUrl = "https://airflow.test/base"
    val restored = XmlSerializer.deserialize(XmlSerializer.serialize(state), classOf[DbtConsumersState])
    assertEquals(Vector("/dags/one", "/dags/two"), restored.airflowPaths.asScala.toVector)
    assertEquals("https://airflow.test/base", restored.airflowUrl)
    assertTrue(DbtAirflow.safeUrl("https://user:secret@airflow.test").isEmpty)
    assertTrue(DbtAirflow.safeUrl("file:///etc/passwd").isEmpty)
    assertTrue(DbtAirflow.safeUrl("javascript:alert(1)").isEmpty)
  }

  def testMetabasePhysicalCollisionsAndStaleMetadataNeverBecomeDirectLineage(): Unit = {
    project()
    val declaration = "sources: [{name: raw, database: warehouse, schema: landing, tables: [{name: orders, identifier: physical_orders, columns: [{name: amount}]}]}]\n"
    add("models/sources.yml", declaration)
    add("dbt_packages/pkg/dbt_project.yml", "name: pkg\nconfig-version: 2\n")
    add("dbt_packages/pkg/models/sources.yml", declaration)
    val card = json("""{"id":7,"name":"Revenue","dataset_query":{"query":{"source-table":42,"fields":[["field",99,null]]}}}""")
    val table = json("""{"id":42,"db_name":"warehouse","schema":"landing","name":"physical_orders","fields":[{"id":99,"name":"amount"}]}""")
    val w = workspace
    val matches = DbtConsumers.mapQuestions(w, "https://metabase.test", Vector(card), Vector(table))
    assertEquals(Set("source.analytics.raw.orders", "source.pkg.raw.orders"), matches.map(_.resource.split("\\|").last).toSet)
    assertTrue(matches.forall(_.confidence.startsWith("inferred")))
    val local = new DbtWorkspace(getProject, w.roots, w.files, w.symbols.filter(s => w.owner(s).contains(root)).map(s =>
      if (s.kind == "source_table") s.copy(attributes = s.attributes.updated("stale", "true")) else s))
    assertTrue(DbtConsumers.mapQuestions(local, "https://metabase.test", Vector(card), Vector(table)).forall(_.confidence.startsWith("inferred")))
    table.addProperty("db_name", "unrelated_target")
    assertTrue(DbtConsumers.mapQuestions(w, "https://metabase.test", Vector(card), Vector(table)).isEmpty)
  }

  def testMetabaseDisconnectedFreshnessAndCredentialSettingsStayExplicitAndSecretFree(): Unit = {
    project()
    val service = getProject.getService(classOf[DbtConsumers])
    assertTrue(service.snapshot.status.contains("Disconnected"))
    assertEquals(0L, service.snapshot.refreshedAt)
    assertTrue(service.refresh(workspace).status.contains("Configure"))
    assertTrue(DbtConsumersCache(Vector.empty, System.currentTimeMillis() - 16 * 60 * 1000, "Metadata refreshed").stale)
    assertFalse(DbtConsumersCache(Vector.empty, System.currentTimeMillis(), "Metadata refreshed").stale)
    service.setSecret("acceptance-secret-must-not-export")
    assertTrue(service.hasSecret)
    val settings = getProject.getService(classOf[DbtConsumersSettings])
    val configurable = new DbtConsumersConfigurable(getProject)
    val panel = configurable.createComponent().asInstanceOf[javax.swing.JPanel]
    val metabase = panel.getComponents.collectFirst {
      case section: JPanel if section.getBorder.isInstanceOf[javax.swing.border.TitledBorder] &&
        section.getBorder.asInstanceOf[javax.swing.border.TitledBorder].getTitle == "Metabase" => section
    }.get
    val key = metabase.getComponents.collectFirst { case field: JPasswordField => field }.get
    assertEquals("acceptance-secret-must-not-export", new String(key.getPassword))
    assertFalse(configurable.isModified)
    configurable.apply()
    assertTrue(service.hasSecret)
    assertFalse(configurable.isModified)
    key.setText("replacement-key")
    assertTrue(configurable.isModified)
    configurable.apply()
    assertEquals("replacement-key", service.apiKey)
    assertFalse(configurable.isModified)
    key.setText("")
    configurable.reset()
    assertEquals("replacement-key", new String(key.getPassword))
    val serialized = JDOMUtil.writeElement(XmlSerializer.serialize(settings.getState))
    assertFalse(serialized.contains("acceptance-secret-must-not-export"))
    assertFalse(service.snapshot.toString.contains("acceptance-secret-must-not-export"))
    key.setText("")
    assertTrue(configurable.isModified)
    configurable.apply()
    assertFalse(service.hasSecret)
    assertFalse(configurable.isModified)
    configurable.disposeUIResources()
  }

  def testMetabaseStalledResponseBodyTimesOutWithoutReplacingTheCache(): Unit = {
    project()
    val server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0)
    val started = new java.util.concurrent.CountDownLatch(1)
    val release = new java.util.concurrent.CountDownLatch(1)
    server.createContext("/api/card", exchange => {
      exchange.sendResponseHeaders(200, 1000)
      exchange.getResponseBody.write('['.toInt)
      exchange.getResponseBody.flush()
      started.countDown()
      try release.await(5, java.util.concurrent.TimeUnit.SECONDS)
      finally exchange.close()
    })
    server.start()
    val service = getProject.getService(classOf[DbtConsumers])
    try {
      getProject.getService(classOf[DbtConsumersSettings]).getState.metabaseUrl = "http://127.0.0.1:" + server.getAddress.getPort
      service.setSecret("bounded-fixture-key")
      val before = service.snapshot
      val start = System.nanoTime()
      val result = service.refresh(workspace, java.time.Duration.ofMillis(500))
      assertTrue("Server sent headers and stalled the response body", started.await(1, java.util.concurrent.TimeUnit.SECONDS))
      assertTrue("Response body deadline must bound the call", System.nanoTime() - start < java.util.concurrent.TimeUnit.SECONDS.toNanos(3))
      assertEquals(before.refreshedAt, result.refreshedAt)
      assertEquals(before.consumers, result.consumers)
      assertTrue(result.status.contains("unavailable"))
      assertFalse(result.toString.contains("bounded-fixture-key"))
    } finally {
      release.countDown();
      server.stop(0)
    }
  }

  def testAgentSchemasMatchRequiredArgumentsAndRejectAdditionalOrInvalidValues(): Unit = {
    project()
    add("models/orders.sql", "select 1")
    val service = getProject.getService(classOf[DbtAgent])
    val schemas = DbtAgentProtocol.schemas.asScala.map(_.getAsJsonObject).map(s => s.get("name").getAsString -> s).toMap
    DbtAgentProtocol.Tools.foreach { case (name, _, required) =>
      val schema = schemas(name).getAsJsonObject("inputSchema")
      assertEquals(required.toSet, schema.getAsJsonArray("required").asScala.map(_.getAsString).toSet)
      assertEquals(DbtAgentProtocol.allowedArguments(name).toSet, schema.getAsJsonObject("properties").keySet().asScala.toSet)
      assertFalse(schema.get("additionalProperties").getAsBoolean)
      assertTrue(schemas(name).getAsJsonObject("annotations").get("readOnlyHint").getAsBoolean)
    }
    Vector(
      """{"project":"analytics","reference":"orders","offset":2147483648}""",
      """{"project":"analytics","reference":"orders","offset":-1}""",
      """{"project":"analytics","reference":"orders","offset":0.5}""",
      """{"project":"analytics","reference":"orders","offset":"0"}""",
      """{"project":"analytics","reference":"orders","shell":"dbt run"}""",
      """{"project":"","reference":"orders"}"""
    ).foreach { args =>
      val request = json("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"resolve_reference","arguments":""" + args + "}}")
      assertTrue(service.rpc(request).getAsJsonObject("result").get("isError").getAsBoolean)
    }
    val unknownTool = json("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"execute_sql","arguments":{}}}""")
    assertEquals(-32602, service.rpc(unknownTool).getAsJsonObject("error").get("code").getAsInt)
    val initialized = service.rpc(json("""{"jsonrpc":"2.0","id":2,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}""")).getAsJsonObject("result")
    assertEquals(DbtAgentProtocol.Version, initialized.getAsJsonObject("serverInfo").get("version").getAsString)
    assertEquals("2025-06-18", initialized.get("protocolVersion").getAsString)
    assertTrue(initialized.getAsJsonObject("capabilities").has("tools"))
    val negotiated = service.rpc(json("""{"jsonrpc":"2.0","id":2,"method":"initialize","params":{"protocolVersion":"2024-11-05"}}""")).getAsJsonObject("result")
    assertEquals(DbtAgentProtocol.LegacyVersions.head, negotiated.get("protocolVersion").getAsString)
    assertEquals(0, service.rpc(json("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")).size())
    service.dispose()
    assertEquals(-32603, service.rpc(json("""{"jsonrpc":"2.0","id":3,"method":"tools/list"}""")).getAsJsonObject("error").get("code").getAsInt)
  }

  def testAgentReadToolsRespectOwningFilesAndExclusionsWithoutStartingDbt(): Unit = {
    project()
    add(".dbtignore", "models/private/**\n")
    val orders = add("models/orders.sql", "select 1 as amount")
    add("models/private/secret.sql", "select 123 as hidden")
    add("other/dbt_project.yml", "name: other\nconfig-version: 2\n")
    val other = add("other/models/report.sql", "select * from {{ ref('orders') }}")
    val w = workspace
    root.runOptions.executable = "dbt-acceptance-must-not-execute"
    val resources = DbtAgentProtocol.call(w, "list_resources", DbtAgentProtocol.obj("project" -> root.directory.getPath))
    val listed = resources.getAsJsonArray("resources").asScala.map(_.getAsJsonObject).toVector
    assertTrue(listed.exists(_.get("resource_id").getAsString == "model.analytics.orders"))
    assertFalse(listed.exists(_.get("path").getAsString.contains("/private/")))
    assertFalse(listed.exists(_.get("path").getAsString.contains("/other/")))
    val args = DbtAgentProtocol.obj("project" -> root.directory.getPath, "file" -> other.getVirtualFile.getPath, "reference" -> "orders")
    args.addProperty("offset", other.getText.indexOf("orders"))
    assertEquals("missing", DbtAgentProtocol.call(w, "resolve_reference", args).get("status").getAsString)
    val docs = DbtAgentProtocol.call(w, "get_documentation", DbtAgentProtocol.obj("project" -> root.directory.getPath, "resource_id" -> "model.analytics.orders"))
    assertEquals(orders.getVirtualFile.getPath, docs.getAsJsonObject("resource").get("path").getAsString)
    val lineage = DbtAgentProtocol.call(w, "get_lineage", DbtAgentProtocol.obj("project" -> root.directory.getPath, "resource_id" -> "model.analytics.orders"))
    assertEquals("partial", lineage.get("status").getAsString)
    assertFalse(getProject.getService(classOf[DbtRunner]).isRunning)
  }

  def testOneResourceSharesIdentityAcrossDeclarationsButIndependentOwnersRemainSeparate(): Unit = {
    project()
    val code = add("models/orders.sql", "select 1")
    val properties = add("models/schema.yml", "models: [{name: orders}]\n")
    val other = add("other/dbt_project.yml", "name: analytics\nconfig-version: 2\n")
    val otherCode = add("other/models/orders.sql", "select 2")
    add("dbt_packages/util/dbt_project.yml", "name: util\nconfig-version: 2\n")
    val packageCode = add("dbt_packages/util/models/orders.sql", "select 3")
    val w = workspace
    val source = w.symbols.find(s => s.file == code && s.isCode).get
    val property = w.symbols.find(s => s.file == properties && s.kind == "model").get
    val independent = w.symbols.find(s => s.file == otherCode && s.isCode).get
    val installed = w.symbols.find(s => s.file == packageCode && s.isCode).get
    assertNotEquals(source.id, property.id)
    assertEquals(DbtResourceIdentity.key(w, source), DbtResourceIdentity.key(w, property))
    assertEquals(source.uniqueId, independent.uniqueId)
    assertNotEquals(DbtResourceIdentity.key(w, source), DbtResourceIdentity.key(w, independent))
    assertNotEquals(DbtResourceIdentity.key(w, source), DbtResourceIdentity.key(w, installed))
    assertEquals(w.executionRoot(w.owner(source).get), w.executionRoot(w.owner(installed).get))
    assertEquals(other.getVirtualFile.getParent, w.owner(independent).get.directory)
  }

  private def reader = DbtManifestReader.get(getProject)

  private def manifest(version: Int, nodes: String, project: String = "analytics"): String =
    s"""{"metadata":{"dbt_schema_version":"https://schemas.getdbt.com/dbt/manifest/v$version.json","project_name":"$project"},"nodes":$nodes}"""

  def testConsumersShareSupportedSchemasAndResourceIdentityWhileBaselineValidationIsStricter(): Unit = {
    val nodes = """{"model.analytics.orders":{"unique_id":"model.analytics.orders","name":"orders"}}"""
    for (version <- 9 to 12; policy <- Vector(DbtManifestPolicy.Navigation, DbtManifestPolicy.TestDiscovery, DbtManifestPolicy.Baseline)) {
      val result = reader.parse(manifest(version, nodes), policy).toOption.get
      assertEquals(version, result.schemaVersion)
      assertEquals(Vector("model.analytics.orders"), result.in("nodes").map(_.id))
    }
    Vector(DbtManifestPolicy.Navigation, DbtManifestPolicy.TestDiscovery, DbtManifestPolicy.Baseline).foreach { policy =>
      assertTrue(reader.parse(manifest(99, nodes), policy).isLeft)
    }
    val incomplete = manifest(12, """{"model.analytics.orders":{"config":[]}}""")
    assertTrue(reader.parse(incomplete, DbtManifestPolicy.Navigation).isRight)
    assertTrue(reader.parse(incomplete, DbtManifestPolicy.Baseline).isLeft)
  }

  def testUnsavedManifestEditsInvalidateCachedRecordsAndDiscoveryChecksTheProject(): Unit = {
    project()
    val file = add("metadata/manifest.json", manifest(12, """{"model.analytics.old":{"name":"old"}}"""))
    val root = workspace.roots.head
    val path = file.getVirtualFile.getPath
    assertEquals(Vector("model.analytics.old"), reader.read(root, path, DbtManifestPolicy.Navigation).toOption.get.resources.map(_.id))
    edit(file, manifest(12, """{"model.analytics.new":{"name":"new"}}"""))
    Vector(DbtManifestPolicy.Navigation, DbtManifestPolicy.TestDiscovery).foreach { policy =>
      assertEquals(Vector("model.analytics.new"), reader.read(root, path, policy).toOption.get.resources.map(_.id))
    }
    edit(file, manifest(12, "{}", "another_project"))
    assertTrue(reader.read(root, path, DbtManifestPolicy.TestDiscovery).isLeft)
    assertTrue(reader.read(root, path, DbtManifestPolicy.Navigation).isRight)
  }

  private def settings = getProject.getService(classOf[DbtConsumersSettings]).getState

  /** Selects `root` in the settings project chooser and returns its manifest picker. */
  private def manifestPicker(fields: Vector[Component], root: DbtRoot): TextFieldWithBrowseButton = {
    fields.collectFirst { case combo: JComboBox[_] if (0 until combo.getItemCount).exists(i => combo.getItemAt(i) == root.directory.getPath) => combo }.get
      .setSelectedItem(root.directory.getPath)
    fields.collectFirst {
      case picker: TextFieldWithBrowseButton if Option(picker.getToolTipText).exists(_.startsWith("Optional manifest file")) => picker
    }.get
  }

  def testDagProjectsApplyResetAndAllowEmptyFolders(): Unit = {
    project()
    settings.airflowPaths.add("dags")
    val root = workspace.roots.head
    val configurable = new DbtSettingsConfigurable(getProject)
    try {
      val tabs = configurable.createComponent().asInstanceOf[JTabbedPane]
      assertEquals(Vector("General", "Integrations"), (0 until tabs.getTabCount).map(tabs.getTitleAt).toVector)
      val fields = components(tabs)
      val manifest = manifestPicker(fields, root)
      assertTrue(fields.exists { case button: JButton => button.getText == "Add folder…"; case _ => false })
      val folders = fields.collectFirst { case list: JList[_] => list }.get
      val url = fields.collectFirst { case text: JTextField if Option(text.getToolTipText).exists(_.startsWith("Optional HTTP")) => text }.get
      assertFalse(configurable.isModified)
      manifest.setText("artifacts/current.json")
      tabs.setSelectedIndex(1)
      url.setText("https://airflow.test")
      assertTrue(configurable.isModified)
      configurable.apply()
      assertEquals("https://airflow.test", settings.airflowUrl)
      assertEquals("artifacts/current.json", DbtProjectSettings.get(getProject).options(root.directory.getPath).manifestPath)
      assertFalse(configurable.isModified)
      folders.setSelectedIndex(0)
      components(tabs.getComponentAt(1)).collectFirst { case button: JButton if button.getText == "Remove" => button }.get.doClick()
      assertTrue(configurable.isModified)
      manifest.setText("discarded.json")
      configurable.reset()
      assertEquals(1, folders.getModel.getSize)
      assertEquals("artifacts/current.json", manifest.getText)
      folders.setSelectedIndex(0)
      components(tabs.getComponentAt(1)).collectFirst { case button: JButton if button.getText == "Remove" => button }.get.doClick()
      configurable.apply()
      assertTrue(settings.airflowPaths.isEmpty)
      configurable.reset()
      assertEquals(0, folders.getModel.getSize)
      assertFalse(configurable.isModified)
      manifest.setText("must-not-save.json")
      url.setText("https://user:password@airflow.test")
      try {
        configurable.apply();
        fail("Credential-bearing browser links must be rejected")
      } catch {
        case _: ConfigurationException => ()
      }
      assertEquals("https://airflow.test", settings.airflowUrl)
      assertEquals("artifacts/current.json", DbtProjectSettings.get(getProject).options(root.directory.getPath).manifestPath)
      val secret = fields.collectFirst { case field: JPasswordField => field }.get
      secret.setText("unsaved-secret")
      configurable.disposeUIResources()
      assertEquals(0, secret.getPassword.length)
    } finally configurable.disposeUIResources()
  }

  def testManifestPickerSavesPerProjectAndCopiesWithRunOptions(): Unit = {
    val config = project()
    val root = workspace.roots.head
    val configurable = new DbtSettingsConfigurable(getProject)
    try {
      val fields = components(configurable.createComponent())
      val manifest = manifestPicker(fields, root)
      manifest.setText("artifacts/current.json")
      assertTrue(configurable.isModified)
      configurable.apply()
      val options = DbtProjectSettings.get(getProject).options(config.getVirtualFile.getParent.getPath)
      assertEquals("artifacts/current.json", options.manifestPath)
      assertEquals("artifacts/current.json", options.copy().manifestPath)
      configurable.reset()
      assertEquals("artifacts/current.json", manifest.getText)
    } finally configurable.disposeUIResources()
  }
}
