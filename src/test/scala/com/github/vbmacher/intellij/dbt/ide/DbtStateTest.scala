package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project._
import com.google.gson.{JsonObject, JsonParser}
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import org.junit.Assert._
import java.nio.file.Files
import scala.jdk.CollectionConverters._

final class DbtStateTest extends DbtCodeInsightTestCase {
  private def workspace = DbtWorkspace.get(getProject)
  private def root = workspace.roots.find(_.name == "analytics").get
  override protected def tearDown(): Unit = try DbtProjectSettings.get(getProject).loadState(new DbtSettingsState) finally super.tearDown()
  private def json(text: String): JsonObject = JsonParser.parseString(text).getAsJsonObject
  private def manifest(nodes: String, extra: String = ""): String =
    """{"metadata":{"dbt_schema_version":"https://schemas.getdbt.com/dbt/manifest/v12.json"},"nodes":""" + nodes + extra + "}"
  private def baseline(text: String): Unit = {
    add("baseline/manifest.json", text)
    root.runOptions.stateDirectory = "baseline"
  }

  def testRejectsWrongContainerShapesAndUnsupportedVersionsWithoutThrowing(): Unit = {
    Vector("[]", "null", "42", "{}", "{bad}",
      """{"metadata":{"dbt_schema_version":[]},"nodes":{}}""",
      """{"metadata":{"dbt_schema_version":"https://schemas.getdbt.com/dbt/manifest/v999.json"},"nodes":{}}""",
      manifest("[]"), manifest("{\"model.analytics.x\":[]}"), manifest("{}", ",\"sources\":[]"),
      manifest("{\"model.analytics.x\":{\"config\":[]}}"),
      manifest("{\"model.analytics.x\":{\"columns\":{\"id\":42}}}"),
      manifest("{\"model.analytics.x\":{\"depends_on\":{\"nodes\":[42]}}}"),
      manifest("{\"model.analytics.x\":{\"unique_id\":\"model.analytics.y\"}}")
    ).foreach(input => assertTrue("Must reject malformed/incompatible shape: " + input, DbtState.parse(input).isLeft))
    (9 to 12).foreach(v => assertTrue(DbtState.parse(manifest("{}").replace("v12.json", s"v$v.json")).isRight))
  }

