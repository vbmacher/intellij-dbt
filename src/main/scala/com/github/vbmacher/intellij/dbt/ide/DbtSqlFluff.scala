package com.github.vbmacher.intellij.dbt.ide

import com.google.gson.{JsonObject, JsonParser}
import com.intellij.codeInspection.{LocalInspectionTool, LocalQuickFix, ProblemDescriptor, ProblemHighlightType, ProblemsHolder}
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.{Key, TextRange}
import com.intellij.psi.{PsiDocumentManager, PsiElementVisitor, PsiFile}
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

private[ide] final case class DbtSqlFluffEdit(range: TextRange, replacement: String)
private[ide] final case class DbtSqlFluffDiagnostic(code: String, message: String, range: TextRange,
  warning: Boolean, edits: Vector[DbtSqlFluffEdit])
private[ide] final case class DbtSqlFluffResult(source: String, stamp: Long, diagnostics: Vector[DbtSqlFluffDiagnostic])
private[ide] object DbtSqlFluff {
  val Result: Key[DbtSqlFluffResult] = Key.create("dbt.sqlfluff.explicit.inspection")
  private val ParseErrors = Set("PRS", "TMP", "LXR")
  private def string(o: JsonObject, key: String): Option[String] =
    Option(o.get(key)).filterNot(_.isJsonNull).map(_.getAsString)
  private def number(o: JsonObject, key: String): Option[Int] =
    Option(o.get(key)).filterNot(_.isJsonNull).map(_.getAsInt)
  private def offset(source: String, position: Int): Option[Int] =
    if (position < 0 || position > source.codePointCount(0, source.length)) None
    else Some(source.offsetByCodePoints(0, position))
  private def lineOffset(source: String, line: Int, column: Int): Option[Int] = {
    val starts = Vector(0) ++ source.indices.filter(source.charAt(_) == '\n').map(_ + 1)
    starts.lift(line - 1).filter(_ => line > 0 && column > 0).flatMap { start =>
      val end = source.indexOf('\n', start) match { case -1 => source.length; case n => n }
      if (column - 1 > source.codePointCount(start, end)) None else Some(source.offsetByCodePoints(start, column - 1))
    }
  }
  private def position(source: String, row: JsonObject, prefix: String): Option[Int] = {
    val byLine = for (line <- number(row, prefix + "_line_no"); column <- number(row, prefix + "_line_pos");
      at <- lineOffset(source, line, column)) yield at
    val byFile = number(row, prefix + "_file_pos").flatMap(offset(source, _))
    if ((row.has(prefix + "_file_pos") && byFile.isEmpty) ||
      ((row.has(prefix + "_line_no") || row.has(prefix + "_line_pos")) && byLine.isEmpty)) None
    else (byLine, byFile) match {
      case (Some(a), Some(b)) if a != b => None
      case (Some(a), _) => Some(a)
      case (_, Some(b)) if !row.has(prefix + "_line_no") && !row.has(prefix + "_line_pos") => Some(b)
      case _ => None
    }
  }
  def parse(source: String, json: String, sourcePath: String = ""): Either[String, Vector[DbtSqlFluffDiagnostic]] = try {
    val files = JsonParser.parseString(json).getAsJsonArray.asScala.toVector
    if (files.size != 1) Left("SQLFluff must return one stdin document; diagnostics were not applied.")
    else {
      val file = files.head.getAsJsonObject
      val path = string(file, "filepath").getOrElse("")
      if (path.isEmpty || (path != "stdin" && path != "-" && path != sourcePath))
        Left("SQLFluff returned a different file; diagnostics were not applied.")
      else {
        val rows = file.getAsJsonArray("violations").asScala.map(_.getAsJsonObject).toVector
        val unsafe = rows.exists(r => ParseErrors(string(r, "code").getOrElse("")))
        val diagnostics = rows.map { row =>
          val start = position(source, row, "start")
          val hasEnd = Vector("end_line_no", "end_line_pos", "end_file_pos").exists(row.has)
          val end = if (hasEnd) position(source, row, "end")
            else start.map(s => if (s < source.length) source.offsetByCodePoints(s, 1) else s)
          for (a <- start; b <- end if a <= b && b <= source.length) yield {
            val code = string(row, "code").getOrElse("SQLFluff")
            val edits = Option(row.getAsJsonArray("fixes")).toVector.flatMap(_.asScala).map { raw =>
              val fix = raw.getAsJsonObject
              for (kind <- string(fix, "type") if Set("replace", "delete", "create_before", "create_after")(kind);
                from <- number(fix, "start_file_pos").flatMap(offset(source, _));
                to <- number(fix, "end_file_pos").flatMap(offset(source, _)) if from <= to;
                replacement <- string(fix, "edit") if (kind != "delete" || replacement.isEmpty) &&
                  (!kind.startsWith("create_") || from == to))
                yield DbtSqlFluffEdit(new TextRange(from, to), replacement)
            }
            val validEdits = if (unsafe || edits.exists(_.isEmpty)) Vector.empty else edits.flatten.toVector
            val sorted = validEdits.sortBy(_.range.getStartOffset)
            val disjoint = sorted.sliding(2).forall(pair => pair.size < 2 || pair.head.range.getEndOffset < pair(1).range.getStartOffset ||
              (pair.head.range.getEndOffset == pair(1).range.getStartOffset && !pair.head.range.isEmpty && !pair(1).range.isEmpty))
            DbtSqlFluffDiagnostic(code, string(row, "description").getOrElse("SQLFluff diagnostic"), new TextRange(a, b),
              string(row, "warning").contains("true"), if (disjoint) validEdits else Vector.empty)
          }
        }
        if (diagnostics.exists(_.isEmpty)) Left("SQLFluff returned invalid source positions; diagnostics were not applied. Update SQLFluff and rerun Inspect dbt SQL.")
        else Right(diagnostics.flatten)
      }
    }
  } catch { case NonFatal(_) => Left("SQLFluff returned unsupported JSON. Check the executable, SQLFluff version and project configuration.") }

  def applyEdits(file: PsiFile, result: DbtSqlFluffResult, diagnostic: DbtSqlFluffDiagnostic): Boolean = {
    val manager = PsiDocumentManager.getInstance(file.getProject)
    val document = manager.getDocument(file)
    if (document == null || !file.isWritable || document.getModificationStamp != result.stamp || document.getText != result.source || diagnostic.edits.isEmpty) false
    else {
      WriteCommandAction.writeCommandAction(file.getProject).withName("Apply SQLFluff " + diagnostic.code).run(new com.intellij.util.ThrowableRunnable[RuntimeException] {
        override def run(): Unit = {
          diagnostic.edits.sortBy(_.range.getStartOffset).reverse.foreach(e => document.replaceString(e.range.getStartOffset, e.range.getEndOffset, e.replacement))
          file.putUserData(Result, null); manager.commitDocument(document)
        }
      })
      true
    }
  }
}

