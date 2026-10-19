package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.navigation.{DbtNavigation, DbtSqlAnalysis}
import com.github.vbmacher.intellij.dbt.project.{DbtSymbolElement, DbtWorkspace}
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.refactoring.safeDelete.{NonCodeUsageSearchInfo, SafeDeleteProcessorDelegateBase}
import com.intellij.refactoring.safeDelete.usageInfo.SafeDeleteReferenceSimpleDeleteUsageInfo
import com.intellij.usageView.UsageInfo
import scala.jdk.CollectionConverters._

private[ide] object DbtSafeDelete {
  private val Kinds = Set("model", "macro", "cte", "sql_alias", "sql_projection", "column")
  /** Translate native editor/file selections before the IDE opens its deletion dialog. */
  def targets(element: PsiElement): Vector[DbtSymbolElement] = element match {
    case symbol: DbtSymbolElement if Kinds(symbol.symbol.kind) => Vector(symbol)
    case file: com.intellij.psi.PsiFile =>
      val w = DbtWorkspace.get(file.getProject)
      w.root(file).filter(_.resource(file)).toVector.flatMap { _ =>
        (w.codeSymbols(file).filter(_.kind == "model") ++ w.symbols.filter(s =>
          s.file == file && s.kind == "macro" && s.attributes.contains("block-start")))
          .map(_.element).distinct
      }
    case _ => Option(element.getContainingFile).toVector.flatMap { file =>
      val w = DbtWorkspace.get(file.getProject)
      val editor = Option(com.intellij.openapi.fileEditor.FileEditorManager.getInstance(file.getProject).getSelectedTextEditor)
        .filter(_.getDocument == com.intellij.psi.PsiDocumentManager.getInstance(file.getProject).getDocument(file))
      editor.toVector.flatMap { active =>
        val offset = active.getCaretModel.getOffset
        val declarations = DbtSqlAnalysis.get(w, file).toVector.flatMap(_.declarations).filter(_.range.containsOffset(offset))
        val authored = w.symbolAt(file, offset).filter(s => Kinds(s.kind))
        val references = DbtNavigation.at(file, offset).flatMap(_.candidates).collect {
          case target: DbtSymbolElement if Kinds(target.symbol.kind) => target.symbol
        }
        val exact = (if (declarations.nonEmpty) declarations else if (authored.nonEmpty) authored else references)
          .filter(s => Kinds(s.kind)).map(_.element).distinct
        if (exact.size == 1) exact else Vector.empty
      }
    }
  }
}

/** The native Safe Delete action requires language support before consulting processor delegates. */
final class DbtSafeDeleteSupport extends com.intellij.lang.refactoring.RefactoringSupportProvider {
  override def isAvailable(element: PsiElement): Boolean = DbtSafeDelete.targets(element).nonEmpty
  override def isSafeDeleteAvailable(element: PsiElement): Boolean = DbtSafeDelete.targets(element).nonEmpty
}

