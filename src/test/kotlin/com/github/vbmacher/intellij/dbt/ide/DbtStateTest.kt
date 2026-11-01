package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project.*
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import java.nio.file.Files

class DbtStateTest : DbtTestCase() {
    private fun json(text: String): JsonObject = JsonParser.parseString(text).asJsonObject

    private fun parse(text: String): Result<DbtManifest> =
        DbtManifestReader.get(getProject()).parse(text, DbtManifestPolicy.Baseline)

    private fun manifest(nodes: String, extra: String = ""): String =
        """{"metadata":{"dbt_schema_version":"https://schemas.getdbt.com/dbt/manifest/v12.json"},"nodes":""" + nodes + extra + "}"

    private fun baseline(text: String) {
        add("baseline/manifest.json", text)
        root.runOptions.stateDirectory = "baseline"
    }

    fun testRejectsWrongContainerShapesAndUnsupportedVersionsWithoutThrowing() {
        listOf(
            "[]", "null", "42", "{}", "{bad}",
            """{"metadata":{"dbt_schema_version":[]},"nodes":{}}""",
            """{"metadata":{"dbt_schema_version":"https://schemas.getdbt.com/dbt/manifest/v999.json"},"nodes":{}}""",
            manifest("[]"), manifest("{\"model.analytics.x\":[]}"), manifest("{}", ",\"sources\":[]"),
            manifest("{\"model.analytics.x\":{\"config\":[]}}"),
            manifest("{\"model.analytics.x\":{\"columns\":{\"id\":42}}}"),
            manifest("{\"model.analytics.x\":{\"depends_on\":{\"nodes\":[42]}}}"),
            manifest("{\"model.analytics.x\":{\"unique_id\":\"model.analytics.y\"}}")
        ).forEach { input -> assertTrue("Must reject malformed/incompatible shape: " + input, parse(input).isFailure) }
        (9..12).forEach { v -> assertTrue(parse(manifest("{}").replace("v12.json", "v$v.json")).isSuccess) }
    }

    fun testBaselineDirectoryCannotAliasOutputThroughSymlinksOrManifestLinks() {
        val directory = tempDirectory("dbt-state-paths-")
        val output = Files.createDirectory(directory.resolve("target"))
        val state = Files.createDirectory(directory.resolve("state"))
        val manifest = Files.writeString(state.resolve("manifest.json"), "{}")
        val alias = Files.createSymbolicLink(directory.resolve("alias"), output)
        assertTrue(DbtState.separate(alias, output).isFailure)
        assertTrue(DbtState.separate(state, alias.resolve("future")).isSuccess)
        val stateAlias = Files.createSymbolicLink(directory.resolve("state-alias"), state)
        assertTrue(DbtState.separate(state, stateAlias.resolve("future")).isFailure)
        Files.createSymbolicLink(output.resolve("manifest.json"), manifest)
        assertTrue(DbtState.separate(state, output).isFailure)
        Files.delete(output.resolve("manifest.json"))
        Files.createSymbolicLink(state.resolve("indirect"), output)
        Files.delete(manifest)
        Files.createSymbolicLink(state.resolve("manifest.json"), output.resolve("future-manifest.json"))
        assertTrue(DbtState.separate(state, output).isFailure)
    }

    fun testConfigAndColumnMetadataChangesAreVisibleWithBaselineRelations() {
        project()
        add("models/orders.sql", "select 1 as amount")
        add(
            "models/schema.yml",
            "models: [{name: orders, description: Orders, config: {materialized: table, tags: [finance]}, columns: [{name: amount, data_type: decimal, description: Revenue}]}]\n"
        )
        baseline(manifest("""{"model.analytics.orders":{"raw_code":"select 1 as amount","description":"Orders","config":{"materialized":"view","tags":["finance"]},"columns":{"amount":{"data_type":"integer","description":"Old description"}},"relation_name":"PROD.FINANCE.ORDERS"}}"""))
        val changes = DbtState.compare(workspace, root).getOrThrow()
        val order = changes.find { it.id == "model.analytics.orders" }!!
        assertEquals("modified", order.status)
        assertTrue(order.reason.contains("Config changed: materialized"))
        assertTrue(order.reason.contains("Column metadata changed: amount.data_type"))
        assertTrue(order.reason.contains("Column metadata changed: amount.description"))
        assertEquals("PROD.FINANCE.ORDERS", order.relation)
    }

