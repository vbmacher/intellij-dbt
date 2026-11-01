package com.github.vbmacher.intellij.dbt.ide

import com.intellij.codeInsight.daemon.LineMarkerInfo.LineMarkerGutterIconRenderer
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.LineMarkerSettings
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.psi.PsiPlainTextFile
import org.junit.Assert.*


/** Native highlighting proves placement in Community plain-text files and YAML. */
class DbtLineMarkerProviderTest : DbtTestCase() {
    private val gutters: List<LineMarkerGutterIconRenderer<*>>
        get() =
            myFixture.findAllGutters().filterIsInstance<LineMarkerGutterIconRenderer<*>>()
                .filter { it.tooltipText.orEmpty().startsWith("dbt:") }

    private val atCaret: DbtLineMarkerProvider.Marker
        get() {
            val line = myFixture.getEditor().getDocument().getLineNumber(myFixture.getCaretOffset())
            val markers = DbtLineMarkerProvider.markers(myFixture.getFile())
                .filter { m -> myFixture.getEditor().getDocument().getLineNumber(m.range.getStartOffset()) == line }
            assertEquals("One dbt marker on the caret line", 1, markers.size)
            return markers.first()
        }

    fun testMarkerCacheFollowsWorkspaceSettings() {
        project()
        add("models/input.sql", "select 1 as id")
        val model = configure("models/orders.sql", "<caret>select * from {{ ref('input') }}")
        val before = DbtLineMarkerProvider.markers(model)
        assertSame(before, DbtLineMarkerProvider.markers(model))
        com.github.vbmacher.intellij.dbt.project.DbtProjectSettings.get(getProject()).changed()
        assertNotSame("Settings changes must recompute markers", before, DbtLineMarkerProvider.markers(model))
    }

    fun testPlainSqlModelOffersPropertiesAndBothDependencyDirections() {
        project()
        val source = add("models/input.sql", "select 1 as id")
        val schema = add("models/schema.yml", "models:\n- name: orders\n  columns: [{name: id}]\n")
        val report = add("models/report.sql", "select * from {{ ref('orders') }}")
        val model = configure("models/orders.sql", "\n\n<caret>select * from {{ ref('input') }}")
        assertTrue(model is PsiPlainTextFile)
        val rendered = gutters
        assertEquals(1, rendered.size)
        assertEquals(2, rendered.first().getLineMarkerInfo().startOffset)
        assertNull("Anchor must be a leaf", rendered.first().getLineMarkerInfo().element!!.firstChild)
        assertTrue(rendered.first().isNavigateAction())
        val choices = atCaret.choices
        assertEquals(listOf("Properties", "Uses", "Used by"), choices.mapNotNull { it.separator })
        assertEquals(
            setOf(schema, source, report),
            choices.filterNot { it.reference }.map { it.target.getContainingFile() }.toSet()
        )
        assertEquals(
            setOf(model.getText().indexOf("input"), report.getText().indexOf("orders")),
            choices.filter { it.reference }.map { it.target.getTextOffset() }.toSet()
        )
        choices.find { it.separator == ("Properties") }!!.target.navigate(true)
        val selected = FileEditorManager.getInstance(getProject()).getSelectedTextEditor()
        assertEquals(schema.getText().indexOf("orders"), selected!!.getCaretModel().getOffset())
        assertEquals(schema.getVirtualFile(), FileEditorManager.getInstance(getProject()).getSelectedFiles().first())
    }

    fun testVersionedYamlPropertiesLinkToTheirPhysicalFiles() {
        project()
        val first = add("models/orders_previous.sql", "select 1")
        val latest = add("models/orders_v2.sql", "select 2")
        val schema = configure(
            "models/schema.yml",
            "models:\n- name: or<caret>ders\n  latest_version: 2\n  versions:\n  - v: 1\n    defined_in: orders_previous\n  - v: 2\n"
        )
        assertEquals(1, gutters.size)
        assertEquals(schema.getText().indexOf("orders"), gutters.first().getLineMarkerInfo().startOffset)
        assertEquals(setOf(first, latest), atCaret.choices.filter { it.separator == ("Definition") }
            .map { it.target.getContainingFile() }.toSet())
        open(first, 0)
        assertEquals(schema, atCaret.choices.find { it.separator == ("Properties") }!!.target.getContainingFile())
    }

