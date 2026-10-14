package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.navigation.DbtNavigation
import com.github.vbmacher.intellij.dbt.project._
import com.github.vbmacher.intellij.dbt.syntax.{DbtSqlText, DbtSyntax}
import com.intellij.openapi.actionSystem.{ActionUpdateThread, AnAction, AnActionEvent, CommonDataKeys}
import com.intellij.openapi.application.{ModalityState, ReadAction}
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.TextRange
import com.intellij.psi.{PsiDocumentManager, PsiFile}
import com.intellij.util.concurrency.AppExecutorUtil
import java.awt.datatransfer.{DataFlavor, StringSelection}
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.Callable
import scala.jdk.CollectionConverters._

private[ide] object DbtClipboard {
  private val Prefix = "/* dbt-workspace-jinja:"
  private def comment(text: String): String = Prefix + Base64.getEncoder.encodeToString(text.getBytes(StandardCharsets.UTF_8)) + " */"
  private def edits(text: String, replacements: Vector[(TextRange, String)]): String = replacements.sortBy(-_._1.getStartOffset)
    .foldLeft(text) { case (result, (range, value)) => result.substring(0, range.getStartOffset) + value + result.substring(range.getEndOffset) }

  def ready(root: DbtRoot): Either[String, Unit] = {
    val w = DbtWorkspace.get(root.config.getProject)
    if (root.setting("manifest").flatMap(root.psiFile).isEmpty) Left("Generate Docs for this project and target to load physical relation metadata.")
    else if (!DbtDeclarationSource.EP.getExtensionList.asScala.forall(_.canReportMissing(w, root)))
      Left("The manifest is malformed or unsupported. Generate Docs with a supported dbt version before converting SQL.")
    else if (!DbtArtifactContext.matches(root)) Left("The manifest has no verified context for the selected project/target. Generate Docs with the dbt Runner before converting SQL.")
    else if (!w.symbols.exists(s => Set("model", "seed", "snapshot", "source_table")(s.kind) &&
      w.owner(s).exists(r => w.executionRoot(r) == root) && DbtResourceData.get(w, s).attributes.get("relation_name").exists(_.nonEmpty)))
      Left("The manifest contains no usable physical relations for this project. Generate Docs before converting SQL.")
    else Right(())
  }

  def copy(file: PsiFile, range: TextRange): Either[String, String] = {
    val w = DbtWorkspace.get(file.getProject)
    w.root(file).map(w.executionRoot).toRight("Open a file in a dbt project.").flatMap { root => ready(root).map { _ =>
      val full = file.getText
      val refs = DbtNavigation.inFile(file)
      val text = range.substring(full)
      val changes = DbtSyntax.blocks(full).filter(b => range.contains(b.range) && !DbtSqlText.inactive(full, b.range.getStartOffset)).map { block =>
        val candidates = refs.filter(r => block.range.contains(r.getRangeInElement)).flatMap(_.candidates).collect {
          case e: DbtSymbolElement if Set("model", "seed", "snapshot", "source_table")(e.symbol.kind) => e.symbol
        }.distinctBy(_.id)
        val tokens = block.tokens
        val calls = DbtSyntax.calls(block)
        val isRelation = !block.tag && ((calls.size == 1 && tokens.headOption.exists(_.range.getStartOffset == calls.head.range.getStartOffset) &&
          Set("ref", "source", "builtins.ref", "builtins.source")(calls.head.name) &&
          tokens.lastOption.exists(_.image == ")")) || tokens.map(_.image) == Vector("this"))
        val physical = if (isRelation && candidates.size == 1) DbtResourceData.get(w, candidates.head).attributes.get("relation_name") else None
        new TextRange(block.range.getStartOffset - range.getStartOffset, block.range.getEndOffset - range.getStartOffset) ->
          physical.getOrElse(comment(block.range.substring(full)))
      }
      edits(text, changes)
    }}
  }

  def paste(w: DbtWorkspace, root: DbtRoot, text: String): Either[String, String] = ready(root).map { _ =>
    def parts(relation: String): Vector[String] = DbtSqlText.tokens(relation).filter(_.identifier).flatMap(t => {
      val value = t.text
      if (value.startsWith("\"") || value.startsWith("`") || value.startsWith("["))
        {
          val decoded = value.drop(1).dropRight(1).replace("\"\"", "\"").replace("``", "`").replace("]]", "]")
          if (value.startsWith("`")) decoded.split("\\.").toVector else Vector(decoded)
        }
      else Vector(value.toLowerCase(java.util.Locale.ROOT))
    })
    val resources = w.symbols.filter(s => Set("model", "seed", "snapshot", "source_table")(s.kind) && !s.isProperty &&
      w.declarations(Set(s.kind), s.name, root, Some(s.namespace)).exists(_.id == s.id)).distinctBy(_.id)
    val relations = resources.flatMap(s => DbtResourceData.get(w, s).attributes.get("relation_name").map(r => (parts(r), s)))
    val changes = DbtNavigation.relationRanges(text).flatMap { range =>
      val name = parts(range.substring(text))
      val matches = if (name.size < 2 || name.size > 3) Vector.empty else relations.filter { case (relation, _) =>
        relation == name || name.size == 2 && relation.takeRight(2) == name
      }.map(_._2).distinctBy(_.id)
      if (matches.size != 1) None else {
        val s = matches.head
        def quote(value: String): String = "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"
        val call = if (s.kind == "source_table") s"source(${quote(s.owner)}, ${quote(s.name)})"
          else "ref(" + (if (s.namespace != root.name) quote(s.namespace) + ", " else "") + quote(s.name) +
            s.version.map(v => ", v=" + quote(v)).getOrElse("") + ")"
        Some(range -> s"{{ $call }}")
      }
    }
    val converted = edits(text, changes)
    // Restore only comments produced by this plugin and only if their payload is exactly one complete Jinja block.
    val restores = DbtSqlText.tokens(converted).filter(t => t.protectedText && t.text.startsWith(Prefix) && t.text.endsWith(" */")).flatMap { token =>
      scala.util.Try(new String(Base64.getDecoder.decode(token.text.stripPrefix(Prefix).stripSuffix(" */")), StandardCharsets.UTF_8)).toOption
        .filter(s => DbtSyntax.blocks(s).exists(b => b.range == new TextRange(0, s.length))).map(token.range -> _)
    }
    edits(converted, restores)
  }
}

