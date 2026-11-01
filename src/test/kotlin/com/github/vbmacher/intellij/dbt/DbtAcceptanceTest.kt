package com.github.vbmacher.intellij.dbt

import com.github.vbmacher.intellij.dbt.ide.DbtTestCase
import com.github.vbmacher.intellij.dbt.lineage.DbtLineage
import com.github.vbmacher.intellij.dbt.lineage.DbtSelectors
import com.github.vbmacher.intellij.dbt.navigation.DbtNavigation
import com.github.vbmacher.intellij.dbt.project.DbtSymbolElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import org.junit.Assert.*

/** Regression cases from the navigation roadmap's shared acceptance criteria. */
class DbtAcceptanceTest : DbtTestCase() {
    private fun permutations(values: List<String>): List<List<String>> =
        if (values.isEmpty()) listOf(emptyList()) else values.flatMap { v -> permutations(values - v).map { listOf(v) + it } }

    fun testSingularTestsInheritDataTestsConfiguration() {
        val config = bareProject("data_tests:\n  analytics:\n    +enabled: false\n")
        add("tests/check.sql", "select 1")
        val enabled = add("tests/keep.sql", "{{ config(enabled=true) }} select 1")
        var w = workspace
        assertTrue(w.declarations(setOf("test"), "check", w.roots.first()).isEmpty())
        assertTrue(DbtSelectors.select(w, w.roots.first(), "test:check", includeGraph = false).isEmpty())
        assertEquals(listOf(enabled), w.declarations(setOf("test"), "keep", w.roots.first()).map { it.file })

        edit(config, "name: analytics\ntests:\n  analytics:\n    +enabled: false\n")
        w = workspace
        assertTrue(w.declarations(setOf("test"), "check", w.roots.first()).isEmpty())
        assertEquals(listOf(enabled), w.declarations(setOf("test"), "keep", w.roots.first()).map { it.file })
    }

    fun testDisabledLocalModelFallsBackToEnabledPackageModel() {
        bareProject()
        val local = add("models/orders.sql", "{{ config(enabled=false) }} select 1")
        add("models/schema.yml", "models:\n- name: orders\n  description: local\n")
        val packaged = add("dbt_packages/util/models/orders.sql", "select 2")
        add("dbt_packages/util/dbt_project.yml", "name: util\n")
        val use = add("models/use.sql", "select * from {{ ref('orders') }}")
        assertEquals(listOf(packaged), targetsAt(use, "orders").map { it.containingFile })

        edit(local, "select 1")
        val enabled = targetsAt(use, "orders").map { it.containingFile }
        assertTrue(enabled.contains(local));
        assertFalse(enabled.contains(packaged))
    }

    fun testRequireDbtVersionListDeterminesSupportedFeatures() {
        val config = bareProject()
        add("models/semantic.yml", "semantic_models: [{name: orders, model: ref('orders')}]\n")
        listOf("[\">=1.5.0\", \"<1.6.0\"]", "[\"<1.6.0\", \">=1.5.0\"]", "\"<1.6.0,>=1.5.0\"").forEach { range ->
            edit(config, "name: analytics\nrequire-dbt-version: " + range + "\n")
            val w = workspace
            val from = w.roots.first()
            assertEquals(range, "1.5.0", from.version)
            assertTrue(range, from.supports(5))
            assertFalse(range, from.supportsKind("semantic_model"))
            assertTrue(range, w.declarations(setOf("semantic_model"), "orders", from).isEmpty())
        }
    }

    fun testRequireDbtVersionUsesEveryLowerBound() {
        val config = bareProject()
        add("models/orders.sql", "select 1 as id")
        val unit =
            add("models/unit.yml", "unit_tests: [{name: sample, model: orders, given: [], expect: {rows: []}}]\n")
        permutations(listOf(">=1.4.0", ">=1.8.0", "<1.9.0")).forEach { bounds ->
            edit(
                config,
                "name: analytics\nrequire-dbt-version: [" + bounds.map { b -> "\"" + b + "\"" }
                    .joinToString(", ") + "]\n"
            )
            val w = workspace
            val from = w.roots.first()
            assertEquals(bounds.toString(), "1.8.0", from.version)
            assertTrue(bounds.toString(), from.supportsKind("unit_test"))
            assertFalse(bounds.toString(), from.supportsKind("function"))
            assertEquals(
                bounds.toString(),
                listOf(unit),
                w.declarations(setOf("unit_test"), "sample", from).map { it.file })
        }
    }

    fun testRequireDbtVersionRetainsPatchBounds() {
        val config = bareProject()
        listOf(
            "=1.10.4" to false, "1.10.5" to true, "1.10.5+build.7" to true, ">1.10.4,<1.11.0" to true,
            ">=1.10.4,<=1.10.4" to false
        ).forEach { (range, supportsArguments) ->
            edit(config, "name: analytics\nrequire-dbt-version: \"" + range + "\"\n")
            assertEquals(range, supportsArguments, workspace.roots.first().supports(10, 5))
        }
    }

    fun testUnusableVersionRangesDoNotEnableVersionedFeatures() {
        val config = bareProject()
        listOf(
            "<1.5.0", ">=1.8.0,<1.8.0", ">1.8.0,<=1.8.0", "=1.8.0,=1.9.0",
            ">=1.8.0,invalid", ">=1.8.0,", ">=1.8.0-beta.1"
        ).forEach { range ->
            edit(config, "name: analytics\nrequire-dbt-version: \"" + range + "\"\n")
            assertFalse(range, workspace.roots.first().supports(5))
        }
    }

