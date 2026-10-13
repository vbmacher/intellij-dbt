package com.github.vbmacher.intellij.dbt.ide

import com.intellij.codeInsight.navigation.CtrlMouseData
import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.json.JsonFileType
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.util.TextRange
import com.intellij.util.concurrency.AppExecutorUtil
import org.junit.Assert._

import java.util.concurrent.{Callable, TimeUnit}
import scala.jdk.CollectionConverters._

/** Ctrl-hover should expose a link on exactly the navigable name under the pointer. */
final class DbtCtrlHoverTest extends DbtCodeInsightTestCase {
  private def hoverData: CtrlMouseData = {
    val editor = myFixture.getEditor
    val file = myFixture.getFile
    val offset = myFixture.getCaretOffset
    ReadAction.nonBlocking(new Callable[CtrlMouseData] {
      override def call(): CtrlMouseData = new GotoDeclarationAction().getCtrlMouseData(editor, file, offset)
    }).submit(AppExecutorUtil.getAppExecutorService).get(10, TimeUnit.SECONDS)
  }

  private def assertLink(name: String): Unit = {
    val file = myFixture.getFile
    val offset = myFixture.getCaretOffset
    val data = hoverData
    assertNotNull(s"Ctrl-hover should offer navigation for $name", data)
    assertTrue(s"Ctrl-hover on $name should highlight a link", data.isNavigatable)
    val start = file.getText.lastIndexOf(name, offset)
    assertTrue(s"The pointer should be inside $name", start >= 0 && offset < start + name.length)
    assertEquals("Only the referenced name should be highlighted",
      Vector(new TextRange(start, start + name.length)), data.getRanges.asScala.toVector)
  }

  private def assertNoLink(): Unit = {
    val data = hoverData
    assertTrue("A non-navigable name should not be highlighted as a link", data == null || !data.isNavigatable)
  }

  def testRefBuiltinIsHighlighted(): Unit = {
    project()
    add("models/orders.sql", "select 1")
    configure("models/report.sql", "select * from {{ r<caret>ef('orders') }}")
    assertLink("ref")
  }

  def testSourceBuiltinIsHighlighted(): Unit = {
    project()
    add("models/sources.yml", "sources:\n- name: raw\n  tables: [{name: events}]\n")
    configure("models/report.sql", "select * from {{ sou<caret>rce('raw', 'events') }}")
    assertLink("source")
  }

  def testModelArgumentIsHighlightedWithoutQuotesOrTheRestOfTheQuery(): Unit = {
    project()
    add("models/orders.sql", "select 1")
    configure("models/report.sql", "select * from {{ ref('or<caret>ders') }} where id > 0")
    assertLink("orders")
  }

  def testSourceNamespaceIsHighlightedSeparatelyFromTheTable(): Unit = {
    project()
    add("models/sources.yml", "sources:\n- name: raw\n  tables: [{name: events}]\n")
    configure("models/report.sql", "select * from {{ source('r<caret>aw', 'events') }}")
    assertLink("raw")
  }

  def testSourceTableIsHighlightedSeparatelyFromTheNamespace(): Unit = {
    project()
    add("models/sources.yml", "sources:\n- name: raw\n  tables: [{name: events}]\n")
    configure("models/report.sql", "select * from {{ source('raw', 'ev<caret>ents') }}")
    assertLink("events")
  }

  def testVariableBuiltinIsHighlighted(): Unit = {
    project("vars: {start_date: today}\n")
    configure("models/report.sql", "select '{{ v<caret>ar('start_date') }}'")
    assertLink("var")
  }

  def testVariableArgumentIsHighlighted(): Unit = {
    project("vars: {start_date: today}\n")
    configure("models/report.sql", "select '{{ var('start_<caret>date') }}'")
    assertLink("start_date")
  }

  def testMacroNameIsHighlighted(): Unit = {
    project()
    add("macros/math.sql", "{% macro double(value) %}{{ value * 2 }}{% endmacro %}")
    configure("models/report.sql", "select {{ dou<caret>ble(value=2) }}")
    assertLink("double")
  }

  def testNamedMacroArgumentIsHighlighted(): Unit = {
    project()
    add("macros/math.sql", "{% macro double(value) %}{{ value * 2 }}{% endmacro %}")
    configure("models/report.sql", "select {{ double(val<caret>ue=2) }}")
    assertLink("value")
  }

