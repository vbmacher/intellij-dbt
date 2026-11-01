package com.github.vbmacher.intellij.dbt.navigation

import com.github.vbmacher.intellij.dbt.ide.DbtTestCase
import com.github.vbmacher.intellij.dbt.lineage.DbtLineage
import com.github.vbmacher.intellij.dbt.project.DbtSymbolElement
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import org.junit.Assert.*

class DbtNavigationTest : DbtTestCase() {
    private fun target(file: PsiFile, text: String, occurrence: Int = 0): PsiElement {
        val all = targetsAt(file, text, occurrence)
        assertEquals("Targets for $text: $all", 1, all.size)
        return all.first()
    }

    private fun path(e: PsiElement): String = e.containingFile.virtualFile.path

    private fun uses(e: PsiElement): Int = ReferencesSearch.search(e).findAll().size

    fun testRefSeedsSnapshotsPropertiesAndReverseUsages() {
        bareProject()
        val model = add("models/orders.sql", "select 1 as id")
        add("seeds/countries.csv", "country\nUS\n")
        add("snapshots/snapshots.yml", "snapshots:\n- name: history\n  relation: ref('orders')\n")
        val properties = add("models/schema.yml", "models:\n- name: orders\n  columns:\n  - name: id\n")
        val usage =
            add("models/use.sql", "select * from {{ ref('orders') }} -- {{ ref('countries') }} {{ ref('history') }}")
        assertEquals(path(model), path(target(usage, "orders")))
        assertTrue(path(target(usage, "countries")).endsWith("countries.csv"))
        assertTrue(path(target(usage, "history")).endsWith("snapshots.yml"))
        assertEquals(path(model), path(target(properties, "orders")))
        assertEquals(3, uses(target(usage, "orders"))) // SQL, properties, and the snapshot relation expression.
    }

    fun testModelVersionsDefinedInAndLatest() {
        bareProject()
        add("models/order_previous.sql", "select 1")
        val latest = add("models/orders_v2.sql", "select 2")
        add(
            "models/orders.yml",
            "models:\n- name: orders\n  latest_version: 2\n  versions:\n  - v: 1\n    defined_in: order_previous\n  - v: 2\n"
        )
        val file = add("models/use.sql", "{{ ref('orders') }} {{ ref('orders', v=1) }} {{ ref('orders', version=2) }}")
        assertEquals(path(latest), path(target(file, "orders")))
        assertTrue(path(target(file, "orders", 1)).endsWith("order_previous.sql"))
        assertEquals(path(latest), path(target(file, "orders", 2)))
    }

    fun testSourceArgumentsUseLogicalNamesAndExactDeclarations() {
        bareProject()
        val schema = add(
            "models/sources.yml",
            "sources:\n- name: raw\n  schema: warehouse\n  tables:\n  - name: orders\n    identifier: physical_orders\n    columns:\n    - name: order_id\n"
        )
        val file = add("models/use.sql", "select * from {{ source('raw', 'orders') }}")
        val first = target(file, "raw").let { it as DbtSymbolElement }
        val second = target(file, "orders").let { it as DbtSymbolElement }
        assertEquals("source", first.symbol.kind)
        assertEquals("source_table", second.symbol.kind)
        assertEquals(path(schema), path(first))
        assertTrue(first.textOffset < second.textOffset)
        assertEquals(1, uses(second))
    }

    fun testMacroCallsArgumentsAndDocs() {
        bareProject()
        val macroFile = add(
            "macros/math.sql",
            "{% macro double(value) %}{{ value * 2 }}{% endmacro %}\n{% macro triple(value) %}{{ value * 3 }}{% endmacro %}"
        )
        val docs = add("docs/catalog.md", "{% docs orders_description %}Orders{% enddocs %}")
        val file = add("models/use.sql", "select {{ double(value=1) }}, {{ triple(1) }}")
        val schema =
            add("models/schema.yml", "models:\n- name: use\n  description: \"{{ doc('orders_description') }}\"\n")
        assertEquals(path(macroFile), path(target(file, "double")))
        assertEquals("parameter", target(file, "value").let { it as DbtSymbolElement }.symbol.kind)
        assertTrue(target(file, "double").textOffset < target(file, "triple").textOffset)
        assertEquals(path(docs), path(target(schema, "orders_description")))
        assertEquals(1, uses(target(file, "double")))
    }

    fun testYamlDecodedQuotedAndBlockOffsets() {
        bareProject("vars:\n  start_date: today\n")
        add("models/orders.sql", "select 1")
        val yaml = add(
            "models/schema.yml",
            "models:\n- name: orders\n  description: \"Example \\" + "\"" + " {{ var('start_date') }}\"\n  config:\n    pre_hook: |\n      select * from {{ ref('orders') }}\n"
        )
        assertNotNull(target(yaml, "start_date"))
        assertTrue(path(target(yaml, "orders", 1)).endsWith("orders.sql"))
        referencesAt(yaml, "start_date").forEach { ref ->
            assertEquals(
                "start_date",
                ref.rangeInElement.substring(yaml.text)
            )
        }
    }

    fun testLocalScopesAndShadowedBuiltin() {
        bareProject("vars:\n  start_date: today\n")
        val file = add(
            "models/use.sql",
            "{% set x = 1 %}{{ x }}{% for x in [1] %}{{ x }}{% endfor %}{{ x }}\n{% set var = custom %}{{ var('start_date') }}"
        )
        val first = target(file, "x", 1)
        val loop = target(file, "x", 3)
        val last = target(file, "x", 4)
        assertEquals(first, last)
        assertNotEquals(first, loop)
        assertTrue(referencesAt(file, "start_date").isEmpty())
    }

