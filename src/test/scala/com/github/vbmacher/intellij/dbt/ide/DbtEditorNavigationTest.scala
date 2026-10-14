package com.github.vbmacher.intellij.dbt.ide

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.testFramework.PlatformTestUtil
import org.junit.Assert._

/** User-guide navigation contracts exercised through IntelliJ's Go to Declaration helpers. */
final class DbtEditorNavigationTest extends DbtCodeInsightTestCase {
  def testModelReferenceInPlainSqlSelectsOnlyTheName(): Unit = {
    project()
    val orders = add("models/orders.sql", "select 1 as order_id")
    configure("models/report.sql", "select * from {{ ref('or<caret>ders') }}")

    assertTarget(orders)
    assertEquals("orders", reference.getRangeInElement.substring(reference.getElement.getText))
  }

  def testGoToDeclarationActionOpensTheReferencedModel(): Unit = {
    project()
    val orders = add("models/orders.sql", "select 1 as order_id")
    configure("models/report.sql", "select * from {{ ref('or<caret>ders') }}")

    myFixture.performEditorAction("GotoDeclaration")
    val expected = PsiDocumentManager.getInstance(getProject).getDocument(orders)
    PlatformTestUtil.waitWithEventsDispatching("Go to Declaration should open orders.sql", () =>
      Option(FileEditorManager.getInstance(getProject).getSelectedTextEditor).exists(_.getDocument == expected), 5)
  }

  def testSeedReferenceOpensTheCsvFile(): Unit = {
    project()
    val seed = add("seeds/countries.csv", "code\nUS\n")
    configure("models/report.sql", "select * from {{ ref('cou<caret>ntries') }}")

    assertTarget(seed)
  }

  def testSnapshotReferenceOpensTheNamedBlockInItsContainer(): Unit = {
    project()
    val snapshots = add("snapshots/history.sql",
      "{% snapshot first %}select 1{% endsnapshot %}\n{% snapshot orders_history %}select 2{% endsnapshot %}")
    configure("models/report.sql", "select * from {{ ref('orders_<caret>history') }}")

    assertTarget(snapshots, snapshots.getText.indexOf("orders_history"))
  }

  def testSourceNamespaceOpensItsYamlName(): Unit = {
    project()
    val schema = add("models/sources.yml", "sources:\n- name: raw\n  tables:\n  - name: events\n")
    configure("models/report.sql", "select * from {{ source('r<caret>aw', 'events') }}")

    assertTarget(schema, schema.getText.indexOf("raw"))
  }

  def testSourceTableUsesTheLogicalNameAndCorrectNamespace(): Unit = {
    project()
    val schema = add("models/sources.yml",
      "sources:\n- name: other\n  tables:\n  - name: events\n- name: raw\n  tables:\n  - name: events\n    identifier: warehouse_events\n")
    configure("models/report.sql", "select * from {{ source('raw', 'ev<caret>ents') }}")

    assertTarget(schema, schema.getText.lastIndexOf("name: events") + "name: ".length)
  }

  def testPhysicalSourceIdentifierIsNotALogicalTableName(): Unit = {
    project()
    add("models/sources.yml", "sources:\n- name: raw\n  tables:\n  - name: events\n    identifier: warehouse_events\n")
    configure("models/report.sql", "select * from {{ source('raw', 'warehouse_<caret>events') }}")

    assertNoTarget()
  }

  def testQualifiedReferenceUsesTheInstalledPackageProjectName(): Unit = {
    project("packages-install-path: vendor\n")
    add("models/orders.sql", "select 1")
    add("vendor/finance/dbt_project.yml", "name: accounting\n")
    val packaged = add("vendor/finance/models/orders.sql", "select 2")
    configure("models/report.sql", "select * from {{ ref('accounting', 'or<caret>ders') }}")

    assertTarget(packaged)
  }

  def testUnversionedModelUsesLatestVersion(): Unit = {
    project()
    add("models/orders_old.sql", "select 1")
    val latest = add("models/orders_v2.sql", "select 2")
    add("models/orders.yml", "models:\n- name: orders\n  latest_version: 2\n  versions:\n  - v: 1\n    defined_in: orders_old\n  - v: 2\n")
    configure("models/report.sql", "select * from {{ ref('or<caret>ders') }}")

    assertTarget(latest)
  }

