package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.navigation.DbtNavigation
import com.github.vbmacher.intellij.dbt.project._
import com.google.gson.{JsonArray, JsonObject}
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.util.TextRange
import com.intellij.util.concurrency.AppExecutorUtil
import org.junit.Assert._
import java.util.concurrent.{Callable, TimeUnit}
import scala.jdk.CollectionConverters._

/** Fixtures describe visible resource identities and metadata rather than the implementation's internal steps. */
final class DbtWorkspaceFeaturesTest extends DbtCodeInsightTestCase {
  override protected def tearDown(): Unit = try DbtProjectSettings.get(getProject).loadState(new DbtSettingsState) finally super.tearDown()
  private def workspace = DbtWorkspace.get(getProject)
  private def resource(name: String) = workspace.symbols.find(s => s.name == name && s.isCode).get
  private def documentedProject(): Unit = {
    project("profile: analytics\n")
    add("profiles.yml", "analytics:\n  target: dev\n  outputs:\n    dev: {type: duckdb, schema: main}\n    prod: {type: duckdb, schema: prod}\n")
    add("models/orders.sql", "{{ config(materialized='incremental') }}\nselect * from {{ source('raw', 'events') }}")
    add("models/schema.yml", """models:
- name: orders
  description: "Orders <strong> & details"
  config: {materialized: incremental, tags: [finance]}
  columns:
  - name: id
    data_type: bigint
    description: Identifier
    data_tests: [unique, not_null]
sources:
- name: raw
  schema: landing
  tables:
  - name: events
    identifier: actual_events
    description: Raw events
    columns: [{name: event_id, data_type: int}]
""")
    add("models/report.sql", "select * from {{ ref('orders') }}")
    add("target/manifest.json", """{"metadata":{"dbt_schema_version":"https://schemas.getdbt.com/dbt/manifest/v12.json","dbt_version":"1.10.0","generated_at":"2099-01-01T00:00:00Z"},"nodes":{
      "model.analytics.orders":{"unique_id":"model.analytics.orders","name":"orders","resource_type":"model","package_name":"analytics","original_file_path":"models/orders.sql","database":"warehouse","schema":"mart","alias":"physical_orders","relation_name":"\"warehouse\".\"mart\".\"physical_orders\"","config":{"materialized":"incremental"},"compiled_code":"select * from warehouse.landing.actual_events","depends_on":{"nodes":["source.analytics.raw.events"]}},
      "test.analytics.unique_orders_id":{"unique_id":"test.analytics.unique_orders_id","name":"unique_orders_id","test_metadata":{"name":"unique"},"resource_type":"test","package_name":"analytics","column_name":"id","depends_on":{"nodes":["model.analytics.orders"]}}
      },"sources":{"source.analytics.raw.events":{"unique_id":"source.analytics.raw.events","name":"events","source_name":"raw","resource_type":"source","package_name":"analytics","database":"warehouse","schema":"landing","identifier":"actual_events","relation_name":"warehouse.landing.actual_events"}}}""")
  }

  def testDocumentedModelSharesSourceAndCompiledMetadataWithSidebar(): Unit = {
    documentedProject()
    val s = resource("orders")
    val data = DbtResourceData.get(workspace, s)
    assertEquals("Orders <strong> & details", data.description)
    assertEquals(Vector("finance"), data.tags)
    assertEquals("bigint", data.columns.head.dataType)
    assertTrue(data.tests.exists(t => t.name == "unique" && t.column == "id"))
    assertEquals("A test declared in YAML and its manifest must be counted once", 2, data.tests.size)
    assertTrue(data.compiledSql.exists(_.startsWith("select")))
    val doc = DbtGraphData.documentation(workspace, s)
    assertEquals(Vector("analytics.raw.events"), doc.getAsJsonArray("upstream").asScala.map(_.getAsString).toVector)
    assertEquals(Vector("analytics.report"), doc.getAsJsonArray("downstream").asScala.map(_.getAsString).toVector)
    val html = DbtDocumentation.render(workspace, s)
    assertTrue(html.contains("Orders &lt;strong&gt; &amp; details"))
    assertFalse(html.contains("Orders <strong>"))
    assertTrue(html.contains("Upstream (1)"))
    assertFalse(html.contains("<li>analytics.materialized</li>"))
    assertFalse(html.contains("<li>analytics.raw</li>"))
  }

