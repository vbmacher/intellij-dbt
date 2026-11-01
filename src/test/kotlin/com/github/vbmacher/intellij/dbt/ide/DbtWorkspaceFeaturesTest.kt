package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.navigation.DbtNavigation
import com.github.vbmacher.intellij.dbt.navigation.DbtSqlAnalysis
import com.github.vbmacher.intellij.dbt.project.*
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.mock.Mock
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.replaceService
import com.intellij.ui.jcef.JBCefApp
import com.intellij.util.concurrency.AppExecutorUtil
import org.junit.Assert.*

import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit

/** Fixtures describe visible resource identities and metadata rather than the implementation's internal steps. */
class DbtWorkspaceFeaturesTest : DbtTestCase() {
    private fun resource(name: String) = workspace.symbols.find { s -> s.name == name && s.isCode }!!

    fun testVirtualEnvironmentsDoNotIntroduceDbtProjectsOrWorkspaceFiles() {
        project()
        add("models/orders.sql", "select 1 as id")
        listOf(".venv", "venv").forEach { environment ->
            add("$environment/lib/dbt/include/adapter/dbt_project.yml", "name: adapter_internal\nconfig-version: 2\n")
            add("$environment/lib/dbt/include/adapter/models/internal.sql", "select 2 as id")
            add("$environment/profiles.yml", "private: {}\n")
        }
        val w = workspace
        assertEquals(listOf("analytics"), w.roots.map { it.name })
        assertEquals(listOf("orders"), w.symbols.filter { it.isCode }.map { it.name })
        assertFalse(w.files.any { f -> f.virtualFile.path.contains("/venv/") || f.virtualFile.path.contains("/.venv/") })
    }

    fun testDeletedFilesAreIgnoredByBackgroundPresentation() {
        project()
        assertEquals(
            "Native Diff PSI files may have no virtual file",
            null,
            DbtWorkspace.rootForFile(getProject(), null)
        )
        val file = add("models/removed.sql", "select 1").virtualFile
        assertTrue(DbtWorkspace.rootForFile(getProject(), file) != null)
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(getProject(), object : Runnable {
            override fun run(): Unit = file.parent.delete(this)
        })
        assertFalse(file.isValid)
        assertEquals(null, DbtWorkspace.rootForFile(getProject(), file))
        assertNull(com.github.vbmacher.intellij.dbt.ide.textmate.DbtSqlFileTypeOverrider().getOverriddenFileType(file))
    }

    private fun documentedProject() {
        project("profile: analytics\n")
        add(
            "profiles.yml",
            "analytics:\n  target: dev\n  outputs:\n    dev: {type: duckdb, schema: main}\n    prod: {type: duckdb, schema: prod}\n"
        )
        add(
            "models/orders.sql",
            "{{ config(materialized='incremental') }}\nselect * from {{ source('raw', 'events') }}"
        )
        add(
            "models/schema.yml",
            """models:
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
"""
        )
        add("models/report.sql", "select * from {{ ref('orders') }}")
        add(
            "target/manifest.json",
            """{"metadata":{"dbt_schema_version":"https://schemas.getdbt.com/dbt/manifest/v12.json","dbt_version":"1.10.0","generated_at":"2099-01-01T00:00:00Z"},"nodes":{
      "model.analytics.orders":{"unique_id":"model.analytics.orders","name":"orders","resource_type":"model","package_name":"analytics","original_file_path":"models/orders.sql","database":"warehouse","schema":"mart","alias":"physical_orders","relation_name":"\"warehouse\".\"mart\".\"physical_orders\"","config":{"materialized":"incremental","database":null,"schema":null},"compiled_code":"select * from warehouse.landing.actual_events","depends_on":{"nodes":["source.analytics.raw.events"]}},
      "test.analytics.unique_orders_id":{"unique_id":"test.analytics.unique_orders_id","name":"unique_orders_id","test_metadata":{"name":"unique"},"resource_type":"test","package_name":"analytics","column_name":"id","depends_on":{"nodes":["model.analytics.orders"]}}
      },"sources":{"source.analytics.raw.events":{"unique_id":"source.analytics.raw.events","name":"events","source_name":"raw","resource_type":"source","package_name":"analytics","database":"warehouse","schema":"landing","identifier":"actual_events","relation_name":"warehouse.landing.actual_events"}}}"""
        )
    }