    fun testPackagesAndNestedProjectsKeepNamespaces() {
        bareProject("vars:\n  color: red\n")
        add("models/orders.sql", "select 1")
        add("dbt_packages/util/dbt_project.yml", "name: util\n")
        val packaged = add("dbt_packages/util/models/orders.sql", "select 2")
        add("dbt_packages/util/macros/double.sql", "{% macro double(x) %}{{ x * 2 }}{% endmacro %}")
        add("nested/dbt_project.yml", "name: nested\nvars:\n  color: blue\n")
        val nested = add("nested/models/use.sql", "{{ var('color') }} {{ ref('orders') }}")
        val file = add("models/use.sql", "{{ ref('util', 'orders') }} {{ util.double(2) }}")
        assertEquals(path(packaged), path(target(file, "orders")))
        assertEquals("blue", target(nested, "color").let { it as org.jetbrains.yaml.psi.YAMLKeyValue }.valueText)
        assertTrue(referencesAt(nested, "orders").all { it.candidates.isEmpty() })
        assertTrue(path(target(file, "util.double")).contains("dbt_packages/util"))
    }

    fun testMeshDependenciesAndAccess() {
        bareProject()
        add("finance/dbt_project.yml", "name: finance\n")
        val finance = add("finance/models/orders.sql", "{{ config(access='public') }}select 1")
        val file = add("models/use.sql", "{{ ref('finance', 'orders') }}")
        assertTrue(referencesAt(file, "orders").all { it.candidates.isEmpty() })
        add("dependencies.yml", "projects:\n- name: finance\n")
        assertEquals(path(finance), path(target(file, "orders")))
    }

    fun testDispatchAdapterAndDefaults() {
        bareProject()
        configureSettings { it.adapter = "postgres" }
        add(
            "macros/adapters.sql",
            "{% macro default__hash(x) %}{{ x }}{% endmacro %}\n{% macro postgres__hash(x) %}{{ x }}{% endmacro %}"
        )
        val file = add("models/use.sql", "{{ adapter.dispatch('hash')(1) }}")
        assertEquals("postgres__hash", target(file, "hash").let { it as DbtSymbolElement }.name)
    }

    fun testProfileTargetAndEnvironmentNames() {
        bareProject("profile: analytics\n")
        add(
            "profiles.yml",
            "analytics:\n  target: dev\n  outputs:\n    dev:\n      type: postgres\n      schema: public\n"
        )
        add(".env", "DBT_SCHEMA=private_value\n")
        val file = add("models/use.sql", "{{ target.schema }} {{ env_var('DBT_SCHEMA') }}")
        assertEquals("schema", target(file, "schema").let { it as org.jetbrains.yaml.psi.YAMLKeyValue }.keyText)
        val env = target(file, "DBT_SCHEMA")
        assertEquals("DBT_SCHEMA", env.text)
        assertFalse(env.toString().contains("private_value"))
    }

    fun testGenericAndUnitTests() {
        bareProject()
        add("models/orders.sql", "select 1")
        add("macros/tests.sql", "{% test positive(model, column_name, threshold) %}select 1{% endtest %}")
        add("tests/fixtures/orders.csv", "id\n1\n")
        val file = add(
            "models/schema.yml",
            "models:\n- name: orders\n  data_tests:\n  - positive:\n      arguments:\n        threshold: 0\nunit_tests:\n- name: test_orders\n  model: orders\n  given:\n  - input: ref('orders')\n    format: csv\n    fixture: orders\n"
        )
        assertEquals("generic_test", target(file, "positive").let { it as DbtSymbolElement }.symbol.kind)
        assertEquals("parameter", target(file, "threshold").let { it as DbtSymbolElement }.symbol.kind)
        assertTrue(path(target(file, "orders", 2)).endsWith("orders.sql"))
        assertTrue(path(target(file, "orders", 4)).endsWith("orders.csv"))
    }

    fun testSemanticMetricsGroupsAndExposures() {
        bareProject()
        val schema = add(
            "models/semantic.yml",
            "groups:\n- name: finance\nsemantic_models:\n- name: sales\n  model: ref('orders')\n  entities:\n  - name: customer\n  dimensions:\n  - name: ordered_at\n  measures:\n  - name: revenue\nmetrics:\n- name: total_revenue\n  type: simple\n  type_params:\n    measure: revenue\n- name: ratio\n  type: ratio\n  type_params:\n    numerator: total_revenue\nexposures:\n- name: dashboard\n  depends_on:\n  - ref('orders')\nsaved_queries:\n- name: revenue_report\n  query_params:\n    metrics: [total_revenue]\n"
        )
        add("models/orders.sql", "{{ config(group='finance') }}select 1")
        assertEquals("measure", target(schema, "revenue", 2).let { it as DbtSymbolElement }.symbol.kind)
        assertEquals("metric", target(schema, "total_revenue", 1).let { it as DbtSymbolElement }.symbol.kind)
    }

    fun testFunctionAndChecksVersionGates() {
        bareProject()
        configureSettings { o -> o.engine = "v2"; o.dbtVersion = "2.0" }
        add("functions/hash.sql", "select 1")
        val check = add(
            "checks/missing.sql",
            "select name from {{ info_schema('models') }} where name = {{ function('hash') }}"
        )
        assertTrue(path(target(check, "hash")).endsWith("hash.sql"))
        assertEquals("documentation", target(check, "models").let { it as DbtSymbolElement }.symbol.kind)
    }

    fun testSqlCteAliasesAndDeclaredColumns() {
        bareProject()
        add("models/orders.sql", "select 1 as order_id")
        add("models/schema.yml", "models:\n- name: orders\n  columns:\n  - name: order_id\n")
        val file = add(
            "models/use.sql",
            "with recent as (select order_id from {{ ref('orders') }}) select r.order_id as id from recent r order by id"
        )
        assertEquals("cte", target(file, "recent", 1).let { it as DbtSymbolElement }.symbol.kind)
        assertEquals("sql_alias", target(file, "r.order_id").let { it as DbtSymbolElement }.symbol.kind)
        assertEquals("column", target(file, "order_id", 1).let { it as DbtSymbolElement }.symbol.kind)
        assertEquals("sql_projection", target(file, "id", 3).let { it as DbtSymbolElement }.symbol.kind)
    }

    fun testSqlRangesAfterTabs() {
        bareProject()
        val file = add("models/tabs.sql", "with\trecent as (select 1 as id)\nselect\tr.id from\trecent r")
        val reference = referencesAt(file, "recent", 1).find { it.candidates.isNotEmpty() }!!
        assertEquals("recent", reference.rangeInElement.substring(file.text))
        assertEquals(file.text.indexOf("recent"), target(file, "recent", 1).textRange.startOffset)
    }

