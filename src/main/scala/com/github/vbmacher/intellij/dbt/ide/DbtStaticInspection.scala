package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.navigation.DbtSqlAnalysis
import com.github.vbmacher.intellij.dbt.project._
import com.github.vbmacher.intellij.dbt.syntax.{DbtSyntax, DbtYaml}
import com.intellij.codeInspection.{LocalInspectionTool, ProblemsHolder}
import com.intellij.psi.{PsiElementVisitor, PsiFile}

final class DbtStaticInspection extends LocalInspectionTool {
  override def buildVisitor(holder: ProblemsHolder, onTheFly: Boolean): PsiElementVisitor = new PsiElementVisitor {
    override def visitFile(file: PsiFile): Unit = {
      val w = DbtWorkspace.get(file.getProject)
      w.root(file).filter(r => r.resource(file) && !r.excluded(file.getVirtualFile)).foreach { root =>
        DbtSyntax.inFile(file).flatMap(DbtSyntax.calls).filter(_.closed).foreach { call =>
          val macros = w.macros(call, root)
          if (macros.size == 1 && call.arguments.forall(a => !a.tokens.exists(t => Set("*", "**")(t.image)))) {
            val parameters = DbtCompletion.parameters(w, macros.head).map(_._1)
            if (parameters.nonEmpty && !macros.head.file.getText.contains("kwargs")) call.arguments.foreach { arg =>
              for (name <- arg.keyword if !parameters.contains(name); range <- arg.keywordRange)
                holder.registerProblem(file, "Unknown macro argument '" + name + "'; expected " + parameters.mkString(", "),
                  com.intellij.codeInspection.ProblemHighlightType.GENERIC_ERROR_OR_WARNING, range)
            }
          }
        }
        DbtSqlAnalysis.get(w, file).foreach { analysis =>
          analysis.problems.foreach(p => holder.registerProblem(file, p.message,
            com.intellij.codeInspection.ProblemHighlightType.GENERIC_ERROR_OR_WARNING, p.range))
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
                if (!declared.contains(c.name)) holder.registerProblem(file,
                  s"Model contract: output '${c.name}' has no column declaration.",
                  com.intellij.codeInspection.ProblemHighlightType.GENERIC_ERROR_OR_WARNING, c.range)
                for (actual <- c.dataType; expected <- declared.get(c.name) if expected.nonEmpty && family(actual) != family(expected) &&
                  Set("string", "integer", "decimal")(family(actual)) && Set("string", "integer", "decimal")(family(expected)))
                  holder.registerProblem(file, s"Model contract: '${c.name}' declares $expected but resolves to $actual.",
                    com.intellij.codeInspection.ProblemHighlightType.GENERIC_ERROR_OR_WARNING, c.range)
              }
            }
          }
        }
      }
    }
  }
}
