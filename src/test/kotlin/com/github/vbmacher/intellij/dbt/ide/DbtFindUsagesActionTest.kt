package com.github.vbmacher.intellij.dbt.ide

import com.intellij.psi.PsiFile
import com.intellij.usages.UsageInfo2UsageAdapter
import org.junit.Assert.*


/** Exercise Find Usages through the editor action and its native result view. */
class DbtFindUsagesActionTest : DbtTestCase() {
    private fun location(file: PsiFile, text: String, occurrence: Int = 0): Triple<PsiFile, Int, String> {
        val offset = generateSequence(file.text.indexOf(text)) { i -> file.text.indexOf(text, i + 1) }
            .drop(occurrence).first()
        assertTrue("Missing expected usage '$text' in ${file.name}", offset >= 0)
        return Triple(file, offset, text)
    }

    private fun assertUsages(vararg expected: Triple<PsiFile, Int, String>) {
        val usages = myFixture.testFindUsagesUsingAction().toList()
        val locations = usages.map { usage ->
            assertTrue("Expected a source usage, got $usage", usage is UsageInfo2UsageAdapter)
            val info = (usage as UsageInfo2UsageAdapter).usageInfo
            val element = info.getElement()
            val range = info.getRangeInElement()
            assertNotNull("Usage element", element)
            assertNotNull("Usage range", range)
            Triple(element!!.containingFile, element.textOffset + range!!.startOffset, range.substring(element.text))
        }
        assertEquals("Number of usages", expected.size, locations.size)
        assertEquals("Usage files, offsets and highlighted names", expected.toSet(), locations.toSet())
    }

    fun testModelUsagesIncludeSqlPropertiesAndSnapshotRelations() {
        project()
        val orders = configure("models/orders.sql", "<caret>select 1 as order_id")
        val caller =
            add("models/report.sql", "select * from {{ ref('orders') }} union all select * from {{ ref('orders') }}")
        val properties = add("models/orders.yml", "models:\n- name: orders\n  columns: [{name: order_id}]\n")
        val snapshot = add("snapshots/history.yml", "snapshots:\n- name: history\n  relation: ref('orders')\n")

        open(orders, 0)
        assertUsages(
            location(caller, "orders"), location(caller, "orders", 1),
            location(properties, "orders"), location(snapshot, "orders")
        )
    }

    fun testJinjaCommentsAndRawBlocksAreExcludedButSqlCommentsCount() {
        project()
        val orders = configure("models/orders.sql", "<caret>select 1")
        val caller = add(
            "models/report.sql",
            "{# {{ ref('orders') }} #}\n{% raw %}{{ ref('orders') }}{% endraw %}\n-- {{ ref('orders') }}\nselect * from {{ ref('orders') }}"
        )

        open(orders, 0)
        assertUsages(location(caller, "orders", 2), location(caller, "orders", 3))
    }

    fun testSourceTableUsagesDoNotIncludeSameNamedTablesInAnotherSource() {
        project()
        val schema = configure(
            "models/sources.yml",
            "sources:\n- name: raw\n  tables:\n  - name: ev<caret>ents\n- name: archived\n  tables:\n  - name: events\n"
        )
        val caller = add(
            "models/report.sql",
            "{{ source('raw', 'events') }} {{ source('archived', 'events') }} {{ source('raw', 'events') }}"
        )

        open(schema, schema.getText().indexOf("events") + 2)
        assertUsages(location(caller, "events"), location(caller, "events", 2))
    }

    fun testMacroUsagesKeepThePackageNamespace() {
        project()
        val macroFile = configure("macros/math.sql", "{% macro dou<caret>ble(value) %}{{ value * 2 }}{% endmacro %}")
        add("dbt_packages/util/dbt_project.yml", "name: util\n")
        add("dbt_packages/util/macros/math.sql", "{% macro double(value) %}{{ value * 2 }}{% endmacro %}")
        val caller = add("models/report.sql", "select {{ double(1) }}, {{ util.double(2) }}, {{ double(3) }}")

        open(macroFile, macroFile.getText().indexOf("double") + 2)
        assertUsages(location(caller, "double"), location(caller, "double", 2))
    }

