package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.lineage.{DbtColumnLineage, DbtLineage}
import com.github.vbmacher.intellij.dbt.navigation.DbtSqlAnalysis
import com.github.vbmacher.intellij.dbt.project._
import com.intellij.ide.hierarchy._
import com.intellij.ide.util.treeView.NodeDescriptor
import com.intellij.openapi.actionSystem.{CommonDataKeys, DataContext}
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import javax.swing.JTree
import scala.jdk.CollectionConverters._

final class DbtHierarchyProvider extends HierarchyProvider {
  override def getTarget(context: DataContext): PsiElement = Option(CommonDataKeys.PROJECT.getData(context)).flatMap { project =>
    val w = DbtWorkspace.get(project)
    val file = Option(CommonDataKeys.PSI_FILE.getData(context))
    val offset = Option(CommonDataKeys.EDITOR.getData(context)).map(_.getCaretModel.getOffset).getOrElse(0)
    val resolved = file.toVector.flatMap(f => com.github.vbmacher.intellij.dbt.navigation.DbtNavigation.at(f, offset))
      .flatMap(_.candidates).collect { case e: DbtSymbolElement if Set("column", "sql_projection")(e.symbol.kind) => e.symbol }.distinctBy(_.id)
    if (resolved.size > 1) None else resolved.headOption.orElse(
      file.flatMap(f => w.symbolAt(f, offset).find(s => Set("column", "sql_projection")(s.kind))))
      .orElse(DbtImpactUi.context(w, file, offset)).map(_.element)
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
    myHighlightedText = new com.intellij.openapi.roots.ui.util.CompositeAppearance
    myHighlightedText.getEnding.addText(symbol.namespace + "." + Option(symbol.owner).filter(_.nonEmpty).map(_ + ".").getOrElse("") + symbol.name +
      (if (relation.isEmpty) "" else " · " + relation))
    myName = myHighlightedText.getText
    setIcon(symbol.element.getIcon(0)); true
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
    def label(edge: com.github.vbmacher.intellij.dbt.lineage.DbtColumnEdge): String =
      edge.kind + (if (edge.kind == "value" && edge.direct) " · direct passthrough" else " · derived") +
        (if (edge.complete) "" else " · partial analysis") + (if (edge.expression.isEmpty) "" else " · " + edge.expression)

    if (Set("column", "sql_projection")(symbol.kind)) {
      val resource = w.symbols.find(s => DbtSymbol.RelationKinds(s.kind) && s.namespace == symbol.namespace &&
        s.relationName == symbol.owner && w.owner(s) == w.owner(symbol))
        .orElse(w.codeSymbols(symbol.file).find(_.kind == "model"))
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