    fun testUnsavedEditsAndSearchScope() {
        bareProject()
        add("models/orders.sql", "select 1")
        val file = add("models/use.sql", "{{ ref('orders') }}")
        val second = add("tests/use.sql", "{{ ref('orders') }}")
        val expected = target(file, "orders")
        assertEquals(2, uses(expected))
        assertEquals(
            1,
            ReferencesSearch.search(expected, GlobalSearchScope.fileScope(getProject(), second.virtualFile))
                .findAll().size
        )
        edit(second, "{{ ref('missing') }}")
        assertEquals(1, uses(expected))
    }

    fun testAmbiguityAndDisabledResourcesDoNotPickFirst() {
        bareProject()
        add("models/a/orders.sql", "select 1")
        add("models/b/orders.sql", "select 2")
        add("models/off.sql", "{{ config(enabled=false) }}select 3")
        val file = add("models/use.sql", "{{ ref('orders') }} {{ ref('off') }}")
        val reference = referencesAt(file, "orders").first()
        assertEquals(2, reference.multiResolve(false).size)
        assertNull(reference.resolve())
        assertTrue(referencesAt(file, "off").all { it.candidates.isEmpty() })
    }

    fun testStaticArgumentsAndWrapperMacros() {
        bareProject()
        add("models/orders.sql", "select 1")
        add("macros/wrapper.sql", "{% macro lookup(model_name) %}{{ builtins.ref(model_name) }}{% endmacro %}")
        val file = add(
            "models/use.sql",
            "{% set resource = 'ord' ~ 'ers' %}{{ ref(resource) }} {{ lookup('orders') }} {{ ref('ord' ~ 'ers') }}"
        )
        assertTrue(referencesAt(file, "resource", 1).flatMap { it.candidates }
            .any { e -> path(e).endsWith("orders.sql") })
        assertTrue(path(target(file, "orders")).endsWith("orders.sql"))
        val concatenated = DbtNavigation.inFile(file).find { it.label == "static reference" }!!
        assertTrue(concatenated.candidates.isNotEmpty())
    }

    fun testVarsFileConflictAndPackageDefaults() {
        bareProject()
        configureSettings { it.dbtVersion = "1.12" }
        add("vars.yml", "vars:\n  color: root\n  util:\n    scoped: override\n")
        add("dbt_packages/util/dbt_project.yml", "name: util\nvars:\n  fallback: package\n  scoped: package\n")
        val file = add(
            "dbt_packages/util/models/use.sql",
            "{{ builtins.var('color') }} {{ var('scoped') }} {{ var('fallback') }}"
        )
        assertTrue(path(target(file, "color")).endsWith("vars.yml"))
        assertEquals("override", target(file, "scoped").let { it as org.jetbrains.yaml.psi.YAMLKeyValue }.valueText)
        assertTrue(path(target(file, "fallback")).endsWith("dbt_packages/util/dbt_project.yml"))
        edit(projectFile("dbt_project.yml", root), "name: analytics\nvars:\n  color: conflict\n")
        assertTrue(referencesAt(file, "color").all { it.candidates.isEmpty() })
    }


    fun testConfigurationHierarchyAndCustomMaterialization() {
        bareProject("models:\n  analytics:\n    +materialized: view\n    marts:\n      +materialized: table\n")
        add(
            "macros/custom.sql",
            "{% materialization incremental_custom, adapter='default' %}select 1{% endmaterialization %}"
        )
        add("models/marts/schema.yml", "models:\n- name: orders\n  config:\n    materialized: incremental_custom\n")
        val file = add(
            "models/marts/orders.sql",
            "{{ config(materialized='incremental_custom') }} {{ config.get('materialized') }}select 1"
        )
        assertEquals(4, referencesAt(file, "materialized").flatMap { it.candidates }.distinct().size)
        assertEquals("materialization", target(file, "incremental_custom").let { it as DbtSymbolElement }.symbol.kind)
    }

    fun testEmbeddedSemanticsAndTypedExpressions() {
        bareProject()
        configureSettings { it.dbtVersion = "1.12" }
        add("models/orders.sql", "select 1")
        val file = add(
            "models/schema.yml",
            "models:\n- name: orders\n  semantic_model:\n    name: sales\n    enabled: true\n  agg_time_dimension: ordered_at\n  columns:\n  - name: customer_id\n    entity:\n      name: customer\n      type: primary\n  - name: ordered_at\n    dimension:\n      type: time\n  - name: country\n    dimension:\n      type: categorical\n  metrics:\n  - name: revenue\n    type: simple\nmetrics:\n- name: ratio\n  type: derived\n  filter: \"{{ Dimension('customer__country') }} = 'US'\"\n"
        )
        assertEquals("dimension", target(file, "ordered_at").let { it as DbtSymbolElement }.symbol.kind)
        assertEquals("customer", target(file, "customer__country").let { it as DbtSymbolElement }.name)
        assertEquals("country", target(file, "country", 1).let { it as DbtSymbolElement }.name)
        assertTrue(workspace.symbols.any { s -> s.kind == "metric" && s.name == "revenue" && s.owner == "orders" })
    }

    fun testFunctionOverloadsAndProperties() {
        bareProject()
        configureSettings { it.dbtVersion = "1.12" }
        add("functions/hash.sql", "select 1")
        add("functions/hash_numeric.sql", "select 2")
        val props = add(
            "functions/hash.yml",
            "functions:\n- name: hash\n  arguments:\n  - name: input\n  returns:\n    data_type: string\n  overloads:\n  - defined_in: hash_numeric\n    arguments:\n    - name: numeric_input\n"
        )
        val file = add("models/use.sql", "select {{ function('hash') }}(1)")
        assertEquals(2, referencesAt(file, "hash").flatMap { it.candidates }.distinct().size)
        assertTrue(path(target(props, "hash_numeric")).endsWith("hash_numeric.sql"))
    }