  def testNativeDocumentationProviderUsesExactReferenceRangesAndRefreshesAfterEdit(): Unit = {
    documentedProject()
    val file = configure("models/hover.sql", "select * from {{ ref('or<caret>ders') }}")
    val provider = new DbtDocumentationTargetProvider
    val target = provider.documentationTargets(file, myFixture.getCaretOffset).asScala
    assertEquals(1, target.size)
    assertEquals("analytics.orders", target.head.computePresentation().getPresentableText)
    assertTrue(provider.documentationTargets(file, 2).isEmpty)
    val pointer = target.head.createPointer()
    edit(file, "select * from {{ ref('missing') }}")
    assertNull(pointer.dereference())
  }

  def testUnknownVersionAndAmbiguousPackageReferencesDoNotProduceDocumentation(): Unit = {
    project()
    add("models/orders_v1.sql", "select 1")
    add("models/schema.yml", "models: [{name: orders, latest_version: 1, versions: [{v: 1}]}]\n")
    val file = configure("models/report.sql", "select * from {{ ref('or<caret>ders', v=99) }}")
    assertTrue(new DbtDocumentationTargetProvider().documentationTargets(file, myFixture.getCaretOffset).isEmpty)
  }

  def testLargeHoverIsBoundedAndSidebarKeepsAllColumns(): Unit = {
    project()
    add("models/orders.sql", "select 1")
    add("models/schema.yml", "models:\n- name: orders\n  columns:\n" + (1 to 19).map(n => s"  - {name: column_$n, data_type: int}\n").mkString)
    val html = DbtDocumentation.render(workspace, resource("orders"))
    assertTrue(html.contains("Columns (19)")); assertTrue(html.contains("4 more columns")); assertFalse(html.contains("<b>column_19</b>"))
    assertEquals(19, DbtGraphData.documentation(workspace, resource("orders")).getAsJsonArray("columns").size())
  }

  def testGraphIncludesOnlyResourceCardsWithUpstreamToDownstreamEdges(): Unit = {
    documentedProject()
    add("seeds/countries.csv", "id\n1\n")
    add("snapshots/history.sql", "{% snapshot history %}select * from {{ ref('orders') }}{% endsnapshot %}")
    add("models/exposures.yml", "exposures:\n- name: dashboard\n  type: dashboard\n  depends_on: [\"ref('report')\"]\n")
    val snapshot = DbtGraphData.build(workspace, None, 5, 5, exposures = true)
    val kinds = snapshot.payload.getAsJsonArray("nodes").asScala.map(_.getAsJsonObject.get("kind").getAsString).toSet
    assertEquals(Set("model", "source_table", "seed", "snapshot", "exposure"), kinds)
    val names = snapshot.payload.getAsJsonArray("nodes").asScala.map(_.getAsJsonObject.get("name").getAsString).toVector
    assertEquals(1, names.count(_ == "orders")); assertFalse(names.exists(_.startsWith("unique_")))
    val edges = snapshot.payload.getAsJsonArray("edges").asScala.map(_.getAsJsonObject)
    assertTrue(edges.exists(e => e.get("source").getAsString.endsWith("source.analytics.raw.events") && e.get("target").getAsString.endsWith("model.analytics.orders")))
    val noExposures = DbtGraphData.build(workspace, None, 5, 5, exposures = false)
    assertFalse(noExposures.payload.getAsJsonArray("nodes").toString.contains("dashboard"))
  }

  def testGraphDepthExpansionAndCyclesDoNotDuplicateResources(): Unit = {
    project()
    (0 to 8).foreach(n => add(s"models/m$n.sql", if (n == 0) "select 1" else s"select * from {{ ref('m${n - 1}') }}"))
    val focus = DbtGraphData.key(workspace, resource("m4"))
    val shallow = DbtGraphData.build(workspace, Some(focus), 1, 1, exposures = true).payload
    assertEquals(3, shallow.getAsJsonArray("nodes").size())
    assertEquals(2, shallow.getAsJsonArray("boundary").size())
    val expanded = DbtGraphData.build(workspace, Some(focus), 6, 6, exposures = true).payload
    assertEquals(9, expanded.getAsJsonArray("nodes").size())
    edit(resource("m0").file, "select * from {{ ref('m8') }}")
    assertEquals(9, DbtGraphData.build(workspace, Some(focus), 20, 20, exposures = true).targets.size)
  }