    fun testMacroAndDocsMarkersOpenExactUsageLocations() {
        project()
        val caller = add("models/report.sql", "select {{ double(2) }}")
        val macroFile = configure("macros/math.sql", "{% macro dou<caret>ble(value) %}{{ value * 2 }}{% endmacro %}")
        assertEquals(1, gutters.size)
        assertEquals(macroFile.getText().indexOf("double"), gutters.first().getLineMarkerInfo().startOffset)
        val usage = atCaret.choices.find { it.reference }!!.target
        assertEquals(caller, usage.getContainingFile())
        assertEquals(caller.getText().indexOf("double"), usage.getTextOffset())
        val properties =
            add("models/schema.yml", "models:\n- name: report\n  description: \"{{ doc('report_doc') }}\"\n")
        configure("docs/catalog.md", "{% docs report_<caret>doc %}Report.{% enddocs %}")
        assertEquals(1, gutters.size)
        val docUsage = atCaret.choices.find { it.reference }!!.target
        assertEquals(properties, docUsage.getContainingFile())
        assertEquals(properties.getText().indexOf("report_doc"), docUsage.getTextOffset())
    }

    fun testUnitTestMarkerOffersModelAndFixtureFiles() {
        project()
        val model = add("models/orders.sql", "select 1 as id")
        val input = add("tests/fixtures/input.csv", "id\n1")
        val expected = add("tests/fixtures/expected.sql", "select 1 as id")
        configure(
            "tests/unit.yml",
            "unit_tests:\n- name: ex<caret>ample\n  model: orders\n  given:\n  - input: ref('orders')\n    format: csv\n    fixture: input\n  expect:\n    format: sql\n    fixture: expected\n"
        )
        assertEquals(1, gutters.size)
        assertEquals(
            setOf(model, input, expected),
            atCaret.choices.filterNot { it.reference }.map { it.target.getContainingFile() }.toSet()
        )
        open(input, 0)
        assertEquals(1, gutters.size)
        assertTrue(atCaret.choices.any { it.separator == ("Used by") })
    }

    fun testSourceTableMarkerKeepsTheSourceIdentity() {
        project()
        val caller = add("models/report.sql", "select * from {{ source('raw', 'events') }}")
        configure(
            "models/sources.yml",
            "sources:\n- name: raw\n  tables:\n  - name: ev<caret>ents\n- name: archived\n  tables:\n  - name: events\n"
        )
        assertEquals("Only the referenced source and its table have markers", 2, gutters.size)
        val usage = atCaret.choices.find { it.reference }!!.target
        assertEquals(caller, usage.getContainingFile())
        assertEquals(caller.getText().indexOf("events"), usage.getTextOffset())
    }

    fun testAmbiguousReferenceShowsOneMarkerAndAllCandidates() {
        project()
        val a = add("models/a/orders.sql", "select 1")
        val b = add("models/b/orders.sql", "select 2")
        configure("models/report.sql", "select * from {{ ref('or<caret>ders') }}")
        assertEquals("Declaration and reference on one line share a marker", 1, gutters.size)
        assertTrue(gutters.first().tooltipText.orEmpty().contains("Multiple definitions"))
        val candidates = atCaret.choices.filterNot { it.reference }.map { it.target.getContainingFile() }.toSet()
        assertEquals(setOf(a, b), candidates)
    }

