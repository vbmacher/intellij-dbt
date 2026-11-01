package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project.DbtWorkspace
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiFile
import com.intellij.usages._
import com.intellij.usages.rules.MergeableUsage
import com.intellij.usageView.UsageInfo
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import scala.jdk.CollectionConverters._

private[ide] final class DbtAirflowUsageInfo(file: PsiFile, val task: DbtAirflowTask)
  extends UsageInfo(file, task.offset, math.min(task.offset + 1, file.getTextLength))

private[ide] final class DbtAirflowUsage(info: DbtAirflowUsageInfo) extends UsageInfo2UsageAdapter(info) {
  def task: DbtAirflowTask = getUsageInfo.asInstanceOf[DbtAirflowUsageInfo].task
  override def computeText(): Array[TextChunk] = Array(new TextChunk(new TextAttributes, getPlainText))
  override def getPlainText: String = task.dag + " / " + task.task + " — " + task.project +
    (if (task.schedule.nonEmpty) " · " + task.schedule else "")
  override def merge(other: MergeableUsage): Boolean = false
  override def getUsageType: com.intellij.usages.impl.rules.UsageType = DbtAirflowResults.TaskType
}

/** Project-wide discovery uses Find results, with native source navigation and a task-details preview. */
private[ide] object DbtAirflowResults {
  val Title = "dbt Airflow Context"
  val TaskType = new com.intellij.usages.impl.rules.UsageType(() => "Airflow tasks")

  def usages(workspace: DbtWorkspace): (Vector[DbtAirflowUsage], Vector[String]) = {
    val tasks = DbtAirflow.tasks(workspace)
    val rows = tasks.flatMap { task =>
      Option(LocalFileSystem.getInstance.findFileByPath(task.evidence))
        .flatMap(com.github.vbmacher.intellij.dbt.syntax.DbtYaml.psiFile(workspace.project, _))
        .filter(file => task.offset >= 0 && task.offset < file.getTextLength)
        .map(file => new DbtAirflowUsage(new DbtAirflowUsageInfo(file, task)))
    }
    (rows, DbtAirflow.problems(workspace) ++ tasks.filterNot(t => rows.exists(_.task.id == t.id))
      .map(t => "Source unavailable: " + t.evidence))
  }

  def show(project: Project): UsageView = {
    val presentation = new UsageViewPresentation
    presentation.setTabText(Title)
    presentation.setTabName(Title)
    presentation.setDynamicUsagesString("Airflow tasks")
    presentation.setScopeText("Local Airflow pipelines")
    presentation.setMergeDupLinesAvailable(false)
    presentation.setOpenInNewTab(false)
    val view = UsageViewManager.getInstance(project).showUsages(UsageTarget.EMPTY_ARRAY, Array.empty[Usage], presentation)
    var closed = false
    var generation = 0
    Disposer.register(view, () => closed = true)
    val status = DbtUi.textArea()
    status.setRows(2)
    status.setColumns(70)
    status.getAccessibleContext.setAccessibleName("Airflow discovery status")
    view.setAdditionalComponent(new JBScrollPane(status))

    lazy val refresh = new AbstractAction("Refresh") {
      override def actionPerformed(event: ActionEvent): Unit = reload()
    }
    def reload(): Unit = {
      generation += 1
      val requested = generation
      def current: Boolean = !closed && requested == generation
      val selected = Option(view.getSelectedUsages).toVector.flatMap(_.asScala)
        .collect { case usage: DbtAirflowUsage => usage.task.id }.toSet
      status.setText("Discovering local Airflow pipelines…")
      refresh.setEnabled(false)
      DbtUi.background(project, "Discover local dbt Airflow context",
        failed = Some(_ => if (current) status.setText("Discovery failed. Refresh and try again.")),
        canceled = () => if (current) status.setText("Discovery canceled. Refresh to try again."),
        finished = () => if (current) refresh.setEnabled(true)) { _ =>
        DbtUi.read(usages(DbtWorkspace.get(project)))
      } { case (rows, problems) => if (current) {
        view.removeUsagesBulk(view.getUsages)
        rows.foreach(view.appendUsage)
        view.selectUsages(rows.filter(r => selected(r.task.id)).toArray[Usage])
        status.setText((Vector(if (rows.isEmpty)
          "No Airflow pipelines detected. Use Configure… to check local DAG folders."
        else "Detected from local DAG Python files; Python is never executed.") ++ problems).mkString("\n"))
      }}
    }
    view.setRerunAction(refresh)
    view.addButtonToLowerPane(new AbstractAction("Configure…") {
      override def actionPerformed(event: ActionEvent): Unit = {
        DbtSettingsConfigurable.showIntegrations(project)
        reload()
      }
    })
    reload()
    view
  }

  def details(task: DbtAirflowTask): String = Vector(
    "DAG / task: " + task.dag + " / " + task.task,
    "Project: " + task.project,
    "Kind: " + task.kind,
    "Schedule: " + Option(task.schedule).filter(_.nonEmpty).getOrElse("Not statically known"),
    "\nSelection: " + task.selection,
    "Exclusions: " + task.exclude,
    "Indirect selection: " + task.indirect,
    "\nResources:\n" + task.resources.map(_.uniqueId).mkString("\n"),
    (if (task.kind == "ingestion") "\nIngestion destinations:\n" else "\nUpstream ingestion pipelines:\n") + task.ingestion.mkString("\n"),
    "\nProblems:\n" + (if (task.problems.isEmpty) "None reported" else task.problems.mkString("\n")),
    "\nSource: " + task.evidence + ":" + task.line).mkString("\n")
}

/** A standard Find-results preview tab; source preview remains available alongside task details. */
final class DbtAirflowDetailsProvider extends UsageContextPanel.Provider {
  override def getTabTitle: String = "Airflow details"
  override def isAvailableFor(view: UsageView): Boolean = view.getPresentation.getTabName == DbtAirflowResults.Title
  override def create(view: UsageView): UsageContextPanel = new DbtAirflowDetailsPanel(view.getPresentation)
}

private[ide] final class DbtAirflowDetailsPanel(presentation: UsageViewPresentation)
  extends com.intellij.usages.impl.UsageContextPanelBase(presentation) {
  private val text = DbtUi.textArea("Select one Airflow task to inspect its details.")
  text.getAccessibleContext.setAccessibleName("Airflow task details")
  private var selected = Option.empty[DbtAirflowTask]
  private val browser = DbtUi.button("Open Airflow task") { selected.flatMap(_.url).foreach(BrowserUtil.browse) }
  browser.setEnabled(false)
  setLayout(new BorderLayout(0, 8))
  setBorder(JBUI.Borders.empty(8))
  add(new JBScrollPane(text), BorderLayout.CENTER)
  add(DbtUi.buttons(browser), BorderLayout.SOUTH)

  override protected def updateLayoutLater(infos: java.util.List[_ <: UsageInfo]): Unit = {
    val tasks = Option(infos).toVector.flatMap(_.asScala).collect { case info: DbtAirflowUsageInfo => info.task }
    selected = tasks.headOption.filter(_ => tasks.size == 1)
    text.setText(selected.map(DbtAirflowResults.details).getOrElse("Select one Airflow task to inspect its details."))
    text.setCaretPosition(0)
    browser.setEnabled(selected.flatMap(_.url).nonEmpty)
  }
}