    fun testExplicitDbtVersionOverridesRangeAndDefaultIsUnchanged() {
        val config = bareProject()
        assertEquals("1.10", workspace.roots.first().version)
        edit(config, "name: analytics\nrequire-dbt-version: [\">=1.5.0\", \"<1.6.0\"]\n")
        configureSettings { it.dbtVersion = "1.12" }
        val from = workspace.roots.first()
        assertEquals("1.12", from.version)
        assertTrue(from.supportsKind("function"))
    }

    fun testSqlProjectionsFollowSourceAndPropertyChanges() {
        val config = bareProject()
        val orders = add("models/orders.sql", "select 1 as old_id")
        val use = add("models/use.sql", "select o.old_id from {{ ref('orders') }} o")
        assertEquals(listOf(orders), targetsAt(use, "old_id").map { it.containingFile })

        edit(orders, "select 1 as new_id")
        assertTrue(targetsAt(use, "old_id").isEmpty())
        edit(use, "select o.new_id from {{ ref('orders') }} o")
        assertEquals(listOf(orders), targetsAt(use, "new_id").map { it.containingFile })

        val properties = add("models/schema.yml", "models:\n- name: orders\n  columns: [{name: new_id}]\n")
        assertEquals(listOf(properties), targetsAt(use, "new_id").map { it.containingFile })
        edit(config, "name: renamed\n")
        assertEquals(listOf(properties), targetsAt(use, "new_id").map { it.containingFile })
        edit(properties, "models:\n- name: orders\n")
        val projected = targetsAt(use, "new_id").first().let { it as DbtSymbolElement }
        assertEquals(orders, projected.containingFile)
        assertEquals("renamed", projected.symbol.namespace)
    }

    fun testDuplicateRootNamesKeepModelsPropertiesAndColumnsSeparate() {
        bareProject(path = "a/")
        bareProject(path = "b/")
        val a = add("a/models/orders.sql", "select 1")
        val b = add("b/models/orders.sql", "select 2")
        val props = add(
            "b/models/schema.yml",
            "models:\n- name: orders\n  config: {enabled: false}\n  columns:\n  - name: id\n"
        )
        val use = add("a/models/use.sql", "select o.id from {{ ref('orders') }} o")
        assertEquals(listOf(a), targetsAt(use, "orders").map { it.containingFile })
        assertTrue(targetsAt(use, "id").isEmpty())
        val w = workspace
        assertTrue(w.properties(w.symbols.find { s -> s.file == a && s.kind == "model" }!!).isEmpty())
        assertFalse(w.symbols.find { s -> s.file == a && s.kind == "model" }!!.attributes["enabled"] == "false")
        assertEquals(
            0,
            ReferencesSearch.search(w.symbols.find { s -> s.file == b && s.kind == "model" }!!.element).findAll().size
        )
        assertTrue(props.isValid)
    }

    fun testYamlContextMatrixAndDescriptionOnlyDocs() {
        bareProject("vars: {color: red}\nmodels:\n  analytics:\n    +schema: \"{{ ref('orders') }}\"\n    +post-hook: \"{{ audit() }}\"\n")
        add("models/orders.sql", "select 1")
        add("macros/audit.sql", "{% macro audit() %}{% endmacro %}")
        add("docs/catalog.md", "{% docs orders_doc %}Orders{% enddocs %}")
        val properties = add(
            "models/schema.yml",
            "models:\n- name: orders\n  description: \"{{ doc('orders_doc') }}\"\n  config:\n    meta:\n      text: \"{{ ref('orders') }}\"\n      description: \"{{ doc('orders_doc') }}\"\n    post_hook: \"{{ audit() }}\"\n"
        )
        assertEquals(1, targetsAt(properties, "orders_doc").size)
        assertTrue(targetsAt(properties, "orders_doc", 1).isEmpty())
        assertTrue(targetsAt(properties, "'orders'").isEmpty())
        assertEquals(1, targetsAt(properties, "audit").size)
        val sql = add("models/use.sql", "{{ doc('orders_doc') }}")
        assertTrue(targetsAt(sql, "orders_doc").isEmpty())
        assertTrue(targetsAt(projectFile("dbt_project.yml"), "'orders'").isEmpty())
        assertEquals(1, targetsAt(projectFile("dbt_project.yml"), "audit").size)
        val unrelated = add("models/readme.yml", "message: \"{{ ref('orders') }}\"\n")
        assertTrue(DbtNavigation.inFile(unrelated).isEmpty())
        val meta = add(
            "models/meta.yml",
            "models:\n- name: orders\n  config:\n    meta:\n      tests: [\"{{ ref('orders') }}\"]\n      given: [{input: \"source('raw', 'events')\"}]\n      defined_in: orders\n"
        )
        assertTrue(targetsAt(meta, "'orders'").isEmpty())
        assertTrue(targetsAt(meta, "orders", 2).isEmpty())
    }


    fun testProfileRuntimeVarsAndCustomProfilesPath() {
        bareProject("profile: analytics\nvars: {color: default}\n")
        configureSettings { o ->
            o.profilesFile = "config/profiles.yml"; o.variables = "{\"color\":\"explicit\"}"; o.target = "dev"
        }
        val profile = add(
            "config/profiles.yml",
            "analytics:\n  target: prod\n  outputs:\n    dev:\n      type: postgres\n      schema: \"{{ var('color') }}\"\n"
        )
        assertTrue(
            targetsAt(profile, "color").first().let { it as DbtSymbolElement }.symbol.attributes.contains("settings")
        )
        val use = add("models/use.sql", "{{ target.name }}")
        assertEquals(listOf(profile), targetsAt(use, "name").map { it.containingFile })
        assertEquals(1, targetsAt(use, "name").size)
    }

