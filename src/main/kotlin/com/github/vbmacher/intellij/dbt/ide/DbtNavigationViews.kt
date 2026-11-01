package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.lineage.*
import com.github.vbmacher.intellij.dbt.navigation.DbtNavigation
import com.github.vbmacher.intellij.dbt.navigation.DbtSqlAnalysis
import com.github.vbmacher.intellij.dbt.project.*
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.ide.IconProvider
import com.intellij.ide.hierarchy.*
import com.intellij.ide.structureView.*
import com.intellij.ide.util.treeView.NodeDescriptor
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.lang.Language
import com.intellij.lang.PsiStructureViewFactory
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ui.util.CompositeAppearance
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.ui.awt.RelativePoint
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JTree

/** One navigation marker per line, anchored to a real leaf even in plain-text SQL. */
class DbtLineMarkerProvider : LineMarkerProviderDescriptor() {
    override fun getName() = "dbt navigation"
    override fun getIcon(): Icon = DbtIcons.Gutter
    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null
    override fun collectSlowLineMarkers(elements: List<PsiElement>, result: MutableCollection<in LineMarkerInfo<*>>) {
        val leaves = elements.filter { it.firstChild == null && it !is PsiFile }.toSet()
        leaves.mapNotNull { it.containingFile }.distinct().forEach { file ->
            markers(file).forEach { marker ->
                file.findElementAt(marker.range.startOffset)?.takeIf { it in leaves }?.let { anchor ->
                    result.add(
                        LineMarkerInfo(
                            anchor, marker.range, icon, { marker.tooltip },
                            { event, _ -> show(event, marker) }, GutterIconRenderer.Alignment.RIGHT, { marker.tooltip })
                    )
                }
            }
        }
    }

    internal data class Marker(val range: TextRange, val choices: List<DbtNavigationItem>, val tooltip: String)
    companion object {
        private val MarkerCache = Key.create<CachedValue<List<Marker>>>("dbt.gutter.markers")
        private val BlockKinds = setOf("macro", "generic_test", "doc", "materialization")
        internal fun markers(file: PsiFile): List<Marker> =
            if (!file.isPhysical || file.virtualFile == null) emptyList() else
                CachedValuesManager.getManager(file.project).getCachedValue(file, MarkerCache, {
                    CachedValueProvider.Result.create(
                        compute(file), PsiModificationTracker.MODIFICATION_COUNT,
                        VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS, DbtProjectSettings.get(file.project)
                    )
                }, false)

        private fun targets(symbols: List<DbtSymbolElement>, heading: String) =
            symbols.distinct().mapIndexed { index, target ->
                DbtNavigationItem.declaration(target, if (index == 0) heading else null)
            }

        private fun compute(file: PsiFile): List<Marker> {
            if (DbtWorkspace.declarationsInFile(file).isEmpty()) return emptyList()
            val workspace = DbtWorkspace.get(file.project)
            val root = workspace.root(file)?.takeIf { !it.excluded(file.virtualFile) } ?: return emptyList()
            val references = DbtNavigation.inFile(file)
            val dependencies by lazy { DbtLineage.edges(workspace) }
            val declarations = workspace.symbols.filter { s ->
                s.file == file && !s.metadata && s.isEnabled &&
                        (s.isCode || s.isProperty || s.kind in BlockKinds || s.kind == "source_table") &&
                        (!s.isCode || workspace.declarations(setOf(s.kind), s.name, root, s.namespace)
                            .any { it.id == s.id })
            }
            val firstContent = file.text.indexOfFirst { !it.isWhitespace() }
            val resourceMarkers = declarations.mapNotNull { symbol ->
                ProgressManager.checkCanceled()
                val offset = if (symbol.isCode && symbol.range.isEmpty) firstContent else symbol.range.startOffset
                val properties = if (symbol.isProperty) emptyList() else workspace.properties(symbol).map { it.element }
                val definitions = if (!symbol.isProperty) emptyList() else workspace.declarations(
                    setOf(symbol.kind),
                    symbol.name,
                    root,
                    symbol.namespace
                )
                    .filter {
                        (it.isCode || it.kind in BlockKinds) && it.sameResource(symbol) && workspace.root(it.file) == workspace.root(
                            file
                        )
                    }.map { it.element }
                val related = targets(properties, "Properties") + targets(
                    definitions,
                    "Definition"
                ) + DbtDependenciesAction.choices(dependencies, setOf(symbol.id))
                if (offset < 0 || related.isEmpty()) null else Marker(
                    TextRange(
                        offset,
                        maxOf(offset + 1, symbol.range.endOffset)
                    ), related,
                    "dbt: " + related.mapNotNull { it.separator }.distinct().joinToString(", ")
                )
            }
            val referenceMarkers = references.mapNotNull { reference ->
                val candidates = reference.candidates.filterIsInstance<DbtSymbolElement>()
                    .filter { it.symbol.kind != "documentation" }
                val metadata = candidates.filter { it.symbol.metadata }
                val labels = (if (reference.candidates.size > 1) listOf("Multiple definitions") else emptyList()) +
                        (if (metadata.any { it.symbol.isStale }) listOf("Stale metadata") else if (metadata.isNotEmpty()) listOf(
                            "Metadata target"
                        ) else emptyList())
                if (labels.isEmpty() || candidates.isEmpty()) null else Marker(
                    reference.rangeInElement,
                    targets(candidates, labels.joinToString(", ")),
                    "dbt: " + labels.joinToString(", ")
                )
            }
            val document = file.viewProvider.document ?: return emptyList()
            return (resourceMarkers + referenceMarkers).groupBy { document.getLineNumber(it.range.startOffset) }
                .toSortedMap().values.mapNotNull { onLine ->
                val sorted = onLine.sortedBy { it.range.startOffset }
                sorted.firstOrNull()?.copy(
                    choices = sorted.flatMap { it.choices }.distinct(),
                    tooltip = "dbt: " + sorted.map { it.tooltip.removePrefix("dbt: ") }.distinct().joinToString("; ")
                )
            }
        }

        private fun show(event: MouseEvent, marker: Marker) {
            val valid = marker.choices.filter { it.target.isValid }
            if (valid.isNotEmpty()) DbtNavigationItem.popup("dbt navigation", valid).show(RelativePoint(event))
        }
    }
}

