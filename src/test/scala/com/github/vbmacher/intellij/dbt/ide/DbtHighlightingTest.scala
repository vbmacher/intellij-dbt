package com.github.vbmacher.intellij.dbt.ide

import com.intellij.codeInsight.navigation.CtrlMouseData
import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileTypes.{FileTypeManager, SyntaxHighlighterFactory}
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.usages.UsageInfo2UsageAdapter
import com.intellij.util.concurrency.AppExecutorUtil
import org.jetbrains.plugins.textmate.language.syntax.lexer.TextMateElementType
import org.jetbrains.yaml.YAMLHighlighter
import org.junit.Assert._

import java.util.concurrent.{Callable, TimeUnit}
import scala.jdk.CollectionConverters._

final class DbtHighlightingTest extends DbtTestCase {
  override protected def open(file: PsiFile, offset: Int): Unit = {
    super.open(file, offset)
    val highlighter = EditorHighlighterFactory.getInstance.createEditorHighlighter(getProject, file.getVirtualFile)
    highlighter.setText(file.getText)
    myFixture.getEditor.asInstanceOf[EditorEx].setHighlighter(highlighter)
  }

  private val wmsComment = """{#
Resolve the storage root for a persisted WMS dataset.
Parameters:
  step: required pipeline-step directory, e.g. 'final_packaging'.
  dataset: required dataset directory, e.g. 'uat_packaging_vpgm'.
  Both names accept only lowercase letters, digits and underscores.
Returns: <target.s3_data_dir>/<step>/<dataset>/, or none for DuckDB.
The target must define s3_data_dir; Athena appends Hive partition folders.
#}"""

  def testSqlAndJinjaUseEditorColorsWithoutChangingFileType(): Unit = {
    project()
    val original = FileTypeManager.getInstance.getFileTypeByExtension("sql")
    val file = configure("models/report.sql", wmsComment + """
{% set root = 'final_packaging' %}
select 42 from {{ ref('orders') }} -- SQL comment
<caret>""")
    assertEquals(original, FileTypeManager.getInstance.getFileTypeByExtension("sql"))
    assertEquals("TEXT", file.getLanguage.getID)
    assertScope(file, "Resolve", "comment.block.jinja")
    assertScope(file, "Athena", "comment.block.jinja")
    assertScope(file, "set root", "keyword.control.jinja")
    assertScope(file, "final_packaging' %}", "string.quoted.single.jinja")
    assertScope(file, "select", "keyword")
    assertScope(file, "42", "constant.numeric")
    assertScope(file, "ref", "variable.other.jinja")
    assertScope(file, "orders", "string.quoted.single.jinja")
    assertScope(file, "SQL comment", "comment.line.double-dash.sql")
  }

  def testJinjaInsideSqlStringsAndComments(): Unit = {
    project()
    val file = configure("models/report.sql", """select '{{ var("single") }}', "{{ var('double') }}", `{{ var('quoted') }}`
-- {{ ref('line_model') }}
/* {{ ref('block_model') }} */<caret>""")
    for (name <- Vector("single", "double", "quoted", "line_model", "block_model"))
      assertScope(file, name, "jinja")
    assertScope(file, "select", "keyword")
  }

  def testTrimmedRawAndCommentsAndDbtTags(): Unit = {
    project()
    val file = configure("macros/report.sql", """{#- trimmed comment -#}
{%- raw -%}{{ uninterpreted }}{%- endraw -%}
{%+ raw +%}{{ plus_raw }}{%+ endraw +%}
{% test custom(model) %}select {{- model -}}{% endtest %}
{% materialization table, default %}select 1{% endmaterialization %}
{% docs report %}Documentation{% enddocs %}
{% snapshot report %}select 1{% endsnapshot %}<caret>""")
    assertScope(file, "trimmed comment", "comment.block.jinja")
    assertScope(file, "uninterpreted", "comment.block.jinja.raw")
    assertScope(file, "plus_raw", "comment.block.jinja.raw")
    for (tag <- Vector("test custom", "endtest", "materialization table", "endmaterialization", "docs report", "enddocs", "snapshot report", "endsnapshot"))
      assertScope(file, tag, "keyword.control.jinja")
  }

