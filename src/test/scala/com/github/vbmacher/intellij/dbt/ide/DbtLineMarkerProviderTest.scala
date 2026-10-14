package com.github.vbmacher.intellij.dbt.ide

import com.intellij.codeInsight.daemon.LineMarkerInfo.LineMarkerGutterIconRenderer
import com.intellij.codeInsight.daemon.{DaemonCodeAnalyzer, LineMarkerSettings}
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.psi.PsiPlainTextFile
import org.junit.Assert._

import scala.jdk.CollectionConverters._

/** Native highlighting proves placement in Community plain-text files and YAML. */
final class DbtLineMarkerProviderTest extends DbtCodeInsightTestCase {
  private def gutters: Vector[LineMarkerGutterIconRenderer[_]] =
    myFixture.findAllGutters().asScala.collect {
      case renderer: LineMarkerGutterIconRenderer[_] if renderer.getTooltipText.startsWith("dbt:") => renderer
    }.toVector

  private def atCaret: DbtLineMarkerProvider.Marker = {
    val line = myFixture.getEditor.getDocument.getLineNumber(myFixture.getCaretOffset)
    val markers = DbtLineMarkerProvider.markers(myFixture.getFile)
      .filter(m => myFixture.getEditor.getDocument.getLineNumber(m.range.getStartOffset) == line)
    assertEquals("One dbt marker on the caret line", 1, markers.size)
    markers.head
  }

  def testPlainSqlModelOffersPropertiesAndBothDependencyDirections(): Unit = {
    project()
    val source = add("models/input.sql", "select 1 as id")
    val schema = add("models/schema.yml", "models:\n- name: orders\n  columns: [{name: id}]\n")
    val report = add("models/report.sql", "select * from {{ ref('orders') }}")
    val model = configure("models/orders.sql", "\n\n<caret>select * from {{ ref('input') }}")
    assertTrue(model.isInstanceOf[PsiPlainTextFile])
    val rendered = gutters
    assertEquals(1, rendered.size)
    assertEquals(2, rendered.head.getLineMarkerInfo.startOffset)
    assertNull("Anchor must be a leaf", rendered.head.getLineMarkerInfo.getElement.getFirstChild)
    assertTrue(rendered.head.isNavigateAction)
    val choices = atCaret.choices
    assertEquals(Vector("Properties", "Uses", "Used by"), choices.flatMap(_.separator))
    assertEquals(Set(schema, source, report), choices.filterNot(_.reference).map(_.target.getContainingFile).toSet)
    assertEquals(Set(model.getText.indexOf("input"), report.getText.indexOf("orders")),
      choices.filter(_.reference).map(_.target.getTextOffset).toSet)
    choices.find(_.separator.contains("Properties")).get.target.navigate(true)
    val selected = FileEditorManager.getInstance(getProject).getSelectedTextEditor
    assertEquals(schema.getText.indexOf("orders"), selected.getCaretModel.getOffset)
    assertEquals(schema.getVirtualFile, FileEditorManager.getInstance(getProject).getSelectedFiles.head)
  }

  def testVersionedYamlPropertiesLinkToTheirPhysicalFiles(): Unit = {
    project()
    val first = add("models/orders_previous.sql", "select 1")
    val latest = add("models/orders_v2.sql", "select 2")
    val schema = configure("models/schema.yml",
      "models:\n- name: or<caret>ders\n  latest_version: 2\n  versions:\n  - v: 1\n    defined_in: orders_previous\n  - v: 2\n")
    assertEquals(1, gutters.size)
    assertEquals(schema.getText.indexOf("orders"), gutters.head.getLineMarkerInfo.startOffset)
    assertEquals(Set(first, latest), atCaret.choices.filter(_.separator.contains("Definition"))
      .map(_.target.getContainingFile).toSet)
    open(first, 0)
    assertEquals(schema, atCaret.choices.find(_.separator.contains("Properties")).get.target.getContainingFile)
  }