    fun testDocumentedModelSharesSourceAndCompiledMetadataWithSidebar() {
        documentedProject()
        val s = resource("orders")
        val data = DbtResourceData.get(workspace, s)
        assertEquals("Orders <strong> & details", data.description)
        assertEquals(listOf("finance"), data.tags)
        assertEquals("bigint", data.columns.first().dataType)
        assertEquals("warehouse", data.attributes["database"])
        assertEquals("mart", data.attributes["schema"])
        assertTrue(data.tests.any { t -> t.name == "unique" && t.column == "id" })
        assertEquals("A test declared in YAML and its manifest must be counted once", 2, data.tests.size)
        assertTrue(data.compiledSql?.startsWith("select") == true)
        val doc = DbtGraphData.documentation(workspace, s)
        assertEquals(listOf("analytics.raw.events"), doc.getAsJsonArray("upstream").map { it.asString }.toList())
        assertEquals(listOf("analytics.report"), doc.getAsJsonArray("downstream").map { it.asString }.toList())
        val html = DbtDocumentation.render(workspace, s)
        assertTrue(org.jsoup.Jsoup.parse(html).text().contains("Orders & details"))
        assertTrue(html.contains("Upstream (1)"))
        assertFalse("Hide Airflow context when no tasks are available", html.contains("Airflow context"))
        assertFalse(html.contains("<li>analytics.materialized</li>"))
        assertFalse(html.contains("<li>analytics.raw</li>"))
    }

    fun testMarkdownDescriptionsRenderFormattingAndOnlySafeLinks() {
        val html = DbtDocumentation.markdown(
            """# Orders

- **Important** `id`
- [Guide](https://example.com/docs)

```sql
select '<value>'
```

<script>alert('bad')</script><img src=x onerror=alert(1)>
[Bad](javascript:alert) [Local](file:///etc/passwd)
"""
        )
        val doc = org.jsoup.Jsoup.parse(html)
        assertEquals("Orders", doc.select("h1").text())
        assertEquals(2, doc.select("li").size)
        assertEquals("Important", doc.select("strong").text())
        assertTrue(doc.select("pre code").text().contains("<value>"))
        assertEquals("https://example.com/docs", doc.select("a[href]").attr("href"))
        assertEquals(1, doc.select("a[href]").size)
        assertTrue(doc.select("script,img,[onerror]").isEmpty())
    }

    fun testNativeDocumentationProviderUsesExactReferenceRangesAndRefreshesAfterEdit() {
        documentedProject()
        val file = configure("models/hover.sql", "select * from {{ ref('or<caret>ders') }}")
        val provider = DbtDocumentationTargetProvider()
        val target = provider.documentationTargets(file, myFixture.caretOffset)
        assertEquals(1, target.size)
        assertEquals("analytics.orders", target.first().computePresentation().presentableText)
        assertTrue(provider.documentationTargets(file, 2).isEmpty())
        val pointer = target.first().createPointer()
        edit(file, "select * from {{ ref('missing') }}")
        assertNull(pointer.dereference())
    }

    fun testUnknownVersionAndAmbiguousPackageReferencesDoNotProduceDocumentation() {
        project()
        add("models/orders_v1.sql", "select 1")
        add("models/schema.yml", "models: [{name: orders, latest_version: 1, versions: [{v: 1}]}]\n")
        val file = configure("models/report.sql", "select * from {{ ref('or<caret>ders', v=99) }}")
        assertTrue(DbtDocumentationTargetProvider().documentationTargets(file, myFixture.caretOffset).isEmpty())
    }

    fun testNativeDocumentationKeepsAllKnownColumns() {
        project()
        add("models/orders.sql", "select 1")
        add(
            "models/schema.yml",
            "models:\n- name: orders\n  columns:\n" + (1..19).map { n -> "  - {name: column_$n, data_type: int}\n" }
                .joinToString("")
        )
        val html = DbtDocumentation.render(workspace, resource("orders"))
        assertTrue(html.contains("Columns (19)"));
        assertFalse(html.contains("more columns"));
        assertTrue(html.contains(">column_19</a>"))
        assertEquals(19, DbtGraphData.documentation(workspace, resource("orders")).getAsJsonArray("columns").size())
    }

    fun testGraphIncludesOnlyResourceCardsWithUpstreamToDownstreamEdges() {
        documentedProject()
        add("seeds/countries.csv", "id\n1\n")
        add("snapshots/history.sql", "{% snapshot history %}select * from {{ ref('orders') }}{% endsnapshot %}")
        add(
            "models/exposures.yml",
            "exposures:\n- name: dashboard\n  type: dashboard\n  depends_on: [\"ref('report')\"]\n"
        )
        val snapshot = DbtGraphData.build(workspace, null, 5, 5)
        val kinds = snapshot.payload.getAsJsonArray("nodes").map { it.asJsonObject.get("kind").asString }.toSet()
        assertEquals(setOf("model", "source_table", "seed", "snapshot", "exposure"), kinds)
        val names = snapshot.payload.getAsJsonArray("nodes").map { it.asJsonObject.get("name").asString }.toList()
        assertEquals(1, names.count { it == "orders" });
        assertFalse(names.any { it.startsWith("unique_") })
        val edges = snapshot.payload.getAsJsonArray("edges").map { it.asJsonObject }
        assertTrue(edges.any { e ->
            e.get("source").asString.endsWith("source.analytics.raw.events") && e.get("target").asString.endsWith(
                "model.analytics.orders"
            )
        })
    }

