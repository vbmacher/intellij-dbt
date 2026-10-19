package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project.DbtWorkspace
import com.github.vbmacher.intellij.dbt.navigation.{DbtSqlAnalysis, DbtSqlSuppressionComment => Comment, DbtSqlSuppressionLayout => Layout}
import com.intellij.codeInspection.{SuppressIntentionAction, SuppressionUtil}
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.{PsiElement, PsiFile}

/** Native suppression actions share the SQL statement boundaries used to filter authored diagnostics. */
private[ide] object DbtStaticSuppression {
  val Id = "DbtStaticAnalysis"
  private def layout(file: PsiFile): Layout = DbtSqlAnalysis.suppressionLayout(file)
  private def supported(file: PsiFile): Boolean = Option(file.getVirtualFile).exists(_.getExtension == "sql") && {
    val w = DbtWorkspace.get(file.getProject)
    w.root(file).exists(r => r.resource(file) && !r.excluded(file.getVirtualFile))
  }
  private def mentions(comment: Comment): Boolean = SuppressionUtil.isInspectionToolIdMentioned(comment.ids, Id)
  private def fileSuppressed(data: Layout): Boolean = data.comments.exists(c => c.file && mentions(c) &&
    data.statements.headOption.forall(s => c.range.getEndOffset <= s.first))
  def fileSuppressed(file: PsiFile): Boolean = supported(file) && fileSuppressed(layout(file))
  def ranges(file: PsiFile): Vector[TextRange] = if (!supported(file)) Vector.empty else {
    val data = layout(file)
    if (fileSuppressed(data)) Vector(new TextRange(0, file.getTextLength))
    else data.statements.filter(s => data.comments.exists(c => !c.file && mentions(c) &&
      c.range.getStartOffset >= s.range.getStartOffset && c.range.getEndOffset <= s.first)).map(_.range)
  }
  def actions(element: PsiElement): Array[SuppressIntentionAction] =
    Option(element).flatMap(e => Option(e.getContainingFile)).filter(supported).map(_ =>
      Array[SuppressIntentionAction](new Action(wholeFile = false), new Action(wholeFile = true)))
      .getOrElse(SuppressIntentionAction.EMPTY_ARRAY)

  private final class Action(wholeFile: Boolean) extends SuppressIntentionAction {
    setText(if (wholeFile) "Suppress for file" else "Suppress for statement")
    override def getFamilyName: String = "Suppress dbt SQL, macro arguments and contracts"
    override def getElementToMakeWritable(file: PsiFile): PsiElement = file
    override def isAvailable(project: Project, editor: Editor, element: PsiElement): Boolean =
      editor != null && Option(element.getContainingFile).exists(supported)
    override def invoke(project: Project, editor: Editor, element: PsiElement): Unit = {
      val file = element.getContainingFile
      val document = editor.getDocument
      if (wholeFile) document.insertString(0, "--file: noinspection " + Id + "\n")
      else layout(file).statements.find(_.range.containsOffset(editor.getCaretModel.getOffset)).foreach { statement =>
        val line = document.getLineNumber(statement.first)
        val lineStart = document.getLineStartOffset(line)
        val indentation = document.getText(new TextRange(lineStart, statement.first))
        if (indentation.forall(_.isWhitespace)) document.insertString(lineStart, indentation + "-- noinspection " + Id + "\n")
        else document.insertString(statement.first, "\n-- noinspection " + Id + "\n")
      }
    }
  }
}
