package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.lineage.DbtSelection
import com.github.vbmacher.intellij.dbt.project.*
import org.junit.Assert.*


/**
 * Contract: features/selection-review.md and state-comparison.md: exact scope, partial selections, CLI-reviewed selections
 * and execution gates.
 */
class DbtSelectionAcceptanceTest : DbtTestCase() {
    /** Models a to b to c to d, with b and c tagged `finance`, and the given `selectors.yml`. */
    private fun chainProject(selectors: String) {
        project()
        add("models/a.sql", "select 1 as id")
        add("models/b.sql", "{{ config(tags=['finance']) }} select * from {{ ref('a') }}")
        add("models/c.sql", "{{ config(tags=['finance']) }} select * from {{ ref('b') }}")
        add("models/d.sql", "select * from {{ ref('c') }}")
        add("selectors.yml", selectors)
    }

    /** Versioned `orders` plus same-named models in an installed package and in an independent project. */
    private fun sameNamedOrders() {
        project()
        add("models/orders_v1.sql", "select 1")
        add("models/orders_v2.sql", "select 2")
        add("models/schema.yml", "models: [{name: orders, versions: [{v: 1}, {v: 2}]}]\n")
        add("dbt_packages/pkg/dbt_project.yml", "name: pkg\nconfig-version: 2\n")
        add("dbt_packages/pkg/models/orders.sql", "select 3")
        add("other/dbt_project.yml", "name: other\nconfig-version: 2\n")
        add("other/models/orders.sql", "select 4")
    }

    fun testGraphDepthIntersectionsNamedSelectorsAndCyclesRemainReviewable() {
        chainProject(
            """selectors:
- name: window
  definition: {method: fqn, value: c, parents: true, parents_depth: 1, children: true, children_depth: 1}
- name: cycle_a
  definition: {method: selector, value: cycle_b}
- name: cycle_b
  definition: {method: selector, value: cycle_a}
"""
        )
        val w = workspace
        assertEquals(setOf("b", "c", "d"), DbtSelection.resolve(w, root, "1+c+1").resources.map { it.name }.toSet())
        assertEquals(
            setOf("b", "c", "d"),
            DbtSelection.resolve(w, root, "selector:window").resources.map { it.name }.toSet()
        )
        assertEquals(
            setOf("b", "c"),
            DbtSelection.resolve(w, root, "tag:finance,1+c+1").resources.map { it.name }.toSet()
        )
        assertEquals(
            setOf("c"),
            DbtSelection.resolve(w, root, "tag:finance,1+c+1", "b").resources.map { it.name }.toSet()
        )
        val cycle = DbtSelection.resolve(w, root, "selector:cycle_a")
        assertTrue(cycle.resources.isEmpty());
        assertTrue(cycle.problems.any { it.contains("cycle") })
        assertFalse(DbtSelection.resolve(w, root, "c,,d").complete)
        val atSelection = DbtSelection.resolve(w, root, "@c")
        assertTrue(atSelection.complete)
        assertEquals(setOf("a", "b", "c", "d"), atSelection.resources.map { it.name }.toSet())
        assertFalse(DbtSelection.resolve(w, root, "selector:missing").complete)
    }

    fun testGeneratedSourceAndExposureSelectorsResolveLocally() {
        project()
        add("models/orders.sql", "select * from {{ source('raw', 'orders') }}")
        add(
            "models/schema.yml",
            "sources:\n- name: raw\n  tables:\n  - name: orders\nexposures:\n- name: board\n  type: dashboard\n  owner: {name: a}\n  depends_on: [ref('orders')]\n"
        )
        val w = workspace
        val resources =
            DbtSelection.resolve(w, root, "").resources.filter { s -> s.kind in setOf("source_table", "exposure") }
        assertEquals(setOf("source_table", "exposure"), resources.map { it.kind }.toSet())
        resources.forEach { s ->
            val selector = DbtSelection.selector(w, s)
            assertEquals(
                selector,
                listOf(s.uniqueId),
                DbtSelection.resolve(w, root, selector).resources.map { it.uniqueId })
        }
    }

