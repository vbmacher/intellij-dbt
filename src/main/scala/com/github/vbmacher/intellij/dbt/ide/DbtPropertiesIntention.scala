package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project._
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

abstract class DbtPropertiesIntention(inherit: Boolean) extends IntentionAction {
  override def getText: String = if (inherit) "Inherit empty dbt column descriptions" else "Generate missing dbt columns"
  override def getFamilyName: String = "dbt properties"
  override def startInWriteAction(): Boolean = false
  private def resource(project: Project, editor: Editor, file: PsiFile): Option[(DbtWorkspace, DbtSymbol)] = {
    val w = DbtWorkspace.get(project)
    DbtImpactUi.context(w, Some(file), editor.getCaretModel.getOffset)
      .filter(s => !s.metadata && s.file.isWritable && w.owner(s).exists(w.packageOwner(_).isEmpty)).map(w -> _)
  }
  override def isAvailable(project: Project, editor: Editor, file: PsiFile): Boolean =
    resource(project, editor, file).exists { case (w, symbol) =>
      if (inherit) com.github.vbmacher.intellij.dbt.lineage.DbtColumnLineage.inherited(w, symbol).nonEmpty
      else {
        val names = w.columns(symbol).filterNot(_.metadata).map(_.name).toSet
        DbtResourceData.get(w, symbol).columns.exists(c => !names(c.name))
      }
    }
  override def invoke(project: Project, editor: Editor, file: PsiFile): Unit =
    resource(project, editor, file).foreach { case (w, symbol) => DbtPropertiesEditor.generate(w, symbol, inherit) }
}
final class DbtGenerateColumnsIntention extends DbtPropertiesIntention(inherit = false)
final class DbtInheritDescriptionsIntention extends DbtPropertiesIntention(inherit = true)