  def testRunnerCommandsKeepVersionTargetCwdAndInlineSqlIntact(): Unit = {
    documentedProject()
    val w = workspace; val root = w.roots.head
    val model = resource("orders").copy(version = Some("2"))
    DbtCommand.Actions.foreach { action =>
      val command = DbtCommand.create(root, Some(model), action, "", fullRefresh = true).toOption.get
      assertEquals(root.directory.getPath, command.directory)
      assertTrue(command.environment("DBT_PROFILES_DIR").endsWith(root.directory.getPath))
      assertTrue(command.arguments.sliding(2).exists(_ == Vector("--target", "dev")))
      assertEquals(Set("Run", "Build")(action), command.arguments.contains("--full-refresh"))
      if (action != "Generate Docs") assertTrue(command.arguments.contains("fqn:analytics.orders.v2"))
    }
    val sql = "select 'path with spaces';\nselect 2"
    val inline = DbtCommand.create(root, None, "Preview", sql, fullRefresh = true).toOption.get
    assertTrue(inline.arguments.sliding(2).exists(_ == Vector("--inline", sql)))
    assertFalse(inline.arguments.contains("--select")); assertFalse(inline.arguments.contains("--full-refresh"))
    assertTrue(DbtCommand.create(root, None, "Run", sql, fullRefresh = false).isLeft)
  }

  def testPreviewFormatsColumnsNullAndNoData(): Unit = {
    assertEquals("No data returned.", DbtPreview.format("log\n{\"preview\":\"[]\"}\n"))
    val table = DbtPreview.format("log\n{\"preview\":\"[{\\\"id\\\":1,\\\"name\\\":null},{\\\"id\\\":2,\\\"name\\\":\\\"Alice\\\"}]\"}\n")
    assertTrue(table.contains("id | name")); assertTrue(table.contains("null")); assertTrue(table.contains("Alice"))
    val actualDbtOutput = "12:00:00 Running with dbt=1.10.0\n{\n  \"node\": \"orders\",\n  \"show\": [\n    {\"ID\": 1, \"NAME\": null}\n  ]\n}\n"
    assertTrue(DbtPreview.format(actualDbtOutput).contains("NAME"))
    assertEquals("No data returned.", DbtPreview.format("logs\n{\n \"show\": []\n}\n"))
  }

  def testWarningsUseExactRangesAndIgnoreCommentStringDynamicAndPartialCalls(): Unit = {
    project()
    add("models/sources.yml", "sources: [{name: raw, tables: [{name: events}]}]\n")
    val file = configure("models/report.sql", """select * from {{ ref('mis<caret>sing') }}
-- {{ ref('commented') }}
select '{{ ref("literal") }}'
{# {{ ref('jinja_comment') }} #}
select * from {{ ref(runtime_name) }}
select * from {{ source('unknown', 'table') }}
select * from {{ source('raw', 'missing_table') }}
{{ ref('partial'
""")
    val annotator = new DbtUnresolvedAnnotator
    val input = annotator.collectInformation(file)
    val result = AppExecutorUtil.getAppExecutorService.submit(new Callable[DbtAnnotationResult] {
      override def call(): DbtAnnotationResult = annotator.doAnnotate(input)
    }).get(10, TimeUnit.SECONDS)
    assertEquals(Vector("Unresolved ref: 'missing'", "Unresolved source: 'unknown'", "Unresolved source table: 'raw.missing_table'"), result.diagnostics.map(_.message))
    assertEquals("missing", result.diagnostics.head.range.substring(file.getText))
    // Native highlighting must expose warning severity, not merely an internal diagnostic list.
    myFixture.enableInspections(new DbtUnresolvedReferenceInspection)
    val highlights = myFixture.doHighlighting().asScala.filter(h => Option(h.getDescription).exists(_.startsWith("Unresolved")))
    assertEquals(3, highlights.size)
    assertTrue(highlights.forall(_.getSeverity == com.intellij.lang.annotation.HighlightSeverity.WARNING))
  }

  def testAddingDefinitionRemovesMissingWarningAndJinjaHostsParticipate(): Unit = {
    project()
    val file = configure("models/query.jinja2", "{{ ref('mis<caret>sing') }}")
    assertTrue(DbtNavigation.inFile(file).exists(_.unresolvedMessage.nonEmpty))
    add("models/missing.sql", "select 1")
    assertFalse(DbtNavigation.inFile(file).exists(_.unresolvedMessage.nonEmpty))
  }

