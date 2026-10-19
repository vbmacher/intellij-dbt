package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.navigation.DbtSqlAnalysis
import com.github.vbmacher.intellij.dbt.project.DbtWorkspace
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

/** IntelliJ supplies a preview and one undoable write command for this intention. */
final class DbtExpandColumnsIntention extends IntentionAction {
  override def getText: String = "Expand dbt SQL columns"
  override def getFamilyName: String = "dbt SQL"
  override def startInWriteAction(): Boolean = true
  private def expansion(file: PsiFile, offset: Int) =
    DbtSqlAnalysis.get(DbtWorkspace.get(file.getProject), file).toVector.flatMap(_.wildcards)
      .find { case (range, columns) => range.containsOffset(offset) && columns.nonEmpty }
  private def problem(file: PsiFile, offset: Int) =
    DbtSqlAnalysis.get(DbtWorkspace.get(file.getProject), file).toVector.flatMap(_.wildcardProblems)
      .find(_.range.containsOffset(offset))
  override def isAvailable(project: Project, editor: Editor, file: PsiFile): Boolean =
    expansion(file, editor.getCaretModel.getOffset).nonEmpty || problem(file, editor.getCaretModel.getOffset).nonEmpty
  override def invoke(project: Project, editor: Editor, file: PsiFile): Unit = {
    val offset = editor.getCaretModel.getOffset
    expansion(file, offset) match {
      case Some((range, columns)) => editor.getDocument.replaceString(range.getStartOffset, range.getEndOffset, columns.mkString(", "))
      case _ => problem(file, offset).foreach(p => HintManager.getInstance().showErrorHint(editor, p.message))
    }
  }
  override def generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo = {
    val original = file.getOriginalFile
    expansion(original, editor.getCaretModel.getOffset).map { case (range, columns) =>
      val text = original.getText
      new IntentionPreviewInfo.CustomDiff(file.getFileType, text,
        text.substring(0, range.getStartOffset) + columns.mkString(", ") + text.substring(range.getEndOffset)): IntentionPreviewInfo
    }.orElse(problem(original, editor.getCaretModel.getOffset).map(p =>
      new IntentionPreviewInfo.Html(com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(p.message)): IntentionPreviewInfo))
      .getOrElse(IntentionPreviewInfo.EMPTY)
  }
}
