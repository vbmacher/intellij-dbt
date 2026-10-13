package com.github.vbmacher.intellij.dbt

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.{PsiDocumentManager, PsiElement, PsiFile}
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert._
import scala.jdk.CollectionConverters._

final class DbtNavigationTest extends BasePlatformTestCase {
  private def add(path: String, text: String): PsiFile = myFixture.addFileToProject(path, text)
  private def root(config: String = "", path: String = ""): PsiFile = add(path + "dbt_project.yml", "name: analytics\n" + config)
  private def refs(file: PsiFile, text: String, occurrence: Int = 0): Vector[DbtNavigationReference] = {
    val start = Iterator.iterate(file.getText.indexOf(text))(i => file.getText.indexOf(text, i + 1)).drop(occurrence).next()
    assertTrue(s"Missing text $text", start >= 0)
    DbtNavigation.at(file, start + math.min(1, text.length - 1))
  }
  private def target(file: PsiFile, text: String, occurrence: Int = 0): PsiElement = {
    val all = refs(file, text, occurrence).flatMap(_.candidates).distinct
    assertEquals(s"Targets for $text: $all", 1, all.size)
    all.head
  }
  private def path(e: PsiElement): String = e.getContainingFile.getVirtualFile.getPath
  private def uses(e: PsiElement): Int = ReferencesSearch.search(e).findAll().size()

  def testRefSeedsSnapshotsPropertiesAndReverseUsages(): Unit = {
    root()
    val model = add("models/orders.sql", "select 1 as id")
    add("seeds/countries.csv", "country\nUS\n")
    add("snapshots/snapshots.yml", "snapshots:\n- name: history\n  relation: ref('orders')\n")
    val properties = add("models/schema.yml", "models:\n- name: orders\n  columns:\n  - name: id\n")
    val usage = add("models/use.sql", "select * from {{ ref('orders') }} -- {{ ref('countries') }} {{ ref('history') }}")
    assertEquals(path(model), path(target(usage, "orders")))
    assertTrue(path(target(usage, "countries")).endsWith("countries.csv"))
    assertTrue(path(target(usage, "history")).endsWith("snapshots.yml"))
    assertEquals(path(model), path(target(properties, "orders")))
    assertEquals(2, uses(target(usage, "orders")))
  }

  def testModelVersionsDefinedInAndLatest(): Unit = {
    root()
    add("models/order_previous.sql", "select 1")
    val latest = add("models/orders_v2.sql", "select 2")
    add("models/orders.yml", "models:\n- name: orders\n  latest_version: 2\n  versions:\n  - v: 1\n    defined_in: order_previous\n  - v: 2\n")
    val file = add("models/use.sql", "{{ ref('orders') }} {{ ref('orders', v=1) }} {{ ref('orders', version=2) }}")
    assertEquals(path(latest), path(target(file, "orders")))
    assertTrue(path(target(file, "orders", 1)).endsWith("order_previous.sql"))
    assertEquals(path(latest), path(target(file, "orders", 2)))
  }

  def testSourceArgumentsUseLogicalNamesAndExactDeclarations(): Unit = {
    root()
    val schema = add("models/sources.yml", "sources:\n- name: raw\n  schema: warehouse\n  tables:\n  - name: orders\n    identifier: physical_orders\n    columns:\n    - name: order_id\n")
    val file = add("models/use.sql", "select * from {{ source('raw', 'orders') }}")
    val first = target(file, "raw").asInstanceOf[DbtSymbolElement]
    val second = target(file, "orders").asInstanceOf[DbtSymbolElement]
    assertEquals("source", first.symbol.kind)
    assertEquals("source_table", second.symbol.kind)
    assertEquals(path(schema), path(first))
    assertTrue(first.getTextOffset < second.getTextOffset)
    assertEquals(1, uses(second))
  }

  def testYamlDecodedQuotedAndBlockOffsets(): Unit = {
    root("vars:\n  start_date: today\n")
    add("models/orders.sql", "select 1")
    val yaml = add("models/schema.yml", "models:\n- name: orders\n  description: \"Example \\" + "\"" + " {{ var('start_date') }}\"\n  config:\n    pre_hook: |\n      select * from {{ ref('orders') }}\n")
    assertNotNull(target(yaml, "start_date"))
    assertTrue(path(target(yaml, "orders", 1)).endsWith("orders.sql"))
    refs(yaml, "start_date").foreach(ref => assertEquals("start_date", ref.getRangeInElement.substring(yaml.getText)))
  }