    fun testOptionalManifestMetadataAndLocalSourceAuthority() {
        bareProject()
        configureSettings { it.manifestPath = "target/manifest.json" }
        val manifest = add(
            "target/manifest.json",
            """{"metadata":{"dbt_schema_version":"https://schemas.getdbt.com/dbt/manifest/v12.json"},"nodes":{"model.analytics.remote":{"unique_id":"model.analytics.remote","resource_type":"model","name":"remote","package_name":"analytics","original_file_path":"models/remote.sql"}}}"""
        )
        val file = add("models/use.sql", "{{ ref('remote') }}")
        val metadata = target(file, "remote").let { it as DbtSymbolElement }
        assertTrue(metadata.symbol.metadata)
        assertEquals(path(manifest), path(metadata))
        val local = add("models/remote.sql", "select 1")
        assertEquals(path(local), path(target(file, "remote")))
        assertFalse(target(file, "remote").let { it as DbtSymbolElement }.symbol.metadata)
    }

    fun testUnsupportedManifestSchemaDoesNotCreateDeclarations() {
        bareProject()
        configureSettings { it.manifestPath = "target/manifest.json" }
        add(
            "target/manifest.json",
            """{"metadata":{"dbt_schema_version":"https://schemas.getdbt.com/dbt/manifest/v999.json"},"nodes":{"model.analytics.remote":{"resource_type":"model","name":"remote"}}}"""
        )
        val file = add("models/use.sql", "{{ ref('remote') }}")
        assertTrue(referencesAt(file, "remote").all { it.candidates.isEmpty() })
    }

    fun testSelectorsWildcardsAndLineageCycles() {
        bareProject()
        val orders = add("models/orders.sql", "select * from {{ ref('customers') }}")
        add("models/customers.sql", "select * from {{ ref('orders') }}")
        val selector = add(
            "selectors.yml",
            "selectors:\n- name: nightly\n  definition: '+orders+'\n- name: selected\n  definition:\n    union:\n    - selector: nightly\n    - 'fqn:*ers'\n"
        )
        val results = referencesAt(selector, "+orders+").flatMap { it.candidates }
        assertEquals(
            setOf("orders", "customers"),
            results.filterIsInstance<DbtSymbolElement>().map { e -> e.name }.toSet()
        )
        assertNotNull(target(selector, "nightly", 1))
        val w = workspace
        val edges = DbtLineage.edges(w)
        assertEquals(2, edges.size)
        assertEquals(
            1,
            DbtLineage.traverse(edges, w.symbols.find { s -> s.file == orders }!!, downstream = true, 100).size
        )
    }

    fun testIgnorePathsAndOlderVersionGates() {
        bareProject()
        add(".dbtignore", "models/ignored/**\n")
        add("models/ignored/orders.sql", "select 1")
        add("functions/hash.sql", "select 1")
        add("checks/missing.sql", "{{ ref('orders') }}")
        val file = add("models/use.sql", "{{ ref('orders') }} {{ function('hash') }}")
        assertTrue(referencesAt(file, "orders").all { it.candidates.isEmpty() })
        assertTrue(referencesAt(file, "hash").all { it.candidates.isEmpty() })
        assertFalse(workspace.symbols.any { it.kind == "check" })
    }

    fun testRestrictedYamlContextsAndLiteralVarValues() {
        bareProject("vars:\n  color: \"{{ ref('orders') }}\"\n")
        add("models/orders.sql", "select 1")
        val profiles = add("profiles.yml", "analytics:\n  target: \"{{ ref('orders') }}\"\n")
        assertTrue(DbtNavigation.inFile(profiles).isEmpty())
        assertTrue(referencesAt(projectFile("dbt_project.yml", root), "orders").isEmpty())
        val schema = add(
            "models/schema.yml",
            "models:\n- name: orders\n  config:\n    meta:\n      text: \"{{ doc('unknown') }}\"\n"
        )
        assertTrue(referencesAt(schema, "unknown").isEmpty())
    }

    fun testMacroParametersAndNamespaceFieldsFindUsages() {
        bareProject()
        val macroFile = add("macros/format.sql", "{% macro format(value) %}{{ value }}{% endmacro %}")
        val local = add(
            "models/use.sql",
            "{% set ns = namespace(found=false) %}{{ ns.found }}{% with item=1 %}{{ item }}{% endwith %}{{ item }}"
        )
        val parameter = target(macroFile, "value", 1)
        assertEquals("parameter", parameter.let { it as DbtSymbolElement }.symbol.kind)
        assertEquals(1, uses(parameter))
        assertEquals("namespace attribute", target(local, "found", 1).let { it as DbtSymbolElement }.symbol.kind)
        assertNotNull(target(local, "item", 1))
        assertTrue(referencesAt(local, "item", 2).all { it.candidates.isEmpty() })
    }

    fun testResourceMovesInvalidateIndex() {
        bareProject()
        val model = add("models/orders.sql", "select 1")
        val file = add("models/use.sql", "{{ ref('orders') }} {{ ref('renamed') }}")
        assertNotNull(target(file, "orders"))
        WriteCommandAction.runWriteCommandAction(getProject(), object : Runnable {
            override fun run(): Unit = model.virtualFile.rename(this, "renamed.sql")
        })
        assertTrue(referencesAt(file, "orders").all { it.candidates.isEmpty() })
        assertTrue(path(target(file, "renamed")).endsWith("renamed.sql"))
    }


    fun testNativeFindUsagesTargetFromPlainTextDeclaration() {
        bareProject()
        val model = add("models/orders.sql", "select 1")
        val usage = add("models/use.sql", "{{ ref('orders') }}")
        myFixture.configureFromExistingVirtualFile(model.virtualFile)
        val flags =
            com.intellij.codeInsight.TargetElementUtil.ELEMENT_NAME_ACCEPTED or com.intellij.codeInsight.TargetElementUtil.REFERENCED_ELEMENT_ACCEPTED
        val selected = com.intellij.codeInsight.TargetElementUtil.findTargetElement(myFixture.editor, flags)!!
        assertTrue("Native Find Usages target: $selected", selected.let { it is DbtSymbolElement })
        assertEquals(1, myFixture.findUsages(selected).size)
        assertEquals(
            "model",
            com.intellij.lang.findUsages.LanguageFindUsages.INSTANCE.forLanguage(selected.language).getType(selected)
        )
    }

