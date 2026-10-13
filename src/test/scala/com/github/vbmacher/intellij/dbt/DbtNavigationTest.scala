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

}