    fun testRelationshipsColumnsResolveTheActualSourceAndVersion() {
        bareProject()
        add("models/orders.sql", "select 1")
        add("models/customers_v1.sql", "select 1")
        add("models/customers_v2.sql", "select 2")
        val props = add(
            "models/schema.yml",
            "sources:\n- name: raw\n  tables:\n  - name: customers\n    columns: [{name: customer_id}]\n- name: other\n  tables:\n  - name: customers\n    columns: [{name: customer_id}]\nmodels:\n- name: customers\n  latest_version: 2\n  versions:\n  - v: 1\n    columns: [{name: old_id}]\n  - v: 2\n    columns: [{name: customer_id}]\n- name: orders\n  data_tests:\n  - relationships:\n      arguments: {to: \"source('raw', 'customers')\", field: customer_id}\n  - relationships:\n      arguments: {to: \"ref('customers', v=2)\", field: customer_id}\n"
        )
        val source = targetsAt(props, "customer_id", 3).filterIsInstance<DbtSymbolElement>().map { e -> e.symbol }
        assertEquals(listOf("raw.customers"), source.map { it.owner })
        val version = targetsAt(props, "customer_id", 4).filterIsInstance<DbtSymbolElement>().map { e -> e.symbol }
        assertEquals(listOf(("2")), version.map { it.version })
    }

    fun testLegacyTestArgumentsGateAndDisabledChecks() {
        bareProject()
        configureSettings { it.dbtVersion = "1.9" }
        add("macros/test.sql", "{% test positive(model, minimum) %}{% endtest %}")
        val props = add(
            "models/tests.yml",
            "models:\n- name: orders\n  tests:\n  - positive: {minimum: 0}\n  - positive: {arguments: {minimum: 0}}\n"
        )
        assertEquals(1, targetsAt(props, "minimum").size)
        assertTrue(targetsAt(props, "minimum", 1).isEmpty())
        configureSettings { it.engine = "v2" }
        val check = add("checks/off.sql", "{{ config(enabled=false) }}select name from {{ info_schema('models') }}")
        assertTrue(targetsAt(check, "models").isEmpty())
    }

    fun testStaticNamesCannotEscapeParameterWithOrLoopShadowing() {
        bareProject()
        add("models/orders.sql", "select 1")
        val file = add(
            "models/use.sql",
            "{% set name = 'orders' %}{% macro m(name) %}{{ ref(name) }}{% endmacro %}{% with name=unknown %}{{ ref(name) }}{% endwith %}{% for name in unknown %}{{ ref(name) }}{% endfor %}{{ ref(name) }}"
        )
        (2..6 step 2).forEach { n ->
            assertFalse(
                targetsAt(
                    file,
                    "name",
                    n
                ).any { it.containingFile.name == "orders.sql" })
        }
        assertTrue(targetsAt(file, "name", 7).any { it.containingFile.name == "orders.sql" })
    }

    fun testStaticSourceAndVarsUseTheSharedResolvers() {
        bareProject("vars: {start_date: today}\n")
        add("models/source.yml", "sources:\n- name: raw\n  tables:\n  - name: events\n")
        add("dbt_packages/util/dbt_project.yml", "name: util\n")
        add("dbt_packages/util/models/source.yml", "sources:\n- name: raw\n  tables:\n  - name: events\n")
        val file = add("models/use.sql", "{% set raw = 'raw' %}{{ source(raw, 'events') }}{{ var('start_' ~ 'date') }}")
        assertEquals(1, targetsAt(file, "events").size)
        assertFalse(targetsAt(file, "events").first().containingFile.virtualFile.path.contains("dbt_packages"))
        assertTrue(
            DbtNavigation.inFile(file)
                .any { r -> r.label == "static reference" && r.candidates.any { it is org.jetbrains.yaml.psi.YAMLKeyValue } })
    }

    fun testIgnoreNegationsAndAnchoredPatternsUpdateOnEdits() {
        bareProject()
        val ignore = add(".dbtignore", "models/*\n!models/keep/\n!models/keep/**\n/root_only.sql\n")
        val keep = add("models/keep/orders.sql", "select 1")
        add("models/drop/orders.sql", "select 2")
        add("models/keep/root_only.sql", "select 3")
        val use = add("tests/use.sql", "{{ ref('orders') }}{{ ref('root_only') }}")
        assertEquals(listOf(keep), targetsAt(use, "orders").map { it.containingFile })
        assertEquals(1, targetsAt(use, "root_only").size)
        edit(ignore, "models/**\n")
        assertTrue(targetsAt(use, "orders").isEmpty())
    }

    fun testSelectorReferencesShorthandInheritanceAndVersion() {
        bareProject()
        val selectors = add(
            "selectors.yml",
            "selectors:\n- name: nightly\n  description: 'orders'\n  definition: orders\n- name: use\n  definition:\n    intersection:\n    - method: selector\n      value: nightly\n    - tag: finance\n    - exclude: [missing]\n- name: reuse\n  definition: 'selector:nightly'\n"
        )
        val model = add("models/orders.sql", "select 1")
        val w = workspace
        val r = w.roots.first()
        assertEquals(1, targetsAt(selectors, "nightly", 1).size)
        assertTrue(targetsAt(selectors, "selector:nightly").isEmpty())
        configureSettings { it.dbtVersion = "1.12" }
        assertEquals(1, targetsAt(selectors, "nightly", 2).size)
        assertTrue(targetsAt(selectors, "orders").isEmpty())
        assertEquals(1, targetsAt(selectors, "orders", 1).size)
        assertEquals(
            1, ReferencesSearch.search(
                workspace.symbols.find { s -> s.file == model && s.kind == "model" }!!.element,
                GlobalSearchScope.fileScope(getProject(), selectors.virtualFile)
            ).findAll().size
        )
        assertTrue(DbtSelectors.select(w, r, "state:modified", includeGraph = false).isEmpty())
    }

