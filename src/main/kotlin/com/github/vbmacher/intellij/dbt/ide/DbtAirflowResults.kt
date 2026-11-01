package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project.DbtWorkspace
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiFile
import com.intellij.usages.*
import com.intellij.usages.rules.MergeableUsage
import com.intellij.usageView.UsageInfo
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.event.ActionEvent
import javax.swing.AbstractAction

internal class DbtAirflowUsageInfo(file: PsiFile, val task: DbtAirflowTask) :
    UsageInfo(file, task.offset, minOf(task.offset + 1, file.textLength))

internal class DbtAirflowUsage(info: DbtAirflowUsageInfo) : UsageInfo2UsageAdapter(info) {
    val task: DbtAirflowTask get() = (usageInfo as DbtAirflowUsageInfo).task
    override fun computeText(): Array<TextChunk> = arrayOf(TextChunk(TextAttributes(), plainText))
    override fun getPlainText() =
        task.dag + " / " + task.task + " — " + task.project + if (task.schedule.isNotEmpty()) " · " + task.schedule else ""

    override fun merge(other: MergeableUsage) = false
    override fun getUsageType() = DbtAirflowResults.TaskType
}

/** Presents local discovery in native Find results; missing source files stay visible as diagnostics rather than guessed links. */
internal object DbtAirflowResults {
    const val Title = "dbt Airflow Context"
    val TaskType = com.intellij.usages.impl.rules.UsageType { "Airflow tasks" }
    fun usages(workspace: DbtWorkspace): Pair<List<DbtAirflowUsage>, List<String>> {
        val tasks = DbtAirflow.tasks(workspace)
        val rows = tasks.mapNotNull { task ->
            LocalFileSystem.getInstance().findFileByPath(task.evidence)
                ?.let { com.github.vbmacher.intellij.dbt.syntax.DbtYaml.psiFile(workspace.project, it) }
                ?.takeIf { task.offset >= 0 && task.offset < it.textLength }
                ?.let { DbtAirflowUsage(DbtAirflowUsageInfo(it, task)) }
        }
        return rows to (DbtAirflow.problems(workspace) + tasks.filter { t -> rows.none { it.task.id == t.id } }
            .map { "Source unavailable: " + it.evidence })
    }

    /** Refreshes off the EDT and ignores callbacks from older refreshes or a closed results view. */
    fun show(project: Project): UsageView {
        val presentation = UsageViewPresentation().also {
            it.tabText = Title; it.tabName = Title; it.setDynamicUsagesString("Airflow tasks"); it.scopeText =
            "Local Airflow pipelines"
            it.isMergeDupLinesAvailable = false; it.isOpenInNewTab = false
        }
        val view = UsageViewManager.getInstance(project).showUsages(UsageTarget.EMPTY_ARRAY, emptyArray(), presentation)
        var closed = false
        var generation = 0
        Disposer.register(view, com.intellij.openapi.Disposable { closed = true })
        val status = DbtUi.textArea()
            .also { it.rows = 2; it.columns = 70; it.accessibleContext.accessibleName = "Airflow discovery status" }
        view.setAdditionalComponent(JBScrollPane(status))
        lateinit var refresh: AbstractAction
        fun reload() {
            generation++
            val requested = generation
            fun current() = !closed && requested == generation
            val selected = view.selectedUsages.orEmpty().filterIsInstance<DbtAirflowUsage>().map { it.task.id }.toSet()
            status.text = "Discovering local Airflow pipelines…"
            refresh.isEnabled = false
            DbtUi.background(
                project,
                "Discover local dbt Airflow context",
                failed = { if (current()) status.text = "Discovery failed. Refresh and try again." },
                canceled = { if (current()) status.text = "Discovery canceled. Refresh to try again." },
                finished = { if (current()) refresh.isEnabled = true },
                work = { DbtUi.read { usages(DbtWorkspace.get(project)) } }) { (rows, problems) ->
                if (current()) {
                    view.removeUsagesBulk(view.usages)
                    rows.forEach(view::appendUsage)
                    view.selectUsages(rows.filter { it.task.id in selected }.toTypedArray())
                    status.text = (listOf(
                        if (rows.isEmpty()) "No Airflow pipelines detected. Use Configure… to check local DAG folders."
                        else "Detected from local DAG Python files; Python is never executed."
                    ) + problems).joinToString("\n")
                }
            }
        }
        refresh = object : AbstractAction("Refresh") {
            override fun actionPerformed(event: ActionEvent) = reload()
        }
        view.setRerunAction(refresh)
        view.addButtonToLowerPane(object : AbstractAction("Configure…") {
            override fun actionPerformed(event: ActionEvent) {
                DbtSettingsConfigurable.showIntegrations(project); reload()
            }
        })
        reload()
        return view
    }

    fun details(task: DbtAirflowTask) = listOf(
        "DAG / task: ${task.dag} / ${task.task}",
        "Project: ${task.project}",
        "Kind: ${task.kind}",
        "Schedule: " + task.schedule.takeIf { it.isNotEmpty() }.let { it ?: "Not statically known" },
        "\nSelection: ${task.selection}",
        "Exclusions: ${task.exclude}",
        "Indirect selection: ${task.indirect}",
        "\nResources:\n" + task.resources.joinToString("\n") { it.uniqueId },
        (if (task.kind == "ingestion") "\nIngestion destinations:\n" else "\nUpstream ingestion pipelines:\n") + task.ingestion.joinToString(
            "\n"
        ),
        "\nProblems:\n" + if (task.problems.isEmpty()) "None reported" else task.problems.joinToString("\n"),
        "\nSource: ${task.evidence}:${task.line}"
    ).joinToString("\n")
}

class DbtAirflowDetailsProvider : UsageContextPanel.Provider {
    override fun getTabTitle() = "Airflow details"
    override fun isAvailableFor(view: UsageView) = view.presentation.tabName == DbtAirflowResults.Title
    override fun create(view: UsageView): UsageContextPanel = DbtAirflowDetailsPanel(view.presentation)
}

internal class DbtAirflowDetailsPanel(presentation: UsageViewPresentation) :
    com.intellij.usages.impl.UsageContextPanelBase(presentation) {
    private val text = DbtUi.textArea("Select one Airflow task to inspect its details.")
        .also { it.accessibleContext.accessibleName = "Airflow task details" }
    private var selected: DbtAirflowTask? = null
    private val browser =
        DbtUi.button("Open Airflow task") { selected?.url?.let(BrowserUtil::browse) }.also { it.isEnabled = false }

    init {
        layout = BorderLayout(0, 8); border = JBUI.Borders.empty(8); add(JBScrollPane(text), BorderLayout.CENTER); add(
            DbtUi.buttons(browser),
            BorderLayout.SOUTH
        )
    }

    override fun updateLayoutLater(infos: List<UsageInfo>?) {
        val tasks = infos.orEmpty().filterIsInstance<DbtAirflowUsageInfo>().map { it.task }
        selected = tasks.singleOrNull()
        text.text = selected?.let(DbtAirflowResults::details) ?: "Select one Airflow task to inspect its details."
        text.caretPosition = 0
        browser.isEnabled = selected?.url != null
    }
}