  def testBaselineDirectoryCannotAliasOutputThroughSymlinksOrManifestLinks(): Unit = {
    val directory = Files.createTempDirectory("dbt-state-paths-")
    try {
      val output = Files.createDirectory(directory.resolve("target"))
      val state = Files.createDirectory(directory.resolve("state"))
      val manifest = Files.writeString(state.resolve("manifest.json"), "{}")
      val alias = Files.createSymbolicLink(directory.resolve("alias"), output)
      assertTrue(DbtState.separate(alias, output).isLeft)
      assertTrue(DbtState.separate(state, alias.resolve("future")).isRight)
      val stateAlias = Files.createSymbolicLink(directory.resolve("state-alias"), state)
      assertTrue(DbtState.separate(state, stateAlias.resolve("future")).isLeft)
      Files.createSymbolicLink(output.resolve("manifest.json"), manifest)
      assertTrue(DbtState.separate(state, output).isLeft)
      Files.delete(output.resolve("manifest.json"))
      Files.createSymbolicLink(state.resolve("indirect"), output)
      Files.delete(manifest)
      Files.createSymbolicLink(state.resolve("manifest.json"), output.resolve("future-manifest.json"))
      assertTrue(DbtState.separate(state, output).isLeft)
    } finally {
      val stream = Files.walk(directory)
      try stream.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists) finally stream.close()
    }
  }

  def testConfigAndColumnMetadataChangesAreVisibleWithBaselineRelations(): Unit = {
    project()
    add("models/orders.sql", "select 1 as amount")
    add("models/schema.yml", "models: [{name: orders, description: Orders, config: {materialized: table, tags: [finance]}, columns: [{name: amount, data_type: decimal, description: Revenue}]}]\n")
    baseline(manifest("""{"model.analytics.orders":{"raw_code":"select 1 as amount","description":"Orders","config":{"materialized":"view","tags":["finance"]},"columns":{"amount":{"data_type":"integer","description":"Old description"}},"relation_name":"PROD.FINANCE.ORDERS"}}"""))
    val changes = DbtState.compare(workspace, root).toOption.get
    val order = changes.find(_.id == "model.analytics.orders").get
    assertEquals("modified", order.status)
    assertTrue(order.reason.contains("Config changed: materialized"))
    assertTrue(order.reason.contains("Column metadata changed: amount.data_type"))
    assertTrue(order.reason.contains("Column metadata changed: amount.description"))
    assertEquals("PROD.FINANCE.ORDERS", order.relation)
  }

  def testCompatibleUnchangedSourceAndMetadataDoNotBecomeChanges(): Unit = {
    project()
    add("models/orders.sql", "{{ config(materialized='table') }} select 1 as amount")
    add("models/schema.yml", "models: [{name: orders, columns: [{name: amount, data_type: integer}]}]\n")
    baseline(manifest("""{"model.analytics.orders":{"raw_code":"{{ config(materialized='table') }} select 1 as amount","config":{"materialized":"table"},"columns":{"amount":{"data_type":"integer","description":""}}}}"""))
    assertTrue(DbtState.compare(workspace, root).toOption.get.isEmpty)
  }

  def testSourcePropertiesDescriptionSurvivesCodeSymbolWithoutCopiedAttributes(): Unit = {
    project()
    add("models/orders.sql", "select 1")
    add("models/schema.yml", "models: [{name: orders, description: Correct description}]\n")
    val original = workspace
    val source = new DbtWorkspace(getProject, original.roots, original.files, original.symbols.map(s =>
      if (s.isCode) s.copy(attributes = s.attributes - "description", mapping = None) else s))
    val data = DbtState.parse(manifest("""{"model.analytics.orders":{"raw_code":"select 1","description":"Correct description","config":{},"columns":{}}}""")).toOption.get
    assertTrue(DbtState.compareManifest(source, root, data).isEmpty)
  }

  def testClearlyAuthoredNewConfigurationKeysHaveReviewableReasons(): Unit = {
    project()
    add("models/orders.sql", "select 1")
    add("models/schema.yml", "models: [{name: orders, config: {materialized: table, post_hook: 'select 2'}}]\n")
    baseline(manifest("""{"model.analytics.orders":{"raw_code":"select 1","config":{},"columns":{}}}"""))
    val change = DbtState.compare(workspace, root).toOption.get.head
    assertEquals("modified", change.status)
    assertTrue(change.reason.contains("Config added: materialized"))
    assertTrue(change.reason.contains("Config added: post-hook"))
  }

  def testResolvedNestedDefaultsAreUnknownRatherThanFalseMetadataChanges(): Unit = {
    project()
    add("models/orders.sql", "select 1 as amount")
    add("models/schema.yml", "models: [{name: orders, config: {contract: {enforced: true}}, columns: [{name: amount, constraints: [{type: not_null}]}]}]\n")
    baseline(manifest("""{"model.analytics.orders":{"raw_code":"select 1 as amount","config":{"contract":{"enforced":true,"alias_types":true}},"columns":{"amount":{"constraints":[{"type":"not_null","warn_unenforced":true}]}}}}"""))
    val change = DbtState.compare(workspace, root).toOption.get.head
    assertEquals("unknown", change.status)
    assertTrue(change.reason.contains("Resolved nested config defaults unknown: contract"))
    assertTrue(change.reason.contains("Resolved column defaults unknown: amount.constraints"))
  }

  def testNormalModelBuildPassesExactStateFlagsToFakeExecutableAndKeepsBaselineReadOnly(): Unit = {
    project()
    val code = add("models/orders.sql", "select 1")
    val directory = Files.createTempDirectory("dbt state command ")
    Disposer.register(getTestRootDisposable, () => com.intellij.openapi.util.io.FileUtil.delete(directory.toFile))
    Files.writeString(directory.resolve("dbt_project.yml"), "name: analytics\nconfig-version: 2\n")
    val state = Files.createDirectory(directory.resolve("state with spaces"))
    val stateFile = Files.writeString(state.resolve("manifest.json"), manifest("{}"))
    val original = Files.readString(stateFile)
    val profiles = Files.createDirectory(directory.resolve("profiles with spaces"))
    val target = directory.resolve("active target")
    val executable = directory.resolve("fake dbt")
    Files.writeString(executable, """#!/usr/bin/python3
import json,os,sys
with open('invocation.json','w') as f: json.dump({'args':sys.argv[1:],'profiles':os.environ['DBT_PROFILES_DIR'],'target':os.environ['DBT_TARGET_PATH']},f)
os.makedirs(os.environ['DBT_TARGET_PATH'],exist_ok=True)
with open(os.path.join(os.environ['DBT_TARGET_PATH'],'manifest.json'),'w') as f: json.dump({'metadata':{'dbt_schema_version':'https://schemas.getdbt.com/dbt/manifest/v12.json'},'nodes':{}},f)
""")
    assertTrue(executable.toFile.setExecutable(true))
    val options = root.runOptions
    options.projectRoot = directory.toString; options.executable = executable.toString; options.target = "dev"
    options.profilesDirectory = profiles.toString; options.artifactDirectory = target.toString
    options.stateDirectory = state.toString; options.defer = true; options.favorState = true; options.notifications = false
    val w = workspace
    val selection = DbtRunSelection.available(w).find(_.path == code.getVirtualFile.getPath).get
    val command = DbtCommand.create(w, selection, "Build", fullRefresh = false).toOption.get
    assertEquals(1, command.arguments.count(_ == "--state"))
    val runner = new DbtRunner(getProject); Disposer.register(getTestRootDisposable, runner)
    runner.console.getComponent
    assertTrue(runner.start(command))
    PlatformTestUtil.waitWithEventsDispatching("State build fake executable completes", () => !runner.isRunning, 15)
    val invocation = json(Files.readString(directory.resolve("invocation.json")))
    val arguments = invocation.getAsJsonArray("args").asScala.map(_.getAsString).toVector
    assertTrue(arguments.sliding(2).exists(_ == Vector("--state", state.toRealPath().toString)))
    assertTrue(arguments.sliding(2).exists(_ == Vector("--target", "dev")))
    assertTrue(arguments.contains("--defer")); assertTrue(arguments.contains("--favor-state"))
    assertEquals(profiles.toString, invocation.get("profiles").getAsString)
    assertEquals(target.toString, invocation.get("target").getAsString)
    assertTrue(Files.isRegularFile(target.resolve("manifest.json")))
    assertEquals(original, Files.readString(stateFile))
    options.defer = false
    assertTrue(DbtCommand.create(w, selection, "Build", fullRefresh = false).isLeft)
    options.defer = true
    add(".dbt-navigation.yml", "engine: v2\n")
    assertTrue(DbtCommand.create(workspace, selection, "Build", fullRefresh = false).left.toOption.get.contains("Core"))
  }

  def testInsufficientAndDynamicConfigProduceUnknownInsteadOfUnchanged(): Unit = {
    project()
    add("models/orders.sql", "select 1 as amount")
    add("models/schema.yml", "models: [{name: orders, config: {schema: \"{{ target.schema }}\"}, columns: [{name: amount}]}]\n")
    baseline(manifest("""{"model.analytics.orders":{"raw_code":"select 1 as amount","config":{"schema":"PROD","materialized":"table"},"columns":{"amount":{}}}}"""))
    val changes = DbtState.compare(workspace, root).toOption.get
    assertEquals("unknown", changes.head.status)
    assertTrue(changes.head.reason.contains("Dynamic config: schema"))
    assertTrue(changes.head.reason.contains("Current resolved config unknown: materialized"))
  }

  def testVersionAndPackageIdentitiesAndGeneratedTestsRemainDistinct(): Unit = {
    project()
    add("models/orders_v2.sql", "select 2")
    add("models/schema.yml", "models: [{name: orders, versions: [{v: 2}]}]\n")
    add("dbt_packages/pkg/dbt_project.yml", "name: pkg\nconfig-version: 2\n")
    add("dbt_packages/pkg/models/orders.sql", "select 3")
    baseline(manifest("""{"model.analytics.orders.v1":{"raw_code":"select 1","config":{},"columns":{}},"model.pkg.orders":{"raw_code":"select 3","config":{},"columns":{}},"test.analytics.unique_orders.id":{"original_file_path":"models/schema.yml","test_metadata":{"name":"unique"},"config":{},"columns":{}}}"""))
    val changes = DbtState.compare(workspace, root).toOption.get.map(c => c.id -> c.status).toMap
    assertEquals("removed", changes("model.analytics.orders.v1"))
    assertEquals("added", changes("model.analytics.orders.v2"))
    assertFalse(changes.contains("model.pkg.orders"))
    assertEquals("unknown", changes("test.analytics.unique_orders.id"))
  }

  def testPreviewSeparatesSelectedFromConditionalDeferredAndSourceDependencies(): Unit = {
    project()
    add("models/sources.yml", "sources: [{name: raw, tables: [{name: orders, columns: [{name: amount}]}]}]\n")
    add("models/staging.sql", "select amount from {{ source('raw', 'orders') }}")
    add("models/report.sql", "select amount from {{ ref('staging') }}")
    baseline(manifest("""{"model.analytics.staging":{"raw_code":"select amount from {{ source('raw', 'orders') }}","config":{},"columns":{},"relation_name":"PROD.FINANCE.STAGING"},"model.analytics.report":{"raw_code":"select amount from {{ ref('staging') }}","config":{},"columns":{}}}"""))
    root.runOptions.defer = true
    val preview = DbtState.preview(workspace, root, "report").toOption.get
    assertEquals(Vector("model.analytics.report"), preview.selected.map(_.uniqueId))
    val deferred = preview.dependencies.find(_.id == "model.analytics.staging").get
    assertEquals("candidate", deferred.resolution); assertEquals("PROD.FINANCE.STAGING", deferred.relation)
    assertTrue(deferred.reason.contains("was not queried")); assertTrue(deferred.evidence.contains("manifest.json"))
    assertEquals("source", preview.dependencies.find(_.id == "source.analytics.raw.orders").get.resolution)
    root.runOptions.favorState = true
    assertEquals("deferred", DbtState.preview(workspace, root, "report").toOption.get.dependencies.find(_.id == "model.analytics.staging").get.resolution)
  }

  def testStateModifiedGraphSelectorAndExcludesConstructReviewableLocalSelection(): Unit = {
    project()
    add("models/staging.sql", "select 2 as amount")
    add("models/report.sql", "select * from {{ ref('staging') }}")
    baseline(manifest("""{"model.analytics.staging":{"raw_code":"select 1 as amount","config":{},"columns":{}},"model.analytics.report":{"raw_code":"select * from {{ ref('staging') }}","config":{},"columns":{}}}"""))
    val preview = DbtState.preview(workspace, root, "state:modified+", "report").toOption.get
    assertEquals(Vector("model.analytics.staging"), preview.selected.map(_.uniqueId))
  }

  def testMissingPhysicalBaselineRelationStaysUnknownAndSourceFilesAreUntouched(): Unit = {
    project()
    val code = add("models/staging.sql", "select 1")
    add("models/report.sql", "select * from {{ ref('staging') }}")
    baseline(manifest("""{"model.analytics.staging":{"raw_code":"select 1","config":{},"columns":{}},"model.analytics.report":{"raw_code":"select * from {{ ref('staging') }}","config":{},"columns":{}}}"""))
    root.runOptions.defer = true
    val before = root.psiFile("baseline/manifest.json").get.getText
    val preview = DbtState.preview(workspace, root, "report").toOption.get
    assertEquals("unknown", preview.dependencies.head.resolution)
    assertEquals("", preview.dependencies.head.relation)
    assertEquals(before, root.psiFile("baseline/manifest.json").get.getText)
    assertEquals("select 1", code.getText)
  }
}
