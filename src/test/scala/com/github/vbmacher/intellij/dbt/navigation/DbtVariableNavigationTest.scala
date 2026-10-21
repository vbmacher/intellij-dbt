package com.github.vbmacher.intellij.dbt.navigation

import com.github.vbmacher.intellij.dbt.project.DbtRoot
import com.github.vbmacher.intellij.dbt.ide.{DbtPsiSymbol, DbtTestCase}
import com.intellij.model.psi.impl.TargetsKt
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.{PsiDocumentManager, PsiFile, PsiReference}
import com.intellij.psi.search.{GlobalSearchScope, LocalSearchScope}
import com.intellij.psi.search.searches.ReferencesSearch
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.junit.Assert._

import scala.jdk.CollectionConverters._

final class DbtVariableNavigationTest extends DbtTestCase {
  private def exampleProject(config: String = "vars:\n  start_date: '2026-01-01'\n"): PsiFile =
    add("dbt_project.yml", "name: example\nconfig-version: 2\n" + config)

  override protected def root: DbtRoot = workspace.roots.find(_.name == "example").get

  private def declaration(name: String): YAMLKeyValue =
    DbtResolution.variable(workspace, root, name).head.asInstanceOf[YAMLKeyValue]

  // Variable name arguments, excluding the `var` builtin itself and dynamic expressions.
  private def references(file: PsiFile): Seq[DbtNavigationReference] =
    DbtNavigation.inFile(file).filterNot(r => Set("var", "static reference").contains(r.getCanonicalText))

  private def usages(name: String): Seq[PsiReference] = ReferencesSearch.search(declaration(name)).findAll().asScala.toSeq

  def testNavigateToYamlAndFindUsages(): Unit = {
    exampleProject()
    val model = add("models/events.sql", "select '{{ var('start_date') }}', {{ var(\"start_date\", 'default') }}")
    val test = add("tests/check_events.sql", "select {{ var('start_date') }}")
    val expected = declaration("start_date")

    assertEquals(2, references(model).size)
    references(model).foreach(reference => {
      assertSame(expected, reference.resolve())
      assertEquals("start_date", reference.getRangeInElement.substring(model.getText))
    })

    // Exercise the registered navigation extension, not just the resolver.
    myFixture.configureFromExistingVirtualFile(model.getVirtualFile)
    val offset = model.getText.indexOf("start_date") + 3
    assertSame(expected, TargetsKt.targetSymbols(model, offset).asScala.head.asInstanceOf[DbtPsiSymbol].element)
    assertSame(expected, model.findReferenceAt(offset).resolve())

    val found = usages("start_date")
    assertEquals(3, found.size)
    assertEquals(Set(model, test), found.map(_.getElement.getContainingFile).toSet)
    assertEquals(3, myFixture.findUsages(expected).size())
  }

  def testProjectScopeOverridesGlobalAndOtherPackagesDoNotLeak(): Unit = {
    exampleProject("vars:\n  start_date: global\n  fallback: global\n  example:\n    start_date: scoped\n  other_package:\n    hidden: other\n")
    val file = add("models/events.sql", "{{ var('start_date') }} {{ var('fallback') }} {{ var('hidden') }}")
    val refs = references(file)
    assertEquals("scoped", refs.head.resolve().asInstanceOf[YAMLKeyValue].getValueText)
    assertEquals("global", refs(1).resolve().asInstanceOf[YAMLKeyValue].getValueText)
    assertNull(refs(2).resolve())
    assertEquals(1, usages("start_date").size)
    val globals = root.config.getKeyValueByKey("vars").getValue
      .asInstanceOf[org.jetbrains.yaml.psi.YAMLMapping]
    assertTrue(ReferencesSearch.search(globals.getKeyValueByKey("start_date")).findAll().isEmpty)
  }

  def testDictionariesAndQuotedNames(): Unit = {
    exampleProject("vars:\n  options:\n    region: us\n  'date.start': today\n  \"it's_ready\": true\n")
    val file = add("models/events.sql", "{{ var('options')['region'] }} {{ var('region') }} {{ var('date.start') }} {{ var(\"it's_ready\") }}")
    assertNotNull(references(file).head.resolve())
    assertNull(references(file)(1).resolve())
    assertEquals(1, usages("date.start").size)
    assertEquals(1, usages("it's_ready").size)
  }