    fun testGraphDepthExpansionAndCyclesDoNotDuplicateResources() {
        project()
        (0..8).forEach { n ->
            add(
                "models/m$n.sql",
                if (n == 0) "select 1" else "select * from {{ ref('m${n - 1}') }}"
            )
        }
        val focus = DbtResourceIdentity.key(workspace, resource("m4"))
        val shallow = DbtGraphData.build(workspace, (focus), 1, 1).payload
        assertEquals(3, shallow.getAsJsonArray("nodes").size())
        assertEquals(2, shallow.getAsJsonArray("boundary").size())
        val expanded = DbtGraphData.build(workspace, (focus), 6, 6).payload
        assertEquals(9, expanded.getAsJsonArray("nodes").size())
        edit(resource("m0").file, "select * from {{ ref('m8') }}")
        assertEquals(9, DbtGraphData.build(workspace, (focus), 20, 20).targets.size)
    }

    fun testRunnerCommandsKeepVersionTargetAndCwdIntact() {
        documentedProject()
        val properties = resource("orders").mapping!!.containingFile
        edit(
            properties,
            properties.text.replace(
                "- name: orders\n",
                "- name: orders\n  latest_version: 2\n  versions: [{v: 2, defined_in: orders}]\n"
            )
        )
        val w = workspace;
        val root = w.roots.first()
        val model = resource("orders")
        assertEquals(("2"), model.version)
        val selected = DbtRunSelection.available(w).find { it.path == model.file.virtualFile.path }!!
        assertTrue(DbtCommand.create(w, selected, "Source Freshness", fullRefresh = false).isFailure)
        DbtCommand.Actions.filterNot { it == "Source Freshness" }.forEach { action ->
            val command = DbtCommand.create(w, selected, action, fullRefresh = true).getOrThrow()
            assertEquals(root.directory.path, command.directory)
            assertTrue(command.environment.getValue("DBT_PROFILES_DIR").endsWith(root.directory.path))
            assertTrue(command.arguments.windowed(2).any { it == listOf("--target", "dev") })
            assertEquals(action in setOf("Run", "Build"), command.arguments.contains("--full-refresh"))
            if (action != "Generate Docs") assertTrue(command.arguments.contains("fqn:analytics.orders.v2"))
        }
        val folder = DbtRunSelection.available(w).find { it.path == root.directory.path + "/models" }!!
        assertTrue(DbtCommand.create(w, folder, "Preview", fullRefresh = false).isFailure)
    }

    fun testPreviewFormatsColumnsNullAndNoData() {
        assertEquals("No data returned.", DbtPreview.format("log\n{\"preview\":\"[]\"}\n"))
        val table =
            DbtPreview.format("log\n{\"preview\":\"[{\\\"id\\\":1,\\\"name\\\":null},{\\\"id\\\":2,\\\"name\\\":\\\"Alice\\\"}]\"}\n")
        assertTrue(table.contains("id | name"));
        assertTrue(table.contains("null"));
        assertTrue(table.contains("Alice"))
        val actualDbtOutput =
            "12:00:00 Running with dbt=1.10.0\n{\n  \"node\": \"orders\",\n  \"show\": [\n    {\"ID\": 1, \"NAME\": null}\n  ]\n}\n"
        assertTrue(DbtPreview.format(actualDbtOutput).contains("NAME"))
        assertEquals("No data returned.", DbtPreview.format("logs\n{\n \"show\": []\n}\n"))
    }

