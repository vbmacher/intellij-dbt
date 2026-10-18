package com.github.vbmacher.intellij.dbt.navigation

import com.github.vbmacher.intellij.dbt.project.{DbtProjectSettings, DbtRoot, DbtWorkspace}
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.util.{Key, TextRange}
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiFile
import com.intellij.psi.util.{CachedValue, CachedValueProvider, CachedValuesManager, PsiModificationTracker}

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
  def projectedColumns(sql: String): Option[(Vector[String], Boolean)] = None
  def withPrefix(text: String, end: Int): Option[String] = None
}

private[dbt] object DbtReferenceProvider {
  val EP: ExtensionPointName[DbtReferenceProvider] = ExtensionPointName.create("com.github.vbmacher.intellij-dbt.referenceProvider")
}

/** The references of dbt files, cached until PSI or VFS changes. */
private[dbt] object DbtNavigation {
  def withPrefix(text: String, end: Int): Option[String] =
    DbtReferenceProvider.EP.getExtensionList.asScala.iterator.flatMap(_.withPrefix(text, end)).nextOption()
  def projectedColumns(sql: String): Option[(Vector[String], Boolean)] =
    DbtReferenceProvider.EP.getExtensionList.asScala.iterator.flatMap(_.projectedColumns(sql)).nextOption()
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