    fun testCompatibleUnchangedSourceAndMetadataDoNotBecomeChanges() {
        project()
        add("models/orders.sql", "{{ config(materialized='table') }} select 1 as amount")
        add("models/schema.yml", "models: [{name: orders, columns: [{name: amount, data_type: integer}]}]\n")
        baseline(manifest("""{"model.analytics.orders":{"raw_code":"{{ config(materialized='table') }} select 1 as amount","config":{"materialized":"table"},"columns":{"amount":{"data_type":"integer","description":""}}}}"""))
        assertTrue(DbtState.compare(workspace, root).getOrThrow().isEmpty())
    }

    fun testDbtWhitespaceProjectionAndUnitTestIdentitiesDoNotCreateFalseChanges() {
        project()
        add("models/orders.sql", "select 1 as amount\n")
        add(
            "models/schema.yml",
            "unit_tests: [{name: valid_input, model: orders}]\nsources: [{name: raw, schema: analytics, tables: [{name: orders}]}]\n"
        )
        baseline(
            manifest(
                """{"model.analytics.orders":{"raw_code":"select 1 as amount","config":{},"columns":{}}}""",
                """, "unit_tests":{"unit_test.analytics.orders.valid_input":{"unique_id":"unit_test.analytics.orders.valid_input","resource_type":"unit_test","name":"valid_input","package_name":"analytics","config":{},"columns":{}}}, "sources":{"source.analytics.raw.orders":{"config":{},"unrendered_schema":"analytics","columns":{}}}"""
            )
        )
        val w = workspace
        assertEquals("unit_test.analytics.orders.valid_input", w.symbols.find { it.kind == "unit_test" }!!.uniqueId)
        assertTrue(DbtState.compare(w, root).getOrThrow().isEmpty())
    }

    fun testSourcePropertiesDescriptionSurvivesCodeSymbolWithoutCopiedAttributes() {
        project()
        add("models/orders.sql", "select 1")
        add("models/schema.yml", "models: [{name: orders, description: Correct description}]\n")
        val original = workspace
        val source = DbtWorkspace(getProject(), original.roots, original.files, original.symbols.map { s ->
            if (s.isCode) s.copy(attributes = s.attributes - "description", mapping = null) else s
        })
        val data =
            parse(manifest("""{"model.analytics.orders":{"raw_code":"select 1","description":"Correct description","config":{},"columns":{}}}""")).getOrThrow()
        assertTrue(DbtState.compareManifest(source, root, data).isEmpty())
    }

    fun testClearlyAuthoredNewConfigurationKeysHaveReviewableReasons() {
        project()
        add("models/orders.sql", "select 1")
        add("models/schema.yml", "models: [{name: orders, config: {materialized: table, post_hook: 'select 2'}}]\n")
        baseline(manifest("""{"model.analytics.orders":{"raw_code":"select 1","config":{},"columns":{}}}"""))
        val change = DbtState.compare(workspace, root).getOrThrow().first()
        assertEquals("modified", change.status)
        assertTrue(change.reason.contains("Config added: materialized"))
        assertTrue(change.reason.contains("Config added: post-hook"))
    }

    fun testResolvedNestedDefaultsAreUnknownRatherThanFalseMetadataChanges() {
        project()
        add("models/orders.sql", "select 1 as amount")
        add(
            "models/schema.yml",
            "models: [{name: orders, config: {contract: {enforced: true}}, columns: [{name: amount, constraints: [{type: not_null}]}]}]\n"
        )
        baseline(manifest("""{"model.analytics.orders":{"raw_code":"select 1 as amount","config":{"contract":{"enforced":true,"alias_types":true}},"columns":{"amount":{"constraints":[{"type":"not_null","warn_unenforced":true}]}}}}"""))
        val change = DbtState.compare(workspace, root).getOrThrow().first()
        assertEquals("unknown", change.status)
        assertTrue(change.reason.contains("Resolved nested config defaults unknown: contract"))
        assertTrue(change.reason.contains("Resolved column defaults unknown: amount.constraints"))
    }

