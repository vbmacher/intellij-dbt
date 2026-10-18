package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.navigation.DbtSqlAnalysis
import com.github.vbmacher.intellij.dbt.project.DbtWorkspace
import org.junit.Assert._
import scala.jdk.CollectionConverters._

final class DbtAuthoringTest extends DbtCodeInsightTestCase {
  def testBundledTemplatesLoadThroughNativeTemplateSettings(): Unit = {
    val settings = com.intellij.codeInsight.template.impl.TemplateSettings.getInstance()
    Vector("dbtref", "dbtsource", "dbtconfig").foreach(name =>
      assertNotNull(name, settings.getTemplate(name, "dbt")))
  }
  private def schema(): Unit = {
    project()
    add("models/orders.sql", "select 1 as id, 2 as amount")
    add("models/schema.yml", "models: [{name: orders, columns: [{name: id, data_type: int, description: Identifier}, {name: amount, data_type: decimal}]}]\n" +
      "sources: [{name: raw, tables: [{name: events, columns: [{name: event_id}]}]}, {name: crm, tables: [{name: customers}]}]\n")
  }
  def testNativeRefCompletionPreservesQuotesAndCaret(): Unit = {
    schema()
    configure("models/report.sql", "select * from {{ ref('ord<caret>') }}")
    val items = Option(myFixture.completeBasic()).toVector.flatMap(_.toVector).map(_.getLookupString)
    if (items.nonEmpty) {
      assertTrue(items.contains("orders"))
      myFixture.getLookup.setCurrentItem(myFixture.getLookupElements.find(_.getLookupString == "orders").get)
      myFixture.finishLookup('\n')
    }
    assertEquals("select * from {{ ref('orders') }}", myFixture.getFile.getText)
    assertEquals("select * from {{ ref('orders".length, myFixture.getCaretOffset)
  }
  def testSourceTablesAreScopedAndRefreshAfterUnsavedChanges(): Unit = {
    schema()
    val file = configure("models/report.sql", "select * from {{ source('raw', '<caret>') }}")
    def names = DbtCompletion.suggestions(file, myFixture.getCaretOffset).map(_.text)
    assertEquals(Vector("events"), names)
    val properties = DbtWorkspace.get(getProject).files.find(_.getName == "schema.yml").get
    edit(properties, properties.getText.replace("name: events", "name: activity"))
    assertEquals(Vector("activity"), names)
  }
  def testColumnCompletionRespectsAliasAndCteProjection(): Unit = {
    schema()
    val file = configure("models/report.sql", "with o as (select id from {{ ref('orders') }}) select x.<caret> from o x")
    assertEquals(Vector("id"), DbtCompletion.suggestions(file, myFixture.getCaretOffset).map(_.text))
  }
  def testLineageDistinguishesPassthroughAndCalculation(): Unit = {
    schema()
    val file = configure("models/report.sql", "select o.id as order_id, o.amount * 2 as gross from {{ ref('orders') }} o<caret>")
    val result = DbtSqlAnalysis.get(DbtWorkspace.get(getProject), file).get
    assertEquals(Vector("order_id", "gross"), result.columns.map(_.name))
    assertTrue(result.columns.head.direct)
    assertEquals(Vector("id"), result.columns.head.inputs.map(_.name))
    assertFalse(result.columns(1).direct)
    assertEquals(Vector("amount"), result.columns(1).inputs.map(_.name))
    assertTrue(result.columns.forall(_.complete))
  }
  def testUnionRecordsEveryBranchAndUnknownSchemaStaysPartial(): Unit = {
    schema()
    val file = configure("models/report.sql", "select id from {{ ref('orders') }} union all select event_id from {{ source('raw', 'events') }}<caret>")
    val columns = DbtSqlAnalysis.get(DbtWorkspace.get(getProject), file).get.columns
    assertEquals(Set("id", "event_id"), columns.head.inputs.map(_.name).toSet)
    assertFalse(columns.head.direct)
    edit(file, "select * from unknown_relation")
    val result = DbtSqlAnalysis.get(DbtWorkspace.get(getProject), file).get
    assertTrue(result.issues.nonEmpty); assertTrue(result.wildcards.isEmpty)
  }
  def testExpandQualifiedWildcardPreservesSqlAndDoesNotExpandCount(): Unit = {
    schema()
    configure("models/report.sql", "select o.<caret>* from {{ ref('orders') }} o")
    try myFixture.launchAction(myFixture.findSingleIntention("Expand dbt SQL columns"))
    catch { case e: AssertionError => throw new AssertionError("After intention: PSI=" + myFixture.getFile.getText + " DOCUMENT=" + myFixture.getEditor.getDocument.getText, e) }
    assertEquals("select o.id, o.amount from {{ ref('orders') }} o", myFixture.getFile.getText)
    configure("models/count.sql", "select count(<caret>*) from {{ ref('orders') }}")
    assertFalse(myFixture.getAvailableIntentions.asScala.exists(_.getText == "Expand dbt SQL columns"))
  }
  def testMacroSignatureIncludesDefaultAndDocumentedType(): Unit = {
    project()
    add("macros/money.sql", "{% macro money(amount, scale=2) %}{{ amount }}{% endmacro %}")
    add("macros/schema.yml", "macros: [{name: money, arguments: [{name: amount, type: decimal}]}]")
    val w = DbtWorkspace.get(getProject)
    val m = w.symbols.find(s => s.kind == "macro" && !s.isProperty).get
    assertEquals(Vector("amount: decimal", "scale=2"), DbtCompletion.parameters(w, m).map(_._2))
  }
  def testCompletionKeepsModelVersionsSeparateAndHandlesUnfinishedQuote(): Unit = {
    schema()
    add("models/versioned_v1.sql", "select 1 as id")
    add("models/versioned_v2.sql", "select 2 as id")
    add("models/versions.yml", "models: [{name: versioned, latest_version: 2, versions: [{v: 1}, {v: 2}]}]")
    val file = configure("models/report.sql", "select * from {{ ref('ord<caret>")
    assertTrue(DbtCompletion.suggestions(file, myFixture.getCaretOffset).exists(_.text == "orders"))
    val version = configure("models/version_report.sql", "select * from {{ ref('versioned', v='<caret>') }}")
    assertEquals(Set("1", "2"), DbtCompletion.suggestions(version, myFixture.getCaretOffset).map(_.text).toSet)
  }
  def testOfflineSchemasSelectVersionAndIgnoreUnrelatedYaml(): Unit = {
    project()
    val properties = add("models/schema.yml", "models: [{name: orders, columns: wrong}]\n")
    val unrelated = add("models/service.yml", "services: {http: {port: 8080}}\n")
    val providers = new DbtYamlSchemaProvider().getProviders(getProject).asScala
    val selected = providers.filter(_.isAvailable(properties.getVirtualFile))
    assertEquals(Vector("dbt properties (current)"), selected.map(_.getName).toVector)
    assertNotNull(selected.head.getSchemaFile)
    assertTrue(providers.forall(!_.isAvailable(unrelated.getVirtualFile)))
    add(".dbt-navigation.yml", "dbt-version: '1.7'\n")
    assertEquals(Vector("dbt properties (queries)"), providers.filter(_.isAvailable(properties.getVirtualFile)).map(_.getName).toVector)
  }
  def testNativeStaticInspectionUsesExactMacroAndColumnRanges(): Unit = {
    schema()
    add("macros/money.sql", "{% macro money(amount, scale=2) %}{{ amount }}{% endmacro %}")
    val file = configure("models/report.sql", "select o.missing<caret> from {{ ref('orders') }} o where {{ money(1, unknown_argument=2) }} = 1")
    myFixture.enableInspections(new DbtStaticInspection)
    val problems = myFixture.doHighlighting().asScala.filter(h => Option(h.getDescription).exists(d => d.startsWith("Unknown column") || d.startsWith("Unknown macro")))
    assertEquals(Set("missing", "unknown_argument"), problems.map(h => file.getText.substring(h.getStartOffset, h.getEndOffset)).toSet)
    assertTrue(problems.forall(_.getSeverity == com.intellij.lang.annotation.HighlightSeverity.WARNING))
  }
  def testJoinAndFilterDependenciesAreSeparateFromValueDerivation(): Unit = {
    schema()
    val file = configure("models/report.sql", "select a.id from {{ ref('orders') }} a join {{ ref('orders') }} b on a.id = b.id where b.amount > 0<caret>")
    val analysis = DbtSqlAnalysis.get(DbtWorkspace.get(getProject), file).get
    assertEquals(Vector("id"), analysis.columns.head.inputs.map(_.name))
    assertEquals(Set("join", "filter"), analysis.columns.head.contextInputs.map(_._2).toSet)
  }
  def testCurrentSqlProjectionOverridesStaleYamlColumnNames(): Unit = {
    schema()
    val workspace = DbtWorkspace.get(getProject)
    val orders = workspace.files.find(_.getName == "orders.sql").get
    edit(orders, "select 1 as id")
    val file = configure("models/report.sql", "select o.<caret> from {{ ref('orders') }} o")
    assertEquals(Vector("id"), DbtCompletion.suggestions(file, myFixture.getCaretOffset).map(_.text))
    edit(file, "select o.amount from {{ ref('orders') }} o")
    val analysis = DbtSqlAnalysis.get(DbtWorkspace.get(getProject), file).get
    assertTrue(analysis.problems.exists(_.message.contains("amount")))
    assertFalse(analysis.columns.head.complete)
  }
  def testAggregatesWindowsAndContractNamesUseAuthoredOutputRanges(): Unit = {
    schema()
    val file = configure("models/report.sql", "select sum(o.amount) as total, row_number() over (partition by o.id order by o.amount) as position from {{ ref('orders') }} o<caret>")
    add("models/report.yml", "models: [{name: report, config: {contract: {enforced: true}}, columns: [{name: total, data_type: decimal}]}]")
    val analysis = DbtSqlAnalysis.get(DbtWorkspace.get(getProject), file).get
    assertEquals(Vector("total", "position"), analysis.columns.map(_.name))
    assertEquals(Vector("amount"), analysis.columns.head.inputs.map(_.name))
    assertEquals(Set("id", "amount"), analysis.columns(1).inputs.map(_.name).toSet)
    assertTrue(analysis.columns.forall(c => c.complete && !c.direct))
    myFixture.enableInspections(new DbtStaticInspection)
    val problem = myFixture.doHighlighting().asScala.find(h => Option(h.getDescription).exists(_.contains("has no column declaration"))).get
    assertEquals("position", file.getText.substring(problem.getStartOffset, problem.getEndOffset))
  }
  def testDynamicProjectionNamesDoNotAppearAsInventedSqlColumns(): Unit = {
    schema()
    add("models/dynamic.sql", "select {{ var('column_name') }} from {{ source('raw', 'events') }}")
    val file = configure("models/report.sql", "select d.<caret> from {{ ref('dynamic') }} d")
    assertTrue(DbtCompletion.suggestions(file, myFixture.getCaretOffset).isEmpty)
  }
}