    fun testMatchedVersionAndPackageIdentitiesAgreeWithAllSelectionCommands() {
        sameNamedOrders()
        val w = workspace
        val options = root.runOptions
        options.selection = "fqn:analytics.orders.v2 package:pkg"
        options.target = "qa";
        options.profilesDirectory = "profiles with spaces"
        val resolved = DbtSelection.resolve(w, root, options.selection)
        assertTrue(resolved.problems.joinToString("; "), resolved.complete)
        assertEquals(
            setOf("model.analytics.orders.v2", "model.pkg.orders"),
            resolved.resources.map { it.uniqueId }.toSet()
        )
        listOf("Run", "Build", "Test", "Compile").forEach { action ->
            val command = DbtCommand.selection(w, root, action, fullRefresh = false).getOrThrow()
            val selection =
                command.arguments.dropWhile { it != "--select" }.drop(1).takeWhile { arg -> !arg.startsWith("-") }
                    .takeWhile { value -> !value.startsWith("--") }
            assertEquals(resolved.resources.map { DbtSelection.selector(w, it) }.toSet(), selection.toSet())
            assertEquals(listOf("--target", "qa"), command.arguments.dropWhile { it != "--target" }.take(2))
            assertTrue(command.environment.getValue("DBT_PROFILES_DIR").endsWith("/profiles with spaces"))
            assertFalse(selection.any { it.contains("other") })
            assertFalse(selection.any { it.endsWith("v1") })
        }
        assertFalse(getProject().getService(DbtRunner::class.java).isRunning)
    }

    fun testFullRefreshDependsOnSelectedIncrementalModelsOnly() {
        project()
        add("models/plain.sql", "select 1 as id")
        add("models/incremental.sql", "{{ config(materialized='incremental') }} select 2 as id")
        val w = workspace
        root.runOptions.selection = "plain"
        assertFalse(
            DbtCommand.selection(w, root, "Build", fullRefresh = true).getOrThrow().arguments.contains("--full-refresh")
        )
        root.runOptions.selection = "incremental"
        assertTrue(
            DbtCommand.selection(w, root, "Build", fullRefresh = true).getOrThrow().arguments.contains("--full-refresh")
        )
        assertFalse(
            DbtCommand.selection(w, root, "Test", fullRefresh = true).getOrThrow().arguments.contains("--full-refresh")
        )
    }

    fun testStateIntersectionUsesBaselineAndKeepsAuthoritativeExpressionInCommand() {
        project()
        add("models/orders.sql", "select 2 as amount")
        add(
            "baseline/manifest.json",
            """{"metadata":{"dbt_schema_version":"https://schemas.getdbt.com/dbt/manifest/v12.json"},"nodes":{"model.analytics.orders":{"raw_code":"select 1 as amount","config":{},"columns":{}}}}"""
        )
        val w = workspace
        val options = root.runOptions
        options.stateDirectory = "baseline";
        options.selection = "fqn:analytics.orders,state:modified"
        val command = DbtCommand.selection(w, root, "Build", fullRefresh = false).getOrThrow()
        assertEquals(
            listOf("fqn:analytics.orders,state:modified"),
            command.arguments.dropWhile { it != "--select" }.drop(1).takeWhile { arg -> !arg.startsWith("-") })
        assertTrue(command.arguments.windowed(2).any { v -> v.first() == "--state" && v.last().endsWith("/baseline") })
        val preview = DbtState.preview(w, root, options.selection).getOrThrow()
        assertEquals(listOf("model.analytics.orders"), preview.selected.map { it.uniqueId })
        options.stateDirectory = "does-not-exist"
        assertTrue(DbtCommand.selection(w, root, "Build", fullRefresh = false).isFailure)
    }

    fun testDocumentationAndFreshnessCommandsIgnoreModelSelectionAndInvalidState() {
        project()
        add("models/orders.sql", "select 1")
        val w = workspace
        root.runOptions.selection = "unsupported:runtime"
        root.runOptions.stateDirectory = "missing-baseline"
        root.runOptions.defer = true;
        root.runOptions.favorState = true
        listOf("Generate Docs", "Source Freshness").forEach { action ->
            val command = DbtCommand.selection(w, root, action, fullRefresh = false).getOrThrow()
            assertFalse(command.arguments.contains("--select"))
            assertFalse(command.arguments.contains("--state"))
            assertFalse(command.arguments.contains("--defer"))
            assertFalse(command.arguments.contains("--favor-state"))
        }
    }

