package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.lineage.{DbtColumnEdge, DbtColumnLineage, DbtLineage}
import com.github.vbmacher.intellij.dbt.navigation.{DbtNavigation, DbtSqlAnalysis}
import com.github.vbmacher.intellij.dbt.project._
import com.intellij.codeInsight.daemon.{LineMarkerInfo, LineMarkerProviderDescriptor}
import com.intellij.ide.IconProvider
import com.intellij.ide.hierarchy._
import com.intellij.ide.structureView._
import com.intellij.ide.util.treeView.NodeDescriptor
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.lang.{Language, PsiStructureViewFactory}
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.{DumbAware, Project}
import com.intellij.openapi.roots.ui.util.CompositeAppearance
import com.intellij.openapi.util.{Key, TextRange}
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.{PsiElement, PsiFile}
import com.intellij.psi.util.{CachedValue, CachedValueProvider, CachedValuesManager, PsiModificationTracker}
import com.intellij.ui.awt.RelativePoint

import java.awt.event.MouseEvent
import java.util.{Collection, List => JList}
import javax.swing.{Icon, JTree}
import scala.jdk.CollectionConverters._

/** One navigation marker per line, anchored to a real leaf even in plain-text SQL. */
final class DbtLineMarkerProvider extends LineMarkerProviderDescriptor {
  override def getName: String = "dbt navigation"

  override def getIcon: Icon = DbtIcons.Gutter

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

final class DbtHierarchyProvider extends HierarchyProvider {
  override def getTarget(context: DataContext): PsiElement = DbtEditorAction.caret(context).flatMap { case (project, file, offset) =>
    val w = DbtWorkspace.get(project)
    val resolved = file.toVector.flatMap(DbtNavigation.resolvedAt(_, offset)).filter(s => DbtSymbol.ColumnKinds(s.kind)).distinctBy(_.id)
    if (resolved.size > 1) None else resolved.headOption.orElse(
        file.flatMap(f => w.symbolAt(f, offset).find(s => DbtSymbol.ColumnKinds(s.kind))))
      .orElse(DbtResourceAction.at(w, file, offset)).map(_.element)
  }.orNull

  override def createHierarchyBrowser(target: PsiElement): HierarchyBrowser = new DbtHierarchyBrowser(target.getProject, target)

