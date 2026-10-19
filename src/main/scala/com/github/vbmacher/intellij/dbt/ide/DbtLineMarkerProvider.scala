package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.lineage.DbtLineage
import com.github.vbmacher.intellij.dbt.navigation.DbtNavigation
import com.github.vbmacher.intellij.dbt.project.{DbtIcons, DbtProjectSettings, DbtSymbolElement, DbtWorkspace}
import com.intellij.codeInsight.daemon.{LineMarkerInfo, LineMarkerProviderDescriptor}
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.{Key, TextRange}
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.{PsiElement, PsiFile}
import com.intellij.psi.util.{CachedValue, CachedValueProvider, CachedValuesManager, PsiModificationTracker}
import com.intellij.ui.awt.RelativePoint

import java.awt.event.MouseEvent
import java.util.{Collection, List => JList}
import scala.jdk.CollectionConverters._

/** One navigation marker per line, anchored to a real leaf even in plain-text SQL. */
final class DbtLineMarkerProvider extends LineMarkerProviderDescriptor {
  override def getName: String = "dbt navigation"
  override def getIcon: javax.swing.Icon = DbtIcons.Gutter
  override def getLineMarkerInfo(element: PsiElement): LineMarkerInfo[_] = null

  override def collectSlowLineMarkers(elements: JList[_ <: PsiElement], result: Collection[_ >: LineMarkerInfo[_]]): Unit = {
    val leaves: Set[PsiElement] = elements.asScala.iterator
      .filter(e => e.getFirstChild == null && !e.isInstanceOf[PsiFile]).map(e => e: PsiElement).toSet
    leaves.toVector.map(_.getContainingFile).distinct.filter(_ != null).foreach { file =>
      DbtLineMarkerProvider.markers(file).foreach { marker =>
        Option(file.findElementAt(marker.range.getStartOffset)).filter(leaves).foreach { anchor =>
          result.add(new LineMarkerInfo[PsiElement](anchor, marker.range, getIcon,
            (_: PsiElement) => marker.tooltip,
            (event: MouseEvent, _: PsiElement) => DbtLineMarkerProvider.show(event, marker),
            GutterIconRenderer.Alignment.RIGHT, () => marker.tooltip))
        }
      }
    }
  }
}

private[ide] object DbtLineMarkerProvider {
  private[ide] final case class Marker(range: TextRange, choices: Vector[DbtNavigationItem], tooltip: String)
  private val MarkerCache = Key.create[CachedValue[Vector[Marker]]]("dbt.gutter.markers")
  private val BlockKinds = Set("macro", "generic_test", "doc", "materialization")

  private[ide] def markers(file: PsiFile): Vector[Marker] =
    if (!file.isPhysical || file.getVirtualFile == null) Vector.empty
    else CachedValuesManager.getManager(file.getProject).getCachedValue(file, MarkerCache,
      () => CachedValueProvider.Result.create(compute(file),
        PsiModificationTracker.MODIFICATION_COUNT, VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS, DbtProjectSettings.get(file.getProject)), false)

  private def targets(symbols: Vector[DbtSymbolElement], heading: String): Vector[DbtNavigationItem] =
    symbols.distinct.zipWithIndex.map { case (target, index) =>
      DbtNavigationItem.declaration(target, if (index == 0) Some(heading) else None)
    }

  private def compute(file: PsiFile): Vector[Marker] = {
    // Most editor files need no markers or project-wide dependency scan.
    if (DbtWorkspace.declarationsInFile(file).isEmpty) return Vector.empty
    val workspace = DbtWorkspace.get(file.getProject)
    if (workspace.root(file).forall(_.excluded(file.getVirtualFile))) return Vector.empty
    val root = workspace.root(file)
    val references = DbtNavigation.inFile(file)
    lazy val dependencies = DbtLineage.edges(workspace)
    val declarations = workspace.symbols.filter(s => s.file == file && !s.metadata && s.isEnabled &&
      (s.isCode || s.isProperty || BlockKinds(s.kind) || s.kind == "source_table") &&
      (!s.isCode || root.exists(r => workspace.declarations(Set(s.kind), s.name, r, Some(s.namespace)).exists(_.id == s.id))))
    val firstContent = file.getText.indexWhere(c => !c.isWhitespace)
    val resourceMarkers = declarations.flatMap { symbol =>
      ProgressManager.checkCanceled()
      val offset = if (symbol.isCode && symbol.range.isEmpty) firstContent else symbol.range.getStartOffset
      val properties = if (symbol.isProperty) Vector.empty else workspace.properties(symbol).map(_.element)
      val definitions = if (!symbol.isProperty) Vector.empty else root.toVector
        .flatMap(r => workspace.declarations(Set(symbol.kind), symbol.name, r, Some(symbol.namespace)))
        .filter(s => (s.isCode || BlockKinds(s.kind)) && s.sameResource(symbol) &&
          workspace.root(s.file) == workspace.root(file)).map(_.element)
      val related = targets(properties, "Properties") ++ targets(definitions, "Definition") ++
        DbtDependenciesAction.choices(dependencies, Set(symbol.id))
      if (offset < 0 || related.isEmpty) Vector.empty
      else Vector(Marker(new TextRange(offset, math.max(offset + 1, symbol.range.getEndOffset)), related,
        "dbt: " + related.flatMap(_.separator).distinct.mkString(", ")))
    }
    val referenceMarkers = references.flatMap { reference =>
      val candidates = reference.candidates.collect { case e: DbtSymbolElement if e.symbol.kind != "documentation" => e }
      val metadata = candidates.filter(_.symbol.metadata)
      val labels = (if (reference.candidates.size > 1) Vector("Multiple definitions") else Vector.empty) ++
        (if (metadata.exists(_.symbol.isStale)) Vector("Stale metadata") else if (metadata.nonEmpty) Vector("Metadata target") else Vector.empty)
      if (labels.isEmpty || candidates.isEmpty) Vector.empty
      else Vector(Marker(reference.getRangeInElement, targets(candidates, labels.mkString(", ")), "dbt: " + labels.mkString(", ")))
    }
    Option(file.getViewProvider.getDocument).toVector.flatMap { document =>
      (resourceMarkers ++ referenceMarkers).groupBy(m => document.getLineNumber(m.range.getStartOffset))
        .toVector.sortBy(_._1).flatMap { case (_, onLine) =>
          val sorted = onLine.sortBy(_.range.getStartOffset)
          sorted.headOption.map(first => first.copy(choices = sorted.flatMap(_.choices).distinct,
            tooltip = "dbt: " + sorted.map(_.tooltip.stripPrefix("dbt: ")).distinct.mkString("; ")))
        }
    }
  }

  private def show(event: MouseEvent, marker: Marker): Unit = {
    val valid = marker.choices.filter(_.target.isValid)
    if (valid.nonEmpty) DbtNavigationItem.popup("dbt navigation", valid).show(new RelativePoint(event))
  }
}
