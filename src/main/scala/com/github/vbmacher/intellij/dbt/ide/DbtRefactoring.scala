package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.navigation.{DbtNavigation, DbtSqlAnalysis}
import com.github.vbmacher.intellij.dbt.project._
import com.github.vbmacher.intellij.dbt.syntax.{DbtSyntax, DbtYaml}
import com.intellij.openapi.project.Project
import com.intellij.psi.{PsiElement, PsiReference}
import com.intellij.psi.search.SearchScope
import com.intellij.refactoring.listeners.RefactoringElementListener
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.usageView.UsageInfo
import com.intellij.util.containers.MultiMap
import scala.jdk.CollectionConverters._

/** Native rename drives preview/conflicts/undo; every usage is resolved by the existing reference providers. */
final class DbtRenameProcessor extends RenamePsiElementProcessor {
  private final class FamilyEdits(val ranges: Vector[com.intellij.psi.SmartPsiFileRange]) { var applied: Boolean = false }
  private val familyEdits = com.intellij.openapi.util.Key.create[FamilyEdits]("dbt.rename.family.edits")
  override def renameElement(element: PsiElement, name: String, usages: Array[UsageInfo], listener: RefactoringElementListener): Unit = {
    val family = Option(element.getUserData(familyEdits))
    val edits = if (family.exists(_.applied)) Map.empty[com.intellij.psi.PsiFile, Vector[(com.intellij.psi.PsiFile, com.intellij.openapi.util.TextRange)]]
      else family.map(_.ranges.flatMap(pointer => for (range <- Option(pointer.getRange); file <- Option(pointer.getContainingFile))
        yield file -> new com.intellij.openapi.util.TextRange(range.getStartOffset, range.getEndOffset))).getOrElse(usages.toVector.flatMap { usage =>
      for (source <- Option(usage.getElement); file <- Option(source.getContainingFile); range <- Option(usage.getRangeInElement))
        yield (file, range.shiftRight(if (source.isInstanceOf[com.intellij.psi.PsiFile]) 0 else source.getTextOffset))
    }).distinct.groupBy(_._1)
    edits.foreach { case (file, changes) =>
      val document = com.intellij.psi.PsiDocumentManager.getInstance(file.getProject).getDocument(file)
      changes.map(_._2).sortBy(-_.getStartOffset).foreach { range =>
        val existing = document.getText(range)
        val old = element.asInstanceOf[DbtSymbolElement].getName
        val quote = existing.headOption.filter(c => Set('"', '\'', '`')(c) && existing.lastOption.contains(c))
        val raw = if (quote.nonEmpty) existing.drop(1).dropRight(1) else existing
        if (raw == old) document.replaceString(range.getStartOffset, range.getEndOffset, quote.fold(name)(q => s"$q$name$q"))
        else if (raw != name) throw new com.intellij.util.IncorrectOperationException("A resolving usage changed; reopen the refactoring preview.")
      }
      com.intellij.psi.PsiDocumentManager.getInstance(file.getProject).commitDocument(document)
    }
    family.foreach(_.applied = true)
    element.asInstanceOf[com.intellij.psi.PsiNamedElement].setName(name)
    if (listener != null) listener.elementRenamed(element)
  }
  override def canProcessElement(element: PsiElement): Boolean = element match {
    case e: DbtSymbolElement => Set("model", "macro", "cte", "sql_alias", "sql_projection", "column")(e.symbol.kind)
    case _ => false
  }
  override def findReferences(element: PsiElement, scope: SearchScope, searchInCommentsAndStrings: Boolean): java.util.Collection[PsiReference] = {
    val w = DbtWorkspace.get(element.getProject)
    val aliases = element match {
      case e: DbtSymbolElement if e.symbol.kind == "sql_projection" => exported(w, e).flatMap { case (resource, _) =>
        val columns = w.columns(resource).filter(_.name == e.getName)
        if (columns.exists(_.mapping.nonEmpty)) Vector.empty else columns.map(_.element)
      }
      case _ => Vector.empty
    }
    w.files.filter(com.intellij.psi.search.PsiSearchScopeUtil.isInScope(scope, _)).flatMap(DbtNavigation.inFile(_))
      .filter(r => r.isReferenceTo(element) || aliases.exists(r.isReferenceTo)).map(r => r: PsiReference).asJava
  }
  private def exported(w: DbtWorkspace, e: DbtSymbolElement) = w.codeSymbols(e.getContainingFile).filter(_.kind == "model").flatMap { model =>
    DbtSqlAnalysis.get(w, model.file).toVector.flatMap(_.columns).filter(c => c.range == e.getTextRange && c.name == e.getName).map(model -> _)
  }
  override def prepareRenaming(element: PsiElement, name: String, renames: java.util.Map[PsiElement, String]): Unit = element match {
    case e: DbtSymbolElement if Set("model", "macro")(e.symbol.kind) =>
      val w = DbtWorkspace.get(e.getProject)
      val family = w.symbols.filter(s => s.kind == e.symbol.kind && s.name == e.symbol.name && s.namespace == e.symbol.namespace &&
        w.owner(s) == w.owner(e.symbol) && (s.isCode || s.isProperty || s.kind == "macro" && s.attributes.contains("block-start")))
      if (e.symbol.kind == "model") {
        val all = (family :+ e.symbol).map(_.element)
        val ranges = w.files.flatMap(DbtNavigation.inFile(_)).filter(r => all.exists(r.isReferenceTo))
          .filterNot(r => Option(r.getElement.findElementAt(r.getRangeInElement.getStartOffset)).exists(leaf => DbtYaml.ancestors(leaf).contains("defined_in")))
          .distinctBy(r => (r.getElement, r.getRangeInElement)).flatMap { r =>
            val range = r.getRangeInElement
            val raw = range.substring(r.getElement.getText)
            val nameRange = if (raw == e.getName) Some(range)
              else if (raw == "\"" + e.getName + "\"" || raw == "'" + e.getName + "'") Some(new com.intellij.openapi.util.TextRange(range.getStartOffset + 1, range.getEndOffset - 1))
              else None
            nameRange.map(com.intellij.psi.SmartPointerManager.getInstance(e.getProject).createSmartPsiFileRangePointer(r.getElement, _))
          }
        val plan = new FamilyEdits(ranges)
        all.foreach(_.putUserData(familyEdits, plan))
        e.putUserData(familyEdits, plan)
      }
      family.distinctBy(s => (s.file, s.range)).filterNot(_.id == e.symbol.id).foreach(p => renames.put(p.element, name))
      w.properties(e.symbol).distinctBy(s => (s.file, s.range)).foreach(p => renames.put(p.element, name))
    case e: DbtSymbolElement if e.symbol.kind == "sql_projection" =>
      val w = DbtWorkspace.get(e.getProject)
      exported(w, e).foreach { case (resource, _) => w.columns(resource).filter(c => c.name == e.getName && c.mapping.nonEmpty)
        .foreach(c => renames.put(c.element, name)) }
    case e: DbtSymbolElement if e.symbol.kind == "column" =>
      val w = DbtWorkspace.get(e.getProject)
      w.symbols.filter(s => s.kind == "model" && s.isCode && s.relationName == e.symbol.owner && s.namespace == e.symbol.namespace &&
        s.version == e.symbol.version && w.owner(s) == w.owner(e.symbol)).foreach { model =>
        DbtSqlAnalysis.get(w, model.file).toVector.flatMap(_.columns).find(_.name == e.getName).foreach { column =>
          if (model.file == e.getContainingFile && column.range == e.getTextRange && !column.outputAlias) e.prepareOutputAliasRename()
          else if (model.file != e.getContainingFile || column.range != e.getTextRange) renames.put(DbtSymbol("sql_projection", column.name,
            model.namespace, model.file, column.range, attributes = if (column.outputAlias) Map.empty else Map("insert-output-alias" -> "true")).element, name)
        }
      }
    case _ => ()
  }
  override def findExistingNameConflicts(element: PsiElement, name: String, conflicts: MultiMap[PsiElement, String]): Unit = element match {
    case e: DbtSymbolElement =>
      val s = e.symbol; val w = DbtWorkspace.get(e.getProject)
      if (s.kind == "column" && !w.symbols.exists(model => model.isCode && model.kind == "model" && model.relationName == s.owner &&
        model.namespace == s.namespace && model.version == s.version && w.owner(model) == w.owner(s) &&
        model.file.getVirtualFile.getExtension == "sql" && DbtSqlAnalysis.get(w, model.file).exists(a =>
          a.issues.isEmpty && a.columns.count(_.name == s.name) == 1 && a.columns.exists(c => c.name == s.name && c.complete))))
        conflicts.putValue(e, "Choose a proven, unambiguous SQL model output column. Physical columns and unavailable output schemas require source editing.")
      if (s.kind == "column" && w.symbols.exists(other => other.kind == "column" && other.file == s.file && other.range == s.range && other.version != s.version))
        conflicts.putValue(e, "This column declaration is shared by model versions. Create explicit version overrides before renaming it.")
      if (!DbtSyntax.isIdentifier(name)) conflicts.putValue(e, "Use a valid dbt identifier.")
      if (!e.isWritable) conflicts.putValue(e, "Installed packages and artifact-only declarations are read-only.")
      if (w.symbols.exists(t => t.kind == s.kind && t.name == name && t.namespace == s.namespace && t.owner == s.owner && w.owner(t) == w.owner(s)))
        conflicts.putValue(e, s"A ${s.kind} named '$name' already exists in this scope.")
      DbtSqlAnalysis.get(w, s.file).foreach { analysis =>
        if (analysis.issues.exists(_.startsWith("Explicit CTE column lists"))) conflicts.putValue(e, "Explicit CTE column lists require source editing; their complete usages cannot be proven.")
        val scope = analysis.scope(e.getTextOffset)
        def parents(range: com.intellij.openapi.util.TextRange) = analysis.ctes.filter(_.body.contains(range)).map(_.range).toSet
        val collision = s.kind match {
          case "cte" => analysis.ctes.exists(c => c.name.equalsIgnoreCase(name) && !c.range.contains(e.getTextRange) && parents(c.range) == parents(e.getTextRange))
          case "sql_alias" => scope.exists(_.relations.exists(_._1.equalsIgnoreCase(name)))
          case "sql_projection" => (analysis.columns ++ analysis.intermediates).exists(c => c.name.equalsIgnoreCase(name) &&
            c.range != e.getTextRange && analysis.scope(c.range.getStartOffset) == scope)
          case _ => false
        }
        if (collision) conflicts.putValue(e, s"'$name' is already declared in this SQL scope.")
      }
      val usages = w.files.flatMap(DbtNavigation.inFile(_)).filter(_.isReferenceTo(e))
      if (usages.exists(r => r.candidates.size != 1 && !(s.kind == "model" && r.candidates.forall {
        case target: DbtSymbolElement => target.symbol.kind == "model" && target.symbol.name == s.name &&
          target.symbol.namespace == s.namespace && w.owner(target.symbol) == w.owner(s)
        case _ => false
      }))) conflicts.putValue(e, "Some usages resolve ambiguously.")
      if ((s.kind == "sql_projection" || s.kind == "column" && s.file.getVirtualFile.getExtension == "sql") && DbtSqlAnalysis.get(w, s.file).forall(_.issues.nonEmpty))
        conflicts.putValue(e, "Column analysis is incomplete; review dynamic consumers before renaming.")
      if (Set("column", "sql_projection", "model", "macro")(s.kind) && w.files.exists(f => DbtSyntax.inFile(f).flatMap(DbtSyntax.calls)
        .exists(c => Set("ref", "source", "adapter.dispatch")(c.name) && c.arguments.exists(_.literal.isEmpty))))
        conflicts.putValue(e, "Dynamic consumers exist; the complete set of usages cannot be proven.")
    case _ => ()
  }
}