  def testYamlAndMarkdownTemplatesKeepTheirHostParsers(): Unit = {
    project()
    val yaml = configure("models/schema.yml", "version: 2\nmodels:\n  - name: orders\n    description: {{ doc('order_docs') }}<caret>")
    assertEquals("yaml", yaml.getLanguage.getID)
    assertScope(yaml, "order_docs", "string.quoted.single.jinja")
    assertScope(yaml, "models", "yaml")
    val quoted = configure("models/quoted.yml", "description: \"{{ doc('quoted_docs') }}\"\nconfig:\n  pre_hook: |\n    select {{ var('hook_value') }}<caret>")
    assertScope(quoted, "quoted_docs", "string.quoted.single.jinja")
    assertScope(quoted, "hook_value", "string.quoted.single.jinja")
    val markdown = configure("docs/orders.md", "# Orders\n{% docs order_docs %}\n{{ var('title') }}\n{% enddocs %}<caret>")
    assertScope(markdown, "# Orders", "markup.heading")
    assertScope(markdown, "title", "string.quoted.single.jinja")
  }

  def testYamlMappingKeysAndBlockScalarsKeepYamlColors(): Unit = {
    project()
    val file = configure("macros/_macros.yml", """version: 2

macros:
  - name: bigquery_mirror_validate_target
    description: >-
      When DBT_PROJECT is bigquery_mirror, require a BigQuery adapter with target
      dev_bq or prod_bq; otherwise raise a compiler error.
      Reference models: {{ doc('order_docs') }}.

  - name: bigquery_mirror_olap_is_int
    description: >-
      Return true when a value is a number with an integral value.
    arguments:
      - name: value
        type: any
        description: "Required value; {{ var('title') }}"
        sql: |
          select {{ var('query') }}
        enabled: true
<caret>""")
    val editor = myFixture.getEditor.asInstanceOf[EditorEx]
    val keyColor = editor.getColorsScheme.getAttributes(YAMLHighlighter.SCALAR_KEY).getForegroundColor
    for (key <- Vector("version", "macros:", "name:", "description:", "arguments:", "type:", "sql:", "enabled:")) {
      assertScope(file, key, "entity.name.tag.yaml")
      val iterator = editor.getHighlighter.createIterator(file.getText.indexOf(key))
      assertEquals(s"$key must use the YAML key color", keyColor, iterator.getTextAttributes.getForegroundColor)
    }
    assertScope(file, "2\n", "constant.numeric")
    assertScope(file, "When DBT_PROJECT", "string.unquoted.block.yaml")
    assertScope(file, "Return true", "string.unquoted.block.yaml")
    assertScope(file, "Reference models:", "string.unquoted.block.yaml")
    assertScope(file, "order_docs", "string.quoted.single.jinja")
    assertScope(file, "title", "string.quoted.single.jinja")
    assertScope(file, "select", "string.unquoted.block.yaml")
    assertScope(file, "query')", "string.quoted.single.jinja")
    assertScope(file, "true\n", "constant.language.yaml")
  }

  def testOnlyDbtResourcesAndConfigurationReceiveTheGrammar(): Unit = {
    val config = project("model-paths: [custom_models]\ntarget-path: compiled\nvars:\n  title: {{ var('title') }}\n")
    add(".dbtignore", "custom_models/ignored.sql\n")
    val model = configure("custom_models/report.sql", "select {{ ref('orders') }}<caret>")
    assertScope(model, "ref", "jinja")
    open(config, config.getTextLength)
    assertScope(config, "title')", "string.quoted.single.jinja")
    for (path <- Vector("scratch.sql", "models/not_configured.sql", "compiled/report.sql", "custom_models/ignored.sql")) {
      val file = add(path, "select {{ ref('orders') }}")
      val syntax = SyntaxHighlighterFactory.getSyntaxHighlighter(file.getFileType, getProject, file.getVirtualFile)
      assertFalse(path, syntax.getHighlightingLexer.isInstanceOf[org.jetbrains.plugins.textmate.language.syntax.lexer.TextMateHighlightingLexer])
    }
  }

