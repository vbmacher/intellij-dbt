package com.github.vbmacher.intellij.dbt.navigation

import com.github.vbmacher.intellij.dbt.project.{DbtProjectSettings, DbtRoot, DbtSymbol, DbtSymbolElement, DbtWorkspace}
import com.github.vbmacher.intellij.dbt.syntax.DbtSyntax
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.{Key, TextRange}
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi._
import com.intellij.psi.util.{CachedValue, CachedValueProvider, CachedValuesManager, PsiModificationTracker}
import com.intellij.util.IncorrectOperationException

import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters._

/** A file being navigated; `found` holds the references of the providers registered before the current one. */
private[dbt] final case class DbtNavigationContext(file: PsiFile, workspace: DbtWorkspace, root: DbtRoot, includeSelections: Boolean,
  found: Vector[DbtNavigationReference])

/** Port of the languages hosting dbt references; implementations are registered in `plugin.xml` in priority order. */
private[dbt] trait DbtReferenceProvider {
  def references(context: DbtNavigationContext): Vector[DbtNavigationReference]

  /** Physical relation positions from a host parser, for clipboard conversion. */
  def relationRanges(sql: String): Vector[TextRange] = Vector.empty

  def sqlAnalysis(file: PsiFile, workspace: DbtWorkspace, completionOffset: Option[Int] = None): Option[DbtSqlAnalysis] = None

  def sqlSuppressionLayout(file: PsiFile): Option[DbtSqlSuppressionLayout] = None

  def projectedColumns(sql: String): Option[(Vector[String], Boolean)] = None

  def withPrefix(text: String, end: Int): Option[String] = None
}

private[dbt] object DbtReferenceProvider {
  val EP: ExtensionPointName[DbtReferenceProvider] = ExtensionPointName.create("com.github.vbmacher.intellij-dbt.referenceProvider")

  /** The answer of the first provider, in registration order, that has one. */
  private[navigation] def first[A](answer: DbtReferenceProvider => Option[A]): Option[A] =
    EP.getExtensionList.asScala.iterator.flatMap(answer).nextOption()
}

/** Every resolver produces candidates; ambiguity is preserved for the IDE's declaration chooser. */
final class DbtNavigationReference private[dbt](file: PsiFile, range: TextRange, val candidates: Vector[PsiElement], val label: String,
  val evidence: Vector[String] = Vector.empty, val unresolvedMessage: Option[String] = None)
  extends PsiPolyVariantReferenceBase[PsiFile](file, range, true) {
  override def multiResolve(incompleteCode: Boolean): Array[ResolveResult] = candidates.distinct.map(new PsiElementResolveResult(_)).toArray

  override def getCanonicalText: String = label

  override def handleElementRename(newName: String): PsiElement = {
    val workspace = DbtWorkspace.get(file.getProject)
    val models = candidates.collect { case e: DbtSymbolElement if e.symbol.kind == "model" => e.symbol }
    val family = models.size == candidates.size && models.nonEmpty && models.map(s =>
      (s.namespace, s.name, workspace.owner(s))).distinct.size == 1
    if (!DbtSyntax.isIdentifier(newName) || candidates.size != 1 && !family)
      throw new IncorrectOperationException("Ambiguous reference '" + label + "' cannot be renamed: " + candidates.map(_.toString).mkString(", "))
    val document = PsiDocumentManager.getInstance(file.getProject).getDocument(file)
    document.replaceString(getRangeInElement.getStartOffset, getRangeInElement.getEndOffset, newName)
    PsiDocumentManager.getInstance(file.getProject).commitDocument(document)
    file
  }

  override def isReferenceTo(element: PsiElement): Boolean = candidates.exists { candidate =>
    (candidate, element) match {
      case (a: DbtSymbolElement, _) if a.symbol.kind == "documentation" => false
      case (a: DbtSymbolElement, b: DbtSymbolElement) if b.symbol.isProperty && !a.symbol.isProperty =>
        a.isValid && b.isValid && DbtWorkspace.get(a.getProject).properties(a.symbol).exists(p =>
          p.file == b.getContainingFile && p.kind == b.symbol.kind && p.name == b.symbol.name &&
            p.version == b.symbol.version && p.element.getTextRange == b.getTextRange)
      case (a: DbtSymbolElement, b: DbtSymbolElement) => a.isValid && b.isValid && a.getContainingFile == b.getContainingFile &&
        (a.getTextRange == b.getTextRange || (a.symbol.isCode && b.symbol.isCode)) && a.symbol.kind == b.symbol.kind && a.symbol.name == b.symbol.name &&
        a.symbol.namespace == b.symbol.namespace && a.symbol.owner == b.symbol.owner && a.symbol.version == b.symbol.version
      case _ => candidate == element || candidate.getManager.areElementsEquivalent(candidate, element)
    }
  }
}

