package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.lineage.DbtDependency
import com.github.vbmacher.intellij.dbt.lineage.DbtLineage
import com.github.vbmacher.intellij.dbt.navigation.DbtNavigation
import com.github.vbmacher.intellij.dbt.project.*
import com.intellij.ide.DataManager
import com.intellij.codeInsight.hint.HintManager
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListItemDescriptor
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.popup.list.GroupedItemsListRenderer
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.WrapLayout
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.Insets
import java.awt.datatransfer.StringSelection
import java.nio.file.Paths
import java.util.concurrent.Callable
import javax.swing.*

internal data class DbtNavigationItem(
    val target: DbtSymbolElement, val text: String,
    val reference: Boolean = false, val separator: String? = null
) {
    override fun toString() = text

    companion object {
        /** A file:line label of an offset. */
        fun location(file: PsiFile, offset: Int): String {
            val line = file.viewProvider.document?.let { it.getLineNumber(minOf(offset, it.textLength)) + 1 } ?: 1
            return "${file.name}:$line"
        }

        fun declaration(target: DbtSymbolElement, separator: String? = null) =
            DbtNavigationItem(
                target,
                "${target.presentation.presentableText} — ${location(target.symbol.file, target.textOffset)}",
                separator = separator
            )

        fun popup(title: String, items: List<DbtNavigationItem>): JBPopup =
            JBPopupFactory.getInstance().createPopupChooserBuilder(items).setTitle(title)
                .setRenderer(DbtNavigationItemRenderer()).setNamerForFiltering { it.text }
                .setItemChosenCallback { it.target.navigate(true) }.createPopup()
    }
}

/** Shows resource icons with their references indented below them. */
internal class DbtNavigationItemRenderer :
    GroupedItemsListRenderer<DbtNavigationItem>(object : ListItemDescriptor<DbtNavigationItem> {
        override fun getTextFor(item: DbtNavigationItem) = item.text
        override fun getTooltipFor(item: DbtNavigationItem) =
            if (item.reference) item.target.symbol.file.virtualFile.path else "${item.target.symbol.kind}: ${item.text}"

        override fun getIconFor(item: DbtNavigationItem): Icon? = if (item.reference) null else item.target.getIcon(0)
        override fun hasSeparatorAboveOf(item: DbtNavigationItem) = item.separator != null
        override fun getCaptionAboveOf(item: DbtNavigationItem): String? = item.separator
    }) {
    override fun customizeComponent(list: JList<out DbtNavigationItem>, item: DbtNavigationItem, selected: Boolean) {
        myTextLabel.border = JBUI.Borders.emptyLeft(if (item.reference) 28 else 0)
    }
}

/** Editor actions update on a background thread. */
abstract class DbtEditorAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    companion object {
        fun fileContext(event: AnActionEvent): Pair<Project, PsiFile>? {
            val project = event.project ?: return null
            val file = event.getData(CommonDataKeys.PSI_FILE) ?: return null
            return project to file
        }

        fun editorContext(event: AnActionEvent): Triple<Project, PsiFile, Editor>? {
            val (project, file) = fileContext(event) ?: return null
            val editor = event.getData(CommonDataKeys.EDITOR) ?: return null
            return Triple(project, file, editor)
        }

        fun sql(event: AnActionEvent, editor: Boolean = false): Boolean =
            (if (editor) editorContext(event)?.second else fileContext(event)?.second)?.name?.endsWith(".sql") == true

        fun caret(context: DataContext): Triple<Project, PsiFile?, Int>? {
            val project = CommonDataKeys.PROJECT.getData(context) ?: return null
            return Triple(
                project,
                CommonDataKeys.PSI_FILE.getData(context),
                CommonDataKeys.EDITOR.getData(context)?.caretModel?.offset ?: 0
            )
        }
    }
}

