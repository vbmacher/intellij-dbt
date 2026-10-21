package com.github.vbmacher.intellij.dbt.navigation.python

import com.github.vbmacher.intellij.dbt.navigation.{DbtNavigationContext, DbtNavigationReference, DbtReferenceProvider, DbtResolution}
import com.github.vbmacher.intellij.dbt.project.{DbtConfiguration, DbtSymbol}
import com.github.vbmacher.intellij.dbt.syntax.DbtPython
import com.github.vbmacher.intellij.dbt.syntax.DbtPython.{invoke, psi}
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement

import scala.collection.mutable.ArrayBuffer

/** `dbt.ref`, `dbt.source` and `dbt.config` calls of Python models. */
private[python] final class DbtPythonNavigation extends DbtReferenceProvider {
  private def ancestor(element: PsiElement, simpleName: String): Option[PsiElement] =
    Iterator.iterate(element.getParent)(_.getParent).takeWhile(_ != null).find(_.getClass.getSimpleName == simpleName)

  private def modelParameter(expression: PsiElement, visited: Set[PsiElement] = Set.empty): Boolean = {
    if (visited(expression) || visited.size >= 8) return false
    Option(expression.getReference).flatMap(r => Option(r.resolve())).exists { target =>
      if (target.getClass.getSimpleName == "PyNamedParameterImpl") invoke(target, "getName").contains("dbt") &&
        ancestor(target, "PyFunctionImpl").exists(f => invoke(f, "getName").contains("model"))
      else if (target.getClass.getSimpleName == "PyTargetExpressionImpl") ancestor(target, "PyAssignmentStatementImpl")
        .flatMap(psi(_, "getAssignedValue")).exists(value => modelParameter(value, visited + expression))
      else false
    }
  }

  private def literalRange(element: PsiElement): TextRange = {
    val source = element.getText
    val quote = source.indexWhere(c => c == '\'' || c == '"')
    val triple = quote >= 0 && source.drop(quote).startsWith(source(quote).toString * 3)
    val margin = if (triple) 3 else 1
    if (quote >= 0 && !source.take(quote).toLowerCase.contains("f")) new TextRange(element.getTextOffset + quote + margin, element.getTextRange.getEndOffset - margin)
    else element.getTextRange
  }

  override def references(context: DbtNavigationContext): Vector[DbtNavigationReference] = {
    import context.{file, root, workspace}
    if (!root.kind(file.getVirtualFile).contains("model") || file.getVirtualFile.getExtension != "py" || !DbtPython.isPython(file))
      return Vector.empty
    val result = ArrayBuffer.empty[DbtNavigationReference]

    def add(e: PsiElement, name: String, targets: Vector[PsiElement]): Unit =
      result += new DbtNavigationReference(file, literalRange(e), targets, name)

    def walk(e: PsiElement): Unit = {
      ProgressManager.checkCanceled()
      if (e.getClass.getSimpleName == "PyCallExpressionImpl") {
        val callee = psi(e, "getCallee")
        val arguments = invoke(e, "getArguments").collect { case xs: Array[_] => xs.toVector.collect { case p: PsiElement => p } }.getOrElse(Vector.empty)
        val name = callee.map(_.getText).getOrElse("")
        val operation = name.split("\\.").drop(1).mkString(".")
        val objectExpression = callee.flatMap(psi(_, "getQualifier")).flatMap { qualifier =>
          if (Set("config", "config.get").contains(operation) && qualifier.getText.endsWith(".config")) psi(qualifier, "getQualifier") else Some(qualifier)
        }
        val positional = arguments.filterNot(_.getClass.getSimpleName == "PyKeywordArgumentImpl")
        val literals = positional.map(arg => invoke(arg, "getStringValue").collect { case s: String if !invoke(arg, "isInterpolated").contains(java.lang.Boolean.TRUE) => arg -> s })
        val version = arguments.filter(_.getClass.getSimpleName == "PyKeywordArgumentImpl")
          .find(a => invoke(a, "getName").exists(x => Set("v", "version").contains(x.toString))).flatMap(psi(_, "getValueExpression"))
          .map(v => invoke(v, "getStringValue").collect { case s: String => s }.getOrElse(v.getText))
        if (objectExpression.exists(modelParameter(_)) && Set("ref", "source", "config", "config.get").contains(operation)) operation match {
          case "ref" if literals.nonEmpty && literals.forall(_.nonEmpty) =>
            val ns = if (literals.size == 2) literals.head.map(_._2) else None
            val selected = literals.last.get
            val defs = workspace.declarations(DbtSymbol.RefKinds, selected._2, root, ns)
            add(selected._1, selected._2, DbtResolution.versioned(defs, version).map(_.element))
          case "source" if literals.size == 2 && literals.forall(_.nonEmpty) =>
            val source = literals.head.get
            val table = literals(1).get
            add(source._1, source._2, workspace.declarations(Set("source"), source._2, root).map(_.element))
            add(table._1, table._2, DbtResolution.sourceTables(workspace, root, source._2, table._2).map(_.element))
          case "config.get" => literals.headOption.flatten.foreach { case (host, key) => add(host, key, DbtConfiguration.locations(workspace, file, key)) }
          case "config" => arguments.filter(_.getClass.getSimpleName == "PyKeywordArgumentImpl").foreach { argument =>
            invoke(argument, "getName").foreach { key =>
              result += new DbtNavigationReference(file,
                new TextRange(argument.getTextOffset, argument.getTextOffset + key.toString.length), DbtConfiguration.locations(workspace, file, key.toString), key.toString)
            }
          }
          case _ => ()
        }
      }
      e.getChildren.foreach(walk)
    }

    walk(file)
    result.toVector
  }
}