    fun testFavorStateRequiresConfiguredCoreVersionEvenWithAValidManifest() {
        project()
        val code = add("models/orders.sql", "select 1")
        configureSettings { o -> o.engine = "core"; o.dbtVersion = "1.3" }
        add(
            "baseline/manifest.json",
            """{"metadata":{"dbt_schema_version":"https://schemas.getdbt.com/dbt/manifest/v12.json"},"nodes":{}}"""
        )
        val w = workspace
        root.runOptions.stateDirectory = "baseline";
        root.runOptions.defer = true;
        root.runOptions.favorState = true
        val selected = DbtRunSelection.available(w).find { it.path == code.virtualFile.path }!!
        val error = DbtCommand.create(w, selected, "Build", fullRefresh = false).exceptionOrNull()!!.message.orEmpty()
        assertTrue(error.contains("1.4"))
    }

    fun testPerRootSelectionAndDeferralSettingsSurviveStateReloadWithoutLeaking() {
        project()
        add("models/orders.sql", "select 1")
        add("other/dbt_project.yml", "name: other\nconfig-version: 2\n")
        add("other/models/orders.sql", "select 2")
        val w = workspace
        val other = w.roots.find { it.name == "other" }!!
        val options = root.runOptions
        options.selection = "orders";
        options.exclude = "tag:slow";
        options.stateDirectory = "/artifacts/prod"
        options.defer = true;
        options.favorState = true;
        options.target = "dev"
        other.runOptions.selection = "tag:other";
        other.runOptions.target = "qa"
        val settings = DbtProjectSettings.get(getProject())
        val saved = DbtSettingsState()
        saved.projects =
            java.util.TreeMap<String, DbtRunOptions>(settings.getState().projects.mapValues { (_, value) -> value.copy() })
        settings.loadState(DbtSettingsState())
        settings.loadState(saved)
        assertEquals("orders", root.runOptions.selection)
        assertEquals("tag:slow", root.runOptions.exclude)
        assertEquals("/artifacts/prod", root.runOptions.stateDirectory)
        assertTrue(root.runOptions.defer);
        assertTrue(root.runOptions.favorState)
        assertEquals("tag:other", other.runOptions.selection)
        assertEquals("qa", other.runOptions.target)
        assertEquals("", other.runOptions.stateDirectory)
        assertFalse(other.runOptions.defer);
        assertFalse(other.runOptions.favorState)
    }

    fun testSelectorCompletionPreservesIntersectionsDepthsAndOwningIdentities() {
        project()
        add("models/orders.sql", "select 1")
        add("selectors.yml", "selectors: [{name: finance, definition: orders}]\n")
        add("other/dbt_project.yml", "name: other\nconfig-version: 2\n")
        add("other/models/orders.sql", "select 2")
        val candidates = DbtSelectionReview.completions(workspace, root)
        assertTrue(candidates.contains("fqn:analytics.orders"))
        assertTrue(candidates.contains("selector:finance"))
        assertFalse(candidates.any { it.contains("other") })
        val text = "tag:finance,2+fqn:anal+1 package:pkg"
        val caret = text.indexOf("+1")
        val completed =
            DbtSelectionReview.complete(text, caret, candidates).find { it.first == "fqn:analytics.orders" }!!
        assertEquals("tag:finance,2+fqn:analytics.orders+1 package:pkg", completed.second)
        assertTrue(DbtSelectionReview.complete("bad", 3, candidates).isEmpty())
    }

    fun testExplicitCliRefreshKeepsTargetWrapperProfilesAndUsesLegacyNamedSelectorFlag() {
        project()
        add("models/orders.sql", "select 1")
        root.runOptions.executableArguments = "'a project'"
        root.runOptions.target = "qa"
        root.runOptions.profilesDirectory = "profiles with spaces"
        root.runOptions.selection = "selector:finance"
        root.runOptions.exclude = "tag:slow"
        val command = DbtCommand.resolveSelection(workspace, root, null).getOrThrow()
        assertEquals(
            listOf(
                "dbt",
                "a project",
                "ls",
                "--quiet",
                "--output",
                "json",
                "--selector",
                "finance",
                "--exclude",
                "tag:slow"
            ), command.arguments.take(10)
        )
        assertTrue(command.arguments.windowed(2).any { it == listOf("--target", "qa") })
        assertTrue(command.environment.getValue("DBT_PROFILES_DIR").endsWith("profiles with spaces"))
        assertEquals("Resolve Selection", command.action)
        assertEquals(root.directory.path, command.root)
    }