  def testMacroAndDocsMarkersOpenExactUsageLocations(): Unit = {
    project()
    val caller = add("models/report.sql", "select {{ double(2) }}")
    val macroFile = configure("macros/math.sql", "{% macro dou<caret>ble(value) %}{{ value * 2 }}{% endmacro %}")
    assertEquals(1, gutters.size)
    assertEquals(macroFile.getText.indexOf("double"), gutters.head.getLineMarkerInfo.startOffset)
    val usage = atCaret.choices.find(_.reference).get.target
    assertEquals(caller, usage.getContainingFile)
    assertEquals(caller.getText.indexOf("double"), usage.getTextOffset)
    val properties = add("models/schema.yml", "models:\n- name: report\n  description: \"{{ doc('report_doc') }}\"\n")
    configure("docs/catalog.md", "{% docs report_<caret>doc %}Report.{% enddocs %}")
    assertEquals(1, gutters.size)
    val docUsage = atCaret.choices.find(_.reference).get.target
    assertEquals(properties, docUsage.getContainingFile)
    assertEquals(properties.getText.indexOf("report_doc"), docUsage.getTextOffset)
  }

  def testUnitTestMarkerOffersModelAndFixtureFiles(): Unit = {
    project()
    val model = add("models/orders.sql", "select 1 as id")
    val input = add("tests/fixtures/input.csv", "id\n1")
    val expected = add("tests/fixtures/expected.sql", "select 1 as id")
    configure("tests/unit.yml", "unit_tests:\n- name: ex<caret>ample\n  model: orders\n  given:\n  - input: ref('orders')\n    format: csv\n    fixture: input\n  expect:\n    format: sql\n    fixture: expected\n")
    assertEquals(1, gutters.size)
    assertEquals(Set(model, input, expected), atCaret.choices.filterNot(_.reference).map(_.target.getContainingFile).toSet)
    open(input, 0)
    assertEquals(1, gutters.size)
    assertTrue(atCaret.choices.exists(_.separator.contains("Used by")))
  }

  def testSourceTableMarkerKeepsTheSourceIdentity(): Unit = {
    project()
    val caller = add("models/report.sql", "select * from {{ source('raw', 'events') }}")
    configure("models/sources.yml", "sources:\n- name: raw\n  tables:\n  - name: ev<caret>ents\n- name: archived\n  tables:\n  - name: events\n")
    assertEquals("Only the referenced source and its table have markers", 2, gutters.size)
    val usage = atCaret.choices.find(_.reference).get.target
    assertEquals(caller, usage.getContainingFile)
    assertEquals(caller.getText.indexOf("events"), usage.getTextOffset)
  }

  def testAmbiguousReferenceShowsOneMarkerAndAllCandidates(): Unit = {
    project()
    val a = add("models/a/orders.sql", "select 1")
    val b = add("models/b/orders.sql", "select 2")
    configure("models/report.sql", "select * from {{ ref('or<caret>ders') }}")
    assertEquals("Declaration and reference on one line share a marker", 1, gutters.size)
    assertTrue(gutters.head.getTooltipText.contains("Multiple definitions"))
    val candidates = atCaret.choices.filterNot(_.reference).map(_.target.getContainingFile).toSet
    assertEquals(Set(a, b), candidates)
  }