/** Computes navigation items at the caret in a background read action and offers them in a popup. */
abstract class DbtPopupAction(private val title: String) : DbtEditorAction() {
    internal abstract fun items(w: DbtWorkspace, file: PsiFile, offset: Int): List<DbtNavigationItem>
    protected open fun nothingFound(project: Project, editor: Editor) {}
    override fun actionPerformed(event: AnActionEvent) {
        val (project, file, editor) = editorContext(event) ?: return
        val offset = editor.caretModel.offset
        DbtUi.readLater(
            project,
            smartIn = project,
            read = { items(DbtWorkspace.get(project), file, offset) }) { choices ->
            if (choices.isEmpty()) nothingFound(project, editor)
            else DbtNavigationItem.popup(title, choices).showInBestPositionFor(editor)
        }
    }
}

class DbtPropertiesAction : DbtPopupAction("dbt Properties") {
    internal override fun items(w: DbtWorkspace, file: PsiFile, offset: Int): List<DbtNavigationItem> {
        val at = DbtNavigation.resolvedAt(file, offset)
        return (at.ifEmpty { w.codeSymbols(file) }).flatMap(w::properties).distinct()
            .map { DbtNavigationItem.declaration(it.element) }
    }
}

class DbtDependenciesAction : DbtPopupAction("dbt Dependencies") {
    internal override fun items(w: DbtWorkspace, file: PsiFile, offset: Int): List<DbtNavigationItem> {
        val at = DbtNavigation.resolvedAt(file, offset)
        val current = at.ifEmpty { w.symbolAt(file, offset) + w.codeSymbols(file) }
        return choices(DbtLineage.edges(w), current.map { it.id }.toSet())
    }

    override fun nothingFound(project: Project, editor: Editor) {
        if (!editor.isDisposed) HintManager.getInstance()
            .showInformationHint(editor, "No statically resolved dependencies at this location.")
    }

    companion object {
        internal fun choices(edges: List<DbtDependency>, selected: Set<String>): List<DbtNavigationItem> =
            listOf(true, false).flatMap { upstream ->
                fun other(e: DbtDependency): DbtSymbol = if (upstream) e.to else e.from
                val related = edges.filter { (if (upstream) it.from.id else it.to.id) in selected }
                related.map(::other).distinctBy { it.id }.flatMapIndexed { index, target ->
                    val references = related.filter { other(it).id == target.id }
                    val candidate = if (references.any { it.ambiguous }) " (candidate)" else ""
                    val heading = if (index == 0) (if (upstream) "Uses" else "Used by") else null
                    listOf(
                        DbtNavigationItem(
                            target.element,
                            target.element.presentation.presentableText + candidate,
                            separator = heading
                        )
                    ) +
                            references.map { e ->
                                val range = e.reference.rangeInElement
                                DbtNavigationItem(
                                    DbtSymbol(
                                        "call site",
                                        target.name,
                                        target.namespace,
                                        e.reference.element,
                                        range
                                    ).element,
                                    DbtNavigationItem.location(e.reference.element, range.startOffset), reference = true
                                )
                            }.distinctBy { it.target.symbol.id }
                }
            }
    }
}

/**
 * Resolves the caret resource off the EDT and applies the action only while source, settings and selection still match.
 * Metadata is loaded during resolution so the UI callback does not perform workspace analysis.
 */
