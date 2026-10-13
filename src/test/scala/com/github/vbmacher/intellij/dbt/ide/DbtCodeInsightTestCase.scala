package com.github.vbmacher.intellij.dbt.ide

import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.{PsiDocumentManager, PsiElement, PsiFile, PsiReference}
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert._

/** Editor fixtures shared by behavior tests; expectations come from source locations. */
abstract class DbtCodeInsightTestCase extends BasePlatformTestCase {
  protected def add(path: String, text: String): PsiFile = myFixture.addFileToProject(path, text)

  protected def project(config: String = ""): PsiFile =
    add("dbt_project.yml", "name: analytics\nconfig-version: 2\n" + config)

  protected def configure(path: String, text: String): PsiFile = {
    val offset = text.indexOf("<caret>")
    assertTrue("The editor fixture must contain one caret", offset >= 0 && offset == text.lastIndexOf("<caret>"))
    val file = add(path, text.replace("<caret>", ""))
    open(file, offset)
    file
  }

  protected def open(file: PsiFile, offset: Int): Unit = {
    myFixture.configureFromExistingVirtualFile(file.getVirtualFile)
    myFixture.getEditor.getCaretModel.moveToOffset(offset)
  }

  protected def reference: PsiReference = myFixture.getReferenceAtCaretPositionWithAssertion()

  protected def declarationTargets: Vector[PsiElement] =
    GotoDeclarationAction.findAllTargetElements(getProject, myFixture.getEditor, myFixture.getCaretOffset).toVector

  protected def assertTarget(file: PsiFile, offset: Int = 0): PsiElement = {
    val targets = declarationTargets
    assertEquals("Go to Declaration should find one target", 1, targets.size)
    val target = targets.head
    assertEquals("Declaration file", file, target.getContainingFile)
    assertEquals("Declaration offset", offset, target.getTextOffset)
    target
  }

  protected def assertNoTarget(): Unit = {
    assertEquals("Go to Declaration should not offer a target", Vector.empty, declarationTargets)
  }

  protected def edit(file: PsiFile, text: String): Unit = {
    WriteCommandAction.runWriteCommandAction(getProject, new Runnable {
      override def run(): Unit = {
        val documents = PsiDocumentManager.getInstance(getProject)
        documents.getDocument(file).setText(text)
        documents.commitAllDocuments()
      }
    })
  }
}