  def testUnsavedEditsAndSearchScope(): Unit = {
    root()
    add("models/orders.sql", "select 1")
    val file = add("models/use.sql", "{{ ref('orders') }}")
    val second = add("tests/use.sql", "{{ ref('orders') }}")
    val expected = target(file, "orders")
    assertEquals(2, uses(expected))
    assertEquals(1, ReferencesSearch.search(expected, GlobalSearchScope.fileScope(getProject, second.getVirtualFile)).findAll().size())
    WriteCommandAction.runWriteCommandAction(getProject, new Runnable {
      override def run(): Unit = {
        val dm = PsiDocumentManager.getInstance(getProject)
        dm.getDocument(second).setText("{{ ref('missing') }}")
        dm.commitAllDocuments()
      }
    })
    assertEquals(1, uses(expected))
  }

  def testAmbiguityAndDisabledResourcesDoNotPickFirst(): Unit = {
    root()
    add("models/a/orders.sql", "select 1")
    add("models/b/orders.sql", "select 2")
    add("models/off.sql", "{{ config(enabled=false) }}select 3")
    val file = add("models/use.sql", "{{ ref('orders') }} {{ ref('off') }}")
    val reference = refs(file, "orders").head
    assertEquals(2, reference.multiResolve(false).length)
    assertNull(reference.resolve())
    assertTrue(refs(file, "off").forall(_.candidates.isEmpty))
  }

  def testIgnorePathsAndOlderVersionGates(): Unit = {
    root()
    add(".dbtignore", "models/ignored/**\n")
    add("models/ignored/orders.sql", "select 1")
    add("functions/hash.sql", "select 1")
    add("checks/missing.sql", "{{ ref('orders') }}")
    val file = add("models/use.sql", "{{ ref('orders') }} {{ function('hash') }}")
    assertTrue(refs(file, "orders").forall(_.candidates.isEmpty))
    assertTrue(refs(file, "hash").forall(_.candidates.isEmpty))
    assertFalse(DbtWorkspace.get(getProject).symbols.exists(_.kind == "check"))
  }

  def testRestrictedYamlContextsAndLiteralVarValues(): Unit = {
    root("vars:\n  color: \"{{ ref('orders') }}\"\n")
    add("models/orders.sql", "select 1")
    val profiles = add("profiles.yml", "analytics:\n  target: \"{{ ref('orders') }}\"\n")
    assertTrue(DbtNavigation.inFile(profiles).isEmpty)
    assertTrue(refs(getProjectFile("dbt_project.yml"), "orders").isEmpty)
    val schema = add("models/schema.yml", "models:\n- name: orders\n  config:\n    meta:\n      text: \"{{ doc('unknown') }}\"\n")
    assertTrue(refs(schema, "unknown").isEmpty)
  }

  def testResourceMovesInvalidateIndex(): Unit = {
    root()
    val model = add("models/orders.sql", "select 1")
    val file = add("models/use.sql", "{{ ref('orders') }} {{ ref('renamed') }}")
    assertNotNull(target(file, "orders"))
    WriteCommandAction.runWriteCommandAction(getProject, new Runnable { override def run(): Unit = model.getVirtualFile.rename(this, "renamed.sql") })
    assertTrue(refs(file, "orders").forall(_.candidates.isEmpty))
    assertTrue(path(target(file, "renamed")).endsWith("renamed.sql"))
  }


  def testNativeFindUsagesTargetFromPlainTextDeclaration(): Unit = {
    root()
    val model = add("models/orders.sql", "select 1")
    val usage = add("models/use.sql", "{{ ref('orders') }}")
    myFixture.configureFromExistingVirtualFile(model.getVirtualFile)
    val flags = com.intellij.codeInsight.TargetElementUtil.ELEMENT_NAME_ACCEPTED | com.intellij.codeInsight.TargetElementUtil.REFERENCED_ELEMENT_ACCEPTED
    val selected = com.intellij.codeInsight.TargetElementUtil.findTargetElement(myFixture.getEditor, flags)
    assertTrue(s"Native Find Usages target: $selected", selected.isInstanceOf[DbtSymbolElement])
    assertEquals(1, myFixture.findUsages(selected).size())
    assertEquals("model", com.intellij.lang.findUsages.LanguageFindUsages.INSTANCE.forLanguage(selected.getLanguage).getType(selected))
  }

