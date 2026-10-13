package com.github.vbmacher.intellij.dbt

import com.intellij.openapi.progress.{ProcessCanceledException, ProgressManager}
import com.intellij.openapi.util.TextRange
import com.intellij.psi.{PsiElement, PsiFile}
import scala.collection.mutable.ArrayBuffer

/** Optional Python Community PSI integration, with no linkage to paid or Python-specific APIs. */
private[dbt] object DbtPythonNavigation {
  private def invoke(element: AnyRef, method: String): Option[AnyRef] = try {
    Option(element.getClass.getMethod(method).invoke(element))
  } catch {
    case e: java.lang.reflect.InvocationTargetException => e.getCause match {
      case canceled: ProcessCanceledException => throw canceled
      case _ => None
    }
    case _: ReflectiveOperationException => None
  }
  private def psi(element: AnyRef, method: String): Option[PsiElement] = invoke(element, method).collect { case p: PsiElement => p }
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
  private def literalRange(element: PsiElement, decoded: String): TextRange = {
    val source = element.getText
    val quote = source.indexWhere(c => c == '\'' || c == '"')
    val triple = quote >= 0 && source.drop(quote).startsWith(source(quote).toString * 3)
    val margin = if (triple) 3 else 1
    if (quote >= 0 && !source.take(quote).toLowerCase.contains("f")) new TextRange(element.getTextOffset + quote + margin, element.getTextRange.getEndOffset - margin)
    else element.getTextRange
  }
  def references(file: PsiFile, workspace: DbtWorkspace, root: DbtRoot): Vector[DbtNavigationReference] = {
    if (file.getVirtualFile.getExtension != "py" || !file.getClass.getName.startsWith("com.jetbrains.python.")) return Vector.empty
    val result = ArrayBuffer.empty[DbtNavigationReference]
    def add(e: PsiElement, name: String, targets: Vector[PsiElement]): Unit =
      result += new DbtNavigationReference(file, literalRange(e, name), targets, name)
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
          .find(a => invoke(a, "getName").exists(x => Set("v", "version").contains(x.toString))).flatMap(psi(_, "getValueExpression")).map(_.getText.stripPrefix("'").stripSuffix("'"))
        if (objectExpression.exists(modelParameter(_)) && Set("ref", "source", "config", "config.get").contains(operation)) operation match {
          case "ref" if literals.nonEmpty && literals.forall(_.nonEmpty) =>
            val ns = if (literals.size == 2) literals.head.map(_._2) else None
            val selected = literals.last.get
            val defs = workspace.declarations(Set("model", "seed", "snapshot"), selected._2, root, ns)
            val latest = defs.flatMap(_.attributes.get("latest_version")).distinct
            val chosen = version.orElse(if (latest.size == 1) latest.headOption else None)
            val targets = defs.filter(s => chosen.fold(s.version.isEmpty)(v => s.version.contains(v))).map(_.element)
            add(selected._1, selected._2, targets)
          case "source" if literals.size == 2 && literals.forall(_.nonEmpty) =>
            val source = literals.head.get; val table = literals(1).get
            add(source._1, source._2, workspace.declarations(Set("source"), source._2, root).map(_.element))
            add(table._1, table._2, workspace.symbols.filter(s => s.kind == "source_table" && s.owner == source._2 && s.name == table._2 && s.namespace == root.name).map(_.element))
          case "config.get" => literals.headOption.flatten.foreach { case (host, key) => add(host, key, DbtConfiguration.locations(workspace, file, key)) }
          case "config" => arguments.filter(_.getClass.getSimpleName == "PyKeywordArgumentImpl").foreach { argument =>
            invoke(argument, "getName").foreach { key => result += new DbtNavigationReference(file,
              new TextRange(argument.getTextOffset, argument.getTextOffset + key.toString.length), DbtConfiguration.locations(workspace, file, key.toString), key.toString) }
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