  def testEverySqlResourceAndConfiguredPaths(): Unit = {
    exampleProject("model-paths: [transformations]\nvars:\n  start_date: today\n")
    Seq("transformations", "tests", "snapshots", "macros", "analyses").foreach { directory =>
      val file = add(s"$directory/use.sql", "{% set date = var('start_date') %}")
      assertSame(declaration("start_date"), references(file).head.resolve())
    }
    assertTrue(references(add("models/ignored.sql", "{{ var('start_date') }}")).isEmpty)
    assertEquals(5, usages("start_date").size)
  }

  def testEmptyResourcePathsDisableResources(): Unit = {
    exampleProject("model-paths: []\nvars:\n  start_date: today\n")
    assertTrue(references(add("models/events.sql", "{{ var('start_date') }}")).isEmpty)
  }

  def testGeneratedPackagesAndNestedProjectsAreExcluded(): Unit = {
    exampleProject("model-paths: ['.']\ntarget-path: build\npackages-install-path: vendor\nvars:\n  start_date: today\n")
    Seq("build", "vendor", "logs").foreach { directory =>
      assertTrue(references(add(s"$directory/use.sql", "{{ var('start_date') }}")).isEmpty)
    }
    myFixture.addFileToProject("nested/dbt_project.yml", "name: nested\nvars:\n  start_date: other\n")
    assertEquals("other", references(add("nested/models/use.sql", "{{ var('start_date') }}")).head.resolve()
      .asInstanceOf[YAMLKeyValue].getValueText)
    assertNotNull(references(add("models/use.sql", "{{ var('start_date') }}")).head.resolve())
    assertEquals(1, usages("start_date").size)
  }

  def testFilesOutsideAProjectDoNotActivateNavigation(): Unit = {
    assertTrue(references(add("models/use.sql", "{{ var('start_date') }}")).isEmpty)
    myFixture.addFileToProject("nested/dbt_project.yml", "name: nested\nvars:\n  start_date: other\n")
    assertTrue(references(add("models/other.sql", "{{ var('start_date') }}")).isEmpty)
    assertEquals("other", references(add("nested/models/use.sql", "{{ var('start_date') }}")).head.resolve()
      .asInstanceOf[YAMLKeyValue].getValueText)
  }

  def testJinjaCommentsRawBlocksAndNonCallsAreIgnored(): Unit = {
    exampleProject()
    val file = add("models/use.sql",
      """
        |select var('start_date');
        |{# {{ var('start_date') }} #}
        |{% raw %}{{ var('start_date') }}{% endraw %}
        |{{ "var('start_date')" }}
        |{{ object.var('start_date') }}
        |{{ value | var('start_date') }}
        |{{ value is var('start_date') }} {{ value is not var('start_date') }}
        |{% filter var('start_date') %}text{% endfilter %}
        |{{ var(prefix) }} {{ var('start_' ~ suffix) }}
        |-- {{ var('start_date') }}
        |{% if var('start_date') %}select 1{% endif %}
        |""".stripMargin)
    assertEquals(2, references(file).size)
    assertEquals(2, usages("start_date").size)
  }

  def testMultilineOffsetsNestedDefaultsAndWhitespaceControl(): Unit = {
    exampleProject("vars:\n  start_date: today\n  fallback: yesterday\n")
    val file = add("models/use.sql", "select\n{{-\n var (\n 'start_date', var('fallback')\n )\n-}}")
    val refs = references(file)
    assertEquals(Seq("start_date", "fallback"), refs.map(_.getCanonicalText))
    refs.foreach(reference => assertEquals(reference.getCanonicalText, reference.getRangeInElement.substring(file.getText)))
    assertEquals(1, usages("start_date").size)
    assertEquals(1, usages("fallback").size)
  }

  def testMissingDefinitionsRemainSoftAndDynamicNamesAreNotReferences(): Unit = {
    exampleProject()
    val file = add("models/use.sql", "{{ var('missing', 'default') }} {{ var(dynamic_name) }} {{ var('prefix_' + name) }}")
    assertEquals(1, references(file).size)
    assertTrue(references(file).head.isSoft)
    assertNull(references(file).head.resolve())
  }