abstract class DbtClipboardAction(copying: Boolean) extends AnAction {
  override def getActionUpdateThread: ActionUpdateThread = ActionUpdateThread.BGT
  override def update(event: AnActionEvent): Unit = event.getPresentation.setEnabled(event.getProject != null &&
    event.getData(CommonDataKeys.PSI_FILE) != null && event.getData(CommonDataKeys.EDITOR) != null)

  override def actionPerformed(event: AnActionEvent): Unit = {
    val project = event.getProject; val file = event.getData(CommonDataKeys.PSI_FILE); val editor = event.getData(CommonDataKeys.EDITOR)
    if (project == null || file == null || editor == null) return
    val document = editor.getDocument
    val stamp = document.getModificationStamp
    val start = if (editor.getSelectionModel.hasSelection) editor.getSelectionModel.getSelectionStart else if (copying) 0 else editor.getCaretModel.getOffset
    val end = if (editor.getSelectionModel.hasSelection) editor.getSelectionModel.getSelectionEnd else if (copying) document.getTextLength else start
    val clipboard = if (copying) None else Option(CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor))
    if (!copying && clipboard.isEmpty) return
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    ReadAction.nonBlocking(new Callable[Either[String, String]] {
      override def call(): Either[String, String] = if (copying) DbtClipboard.copy(file, new TextRange(start, end))
        else {
          val w = DbtWorkspace.get(project)
          w.root(file).map(w.executionRoot).toRight("Open a file in a dbt project.").flatMap(r => DbtClipboard.paste(w, r, clipboard.getOrElse("")))
        }
    }).expireWith(project).inSmartMode(project).finishOnUiThread(ModalityState.defaultModalityState(), (result: Either[String, String]) =>
      if (document.getModificationStamp == stamp && !editor.isDisposed) result match {
        case Left(message) => Messages.showWarningDialog(project, message, "dbt SQL Conversion")
        case Right(text) if copying => CopyPasteManager.getInstance().setContents(new StringSelection(text))
        case Right(text) => WriteCommandAction.runWriteCommandAction(project, "Paste as dbt Refs", null, new Runnable {
          override def run(): Unit = {
            document.replaceString(start, end, text); editor.getSelectionModel.removeSelection()
            editor.getCaretModel.moveToOffset(start + text.length)
          }
        })
      }
    ).submit(AppExecutorUtil.getAppExecutorService)
  }
}
final class DbtCopyForTargetAction extends DbtClipboardAction(copying = true)
final class DbtPasteAsRefsAction extends DbtClipboardAction(copying = false)
