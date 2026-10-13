package com.github.vbmacher.intellij.dbt.syntax

import com.intellij.openapi.progress.{ProcessCanceledException, ProgressManager}
import com.intellij.psi.{PsiElement, PsiFile}

import scala.collection.mutable.ArrayBuffer

/** Optional Python Community PSI, accessed reflectively so the plugin never links to Python-specific APIs. */
private[dbt] object DbtPython {
  def isPython(file: PsiFile): Boolean = file.getClass.getName.startsWith("com.jetbrains.python.")

  def invoke(element: AnyRef, method: String): Option[AnyRef] = try {
    Option(element.getClass.getMethod(method).invoke(element))
  } catch {
    case e: java.lang.reflect.InvocationTargetException => e.getCause match {
      case canceled: ProcessCanceledException => throw canceled
      case _ => None
    }
    case _: ReflectiveOperationException => None
  }

  def psi(element: AnyRef, method: String): Option[PsiElement] = invoke(element, method).collect { case p: PsiElement => p }

  /** Name identifiers of the functions and assignment targets named `name`. */
  def declarations(file: PsiFile, name: String): Vector[PsiElement] = {
    if (!isPython(file)) return Vector.empty
    val result = ArrayBuffer.empty[PsiElement]
    def walk(e: PsiElement): Unit = {
      ProgressManager.checkCanceled()
      if (Set("PyFunctionImpl", "PyTargetExpressionImpl").contains(e.getClass.getSimpleName) && invoke(e, "getName").contains(name))
        psi(e, "getNameIdentifier").foreach(result += _)
      e.getChildren.foreach(walk)
    }
    walk(file)
    result.toVector
  }
}