    fun testDefaultDispatchPrefersRootOverrideAndProfileTypeIsDecoded() {
        bareProject("profile: analytics\n")
        configureSettings { it.target = "dev" }
        add("profiles.yml", "analytics:\n  outputs:\n    dev:\n      type: 'redshift'\n")
        add("dbt_packages/util/dbt_project.yml", "name: util\n")
        add("dbt_packages/util/macros/format.sql", "{% macro default__format() %}{% endmacro %}")
        val overrideFile = add("macros/format.sql", "{% macro postgres__format() %}{% endmacro %}")
        val file = add("models/use.sql", "{{ adapter.dispatch('format', 'util')() }}")
        assertEquals(listOf(overrideFile), targetsAt(file, "format").map { it.containingFile })
    }

    fun testManifestSourceArgumentsAndMissingVersionStayDistinct() {
        bareProject()
        configureSettings { it.manifestPath = "target/manifest.json" }
        add("models/orders_v1.sql", "select 1")
        add("models/versions.yml", "models:\n- name: orders\n  versions:\n  - v: 1\n")
        add(
            "target/manifest.json",
            """{"metadata":{"dbt_schema_version":"https://schemas.getdbt.com/dbt/manifest/v12.json"},"sources":{"source.analytics.raw.events":{"unique_id":"source.analytics.raw.events","resource_type":"source","name":"events","source_name":"raw","package_name":"analytics"}},"nodes":{"model.analytics.orders.v2":{"unique_id":"model.analytics.orders.v2","resource_type":"model","name":"orders","version":2,"package_name":"analytics","original_file_path":"models/orders_v2.sql"}}}"""
        )
        val file = add("models/use.sql", "{{ source('raw', 'events') }}{{ ref('orders', v=2) }}")
        assertEquals(
            listOf("source"),
            targetsAt(file, "raw").filterIsInstance<DbtSymbolElement>().map { e -> e.symbol.kind })
        assertEquals(
            listOf("source_table"),
            targetsAt(file, "events").filterIsInstance<DbtSymbolElement>().map { e -> e.symbol.kind })
        assertEquals(
            listOf(("2")),
            targetsAt(file, "orders").filterIsInstance<DbtSymbolElement>().map { e -> e.symbol.version })
        assertEquals(1, ReferencesSearch.search(targetsAt(file, "events").first()).findAll().size)
    }

    fun testSnapshotBlockNamesReplaceTheContainerFilename() {
        bareProject()
        val definition =
            add("snapshots/all.sql", "{% snapshot history %}{{ config(unique_key='id') }}select 1{% endsnapshot %}")
        val file = add("models/use.sql", "{{ ref('history') }}{{ ref('all') }}")
        assertEquals(listOf(definition), targetsAt(file, "history").map { it.containingFile })
        assertTrue(targetsAt(file, "all").isEmpty())
        assertEquals(definition.text.indexOf("history"), targetsAt(file, "history").first().textOffset)
    }

    fun testUnitTestDuplicateMissingFixturesAndNamespace() {
        bareProject("test-paths: [tests, extra_tests]\n")
        add("dbt_packages/util/dbt_project.yml", "name: util\n")
        val model = add("dbt_packages/util/models/orders.sql", "select 1")
        add("tests/fixtures/input.csv", "id\n1\n")
        add("extra_tests/fixtures/input.csv", "id\n2\n")
        val unit = add(
            "models/unit.yml",
            "unit_tests:\n- name: sample\n  model: util.orders\n  given:\n  - input: ref('util', 'orders')\n    format: csv\n    fixture: input\n  expect: {format: csv, fixture: missing}\n"
        )
        assertEquals(listOf(model), targetsAt(unit, "util.orders").map { it.containingFile })
        assertEquals(2, targetsAt(unit, "input", 1).size)
        assertTrue(targetsAt(unit, "missing").isEmpty())
        configureSettings { it.dbtVersion = "1.7" }
        assertTrue(targetsAt(unit, "util.orders").isEmpty())
        assertTrue(targetsAt(unit, "input", 1).isEmpty())
    }

    fun testVersionSpecificTestArguments() {
        bareProject()
        configureSettings { it.dbtVersion = "1.10.4" }
        add("macros/test.sql", "{% test positive(model, minimum) %}{% endtest %}")
        val props =
            add("models/tests.yml", "models:\n- name: orders\n  data_tests:\n  - positive: {arguments: {minimum: 0}}\n")
        assertTrue(targetsAt(props, "minimum").isEmpty())
        configureSettings { it.dbtVersion = "1.10.5" }
        assertEquals(1, targetsAt(props, "minimum").size)
    }

    fun testYamlConstantsRemainWithinTheirScalar() {
        bareProject()
        add("models/orders.sql", "select 1")
        val file = add(
            "models/schema.yml",
            "models:\n- name: orders\n  config:\n    pre_hook: \"{% set name = 'orders' %}{{ ref(name) }}\"\n    post_hook: \"{{ ref(name) }}\"\n"
        )
        assertTrue(targetsAt(file, "name", 2).any { it.containingFile.name == "orders.sql" })
        assertFalse(targetsAt(file, "name", 3).any { it.containingFile.name == "orders.sql" })
    }