abstract class DbtResourceAction internal constructor(private val perform: (DbtWorkspace, DbtSymbol) -> Unit) :
    DbtEditorAction() {
    private fun resource(event: AnActionEvent): Pair<DbtWorkspace, DbtSymbol>? =
        caret(event.dataContext)?.let { (project, file, offset) -> at(project, file, offset) }

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = resource(event) != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val (project, source, offset) = caret(event.dataContext) ?: return
        val editor = event.getData(CommonDataKeys.EDITOR)
        val component = event.getData(PlatformCoreDataKeys.CONTEXT_COMPONENT)
        val document = source?.viewProvider?.document
        val stamp = document?.modificationStamp
        val settings = DbtProjectSettings.get(project).modificationCount
        val structure = VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS.modificationCount
        fun unchanged() = (source?.isValid ?: true) && document?.modificationStamp == stamp &&
                DbtProjectSettings.get(project).modificationCount == settings && VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS.modificationCount == structure

        fun stopped(message: String) = NotificationGroupManager.getInstance().getNotificationGroup("dbt Workspace")
            .createNotification("dbt action stopped", message, NotificationType.INFORMATION).notify(project)
        DbtUi.background(
            project,
            "Resolve dbt resource",
            canceled = { stopped("Resource resolution was canceled. Run the action again when ready.") },
            work = { indicator ->
                ReadAction.nonBlocking(Callable {
                    val result = if (!unchanged()) null else at(
                        project,
                        source,
                        offset
                    )?.also { (workspace, symbol) -> DbtResourceData.get(workspace, symbol) }
                    PsiModificationTracker.getInstance(project).modificationCount to result
                }).expireWith(project).withDocumentsCommitted(project).wrapProgress(indicator).executeSynchronously()
            }) { (modifications, result) ->
            val sameSelection = if (editor != null) !editor.isDisposed && editor.caretModel.offset == offset &&
                    FileEditorManager.getInstance(project).selectedTextEditor == editor
            else component?.let {
                CommonDataKeys.PSI_FILE.getData(
                    DataManager.getInstance().getDataContext(it)
                ) == source
            } ?: true
            if (!unchanged() || !sameSelection || PsiModificationTracker.getInstance(project).modificationCount != modifications)
                stopped("Source, settings or selection changed during resolution. Run the action again.")
            else result?.let { (w, s) -> perform(w, s) }
        }
    }

    companion object {
        internal fun at(project: Project, file: PsiFile?, offset: Int): Pair<DbtWorkspace, DbtSymbol>? {
            val w = DbtWorkspace.get(project)
            return at(w, file, offset)?.let { w to it }
        }

        internal fun at(w: DbtWorkspace, source: PsiFile?, offset: Int): DbtSymbol? = source?.let { file ->
            val here = w.symbolAt(file, offset)
            here.firstOrNull { it.kind in DbtResourceData.GraphKinds }
                ?: here.firstOrNull { it.kind == "column" }
                    ?.let { w.relations(it).sortedBy { r -> r.isProperty }.firstOrNull() }
                ?: w.codeSymbols(file).firstOrNull()
        }
    }
}

class DbtEditPropertiesAction : DbtResourceAction(DbtPropertiesEditor::show)
class DbtGenerateColumnsAction : DbtResourceAction({ w, s -> DbtPropertiesEditor.generate(w, s, inherit = false) })
class DbtInheritDescriptionsAction : DbtResourceAction({ w, s -> DbtPropertiesEditor.generate(w, s, inherit = true) })
class DbtOpenCompiledSqlAction : DbtResourceAction({ w, s -> DbtCompiledSql.show(w, s, compare = false) })
class DbtCompareSqlAction : DbtResourceAction({ w, s -> DbtCompiledSql.show(w, s, compare = true) })