    fun testSqlProjectionColumnsWithoutYamlSchema() {
        bareProject()
        val definition = add("models/orders.sql", "select 1 as order_id")
        val file = add("models/use.sql", "select o.order_id from {{ ref('orders') }} o")
        assertEquals(path(definition), path(target(file, "order_id")))
        assertEquals("order_id", target(file, "order_id").text)
    }

    fun testFunctionBodyArgumentsUseLibraryExpressionParser() {
        bareProject()
        configureSettings { it.dbtVersion = "1.12" }
        val props = add(
            "functions/is_positive.yml",
            "functions:\n- name: is_positive\n  arguments:\n  - name: input_value\n  returns:\n    data_type: boolean\n"
        )
        val body = add("functions/is_positive.sql", "input_value > 0")
        assertEquals(path(props), path(target(body, "input_value")))
        assertEquals("parameter", target(body, "input_value").let { it as DbtSymbolElement }.symbol.kind)
    }

    fun testNoPythonPluginIsRequiredForSqlNavigation() {
        bareProject()
        val definition = add("models/orders.sql", "select 1")
        val py = add("models/fallback.py", "def model(dbt, session): return dbt.ref('orders')")
        assertTrue(DbtNavigation.inFile(py).isEmpty())
        val usage = add("models/use.sql", "{{ ref('orders') }}")
        assertEquals(path(definition), path(target(usage, "orders")))
    }

    fun testBuiltinVarOverridesAndExplicitBuiltinEscape() {
        bareProject("vars:\n  color: red\n")
        add("macros/var.sql", "{% macro var(name) %}{{ builtins.var(name) }}{% endmacro %}")
        val file = add("models/use.sql", "{{ var('color') }} {{ builtins.var('color') }}")
        assertTrue(referencesAt(file, "color").all { it.canonicalText == "delegated reference" })
        assertEquals("macro", target(file, "var").let { it as DbtSymbolElement }.symbol.kind)
        assertNotNull(target(file, "color", 1))
    }

    fun testPackageMacroVariableScopeComesFromCallingNode() {
        bareProject("vars:\n  color: global\n  analytics:\n    color: root_node\n  util:\n    color: package_node\n")
        add("dbt_packages/util/dbt_project.yml", "name: util\n")
        val macroFile = add("dbt_packages/util/macros/color.sql", "{% macro color() %}{{ var('color') }}{% endmacro %}")
        add("models/use.sql", "{{ util.color() }}")
        assertEquals(
            "root_node",
            target(macroFile, "color", 1).let { it as org.jetbrains.yaml.psi.YAMLKeyValue }.valueText
        )
    }

    fun testSourceNamespaceDoesNotLeakFromInstalledPackage() {
        bareProject()
        add("models/sources.yml", "sources:\n- name: raw\n  tables:\n  - name: events\n")
        add("dbt_packages/util/dbt_project.yml", "name: util\n")
        add("dbt_packages/util/models/sources.yml", "sources:\n- name: raw\n  tables:\n  - name: events\n")
        val file = add("models/use.sql", "{{ source('raw', 'events') }}")
        assertEquals(1, referencesAt(file, "events").flatMap { it.candidates }.distinct().size)
        assertFalse(path(target(file, "events")).contains("dbt_packages"))
    }

    fun testProjectConfigVarsRequireVarsFile() {
        bareProject("vars:\n  color: red\nmodels:\n  analytics:\n    +schema: \"{{ var('color') }}\"\n")
        val file = projectFile("dbt_project.yml", root)
        assertTrue(referencesAt(file, "color", 1).all { it.candidates.isEmpty() })
    }

    fun testSqlConfigEnabledOverridesPropertyDefaults() {
        bareProject()
        add("models/off.sql", "{{ config(enabled=false) }}select 1")
        add("models/off.yml", "models:\n- name: off\n  config:\n    enabled: true\n")
        val file = add("models/use.sql", "{{ ref('off') }}")
        assertTrue(referencesAt(file, "off").all { it.candidates.isEmpty() })
    }


    fun testInfoSchemaColumnsAndProvedResourceNamePredicates() {
        bareProject("info_schema:\n  version: 1\n")
        configureSettings { o -> o.engine = "v2"; o.dbtVersion = "2.0" }
        val model = add("models/orders.sql", "select 1")
        val check = add(
            "checks/descriptions.sql",
            "select name from {{ info_schema('models') }} where name = 'orders' and description <> 'orders'"
        )
        assertEquals("documentation", target(check, "name").let { it as DbtSymbolElement }.symbol.kind)
        assertEquals(path(model), path(target(check, "orders")))
        assertTrue(referencesAt(check, "orders", 1).isEmpty())
    }

    fun testIndexLargeProjectAndPartialTemplates() {
        bareProject()
        (1..80).forEach { i -> add("models/model_$i.sql", "select $i as id") }
        val file = add("models/use.sql", "{{ ref('model_80') }}\n{{ ref('model_1')")
        assertTrue(path(target(file, "model_80")).endsWith("model_80.sql"))
        assertTrue(path(target(file, "model_1")).endsWith("model_1.sql"))
    }

    fun testLoopIterableAndNamespaceMutationKeepOuterBindings() {
        bareProject()
        val file = add(
            "models/use.sql",
            "{% set item = 'orders' %}{% set ns = namespace(total=0) %}{% for item in item %}{% set ns.total = ns.total + 1 %}{{ item }}{% endfor %}{{ ns.total }} {{ item }}"
        )
        val iterable = target(file, "item", 2)
        assertEquals(file.text.indexOf("item"), iterable.textOffset)
        assertTrue(target(file, "item", 3).textOffset > iterable.textOffset)
        assertEquals(iterable, target(file, "item", 4))
        assertEquals(target(file, "total", 1), target(file, "total", 3))
        assertEquals(3, uses(target(file, "total", 3)))
    }