    fun testConfigurationFoldersPackagesSourcesAndNewResourceGates() {
        val config =
            bareProject("models:\n  analytics:\n    marts:\n      orders: {+materialized: table}\n  util:\n    packaged: {}\nsources:\n  analytics:\n    raw:\n      +enabled: false\n      events: {}\nfunctions:\n  analytics:\n    hash: {}\n")
        add("models/marts/orders.sql", "select 1")
        add("dbt_packages/util/dbt_project.yml", "name: util\n")
        val packageModel = add("dbt_packages/util/models/packaged.sql", "select 1")
        add("models/source.yml", "sources:\n- name: raw\n  tables: [{name: events}]\n")
        add("functions/hash.sql", "select 1")
        assertTrue(targetsAt(config, "marts").first().let { it is com.intellij.psi.PsiDirectory })
        assertEquals(listOf(packageModel), targetsAt(config, "packaged").map { it.containingFile })
        val use = add("models/use.sql", "{{ source('raw', 'events') }}")
        assertTrue(targetsAt(use, "raw").isEmpty())
        assertTrue(targetsAt(use, "events").isEmpty())
        assertTrue(targetsAt(config, "hash").isEmpty())
        configureSettings { it.dbtVersion = "1.11" }
        assertEquals(1, targetsAt(config, "hash").size)
    }

    fun testBuiltinSourceAndDocumentationTargetsFindOnlyLocalUsages() {
        bareProject()
        configureSettings { o -> o.builtinSources.put("adapter.get_relation", "vendor/adapter.sql") }
        val source = add("vendor/adapter.sql", "{% macro get_relation(database, schema, identifier) %}{% endmacro %}")
        val use = add(
            "models/use.sql",
            "{{ adapter.get_relation('db', 's', 't') }} {{ config.require('unique_key') }} {{ dbt_version }}"
        )
        assertEquals(listOf(source), targetsAt(use, "adapter.get_relation").map { it.containingFile })
        assertEquals(1, ReferencesSearch.search(targetsAt(use, "adapter.get_relation").first()).findAll().size)
        assertEquals(
            listOf("documentation"),
            targetsAt(use, "config.require").filterIsInstance<DbtSymbolElement>().map { e -> e.symbol.kind })
        assertEquals(
            listOf("documentation"),
            targetsAt(use, "dbt_version").filterIsInstance<DbtSymbolElement>().map { e -> e.symbol.kind })
        assertEquals(0, ReferencesSearch.search(targetsAt(use, "dbt_version").first()).findAll().size)
    }

    fun testFunctionLanguageAdapterOverloadAndCallerGates() {
        bareProject()
        configureSettings { o -> o.dbtVersion = "1.11"; o.adapter = "snowflake" }
        val js = add("functions/hash.js", "return input;")
        val sql = add("functions/hash_numeric.sql", "input > 0")
        val props = add(
            "functions/hash.yml",
            "functions:\n- name: hash\n  config: {language: javascript}\n  overloads:\n  - defined_in: hash_numeric\n"
        )
        val use = add("models/use.sql", "{{ function('hash') }}")
        assertTrue(targetsAt(use, "hash").isEmpty())
        configureSettings { o -> o.dbtVersion = "1.12"; o.adapter = "snowflake" }
        assertEquals(setOf(js, sql), targetsAt(use, "hash").map { it.containingFile }.toSet())
        val hook =
            add("models/hook.yml", "models:\n- name: use\n  config:\n    post_hook: \"{{ function('hash') }}\"\n")
        assertEquals(
            3,
            ReferencesSearch.search(targetsAt(use, "hash").find { it.containingFile == js }!!).findAll().size
        )
        configureSettings { o -> o.dbtVersion = "1.12"; o.adapter = "postgres" }
        assertFalse(targetsAt(use, "hash").any { it.containingFile == js })
        assertTrue(hook.isValid && props.isValid)
    }

    fun testLineageHonorsScopeEditsCyclesAndCancellation() {
        bareProject()
        val orders = add("models/orders.sql", "{{ ref('customers') }}")
        val customers = add("models/customers.sql", "{{ ref('orders') }}")
        val w = workspace
        val edges = DbtLineage.edges(w, (GlobalSearchScope.fileScope(getProject(), orders.virtualFile)))
        assertEquals(1, edges.size)
        assertEquals("orders", edges.first().from.name)
        edit(orders, "{{ ref('missing') }}")
        assertEquals(1, DbtLineage.edges(workspace).size)
        val start = edges.first().from
        val canceled = java.util.concurrent.atomic.AtomicBoolean(false)
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread(object : Runnable {
            override fun run() {
                val indicator = com.intellij.openapi.progress.EmptyProgressIndicator()
                try {
                    com.intellij.openapi.progress.ProgressManager.getInstance().runProcess(object : Runnable {
                        override fun run() {
                            indicator.cancel();
                            DbtLineage.traverse(edges, start, downstream = false, 100);
                            Unit
                        }
                    }, indicator)
                } catch (_: com.intellij.openapi.progress.ProcessCanceledException) {
                    canceled.set(true)
                }
            }
        }).get(10, java.util.concurrent.TimeUnit.SECONDS)
        assertTrue(canceled.get())
        assertTrue(customers.isValid)
    }

