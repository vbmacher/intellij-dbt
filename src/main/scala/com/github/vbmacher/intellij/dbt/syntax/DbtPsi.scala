package com.github.vbmacher.intellij.dbt.syntax

import com.intellij.openapi.progress.{ProcessCanceledException, ProgressManager}
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.{PsiElement, PsiFile, PsiManager}
import org.jetbrains.yaml.psi._

import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters._

/** Null-safe PSI helpers for YAML and project files. */
private[dbt] object DbtYaml {
  def psiFile(project: Project, file: VirtualFile): Option[PsiFile] =
    Option(file).flatMap(f => Option(PsiManager.getInstance(project).findFile(f)))

  def mapping(file: PsiFile): Option[YAMLMapping] = file match {
    case yaml: YAMLFile => yaml.getDocuments.asScala.headOption.flatMap(d => Option(d.getTopLevelValue)).collect {
      case m: YAMLMapping => m
    }
    case _ => None
  }

  def keyValue(m: YAMLMapping, key: String): Option[YAMLKeyValue] = Option(m.getKeyValueByKey(key))

  def value(m: YAMLMapping, key: String): Option[YAMLValue] = keyValue(m, key).flatMap(k => Option(k.getValue))

  def map(m: YAMLMapping, key: String): Option[YAMLMapping] = value(m, key).collect { case x: YAMLMapping => x }

  def string(m: YAMLMapping, key: String): Option[String] = value(m, key).collect { case s: YAMLScalar => s.getTextValue }

  /** Values of the items of the sequence held by a key. */
  def elements(m: YAMLMapping, key: String): Vector[YAMLValue] = value(m, key).collect {
    case s: YAMLSequence => s.getItems.asScala.toVector.flatMap(i => Option(i.getValue))
  }.getOrElse(Vector.empty)

  def items(m: YAMLMapping, key: String): Vector[YAMLMapping] = elements(m, key).collect { case x: YAMLMapping => x }

  def strings(m: YAMLMapping, key: String, default: Vector[String] = Vector.empty): Vector[String] = value(m, key) match {
    case Some(_: YAMLSequence) => elements(m, key).collect { case x: YAMLScalar => x.getTextValue }
    case Some(s: YAMLScalar) => Vector(s.getTextValue)
    case None => default
    case _ => Vector.empty
  }

  def nameRange(m: YAMLMapping, key: String = "name"): TextRange = value(m, key).map(_.getTextRange).getOrElse(m.getTextRange)

  def attributes(m: YAMLMapping): Map[String, String] = m.getKeyValues.asScala.flatMap(k => Option(k.getValue).collect {
    case s: YAMLScalar => k.getKeyText -> s.getTextValue
    case _: YAMLSequence if k.getKeyText.stripPrefix("+") == "tags" => k.getKeyText -> strings(m, k.getKeyText).mkString(",")
  }).toMap ++ map(m, "config").toVector.flatMap(attributes)

  /** Keys of the enclosing key-values, innermost first. */
  def ancestors(element: PsiElement): Vector[String] =
    Iterator.iterate(element)(_.getParent).takeWhile(_ != null).collect { case key: YAMLKeyValue => key.getKeyText }.toVector

  def parentMapping(element: PsiElement): Option[YAMLMapping] =
    Iterator.iterate(element.getParent)(_.getParent).takeWhile(_ != null).collectFirst { case m: YAMLMapping => m }

  /** The key of the key-value which directly holds a scalar, or empty for sequence items. */
  def key(scalar: YAMLScalar): String = scalar.getParent match {
    case kv: YAMLKeyValue => kv.getKeyText
    case _ => ""
  }

  /** Decoded scalar text and a function mapping decoded offsets to file offsets. */
  def decoded(scalar: YAMLScalar): Option[(String, Int => Int)] = {
    val escaper = scalar.createLiteralTextEscaper()
    val host = new TextRange(0, scalar.getTextLength)
    val text = new java.lang.StringBuilder
    if (escaper.decode(host, text)) Some(text.toString -> (i => scalar.getTextOffset + escaper.getOffsetInHost(i, host)))
    else None
  }
}

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