/** Opens SQL from the current artifact context, or offers an explicit Compile before checking that context again. */
internal object DbtCompiledSql {
    fun show(w: DbtWorkspace, symbol: DbtSymbol, compare: Boolean) {
        fun open(current: DbtWorkspace, model: DbtSymbol): Boolean {
            val data = DbtResourceData.get(current, model)
            data.compiledSql?.let { sql ->
                if (compare) DbtSqlEditors.compare(
                    current.project,
                    model.name,
                    data.rawSql ?: model.file.text,
                    sql,
                    "Authored SQL",
                    "Compiled SQL"
                )
                else DbtSqlEditors.open(current.project, model.name + " compiled", sql)
            }
            return data.compiledSql != null
        }
        if (open(w, symbol)) return
        val label = if (compare) "Compile and compare" else "Compile and open"
        val model = w.model(symbol.file)?.takeIf { it.isEnabled }
        if (model == null) DbtNotifications.show(
            w.project,
            "dbt compiled SQL",
            "Compiled SQL is unavailable. Choose a source model to compile.",
            NotificationType.INFORMATION
        )
        else if (Messages.showYesNoDialog(
                w.project, "Compiled SQL is unavailable for this model and target.",
                "dbt compiled SQL", label, "Cancel", Messages.getQuestionIcon()
            ) == Messages.YES
        ) {
            val path = symbol.file.virtualFile.path
            DbtUi.saveAndCommit(w.project)
            DbtUi.readLater(w.project, read = {
                val current = DbtWorkspace.get(w.project)
                val selection = DbtRunSelection.available(current).firstOrNull { it.path == path }
                if (selection == null) Result.failure<DbtCommand>(IllegalArgumentException("The source model is unavailable."))
                else DbtCommand.create(current, selection, "Compile", fullRefresh = false)
            }) { result ->
                result.fold(onFailure = {
                    DbtNotifications.show(
                        w.project,
                        "dbt compiled SQL",
                        it.message.orEmpty(),
                        NotificationType.ERROR
                    )
                }, onSuccess = { command ->
                    DbtRunConfiguration.launch(w.project, command, { finished ->
                        LocalFileSystem.getInstance()
                            .refreshIoFiles(listOf(java.io.File(finished.artifactDirectory)), true, true, {
                                DbtUi.readLater(w.project, read = {
                                    val current = DbtWorkspace.get(w.project)
                                    current.model(path)?.takeIf { m ->
                                        current.owner(m)?.let { r ->
                                            DbtArtifactContext.identity(current.executionRoot(r)) == finished.artifactContext
                                        } == true
                                    }?.let { current to it }
                                }) { resolved ->
                                    if (resolved?.let { (current, resource) -> open(current, resource) } != true)
                                        DbtNotifications.show(
                                            w.project,
                                            "dbt compiled SQL",
                                            "Compilation finished, but matching compiled SQL is unavailable. Check the Run output and current source and target."
                                        )
                                }
                            })
                    })
                })
            }
        }
    }
}

/** Read-only SQL shown in editors and diffs. */
internal object DbtSqlEditors {
    private val sqlType: FileType get() = FileTypeManager.getInstance().getFileTypeByExtension("sql")
    fun open(project: Project, name: String, sql: String) {
        val file = LightVirtualFile(name.removeSuffix(".sql") + ".sql", sqlType, sql)
        file.isWritable = false
        FileEditorManager.getInstance(project).openFile(file, true)
    }

    fun compare(project: Project, name: String, before: String, after: String, left: String, right: String) {
        val factory = DiffContentFactory.getInstance()
        DiffManager.getInstance().showDiff(
            project,
            SimpleDiffRequest(
                name,
                factory.create(project, before, sqlType),
                factory.create(project, after, sqlType),
                left,
                right
            )
        )
    }
}

/** Presentation and threading helpers: never resolves resources or launches a process. */
internal object DbtUi {
    fun name(path: String) = try {
        Paths.get(path).fileName.toString()
    } catch (_: Exception) {
        path
    }

    /**
     * Runs [read] in a cancellable read action tied to [parent], then delivers [done] on the EDT.
     * [smartIn] waits for indexes, [committedIn] waits for committed documents, and [coalesce] replaces queued equivalent reads.
     */
    fun <A> readLater(
        parent: Disposable, modality: ModalityState = ModalityState.defaultModalityState(),
        smartIn: Project? = null, coalesce: Any? = null, committedIn: Project? = null, read: () -> A, done: (A) -> Unit
    ) {
        val action = ReadAction.nonBlocking(Callable { read() }).expireWith(parent)
        val smart = smartIn?.let(action::inSmartMode) ?: action
        val committed = committedIn?.let(smart::withDocumentsCommitted) ?: smart
        val coalesced = coalesce?.let { committed.coalesceBy(it) } ?: committed
        coalesced.finishOnUiThread(modality) { done(it) }.submit(AppExecutorUtil.getAppExecutorService())
    }