  override def browserActivated(browser: HierarchyBrowser): Unit = browser.asInstanceOf[DbtHierarchyBrowser].changeView(CallHierarchyBrowserBase.getCalleeType)
}

private[ide] final class DbtHierarchyBrowser(project: Project, target: PsiElement) extends CallHierarchyBrowserBase(project, target) {
  override protected def createTrees(trees: java.util.Map[_ >: String, _ >: JTree]): Unit = {
    trees.put(CallHierarchyBrowserBase.getCallerType, createTree(false))
    trees.put(CallHierarchyBrowserBase.getCalleeType, createTree(false))
  }

  override protected def getPresentableNameMap: java.util.Map[String, java.util.function.Supplier[String]] = {
    val names = new java.util.LinkedHashMap[String, java.util.function.Supplier[String]]
    names.put(CallHierarchyBrowserBase.getCalleeType, () => "Upstream dependencies")
    names.put(CallHierarchyBrowserBase.getCallerType, () => "Downstream consumers")
    names
  }

  override protected def getElementFromDescriptor(descriptor: HierarchyNodeDescriptor): PsiElement = descriptor.getPsiElement

  override protected def isApplicableElement(element: PsiElement): Boolean = element.isInstanceOf[DbtSymbolElement]

  override protected def getComparator: java.util.Comparator[NodeDescriptor[_]] =
    (a: NodeDescriptor[_], b: NodeDescriptor[_]) => a.toString.compareToIgnoreCase(b.toString)

  override protected def createHierarchyTreeStructure(view: String, element: PsiElement): HierarchyTreeStructure = element match {
    case target: DbtSymbolElement => new DbtHierarchyTree(project, target.symbol, view == CallHierarchyBrowserBase.getCallerType)
    case _ => null
  }
}

private[ide] final class DbtHierarchyNode(project: Project, parent: DbtHierarchyNode, val symbol: DbtSymbol,
  val path: Set[String], relation: String = "") extends HierarchyNodeDescriptor(project, parent, symbol.element, parent == null) {
  override def update(): Boolean = {
    myHighlightedText = new CompositeAppearance
    myHighlightedText.getEnding.addText(symbol.namespace + "." + Option(symbol.owner).filter(_.nonEmpty).map(_ + ".").getOrElse("") + symbol.name +
      (if (relation.isEmpty) "" else " · " + relation))
    myName = myHighlightedText.getText
    setIcon(symbol.element.getIcon(0));
    true
  }
}

private[ide] final class DbtHierarchyTree(project: Project, symbol: DbtSymbol, downstream: Boolean)
  extends HierarchyTreeStructure(project, new DbtHierarchyNode(project, null, symbol, Set(symbol.id))) {
  override protected def buildChildren(descriptor: HierarchyNodeDescriptor): Array[AnyRef] = descriptor match {
    case node: DbtHierarchyNode =>
      val w = DbtWorkspace.get(project)
      DbtHierarchy.related(w, node.symbol, downstream).filterNot(p => node.path(p._1.id)).map { case (next, relation) =>
        new DbtHierarchyNode(project, node, next, node.path + next.id, relation): AnyRef
      }.toArray
    case _ => Array.empty
  }
}

private[ide] object DbtHierarchy {
  def related(w: DbtWorkspace, symbol: DbtSymbol, downstream: Boolean): Vector[(DbtSymbol, String)] = {
    def label(edge: DbtColumnEdge): String =
      edge.kind + (if (edge.kind == "value" && edge.direct) " · direct passthrough" else " · derived") +
        (if (edge.complete) "" else " · partial analysis") + (if (edge.expression.isEmpty) "" else " · " + edge.expression)

    if (DbtSymbol.ColumnKinds(symbol.kind)) {
      val resource = w.symbols.find(s => DbtSymbol.RelationKinds(s.kind) && s.namespace == symbol.namespace &&
          s.relationName == symbol.owner && w.owner(s) == w.owner(symbol))
        .orElse(w.model(symbol.file))
      DbtColumnLineage.edges(w).flatMap { edge =>
        if (!downstream && resource.exists(_.sameResource(edge.resource)) && edge.column.equalsIgnoreCase(symbol.name))
          Vector(edge.input -> label(edge))
        else if (downstream && edge.input.namespace == symbol.namespace && edge.input.name.equalsIgnoreCase(symbol.name) &&
          edge.input.owner == symbol.owner && edge.input.version == symbol.version && w.owner(edge.input) == w.owner(symbol)) {
          val column = w.columns(edge.resource).find(_.name.equalsIgnoreCase(edge.column)).orElse(
            DbtSqlAnalysis.get(w, edge.resource.file).flatMap(_.columns.find(_.name.equalsIgnoreCase(edge.column))).map(c =>
              DbtSymbol("sql_projection", c.name, edge.resource.namespace, edge.resource.file, c.range, owner = edge.resource.name)))
          column.toVector.map(_ -> label(edge))
        } else Vector.empty
      }.distinctBy(p => (p._1.id, p._2))
    } else DbtLineage.edges(w).flatMap { edge =>
      val from = if (downstream) edge.to else edge.from
      if (from.sameResource(symbol) && w.owner(from) == w.owner(symbol))
        Vector((if (downstream) edge.from else edge.to) -> (if (edge.ambiguous) "candidate" else ""))
      else Vector.empty
    }.distinctBy(_._1.id)
  }
}

final class DbtSqlStructureViewFactory extends PsiStructureViewFactory {
  override def getStructureViewBuilder(file: PsiFile): StructureViewBuilder = {
    if (DbtWorkspace.get(file.getProject).model(file).isEmpty) null
    else new TreeBasedStructureViewBuilder {
      override def createStructureViewModel(editor: Editor): StructureViewModel =
        new StructureViewModelBase(file, editor, new DbtSqlStructureElement(file, None)) {
          override def getCurrentEditorElement: AnyRef = {
            val workspace = DbtWorkspace.get(file.getProject)
            val cte = for {
              model <- workspace.model(file)
              analysis <- DbtSqlAnalysis.get(workspace, file)
              selected <- Option(editor).flatMap(e => DbtCteInspection.at(workspace, file, analysis, e.getCaretModel.getOffset))
            } yield DbtSymbol("cte", selected.name, model.namespace, file, selected.range).element
            cte.getOrElse(file)
          }
        }
    }
  }
}

private[ide] final class DbtSqlStructureElement(file: PsiFile, symbol: Option[DbtSymbol]) extends StructureViewTreeElement {
  override def getValue: AnyRef = symbol.map(_.element).getOrElse(file)

  override def getPresentation: ItemPresentation = symbol.map(_.element.getPresentation).getOrElse(new ItemPresentation {
    override def getPresentableText: String = file.getName

    override def getLocationString: String = null

    override def getIcon(open: Boolean): Icon = file.getIcon(0)
  })

  override def getChildren: Array[TreeElement] = {
    val w = DbtWorkspace.get(file.getProject)
    val nodes = for {
      model <- w.model(file).toVector
      analysis <- DbtSqlAnalysis.get(w, file).toVector
      cte <- analysis.ctes if symbol.fold(!analysis.ctes.exists(p => p.body.contains(cte.range)))(parent =>
        analysis.ctes.find(_.range == parent.range).exists(p => p.body.contains(cte.range)) &&
          !analysis.ctes.exists(p => p.range != parent.range && parent.range.contains(p.range) && p.body.contains(cte.range)))
    } yield new DbtSqlStructureElement(file, Some(DbtSymbol("cte", cte.name, model.namespace, file, cte.range))): TreeElement
    nodes.toArray
  }

  override def canNavigate: Boolean = symbol.exists(_.element.canNavigate)

  override def canNavigateToSource: Boolean = canNavigate

  override def navigate(requestFocus: Boolean): Unit = symbol.foreach(_.element.navigate(requestFocus))
}

/** Extend a SQL plugin's existing outline rather than replacing its structure provider. */
final class DbtSqlStructureViewExtension extends StructureViewExtension {
  override def getType: Class[_ <: PsiElement] = classOf[PsiFile]