    fun testWarningsUseExactRangesAndIgnoreCommentStringDynamicAndPartialCalls() {
        project()
        add("models/sources.yml", "sources: [{name: raw, tables: [{name: events}]}]\n")
        val file = configure(
            "models/report.sql",
            """select * from {{ ref('mis<caret>sing') }}
-- {{ ref('commented') }}
select '{{ ref("literal") }}'
{# {{ ref('jinja_comment') }} #}
select * from {{ ref(runtime_name) }}
select * from {{ source('unknown', 'table') }}
select * from {{ source('raw', 'missing_table') }}
{{ ref('partial'
"""
        )
        val annotator = DbtUnresolvedAnnotator()
        val input = annotator.collectInformation(file)!!
        val result = AppExecutorUtil.getAppExecutorService().submit(Callable { annotator.doAnnotate(input) })
            .get(10, TimeUnit.SECONDS)
        assertEquals(
            listOf(
                "Unresolved ref: 'missing'",
                "Unresolved source: 'unknown'",
                "Unresolved source table: 'raw.missing_table'"
            ), result.diagnostics.map { it.message })
        assertEquals("missing", result.diagnostics.first().range.substring(file.text))
        // Native highlighting must expose warning severity, not merely an internal diagnostic list.
        myFixture.enableInspections(DbtUnresolvedReferenceInspection())
        val highlights = myFixture.doHighlighting().filter { h -> h.description?.startsWith("Unresolved") == true }
        assertEquals(3, highlights.size)
        assertTrue(highlights.all { it.severity == com.intellij.lang.annotation.HighlightSeverity.WARNING })
    }

    fun testAddingDefinitionRemovesMissingWarningAndJinjaHostsParticipate() {
        project()
        val file = configure("models/query.jinja2", "{{ ref('mis<caret>sing') }}")
        assertTrue(DbtNavigation.inFile(file).any { it.unresolvedMessage != null })
        add("models/missing.sql", "select 1")
        assertFalse(DbtNavigation.inFile(file).any { it.unresolvedMessage != null })
    }

    fun testArtifactOnlyDocumentationAndSourcePhysicalIdentifier() {
        documentedProject()
        val source = workspace.symbols.find { s -> s.kind == "source_table" && s.name == "events" }!!
        val data = DbtResourceData.get(workspace, source)
        assertEquals("Raw events", data.description)
        assertEquals("actual_events", data.attributes["identifier"])
        assertEquals("warehouse.landing.actual_events", data.attributes["relation_name"])
        val file = configure("models/source_hover.sql", "select * from {{ source('raw', 'ev<caret>ents') }}")
        val targets = DbtDocumentationTargetProvider().documentationTargets(file, myFixture.caretOffset)
        assertEquals(1, targets.size)
        assertEquals("analytics.raw.events", targets.get(0).computePresentation().presentableText)
        val html = DbtDocumentation.render(workspace, source)
        assertTrue(html.contains("<b>analytics.raw.events</b>"))
        assertTrue(html.contains("<td>Database</td><td>warehouse</td>"))
        assertTrue(html.contains("<td>Schema</td><td>landing</td>"))
        assertTrue(html.contains("warehouse.landing.actual_events"))
        assertEquals("analytics.raw.events", DbtGraphData.documentation(workspace, source).get("name").asString)
    }

    fun testSourceHoverDistinguishesLogicalSourcesWithoutPhysicalMetadata() {
        project()
        add(
            "models/sources.yml",
            "sources: [{name: raw, tables: [{name: events}]}, {name: archive, tables: [{name: events}]}]\n"
        )
        for (name in listOf("raw", "archive")) {
            val file = configure("models/${name}_hover.jinja", "{{ source('$name', 'ev<caret>ents') }}")
            val target = DbtDocumentationTargetProvider().documentationTargets(file, myFixture.caretOffset).get(0)
            assertEquals("analytics.$name.events", target.computePresentation().presentableText)
            val source =
                workspace.symbols.find { s -> s.kind == "source_table" && s.owner == name && s.name == "events" }!!
            val html = DbtDocumentation.render(workspace, source)
            assertTrue(html.contains("<b>analytics.$name.events</b>"))
            assertFalse(html.contains("<td>Database</td>"))
            assertFalse(html.contains("<td>Schema</td>"))
            assertFalse(html.contains("<td>Relation</td>"))
        }
    }

    fun testDocsBlocksAndMacroArgumentsRefreshInNativeDocumentation() {
        project()
        add("macros/math.sql", "{% macro double(value) %}{{ value * 2 }}{% endmacro %}")
        add(
            "macros/schema.yml",
            "macros: [{name: double, description: 'Doubles a value', arguments: [{name: value, type: integer, description: Input}]}]\n"
        )
        val docs = add("docs/orders.md", "{% docs orders_description %}Documented orders{% enddocs %}")
        add("models/orders.sql", "select 1")
        add("models/schema.yml", "models: [{name: orders, description: \"{{ doc('orders_description') }}\"}]\n")
        assertEquals("Documented orders", DbtResourceData.get(workspace, resource("orders")).description)
        edit(docs, "{% docs orders_description %}Updated orders{% enddocs %}")
        assertEquals("Updated orders", DbtResourceData.get(workspace, resource("orders")).description)
        val file = configure("models/report.sql", "select {{ dou<caret>ble(2) }}")
        val targets = DbtDocumentationTargetProvider().documentationTargets(file, myFixture.caretOffset)
        assertEquals(1, targets.size)
        val macroSymbol = workspace.symbols.find { s -> s.kind == "macro" && !s.isProperty }!!
        val html = DbtDocumentation.render(workspace, macroSymbol)
        assertTrue(html.contains("double(value)"));
        assertTrue(html.contains("Doubles a value"));
        assertTrue(html.contains("integer"))
    }

