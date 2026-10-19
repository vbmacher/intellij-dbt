package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.lineage.DbtColumnLineage
import com.github.vbmacher.intellij.dbt.navigation.{DbtNavigation, DbtSqlAnalysis}
import com.github.vbmacher.intellij.dbt.project._
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.progress.{EmptyProgressIndicator, ProcessCanceledException, ProgressManager}
import com.intellij.psi.PsiDocumentManager
import org.junit.Assert._
import scala.jdk.CollectionConverters._

/** User-visible regressions found while checking the authoring ticket acceptance criteria. */
final class DbtAuthoringAcceptanceAuditTest extends DbtCodeInsightTestCase {
  override protected def tearDown(): Unit = try DbtProjectSettings.get(getProject).loadState(new DbtSettingsState) finally super.tearDown()
  private def w = DbtWorkspace.get(getProject)
  private def model(name: String) = w.symbols.find(s => s.kind == "model" && s.isCode && s.name == name).get
  private def undo(): Unit = {
    val manager = UndoManager.getInstance(getProject)
    val editor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(getProject).getSelectedEditor
    val target = if (manager.isUndoAvailable(editor)) editor else null
    assertTrue("One native Undo must restore the complete action", manager.isUndoAvailable(target))
    val previous = com.intellij.openapi.ui.TestDialogManager.setTestDialog(com.intellij.openapi.ui.TestDialog.OK)
    try manager.undo(target) finally com.intellij.openapi.ui.TestDialogManager.setTestDialog(previous)
    PsiDocumentManager.getInstance(getProject).commitAllDocuments()
  }

  def testCreationCanReviewNewNameAndPropertiesAndUndoAllThreeChanges(): Unit = {
    project("model-paths: [transforms]\n")
    val before = "select * from {{ ref('missing') }}"
    val caller = configure("transforms/report.sql", "select * from {{ ref('miss<caret>ing') }}")
    val proposal = DbtCreateResourceFix.proposal(caller, myFixture.getCaretOffset).get
    val root = proposal.root.directory
    assertTrue(DbtCreateResourceFix.create(proposal, "transforms/new_orders.sql", includeProperties = true, logicalName = Some("new_orders")).isRight)
    assertEquals("select * from {{ ref('new_orders') }}", caller.getText)
    assertEquals("select\n    1 as id\n", root.findFileByRelativePath("transforms/new_orders.sql").contentsToByteArray().map(_.toChar).mkString)
    val properties = w.files.find(_.getName == "new_orders.yml").get
    assertEquals("version: 2\nmodels:\n  - name: new_orders\n    columns:\n      - name: id\n", properties.getText)
    assertEquals("new_orders", DbtNavigation.inFile(caller).flatMap(_.candidates).collectFirst {
      case element: DbtSymbolElement if element.symbol.kind == "model" => element.symbol.name
    }.get)
    undo()
    assertEquals(before, caller.getText)
    assertNull(root.findFileByRelativePath("transforms/new_orders.sql"))
    assertNull(root.findFileByRelativePath("transforms/new_orders.yml"))
  }

  def testCreationRejectsIgnoredGeneratedForeignAndMismatchedDestinationsWithoutWrites(): Unit = {
    project("model-paths: [models]\ntarget-path: models/generated\n")
    add(".dbtignore", "models/private/\n")
    add("models/foreign/dbt_project.yml", "name: foreign\n")
    val caller = configure("models/report.sql", "select * from {{ ref('miss<caret>ing') }}")
    val plan = DbtCreateResourceFix.proposal(caller, myFixture.getCaretOffset).get
    Vector("models/private/missing.sql", "models/generated/missing.sql", "models/foreign/models/missing.sql",
      "../missing.sql", "models/different.sql", "models/missing.yml").foreach { path =>
      assertTrue(path, DbtCreateResourceFix.create(plan, path).isLeft)
      assertNull(path, plan.root.directory.findFileByRelativePath(path))
    }
    assertEquals(plan.origin.get._3, caller.getText)
  }