    fun testCurrentMetricInputsAliasesSavedQueriesAndTimeSpines() {
        bareProject()
        configureSettings { it.dbtVersion = "1.12" }
        add("models/orders.sql", "select 1")
        val schema = add(
            "models/semantics.yml",
            "models:\n- name: orders\n  semantic_model: {name: sales}\n  time_spine: {standard_granularity_column: ordered_at}\n  columns:\n  - name: customer_id\n    entity: {name: customer, type: primary}\n  - name: ordered_at\n    dimension: {type: time}\n  - name: country\n    dimension: {type: categorical}\n  - name: amount\n  metrics:\n  - name: revenue\n    type: simple\n    expr: amount\n  - name: running_revenue\n    type: cumulative\n    input_metric: revenue\n  - name: conversion\n    type: conversion\n    entity: customer\n    base_metric: revenue\n    conversion_metric: running_revenue\nmetrics:\n- name: growth\n  type: derived\n  input_metrics:\n  - name: revenue\n    alias: gross\n  expr: gross + 1\nsaved_queries:\n- name: report\n  query_params:\n    metrics: [growth]\n    group_by: [\"Dimension('customer__country')\"]\n    where: \"TimeDimension('ordered_at', 'DAY') > '2026-01-01'\"\n    order_by: [\"Metric('growth')\"]\n  exports: [{name: daily}]\n"
        )
        assertEquals(1, targetsAt(schema, "ordered_at").size)
        assertEquals(
            listOf("column"),
            targetsAt(schema, "amount", 1).filterIsInstance<DbtSymbolElement>().map { e -> e.symbol.kind })
        assertEquals(
            listOf("metric"),
            targetsAt(schema, "revenue", 2).filterIsInstance<DbtSymbolElement>().map { e -> e.symbol.kind })
        assertEquals(
            listOf("metric_alias"),
            targetsAt(schema, "gross", 1).filterIsInstance<DbtSymbolElement>().map { e -> e.symbol.kind })
        assertEquals(
            listOf("entity"),
            targetsAt(schema, "customer", 2).filterIsInstance<DbtSymbolElement>().map { e -> e.symbol.kind })
        assertEquals(
            listOf("dimension"),
            targetsAt(schema, "country", 1).filterIsInstance<DbtSymbolElement>().map { e -> e.symbol.kind })
        assertEquals(
            listOf("saved_query"),
            targetsAt(schema, "daily").filterIsInstance<DbtSymbolElement>().map { e -> e.symbol.kind })
        val metric = workspace.symbols.find { s -> s.kind == "metric" && s.name == "revenue" }!!.element
        assertEquals(3, ReferencesSearch.search(metric).findAll().size)
        val legacy = add(
            "models/legacy.yml",
            "semantic_models:\n- name: activity\n  entities: [{name: actor, type: primary}]\n  measures: [{name: visits}, {name: purchases}]\nmetrics:\n- name: conversion_legacy\n  type: conversion\n  type_params:\n    entity: actor\n    base_measure: {name: visits}\n    conversion_measure: {name: purchases}\n"
        )
        assertEquals(
            listOf("measure"),
            targetsAt(legacy, "visits", 1).filterIsInstance<DbtSymbolElement>().map { e -> e.symbol.kind })
        assertEquals(
            listOf("measure"),
            targetsAt(legacy, "purchases", 1).filterIsInstance<DbtSymbolElement>().map { e -> e.symbol.kind })
        assertEquals(1, ReferencesSearch.search(targetsAt(legacy, "visits", 1).first()).findAll().size)
    }

    fun testNumericAdditionAndReassignedWrapperArgumentsStayUnknown() {
        bareProject()
        add("models/orders.sql", "select 1")
        add("models/12.sql", "select 1")
        add(
            "macros/wrapper.sql",
            "{% macro lookup(name) %}{% set name = env_var('MODEL') %}{{ builtins.ref(name) }}{% endmacro %}"
        )
        val file = add("models/use.sql", "{{ ref(1 + 2) }}{{ lookup('orders') }}")
        assertTrue(targetsAt(file, "orders").isEmpty())
        assertFalse(DbtNavigation.inFile(file).flatMap { it.candidates }.any { it.containingFile.name == "12.sql" })
    }

    fun testTransitivePackageMacroVariablesUseTheirOwnCallingNode() {
        bareProject("vars:\n  analytics: {color: root_node}\n  util: {color: package_node}\n")
        add("dbt_packages/util/dbt_project.yml", "name: util\n")
        val macros = add(
            "dbt_packages/util/macros/colors.sql",
            "{% macro root_color() %}{{ var('color') }}{% endmacro %}{% macro package_color() %}{{ var('color') }}{% endmacro %}{% macro wrapper() %}{{ util.root_color() }}{% endmacro %}"
        )
        add("models/use.sql", "{{ util.wrapper() }}")
        add("dbt_packages/util/models/use.sql", "{{ util.package_color() }}")
        val rootColor = targetsAt(macros, "'color'").first().let { it as org.jetbrains.yaml.psi.YAMLKeyValue }
        val packageColor = targetsAt(macros, "'color'", 1).first().let { it as org.jetbrains.yaml.psi.YAMLKeyValue }
        assertEquals("root_node", rootColor.valueText)
        assertEquals("package_node", packageColor.valueText)
    }

    fun testStaticEvidenceInvalidatesAfterConstantAndWrapperEdits() {
        bareProject()
        val orders = add("models/orders.sql", "select 1")
        val customers = add("models/customers.sql", "select 1")
        val wrapper = add("macros/lookup.sql", "{% macro lookup(name) %}{{ builtins.ref(name) }}{% endmacro %}")
        val use = add("models/use.sql", "{% set name = 'orders' %}{{ ref(name) }}{{ lookup('orders') }}")
        val static = DbtNavigation.inFile(use).find { it.label == "static reference" }!!
        assertEquals(listOf(orders), static.candidates.map { it.containingFile })
        assertTrue(static.evidence.isNotEmpty())
        assertTrue(
            DbtNavigation.inFile(use)
                .find { it.label == "delegated reference" }!!.evidence.any { it.contains("analytics.lookup") })
        edit(use, "{% set name = 'customers' %}{{ ref(name) }}{{ lookup('orders') }}")
        assertEquals(
            listOf(customers),
            DbtNavigation.inFile(use).find { it.label == "static reference" }!!.candidates.map { it.containingFile })
        edit(wrapper, "{% macro lookup(name) %}{{ builtins.ref('missing') }}{% endmacro %}")
        assertTrue(targetsAt(use, "'orders'").isEmpty())
    }