    fun testExcludesWithoutAnExpressionStayInsideTheSelectedDirectory() {
        project()
        val file = add("models/finance/orders.sql", "select 1")
        add("models/finance/slow.sql", "select 2")
        add("models/unrelated.sql", "select 3")
        val w = workspace
        val selected = DbtRunSelection.available(w).find { it.path == file.virtualFile.parent.path }!!
        root.runOptions.exclude = "slow"
        val command = DbtCommand.selection(w, root, "Build", fullRefresh = false, (selected)).getOrThrow()
        assertEquals(
            listOf("fqn:analytics.finance.orders"),
            command.arguments.dropWhile { it != "--select" }.drop(1).takeWhile { arg -> !arg.startsWith("-") }
                .takeWhile { !it.startsWith("--") })
        assertFalse(command.arguments.any { it.contains("unrelated") })
    }

    fun testCliReviewedRuntimeSelectionUsesOnlyKnownExactResources() {
        project()
        add("models/orders.sql", "select 1")
        add("models/unrelated.sql", "select 2")
        root.runOptions.selection = "config.materialized:view"
        assertTrue(DbtCommand.selection(workspace, root, "Build", fullRefresh = false).isFailure)
        val ids = listOf("model.analytics.orders")
        val command = DbtCommand.selection(workspace, root, "Build", fullRefresh = false, null, (ids)).getOrThrow()
        assertEquals(
            listOf("fqn:analytics.orders"),
            command.arguments.dropWhile { it != "--select" }.drop(1).takeWhile { arg -> !arg.startsWith("-") })
        assertTrue(
            DbtCommand.selection(
                workspace,
                root,
                "Build",
                fullRefresh = false,
                null,
                (listOf("model.other.orders"))
            ).isFailure
        )
        assertEquals(
            Result.success(ids),
            DbtSelectionReview.cliResources("log line\n{\"unique_id\":\"model.analytics.orders\"}\n")
        )
        assertTrue(DbtSelectionReview.cliResources("{\"info\":{\"msg\":\"log\"}}\n").isFailure)
        assertEquals(Result.success(emptyList<String>()), DbtSelectionReview.cliResources(""))
    }

    fun testNamedExclusionsReachBothManagedCommandsAsCanonicalResources() {
        project()
        add("models/orders.sql", "select 1")
        add("models/slow.sql", "select 2")
        add("selectors.yml", "selectors: [{name: omit, definition: slow}]\n")
        val w = workspace
        root.runOptions.selection = "*"
        root.runOptions.exclude = "selector:omit"
        val commands = listOf(
            DbtCommand.selection(w, root, "Build", fullRefresh = false),
            DbtCommand.resolveSelection(w, root, null)
        )
        commands.forEach { built ->
            val command = built.getOrThrow()
            assertTrue(command.arguments.windowed(2).any { it == listOf("--exclude", "fqn:analytics.slow") })
            assertFalse(command.arguments.contains("selector:omit"))
        }
        root.runOptions.selection = "+selector:omit"
        assertTrue(DbtCommand.resolveSelection(w, root, null).getOrThrow().arguments.contains("fqn:analytics.slow"))
    }


    fun testCliSelectionKeepsExactGenericAndUnitTestIdentitiesAndSourceLocations() {
        project()
        add("models/orders.sql", "select 1 as id")
        val yaml = add(
            "models/schema.yml",
            """models:
- name: orders
  columns: [{name: id, data_tests: [unique]}]
unit_tests:
- name: input_is_valid
  model: orders
"""
        )
        add(
            "target/manifest.json",
            """{"metadata":{"dbt_schema_version":"https://schemas.getdbt.com/dbt/manifest/v12.json","dbt_version":"1.10.0","generated_at":"2099-01-01T00:00:00Z"},"nodes":{
      "test.analytics.unique_orders_id.abc":{"unique_id":"test.analytics.unique_orders_id.abc","resource_type":"test","name":"unique_orders_id","package_name":"analytics","original_file_path":"models/schema.yml","column_name":"id","fqn":["analytics","unique_orders_id"],"test_metadata":{"name":"unique"},"config":{},"depends_on":{"nodes":["model.analytics.orders"]}}},"unit_tests":{}}
"""
        )
        val w = workspace
        val ids = listOf("test.analytics.unique_orders_id.abc", "unit_test.analytics.orders.input_is_valid")
        val known = DbtSelectionReview.knownCliResources(w, root).filter { r -> ids.contains(r.id) }
        assertEquals(ids.toSet(), known.map { it.id }.toSet())
        assertTrue(known.all { it.file == yaml })
        assertEquals(yaml.text.indexOf("unique"), known.find { it.id == ids.first() }!!.range.startOffset)
        val command = DbtCommand.selection(w, root, "Test", fullRefresh = false, cliIds = (ids)).getOrThrow()
        assertEquals(DbtCommandScope.Selected(known.map { it.selector }.distinct()), command.request.scope)
        assertTrue(
            DbtCommand.selection(
                w,
                root,
                "Test",
                fullRefresh = false,
                cliIds = (ids + "test.analytics.missing.hash")
            ).isFailure
        )
    }