  def testCreationRejectsNewDuplicateNameAndExistingPropertiesAndChangedCall(): Unit = {
    project()
    val caller = configure("models/report.sql", "select * from {{ ref('miss<caret>ing') }}")
    val plan = DbtCreateResourceFix.proposal(caller, myFixture.getCaretOffset).get
    val existing = add("models/missing.yml", "# Keep this file\nmodels: []\n")
    assertTrue(DbtCreateResourceFix.create(plan, plan.path, includeProperties = true).isLeft)
    assertEquals("# Keep this file\nmodels: []\n", existing.getText)
    add("models/other.sql", "select 2 as id")
    assertTrue(DbtCreateResourceFix.create(plan, "models/nested/other.sql", logicalName = Some("other")).isLeft)
    edit(caller, "select * from {{ ref('different') }}")
    assertTrue(DbtCreateResourceFix.create(plan, plan.path).isLeft)
    assertNull(plan.root.directory.findFileByRelativePath(plan.path))
  }

  def testNewSourceCreationResolvesAndExistingSourceEditRejectsStaleYaml(): Unit = {
    project()
    val caller = configure("models/report.sql", "select * from {{ source('c<caret>rm', 'customers') }}")
    val plan = DbtCreateResourceFix.proposal(caller, myFixture.getCaretOffset).get
    assertTrue(plan.file.isEmpty)
    assertTrue(DbtCreateResourceFix.create(plan, plan.path).isRight)
    assertTrue(w.symbols.exists(s => s.kind == "source_table" && s.owner == "crm" && s.name == "customers"))
    val yaml = w.files.find(_.getName == "crm_sources.yml").get
    val missing = configure("models/another.sql", "select * from {{ source('crm', 'ord<caret>ers') }}")
    val next = DbtCreateResourceFix.proposal(missing, myFixture.getCaretOffset).get
    edit(yaml, yaml.getText + "# concurrent change\n")
    val current = yaml.getText
    assertTrue(DbtCreateResourceFix.create(next, next.path).isLeft)
    assertEquals(current, yaml.getText)
  }

  def testPackagesVersionsAndDynamicCallsNeverOfferWritableCreation(): Unit = {
    project()
    Vector("ref('finance', 'missing')", "ref('missing', v=2)", "ref(var('name'))", "source(var('name'), 'missing')")
      .zipWithIndex.foreach { case (call, index) =>
        val file = configure(s"models/negative_$index.sql", "select * from {{ " + call + "<caret> }}")
        assertTrue(call, DbtCreateResourceFix.proposal(file, file.getText.indexOf("missing") match {
          case -1 => myFixture.getCaretOffset
          case offset => offset
        }).isEmpty)
      }
  }

  def testWildcardExpansionHasExactJoinOrderingAndSingleUndo(): Unit = {
    project()
    add("models/left_orders.sql", "select 1 as id, 2 as amount")
    add("models/right_orders.sql", "select 3 as id, 4 as total")
    val before = "select * from {{ ref('left_orders') }} l join {{ ref('right_orders') }} r on l.id = r.id"
    configure("models/report.sql", before.replace("select *", "select <caret>*"))
    myFixture.launchAction(myFixture.findSingleIntention("Expand dbt SQL columns"))
    assertEquals("select l.id, l.amount, r.id, r.total from {{ ref('left_orders') }} l join {{ ref('right_orders') }} r on l.id = r.id", myFixture.getFile.getText)
    undo(); assertEquals(before, myFixture.getFile.getText)
  }

  def testRegisteredWildcardIntentionLoadsNativeSettingsDescriptionAndExamples(): Unit = {
    val entries = com.intellij.codeInsight.intention.impl.config.IntentionManagerSettings.getInstance.getMetaData.asScala
      .filter(_.getAction.getText == "Expand dbt SQL columns")
    assertEquals("One registered native intention", 1, entries.size)
    val metadata = entries.head
    assertTrue("Settings must load the intention description", metadata.getDescription.getText.trim.nonEmpty)
    val before = metadata.getExampleUsagesBefore.toVector.map(_.getText)
    val after = metadata.getExampleUsagesAfter.toVector.map(_.getText)
    assertTrue("Settings must show a wildcard example", before.exists(_.contains("*")))
    assertTrue("Settings must show the changed projection", after.nonEmpty && after != before)
  }