final class DbtSafeDelete extends SafeDeleteProcessorDelegateBase {
  override def handlesElement(element: PsiElement): Boolean = DbtSafeDelete.targets(element).nonEmpty
  override def getElementsToSearch(element: PsiElement, module: Module, all: java.util.Collection[_ <: PsiElement]): java.util.Collection[_ <: PsiElement] =
    DbtSafeDelete.targets(element).map(target => target: PsiElement).asJava
  override def getAdditionalElementsToDelete(element: PsiElement, all: java.util.Collection[_ <: PsiElement], ask: Boolean): java.util.Collection[PsiElement] = element match {
    case e: DbtSymbolElement if e.symbol.kind == "model" || e.symbol.kind == "macro" =>
      val w = DbtWorkspace.get(e.getProject)
      (w.properties(e.symbol) ++ w.symbols.filter(s => e.symbol.isProperty && s.sameResource(e.symbol) && w.owner(s) == w.owner(e.symbol) &&
        (s.isCode || s.kind == "macro" && s.attributes.contains("block-start"))))
        .filterNot(_.id == e.symbol.id).distinctBy(_.id).map(s => s.element: PsiElement).asJava
    case e: DbtSymbolElement if e.symbol.kind == "sql_projection" =>
      val w = DbtWorkspace.get(e.getProject)
      w.codeSymbols(e.getContainingFile).filter(s => s.kind == "model" && DbtSqlAnalysis.get(w, s.file).exists(_.columns
        .exists(c => c.name == e.getName && c.range == e.getTextRange))).flatMap(w.columns).filter(c => c.name == e.getName && c.mapping.nonEmpty)
        .map(c => c.copy(attributes = c.attributes + ("properties-only-delete" -> "true")).element: PsiElement).asJava
    case _ => java.util.List.of()
  }
  override def findUsages(element: PsiElement, all: Array[PsiElement], usages: java.util.List[_ >: UsageInfo]): NonCodeUsageSearchInfo = {
    new DbtRenameProcessor().findReferences(element, com.intellij.psi.search.GlobalSearchScope.projectScope(element.getProject), false)
      .asScala.filterNot { ref =>
        all.exists {
          case e: DbtSymbolElement if e.getContainingFile == ref.getElement.getContainingFile =>
            e.symbol.isCode || e.symbol.mapping.exists(_.getTextRange.contains(ref.getRangeInElement))
          case file: com.intellij.psi.PsiFile => file == ref.getElement.getContainingFile
          case _ => false
        }
      }.foreach { ref =>
      val range = ref.getRangeInElement
      usages.add(new SafeDeleteReferenceSimpleDeleteUsageInfo(ref.getElement, element, range.getStartOffset, range.getEndOffset, false, false))
    }
    null
  }
  override def findConflicts(element: PsiElement, all: Array[PsiElement], usages: Array[UsageInfo],
    conflicts: com.intellij.util.containers.MultiMap[PsiElement, String]): Unit =
    conflictMessages(element).asScala.foreach(conflicts.putValue(element, _))
  private def conflictMessages(element: PsiElement): java.util.Collection[String] = element match {
    case e: DbtSymbolElement =>
      val w = DbtWorkspace.get(e.getProject)
      val messages = Vector.newBuilder[String]
      if (!e.isWritable) messages += "Installed packages and artifact-only declarations are read-only."
      if (!Set("model", "macro")(e.symbol.kind) && sourceDeletion(e).isEmpty && e.symbol.mapping.isEmpty)
        messages += "This definition cannot be removed while preserving valid SQL."
      if (e.symbol.kind == "column" && !e.symbol.attributes.contains("properties-only-delete") && sourceDeletion(e).isEmpty)
        messages += "The output projection is unavailable or ambiguous. Delete its YAML declaration explicitly in source."
      if (e.symbol.kind == "model" && e.symbol.version.nonEmpty) messages += "Delete a versioned model family explicitly after reviewing every version."
      if (e.symbol.kind == "column" && w.symbols.exists(other => other.kind == "column" && other.file == e.symbol.file &&
        other.range == e.symbol.range && other.version != e.symbol.version))
        messages += "This column declaration is shared by model versions. Create explicit version overrides before deleting it."
      if (Set("model", "macro", "column", "sql_projection")(e.symbol.kind)) {
        val localFiles = w.files.filter(f => w.root(f).exists(r => w.owner(e.symbol).exists(owner => w.executionRoot(r) == w.executionRoot(owner))))
        if (localFiles.exists(f => com.github.vbmacher.intellij.dbt.syntax.DbtSyntax.inFile(f)
          .flatMap(com.github.vbmacher.intellij.dbt.syntax.DbtSyntax.calls).exists(c =>
            Set("ref", "source", "builtins.ref", "builtins.source", "adapter.dispatch")(c.name) && c.arguments.exists(_.literal.isEmpty))))
          messages += "Dynamic consumers exist; the complete set of usages cannot be proven."
        if (Set("column", "sql_projection")(e.symbol.kind) && localFiles.filter(_.getName.endsWith(".sql"))
          .exists(f => DbtSqlAnalysis.get(w, f).forall(_.issues.nonEmpty)))
          messages += "SQL analysis is incomplete; dependent column usages cannot be proven."
      }
      messages.result().asJava
    case _ => java.util.List.of()
  }
  override def preprocessUsages(project: Project, usages: Array[UsageInfo]): Array[UsageInfo] = usages
  private def sourceDeletion(element: DbtSymbolElement): Option[(com.intellij.psi.PsiFile, com.intellij.openapi.util.TextRange)] = {
    val w = DbtWorkspace.get(element.getProject)
    val s = element.symbol
    if (s.attributes.contains("properties-only-delete")) return None
    val direct = DbtSqlAnalysis.get(w, s.file).filter(_.issues.isEmpty).toVector.flatMap(_.deletions).find(_._1 == element.getTextRange).map(s.file -> _._2)
    direct.orElse(if (s.kind != "column") None else w.symbols.find(model => model.isCode && model.kind == "model" &&
      model.namespace == s.namespace && model.relationName == s.owner && model.version == s.version && w.owner(model) == w.owner(s)).flatMap { model =>
      DbtSqlAnalysis.get(w, model.file).filter(_.issues.isEmpty).flatMap { a =>
        val columns = a.columns.filter(_.name == s.name)
        if (columns.size == 1 && columns.head.complete) a.deletions.find(_._1 == columns.head.range) else None
      }
        .map(model.file -> _._2)
    })
  }
  override def prepareForDeletion(element: PsiElement): Unit = element match {
    case e: DbtSymbolElement => sourceDeletion(e).foreach { case (file, range) =>
      if (file == e.getContainingFile) e.prepareSourceDeletion(range)
      else {
        val manager = com.intellij.psi.PsiDocumentManager.getInstance(e.getProject)
        manager.getDocument(file).deleteString(range.getStartOffset, range.getEndOffset); manager.commitAllDocuments()
      }
    }
    case _ => ()
  }
  override def isToSearchInComments(element: PsiElement): Boolean = false
  override def setToSearchInComments(element: PsiElement, value: Boolean): Unit = ()
  override def isToSearchForTextOccurrences(element: PsiElement): Boolean = false
  override def setToSearchForTextOccurrences(element: PsiElement, value: Boolean): Unit = ()
}
