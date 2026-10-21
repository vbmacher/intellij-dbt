package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.navigation.DbtSqlAnalysis
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiElement
import com.intellij.testFramework.utils.parameterInfo.{MockCreateParameterInfoContext, MockParameterInfoUIContext, MockUpdateParameterInfoContext}
import org.junit.Assert._

import scala.jdk.CollectionConverters._

/** SQL authoring assistance: templates, completion, column analysis, macro Parameter Info and static inspection suppression. */
final class DbtAuthoringTest extends DbtTestCase {
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
    val properties = workspace.files.find(_.getName == "schema.yml").get
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
    val result = DbtSqlAnalysis.get(workspace, file).get
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
    val columns = DbtSqlAnalysis.get(workspace, file).get.columns
    assertEquals(Set("id", "event_id"), columns.head.inputs.map(_.name).toSet)
    assertFalse(columns.head.direct)
    edit(file, "select * from unknown_relation")
    val result = DbtSqlAnalysis.get(workspace, file).get
    assertTrue(result.issues.nonEmpty);
    assertTrue(result.wildcards.isEmpty)
  }

  def testExpandQualifiedWildcardPreservesSqlAndDoesNotExpandCount(): Unit = {
    schema()
    configure("models/report.sql", "select o.<caret>* from {{ ref('orders') }} o")
    try myFixture.launchAction(myFixture.findSingleIntention("Expand dbt SQL columns"))
    catch {
      case e: AssertionError => throw new AssertionError("After intention: PSI=" + myFixture.getFile.getText + " DOCUMENT=" + myFixture.getEditor.getDocument.getText, e)
    }
    assertEquals("select o.id, o.amount from {{ ref('orders') }} o", myFixture.getFile.getText)
    configure("models/count.sql", "select count(<caret>*) from {{ ref('orders') }}")
    assertFalse(myFixture.getAvailableIntentions.asScala.exists(_.getText == "Expand dbt SQL columns"))
  }

  def testMacroSignatureIncludesDefaultAndDocumentedType(): Unit = {
    project()
    add("macros/money.sql", "{% macro money(amount, scale=2) %}{{ amount }}{% endmacro %}")
    add("macros/schema.yml", "macros: [{name: money, arguments: [{name: amount, type: decimal}]}]")
    val w = workspace
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
    configureSettings(_.dbtVersion = "1.7")
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
    val analysis = DbtSqlAnalysis.get(workspace, file).get
    assertEquals(Vector("id"), analysis.columns.head.inputs.map(_.name))
    assertEquals(Set("join", "filter"), analysis.columns.head.contextInputs.map(_._2).toSet)
  }

  def testCurrentSqlProjectionOverridesStaleYamlColumnNames(): Unit = {
    schema()
    val orders = workspace.files.find(_.getName == "orders.sql").get
    edit(orders, "select 1 as id")
    val file = configure("models/report.sql", "select o.<caret> from {{ ref('orders') }} o")
    assertEquals(Vector("id"), DbtCompletion.suggestions(file, myFixture.getCaretOffset).map(_.text))
    edit(file, "select o.amount from {{ ref('orders') }} o")
    val analysis = DbtSqlAnalysis.get(workspace, file).get
    assertTrue(analysis.problems.exists(_.message.contains("amount")))
    assertFalse(analysis.columns.head.complete)
  }

  def testAggregatesWindowsAndContractNamesUseAuthoredOutputRanges(): Unit = {
    schema()
    val file = configure("models/report.sql", "select sum(o.amount) as total, row_number() over (partition by o.id order by o.amount) as position from {{ ref('orders') }} o<caret>")
    add("models/report.yml", "models: [{name: report, config: {contract: {enforced: true}}, columns: [{name: total, data_type: decimal}]}]")
    val analysis = DbtSqlAnalysis.get(workspace, file).get
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

  private def macroFixture(): Unit = {
    project()
    add("macros/money.sql", "{% macro money(amount, scale=2, currency='USD') %}{{ amount }}{% endmacro %}")
    add("macros/schema.yml", "macros: [{name: money, arguments: [{name: amount, type: decimal}, {name: scale, type: integer}]}]\n")
  }

  def testNativeParameterInfoActionDispatchCreatesControllerForDbtSql(): Unit = {
    macroFixture()
    val file = configure("models/report.sql", "select {{ money(12, <caret>2) }} as amount")
    val editor = myFixture.getEditor
    val language = com.intellij.psi.util.PsiUtilCore.getLanguageAtOffset(file, myFixture.getCaretOffset)
    val handlers = com.intellij.codeInsight.hint.ShowParameterInfoHandler.getHandlers(getProject,
      language, file.getViewProvider.getBaseLanguage)
    assertTrue("Registered handler for " + language + " / " + file.getFileType.getName,
      handlers.exists(_.isInstanceOf[DbtParameterInfoHandler]))
    myFixture.performEditorAction("ParameterInfo")
    com.intellij.openapi.application.impl.NonBlockingReadActionImpl.waitForAsyncTaskCompletion()
    com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
    val controller = com.intellij.codeInsight.hint.ParameterInfoControllerBase.findControllerAtOffset(editor,
      file.getText.indexOf("money(") + "money".length)
    assertNotNull("The native Parameter Info action must create a hint controller for " + language + " / " + file.getFileType.getName, controller)
    try {
      val signature = controller.getObjects.head.asInstanceOf[DbtMacroSignature]
      assertEquals("money", signature.name)
      assertEquals(Vector("amount: decimal", "scale=2: integer", "currency='USD'"), signature.parameters.map(_._2))
    } finally com.intellij.openapi.util.Disposer.dispose(controller)
  }

  def testNativeNamedArgumentHintHasExactSignatureAnchorAndHighlightRange(): Unit = {
    macroFixture()
    val file = configure("models/report.sql", "{{ money(42, currency='EUR', scale=<caret>3) }}")
    val handler = new DbtParameterInfoHandler
    val create = new DbtParameterInfoCapture(myFixture.getEditor, file)
    val owner = handler.findElementForParameterInfo(create)
    assertSame(file, owner)
    assertEquals(1, create.getItemsToShow.length)
    handler.showParameterInfo(owner, create)
    assertSame(file, create.hinted);
    assertEquals(file.getText.indexOf("money(") + "money".length, create.anchor)
    assertEquals('(', file.getText.charAt(create.anchor))
    val signature = create.getItemsToShow.head.asInstanceOf[DbtMacroSignature]
    val update = new MockUpdateParameterInfoContext(myFixture.getEditor, file, create.getItemsToShow)
    handler.updateParameterInfo(handler.findElementForUpdatingParameterInfo(update), update)
    assertEquals(1, update.getCurrentParameter)
    val ui = new MockParameterInfoUIContext[PsiElement](file)
    ui.setCurrentParameterIndex(update.getCurrentParameter)
    handler.updateUI(signature, ui)
    val expected = "money(amount: decimal, scale=2: integer, currency='USD')"
    assertEquals(expected, ui.getText)
    assertEquals(expected.indexOf("scale"), ui.getHighlightStart)
    assertEquals(expected.indexOf("scale") + "scale=2: integer".length, ui.getHighlightEnd)
    assertEquals("scale=2: integer", ui.getText.substring(ui.getHighlightStart, ui.getHighlightEnd))
  }

  def testNestedCommaDoesNotAdvanceThePositionalParameterAndLeavingCallClosesHint(): Unit = {
    macroFixture()
    val file = configure("models/report.sql", "{{ money(coalesce(1, 2), <caret>4) }}")
    val handler = new DbtParameterInfoHandler
    val create = new MockCreateParameterInfoContext(myFixture.getEditor, file)
    assertNotNull(handler.findElementForParameterInfo(create))
    var removed = false
    val update = new MockUpdateParameterInfoContext(myFixture.getEditor, file, create.getItemsToShow) {
      override def removeHint(): Unit = {
        removed = true
      }
    }
    handler.updateParameterInfo(handler.findElementForUpdatingParameterInfo(update), update)
    assertEquals(1, update.getCurrentParameter)
    myFixture.getEditor.getCaretModel.moveToOffset(file.getTextLength)
    val owner = handler.findElementForUpdatingParameterInfo(update)
    assertNull(owner)
    handler.updateParameterInfo(owner, update)
    assertTrue(removed)
  }

  def testAmbiguousMacroDefinitionsNeverShowAnInventedUniqueSignature(): Unit = {
    project()
    add("macros/one.sql", "{% macro collide(a) %}{{ a }}{% endmacro %}")
    add("macros/two.sql", "{% macro collide(a, b=2) %}{{ a }}{% endmacro %}")
    val file = configure("models/report.sql", "{{ collide(<caret>1) }}")
    val create = new MockCreateParameterInfoContext(myFixture.getEditor, file)
    assertNull(new DbtParameterInfoHandler().findElementForParameterInfo(create))
    assertNull(create.getItemsToShow)
  }

  private def suppressionFixture(): Unit = {
    project()
    add("models/orders.sql", "select 1 as id")
    add("macros/money.sql", "{% macro money(amount, scale=2) %}{{ amount }}{% endmacro %}")
    myFixture.enableInspections(new DbtStaticInspection)
  }

  private def diagnostics: Vector[HighlightInfo] = myFixture.doHighlighting().asScala
    .filter(h => Option(h.getDescription).exists(d => d.startsWith("Unknown macro argument") || d.startsWith("Unknown column"))).toVector

  private def nativeSuppression(name: String): IntentionAction = {
    val warning = diagnostics.find(h => h.startOffset <= myFixture.getCaretOffset && h.endOffset >= myFixture.getCaretOffset).get
    val element = myFixture.getFile.findElementAt(myFixture.getCaretOffset)
    val action = warning.findRegisteredQuickFix[IntentionAction]((descriptor, _) =>
      descriptor.getOptions(element, myFixture.getEditor).asScala.find(_.getText == name).orNull)
    assertNotNull("Native inspection options must expose " + name, action)
    action
  }

  def testNativeStatementSuppressionRemovesColumnAndMacroWarningsAndUndoRestoresThem(): Unit = {
    suppressionFixture()
    val file = configure("models/report.sql", "select o.miss<caret>ing from {{ ref('orders') }} o where {{ money(1, typo=2) }} = 1")
    val before = file.getText
    assertEquals(2, diagnostics.size)
    assertTrue(diagnostics.forall(_.getSeverity == HighlightSeverity.WARNING))
    myFixture.launchAction(nativeSuppression("Suppress for statement"))
    assertEquals("-- noinspection DbtStaticAnalysis\n" + before, file.getText)
    assertTrue(diagnostics.isEmpty)
    undo(orProject = true)
    assertEquals(before, file.getText)
    assertEquals(2, diagnostics.size)
  }

  def testStatementDirectiveStopsAtSqlSemicolonAndIgnoresQuotedSemicolons(): Unit = {
    suppressionFixture()
    configure("models/statements.sql", "--noinspection OtherInspection, DbtStaticAnalysis\n" +
      "select ';' as value, {{ money(1, ignored=2) }} as amount;\n" +
      "select {{ money(1, visible<caret>=2) }} as amount")
    val warnings = diagnostics
    assertEquals(1, warnings.size)
    assertEquals("visible", myFixture.getFile.getText.substring(warnings.head.startOffset, warnings.head.endOffset))
  }

  def testNativeFileSuppressionCoversAllStatementsAndOneUndoRestoresSource(): Unit = {
    suppressionFixture()
    val file = configure("models/statements.sql", "select {{ money(1, first=2) }};\nselect {{ money(1, sec<caret>ond=2) }}")
    val before = file.getText
    assertEquals(2, diagnostics.size)
    myFixture.launchAction(nativeSuppression("Suppress for file"))
    assertEquals("--file: noinspection DbtStaticAnalysis\n" + before, file.getText)
    assertTrue(diagnostics.isEmpty)
    undo(orProject = true)
    assertEquals(before, file.getText)
    assertEquals(2, diagnostics.size)
  }

  def testSuppressionTextInsideSqlStringsAndUnrelatedIdsDoNotHideProblems(): Unit = {
    suppressionFixture()
    configure("models/literals.sql", "--noinspection DifferentInspection\n" +
      "select '--file: noinspection DbtStaticAnalysis; --noinspection ALL' as label, {{ money(1, ty<caret>po=2) }} as amount")
    assertEquals(1, diagnostics.size)
    assertTrue(diagnostics.head.getDescription.contains("typo"))
  }

  def testFileDirectiveInsideLaterStatementDoesNotSuppressEntireFile(): Unit = {
    suppressionFixture()
    configure("models/later.sql", "select {{ money(1, first=2) }};\n--file: noinspection DbtStaticAnalysis\nselect {{ money(1, sec<caret>ond=2) }}")
    assertEquals(2, diagnostics.size)
  }

  def testSuppressionActionsAreNotOfferedOutsideDbtSqlResources(): Unit = {
    suppressionFixture()
    val file = configure("notes.sql", "select <caret>1")
    assertTrue(new DbtStaticInspection().getSuppressActions(file).isEmpty)
  }

  def testNativeSeveritySettingRemainsEffectiveAndCanStillBeSuppressed(): Unit = {
    suppressionFixture()
    configure("models/severity.sql", "select {{ money(1, ty<caret>po=2) }}")
    val initial = diagnostics.head
    val key = com.intellij.codeInsight.daemon.HighlightDisplayKey.find(initial.getInspectionToolId)
    assertNotNull(key)
    val profile = com.intellij.profile.codeInspection.InspectionProjectProfileManager.getInstance(getProject).getCurrentProfile
    val previous = profile.getErrorLevel(key, myFixture.getFile)
    try {
      profile.setErrorLevel(key, com.intellij.codeHighlighting.HighlightDisplayLevel.ERROR, getProject)
      com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(getProject).restart(myFixture.getFile, "dbt inspection severity changed")
      assertEquals(HighlightSeverity.ERROR, diagnostics.head.getSeverity)
      myFixture.launchAction(nativeSuppression("Suppress for statement"))
      assertTrue(diagnostics.isEmpty)
    } finally profile.setErrorLevel(key, previous, getProject)
  }
}