    fun testStaticExpressionsBuiltinsAndWrappersUseTheNavigationMissingProof() {
        project()
        add("macros/lookup.sql", "{% macro lookup(name) %}{{ builtins.ref(name) }}{% endmacro %}")
        val file = configure(
            "models/report.sql",
            """{% set prefix = 'missing_' %}
{{ ref(prefix ~ 'model') }}
{{ builtins.ref('missing_builtin', v=99) }}
{{ lookup('missing_wrapper') }}
{{ ref('absent_package', 'runtime_unknown') }}
{{ ref('partial', var('nested') }}<caret>"""
        )
        val messages = DbtNavigation.inFile(file).mapNotNull { it.unresolvedMessage }
        assertTrue(messages.contains("Unresolved ref: 'missing_model'"))
        assertTrue(messages.contains("Unresolved ref: 'missing_builtin' (version 99)"))
        assertTrue(messages.contains("Unresolved ref: 'missing_wrapper'"))
        assertFalse(messages.any { it.contains("runtime_unknown") })
        assertFalse(messages.any { it.contains("partial") })
    }

    fun testMissingConfiguredOrMalformedArtifactsSuppressMissingResourceClaims() {
        project()
        configureSettings { it.manifestPath = "unavailable/manifest.json" }
        val file = configure("models/report.sql", "{{ ref('mis<caret>sing') }}")
        val annotator = DbtUnresolvedAnnotator()

        fun diagnosticCount(): Int {
            val input = annotator.collectInformation(file)!!
            return AppExecutorUtil.getAppExecutorService().submit(Callable { annotator.doAnnotate(input) })
                .get(10, TimeUnit.SECONDS).diagnostics.size
        }

        assertEquals(0, diagnosticCount())
        add("unavailable/manifest.json", "{bad json")
        assertEquals(0, diagnosticCount())
    }

    fun testSettingsSerializeEveryGraphOptionAndKeepRootsIndependent() {
        project()
        val settings = DbtProjectSettings.get(getProject())
        val root = workspace.roots.first().directory.path
        settings.options(root).target = "dev"
        settings.options(root).executableArguments = "analytics"
        settings.options(root + "/another").target = "prod"
        for (direction in listOf("LR", "TB", "RL", "BT")) {
            settings.getState().direction = direction
            val xml = com.intellij.util.xmlb.XmlSerializer.serialize(settings.getState())
            val restored = com.intellij.util.xmlb.XmlSerializer.deserialize(xml, DbtSettingsState::class.java)
            assertEquals(direction, restored.direction)
            assertEquals("dev", restored.projects.get(root)!!.target); assertEquals(
                "prod",
                restored.projects.get(root + "/another")!!.target
            )
            assertEquals("analytics", restored.projects.get(root)!!.executableArguments)
            assertEquals("", restored.projects.get(root + "/another")!!.executableArguments)
        }
    }

    fun testRunnerUsesFullNestedFqnAndConsumingProjectForPackageModels() {
        project("profile: analytics\n")
        add("profiles.yml", "analytics: {target: dev, outputs: {dev: {type: duckdb}}}\n")
        add("dbt_packages/util/dbt_project.yml", "name: util\nconfig-version: 2\n")
        val modelFile = add("dbt_packages/util/models/staging/orders.sql", "select 1")
        val w = workspace
        val owner = w.root(modelFile)!!
        val consuming = w.executionRoot(owner)
        assertEquals("analytics", consuming.name)
        val model = w.codeSymbols(modelFile).first()
        val command = DbtCommand.create(
            w,
            DbtRunSelection.available(w).find { it.path == model.file.virtualFile.path }!!,
            "Run",
            fullRefresh = false
        ).getOrThrow()
        assertTrue(command.arguments.contains("fqn:util.staging.orders"))
        assertEquals(consuming.directory.path, command.directory)
        assertEquals("dev", command.target)
    }

