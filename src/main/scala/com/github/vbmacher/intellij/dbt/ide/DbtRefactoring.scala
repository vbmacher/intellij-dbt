package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.navigation.{DbtNavigation, DbtNavigationReference, DbtSqlAnalysis}
import com.github.vbmacher.intellij.dbt.project._
import com.github.vbmacher.intellij.dbt.syntax.{DbtSyntax, DbtYaml}
import com.intellij.lang.refactoring.RefactoringSupportProvider
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.{Key, TextRange}
import com.intellij.psi._
import com.intellij.psi.search.{GlobalSearchScope, PsiSearchScopeUtil, SearchScope}
import com.intellij.refactoring.listeners.RefactoringElementListener
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.refactoring.safeDelete.{NonCodeUsageSearchInfo, SafeDeleteProcessorDelegateBase}
import com.intellij.refactoring.safeDelete.usageInfo.SafeDeleteReferenceSimpleDeleteUsageInfo
import com.intellij.usageView.UsageInfo
import com.intellij.util.IncorrectOperationException
import com.intellij.util.containers.MultiMap
import scala.jdk.CollectionConverters._

private[ide] object DbtRefactoring {
  /** Kinds that rename and safe delete support. */
  val Kinds: Set[String] = Set("model", "macro", "cte", "sql_alias", "sql_projection", "column")

  /** Authored SQL models whose properties declare `column`. */
  def models(w: DbtWorkspace, column: DbtSymbol): Vector[DbtSymbol] = w.relations(column).filter(s => s.kind == "model" && s.isCode)

  /** dbt references in workspace files (of `scope`, if given) that resolve to any of `targets`. */
  def references(w: DbtWorkspace, targets: Seq[PsiElement], scope: Option[SearchScope] = None): Vector[DbtNavigationReference] =
    w.files.filter(f => scope.forall(PsiSearchScopeUtil.isInScope(_, f))).flatMap(DbtNavigation.inFile(_))
      .filter(r => targets.exists(r.isReferenceTo))
}

/** Native rename drives preview/conflicts/undo; every usage is resolved by the existing reference providers. */
final class DbtRenameProcessor extends RenamePsiElementProcessor {
  private final class FamilyEdits(val ranges: Vector[SmartPsiFileRange]) {
    var applied: Boolean = false
  }

  private val familyEdits = Key.create[FamilyEdits]("dbt.rename.family.edits")

  override def renameElement(element: PsiElement, name: String, usages: Array[UsageInfo], listener: RefactoringElementListener): Unit = {
    val family = Option(element.getUserData(familyEdits))
    val edits = if (family.exists(_.applied)) Map.empty[PsiFile, Vector[(PsiFile, TextRange)]]
    else family.map(_.ranges.flatMap(pointer => for (range <- Option(pointer.getRange); file <- Option(pointer.getContainingFile))
      yield file -> new TextRange(range.getStartOffset, range.getEndOffset))).getOrElse(usages.toVector.flatMap { usage =>
      for (source <- Option(usage.getElement); file <- Option(source.getContainingFile); range <- Option(usage.getRangeInElement))
        yield (file, range.shiftRight(if (source.isInstanceOf[PsiFile]) 0 else source.getTextOffset))
    }).distinct.groupBy(_._1)
    edits.foreach { case (file, changes) =>
      val documents = PsiDocumentManager.getInstance(file.getProject)
      val document = documents.getDocument(file)
      changes.map(_._2).sortBy(-_.getStartOffset).foreach { range =>
        val existing = document.getText(range)
        val old = element.asInstanceOf[DbtSymbolElement].getName
        val quote = DbtSymbolElement.quote(existing)
        val raw = if (quote.nonEmpty) existing.drop(1).dropRight(1) else existing
        if (raw == old) document.replaceString(range.getStartOffset, range.getEndOffset, quote.fold(name)(q => s"$q$name$q"))
        else if (raw != name) throw new IncorrectOperationException("A resolving usage changed; reopen the refactoring preview.")
      }
      documents.commitDocument(document)
    }
    family.foreach(_.applied = true)
    element.asInstanceOf[PsiNamedElement].setName(name)
    if (listener != null) listener.elementRenamed(element)
  }

