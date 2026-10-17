package com.github.vbmacher.intellij.dbt.ide

import com.intellij.json.JsonFileType
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.psi.PsiFile
import com.intellij.usages.UsageInfo2UsageAdapter
import org.junit.Assert._

import scala.jdk.CollectionConverters._

/** Find Usages is invoked from the editor, just as in the Cucumber Scala fixture tests. */
final class DbtFindUsagesActionTest extends DbtCodeInsightTestCase {
  private def location(file: PsiFile, text: String, occurrence: Int = 0): (PsiFile, Int, String) = {
    val offset = Iterator.iterate(file.getText.indexOf(text))(i => file.getText.indexOf(text, i + 1))
      .drop(occurrence).next()
    assertTrue(s"Missing expected usage '$text' in ${file.getName}", offset >= 0)
    (file, offset, text)
  }

  private def assertUsages(expected: (PsiFile, Int, String)*): Unit = {
    val usages = myFixture.testFindUsagesUsingAction().asScala.toVector
    val locations = usages.map { usage =>
      assertTrue(s"Expected a source usage, got $usage", usage.isInstanceOf[UsageInfo2UsageAdapter])
      val info = usage.asInstanceOf[UsageInfo2UsageAdapter].getUsageInfo
      val element = info.getElement
      val range = info.getRangeInElement
      assertNotNull("Usage element", element)
      assertNotNull("Usage range", range)
      (element.getContainingFile, element.getTextOffset + range.getStartOffset, range.substring(element.getText))
    }
    assertEquals("Number of usages", expected.size, locations.size)
    assertEquals("Usage files, offsets and highlighted names", expected.toSet, locations.toSet)
  }

  def testModelUsagesIncludeSqlPropertiesAndSnapshotRelations(): Unit = {
    project()
    val orders = configure("models/orders.sql", "<caret>select 1 as order_id")
    val caller = add("models/report.sql", "select * from {{ ref('orders') }} union all select * from {{ ref('orders') }}")
    val properties = add("models/orders.yml", "models:\n- name: orders\n  columns: [{name: order_id}]\n")
    val snapshot = add("snapshots/history.yml", "snapshots:\n- name: history\n  relation: ref('orders')\n")

    open(orders, 0)
    assertUsages(location(caller, "orders"), location(caller, "orders", 1),
      location(properties, "orders"), location(snapshot, "orders"))
  }

  def testJinjaCommentsAndRawBlocksAreExcludedButSqlCommentsCount(): Unit = {
    project()
    val orders = configure("models/orders.sql", "<caret>select 1")
    val caller = add("models/report.sql",
      "{# {{ ref('orders') }} #}\n{% raw %}{{ ref('orders') }}{% endraw %}\n-- {{ ref('orders') }}\nselect * from {{ ref('orders') }}")

    open(orders, 0)
    assertUsages(location(caller, "orders", 2), location(caller, "orders", 3))
  }

  def testSourceTableUsagesDoNotIncludeSameNamedTablesInAnotherSource(): Unit = {
    project()
    val schema = configure("models/sources.yml",
      "sources:\n- name: raw\n  tables:\n  - name: ev<caret>ents\n- name: archived\n  tables:\n  - name: events\n")
    val caller = add("models/report.sql",
      "{{ source('raw', 'events') }} {{ source('archived', 'events') }} {{ source('raw', 'events') }}")

    open(schema, schema.getText.indexOf("events") + 2)
    assertUsages(location(caller, "events"), location(caller, "events", 2))
  }

  def testMacroUsagesKeepThePackageNamespace(): Unit = {
    project()
    val macroFile = configure("macros/math.sql", "{% macro dou<caret>ble(value) %}{{ value * 2 }}{% endmacro %}")
    add("dbt_packages/util/dbt_project.yml", "name: util\n")
    add("dbt_packages/util/macros/math.sql", "{% macro double(value) %}{{ value * 2 }}{% endmacro %}")
    val caller = add("models/report.sql", "select {{ double(1) }}, {{ util.double(2) }}, {{ double(3) }}")

    open(macroFile, macroFile.getText.indexOf("double") + 2)
    assertUsages(location(caller, "double"), location(caller, "double", 2))
  }

  def testMacroParameterUsagesIncludeTheBodyAndNamedCallerArguments(): Unit = {
    project()
    val macroFile = configure("macros/math.sql", "{% macro double(val<caret>ue) %}{{ value * 2 }}{% endmacro %}")
    add("macros/other.sql", "{% macro triple(value) %}{{ value * 3 }}{% endmacro %}")
    val caller = add("models/report.sql", "select {{ double(value=1) }}, {{ triple(value=2) }}")

    open(macroFile, macroFile.getText.indexOf("value") + 2)
    assertUsages(location(macroFile, "value", 1), location(caller, "value"))
  }