class DbtHierarchyProvider : HierarchyProvider {
    override fun getTarget(context: DataContext): PsiElement? {
        val (project, file, offset) = DbtEditorAction.caret(context) ?: return null
        val w = DbtWorkspace.get(project)
        val resolved = file?.let { DbtNavigation.resolvedAt(it, offset) }?.filter { it.kind in DbtSymbol.ColumnKinds }
            ?.distinctBy { it.id } ?: emptyList()
        if (resolved.size > 1) return null
        return (resolved.firstOrNull() ?: file?.let {
            w.symbolAt(it, offset).firstOrNull { s -> s.kind in DbtSymbol.ColumnKinds }
        }
        ?: DbtResourceAction.at(w, file, offset))?.element
    }

    override fun createHierarchyBrowser(target: PsiElement): HierarchyBrowser =
        DbtHierarchyBrowser(target.project, target)

    override fun browserActivated(browser: HierarchyBrowser) {
        (browser as DbtHierarchyBrowser).changeView(CallHierarchyBrowserBase.getCalleeType())
    }
}

internal class DbtHierarchyBrowser(private val project: Project, target: PsiElement) :
    CallHierarchyBrowserBase(project, target) {
    override fun createTrees(trees: MutableMap<in String, in JTree>) {
        trees[CallHierarchyBrowserBase.getCallerType()] = createTree(false)
        trees[CallHierarchyBrowserBase.getCalleeType()] = createTree(false)
    }

    override fun getPresentableNameMap(): Map<String, java.util.function.Supplier<String>> = linkedMapOf(
        CallHierarchyBrowserBase.getCalleeType() to java.util.function.Supplier { "Upstream dependencies" },
        CallHierarchyBrowserBase.getCallerType() to java.util.function.Supplier { "Downstream consumers" })

    override fun getElementFromDescriptor(descriptor: HierarchyNodeDescriptor): PsiElement? = descriptor.psiElement
    override fun isApplicableElement(element: PsiElement) = element is DbtSymbolElement
    override fun getComparator(): Comparator<NodeDescriptor<*>> =
        Comparator { a, b -> a.toString().compareTo(b.toString(), ignoreCase = true) }

    override fun createHierarchyTreeStructure(view: String, element: PsiElement): HierarchyTreeStructure? =
        (element as? DbtSymbolElement)?.let {
            DbtHierarchyTree(
                project,
                it.symbol,
                view == CallHierarchyBrowserBase.getCallerType()
            )
        }
}