    fun testNormalModelBuildPassesExactStateFlagsToFakeExecutableAndKeepsBaselineReadOnly() {
        project()
        val code = add("models/orders.sql", "select 1")
        val directory = tempDirectory("dbt state command ")
        Files.writeString(directory.resolve("dbt_project.yml"), "name: analytics\nconfig-version: 2\n")
        val state = Files.createDirectory(directory.resolve("state with spaces"))
        val stateFile = Files.writeString(state.resolve("manifest.json"), manifest("{}"))
        val original = Files.readString(stateFile)
        val profiles = Files.createDirectory(directory.resolve("profiles with spaces"))
        val target = directory.resolve("active target")
        val executable = fakeTool(
            directory.resolve("fake dbt"),
            """#!/usr/bin/python3
import json,os,sys
with open('invocation.json','w') as f: json.dump({'args':sys.argv[1:],'profiles':os.environ['DBT_PROFILES_DIR'],'target':os.environ['DBT_TARGET_PATH']},f)
os.makedirs(os.environ['DBT_TARGET_PATH'],exist_ok=True)
with open(os.path.join(os.environ['DBT_TARGET_PATH'],'manifest.json'),'w') as f: json.dump({'metadata':{'dbt_schema_version':'https://schemas.getdbt.com/dbt/manifest/v12.json'},'nodes':{}},f)
"""
        )
        val options = root.runOptions
        options.projectRoot = directory.toString();
        options.executable = executable.toString();
        options.target = "dev"
        options.profilesDirectory = profiles.toString();
        options.artifactDirectory = target.toString()
        options.stateDirectory = state.toString();
        options.defer = true;
        options.favorState = true;
        options.notifications = false
        val w = workspace
        val selection = DbtRunSelection.available(w).find { it.path == code.virtualFile.path }!!
        val command = DbtCommand.create(w, selection, "Build", fullRefresh = false).getOrThrow()
        assertEquals(1, command.arguments.count { it == "--state" })
        val runner = newRunner()
        assertTrue(runner.start(command))
        await("State build fake executable completes") { !runner.isRunning }
        val invocation = json(Files.readString(directory.resolve("invocation.json")))
        val arguments = invocation.getAsJsonArray("args").map { it.asString }.toList()
        assertTrue(arguments.windowed(2).any { it == listOf("--state", state.toRealPath().toString()) })
        assertTrue(arguments.windowed(2).any { it == listOf("--target", "dev") })
        assertTrue(arguments.contains("--defer"));
        assertTrue(arguments.contains("--favor-state"))
        assertEquals(profiles.toString(), invocation.get("profiles").asString)
        assertEquals(target.toString(), invocation.get("target").asString)
        assertTrue(Files.isRegularFile(target.resolve("manifest.json")))
        assertEquals(original, Files.readString(stateFile))
        options.defer = false
        assertTrue(DbtCommand.create(w, selection, "Build", fullRefresh = false).isFailure)
        options.defer = true
        configureSettings { it.engine = "v2" }
        assertTrue(
            DbtCommand.create(workspace, selection, "Build", fullRefresh = false).exceptionOrNull()!!.message.orEmpty()
                .contains("Core")
        )
    }

    fun testInsufficientAndDynamicConfigProduceUnknownInsteadOfUnchanged() {
        project()
        add("models/orders.sql", "select 1 as amount")
        add(
            "models/schema.yml",
            "models: [{name: orders, config: {schema: \"{{ target.schema }}\"}, columns: [{name: amount}]}]\n"
        )
        baseline(manifest("""{"model.analytics.orders":{"raw_code":"select 1 as amount","config":{"schema":"PROD","materialized":"table"},"columns":{"amount":{}}}}"""))
        val changes = DbtState.compare(workspace, root).getOrThrow()
        assertEquals("unknown", changes.first().status)
        assertTrue(changes.first().reason.contains("Dynamic config: schema"))
        assertTrue(changes.first().reason.contains("Current resolved config unknown: materialized"))
    }