    fun testChecksIgnorePathsUnsupportedVersionsAndIncompleteQueries() {
        bareProject("check-paths: [metadata_checks]\ninfo_schema: {version: 1}\n")
        configureSettings { it.engine = "v2" }
        add("models/orders.sql", "select 1")
        val check = add("metadata_checks/partial.sql", "select name from {{ info_schema('models') }} where name =")
        assertEquals(1, targetsAt(check, "models").size)
        add(".dbtignore", "metadata_checks/ignored.sql\n")
        val ignored = add("metadata_checks/ignored.sql", "select name from {{ info_schema('models') }}")
        assertTrue(DbtNavigation.inFile(ignored).isEmpty())
        edit(
            projectFile("dbt_project.yml"),
            "name: analytics\ncheck-paths: [metadata_checks]\ninfo_schema: {version: 999}\n"
        )
        assertTrue(targetsAt(check, "models").isEmpty())
        configureSettings { o -> o.engine = "core"; o.dbtVersion = "1.12" }
        assertTrue(DbtNavigation.inFile(check).isEmpty())
    }

    fun testFindUsagesFromPropertiesKeepsTheResourceAndVersionIdentity() {
        bareProject()
        add("models/orders_v1.sql", "select 1")
        add("models/orders_v2.sql", "select 2")
        val properties =
            add("models/orders.yml", "models:\n- name: orders\n  latest_version: 2\n  versions:\n  - v: 1\n  - v: 2\n")
        val use = add("models/use.sql", "{{ ref('orders', v=1) }}{{ ref('orders', v=2) }}")
        val symbols = workspace.symbols.filter { s -> s.file == properties && s.kind == "model" }
        symbols.forEach { symbol ->
            val usages =
                ReferencesSearch.search(symbol.element, GlobalSearchScope.fileScope(getProject(), use.virtualFile))
                    .findAll()
            assertEquals(1, usages.size)
            val target = usages.iterator().next().resolve().let { it as DbtSymbolElement }
            assertEquals(symbol.version, target.symbol.version)
        }
        val missing = add("models/missing.yml", "models:\n- name: missing\n")
        assertEquals(
            0,
            ReferencesSearch.search(workspace.symbols.find { s -> s.file == missing }!!.element).findAll().size
        )
    }

    fun testTupleAssignmentsAndCaptureBodiesRetainLexicalScope() {
        bareProject()
        add("models/orders.sql", "select 1")
        val file = add(
            "models/use.sql",
            "{% set name = 'orders' %}{% set first, name = unknown %}{{ name }}{{ ref(name) }}{% set label = 'outer' %}{% set label %}{{ label }}{% endset %}{{ label }}"
        )
        assertEquals(
            file.text.indexOf("name", file.text.indexOf("name") + 1),
            targetsAt(file, "name", 2).first().textOffset
        )
        assertFalse(targetsAt(file, "name", 3).any { it.containingFile.name == "orders.sql" })
        val outer = targetsAt(file, "label", 2).first()
        val captured = targetsAt(file, "label", 3).first()
        assertEquals(file.text.indexOf("label"), outer.textOffset)
        assertEquals(file.text.indexOf("label", outer.textOffset + 1), captured.textOffset)
    }

    fun testCatalogColumnsRespectProjectAndInstalledPackageOwners() {
        bareProject()
        configureSettings { it.catalogPath = "target/catalog.json" }
        add("models/orders.sql", "select 1")
        bareProject(path = "other/")
        add("other/models/orders.sql", "select 2")
        add("other/models/schema.yml", "models:\n- name: orders\n  columns: [{name: total}]\n")
        add("dbt_packages/util/dbt_project.yml", "name: util\n")
        add("dbt_packages/util/models/orders.sql", "select 3")
        add(
            "target/catalog.json",
            """{"metadata":{"dbt_schema_version":"https://schemas.getdbt.com/dbt/catalog/v1.json","generated_at":"2099-01-01T00:00:00Z"},"nodes":{"model.analytics.orders":{"unique_id":"model.analytics.orders","columns":{"total":{"name":"total"}}},"model.util.orders":{"unique_id":"model.util.orders","columns":{"package_total":{"name":"package_total"}}}}}"""
        )
        val use = add(
            "models/use.sql",
            "select a.total, p.package_total from {{ ref('orders') }} a join {{ ref('util', 'orders') }} p on true"
        )
        assertEquals(
            listOf("target/catalog.json"),
            targetsAt(use, "total").map { e -> workspace.root(use)!!.relative(e.containingFile.virtualFile)!! })
        assertEquals(1, targetsAt(use, "package_total").size)
        assertTrue(targetsAt(use, "package_total").first().let { it as DbtSymbolElement }.symbol.metadata)
    }

    fun testSemanticJoinCandidatesStayAmbiguousAndUpdateOnEdits() {
        bareProject()
        val schema = add(
            "models/semantic.yml",
            "semantic_models:\n- name: sales\n  entities: [{name: customer, type: primary}]\n  dimensions: [{name: country}]\n- name: customers\n  entities: [{name: customer, type: primary}]\n  dimensions: [{name: country}]\n"
        )
        val use = add("models/use.sql", "{{ Dimension('customer__country') }}{{ Dimension('missing__country') }}")
        val ref = DbtNavigation.at(use, use.text.indexOf("country") + 1).first()
        assertEquals(2, ref.candidates.size)
        assertNull(ref.resolve())
        assertTrue(targetsAt(use, "missing__country").isEmpty())
        edit(
            schema,
            schema.text.replace(
                "name: sales\n  entities: [{name: customer, type: primary}]",
                "name: sales\n  entities: [{name: customer, type: foreign}]"
            )
        )
        assertEquals(1, targetsAt(use, "country").size)
    }