    fun testNamedExclusionsRetainPackageVersionAndIndependentRootIdentity() {
        sameNamedOrders()
        add(
            "selectors.yml",
            """selectors:
- name: omit
  definition:
    union: [fqn:analytics.orders.v2, package:pkg]
"""
        )
        val w = workspace
        val terms = DbtSelectionReview.cliTerms(w, root, "selector:omit").getOrThrow()
        assertEquals(setOf("fqn:analytics.orders.v2", "fqn:pkg.orders"), terms.toSet())
        assertFalse(terms.any { it.contains("other") })
        assertFalse(terms.any { it.endsWith("v1") })
        assertFalse(terms.any { it.startsWith("selector:") })
    }

    fun testNamedGraphDepthsIntersectionsAndUnionsBecomeExactCliTerms() {
        chainProject(
            """selectors:
- {name: center, definition: c}
- {name: window, definition: '1+c+1'}
"""
        )
        val w = workspace
        assertEquals(
            setOf("fqn:analytics.a", "fqn:analytics.b", "fqn:analytics.c", "fqn:analytics.d"),
            DbtSelectionReview.cliTerms(w, root, "@selector:center").getOrThrow().toSet()
        )
        assertEquals(
            setOf("fqn:analytics.b", "fqn:analytics.c", "fqn:analytics.d"),
            DbtSelectionReview.cliTerms(w, root, "1+selector:center+1").getOrThrow().toSet()
        )
        assertEquals(
            setOf("fqn:analytics.a", "fqn:analytics.b", "fqn:analytics.c"),
            DbtSelectionReview.cliTerms(w, root, "selector:window,tag:finance fqn:analytics.a").getOrThrow().toSet()
        )
    }

    fun testCyclicMissingAndMixedRuntimeNamedExclusionsAreRejected() {
        project()
        add("models/orders.sql", "select 1")
        add(
            "selectors.yml",
            """selectors:
- {name: ordinary, definition: orders}
- {name: runtime, definition: 'state:modified'}
- {name: cycle_a, definition: 'selector:cycle_b'}
- {name: cycle_b, definition: 'selector:cycle_a'}
"""
        )
        val w = workspace
        listOf(
            "selector:cycle_a", "selector:missing", "selector:runtime", "selector:ordinary result:error",
            "selector:ordinary,state:modified", "selector:ordinary,,orders"
        ).forEach { expression ->
            val result = DbtSelectionReview.cliTerms(w, root, expression)
            assertTrue(expression + " must not be sent to dbt as a named exclusion", result.isFailure)
            assertTrue(result.exceptionOrNull()!!.message.orEmpty().contains("resolve completely"))
        }
    }

    fun testOrdinaryExplicitCliExclusionsStayUnchangedAndEmptyNamedSetsStayEmpty() {
        project()
        add("models/orders.sql", "select 1")
        add("selectors.yml", "selectors: [{name: none, definition: 'tag:absent'}]\n")
        val w = workspace
        assertEquals(Result.success(emptyList<String>()), DbtSelectionReview.cliTerms(w, root, " \n\t "))
        assertEquals(Result.success(emptyList<String>()), DbtSelectionReview.cliTerms(w, root, "selector:none"))
        assertEquals(
            Result.success(listOf("config.materialized:incremental", "state:modified,tag:slow", "@orders")),
            DbtSelectionReview.cliTerms(w, root, "config.materialized:incremental\n state:modified,tag:slow\t@orders")
        )
        assertEquals(
            Result.success(listOf("tag:selector:literal")),
            DbtSelectionReview.cliTerms(w, root, "tag:selector:literal")
        )
    }
}