  def testHighlightingUpdatesWhileTypingIncompleteJinja(): Unit = {
    project()
    val file = configure("models/report.sql", "select 1\n{# unfinished<caret>")
    assertScope(file, "unfinished", "comment.block.jinja")
    edit(file, "select 1\n{# finished #}\nselect {{ ref('")
    assertScope(file, "select 1", "keyword")
    assertScope(file, "ref", "variable.other.jinja")
    edit(file, "select 1\n{# finished #}\nselect {{ ref('orders') }}")
    assertScope(file, "orders", "string.quoted.single.jinja")
  }

  def testGoToDeclarationAndCtrlHoverWithHighlightingEnabled(): Unit = {
    project()
    val orders = add("models/orders.sql", "select 1")
    val file = configure("models/report.sql", wmsComment + "\nselect * from {{ ref('or<caret>ders') }}")
    assertScope(file, "orders", "string.quoted.single.jinja")
    assertTarget(orders)
    val editor = myFixture.getEditor
    val offset = myFixture.getCaretOffset
    val data = ReadAction.nonBlocking(new Callable[CtrlMouseData] {
      override def call(): CtrlMouseData = new GotoDeclarationAction().getCtrlMouseData(editor, file, offset)
    }).submit(AppExecutorUtil.getAppExecutorService).get(10, TimeUnit.SECONDS)
    assertNotNull(data)
    val start = file.getText.indexOf("orders")
    assertEquals(Vector(new TextRange(start, start + 6)), data.getRanges.asScala.toVector)
  }

  def testEnvironmentDeclarationWithBundledTextMate(): Unit = {
    project()
    val file = configure(".env", "D<caret>BT_TARGET=dev\n")
    add("models/report.sql", "{{ env_var('DBT_TARGET') }}")
    val flags = com.intellij.codeInsight.TargetElementUtil.ELEMENT_NAME_ACCEPTED | com.intellij.codeInsight.TargetElementUtil.REFERENCED_ELEMENT_ACCEPTED
    val target = com.intellij.codeInsight.TargetElementUtil.findTargetElement(myFixture.getEditor, flags)
    assertNotNull(target)
    assertEquals("DBT_TARGET", target.getText)
  }

  def testFindUsagesFromCallerAndDeclarationWithHighlightingEnabled(): Unit = {
    project()
    val orders = add("models/orders.sql", "select 1")
    val first = configure("models/first.sql", "select * from {{ ref('or<caret>ders') }}")
    val second = add("models/second.sql", "select * from {{ ref('orders') }}")
    assertScope(first, "orders", "jinja")
    def locations: Set[(PsiFile, String)] = myFixture.testFindUsagesUsingAction().asScala.map { usage =>
      val info = usage.asInstanceOf[UsageInfo2UsageAdapter].getUsageInfo
      (info.getElement.getContainingFile, info.getRangeInElement.substring(info.getElement.getText))
    }.toSet
    val expected = Set((first, "orders"), (second, "orders"))
    assertEquals(expected, locations)
    open(orders, 0)
    assertEquals(expected, locations)
  }

  private def assertScope(file: PsiFile, text: String, scope: String): Unit = {
    val offset = file.getText.indexOf(text)
    assertTrue("Expected text in fixture: " + text, offset >= 0)
    val iterator = myFixture.getEditor.asInstanceOf[EditorEx].getHighlighter.createIterator(offset)
    assertFalse("Expected a token for " + text, iterator.atEnd())
    assertTrue("Expected TextMate editor highlighting for " + text + ": " + iterator.getTokenType,
      iterator.getTokenType.isInstanceOf[TextMateElementType])
    val actual = iterator.getTokenType.asInstanceOf[TextMateElementType].getScope.toString
    assertTrue(s"$text needs $scope, found $actual", actual.contains(scope))
    assertNotNull("Theme attributes for " + text, iterator.getTextAttributes)
  }
}