    fun testVersionAndPackageIdentitiesAndGeneratedTestsRemainDistinct() {
        project()
        add("models/orders_v2.sql", "select 2")
        add("models/schema.yml", "models: [{name: orders, versions: [{v: 2}]}]\n")
        add("dbt_packages/pkg/dbt_project.yml", "name: pkg\nconfig-version: 2\n")
        add("dbt_packages/pkg/models/orders.sql", "select 3")
        baseline(manifest("""{"model.analytics.orders.v1":{"raw_code":"select 1","config":{},"columns":{}},"model.pkg.orders":{"raw_code":"select 3","config":{},"columns":{}},"test.analytics.unique_orders.id":{"original_file_path":"models/schema.yml","test_metadata":{"name":"unique"},"config":{},"columns":{}}}"""))
        val changes = DbtState.compare(workspace, root).getOrThrow().map { c -> c.id to c.status }.toMap()
        assertEquals("removed", changes["model.analytics.orders.v1"])
        assertEquals("added", changes["model.analytics.orders.v2"])
        assertFalse(changes.containsKey("model.pkg.orders"))
        assertEquals("unknown", changes["test.analytics.unique_orders.id"])
    }

    fun testPreviewSeparatesSelectedFromConditionalDeferredAndSourceDependencies() {
        project()
        add("models/sources.yml", "sources: [{name: raw, tables: [{name: orders, columns: [{name: amount}]}]}]\n")
        add("models/staging.sql", "select amount from {{ source('raw', 'orders') }}")
        add("models/report.sql", "select amount from {{ ref('staging') }}")
        baseline(manifest("""{"model.analytics.staging":{"raw_code":"select amount from {{ source('raw', 'orders') }}","config":{},"columns":{},"relation_name":"PROD.FINANCE.STAGING"},"model.analytics.report":{"raw_code":"select amount from {{ ref('staging') }}","config":{},"columns":{}}}"""))
        root.runOptions.defer = true
        val preview = DbtState.preview(workspace, root, "report").getOrThrow()
        assertEquals(listOf("model.analytics.report"), preview.selected.map { it.uniqueId })
        val deferred = preview.dependencies.find { it.id == "model.analytics.staging" }!!
        assertEquals("candidate", deferred.resolution);
        assertEquals("PROD.FINANCE.STAGING", deferred.relation)
        assertTrue(deferred.reason.contains("was not queried"));
        assertTrue(deferred.evidence.contains("manifest.json"))
        assertEquals("source", preview.dependencies.find { it.id == "source.analytics.raw.orders" }!!.resolution)
        root.runOptions.favorState = true
        assertEquals(
            "deferred",
            DbtState.preview(workspace, root, "report")
                .getOrThrow().dependencies.find { it.id == "model.analytics.staging" }!!.resolution
        )
    }

    fun testStateModifiedGraphSelectorAndExcludesConstructReviewableLocalSelection() {
        project()
        add("models/staging.sql", "select 2 as amount")
        add("models/report.sql", "select * from {{ ref('staging') }}")
        baseline(manifest("""{"model.analytics.staging":{"raw_code":"select 1 as amount","config":{},"columns":{}},"model.analytics.report":{"raw_code":"select * from {{ ref('staging') }}","config":{},"columns":{}}}"""))
        val preview = DbtState.preview(workspace, root, "state:modified+", "report").getOrThrow()
        assertEquals(listOf("model.analytics.staging"), preview.selected.map { it.uniqueId })
    }

    fun testMissingPhysicalBaselineRelationStaysUnknownAndSourceFilesAreUntouched() {
        project()
        val code = add("models/staging.sql", "select 1")
        add("models/report.sql", "select * from {{ ref('staging') }}")
        baseline(manifest("""{"model.analytics.staging":{"raw_code":"select 1","config":{},"columns":{}},"model.analytics.report":{"raw_code":"select * from {{ ref('staging') }}","config":{},"columns":{}}}"""))
        root.runOptions.defer = true
        val before = root.psiFile("baseline/manifest.json")!!.text
        val preview = DbtState.preview(workspace, root, "report").getOrThrow()
        assertEquals("unknown", preview.dependencies.first().resolution)
        assertEquals("", preview.dependencies.first().relation)
        assertEquals(before, root.psiFile("baseline/manifest.json")!!.text)
        assertEquals("select 1", code.text)
    }
}