    fun testIntelliJOverridesWithoutReadingEnvironmentValues() {
        bareProject("vars:\n  color: default\n")
        configureSettings { o ->
            o.variables = "{\"color\":\"selected\"}"
            o.environmentVariables.put("DBT_ENV_SECRET_TOKEN", "secret-value")
        }
        add(".env", "DBT_ENV_SECRET_TOKEN=another-secret\n")
        val file = add("models/use.sql", "{{ var('color') }} {{ env_var('DBT_ENV_SECRET_TOKEN') }}")
        assertTrue(target(file, "color").let { it as DbtSymbolElement }.symbol.attributes.contains("settings"))
        val env = target(file, "DBT_ENV_SECRET_TOKEN")
        assertTrue(env.let { it as DbtSymbolElement }.symbol.attributes.contains("settings"))
        assertFalse(env.containingFile.text.contains("secret-value"))
        assertFalse(env.toString().contains("secret-value"))
        assertEquals("DBT_ENV_SECRET_TOKEN", env.text)
        assertEquals(1, uses(target(file, "color")))
    }

    fun testInstalledDependencyDeclarationsAndLockNavigation() {
        bareProject("packages-install-path: vendor\n")
        add("vendor/dbt_utils/dbt_project.yml", "name: dbt_utils\nversion: 1.2.0\n")
        val lock = add("package-lock.yml", "packages:\n- package: dbt-labs/dbt_utils\n  version: 1.2.0\n")
        val dependencies = add(
            "packages.yml",
            "packages:\n- package: dbt-labs/dbt_utils\n  version: 1.2.0\n- package: missing/unknown\n"
        )
        assertTrue(path(target(dependencies, "dbt-labs/dbt_utils")).endsWith("vendor/dbt_utils/dbt_project.yml"))
        assertEquals(path(lock), path(target(dependencies, "1.2.0")))
        assertTrue(referencesAt(dependencies, "missing/unknown").all { it.candidates.isEmpty() })
    }

    fun testMaterializationAdapterSelectionAndIndirectUsages() {
        bareProject()
        configureSettings { it.adapter = "redshift" }
        add(
            "macros/table.sql",
            "{% materialization audit, adapter='default' %}select 1{% endmaterialization %}{% materialization audit, adapter='postgres' %}select 2{% endmaterialization %}"
        )
        val file = add("models/use.sql", "{{ config(materialized='audit') }} select 1")
        val selected = target(file, "audit").let { it as DbtSymbolElement }
        assertEquals("postgres", selected.symbol.attributes["adapter"])
        assertEquals(1, uses(selected))
        configureSettings { it.adapter = "bigquery" }
        assertEquals("default", target(file, "audit").let { it as DbtSymbolElement }.symbol.attributes["adapter"])
    }

    fun testMetricAliasesAndSavedQueryExports() {
        bareProject()
        val file = add(
            "models/metrics.yml",
            "metrics:\n- name: revenue\n  type: simple\n- name: growth\n  type: derived\n  type_params:\n    expr: current / previous\n    metrics:\n    - name: revenue\n      alias: current\n    - name: revenue\n      alias: previous\nsaved_queries:\n- name: revenue_report\n  query_params:\n    metrics: [revenue]\n  exports:\n  - name: daily_export\n"
        )
        assertEquals("metric_alias", target(file, "current").let { it as DbtSymbolElement }.symbol.kind)
        assertEquals(1, uses(target(file, "current")))
        assertEquals("revenue_report", target(file, "daily_export").let { it as DbtSymbolElement }.name)
        assertEquals(3, uses(target(file, "revenue", 1)))
    }

    fun testUnitThisAndVariableOverrideIdentity() {
        bareProject("vars:\n  color: default\n")
        val model = add("models/orders.sql", "select 1")
        val test = add(
            "models/unit.yml",
            "unit_tests:\n- name: example\n  model: orders\n  given:\n  - input: this\n    rows: []\n  overrides:\n    vars:\n      color: selected\n"
        )
        assertEquals(path(model), path(target(test, "this")))
        assertTrue(path(target(test, "color")).endsWith("dbt_project.yml"))
        assertEquals(1, uses(target(test, "color")))
    }

    fun testRunYamlDoesNotProvideSelectionNavigation() {
        bareProject()
        add("models/orders.sql", "select 1 as id")
        add("models/schema.yml", "models:\n- name: orders\n  columns:\n  - name: id\n")
        val run = add("run.yml", "select: orders\n")
        assertTrue(DbtNavigation.inFile(run).isEmpty())
    }

    fun testUnsupportedTemplateLoaderDoesNotInventImports() {
        bareProject()
        add("macros/math.sql", "{% macro double(value) %}{{ value * 2 }}{% endmacro %}")
        val file = add(
            "models/use.sql",
            "{% from 'macros/math.sql' import double as twice %} {{ twice(2) }} {% include 'macros/math.sql' %}"
        )
        assertTrue(referencesAt(file, "twice", 1).flatMap { it.candidates }.isEmpty())
        assertTrue(referencesAt(file, "macros/math.sql").flatMap { it.candidates }.isEmpty())
    }

    fun testDeclarationTargetsSurviveInsertedText() {
        bareProject()
        val macroFile = add("macros/math.sql", "{% macro double(value) %}{{ value * 2 }}{% endmacro %}")
        val file = add("models/use.sql", "{{ double(2) }}")
        val oldTarget = target(file, "double")
        val oldOffset = oldTarget.textOffset
        WriteCommandAction.runWriteCommandAction(getProject(), object : Runnable {
            override fun run() {
                val dm = PsiDocumentManager.getInstance(getProject())
                dm.getDocument(macroFile)!!.insertString(0, "-- Added comment\n");
                dm.commitAllDocuments()
            }
        })
        assertTrue(oldTarget.isValid)
        assertEquals(oldOffset + 17, oldTarget.textOffset)
        assertEquals(1, uses(oldTarget))
    }