  def testDocsUsagesIncludeDescriptionsButExcludeSqlAndMetadataStrings(): Unit = {
    project()
    val docs = configure("docs/catalog.md", "{% docs orders_<caret>description %}Orders{% enddocs %}")
    add("models/orders.sql", "{{ doc('orders_description') }}")
    val schema = add("models/orders.yml",
      "models:\n- name: orders\n  description: \"{{ doc('orders_description') }}\"\n  columns:\n  - name: id\n    description: \"{{ doc('orders_description') }}\"\n  config:\n    meta:\n      description: \"{{ doc('orders_description') }}\"\n")

    open(docs, docs.getText.indexOf("orders_description") + 2)
    assertUsages(location(schema, "orders_description"), location(schema, "orders_description", 1))
  }

  def testScopedVariableUsagesExcludeTheShadowedGlobalDefaultAndAnotherProject(): Unit = {
    val config = project("vars:\n  color: global\n  analytics:\n    color: scoped\n")
    val caller = add("models/report.sql", "{{ var('color') }} {{ var('color', 'fallback') }}")
    add("nested/dbt_project.yml", "name: nested\nvars: {color: nested}\n")
    add("nested/models/report.sql", "{{ var('color') }}")

    open(config, config.getText.lastIndexOf("color") + 2)
    assertUsages(location(caller, "color"), location(caller, "color", 1))
  }

  def testEnvironmentUsagesHighlightTheKeyWithoutItsValueOrFallback(): Unit = {
    project()
    add(".dbt-navigation.yml", "env-files: [.env]\n")
    val env = configure(".env", "DBT_<caret>SCHEMA=private_schema\n")
    val caller = add("models/report.sql", "{{ env_var('DBT_SCHEMA') }} {{ env_var('DBT_SCHEMA', 'private_schema') }}")

    open(env, 2)
    assertUsages(location(caller, "DBT_SCHEMA"), location(caller, "DBT_SCHEMA", 1))
  }

  def testFixtureUsagesDoNotIncludeSameNamedModelReferencesOrInlineRows(): Unit = {
    project()
    add("models/orders.sql", "select 1")
    val fixture = configure("tests/fixtures/orders.csv", "<caret>id\n1\n")
    val unit = add("models/unit.yml",
      "unit_tests:\n- name: example\n  model: orders\n  given:\n  - input: ref('orders')\n    format: csv\n    fixture: orders\n  - input: ref('orders')\n    rows: [{id: orders}]\n  expect:\n    format: csv\n    fixture: orders\n")

    open(fixture, 0)
    assertUsages(location(unit, "orders", 2), location(unit, "orders", 5))
  }

  def testFindUsagesFromACallerFindsOtherCallersOfTheSameModel(): Unit = {
    project()
    add("models/orders.sql", "select 1")
    val first = configure("models/first.sql", "select * from {{ ref('or<caret>ders') }}")
    val second = add("models/second.sql", "select * from {{ ref('orders') }}")

    open(first, first.getText.indexOf("orders") + 2)
    assertUsages(location(first, "orders"), location(second, "orders"))
  }

  def testFindUsagesWithAnExistingLanguageParserUsesThePublicSymbolSearch(): Unit = {
    project()
    val fileTypes = FileTypeManager.getInstance
    val original = fileTypes.getFileTypeByExtension("sql")
    WriteCommandAction.runWriteCommandAction(getProject, new Runnable {
      override def run(): Unit = fileTypes.associateExtension(JsonFileType.INSTANCE, "sql")
    })
    try {
      add("models/orders.sql", "{}")
      val first = configure("models/first.sql", "{\"value\": \"{{ ref('or<caret>ders') }}\"}")
      val second = add("models/second.sql", "{\"value\": \"{{ ref('orders') }}\"}")
      val offset = first.getText.indexOf("orders") + 2
      assertTrue(first.isInstanceOf[com.intellij.json.psi.JsonFile])
      assertNull("This parser does not expose a legacy PSI reference", first.findReferenceAt(offset))
      open(first, offset)
      assertUsages(location(first, "orders"), location(second, "orders"))
    } finally {
      WriteCommandAction.runWriteCommandAction(getProject, new Runnable {
        override def run(): Unit = {
          fileTypes.removeAssociatedExtension(JsonFileType.INSTANCE, "sql")
          fileTypes.associateExtension(original, "sql")
        }
      })
    }
  }
}
