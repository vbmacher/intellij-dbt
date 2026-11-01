package com.github.vbmacher.intellij.dbt.ide

import com.intellij.codeInsight.navigation.CtrlMouseData
import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.util.TextRange
import com.intellij.util.concurrency.AppExecutorUtil
import org.junit.Assert.*

import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit

/** Ctrl-hover should expose a link on exactly the navigable name under the pointer. */
class DbtCtrlHoverTest : DbtTestCase() {
    private val hoverData: CtrlMouseData?
        get() {
            val editor = myFixture.getEditor()
            val file = myFixture.getFile()
            val offset = myFixture.getCaretOffset()
            return ReadAction.nonBlocking(Callable { GotoDeclarationAction().getCtrlMouseData(editor, file, offset) })
                .submit(AppExecutorUtil.getAppExecutorService()).get(10, TimeUnit.SECONDS)
        }

    private fun assertLink(name: String) {
        val file = myFixture.getFile()
        val offset = myFixture.getCaretOffset()
        val data = hoverData
        assertNotNull("Ctrl-hover should offer navigation for $name", data)
        assertTrue("Ctrl-hover on $name should highlight a link", data!!.isNavigatable)
        val start = file.getText().lastIndexOf(name, offset)
        assertTrue("The pointer should be inside $name", start >= 0 && offset < start + name.length)
        assertEquals(
            "Only the referenced name should be highlighted",
            listOf(TextRange(start, start + name.length)), data!!.ranges.toList()
        )
    }

    private fun assertNoLink() {
        val data = hoverData
        assertTrue("A non-navigable name should not be highlighted as a link", data == null || !data.isNavigatable)
    }

    fun testRefBuiltinIsHighlighted() {
        project()
        add("models/orders.sql", "select 1")
        configure("models/report.sql", "select * from {{ r<caret>ef('orders') }}")
        assertLink("ref")
    }

    fun testSourceBuiltinIsHighlighted() {
        project()
        add("models/sources.yml", "sources:\n- name: raw\n  tables: [{name: events}]\n")
        configure("models/report.sql", "select * from {{ sou<caret>rce('raw', 'events') }}")
        assertLink("source")
    }

    fun testModelArgumentIsHighlightedWithoutQuotesOrTheRestOfTheQuery() {
        project()
        add("models/orders.sql", "select 1")
        configure("models/report.sql", "select * from {{ ref('or<caret>ders') }} where id > 0")
        assertLink("orders")
    }

    fun testSourceNamespaceIsHighlightedSeparatelyFromTheTable() {
        project()
        add("models/sources.yml", "sources:\n- name: raw\n  tables: [{name: events}]\n")
        configure("models/report.sql", "select * from {{ source('r<caret>aw', 'events') }}")
        assertLink("raw")
    }

    fun testSourceTableIsHighlightedSeparatelyFromTheNamespace() {
        project()
        add("models/sources.yml", "sources:\n- name: raw\n  tables: [{name: events}]\n")
        configure("models/report.sql", "select * from {{ source('raw', 'ev<caret>ents') }}")
        assertLink("events")
    }

    fun testVariableBuiltinIsHighlighted() {
        project("vars: {start_date: today}\n")
        configure("models/report.sql", "select '{{ v<caret>ar('start_date') }}'")
        assertLink("var")
    }

    fun testVariableArgumentIsHighlighted() {
        project("vars: {start_date: today}\n")
        configure("models/report.sql", "select '{{ var('start_<caret>date') }}'")
        assertLink("start_date")
    }

    fun testMacroNameIsHighlighted() {
        project()
        add("macros/math.sql", "{% macro double(value) %}{{ value * 2 }}{% endmacro %}")
        configure("models/report.sql", "select {{ dou<caret>ble(value=2) }}")
        assertLink("double")
    }

    fun testNamedMacroArgumentIsHighlighted() {
        project()
        add("macros/math.sql", "{% macro double(value) %}{{ value * 2 }}{% endmacro %}")
        configure("models/report.sql", "select {{ double(val<caret>ue=2) }}")
        assertLink("value")
    }

    fun testYamlDocumentationNameIsHighlightedWithoutTheSurroundingDescription() {
        project()
        add("models/orders.sql", "select 1")
        add("docs/catalog.md", "{% docs orders_description %}Orders{% enddocs %}")
        configure(
            "models/orders.yml",
            "models:\n- name: orders\n  description: \"See {{ doc('orders_<caret>description') }} for details\"\n"
        )
        assertLink("orders_description")
    }

    fun testModelInMultilineYamlHookIsHighlighted() {
        project()
        add("models/orders.sql", "select 1")
        configure(
            "models/report.yml",
            "models:\n- name: report\n  config:\n    pre_hook: |\n      delete from {{ ref('or<caret>ders') }}\n      where id < 0\n"
        )
        assertLink("orders")
    }

    fun testYamlModelPropertyNameIsHighlighted() {
        project()
        add("models/orders.sql", "select 1")
        configure("models/orders.yml", "models:\n- name: or<caret>ders\n  description: Orders\n")
        assertLink("orders")
    }

    fun testAmbiguousModelStillHighlightsItsReferenceForTheChooser() {
        project()
        add("models/first/orders.sql", "select 1")
        add("models/second/orders.sql", "select 2")
        configure("models/report.sql", "select * from {{ ref('or<caret>ders') }}")
        assertLink("orders")
    }

    fun testCteReferenceIsHighlightedWithoutTheRestOfTheSqlFile() {
        project()
        configure("models/report.sql", "with recent as (select 1 as id)\nselect id from re<caret>cent")
        assertLink("recent")
    }

    fun testLocalJinjaNameIsHighlighted() {
        project()
        configure("models/report.sql", "{% set start_date = 'today' %}\nselect '{{ start_<caret>date }}'")
        assertLink("start_date")
    }

    fun testYamlFixtureNameIsHighlighted() {
        project()
        add("models/orders.sql", "select 1")
        add("tests/fixtures/orders.csv", "id\n1\n")
        configure(
            "models/unit.yml",
            "unit_tests:\n- name: example\n  model: orders\n  given:\n  - input: ref('orders')\n    format: csv\n    fixture: or<caret>ders\n"
        )
        assertLink("orders")
    }

    fun testHighlightingWorksWithAnExistingLanguageParser() {
        project("vars: {start_date: today}\n")
        withSqlParsedAsJson {
            val file = configure("models/report.sql", "{\"value\": \"{{ var('start_<caret>date') }}\"}")
            assertTrue(file is com.intellij.json.psi.JsonFile)
            assertLink("start_date")
        }
    }

    fun testUnresolvedModelIsNotHighlightedAsALink() {
        project()
        configure("models/report.sql", "select * from {{ ref('mis<caret>sing') }}")
        assertNoLink()
    }

    fun testJinjaCommentIsNotHighlightedAsALink() {
        project()
        add("models/orders.sql", "select 1")
        configure("models/report.sql", "{# {{ ref('or<caret>ders') }} #}")
        assertNoLink()
    }

    fun testRawBlockIsNotHighlightedAsALink() {
        project()
        add("models/orders.sql", "select 1")
        configure("models/report.sql", "{% raw %}{{ ref('or<caret>ders') }}{% endraw %}")
        assertNoLink()
    }

    fun testSqlPunctuationIsNotHighlightedAsALink() {
        project()
        add("models/orders.sql", "select 1")
        configure("models/report.sql", "select * from {{ ref(<caret>'orders') }}")
        assertNoLink()
    }
}