    /**
     * Runs [work] on a background thread and dispatches outcome callbacks on the EDT while the project is open.
     * [finished] runs after success, cancellation or failure, including project disposal, so it may release local state.
     */
    fun <A> background(
        project: Project, title: String, failed: ((Throwable) -> Unit)? = null, finished: () -> Unit = {},
        canceled: () -> Unit = {}, work: (ProgressIndicator) -> A, show: (A) -> Unit
    ) {
        object : Task.Backgroundable(project, title, true) {
            private var result: Result<A>? = null
            override fun run(indicator: ProgressIndicator) {
                result = Result.success(work(indicator))
            }

            override fun onSuccess() {
                if (!project.isDisposed) result?.let { show(it.getOrThrow()) }
            }

            override fun onCancel() {
                if (!project.isDisposed) canceled()
            }

            override fun onThrowable(error: Throwable) {
                if (!project.isDisposed) {
                    if (failed != null) failed(error)
                    else DbtNotifications.show(
                        project,
                        title,
                        "Analysis failed. Refresh and try again.",
                        NotificationType.ERROR
                    )
                }
            }

            override fun onFinished() = finished()
        }.queue()
    }

    fun saveAndCommit(project: Project) {
        FileDocumentManager.getInstance().saveAllDocuments()
        PsiDocumentManager.getInstance(project).commitAllDocuments()
    }

    fun <A> read(body: () -> A): A = ReadAction.computeBlocking<A, RuntimeException> { body() }
    fun copy(value: String) = CopyPasteManager.getInstance().setContents(StringSelection(value))
    fun open(project: Project, path: String, offset: Int) {
        LocalFileSystem.getInstance().refreshAndFindFileByPath(path)
            ?.let { OpenFileDescriptor(project, it, offset).navigate(true) }
    }

    fun <A> selected(list: JList<*>, values: List<A>): A? = values.getOrNull(list.selectedIndex)
    fun button(label: String, action: () -> Unit): JButton = JButton(label).also { it.addActionListener { action() } }
    fun buttons(vararg items: JComponent): JPanel =
        JPanel(WrapLayout(FlowLayout.LEFT)).also { panel -> items.forEach { panel.add(it) } }

    fun textArea(text: String = "", wordWrap: Boolean = true) = JTextArea(text).also {
        it.isEditable = false; it.lineWrap = true; it.wrapStyleWord = wordWrap
    }

    fun border(center: JComponent, north: JComponent? = null, south: JComponent? = null): JPanel =
        JPanel(BorderLayout(6, 6)).also {
            if (north != null) it.add(north, BorderLayout.NORTH)
            it.add(center, BorderLayout.CENTER)
            if (south != null) it.add(south, BorderLayout.SOUTH)
        }

    fun input(field: JComponent): JComponent {
        val target = field.getClientProperty("dbt.input")
        if (target is JComponent) return input(target)
        return when (field) {
            is com.intellij.openapi.ui.TextFieldWithBrowseButton -> field.textField
            is JScrollPane -> (field.viewport.view as? JComponent)?.let(::input) ?: field
            is JSpinner -> (field.editor as? JSpinner.DefaultEditor)?.textField ?: field
            else -> field
        }
    }

    fun labeledRows(panel: JPanel, insets: Insets, rows: List<Pair<String, JComponent>>) {
        rows.forEachIndexed { row, (label, field) ->
            val c = GridBagConstraints().also {
                it.gridy = row; it.gridx = 0; it.anchor = GridBagConstraints.WEST; it.insets = insets
            }
            val hasLabels = rows.any { it.first.isNotEmpty() }
            if (hasLabels) {
                val caption = JLabel(label)
                if (label.isNotEmpty()) {
                    caption.border = JBUI.Borders.emptyRight(4)
                    val target = input(field)
                    caption.labelFor = target
                    field.accessibleContext.accessibleName = label
                    target.accessibleContext.accessibleName = label
                }
                panel.add(caption, c)
            }
            c.gridx = if (hasLabels) 1 else 0; c.weightx = 1.0; c.fill = GridBagConstraints.HORIZONTAL
            panel.add(field, c)
        }
    }
}
