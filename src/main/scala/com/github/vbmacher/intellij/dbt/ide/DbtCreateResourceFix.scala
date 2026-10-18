package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.navigation.DbtNavigation
import com.github.vbmacher.intellij.dbt.project._
import com.github.vbmacher.intellij.dbt.syntax.{DbtSyntax, DbtYaml}
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.{DialogWrapper, ValidationInfo}
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.{PsiDocumentManager, PsiFile}
import com.intellij.ui.components.{JBLabel, JBTextArea, JBTextField}
import java.awt.BorderLayout
import javax.swing.{JCheckBox, JComponent, JPanel}
import org.jetbrains.yaml.psi.YAMLSequence

private[ide] final case class DbtCreation(kind: String, name: String, source: Option[String], root: DbtRoot,
  file: Option[PsiFile], before: String, after: String, path: String)
private[ide] object DbtCreateResourceFix {
  def proposal(file: PsiFile, offset: Int): Option[DbtCreation] = {
    val w = DbtWorkspace.get(file.getProject)
    for {
      root <- w.root(file).filter(r => w.packageOwner(r).isEmpty && !r.excluded(file.getVirtualFile))
      ref <- DbtNavigation.inFile(file).find(r => r.unresolvedMessage.nonEmpty && r.getRangeInElement.containsOffset(offset))
      call <- DbtSyntax.inFile(file).flatMap(DbtSyntax.calls).find(c => c.closed && c.expressionRange.contains(ref.getRangeInElement))
      folder <- root.pathKinds.find(_._1 == "model").map(_._2)
      result <- call.name match {
        case "ref" | "builtins.ref" if call.positional.size == 1 && call.version.isEmpty => call.literal(0)
          .filter(DbtSyntax.isIdentifier).map(name => DbtCreation("model", name, None, root, None, "", "select\n    1 as id\n", folder + "/" + name + ".sql"))
        case "source" | "builtins.source" if call.positional.size == 2 =>
          for (source <- call.literal(0).filter(DbtSyntax.isIdentifier); table <- call.literal(1).filter(DbtSyntax.isIdentifier);
            result <- sourcePlan(w, root, folder, source, table)) yield result
        case _ => None
      }
    } yield result
  }
  private def sourcePlan(w: DbtWorkspace, root: DbtRoot, folder: String, source: String, table: String): Option[DbtCreation] = {
    val parents = w.declarations(Set("source"), source, root).filter(s => !s.metadata && w.owner(s).contains(root))
    if (parents.size > 1 || parents.exists(s => !s.file.isWritable)) None
    else parents.headOption match {
      case Some(parent) => parent.mapping.flatMap { mapping =>
        val before = parent.file.getText
        val value = "{name: " + table + "}"
        val after = DbtYaml.value(mapping, "tables") match {
          case Some(sequence: YAMLSequence) =>
            val flow = sequence.getText.trim.startsWith("[")
            val offset = sequence.getTextRange.getEndOffset - (if (flow) 1 else 0)
            val insertion = if (flow) (if (sequence.getItems.isEmpty) "" else ", ") + value else
              "\n" + " " * org.jetbrains.yaml.YAMLUtil.getIndentInThisLine(sequence) + "- " + value
            Some(before.substring(0, offset) + insertion + before.substring(offset))
          case None =>
            val offset = mapping.getTextRange.getEndOffset
            Some(before.substring(0, offset) + "\n" + " " * org.jetbrains.yaml.YAMLUtil.getIndentInThisLine(mapping) + "tables: [" + value + "]" + before.substring(offset))
          case _ => None
        }
        after.map(text => DbtCreation("source", table, Some(source), root, Some(parent.file), before, text,
          root.relative(parent.file.getVirtualFile).getOrElse("")))
      }
      case None =>
        val path = folder + "/" + source + "_sources.yml"
        if (root.directory.findFileByRelativePath(path) != null) None else
          Some(DbtCreation("source", table, Some(source), root, None, "", s"version: 2\nsources:\n  - name: $source\n    tables:\n      - name: $table\n", path))
    }
  }
  def create(proposal: DbtCreation, relativePath: String): Either[String, Unit] = {
    val path = java.nio.file.Paths.get(relativePath).normalize()
    val valid = !path.isAbsolute && !path.startsWith("..") && proposal.root.pathKinds.exists { case (kind, folder) =>
      kind == "model" && proposal.root.pathContains(folder, path.toString.replace('\\', '/'))
    }
    if (!valid) Left("Choose a file inside a configured model path.")
    else if (proposal.file.exists(f => f.getText != proposal.before)) Left("Source properties changed. Reopen the quick fix.")
    else if (proposal.file.isEmpty && proposal.root.directory.findFileByRelativePath(path.toString) != null) Left("The destination already exists.")
    else {
      WriteCommandAction.writeCommandAction(proposal.root.config.getProject).withName("Create dbt resource").run(new com.intellij.util.ThrowableRunnable[RuntimeException] {
        override def run(): Unit = proposal.file match {
          case Some(file) =>
            val manager = PsiDocumentManager.getInstance(file.getProject)
            manager.getDocument(file).setText(proposal.after); manager.commitAllDocuments()
          case None =>
            val parent = Option(path.getParent).fold(proposal.root.directory)(p => VfsUtil.createDirectoryIfMissing(proposal.root.directory, p.toString))
            val created = parent.createChildData(this, path.getFileName.toString)
            VfsUtil.saveText(created, proposal.after)
        }
      })
      Right(())
    }
  }
}

final class DbtCreateResourceFix(offset: Int) extends IntentionAction {
  override def getText: String = "Create missing dbt resource"
  override def getFamilyName: String = "dbt resource creation"
  override def startInWriteAction(): Boolean = false
  override def isAvailable(project: Project, editor: Editor, file: PsiFile): Boolean = DbtCreateResourceFix.proposal(file, offset).nonEmpty
  override def invoke(project: Project, editor: Editor, file: PsiFile): Unit = DbtCreateResourceFix.proposal(file, offset).foreach { proposal =>
    val dialog = new DialogWrapper(project) {
      val path = new JBTextField(proposal.path, 55)
      val content = new JPanel(new BorderLayout)
      val preview = new JBTextArea(proposal.after, 12, 70); preview.setEditable(false)
      content.add(new JBLabel(s"Project: ${proposal.root.name} · ${proposal.kind} ${proposal.source.map(_ + ".").getOrElse("")}${proposal.name}"), BorderLayout.NORTH)
      content.add(path, BorderLayout.CENTER); content.add(new com.intellij.ui.components.JBScrollPane(preview), BorderLayout.SOUTH)
      setTitle("Create dbt resource"); setOKButtonText("Create"); init()
      override def createCenterPanel(): JComponent = content
      override def doOKAction(): Unit = DbtCreateResourceFix.create(proposal, path.getText.trim) match {
        case Right(_) => super.doOKAction()
        case Left(error) => setErrorText(error)
      }
    }
    dialog.show()
  }
}