    fun testCatalogUsesLogicalResourceIdsAndKeepsSourceColumnsAuthoritative() {
        bareProject()
        configureSettings { it.catalogPath = "target/catalog.json" }
        add("models/orders.sql", "select * from physical_table")
        add("models/schema.yml", "models:\n- name: orders\n  columns:\n  - name: id\n")
        add(
            "target/catalog.json",
            """{"metadata":{"dbt_schema_version":"https://schemas.getdbt.com/dbt/catalog/v1.json","generated_at":"2099-01-01T00:00:00Z"},"nodes":{"model.analytics.orders":{"unique_id":"model.analytics.orders","metadata":{"name":"warehouse_alias"},"columns":{"id":{"name":"id"},"total":{"name":"total"}}}}}"""
        )
        val file = add("models/use.sql", "select o.id, o.total from {{ ref('orders') }} o")
        assertFalse(target(file, "id").let { it as DbtSymbolElement }.symbol.metadata)
        assertTrue(target(file, "total").let { it as DbtSymbolElement }.symbol.metadata)
        assertTrue(path(target(file, "total")).endsWith("catalog.json"))
    }

    fun testMetadataDependenciesMapToLocalSource() {
        bareProject()
        configureSettings { it.stateManifestPath = "state/manifest.json" }
        add("models/orders.sql", "select 1")
        add(
            "state/manifest.json",
            """{"metadata":{"dbt_schema_version":"https://schemas.getdbt.com/dbt/manifest/v12.json"},"nodes":{"model.analytics.remote":{"unique_id":"model.analytics.remote","resource_type":"model","name":"remote","package_name":"analytics","original_file_path":"models/remote.sql","depends_on":{"nodes":["model.analytics.orders"]}}}}"""
        )
        val w = workspace
        val edge = DbtLineage.edges(w).find { it.from.name == "remote" }!!
        assertEquals("orders", edge.to.name)
        assertFalse(edge.to.metadata)
        assertTrue(edge.from.element.toString().contains("stale metadata"))
    }

    fun testFiniteCollectionAndUnknownConditionalNames() {
        bareProject()
        add("models/orders.sql", "select 1")
        add("models/customers.sql", "select 1")
        val file = add(
            "models/use.sql",
            "{% set names = ['orders', 'customers'] %}{% for name in names %}{{ ref(name) }}{% endfor %}{% if execute %}{% set unknown = 'orders' %}{% else %}{% set unknown = env_var('MODEL') %}{% endif %}{{ ref(unknown) }}"
        )
        assertEquals(
            setOf("orders", "customers"),
            referencesAt(file, "name", 3).flatMap { it.candidates }.filterIsInstance<DbtSymbolElement>()
                .filter { e -> e.symbol.kind == "model" }.map { e -> e.name }.toSet()
        )
        assertTrue(referencesAt(file, "unknown", 2).flatMap { it.candidates }
            .all { e -> e.let { it as DbtSymbolElement }.symbol.kind == "local" })
    }

    fun testTemporaryCompletionFilesHaveNoDbtReferences() {
        bareProject()
        val temporary = com.intellij.psi.PsiFileFactory.getInstance(getProject()).createFileFromText(
            "orders.sql",
            com.intellij.openapi.fileTypes.PlainTextFileType.INSTANCE,
            "{{ ref('orders') }}"
        )
        assertFalse(temporary.isPhysical)
        assertTrue(DbtNavigation.inFile(temporary).isEmpty())
        assertTrue(temporary.references.isEmpty())
        assertTrue(workspace.root(temporary) == null)
    }

    fun testGeneratedAndIgnoredNestedProjectsAreNotActivated() {
        bareProject("target-path: build-output\n")
        add(".dbtignore", "scratch/\n")
        add("build-output/generated/dbt_project.yml", "name: generated\n")
        add("build-output/generated/models/orders.sql", "select 1")
        add("scratch/dbt_project.yml", "name: scratch\n")
        add("scratch/models/orders.sql", "select 1")
        add("dbt_packages/utils/dbt_project.yml", "name: utils\n")
        add("dbt_packages/utils/models/orders.sql", "select 1")
        val roots = workspace.roots.map { it.name }.toSet()
        assertEquals(setOf("analytics", "utils"), roots)
    }

    fun testInheritedDisabledConfigurationAndPackageOverrides() {
        bareProject("models:\n  analytics:\n    disabled:\n      +enabled: false\n  utils:\n    +group: finance\n")
        add("models/disabled/off.sql", "select 1")
        add("models/disabled/on.sql", "{{ config(enabled=true) }}select 1")
        add("dbt_packages/utils/dbt_project.yml", "name: utils\n")
        add("dbt_packages/utils/models/orders.sql", "select 1")
        val file = add("models/use.sql", "{{ ref('off') }} {{ ref('on') }} {{ ref('utils', 'orders') }}")
        assertTrue(referencesAt(file, "off").all { it.candidates.isEmpty() })
        assertTrue(path(target(file, "on")).endsWith("on.sql"))
        assertEquals("finance", target(file, "orders").let { it as DbtSymbolElement }.symbol.attributes["group"])
        val w = workspace
        assertFalse(
            DbtLineage.edges(w)
                .any { e -> e.from.attributes.contains("property") && e.from.name == e.to.name && e.from.kind == e.to.kind })
    }

    fun testMultilineSqlNavigationKeepsDeclarationOffsets() {
        bareProject()
        add("models/orders.sql", "select 1 as order_id")
        add("models/schema.yml", "models:\n- name: orders\n  columns:\n  - name: order_id\n")
        val file = add(
            "models/use.sql",
            "with recent as (\n  select order_id from {{ ref('orders') }}\n)\nselect r.order_id as id from recent r order by id\n"
        )
        assertEquals(file.text.indexOf("recent"), target(file, "recent", 1).textOffset)
        assertTrue(path(target(file, "order_id", 1)).endsWith("schema.yml"))
        assertEquals(1, uses(target(file, "recent", 1)))
    }

    fun testNativeGoToDeclarationActionNavigatesWithinSqlFile() {
        bareProject()
        add("models/orders.sql", "select 1 as order_id")
        val file = add(
            "models/use.sql",
            "with recent as (\n  select order_id from {{ ref('orders') }}\n)\nselect r.order_id from recent r\n"
        )
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        myFixture.editor.caretModel.moveToOffset(file.text.lastIndexOf("recent") + 1)
        myFixture.performEditorAction("GotoDeclaration")
        await("Go to Declaration did not move to the CTE") { myFixture.editor.caretModel.offset == file.text.indexOf("recent") }
    }