/** The references of dbt files, cached until PSI or VFS changes. */
private[dbt] object DbtNavigation {
  def withPrefix(text: String, end: Int): Option[String] = DbtReferenceProvider.first(_.withPrefix(text, end))

  def projectedColumns(sql: String): Option[(Vector[String], Boolean)] = DbtReferenceProvider.first(_.projectedColumns(sql))

  def relationRanges(sql: String): Vector[TextRange] =
    DbtReferenceProvider.EP.getExtensionList.asScala.toVector.flatMap(_.relationRanges(sql)).distinct

  private val ProjectFiles = Set(DbtWorkspace.ProjectFileName, "profiles.yml", "packages.yml", "package-lock.yml", "dependencies.yml",
    "selectors.yml", "vars.yml")
  private val ReferenceCache = Key.create[CachedValue[Vector[DbtNavigationReference]]]("dbt.navigation.references")

  /** All references of a file; selections expand graph operators only when `includeSelections` is set. */
  def inFile(file: PsiFile, includeSelections: Boolean = true): Vector[DbtNavigationReference] =
    if (!file.isPhysical || file.getVirtualFile == null) Vector.empty
    else if (!includeSelections) compute(file, includeSelections = false)
    else CachedValuesManager.getManager(file.getProject).getCachedValue(file, ReferenceCache,
      () => CachedValueProvider.Result.create(compute(file, includeSelections = true),
        PsiModificationTracker.MODIFICATION_COUNT, VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS, DbtProjectSettings.get(file.getProject)), false)

  /** The innermost references at an offset. */
  def at(file: PsiFile, offset: Int): Vector[DbtNavigationReference] = {
    val matches = inFile(file).filter(_.getRangeInElement.containsOffset(offset))
    matches.map(_.getRangeInElement.getLength).minOption.toVector.flatMap(size => matches.filter(_.getRangeInElement.getLength == size))
  }

  /** Declarations resolved by the innermost references at an offset. */
  def resolvedAt(file: PsiFile, offset: Int): Vector[DbtSymbol] =
    at(file, offset).flatMap(_.candidates).collect { case e: DbtSymbolElement => e.symbol }

  private def compute(file: PsiFile, includeSelections: Boolean): Vector[DbtNavigationReference] = {
    val workspace = DbtWorkspace.get(file.getProject)
    workspace.root(file).filter(navigable(file, _)).fold(Vector.empty[DbtNavigationReference]) { root =>
      val found = DbtReferenceProvider.EP.getExtensionList.asScala.foldLeft(Vector.empty[DbtNavigationReference]) { (found, provider) =>
        found ++ provider.references(DbtNavigationContext(file, workspace, root, includeSelections, found))
      }
      found.groupBy(r => (r.getRangeInElement, r.candidates)).valuesIterator.map(_.head).toVector.sortBy(_.getRangeInElement.getStartOffset)
    }
  }

  private def navigable(file: PsiFile, r: DbtRoot): Boolean = !r.excluded(file.getVirtualFile) && (r.resource(file) ||
    r.isSelectionFile(file) || ProjectFiles(file.getName) || r.isFile(file, r.profilesFile))
}

/** Parser-independent SQL evidence shared by editor features and lineage. Unknown is never an empty, known schema. */
private[dbt] final case class DbtSqlColumn(name: String, range: TextRange, inputs: Vector[DbtSymbol],
  direct: Boolean, expression: String, complete: Boolean, dataType: Option[String] = None, outputAlias: Boolean = false,
  contextInputs: Vector[(DbtSymbol, String)] = Vector.empty)

private[dbt] final case class DbtSqlProblem(range: TextRange, message: String)

private[dbt] final case class DbtSqlSuppressionComment(range: TextRange, ids: String, file: Boolean)

private[dbt] final case class DbtSqlSuppressionStatement(range: TextRange, first: Int)

private[dbt] final case class DbtSqlSuppressionLayout(statements: Vector[DbtSqlSuppressionStatement], comments: Vector[DbtSqlSuppressionComment])

private[dbt] final case class DbtSqlScope(range: TextRange, relations: Vector[(String, Vector[DbtSymbol])], complete: Boolean)

private[dbt] final case class DbtCte(name: String, range: TextRange, body: TextRange, dependencies: Vector[String], sql: String)

private[dbt] final case class DbtSqlAnalysis(columns: Vector[DbtSqlColumn], scopes: Vector[DbtSqlScope],
  ctes: Vector[DbtCte], wildcards: Vector[(TextRange, Vector[String])], issues: Vector[String],
  intermediates: Vector[DbtSqlColumn] = Vector.empty, problems: Vector[DbtSqlProblem] = Vector.empty,
  deletions: Vector[(TextRange, TextRange)] = Vector.empty, declarations: Vector[DbtSymbol] = Vector.empty,
  wildcardProblems: Vector[DbtSqlProblem] = Vector.empty) {
  def scope(offset: Int): Option[DbtSqlScope] = scopes.filter(_.range.containsOffset(offset)).sortBy(_.range.getLength).headOption
}

private[dbt] object DbtSqlAnalysis {
  def suppressionLayout(file: PsiFile): DbtSqlSuppressionLayout =
    DbtReferenceProvider.first(_.sqlSuppressionLayout(file)).getOrElse(DbtSqlSuppressionLayout(Vector.empty, Vector.empty))

  def forCompletion(w: DbtWorkspace, file: PsiFile, offset: Int): Option[DbtSqlAnalysis] =
    DbtReferenceProvider.first(_.sqlAnalysis(file, w, Some(offset)))

  private val Cache = Key.create[ConcurrentHashMap[String, DbtSqlAnalysis]]("dbt.sql.analysis")
  private val active = new ThreadLocal[Set[String]] {
    override def initialValue(): Set[String] = Set.empty
  }

  def get(w: DbtWorkspace, file: PsiFile): Option[DbtSqlAnalysis] = {
    ProgressManager.checkCanceled()
    val path = Option(file.getVirtualFile).map(_.getPath).getOrElse("")
    if (path.isEmpty || active.get()(path) || active.get().size >= 32) None
    else {
      val cache = w.synchronized {
        Option(w.getUserData(Cache)).getOrElse {
          val created = new ConcurrentHashMap[String, DbtSqlAnalysis]()
          w.putUserData(Cache, created);
          created
        }
      }
      Option(cache.get(path)).orElse {
        val previous = active.get();
        active.set(previous + path)
        try {
          val result = DbtReferenceProvider.first(_.sqlAnalysis(file, w))
          result.foreach(cache.put(path, _));
          result
        } finally active.set(previous)
      }
    }
  }
}