    fun testSelectorsKeepDuplicateNamesAnchorsAndShorthandConservative() {
        bareProject("models:\n  analytics:\n    +tags: [finance]\n")
        val orders = add("models/orders.sql", "select 1")
        val selectors = add(
            "selectors.yml",
            "selectors:\n- name: nightly\n  definition: &finance {tag: finance}\n- name: nightly\n  definition: 'fqn:missing'\n- name: use\n  definition:\n    union:\n    - selector: nightly\n    - *finance\n"
        )
        assertEquals(listOf(orders), targetsAt(selectors, "finance", 1).map { it.containingFile })
        val named = DbtNavigation.at(selectors, selectors.text.lastIndexOf("nightly") + 1).first()
        assertEquals(2, named.candidates.size)
        assertNull(named.resolve())
        assertTrue(targetsAt(selectors, "*finance").isEmpty())
    }

    fun testLineageKeepsSnapshotAndGenericTestBlockOwners() {
        bareProject()
        add("models/orders.sql", "select 1")
        add("models/customers.sql", "select 2")
        add(
            "snapshots/history.sql",
            "{% snapshot first %}{{ ref('orders') }}{% endsnapshot %}{% snapshot second %}{{ ref('customers') }}{% endsnapshot %}{% snapshot off %}{{ config(enabled=false) }}select 3{% endsnapshot %}"
        )
        add("macros/tests.sql", "{% test compares(model) %}{{ ref('orders') }}{% endtest %}")
        val use = add("models/use.sql", "{{ ref('off') }}")
        val w = workspace
        val edges = DbtLineage.edges(w)
        assertTrue(edges.any { e -> e.from.name == "first" && e.to.name == "orders" })
        assertTrue(edges.any { e -> e.from.name == "second" && e.to.name == "customers" })
        assertFalse(edges.any { e -> e.from.name == "first" && e.to.name == "customers" })
        assertTrue(edges.any { e -> e.from.kind == "generic_test" && e.from.name == "compares" && e.to.name == "orders" })
        assertTrue(targetsAt(use, "off").isEmpty())
    }

    fun testDeferredHookVarsAndEnabledSourceTableOverrides() {
        val config =
            bareProject("vars: {start_date: today}\non-run-start: \"{{ var('start_' ~ 'date') }}\"\nsources:\n  analytics:\n    raw:\n      +enabled: false\n")
        val schema = add(
            "models/source.yml",
            "sources:\n- name: raw\n  tables:\n  - name: off\n  - name: events\n    config: {enabled: true}\n"
        )
        val use = add("models/use.sql", "{{ source('raw', 'events') }}{{ source('raw', 'off') }}")
        assertEquals(listOf(schema), targetsAt(use, "events").map { it.containingFile })
        assertTrue(targetsAt(use, "off").isEmpty())
        assertTrue(DbtNavigation.inFile(config).any { r -> r.label == "static reference" && r.candidates.isNotEmpty() })
        val profile =
            add("profiles.yml", "analytics:\n  outputs:\n    dev:\n      schema: \"{{ var('start_' ~ 'date') }}\"\n")
        assertTrue(DbtNavigation.inFile(profile).filter { it.label == "static reference" }
            .all { it.candidates.isEmpty() })
    }

    fun testNativeEnvironmentFindUsagesTargetsOnlyTheKey() {
        bareProject()
        val env = add(".env", "DBT_ENV_SECRET_TOKEN=secret-value\n")
        val use = add("models/use.sql", "{{ env_var('DBT_ENV_SECRET_TOKEN') }}")
        myFixture.configureFromExistingVirtualFile(env.virtualFile)
        myFixture.editor.caretModel.moveToOffset(1)
        val flags =
            com.intellij.codeInsight.TargetElementUtil.ELEMENT_NAME_ACCEPTED or com.intellij.codeInsight.TargetElementUtil.REFERENCED_ELEMENT_ACCEPTED
        val declaration = com.intellij.codeInsight.TargetElementUtil.findTargetElement(myFixture.editor, flags)!!
        assertTrue(declaration.let { it is DbtSymbolElement })
        assertEquals("DBT_ENV_SECRET_TOKEN", declaration.text)
        assertEquals(1, myFixture.findUsages(declaration).size)
        assertEquals(listOf(declaration), targetsAt(use, "DBT_ENV_SECRET_TOKEN"))
    }

    fun testRuntimeVersionArgumentsDoNotChooseLatest() {
        bareProject()
        add("models/orders_v1.sql", "select 1")
        add("models/orders_v2.sql", "select 2")
        add("models/orders.yml", "models:\n- name: orders\n  latest_version: 2\n  versions: [{v: 1}, {v: 2}]\n")
        val use = add(
            "models/use.sql",
            "{{ ref('orders', v=runtime_version) }}{{ ref('orders', version=env_var('VERSION')) }}{% set name = 'orders' %}{{ ref(name, v=runtime_version) }}"
        )
        assertTrue(targetsAt(use, "orders").isEmpty())
        assertTrue(targetsAt(use, "orders", 1).isEmpty())
        assertTrue(DbtNavigation.inFile(use).filter { it.label == "static reference" }.all { it.candidates.isEmpty() })
    }
}