  def testYamlDocumentationNameIsHighlightedWithoutTheSurroundingDescription(): Unit = {
    project()
    add("models/orders.sql", "select 1")
    add("docs/catalog.md", "{% docs orders_description %}Orders{% enddocs %}")
    configure("models/orders.yml", "models:\n- name: orders\n  description: \"See {{ doc('orders_<caret>description') }} for details\"\n")
    assertLink("orders_description")
  }

  def testModelInMultilineYamlHookIsHighlighted(): Unit = {
    project()
    add("models/orders.sql", "select 1")
    configure("models/report.yml", "models:\n- name: report\n  config:\n    pre_hook: |\n      delete from {{ ref('or<caret>ders') }}\n      where id < 0\n")
    assertLink("orders")
  }

  def testYamlModelPropertyNameIsHighlighted(): Unit = {
    project()
    add("models/orders.sql", "select 1")
    configure("models/orders.yml", "models:\n- name: or<caret>ders\n  description: Orders\n")
    assertLink("orders")
  }

  def testAmbiguousModelStillHighlightsItsReferenceForTheChooser(): Unit = {
    project()
    add("models/first/orders.sql", "select 1")
    add("models/second/orders.sql", "select 2")
    configure("models/report.sql", "select * from {{ ref('or<caret>ders') }}")
    assertLink("orders")
  }

  def testCteReferenceIsHighlightedWithoutTheRestOfTheSqlFile(): Unit = {
    project()
    configure("models/report.sql", "with recent as (select 1 as id)\nselect id from re<caret>cent")
    assertLink("recent")
  }

  def testLocalJinjaNameIsHighlighted(): Unit = {
    project()
    configure("models/report.sql", "{% set start_date = 'today' %}\nselect '{{ start_<caret>date }}'")
    assertLink("start_date")
  }

  def testYamlFixtureNameIsHighlighted(): Unit = {
    project()
    add("models/orders.sql", "select 1")
    add("tests/fixtures/orders.csv", "id\n1\n")
    configure("models/unit.yml",
      "unit_tests:\n- name: example\n  model: orders\n  given:\n  - input: ref('orders')\n    format: csv\n    fixture: or<caret>ders\n")
    assertLink("orders")
  }

  def testHighlightingWorksWithAnExistingLanguageParser(): Unit = {
    project("vars: {start_date: today}\n")
    val fileTypes = FileTypeManager.getInstance
    val original = fileTypes.getFileTypeByExtension("sql")
    WriteCommandAction.runWriteCommandAction(getProject, new Runnable {
      override def run(): Unit = fileTypes.associateExtension(JsonFileType.INSTANCE, "sql")
    })
    try {
      val file = configure("models/report.sql", "{\"value\": \"{{ var('start_<caret>date') }}\"}")
      assertTrue(file.isInstanceOf[com.intellij.json.psi.JsonFile])
      assertLink("start_date")
    } finally {
      WriteCommandAction.runWriteCommandAction(getProject, new Runnable {
        override def run(): Unit = {
          fileTypes.removeAssociatedExtension(JsonFileType.INSTANCE, "sql")
          fileTypes.associateExtension(original, "sql")
        }
      })
    }
  }

  def testUnresolvedModelIsNotHighlightedAsALink(): Unit = {
    project()
    configure("models/report.sql", "select * from {{ ref('mis<caret>sing') }}")
    assertNoLink()
  }

  def testJinjaCommentIsNotHighlightedAsALink(): Unit = {
    project()
    add("models/orders.sql", "select 1")
    configure("models/report.sql", "{# {{ ref('or<caret>ders') }} #}")
    assertNoLink()
  }

  def testRawBlockIsNotHighlightedAsALink(): Unit = {
    project()
    add("models/orders.sql", "select 1")
    configure("models/report.sql", "{% raw %}{{ ref('or<caret>ders') }}{% endraw %}")
    assertNoLink()
  }

  def testSqlPunctuationIsNotHighlightedAsALink(): Unit = {
    project()
    add("models/orders.sql", "select 1")
    configure("models/report.sql", "select * from {{ ref(<caret>'orders') }}")
    assertNoLink()
  }
}