    fun testUnresolvedInspectionUsesConfiguredSeverity() {
        project()
        configure("models/report.sql", "{{ ref('mis<caret>sing') }}")
        myFixture.enableInspections(DbtUnresolvedReferenceInspection())
        val key = com.intellij.codeInsight.daemon.HighlightDisplayKey.find("DbtUnresolvedReference")!!
        val profile =
            com.intellij.profile.codeInspection.InspectionProjectProfileManager.getInstance(getProject()).currentProfile
                .let { it as com.intellij.codeInspection.ex.InspectionProfileImpl }
        val previous = profile.getErrorLevel(key, myFixture.file)
        try {
            profile.setErrorLevel(key, com.intellij.codeHighlighting.HighlightDisplayLevel.ERROR, getProject())
            val warnings =
                myFixture.doHighlighting().filter { h -> h.description?.startsWith("Unresolved ref:") == true }
            assertEquals(1, warnings.size)
            assertEquals(com.intellij.lang.annotation.HighlightSeverity.ERROR, warnings.first().severity)
        } finally {
            profile.setErrorLevel(key, previous, getProject())
        }
    }

    fun testChangingRunnerTargetRefreshesExistingNavigationReferences() {
        project("profile: analytics\n")
        add(
            "profiles.yml",
            "analytics:\n  target: dev\n  outputs:\n    dev: {schema: development}\n    prod: {schema: production}\n"
        )
        val file = configure("models/report.sql", "select {{ target.sch<caret>ema }}")

        fun current() =
            DbtNavigation.at(file, myFixture.caretOffset).flatMap { it.candidates }.map { it.text }.joinToString("")

        assertTrue(current().contains("development"))
        val settings = DbtProjectSettings.get(getProject())
        settings.options(workspace.roots.first().directory.path).target = "prod"
        settings.changed()
        assertTrue(current().contains("production"))
    }

    fun testRunnerResolvesTheExplicitSelectionAndItsProject() {
        project("profile: analytics\n")
        add("profiles.yml", "analytics: {target: dev, outputs: {dev: {type: duckdb}}}\n")
        val chosen = add("models/orders.sql", "{{ config(materialized='incremental') }} select 1")
        add("other/dbt_project.yml", "name: marketing\nconfig-version: 2\nprofile: marketing\n")
        add("other/profiles.yml", "marketing: {target: prod, outputs: {prod: {type: duckdb}}}\n")
        configure("other/models/orders.sql", "{{ config(materialized='incremental') }} select 2<caret>")
        val w = workspace
        val selected = DbtRunSelection.available(w).find { it.path == chosen.virtualFile.path }!!
        val command = DbtCommand.create(w, selected, "Run", fullRefresh = true).getOrThrow()
        assertTrue(command.arguments.contains("fqn:analytics.orders"))
        assertEquals("dev", command.target)
        assertFalse(command.directory.endsWith("/other"))
        assertTrue(command.arguments.contains("--full-refresh"))
    }

    fun testConfiguredArtifactDirectoryRemainsExcludedFromSourceResources() {
        project()
        add("models/orders.sql", "select 1")
        add("models/generated/shadow.sql", "select 2")
        val settings = DbtProjectSettings.get(getProject())
        settings.options(workspace.roots.first().directory.path).artifactDirectory = "models/generated"
        settings.changed()
        assertTrue(workspace.symbols.any { s -> s.isCode && s.name == "orders" })
        assertFalse(workspace.symbols.any { s -> s.isCode && s.name == "shadow" })
    }

    fun testCombinedDocExpressionsPreserveAllContentAndUnsupportedExpressions() {
        project()
        add("docs/descriptions.md", "{% docs one %}First{% enddocs %}\n{% docs two %}Second{% enddocs %}")
        add("models/orders.sql", "select 1")
        val properties = add("models/schema.yml", "models: [{name: orders}]\n")
        val cases = listOf(
            "{{ doc('one') ~ doc('two') }}" to "FirstSecond",
            "Before {{ doc('one') ~ ' / ' ~ doc('two') }} after" to "Before First / Second after",
            "{{ doc('one') }} and {{ doc('two') }}" to "First and Second",
            "{{- doc('one') ~ doc('two') -}}" to "FirstSecond",
            "{{ doc('one') ~ doc('missing') }}" to "{{ doc('one') ~ doc('missing') }}",
            "{{ doc('one') | upper }}" to "{{ doc('one') | upper }}",
            "{{ doc('one')" to "{{ doc('one')",
            "{{ doc('one') if execute else doc('two') }}" to "{{ doc('one') if execute else doc('two') }}"
        )
        cases.forEach { (input, expected) ->
            edit(
                properties,
                "models: [{name: orders, description: \"" + input + "\", columns: [{name: id, description: \"" + input + "\"}]}]\n"
            )
            val data = DbtResourceData.get(workspace, resource("orders"))
            assertEquals(input, expected, data.description)
            assertEquals(input, expected, data.columns.first().description)
        }
    }