internal class DbtHierarchyNode(
    project: Project, parent: DbtHierarchyNode?, val symbol: DbtSymbol,
    val path: Set<String>, private val relation: String = ""
) : HierarchyNodeDescriptor(project, parent, symbol.element, parent == null) {
    override fun update(): Boolean {
        myHighlightedText = CompositeAppearance()
        myHighlightedText.ending.addText(symbol.namespace + "." + symbol.owner.takeIf { it.isNotEmpty() }
            ?.let { "$it." }.orEmpty() + symbol.name + if (relation.isEmpty()) "" else " · $relation")
        myName = myHighlightedText.text
        setIcon(symbol.element.getIcon(0))
        return true
    }
}

internal class DbtHierarchyTree(private val project: Project, symbol: DbtSymbol, private val downstream: Boolean) :
    HierarchyTreeStructure(project, DbtHierarchyNode(project, null, symbol, setOf(symbol.id))) {
    override fun buildChildren(descriptor: HierarchyNodeDescriptor): Array<Any> =
        (descriptor as? DbtHierarchyNode)?.let { node ->
            DbtHierarchy.related(DbtWorkspace.get(project), node.symbol, downstream)
                .filter { it.first.id !in node.path }
                .map { (next, relation) -> DbtHierarchyNode(project, node, next, node.path + next.id, relation) as Any }
                .toTypedArray()
        } ?: emptyArray()
}

/** Resolves resource or column neighbors, preserving candidate edges and the direct, derived or partial analysis labels. */
internal object DbtHierarchy {
    fun related(w: DbtWorkspace, symbol: DbtSymbol, downstream: Boolean): List<Pair<DbtSymbol, String>> {
        fun label(edge: DbtColumnEdge) =
            edge.kind + (if (edge.kind == "value" && edge.direct) " · direct passthrough" else " · derived") +
                    (if (edge.complete) "" else " · partial analysis") + (if (edge.expression.isEmpty()) "" else " · " + edge.expression)
        if (symbol.kind in DbtSymbol.ColumnKinds) {
            val resource = w.symbols.firstOrNull {
                it.kind in DbtSymbol.RelationKinds && it.namespace == symbol.namespace && it.relationName == symbol.owner && w.owner(
                    it
                ) == w.owner(symbol)
            } ?: w.model(symbol.file)
            return DbtColumnLineage.edges(w).flatMap { edge ->
                if (!downstream && resource?.sameResource(edge.resource) == true && edge.column.equals(
                        symbol.name,
                        ignoreCase = true
                    )
                ) listOf(edge.input to label(edge))
                else if (downstream && edge.input.namespace == symbol.namespace && edge.input.name.equals(
                        symbol.name,
                        ignoreCase = true
                    ) && edge.input.owner == symbol.owner && edge.input.version == symbol.version && w.owner(edge.input) == w.owner(
                        symbol
                    )
                ) {
                    val column = w.columns(edge.resource).firstOrNull { it.name.equals(edge.column, ignoreCase = true) }
                        ?: DbtSqlAnalysis.get(w, edge.resource.file)?.columns?.firstOrNull {
                            it.name.equals(
                                edge.column,
                                ignoreCase = true
                            )
                        }?.let {
                            DbtSymbol(
                                "sql_projection",
                                it.name,
                                edge.resource.namespace,
                                edge.resource.file,
                                it.range,
                                owner = edge.resource.name
                            )
                        }
                    listOfNotNull(column?.let { it to label(edge) })
                } else emptyList()
            }.distinctBy { it.first.id to it.second }
        }
        return DbtLineage.edges(w).mapNotNull { edge ->
            val from = if (downstream) edge.to else edge.from
            if (from.sameResource(symbol) && w.owner(from) == w.owner(symbol)) (if (downstream) edge.from else edge.to) to (if (edge.ambiguous) "candidate" else "") else null
        }.distinctBy { it.first.id }
    }
}