  override def canProcessElement(element: PsiElement): Boolean = element match {
    case e: DbtSymbolElement => DbtRefactoring.Kinds(e.symbol.kind)
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
    DbtRefactoring.references(w, element +: aliases, Some(scope)).map(r => r: PsiReference).asJava
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
        val ranges = DbtRefactoring.references(w, all)
          .filterNot(r => Option(r.getElement.findElementAt(r.getRangeInElement.getStartOffset)).exists(leaf => DbtYaml.ancestors(leaf).contains("defined_in")))
          .distinctBy(r => (r.getElement, r.getRangeInElement)).flatMap { r =>
            val range = r.getRangeInElement
            val raw = range.substring(r.getElement.getText)
            val nameRange = if (raw == e.getName) Some(range)
            else if (raw == "\"" + e.getName + "\"" || raw == "'" + e.getName + "'") Some(new TextRange(range.getStartOffset + 1, range.getEndOffset - 1))
            else None
            nameRange.map(SmartPointerManager.getInstance(e.getProject).createSmartPsiFileRangePointer(r.getElement, _))
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
        .foreach(c => renames.put(c.element, name))
      }
    case e: DbtSymbolElement if e.symbol.kind == "column" =>
      val w = DbtWorkspace.get(e.getProject)
      DbtRefactoring.models(w, e.symbol).foreach { model =>
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
      val s = e.symbol;
      val w = DbtWorkspace.get(e.getProject)
      if (s.kind == "column" && !DbtRefactoring.models(w, s).exists(model =>
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

        def parents(range: TextRange) = analysis.ctes.filter(_.body.contains(range)).map(_.range).toSet

        val collision = s.kind match {
          case "cte" => analysis.ctes.exists(c => c.name.equalsIgnoreCase(name) && !c.range.contains(e.getTextRange) && parents(c.range) == parents(e.getTextRange))
          case "sql_alias" => scope.exists(_.relations.exists(_._1.equalsIgnoreCase(name)))
          case "sql_projection" => (analysis.columns ++ analysis.intermediates).exists(c => c.name.equalsIgnoreCase(name) &&
            c.range != e.getTextRange && analysis.scope(c.range.getStartOffset) == scope)
          case _ => false
        }
        if (collision) conflicts.putValue(e, s"'$name' is already declared in this SQL scope.")
      }
      val usages = DbtRefactoring.references(w, Vector(e))
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

private[ide] object DbtSafeDelete {

  import DbtRefactoring.Kinds

  /** Translate native editor/file selections before the IDE opens its deletion dialog. */
  def targets(element: PsiElement): Vector[DbtSymbolElement] = element match {
    case symbol: DbtSymbolElement if Kinds(symbol.symbol.kind) => Vector(symbol)
    case file: PsiFile =>
      val w = DbtWorkspace.get(file.getProject)
      w.root(file).filter(_.resource(file)).toVector.flatMap { _ =>
        (w.codeSymbols(file).filter(_.kind == "model") ++ w.symbols.filter(s =>
          s.file == file && s.kind == "macro" && s.attributes.contains("block-start")))
          .map(_.element).distinct
      }
    case _ => Option(element.getContainingFile).toVector.flatMap { file =>
      val w = DbtWorkspace.get(file.getProject)
      DbtUsages.caretEditor(file).toVector.flatMap { active =>
        val offset = active.getCaretModel.getOffset
        val declarations = DbtUsages.sqlDeclarationsAt(w, file, offset)
        val authored = w.symbolAt(file, offset).filter(s => Kinds(s.kind))
        val references = DbtNavigation.resolvedAt(file, offset).filter(s => Kinds(s.kind))
        val exact = (if (declarations.nonEmpty) declarations else if (authored.nonEmpty) authored else references)
          .filter(s => Kinds(s.kind)).map(_.element).distinct
        if (exact.size == 1) exact else Vector.empty
      }
    }
  }
}

/** The native Safe Delete action requires language support before consulting processor delegates. */
final class DbtSafeDeleteSupport extends RefactoringSupportProvider {
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
    new DbtRenameProcessor().findReferences(element, GlobalSearchScope.projectScope(element.getProject), false)
      .asScala.filterNot { ref =>
        all.exists {
          case e: DbtSymbolElement if e.getContainingFile == ref.getElement.getContainingFile =>
            e.symbol.isCode || e.symbol.mapping.exists(_.getTextRange.contains(ref.getRangeInElement))
          case file: PsiFile => file == ref.getElement.getContainingFile
          case _ => false
        }
      }.foreach { ref =>
        val range = ref.getRangeInElement
        usages.add(new SafeDeleteReferenceSimpleDeleteUsageInfo(ref.getElement, element, range.getStartOffset, range.getEndOffset, false, false))
      }
    null
  }

  override def findConflicts(element: PsiElement, all: Array[PsiElement], usages: Array[UsageInfo],
    conflicts: MultiMap[PsiElement, String]): Unit =
    conflictMessages(element).foreach(conflicts.putValue(element, _))

  private def conflictMessages(element: PsiElement): Vector[String] = element match {
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
        if (localFiles.exists(f => DbtSyntax.inFile(f).flatMap(DbtSyntax.calls).exists(c =>
          Set("ref", "source", "builtins.ref", "builtins.source", "adapter.dispatch")(c.name) && c.arguments.exists(_.literal.isEmpty))))
          messages += "Dynamic consumers exist; the complete set of usages cannot be proven."
        if (DbtSymbol.ColumnKinds(e.symbol.kind) && localFiles.filter(_.getName.endsWith(".sql"))
          .exists(f => DbtSqlAnalysis.get(w, f).forall(_.issues.nonEmpty)))
          messages += "SQL analysis is incomplete; dependent column usages cannot be proven."
      }
      messages.result()
    case _ => Vector.empty
  }

  override def preprocessUsages(project: Project, usages: Array[UsageInfo]): Array[UsageInfo] = usages

  private def sourceDeletion(element: DbtSymbolElement): Option[(PsiFile, TextRange)] = {
    val w = DbtWorkspace.get(element.getProject)
    val s = element.symbol
    if (s.attributes.contains("properties-only-delete")) return None
    val direct = DbtSqlAnalysis.get(w, s.file).filter(_.issues.isEmpty).toVector.flatMap(_.deletions).find(_._1 == element.getTextRange).map(s.file -> _._2)
    direct.orElse(if (s.kind != "column") None else DbtRefactoring.models(w, s).headOption.flatMap { model =>
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
        val manager = PsiDocumentManager.getInstance(e.getProject)
        manager.getDocument(file).deleteString(range.getStartOffset, range.getEndOffset);
        manager.commitAllDocuments()
      }
    }
    case _ => ()
  }

  override def isToSearchInComments(element: PsiElement): Boolean = false

  override def setToSearchInComments(element: PsiElement, value: Boolean): Unit = ()

  override def isToSearchForTextOccurrences(element: PsiElement): Boolean = false

  override def setToSearchForTextOccurrences(element: PsiElement, value: Boolean): Unit = ()
}