    fun testMetadataStatusShowsStalenessAndYieldsToLocalSource() {
        project()
        configureSettings { it.manifestPath = "target/manifest.json" }
        val content =
            """{"metadata":{"dbt_schema_version":"https://schemas.getdbt.com/dbt/manifest/v12.json","generated_at":"2000-01-01T00:00:00Z"},"nodes":{"model.analytics.remote":{"unique_id":"model.analytics.remote","resource_type":"model","name":"remote","package_name":"analytics","original_file_path":"models/remote.sql"}}}"""
        val manifest = add("target/manifest.json", content)
        configure("models/report.sql", "select * from {{ ref('re<caret>mote') }}")
        assertEquals(1, gutters.size)
        assertTrue(gutters.first().tooltipText.orEmpty().contains("Stale metadata"))
        assertTrue(atCaret.choices.any { it.target.symbol.metadata })
        edit(manifest, content.replace("2000-01-01", "2099-01-01"))
        assertTrue(gutters.first().tooltipText.orEmpty().contains("Metadata target"))
        assertFalse(gutters.first().tooltipText.orEmpty().contains("Stale metadata"))
        val local = add("models/remote.sql", "select 1")
        assertEquals(1, gutters.size)
        assertFalse(gutters.first().tooltipText.orEmpty().contains("metadata"))
        assertEquals(
            setOf(local),
            atCaret.choices.filterNot { it.reference }.map { it.target.getContainingFile() }.toSet()
        )
    }

    fun testUdfAndPythonModelFilesOfferYamlProperties() {
        project()
        configureSettings { o -> o.dbtVersion = "1.12"; o.adapter = "snowflake" }
        val schema = add("functions/schema.yml", "functions:\n- name: hash_customer\n")
        configure("functions/hash_customer.js", "<caret>return customer_id;")
        assertEquals(1, gutters.size)
        assertEquals(schema, atCaret.choices.find { it.separator == ("Properties") }!!.target.getContainingFile())
        val modelProperties = add("models/schema.yml", "models:\n- name: orders\n")
        configure("models/orders.py", "<caret>def model(dbt, session):\n    return session\n")
        assertEquals(1, gutters.size)
        assertEquals(
            modelProperties,
            atCaret.choices.find { it.separator == ("Properties") }!!.target.getContainingFile()
        )
    }

    fun testUnsupportedAndDisabledResourcesHaveNoMarkers() {
        project()
        configureSettings { o -> o.dbtVersion = "1.11"; o.adapter = "snowflake" }
        val schema = add("functions/schema.yml", "functions:\n- name: hash_customer\n  language: javascript\n")
        configure("functions/hash_customer.js", "<caret>return customer_id;")
        assertEquals("JavaScript UDF navigation requires Core 1.12", 0, gutters.size)
        open(schema, schema.getText().indexOf("hash_customer"))
        assertEquals(0, gutters.size)
        add("models/schema.yml", "models:\n- name: disabled\n  config: {enabled: false}\n")
        configure("models/disabled.sql", "<caret>select 1")
        assertEquals(0, gutters.size)
    }

    fun testIgnoredGeneratedUnrelatedAndDynamicFilesHaveNoMarkers() {
        project()
        add("models/orders.sql", "select 1")
        add(".dbtignore", "models/ignored.sql\n")
        listOf("models/ignored.sql", "target/compiled/report.sql", "scripts/report.sql", "models/dynamic.sql")
            .forEach { path ->
                configure(path, "<caret>select * from {{ ref(runtime_name) }}")
                assertEquals(path, 0, gutters.size)
            }
    }

    fun testMarkersUpdateAfterPropertiesEditsAndCanBeDisabled() {
        project()
        val schema = add("models/schema.yml", "models:\n- name: orders\n")
        val model = configure("models/orders.sql", "<caret>select 1")
        assertEquals(1, gutters.size)
        edit(schema, "models:\n- name: other\n")
        assertEquals(0, gutters.size)
        edit(schema, "models:\n- name: orders\n")
        assertEquals(1, gutters.size)
        val provider = DbtLineMarkerProvider()
        val settings = LineMarkerSettings.getSettings()
        val enabled = settings.isEnabled(provider)
        try {
            settings.setEnabled(provider, false)
            DaemonCodeAnalyzer.getInstance(getProject()).restart("dbt gutter setting changed")
            assertEquals(0, gutters.size)
        } finally {
            settings.setEnabled(provider, enabled)
            DaemonCodeAnalyzer.getInstance(getProject()).restart("dbt gutter setting restored")
        }
        open(model, 0)
        assertEquals(1, gutters.size)
    }
}
