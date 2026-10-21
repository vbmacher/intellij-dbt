package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.navigation.{DbtSqlAnalysis, DbtSqlSuppressionComment => Comment, DbtSqlSuppressionLayout => Layout}
import com.github.vbmacher.intellij.dbt.project._
import com.github.vbmacher.intellij.dbt.syntax.{DbtSyntax, DbtYaml}
import com.intellij.codeInspection._
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.{PsiElement, PsiElementVisitor, PsiFile}

final class DbtStaticInspection extends LocalInspectionTool with CustomSuppressableInspectionTool {
  override def getID: String = DbtStaticSuppression.Id
  override def getSuppressActions(element: PsiElement): Array[SuppressIntentionAction] = DbtStaticSuppression.actions(element)
  override def isSuppressedFor(element: PsiElement): Boolean =
    Option(element).flatMap(e => Option(e.getContainingFile)).exists(DbtStaticSuppression.fileSuppressed)
  private def acceptsUnknownKeywords(macroSymbol: DbtSymbol): Boolean = {
    val blocks = DbtSyntax.inFile(macroSymbol.file)
    blocks.find(b => b.isTag("macro") && b.range.contains(macroSymbol.range)).exists { declaration =>
      val end = blocks.find(b => b.isTag("endmacro") && b.range.getStartOffset > declaration.range.getStartOffset)
        .map(_.range.getStartOffset).getOrElse(declaration.range.getEndOffset)
      blocks.filter(b => b.range.getStartOffset >= declaration.range.getEndOffset && b.range.getStartOffset < end)
        .exists(_.tokens.exists(t => !t.string && t.image == "kwargs"))
    }
  }
  override def buildVisitor(holder: ProblemsHolder, onTheFly: Boolean): PsiElementVisitor = new PsiElementVisitor {
    override def visitFile(file: PsiFile): Unit = {
      val w = DbtWorkspace.get(file.getProject)
      w.root(file).filter(r => r.resource(file) && !r.excluded(file.getVirtualFile)).foreach { root =>
        val suppressed = DbtStaticSuppression.ranges(file)
        def report(message: String, range: TextRange): Unit =
          if (!suppressed.exists(_.contains(range)))
            holder.registerProblem(file, message, ProblemHighlightType.GENERIC_ERROR_OR_WARNING, range)
        DbtSyntax.inFile(file).flatMap(DbtSyntax.calls).filter(_.closed).foreach { call =>
          val macros = w.macros(call, root)
          if (macros.size == 1 && call.arguments.forall(a => !a.tokens.exists(t => Set("*", "**")(t.image)))) {
            val parameters = DbtCompletion.parameters(w, macros.head).map(_._1)
            val knownSignature = parameters.nonEmpty || DbtSyntax.inFile(macros.head.file)
              .exists(b => b.isTag("macro") && b.range.contains(macros.head.range))
            if (knownSignature && !acceptsUnknownKeywords(macros.head)) call.arguments.foreach { arg =>
              for (name <- arg.keyword if !parameters.contains(name); range <- arg.keywordRange)
                report("Unknown macro argument '" + name + "'; expected " + parameters.mkString(", "), range)
            }
          }
        }
        DbtSqlAnalysis.get(w, file).foreach { analysis =>
          analysis.problems.foreach(p => report(p.message, p.range))
          w.codeSymbols(file).filter(_.kind == "model").foreach { resource =>
            val enforced = w.properties(resource).flatMap(_.mapping).flatMap(DbtYaml.map(_, "config"))
              .flatMap(DbtYaml.map(_, "contract")).exists(m => DbtYaml.string(m, "enforced").contains("true"))
            if (enforced && analysis.issues.isEmpty && analysis.columns.forall(_.complete)) {
              val declared = w.columns(resource).filter(c => c.mapping.nonEmpty && !c.metadata)
                .map(c => c.name -> c.attributes.getOrElse("data_type", "").toLowerCase).toMap
              def family(t: String): String = t.toLowerCase.takeWhile(c => c != '(' && c != ' ') match {
                case "varchar" | "char" | "text" | "string" => "string"
                case "int" | "integer" | "bigint" | "smallint" => "integer"
                case "numeric" | "decimal" => "decimal"
                case other => other
              }
              analysis.columns.foreach { c =>
                if (!declared.contains(c.name)) report(s"Model contract: output '${c.name}' has no column declaration.", c.range)
                for (actual <- c.dataType; expected <- declared.get(c.name) if expected.nonEmpty && family(actual) != family(expected) &&
                  Set("string", "integer", "decimal")(family(actual)) && Set("string", "integer", "decimal")(family(expected)))
                  report(s"Model contract: '${c.name}' declares $expected but resolves to $actual.", c.range)
              }
            }
          }
        }
      }
    }
  }
}

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