  def testUnknownSchemasWildcardModifiersAndCommentsNeverExpand(): Unit = {
    project()
    add("models/orders.sql", "select 1 as id, 2 as amount")
    Vector("select * except (id) from {{ ref('orders') }}", "select * exclude id from {{ ref('orders') }}",
      "select * replace (3 as id) from {{ ref('orders') }}", "select * from unknown_relation",
      "select count(*) from {{ ref('orders') }}", "select '*' as value", "select 1 /* * */")
      .zipWithIndex.foreach { case (sql, index) =>
        configure(s"models/negative_star_$index.sql", sql.replaceFirst("\\*", "<caret>*"))
        val before = myFixture.getFile.getText
        if (index >= 4) assertFalse(sql, new DbtExpandColumnsIntention().isAvailable(getProject, myFixture.getEditor, myFixture.getFile))
        assertTrue(sql, DbtSqlAnalysis.get(w, myFixture.getFile).forall(_.wildcards.isEmpty))
        assertEquals(before, myFixture.getFile.getText)
      }
  }

  def testMissingPropertiesScaffoldAppliesAndOneUndoRemovesIt(): Unit = {
    project()
    val source = configure("models/orders.sql", "select 1 as id<caret>")
    var planned: Either[String, DbtPropertyEdit] = Left("not planned")
    WriteAction.run(() => planned = DbtPropertiesEditor.plan(w, model("orders"), "Orders", Vector(DbtColumnData("id", "int", "Identifier")), Vector.empty, Vector.empty))
    val plan = planned.toOption.get
    assertTrue(plan.file.isEmpty)
    assertNull(source.getVirtualFile.getParent.findChild("orders.yml"))
    plan.apply(getProject)
    assertEquals("Orders", DbtResourceData.get(w, model("orders")).description)
    assertEquals(plan.after + "\nColumns: " + w.columns(model("orders")).map(c => (c.name, c.attributes)) +
      "\nMappings: " + w.properties(model("orders")).map(_.mapping.map(_.getText)),
      "Identifier", DbtResourceData.get(w, model("orders")).columns.find(_.name == "id").get.description)
    undo(); assertNull(source.getVirtualFile.getParent.findChild("orders.yml"))
  }

  def testDescriptionInheritanceKeepsExactOriginAndRejectsUnionAggregateAndExistingDoc(): Unit = {
    project()
    val upstream = add("models/orders.sql", "select 1 as id, 2 as amount")
    add("models/schema.yml", "models:\n- name: orders\n  columns:\n  - {name: id, description: Identifier}\n  - {name: amount, description: Amount}\n- name: report\n  columns:\n  - {name: kept, description: \"{{ doc('keep_me') }}\"}\n")
    add("models/report.sql", "select id as inherited, id as kept, sum(amount) as calculated from {{ ref('orders') }} group by id")
    val inherited = DbtColumnLineage.inherited(w, model("report"))
    val editable = DbtPropertiesEditor.editableColumns(w, model("report"))
    assertEquals("Opening Edit must not implicitly accept an inherited description", "", editable.find(_.name == "inherited").get.description)
    assertEquals("{{ doc('keep_me') }}", editable.find(_.name == "kept").get.description)
    assertEquals(Vector("inherited"), inherited.map(_._1))
    assertEquals("Identifier", inherited.head._2)
    assertEquals("id", inherited.head._3.name)
    assertEquals("orders", inherited.head._3.owner)
    val review = DbtPropertiesEditor.origins(w, model("report"), "inherited")
    assertTrue(review._1, review._1.contains("Passthrough") && review._1.contains("analytics.orders.id"))
    assertEquals(inherited.head._3.id, review._2.head.id)
    assertTrue(DbtPropertiesEditor.origins(w, model("report"), "calculated")._1.startsWith("Calculated"))
    add("models/unioned.sql", "select id from {{ ref('orders') }} union all select id from {{ ref('orders') }}")
    assertTrue(DbtColumnLineage.inherited(w, model("unioned")).isEmpty)
    edit(upstream, "select 1 as changed")
    assertTrue("Unsaved source edits invalidate prior origins", DbtColumnLineage.inherited(w, model("report")).isEmpty)
  }