    fun testNestedDefaultsDoNotCreateFakeMacroParameters() {
        bareProject()
        val macroFile = add(
            "macros/format.sql",
            "{% macro format(a=['one', 'two'], b=namespace(flag=true)) %}{{ a }} {{ b }}{% endmacro %}"
        )
        val file = add("models/use.sql", "{{ format(b=1) }}")
        val parameter = target(file, "b").let { it as DbtSymbolElement }
        assertEquals("parameter", parameter.symbol.kind)
        assertEquals(2, workspace.symbols.count { s -> s.file == macroFile && s.kind == "parameter" })
        assertEquals(2, uses(parameter))
    }

    fun testRepeatedNamedArgumentsNavigateToTheirOwnMacro() {
        bareProject()
        add(
            "macros/math.sql",
            "{% macro double(value) %}{{ value * 2 }}{% endmacro %}{% macro triple(value) %}{{ value * 3 }}{% endmacro %}"
        )
        val file = add("models/use.sql", "{{ double(value=1) + triple(value=2) }}")
        assertEquals("double", target(file, "value").let { it as DbtSymbolElement }.symbol.owner)
        assertEquals("triple", target(file, "value", 1).let { it as DbtSymbolElement }.symbol.owner)
    }

    fun testDocumentationTargetsUseCanonicalUrlsAndContexts() {
        bareProject()
        val file = add("models/use.sql", "{{ env_var('MISSING') }} {{ run_query('select 1') }}")
        assertEquals(
            "https://docs.getdbt.com/reference/dbt-jinja-functions/env_var?version=1.10",
            target(file, "env_var").let { it as DbtSymbolElement }.symbol.attributes["url"]
        )
        assertEquals(
            "https://docs.getdbt.com/reference/dbt-jinja-functions/run_query?version=1.10",
            target(file, "run_query").let { it as DbtSymbolElement }.symbol.attributes["url"]
        )
        add(".env", "NAME=local\n")
        val deps = add("dependencies.yml", "packages:\n- package: \"{{ env_var('NAME') }}\"\n")
        assertTrue(DbtNavigation.inFile(deps).all { it.candidates.isEmpty() })
        add("models/orders.sql", "select 1")
        val python =
            add("models/use.py", "def model(dbt, session):\n    text = \"{{ ref('orders') }}\"\n    return text\n")
        assertTrue(referencesAt(python, "orders").all { it.candidates.isEmpty() })
    }

    fun testCallBlockParametersDoNotShadowTheInvocationArguments() {
        bareProject()
        val file = add(
            "models/use.sql",
            "{% set user = 'outer' %}{% call(user) consume(user) %}{{ user }}{% endcall %}{{ user }}"
        )
        val outer = target(file, "user", 2)
        val parameter = target(file, "user", 3)
        assertEquals(file.text.indexOf("user"), outer.textOffset)
        assertEquals(file.text.indexOf("user", outer.textOffset + 1), parameter.textOffset)
        assertEquals(outer, target(file, "user", 4))
        assertEquals(2, uses(outer))
        assertEquals(1, uses(parameter))
    }

    fun testSelectorTermsKeepExactRangesAndIgnoreVariableValues() {
        bareProject()
        add("models/orders.sql", "select 1")
        add("models/customers.sql", "select 1")
        val file = add(
            "selectors.yml",
            "selectors:\n- name: nightly\n  definition: 'orders customers,orders'\nvars:\n  names: [orders]\n"
        )
        assertTrue(path(target(file, "orders")).endsWith("orders.sql"))
        assertTrue(path(target(file, "customers")).endsWith("customers.sql"))
        assertEquals(2, uses(target(file, "orders")))
        assertTrue(referencesAt(file, "orders", 2).all { it.candidates.isEmpty() })
        assertEquals("customers", referencesAt(file, "customers").first().rangeInElement.substring(file.text))
    }

    fun testFixtureFormatsAndResourceExtensionsKeepSeparateIdentities() {
        bareProject()
        add("models/orders.sql", "select 1")
        add("tests/fixtures/input.csv", "id\n1\n")
        add("tests/fixtures/input.sql", "select 1 as id")
        add("models/not_a_model.csv", "id\n1\n")
        add("seeds/not_a_seed.sql", "select 1")
        val file = add(
            "models/unit.yml",
            "unit_tests:\n- name: example\n  model: orders\n  given:\n  - input: ref('orders')\n    format: csv\n    fixture: input\n  expect:\n    format: sql\n    fixture: input\n"
        )
        val csv = target(file, "input", 1)
        val sql = target(file, "input", 2)
        assertTrue(path(csv).endsWith("input.csv"))
        assertTrue(path(sql).endsWith("input.sql"))
        assertEquals("fixture", csv.let { it as DbtSymbolElement }.symbol.kind)
        assertEquals(1, uses(csv))
        assertEquals(1, uses(sql))
        assertFalse(workspace.symbols.any { s -> setOf("not_a_model", "not_a_seed").contains(s.name) })
    }

    fun testWithValuesUseOuterScopeAndNestedKeywordsAreNotBindings() {
        bareProject()
        val file = add(
            "models/use.sql",
            "{% set x = 'outer' %}{% with x=x, y=namespace(flag=true) %}{{ x }} {{ y }} {{ flag }}{% endwith %}{{ x }}"
        )
        val outer = target(file, "x", 2)
        val inner = target(file, "x", 3)
        assertEquals(file.text.indexOf("x"), outer.textOffset)
        assertEquals(file.text.indexOf("x", outer.textOffset + 1), inner.textOffset)
        assertEquals(outer, target(file, "x", 4))
        assertEquals(2, uses(outer))
        assertEquals(1, uses(inner))
        assertTrue(referencesAt(file, "flag", 1).all { it.candidates.isEmpty() })
        assertEquals("local", target(file, "y", 1).let { it as DbtSymbolElement }.symbol.kind)
    }
}