    fun testMacroParameterUsagesIncludeTheBodyAndNamedCallerArguments() {
        project()
        val macroFile = configure("macros/math.sql", "{% macro double(val<caret>ue) %}{{ value * 2 }}{% endmacro %}")
        add("macros/other.sql", "{% macro triple(value) %}{{ value * 3 }}{% endmacro %}")
        val caller = add("models/report.sql", "select {{ double(value=1) }}, {{ triple(value=2) }}")

        open(macroFile, macroFile.getText().indexOf("value") + 2)
        assertUsages(location(macroFile, "value", 1), location(caller, "value"))
    }

    fun testDocsUsagesIncludeDescriptionsButExcludeSqlAndMetadataStrings() {
        project()
        val docs = configure("docs/catalog.md", "{% docs orders_<caret>description %}Orders{% enddocs %}")
        add("models/orders.sql", "{{ doc('orders_description') }}")
        val schema = add(
            "models/orders.yml",
            "models:\n- name: orders\n  description: \"{{ doc('orders_description') }}\"\n  columns:\n  - name: id\n    description: \"{{ doc('orders_description') }}\"\n  config:\n    meta:\n      description: \"{{ doc('orders_description') }}\"\n"
        )

        open(docs, docs.getText().indexOf("orders_description") + 2)
        assertUsages(location(schema, "orders_description"), location(schema, "orders_description", 1))
    }

    fun testScopedVariableUsagesExcludeTheShadowedGlobalDefaultAndAnotherProject() {
        val config = project("vars:\n  color: global\n  analytics:\n    color: scoped\n")
        val caller = add("models/report.sql", "{{ var('color') }} {{ var('color', 'fallback') }}")
        add("nested/dbt_project.yml", "name: nested\nvars: {color: nested}\n")
        add("nested/models/report.sql", "{{ var('color') }}")

        open(config, config.getText().lastIndexOf("color") + 2)
        assertUsages(location(caller, "color"), location(caller, "color", 1))
    }

    fun testEnvironmentUsagesHighlightTheKeyWithoutItsValueOrFallback() {
        project()
        configureSettings { it.environmentFiles = ".env" }
        val env = configure(".env", "DBT_<caret>SCHEMA=private_schema\n")
        val caller =
            add("models/report.sql", "{{ env_var('DBT_SCHEMA') }} {{ env_var('DBT_SCHEMA', 'private_schema') }}")

        open(env, 2)
        assertUsages(location(caller, "DBT_SCHEMA"), location(caller, "DBT_SCHEMA", 1))
    }

    fun testFixtureUsagesDoNotIncludeSameNamedModelReferencesOrInlineRows() {
        project()
        add("models/orders.sql", "select 1")
        val fixture = configure("tests/fixtures/orders.csv", "<caret>id\n1\n")
        val unit = add(
            "models/unit.yml",
            "unit_tests:\n- name: example\n  model: orders\n  given:\n  - input: ref('orders')\n    format: csv\n    fixture: orders\n  - input: ref('orders')\n    rows: [{id: orders}]\n  expect:\n    format: csv\n    fixture: orders\n"
        )

        open(fixture, 0)
        assertUsages(location(unit, "orders", 2), location(unit, "orders", 5))
    }

    fun testFindUsagesFromACallerFindsOtherCallersOfTheSameModel() {
        project()
        add("models/orders.sql", "select 1")
        val first = configure("models/first.sql", "select * from {{ ref('or<caret>ders') }}")
        val second = add("models/second.sql", "select * from {{ ref('orders') }}")

        open(first, first.getText().indexOf("orders") + 2)
        assertUsages(location(first, "orders"), location(second, "orders"))
    }

    fun testFindUsagesWithAnExistingLanguageParserUsesThePublicSymbolSearch() {
        project()
        withSqlParsedAsJson {
            add("models/orders.sql", "{}")
            val first = configure("models/first.sql", "{\"value\": \"{{ ref('or<caret>ders') }}\"}")
            val second = add("models/second.sql", "{\"value\": \"{{ ref('orders') }}\"}")
            val offset = first.getText().indexOf("orders") + 2
            assertTrue(first is com.intellij.json.psi.JsonFile)
            assertNull("This parser does not expose a legacy PSI reference", first.findReferenceAt(offset))
            open(first, offset)
            assertUsages(location(first, "orders"), location(second, "orders"))
        }
    }
}