    fun testResourceDocumentationIncludesAllColumnsAndTestsWithoutASidebar() {
        project();
        add("models/orders.sql", "select 1")
        add(
            "models/schema.yml", "models:\n- name: orders\n  description: Orders\n  columns:\n" +
                (1..20).map { i -> "  - name: column_$i\n    description: Description $i\n" }
                    .joinToString("") + "  data_tests: [unique]\n"
        )
        val w = workspace;
        val model = w.symbols.find { s -> s.kind == "model" && s.isCode }!!
        val html = DbtDocumentation.render(w, model)
        assertTrue(html.contains("column_20"));
        assertTrue(html.contains("Description 20"));
        assertTrue(html.contains("unique"))
        assertFalse(html.contains("open the Lineage documentation sidebar"))
        val target = DbtBuiltinPsiDocumentationTargetProvider().documentationTarget(model.element, model.element)
        assertNotNull("Declarations support native Quick Documentation", target)
        assertNotNull(target!!.createPointer().dereference())
    }

    fun testLineageDocumentationLinksTestsToTheirExactSourceDeclarations() {
        project()
        add("models/orders.sql", "select 1 as id")
        val properties = add(
            "models/test sources.yml",
            "models:\n- name: orders\n  data_tests:\n  - ab_test:\n      arguments: {value: 1}\n  columns:\n  - name: id\n    tests: [not_null]\n  - name: other_id\n    data_tests: [not_null]\n"
        )
        val w = workspace
        val model = w.symbols.find { s -> s.kind == "model" && s.isCode }!!
        val tests = DbtResourceData.get(w, model).tests
        assertEquals(3, tests.size)
        val html = org.jsoup.Jsoup.parse(DbtDocumentation.render(w, model, linkTestSources = true))
        val links = html.select("a[href^=dbt-test:]")
        assertEquals(3, links.size)
        tests.forEach { test ->
            val source = test.source!!
            assertEquals(properties, source.containingFile)
            assertEquals(test.name, source.text)
            val link = links.find { a ->
                java.net.URLDecoder.decode(
                    a.attr("href").removePrefix("dbt-test:"),
                    java.nio.charset.StandardCharsets.UTF_8
                ) == test.sourceId!!
            }!!
            assertEquals(test.name, link.text())
        }
        assertEquals(3, tests.mapNotNull { it.sourceId }.distinct().size)
        assertFalse(
            "Native documentation does not use Lineage message links",
            DbtDocumentation.render(w, model).contains("dbt-test:")
        )
        assertEquals(null, DbtTestData("unavailable").sourceId)
    }

    fun testStructureViewNavigatesCteDefinitionsAndIgnoresUnrelatedSql() {
        project()
        val file =
            configure("models/orders.sql", "with a as (select 1 as id), b as (select id from a) select * from b<caret>")
        val factory = DbtSqlStructureViewFactory()
        val builder = factory.getStructureViewBuilder(file).let { it as TreeBasedStructureViewBuilder }
        val model = builder.createStructureViewModel(myFixture.editor)
        try {
            val nodes = model.root.children.map { it.let { it as StructureViewTreeElement } }
            assertEquals(listOf("a", "b"), nodes.map { it.presentation.presentableText!!.split('.').last() }.toList())
            nodes[1].navigate(true)
            assertEquals(file.text.indexOf("b as"), myFixture.caretOffset)
        } finally {
            model.dispose()
        }
        val plain = add("elsewhere/plain.sql", "select 1")
        assertNull(factory.getStructureViewBuilder(plain))
    }

    fun testGeneratedSqlOpensInReadOnlyEditorAndGenerationActionsAreNative() {
        project();
        val source = configure("models/orders.sql", "select 1<caret>")
        DbtSqlEditors.open(getProject(), "orders compiled", "select 2")
        val generated = FileEditorManager.getInstance(getProject()).selectedFiles.first()
        assertFalse(generated.isWritable)
        assertEquals("select 2", String(generated.contentsToByteArray(), java.nio.charset.StandardCharsets.UTF_8))
        assertEquals("select 1", source.text)
        listOf(
            "Dbt.GenerateColumns",
            "Dbt.InheritDescriptions",
            "Dbt.EditProperties",
            "Dbt.OpenCompiledSql",
            "Dbt.CompareSql"
        )
            .forEach { id -> assertNotNull(id, ActionManager.getInstance().getAction(id)) }
    }