/** Supplies a nested CTE outline for dbt models without requiring a SQL language plugin. */
class DbtSqlStructureViewFactory : PsiStructureViewFactory {
    override fun getStructureViewBuilder(file: PsiFile): StructureViewBuilder? {
        if (DbtWorkspace.get(file.project).model(file) == null) return null
        return object : TreeBasedStructureViewBuilder() {
            override fun createStructureViewModel(editor: Editor?): StructureViewModel =
                object : StructureViewModelBase(file, editor, DbtSqlStructureElement(file, null)) {
                    override fun getCurrentEditorElement(): Any {
                        val workspace = DbtWorkspace.get(file.project)
                        val model = workspace.model(file)
                        val analysis = DbtSqlAnalysis.get(workspace, file)
                        val selected = if (model != null && analysis != null && editor != null) DbtCteInspection.at(
                            workspace,
                            file,
                            analysis,
                            editor.caretModel.offset
                        ) else null
                        return if (model != null && selected != null) DbtSymbol(
                            "cte",
                            selected.name,
                            model.namespace,
                            file,
                            selected.range
                        ).element else file
                    }
                }
        }
    }
}

internal class DbtSqlStructureElement(private val file: PsiFile, private val symbol: DbtSymbol?) :
    StructureViewTreeElement {
    override fun getValue(): Any = symbol?.element ?: file
    override fun getPresentation(): ItemPresentation = symbol?.element?.presentation ?: object : ItemPresentation {
        override fun getPresentableText() = file.name
        override fun getLocationString(): String? = null
        override fun getIcon(open: Boolean): Icon? = file.getIcon(0)
    }

    override fun getChildren(): Array<TreeElement> {
        val w = DbtWorkspace.get(file.project)
        val model = w.model(file) ?: return emptyArray()
        val analysis = DbtSqlAnalysis.get(w, file) ?: return emptyArray()
        return analysis.ctes.filter { cte ->
            if (symbol == null) analysis.ctes.none { it.body.contains(cte.range) }
            else analysis.ctes.firstOrNull { it.range == symbol.range }?.let { it.body.contains(cte.range) } == true &&
                    analysis.ctes.none {
                        it.range != symbol.range && symbol.range.contains(it.range) && it.body.contains(
                            cte.range
                        )
                    }
        }.map {
            DbtSqlStructureElement(
                file,
                DbtSymbol("cte", it.name, model.namespace, file, it.range)
            ) as TreeElement
        }.toTypedArray()
    }

    override fun canNavigate() = symbol?.element?.canNavigate() == true
    override fun canNavigateToSource() = canNavigate()
    override fun navigate(requestFocus: Boolean) {
        symbol?.element?.navigate(requestFocus)
    }
}

class DbtSqlStructureViewExtension : StructureViewExtension {
    override fun getType(): Class<out PsiElement> = PsiFile::class.java
    override fun getChildren(element: PsiElement): Array<StructureViewTreeElement> {
        val file = element as? PsiFile ?: return emptyArray()
        if (Language.findLanguageByID("SQL")
                ?.let { file.language.isKindOf(it) } != true || DbtWorkspace.get(file.project).model(file) == null
        ) return emptyArray()
        return DbtSqlStructureElement(file, null).children.map { it as StructureViewTreeElement }.toTypedArray()
    }

    override fun getCurrentEditorElement(editor: Editor, parent: PsiElement): Any? = (parent as? PsiFile)?.let { file ->
        getChildren(file).map { it.value }.filterIsInstance<DbtSymbolElement>()
            .firstOrNull { it.symbol.range.containsOffset(editor.caretModel.offset) }
    }
}

/** Uses local declaration icons; files requiring different icons keep their native file-type icon. */
class DbtIconProvider : IconProvider(), DumbAware {
    override fun getIcon(element: PsiElement, flags: Int): Icon? {
        val file = element as? PsiFile ?: return null
        val symbols = DbtWorkspace.declarationsInFile(file)
        val code = symbols.filter { it.isCode }
        val resources = code.ifEmpty { symbols.filter { it.owner.isEmpty() } }
        val icons = resources.map { DbtSymbolElement.icon(if (it.isTestOnlyProperty) "test" else it.kind) }.distinct()
        return icons.singleOrNull()
    }
}