  def testExplicitVersionUsesDefinedInRatherThanLatest(): Unit = {
    project()
    val old = add("models/orders_old.sql", "select 1")
    add("models/orders_v2.sql", "select 2")
    add("models/orders.yml", "models:\n- name: orders\n  latest_version: 2\n  versions:\n  - v: 1\n    defined_in: orders_old\n  - v: 2\n")
    configure("models/report.sql", "select * from {{ ref('or<caret>ders', version=1) }}")

    assertTarget(old)
  }

  def testMacroReferenceOpensItsDeclarationRatherThanTheFirstBlock(): Unit = {
    project()
    val macros = add("macros/math.sql",
      "{% macro triple(value) %}{{ value * 3 }}{% endmacro %}\n{% macro double(value) %}{{ value * 2 }}{% endmacro %}")
    configure("models/report.sql", "select {{ dou<caret>ble(value=2) }}")

    assertTarget(macros, macros.getText.indexOf("double"))
  }

  def testNamedMacroArgumentOpensTheParameter(): Unit = {
    project()
    val macros = add("macros/math.sql", "{% macro double(value) %}{{ value * 2 }}{% endmacro %}")
    configure("models/report.sql", "select {{ double(val<caret>ue=2) }}")

    assertTarget(macros, macros.getText.indexOf("value"))
  }

  def testDocsReferenceInResourceDescriptionOpensTheNamedBlock(): Unit = {
    project()
    add("models/orders.sql", "select 1")
    val docs = add("docs/catalog.md", "{% docs other %}Other{% enddocs %}\n{% docs orders_description %}Orders{% enddocs %}")
    configure("models/schema.yml", "models:\n- name: orders\n  description: \"{{ doc('orders_<caret>description') }}\"\n")

    assertTarget(docs, docs.getText.indexOf("orders_description"))
  }

  def testDocsCallInSqlHasNoDocumentationTarget(): Unit = {
    project()
    add("docs/catalog.md", "{% docs orders_description %}Orders{% enddocs %}")
    configure("models/report.sql", "{{ doc('orders_<caret>description') }}")

    assertNoTarget()
  }

  def testMultilineYamlHookOpensTheReferencedModel(): Unit = {
    project()
    val orders = add("models/orders.sql", "select 1")
    configure("models/report.yml",
      "models:\n- name: report\n  config:\n    pre_hook: |\n      delete from {{ ref('or<caret>ders') }}\n      where id < 0\n")

    assertTarget(orders)
  }

  def testCteReferenceOpensTheDefinitionInTheSameSqlFile(): Unit = {
    project()
    val file = configure("models/report.sql", "with recent as (select 1 as id)\nselect id from re<caret>cent")

    assertTarget(file, file.getText.indexOf("recent"))
  }

  def testQualifiedColumnOpensTheYamlColumnDeclaration(): Unit = {
    project()
    add("models/orders.sql", "select 1 as order_id")
    val schema = add("models/orders.yml", "models:\n- name: orders\n  columns:\n  - name: order_id\n")
    configure("models/report.sql", "select o.order_<caret>id from {{ ref('orders') }} o")

    assertTarget(schema, schema.getText.indexOf("order_id"))
  }

  def testUnitTestFixtureOpensTheCsvRatherThanTheSameNamedModel(): Unit = {
    project()
    add("models/orders.sql", "select 1")
    val fixture = add("tests/fixtures/orders.csv", "id\n1\n")
    configure("models/unit.yml",
      "unit_tests:\n- name: example\n  model: orders\n  given:\n  - input: ref('orders')\n    format: csv\n    fixture: or<caret>ders\n")

    assertTarget(fixture)
  }

  def testDuplicateModelNamesOfferAllDeclarationsWithoutChoosingOne(): Unit = {
    project()
    val first = add("models/first/orders.sql", "select 1")
    val second = add("models/second/orders.sql", "select 2")
    configure("models/report.sql", "select * from {{ ref('or<caret>ders') }}")

    val ref = reference
    assertNull("An ambiguous reference should not choose a model", ref.resolve())
    assertTrue("IntelliJ must be able to offer a chooser", ref.isInstanceOf[PsiPolyVariantReference])
    val targets = ref.asInstanceOf[PsiPolyVariantReference].multiResolve(false).toVector
    assertEquals(2, targets.size)
    assertEquals(Set(first, second), targets.map(_.getElement.getContainingFile).toSet)
    assertEquals(Set(first, second), declarationTargets.map(_.getContainingFile).toSet)
  }