    fun testNativeDependencyHierarchyPreservesValueAndFilterOrigins() {
        project()
        add("models/base.sql", "select 1 as id, 1 as active")
        val file = add("models/report.sql", "select id from {{ ref('base') }} where active = 1")
        val w = workspace;
        val model = w.codeSymbols(file).first()
        val resourceEdges = DbtHierarchy.related(w, model, downstream = false)
        assertTrue(resourceEdges.any { it.first.name == "base" })
        val column = DbtSqlAnalysis.get(w, file)!!.columns.first()
        val symbol = DbtSymbol("sql_projection", column.name, model.namespace, file, column.range, owner = model.name)
        val origins = DbtHierarchy.related(w, symbol, downstream = false)
        assertTrue(origins.any { it.second.startsWith("value") })
        assertTrue(origins.any { it.second.startsWith("filter") })
    }

    private var ui: Disposable? = null

    override fun tearDown() {
        try {
            ui?.let(Disposer::dispose)
        } finally {
            super.tearDown()
        }
    }

    private fun status(panel: DbtToolWindowPanel): String = panel.lastSnapshot?.let { snapshot ->
        val nodes = snapshot.payload.getAsJsonArray("nodes")
        val focus = snapshot.payload.get("focus")?.asString.orEmpty()
        val name = snapshot.targets[focus]?.symbol?.presentableText ?: "None"
        "Lineage focus: " + name + " Resources: " + nodes.size()
    }.orEmpty()

    private fun setupRestoringEditor(): Triple<DbtToolWindowPanel, (VirtualFile) -> Unit, VirtualFile> {
        ui = Disposer.newDisposable()
        Disposer.register(getTestRootDisposable(), ui!!)
        project()
        add("models/events.sql", "select 1")
        val model = add("models/orders.sql", "select * from {{ ref('events') }}").virtualFile
        add("models/report.sql", "select * from {{ ref('orders') }}")
        add("models/unrelated.sql", "select 2")
        var selected: VirtualFile? = null
        getProject().replaceService(FileEditorManager::class.java, object : Mock.MyFileEditorManager() {
            override fun getProject(): com.intellij.openapi.project.Project = this@DbtWorkspaceFeaturesTest.project

            override fun getComposite(file: VirtualFile): com.intellij.openapi.fileEditor.impl.EditorComposite? = null

            override fun getOpenFiles(): Array<VirtualFile> = listOfNotNull(selected).toTypedArray()

            override fun getSelectedFiles(): Array<VirtualFile> = listOfNotNull(selected).toTypedArray()
        }, ui!!)
        newRunner(service = true, parent = ui!!)
        // Keep graph refresh tests independent of native browser availability and its ready callback.
        val testMode = Registry.get("ide.browser.jcef.testMode.enabled")
        val enabled = Registry.get("ide.browser.jcef.enabled")
        val wasTestMode = testMode.asBoolean();
        val wasEnabled = enabled.asBoolean()
        val panel = try {
            testMode.setValue(true);
            enabled.setValue(false)
            DbtToolWindowPanel(getProject())
        } finally {
            enabled.setValue(wasEnabled)
            JBCefApp.isSupported() // Restore the cached support result while test mode still forces a fresh check.
            testMode.setValue(wasTestMode)
        }
        Disposer.register(ui!!, panel)
        panel.refresh(follow = true)
        await("Initial refresh precedes editor restoration") {
            status(panel).contains("Lineage focus: None") && status(
                panel
            ).contains("Resources: 4")
        }
        return Triple(panel, { file -> selected = file }, model)
    }

    fun testBackgroundRefreshRetainsInitialModelFocusWhenItReplacesAFollowRequest() {
        val (panel, select, model) = setupRestoringEditor()
        select(model)
        panel.refresh(follow = true)
        panel.refresh(follow = false)
        await("Restored model reaches the panel") { status(panel).contains("Lineage focus: analytics.orders") }
        assertTrue(
            "Show the model's three connected resources, excluding the unrelated model",
            status(panel).contains("Resources: 3")
        )
        val unrelated = model.parent.findChild("unrelated.sql")!!
        select(unrelated)
        panel.refresh(follow = false)
        await("Background refresh sees the new editor") { status(panel).contains("Lineage focus: analytics.orders") }
        assertTrue("An established graph focus survives background refreshes", status(panel).contains("Resources: 3"))
    }

    fun testRestoredEditorOpeningInitializesFocusWithoutASelectionChange() {
        val (panel, select, model) = setupRestoringEditor()
        select(model)
        getProject().messageBus.syncPublisher(FileEditorManagerListener.FILE_EDITOR_MANAGER)
            .fileOpened(FileEditorManager.getInstance(getProject()), model)
        await("Opening the restored editor refreshes the panel") { status(panel).contains("Lineage focus: analytics.orders") }
        assertTrue(status(panel).contains("Resources: 3"))
    }
}