  def testMetadataStatusShowsStalenessAndYieldsToLocalSource(): Unit = {
    project()
    add(".dbt-navigation.yml", "manifest: target/manifest.json\n")
    val content = """{"metadata":{"dbt_schema_version":"https://schemas.getdbt.com/dbt/manifest/v12.json","generated_at":"2000-01-01T00:00:00Z"},"nodes":{"model.analytics.remote":{"unique_id":"model.analytics.remote","resource_type":"model","name":"remote","package_name":"analytics","original_file_path":"models/remote.sql"}}}"""
    val manifest = add("target/manifest.json", content)
    configure("models/report.sql", "select * from {{ ref('re<caret>mote') }}")
    assertEquals(1, gutters.size)
    assertTrue(gutters.head.getTooltipText.contains("Stale metadata"))
    assertTrue(atCaret.choices.exists(_.target.symbol.metadata))
    edit(manifest, content.replace("2000-01-01", "2099-01-01"))
    assertTrue(gutters.head.getTooltipText.contains("Metadata target"))
    assertFalse(gutters.head.getTooltipText.contains("Stale metadata"))
    val local = add("models/remote.sql", "select 1")
    assertEquals(1, gutters.size)
    assertFalse(gutters.head.getTooltipText.contains("metadata"))
    assertEquals(Set(local), atCaret.choices.filterNot(_.reference).map(_.target.getContainingFile).toSet)
  }

  def testUdfAndPythonModelFilesOfferYamlProperties(): Unit = {
    project()
    add(".dbt-navigation.yml", "dbt-version: '1.12'\nadapter: snowflake\n")
    val schema = add("functions/schema.yml", "functions:\n- name: hash_customer\n")
    configure("functions/hash_customer.js", "<caret>return customer_id;")
    assertEquals(1, gutters.size)
    assertEquals(schema, atCaret.choices.find(_.separator.contains("Properties")).get.target.getContainingFile)
    val modelProperties = add("models/schema.yml", "models:\n- name: orders\n")
    configure("models/orders.py", "<caret>def model(dbt, session):\n    return session\n")
    assertEquals(1, gutters.size)
    assertEquals(modelProperties, atCaret.choices.find(_.separator.contains("Properties")).get.target.getContainingFile)
  }

  def testUnsupportedAndDisabledResourcesHaveNoMarkers(): Unit = {
    project()
    add(".dbt-navigation.yml", "dbt-version: '1.11'\nadapter: snowflake\n")
    val schema = add("functions/schema.yml", "functions:\n- name: hash_customer\n  language: javascript\n")
    configure("functions/hash_customer.js", "<caret>return customer_id;")
    assertEquals("JavaScript UDF navigation requires Core 1.12", 0, gutters.size)
    open(schema, schema.getText.indexOf("hash_customer"))
    assertEquals(0, gutters.size)
    add("models/schema.yml", "models:\n- name: disabled\n  config: {enabled: false}\n")
    configure("models/disabled.sql", "<caret>select 1")
    assertEquals(0, gutters.size)
  }

  def testIgnoredGeneratedUnrelatedAndDynamicFilesHaveNoMarkers(): Unit = {
    project()
    add("models/orders.sql", "select 1")
    add(".dbtignore", "models/ignored.sql\n")
    Vector("models/ignored.sql", "target/compiled/report.sql", "scripts/report.sql", "models/dynamic.sql")
      .foreach { path =>
        configure(path, "<caret>select * from {{ ref(runtime_name) }}")
        assertEquals(path, 0, gutters.size)
      }
  }

  def testMarkersUpdateAfterPropertiesEditsAndCanBeDisabled(): Unit = {
    project()
    val schema = add("models/schema.yml", "models:\n- name: orders\n")
    val model = configure("models/orders.sql", "<caret>select 1")
    assertEquals(1, gutters.size)
    edit(schema, "models:\n- name: other\n")
    assertEquals(0, gutters.size)
    edit(schema, "models:\n- name: orders\n")
    assertEquals(1, gutters.size)
    val provider = new DbtLineMarkerProvider
    val settings = LineMarkerSettings.getSettings
    val enabled = settings.isEnabled(provider)
    try {
      settings.setEnabled(provider, false)
      DaemonCodeAnalyzer.getInstance(getProject).restart("dbt gutter setting changed")
      assertEquals(0, gutters.size)
    } finally {
      settings.setEnabled(provider, enabled)
      DaemonCodeAnalyzer.getInstance(getProject).restart("dbt gutter setting restored")
    }
    open(model, 0)
    assertEquals(1, gutters.size)
  }
}