  def testAnUnknownVersionDoesNotFallBackToLatest(): Unit = {
    project()
    add("models/orders_v2.sql", "select 2")
    add("models/orders.yml", "models:\n- name: orders\n  latest_version: 2\n  versions: [{v: 2}]\n")
    configure("models/report.sql", "select * from {{ ref('or<caret>ders', v=runtime_version) }}")

    assertNoTarget()
  }

  def testIgnoredModelCannotBeADeclarationTarget(): Unit = {
    project()
    add("models/experimental/orders.sql", "select 1")
    add(".dbtignore", "models/experimental/\n")
    configure("models/report.sql", "select * from {{ ref('or<caret>ders') }}")

    assertNoTarget()
  }

  def testExplicitlyReincludedModelInsideAnIgnoredDirectoryIsNavigable(): Unit = {
    project()
    val ignore = add(".dbtignore", "models/experimental/\n!models/experimental/keep.sql\n")
    val keep = add("models/experimental/keep.sql", "select 1")
    add("models/experimental/drop.sql", "select 2")
    val caller = configure("models/report.sql", "{{ ref('ke<caret>ep') }} {{ ref('drop') }}")

    assertTarget(keep)
    myFixture.getEditor.getCaretModel.moveToOffset(caller.getText.indexOf("drop") + 2)
    assertNoTarget()

    edit(ignore, "models/experimental/\n")
    myFixture.getEditor.getCaretModel.moveToOffset(caller.getText.indexOf("keep") + 2)
    assertNoTarget()
  }

  def testWildcardReinclusionReachesFilesBelowIgnoredAncestors(): Unit = {
    project()
    add(".dbtignore", "models/experimental/\n!models/experimental/**/keep.sql\n")
    val keep = add("models/experimental/nested/keep.sql", "select 1")
    configure("models/report.sql", "{{ ref('ke<caret>ep') }}")

    assertTarget(keep)
  }

  def testIgnoreNegationsCannotReincludeGeneratedOutput(): Unit = {
    project("model-paths: [models, generated]\ntarget-path: generated\n")
    add(".dbtignore", "models/experimental/\n!generated/**\n")
    add("generated/orders.sql", "select 1")
    configure("models/report.sql", "{{ ref('or<caret>ders') }}")

    assertNoTarget()
  }

  def testInformationSchemaNamePredicateRespectsEnabledConfiguration(): Unit = {
    project("models:\n  analytics:\n    +enabled: false\n")
    add(".dbt-navigation.yml", "engine: v2\n")
    val orders = add("models/orders.sql", "select 1")
    configure("checks/exists.sql", "select name from {{ info_schema('models') }} where name = 'or<caret>ders'")

    assertNoTarget()
    edit(orders, "{{ config(enabled=true) }} select 1")
    assertTarget(orders)
    edit(orders, "{{ config(enabled=false) }} select 1")
    assertNoTarget()
  }

  def testAddingAMissingModelMakesTheExistingReferenceNavigable(): Unit = {
    project()
    configure("models/report.sql", "select * from {{ ref('or<caret>ders') }}")
    assertNoTarget()

    val orders = add("models/orders.sql", "select 1")
    assertTarget(orders)
  }

  def testDeletingAModelRemovesTheExistingNavigationTarget(): Unit = {
    project()
    val orders = add("models/orders.sql", "select 1")
    configure("models/report.sql", "select * from {{ ref('or<caret>ders') }}")
    assertTarget(orders)

    WriteCommandAction.runWriteCommandAction(getProject, new Runnable {
      override def run(): Unit = orders.getVirtualFile.delete(this)
    })
    assertNoTarget()
  }

  def testUnsavedCallerEditNavigatesToTheNewModel(): Unit = {
    project()
    val orders = add("models/orders.sql", "select 1")
    val customers = add("models/customers.sql", "select 2")
    val caller = configure("models/report.sql", "select * from {{ ref('or<caret>ders') }}")
    assertTarget(orders)

    edit(caller, "select * from {{ ref('customers') }}")
    myFixture.getEditor.getCaretModel.moveToOffset(caller.getText.indexOf("customers") + 2)
    assertTarget(customers)
  }

  def testNestedProjectDoesNotResolveAnOuterProjectModel(): Unit = {
    project()
    add("models/orders.sql", "select 1")
    add("nested/dbt_project.yml", "name: nested\n")
    configure("nested/models/report.sql", "select * from {{ ref('or<caret>ders') }}")

    assertNoTarget()
  }
}