  def testNativeSchemaCompletionAlsoCoversProjectPackagesAndSelectors(): Unit = {
    val settings = com.intellij.codeInsight.CodeInsightSettings.getInstance()
    val previous = settings.AUTOCOMPLETE_ON_CODE_COMPLETION
    settings.AUTOCOMPLETE_ON_CODE_COMPLETION = false
    try {
    val config = project()
    open(config, config.getText.length)
    def keys: Set[String] = Option(myFixture.completeBasic()).toVector.flatMap(_.toVector).map(_.getLookupString).toSet
    assertTrue(keys.contains("model-paths"))
    com.intellij.codeInsight.lookup.LookupManager.hideActiveLookup(getProject)
    configure("packages.yml", "<caret>\n")
    assertTrue(keys.contains("packages"))
    com.intellij.codeInsight.lookup.LookupManager.hideActiveLookup(getProject)
    configure("selectors.yml", "<caret>\n")
    assertTrue(keys.contains("selectors"))
    } finally settings.AUTOCOMPLETE_ON_CODE_COMPLETION = previous
  }

  def testCompletionAndColumnAnalysisHonorCancellation(): Unit = {
    project()
    (1 to 100).foreach(i => add(s"models/orders_$i.sql", "select 1 as id"))
    val file = configure("models/report.sql", "select * from {{ ref('ord<caret>') }}")
    val offset = myFixture.getCaretOffset
    def cancels(action: () => Unit): Unit = {
      val canceled = new java.util.concurrent.atomic.AtomicBoolean
      com.intellij.openapi.application.ApplicationManager.getApplication.executeOnPooledThread(new Runnable {
        override def run(): Unit = {
          val indicator = new EmptyProgressIndicator
          try ProgressManager.getInstance.runProcess(new Runnable {
            override def run(): Unit = {
              indicator.cancel()
              com.intellij.openapi.application.ReadAction.run(() => action())
            }
          }, indicator) catch { case _: ProcessCanceledException => canceled.set(true) }
        }
      }).get(10, java.util.concurrent.TimeUnit.SECONDS)
      assertTrue("Background work must observe cancellation", canceled.get())
    }
    cancels(() => { DbtCompletion.suggestions(file, offset); () })
    cancels(() => { DbtSqlAnalysis.get(w, file); () })
    assertTrue("Cancellation must not poison future completion", DbtCompletion.suggestions(file, myFixture.getCaretOffset).exists(_.text == "orders_1"))
  }

  def testUnrelatedKwargsAndCommentsDoNotHideStrictMacroWarnings(): Unit = {
    project()
    add("macros/helpers.sql", "{# kwargs in another macro must not disable checks here #}\n" +
      "{% macro strict(amount, scale=2) %}{{ amount }}{% endmacro %}\n" +
      "{% macro zero() %}1{% endmacro %}\n" +
      "{% macro permissive(amount) %}{{ kwargs.get('extra', 0) }}{% endmacro %}\n")
    val file = configure("models/report.sql", "select {{ strict(1, typo=2) }} as strict_result, {{ zero(typo2=2) }} as zero_result, {{ permissive(1, extra=2) }} as flexible_result<caret>")
    myFixture.enableInspections(new DbtStaticInspection)
    val diagnostics = myFixture.doHighlighting().asScala.filter(h => Option(h.getDescription).exists(_.startsWith("Unknown macro argument")))
    assertEquals(Vector("typo", "typo2"), diagnostics.map(h => file.getText.substring(h.getStartOffset, h.getEndOffset)).toVector)
    assertTrue(diagnostics.forall(_.getSeverity == com.intellij.lang.annotation.HighlightSeverity.WARNING))
  }
}