  def testSearchRespectsGlobalAndLocalScopes(): Unit = {
    exampleProject()
    val first = add("models/first.sql", "{{ var('start_date') }}")
    add("models/second.sql", "{{ var('start_date') }}")
    assertEquals(1, ReferencesSearch.search(declaration("start_date"),
      GlobalSearchScope.fileScope(getProject, first.getVirtualFile)).findAll().size())
    assertEquals(1, ReferencesSearch.search(declaration("start_date"), new LocalSearchScope(first)).findAll().size())
  }

  def testUnsavedEditsInvalidateCallsAndDefinitions(): Unit = {
    val config = exampleProject("vars:\n  start_date: today\n  end_date: tomorrow\n")
    val file = add("models/use.sql", "{{ var('start_date') }}")
    assertEquals("start_date", references(file).head.getCanonicalText)
    val documents = PsiDocumentManager.getInstance(getProject)
    WriteCommandAction.runWriteCommandAction(getProject, new Runnable {
      override def run(): Unit = {
        documents.getDocument(file).setText("{{ var('end_date') }}")
        documents.getDocument(config).setText("name: example\nvars:\n  end_date: changed\n")
        documents.commitAllDocuments()
      }
    })
    assertEquals("end_date", references(file).head.getCanonicalText)
    assertEquals("changed", references(file).head.resolve().asInstanceOf[YAMLKeyValue].getValueText)
    assertEquals(1, usages("end_date").size)
  }

  def testCompletedCallInsideAnUnclosedJinjaBlock(): Unit = {
    exampleProject()
    val file = add("models/use.sql", "{{ var('start_date')")
    assertSame(declaration("start_date"), references(file).head.resolve())
  }

  def testEscapedLiteralNamesKeepTheirSourceRanges(): Unit = {
    exampleProject("vars:\n  \"it's_ready\": true\n")
    val file = add("models/use.sql", "{{ var('it\\'s_ready') }}")
    val reference = references(file).head
    assertEquals("it's_ready", reference.getCanonicalText)
    assertEquals("it\\'s_ready", reference.getRangeInElement.substring(file.getText))
    assertSame(declaration("it's_ready"), reference.resolve())
    assertEquals(1, usages("it's_ready").size)
  }

  def testWhitespaceControlledRawBlocks(): Unit = {
    exampleProject()
    val file = add("models/use.sql", "{%- raw -%}{{ var('start_date') }}{%- endraw -%}\n{{ var('start_date') }}")
    assertEquals(1, references(file).size)
    assertEquals(file.getText.lastIndexOf("start_date"), references(file).head.getRangeInElement.getStartOffset)
  }

  def testMalformedJinjaDoesNotBreakNavigation(): Unit = {
    exampleProject()
    val file = add("models/use.sql", "{{ var('start_date') }}\n{% = %}")
    assertEquals(1, references(file).size)
  }

  def testTrimNormalizationDoesNotChangeVariableNames(): Unit = {
    exampleProject("vars:\n  '{%-name': true\n")
    val file = add("models/use.sql", "{{ var('{%-name') }}")
    assertEquals("{%-name", references(file).head.getCanonicalText)
    assertSame(declaration("{%-name"), references(file).head.resolve())
    assertEquals(1, usages("{%-name").size)
  }

  def testNavigationAndSearchWithAnExistingLanguageParser(): Unit = {
    exampleProject()
    withSqlParsedAsJson {
      val file = add("models/use.sql", "value: \"{{ var('start_date') }}\"")
      assertTrue(file.isInstanceOf[com.intellij.json.psi.JsonFile])
      val offset = file.getText.indexOf("start_date") + 2
      myFixture.configureFromExistingVirtualFile(file.getVirtualFile)
      assertSame(declaration("start_date"),
        TargetsKt.targetSymbols(file, offset).asScala.head.asInstanceOf[DbtPsiSymbol].element)
      assertEquals(1, usages("start_date").size)
    }
  }
}