  override def getChildren(element: PsiElement): Array[StructureViewTreeElement] = element match {
    case file: PsiFile if Option(Language.findLanguageByID("SQL")).exists(file.getLanguage.isKindOf) &&
      DbtWorkspace.get(file.getProject).model(file).nonEmpty =>
      new DbtSqlStructureElement(file, None).getChildren.map(_.asInstanceOf[StructureViewTreeElement])
    case _ => Array.empty
  }

  override def getCurrentEditorElement(editor: Editor, parent: PsiElement): AnyRef = parent match {
    case file: PsiFile => getChildren(file).map(_.getValue).collectFirst {
      case e: DbtSymbolElement if e.symbol.range.containsOffset(editor.getCaretModel.getOffset) => e
    }.orNull
    case _ => null
  }
}

/** File icons use local dbt declarations; mixed property files keep their file-type icon. */
final class DbtIconProvider extends IconProvider with DumbAware {
  override def getIcon(element: PsiElement, flags: Int): Icon = element match {
    case file: PsiFile =>
      val symbols = DbtWorkspace.declarationsInFile(file)
      val code = symbols.filter(_.isCode)
      val resources = if (code.nonEmpty) code else symbols.filter(_.owner.isEmpty)
      val icons = resources.map(s => DbtSymbolElement.icon(if (s.isTestOnlyProperty) "test" else s.kind)).distinct
      if (icons.size == 1) icons.head else null
    case _ => null
  }
}