  def testArtifactOnlyDocumentationAndSourcePhysicalIdentifier(): Unit = {
    documentedProject()
    val source = workspace.symbols.find(s => s.kind == "source_table" && s.name == "events").get
    val data = DbtResourceData.get(workspace, source)
    assertEquals("Raw events", data.description)
    assertEquals("actual_events", data.attributes("identifier"))
    assertEquals("warehouse.landing.actual_events", data.attributes("relation_name"))
    val file = configure("models/source_hover.sql", "select * from {{ source('raw', 'ev<caret>ents') }}")
    val targets = new DbtDocumentationTargetProvider().documentationTargets(file, myFixture.getCaretOffset)
    assertEquals(1, targets.size())
    assertEquals("analytics.raw.events", targets.get(0).computePresentation().getPresentableText)
    val html = DbtDocumentation.render(workspace, source)
    assertTrue(html.contains("<b>analytics.raw.events</b>"))
    assertTrue(html.contains("<td>Database</td><td>warehouse</td>"))
    assertTrue(html.contains("<td>Schema</td><td>landing</td>"))
    assertTrue(html.contains("warehouse.landing.actual_events"))
    assertEquals("analytics.raw.events", DbtGraphData.documentation(workspace, source).get("name").getAsString)
  }

  def testSourceHoverDistinguishesLogicalSourcesWithoutPhysicalMetadata(): Unit = {
    project()
    add("models/sources.yml", "sources: [{name: raw, tables: [{name: events}]}, {name: archive, tables: [{name: events}]}]\n")
    for (name <- Vector("raw", "archive")) {
      val file = configure(s"models/${name}_hover.jinja", s"{{ source('$name', 'ev<caret>ents') }}")
      val target = new DbtDocumentationTargetProvider().documentationTargets(file, myFixture.getCaretOffset).get(0)
      assertEquals(s"analytics.$name.events", target.computePresentation().getPresentableText)
      val source = workspace.symbols.find(s => s.kind == "source_table" && s.owner == name && s.name == "events").get
      val html = DbtDocumentation.render(workspace, source)
      assertTrue(html.contains(s"<b>analytics.$name.events</b>"))
      assertFalse(html.contains("<td>Database</td>"))
      assertFalse(html.contains("<td>Schema</td>"))
      assertFalse(html.contains("<td>Relation</td>"))
    }
  }
  def testDocsBlocksAndMacroArgumentsRefreshInNativeDocumentation(): Unit = {
    project()
    add("macros/math.sql", "{% macro double(value) %}{{ value * 2 }}{% endmacro %}")
    add("macros/schema.yml", "macros: [{name: double, description: 'Doubles a value', arguments: [{name: value, type: integer, description: Input}]}]\n")
    val docs = add("docs/orders.md", "{% docs orders_description %}Documented orders{% enddocs %}")
    add("models/orders.sql", "select 1")
    add("models/schema.yml", "models: [{name: orders, description: \"{{ doc('orders_description') }}\"}]\n")
    assertEquals("Documented orders", DbtResourceData.get(workspace, resource("orders")).description)
    edit(docs, "{% docs orders_description %}Updated orders{% enddocs %}")
    assertEquals("Updated orders", DbtResourceData.get(workspace, resource("orders")).description)
    val file = configure("models/report.sql", "select {{ dou<caret>ble(2) }}")
    val targets = new DbtDocumentationTargetProvider().documentationTargets(file, myFixture.getCaretOffset)
    assertEquals(1, targets.size())
    val macroSymbol = workspace.symbols.find(s => s.kind == "macro" && !s.isProperty).get
    val html = DbtDocumentation.render(workspace, macroSymbol)
    assertTrue(html.contains("double(value)")); assertTrue(html.contains("Doubles a value")); assertTrue(html.contains("integer"))
  }