/** Uses only results from the user's explicit Inspect dbt SQL invocation. It never launches a tool while typing. */
final class DbtSqlFluffInspection extends LocalInspectionTool {
  override def getShortName: String = "DbtSqlFluff"
  override def buildVisitor(holder: ProblemsHolder, onTheFly: Boolean): PsiElementVisitor = new PsiElementVisitor {
    override def visitFile(file: PsiFile): Unit = Option(file.getUserData(DbtSqlFluff.Result))
      .filter(r => r.source == file.getText && Option(PsiDocumentManager.getInstance(file.getProject).getDocument(file)).exists(_.getModificationStamp == r.stamp))
      .foreach { result =>
        result.diagnostics.foreach { d =>
          val fixes: Array[LocalQuickFix] = if (d.edits.isEmpty) LocalQuickFix.EMPTY_ARRAY else Array(new DbtSqlFluffFix(result, d))
          holder.registerProblem(file, "SQLFluff " + d.code + ": " + d.message,
            if (Set("PRS", "TMP", "LXR")(d.code)) ProblemHighlightType.GENERIC_ERROR else if (d.warning) ProblemHighlightType.WEAK_WARNING else ProblemHighlightType.GENERIC_ERROR_OR_WARNING, d.range, fixes: _*)
        }
      }
  }
}

final class DbtSqlFluffFix private[ide](result: DbtSqlFluffResult, diagnostic: DbtSqlFluffDiagnostic) extends LocalQuickFix {
  override def getFamilyName: String = "Apply available SQLFluff fix"
  override def getName: String = "Apply SQLFluff " + diagnostic.code + " fix"
  override def startInWriteAction(): Boolean = false
  override def getElementToMakeWritable(currentFile: PsiFile): com.intellij.psi.PsiElement = currentFile
  override def applyFix(project: Project, descriptor: ProblemDescriptor): Unit = {
    DbtSqlFluff.applyEdits(descriptor.getPsiElement.getContainingFile, result, diagnostic)
    ()
  }
}