  def testSourceNamespaceDoesNotLeakFromInstalledPackage(): Unit = {
    root()
    add("models/sources.yml", "sources:\n- name: raw\n  tables:\n  - name: events\n")
    add("dbt_packages/util/dbt_project.yml", "name: util\n")
    add("dbt_packages/util/models/sources.yml", "sources:\n- name: raw\n  tables:\n  - name: events\n")
    val file = add("models/use.sql", "{{ source('raw', 'events') }}")
    assertEquals(1, refs(file, "events").flatMap(_.candidates).distinct.size)
    assertFalse(path(target(file, "events")).contains("dbt_packages"))
  }

  def testIndexLargeProjectAndPartialTemplates(): Unit = {
    root()
    (1 to 80).foreach(i => add(s"models/model_$i.sql", s"select $i as id"))
    val file = add("models/use.sql", "{{ ref('model_80') }}\n{{ ref('model_1')")
    assertTrue(path(target(file, "model_80")).endsWith("model_80.sql"))
    assertTrue(path(target(file, "model_1")).endsWith("model_1.sql"))
  }
  def testDeclarationTargetsSurviveInsertedText(): Unit = {
    root()
    val macroFile = add("macros/math.sql", "{% macro double(value) %}{{ value * 2 }}{% endmacro %}")
    val file = add("models/use.sql", "{{ double(2) }}")
    val oldTarget = target(file, "double")
    val oldOffset = oldTarget.getTextOffset
    WriteCommandAction.runWriteCommandAction(getProject, new Runnable { override def run(): Unit = {
      val dm = PsiDocumentManager.getInstance(getProject)
      dm.getDocument(macroFile).insertString(0, "-- Added comment\n"); dm.commitAllDocuments()
    }})
    assertTrue(oldTarget.isValid)
    assertEquals(oldOffset + 17, oldTarget.getTextOffset)
    assertEquals(1, uses(oldTarget))
  }

  def testTemporaryCompletionFilesHaveNoDbtReferences(): Unit = {
    root()
    val temporary = com.intellij.psi.PsiFileFactory.getInstance(getProject).createFileFromText("orders.sql", com.intellij.openapi.fileTypes.PlainTextFileType.INSTANCE, "{{ ref('orders') }}")
    assertFalse(temporary.isPhysical)
    assertTrue(DbtNavigation.inFile(temporary).isEmpty)
    assertTrue(temporary.getReferences.isEmpty)
    assertTrue(DbtWorkspace.get(getProject).root(temporary).isEmpty)
  }

  def testNestedDefaultsDoNotCreateFakeMacroParameters(): Unit = {
    root()
    val macroFile = add("macros/format.sql", "{% macro format(a=['one', 'two'], b=namespace(flag=true)) %}{{ a }} {{ b }}{% endmacro %}")
    val file = add("models/use.sql", "{{ format(b=1) }}")
    val parameter = target(file, "b").asInstanceOf[DbtSymbolElement]
    assertEquals("parameter", parameter.symbol.kind)
    assertEquals(2, DbtWorkspace.get(getProject).symbols.count(s => s.file == macroFile && s.kind == "parameter"))
    assertEquals(2, uses(parameter))
  }

  def testRepeatedNamedArgumentsNavigateToTheirOwnMacro(): Unit = {
    root()
    add("macros/math.sql", "{% macro double(value) %}{{ value * 2 }}{% endmacro %}{% macro triple(value) %}{{ value * 3 }}{% endmacro %}")
    val file = add("models/use.sql", "{{ double(value=1) + triple(value=2) }}")
    assertEquals("double", target(file, "value").asInstanceOf[DbtSymbolElement].symbol.owner)
    assertEquals("triple", target(file, "value", 1).asInstanceOf[DbtSymbolElement].symbol.owner)
  }

}