  def testStaticExpressionsBuiltinsAndWrappersUseTheNavigationMissingProof(): Unit = {
    project()
    add("macros/lookup.sql", "{% macro lookup(name) %}{{ builtins.ref(name) }}{% endmacro %}")
    val file = configure("models/report.sql", """{% set prefix = 'missing_' %}
{{ ref(prefix ~ 'model') }}
{{ builtins.ref('missing_builtin', v=99) }}
{{ lookup('missing_wrapper') }}
{{ ref('absent_package', 'runtime_unknown') }}
{{ ref('partial', var('nested') }}<caret>""")
    val messages = DbtNavigation.inFile(file).flatMap(_.unresolvedMessage)
    assertTrue(messages.contains("Unresolved ref: 'missing_model'"))
    assertTrue(messages.contains("Unresolved ref: 'missing_builtin' (version 99)"))
    assertTrue(messages.contains("Unresolved ref: 'missing_wrapper'"))
    assertFalse(messages.exists(_.contains("runtime_unknown")))
    assertFalse(messages.exists(_.contains("partial")))
  }

  def testMissingConfiguredOrMalformedArtifactsSuppressMissingResourceClaims(): Unit = {
    project()
    add(".dbt-navigation.yml", "manifest: unavailable/manifest.json\n")
    val file = configure("models/report.sql", "{{ ref('mis<caret>sing') }}")
    val annotator = new DbtUnresolvedAnnotator
    def diagnosticCount(): Int = {
      val input = annotator.collectInformation(file)
      AppExecutorUtil.getAppExecutorService.submit(new Callable[DbtAnnotationResult] {
        override def call(): DbtAnnotationResult = annotator.doAnnotate(input)
      }).get(10, TimeUnit.SECONDS).diagnostics.size
    }
    assertEquals(0, diagnosticCount())
    add("unavailable/manifest.json", "{bad json")
    assertEquals(0, diagnosticCount())
  }

  def testSettingsSerializeEveryGraphOptionAndKeepRootsIndependent(): Unit = {
    project()
    val settings = DbtProjectSettings.get(getProject)
    val root = workspace.roots.head.directory.getPath
    settings.options(root).target = "dev"
    settings.options(root).executableArguments = "analytics"
    settings.options(root + "/another").target = "prod"
    for (direction <- Vector("LR", "TB", "RL", "BT"); edge <- Vector("bezier", "taxi", "round-taxi", "segments", "straight", "unbundled-bezier", "haystack")) {
      settings.getState.direction = direction; settings.getState.edgeStyle = edge
      val xml = com.intellij.util.xmlb.XmlSerializer.serialize(settings.getState)
      val restored = com.intellij.util.xmlb.XmlSerializer.deserialize(xml, classOf[DbtSettingsState])
      assertEquals(direction, restored.direction); assertEquals(edge, restored.edgeStyle)
      assertEquals("dev", restored.projects.get(root).target); assertEquals("prod", restored.projects.get(root + "/another").target)
      assertEquals("analytics", restored.projects.get(root).executableArguments)
      assertEquals("", restored.projects.get(root + "/another").executableArguments)
    }
  }

  def testRunnerUsesFullNestedFqnAndConsumingProjectForPackageModels(): Unit = {
    project("profile: analytics\n")
    add("profiles.yml", "analytics: {target: dev, outputs: {dev: {type: duckdb}}}\n")
    add("dbt_packages/util/dbt_project.yml", "name: util\nconfig-version: 2\n")
    val modelFile = add("dbt_packages/util/models/staging/orders.sql", "select 1")
    val w = workspace
    val owner = w.root(modelFile).get
    val consuming = w.executionRoot(owner)
    assertEquals("analytics", consuming.name)
    val model = w.codeSymbols(modelFile).head
    val command = DbtCommand.create(consuming, Some(model), "Run", "", fullRefresh = false).toOption.get
    assertTrue(command.arguments.contains("fqn:util.staging.orders"))
    assertEquals(consuming.directory.getPath, command.directory)
    assertEquals("dev", command.target)
  }

  def testUnresolvedInspectionUsesConfiguredSeverity(): Unit = {
    project()
    configure("models/report.sql", "{{ ref('mis<caret>sing') }}")
    myFixture.enableInspections(new DbtUnresolvedReferenceInspection)
    val key = com.intellij.codeInsight.daemon.HighlightDisplayKey.find("DbtUnresolvedReference")
    val profile = com.intellij.profile.codeInspection.InspectionProjectProfileManager.getInstance(getProject).getCurrentProfile
      .asInstanceOf[com.intellij.codeInspection.ex.InspectionProfileImpl]
    val previous = profile.getErrorLevel(key, myFixture.getFile)
    try {
      profile.setErrorLevel(key, com.intellij.codeHighlighting.HighlightDisplayLevel.ERROR, getProject)
      val warnings = myFixture.doHighlighting().asScala.filter(h => Option(h.getDescription).exists(_.startsWith("Unresolved ref:")))
      assertEquals(1, warnings.size)
      assertEquals(com.intellij.lang.annotation.HighlightSeverity.ERROR, warnings.head.getSeverity)
    } finally profile.setErrorLevel(key, previous, getProject)
  }

  def testChangingRunnerTargetRefreshesExistingNavigationReferences(): Unit = {
    project("profile: analytics\n")
    add("profiles.yml", "analytics:\n  target: dev\n  outputs:\n    dev: {schema: development}\n    prod: {schema: production}\n")
    val file = configure("models/report.sql", "select {{ target.sch<caret>ema }}")
    def current = DbtNavigation.at(file, myFixture.getCaretOffset).flatMap(_.candidates).map(_.getText).mkString
    assertTrue(current.contains("development"))
    val settings = DbtProjectSettings.get(getProject)
    settings.options(workspace.roots.head.directory.getPath).target = "prod"
    settings.changed()
    assertTrue(current.contains("production"))
  }

  def testRunnerResolvesTheClickedFileRatherThanAnOlderToolbarContext(): Unit = {
    project()
    val oldFile = add("models/orders.sql", "{{ config(materialized='incremental') }} select 1")
    add("other/dbt_project.yml", "name: marketing\nconfig-version: 2\nprofile: marketing\n")
    add("other/profiles.yml", "marketing: {target: prod, outputs: {prod: {type: duckdb}}}\n")
    val current = add("other/models/orders.sql", "{{ config(materialized='incremental') }} select 2")
    val w = workspace
    val oldModel = w.codeSymbols(oldFile).head
    val command = DbtCommand.forFile(w, current, "Run", "", Some(oldModel.id)).toOption.get
    assertTrue(command.arguments.contains("fqn:marketing.orders"))
    assertEquals("prod", command.target)
    assertTrue(command.directory.endsWith("/other"))
    assertFalse(command.arguments.contains("--full-refresh"))
    val scratch = add("scratch.sql", "select 3")
    assertTrue(DbtCommand.forFile(DbtWorkspace.get(getProject), scratch, "Preview", "select 3", None).isRight)
  }

  def testConfiguredArtifactDirectoryRemainsExcludedFromSourceResources(): Unit = {
    project()
    add("models/orders.sql", "select 1")
    add("models/generated/shadow.sql", "select 2")
    val settings = DbtProjectSettings.get(getProject)
    settings.options(workspace.roots.head.directory.getPath).artifactDirectory = "models/generated"
    settings.changed()
    assertTrue(workspace.symbols.exists(s => s.isCode && s.name == "orders"))
    assertFalse(workspace.symbols.exists(s => s.isCode && s.name == "shadow"))
  }

  def testCombinedDocExpressionsPreserveAllContentAndUnsupportedExpressions(): Unit = {
    project()
    add("docs/descriptions.md", "{% docs one %}First{% enddocs %}\n{% docs two %}Second{% enddocs %}")
    add("models/orders.sql", "select 1")
    val properties = add("models/schema.yml", "models: [{name: orders}]\n")
    val cases = Vector(
      "{{ doc('one') ~ doc('two') }}" -> "FirstSecond",
      "Before {{ doc('one') ~ ' / ' ~ doc('two') }} after" -> "Before First / Second after",
      "{{ doc('one') }} and {{ doc('two') }}" -> "First and Second",
      "{{- doc('one') ~ doc('two') -}}" -> "FirstSecond",
      "{{ doc('one') ~ doc('missing') }}" -> "{{ doc('one') ~ doc('missing') }}",
      "{{ doc('one') | upper }}" -> "{{ doc('one') | upper }}",
      "{{ doc('one')" -> "{{ doc('one')",
      "{{ doc('one') if execute else doc('two') }}" -> "{{ doc('one') if execute else doc('two') }}")
    cases.foreach { case (input, expected) =>
      edit(properties, "models: [{name: orders, description: \"" + input + "\", columns: [{name: id, description: \"" + input + "\"}]}]\n")
      val data = DbtResourceData.get(workspace, resource("orders"))
      assertEquals(input, expected, data.description)
      assertEquals(input, expected, data.columns.head.description)
    }
  }

}
