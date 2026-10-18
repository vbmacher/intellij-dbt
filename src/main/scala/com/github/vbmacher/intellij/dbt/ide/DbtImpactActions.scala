package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project._
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.{AnAction, AnActionEvent, CommonDataKeys}
import com.intellij.openapi.application.{ApplicationManager, ReadAction}
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.{ProgressIndicator, Task}
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.{DialogWrapper, Messages}
import com.intellij.openapi.ide.CopyPasteManager
import java.awt.{BorderLayout, FlowLayout}
import java.awt.datatransfer.StringSelection
import javax.swing._
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

private[ide] object DbtImpactUi {
  def background[A](project: Project, title: String)(read: => A)(show: A => Unit): Unit =
    new Task.Backgroundable(project, title, true) {
      private var result: Option[A] = None
      override def run(indicator: ProgressIndicator): Unit = {
        result = Some(read)
      }
      override def onSuccess(): Unit = if (!project.isDisposed) result.foreach(show)
      override def onThrowable(error: Throwable): Unit = if (!project.isDisposed) Messages.showErrorDialog(project, "Analysis failed or was canceled. Refresh and try again.", title)
    }.queue()
  def read[A](body: => A): A = ReadAction.computeBlocking[A, RuntimeException](() => body)
  def context(w: DbtWorkspace, source: Option[com.intellij.psi.PsiFile], offset: Int): Option[DbtSymbol] = source.flatMap { file =>
    val at = w.symbolAt(file, offset)
    at.find(s => DbtResourceData.GraphKinds(s.kind)).orElse(at.find(_.kind == "column").flatMap(c =>
      w.symbols.filter(s => DbtSymbol.RelationKinds(s.kind) && s.relationName == c.owner && s.namespace == c.namespace &&
        s.version == c.version && w.owner(s) == w.owner(c)).sortBy(_.isProperty).headOption)).orElse(w.codeSymbols(file).headOption)
  }
  def button(label: String)(action: => Unit): JButton = { val b = new JButton(label); b.addActionListener(_ => action); b }
  def copy(value: String): Unit = CopyPasteManager.getInstance().setContents(new StringSelection(value))
  def show(project: Project, title: String, panel: JComponent): Unit = new DialogWrapper(project) {
    setTitle(title); init()
    override def createCenterPanel(): JComponent = panel
  }.show()
  def panel(): JPanel = { val p = new JPanel(new BorderLayout(6, 6)); p.setPreferredSize(new java.awt.Dimension(850, 550)); p }
  def open(project: Project, path: String, offset: Int): Unit = Option(com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(path))
    .foreach(f => new OpenFileDescriptor(project, f, offset).navigate(true))
}

final class DbtImpactAction extends AnAction {
  override def actionPerformed(event: AnActionEvent): Unit = Option(event.getProject).foreach { project =>
    val baseline = Messages.showInputDialog(project, "Compare current source and unsaved edits against Git revision:", "dbt Change Impact", Messages.getQuestionIcon, "HEAD", null)
    if (baseline != null && baseline.trim.nonEmpty) DbtImpactUi.background(project, "Analyze dbt Git impact") {
      val w = DbtImpactUi.read { DbtWorkspace.get(project) }
      DbtImpact.analyze(w, baseline.trim).map(result => DbtImpactUi.read { DbtWorkspace.get(project) } -> result)
    } {
      case Left(error) => Messages.showErrorDialog(project, error, "dbt Change Impact")
      case Right((workspace, result)) => show(project, workspace, result)
    }
  }
  private def show(project: Project, w: DbtWorkspace, result: DbtImpactResult): Unit = {
    val panel = DbtImpactUi.panel()
    val heading = new JTextArea("Baseline: " + result.baseline + "\nCompare: Current source including unsaved documents\n" +
      result.changes.map(c => c.state + " " + c.resource.id + " [outputs: " + c.columns.mkString(", ") + "]").mkString("\n"))
    heading.setEditable(false); heading.setLineWrap(true)
    panel.add(new JScrollPane(heading), BorderLayout.NORTH)
    val list = new JList[String](result.affected.map { case (s, trace) => s.uniqueId + " via " + trace.mkString(" → ") }.toArray)
    val tabs = new JTabbedPane
    tabs.addTab("Affected resources (potential)", new JScrollPane(list))
    val consumers = DbtConsumers.exposures(w).filter(c => result.affected.exists(a => DbtImpact.key(w, a._1) == c.resource)) ++
      project.getService(classOf[DbtConsumers]).snapshot.consumers.filter(c => result.affected.exists(a => DbtImpact.key(w, a._1) == c.resource))
    tabs.addTab("Consumers", new JScrollPane(new JList[String](consumers.map(c => c.name + " · " + c.kind + " · " + c.confidence + " · " + c.evidence).toArray)))
    val tasks = DbtAirflow.tasks(w).filter(t => t.resources.exists(s => result.affected.exists(a => DbtImpact.key(w, a._1) == DbtImpact.key(w, s))) || t.problems.nonEmpty)
    tabs.addTab("Airflow", new JScrollPane(new JList[String](tasks.map(t => t.dag + "/" + t.task + " · " + t.selection + " · " + t.problems.mkString("; ")).toArray)))
    val notes = new JTextArea((result.problems ++ Vector("Suggested dbt build --select " + result.selection,
      "Attached data/unit tests are selected by dbt build. Review target/profiles in Runner settings.")).mkString("\n"))
    notes.setEditable(false); notes.setLineWrap(true); notes.setWrapStyleWord(true)
    tabs.addTab("Validation plan / limitations", new JScrollPane(notes))
    panel.add(tabs, BorderLayout.CENTER)
    val buttons = new JPanel(new FlowLayout(FlowLayout.LEFT))
    buttons.add(DbtImpactUi.button("Go to definition") { Option(list.getSelectedIndex).filter(i => i >= 0 && i < result.affected.size).foreach(i => result.affected(i)._1.element.navigate(true)) })
    buttons.add(DbtImpactUi.button("Copy selection") { DbtImpactUi.copy(result.selection) })
    val build = DbtImpactUi.button("Build reviewed selection") {
      val command = DbtImpactUi.read {
        val current = DbtWorkspace.get(project)
        val roots = result.affected.map(_._1).flatMap(w.owner).map(w.executionRoot).distinct
        if (roots.size != 1 || result.selection.isEmpty) Left("Choose affected resources from a single execution project. Removed-only changes have no runnable selection.")
        else DbtRunSelection.all(current, roots.head.directory.getPath).toRight("Execution project is no longer available.")
          .flatMap(DbtCommand.create(current, _, "Build", fullRefresh = false)).map(c => c.copy(arguments = c.arguments ++ Vector("--select") ++ result.selection.split("\\s+")))
      }
      command match {
        case Left(error) => Messages.showErrorDialog(project, error, "dbt validation")
        case Right(value) => if (!project.getService(classOf[DbtRunner]).start(value)) Messages.showInfoMessage(project, "A dbt command is already running. Use Runner to cancel it.", "dbt validation")
      }
    }
    build.setEnabled(result.selection.nonEmpty); buttons.add(build)
    panel.add(buttons, BorderLayout.SOUTH); DbtImpactUi.show(project, "dbt Change Impact", panel)
  }
}

final class DbtConsumersAction extends AnAction {
  override def actionPerformed(event: AnActionEvent): Unit = Option(event.getProject).foreach { project =>
    val source = Option(event.getData(CommonDataKeys.PSI_FILE))
    val offset = Option(event.getData(CommonDataKeys.EDITOR)).map(_.getCaretModel.getOffset).getOrElse(0)
    DbtImpactUi.background(project, "Read dbt consumers") { DbtImpactUi.read {
      val w = DbtWorkspace.get(project); val focus = DbtImpactUi.context(w, source, offset)
      val columns = focus.toVector.flatMap(s => DbtResourceData.get(w, s).columns.map(_.name)).distinct
      (w, focus, DbtConsumers.exposures(w, focus), columns)
    } } { case (w, focus, exposures, columns) =>
      val service = project.getService(classOf[DbtConsumers]); val panel = DbtImpactUi.panel()
      val status = new JLabel; val list = new JList[String]; var values = Vector.empty[DbtConsumer]
      val column = new JComboBox[String]((Vector("All columns") ++ columns).toArray)
      def update(): Unit = {
        val cache = service.snapshot
        val selectedColumn = Option(column.getSelectedItem).map(_.toString).filter(_ != "All columns")
        values = exposures ++ cache.consumers.filter(c => focus.forall(s => c.resource == DbtImpact.key(w, s)) &&
          selectedColumn.forall(name => c.column.isEmpty || c.column.contains(name)))
        list.setListData(values.map(c => c.name + " · " + c.kind + " · Owner: " + c.owner + " · " + c.confidence +
          (if (selectedColumn.nonEmpty && c.column.isEmpty) " · model-level; column use unknown" else c.column.map(" · column: " + _).getOrElse("")) + " · " + c.evidence).toArray)
        status.setText(cache.status + " Refreshed: " + (if (cache.refreshedAt == 0) "never" else new java.util.Date(cache.refreshedAt).toString) +
          (if (cache.stale) " (stale)" else "") + (if (values.isEmpty) " No consumers found." else ""))
      }
      column.addActionListener(_ => update())
      val header = new JPanel(new BorderLayout); header.add(status, BorderLayout.NORTH); header.add(column, BorderLayout.SOUTH)
      update(); panel.add(header, BorderLayout.NORTH); panel.add(new JScrollPane(list), BorderLayout.CENTER)
      val buttons = new JPanel(new FlowLayout(FlowLayout.LEFT))
      buttons.add(DbtImpactUi.button("Open consumer") { Option(list.getSelectedIndex).filter(i => i >= 0 && i < values.size).flatMap(i => values(i).url).foreach(BrowserUtil.browse) })
      buttons.add(DbtImpactUi.button("Go to exposure definition") { Option(list.getSelectedIndex).filter(i => i >= 0 && i < values.size).foreach { i =>
        w.symbols.find(s => DbtImpact.key(w, s) == values(i).resource && s.kind == "exposure").foreach(_.element.navigate(true))
      } })
      val refresh = DbtImpactUi.button("Refresh Metabase metadata") {
        DbtImpactUi.background(project, "Refresh Metabase consumers") { service.refresh(DbtImpactUi.read { DbtWorkspace.get(project) }) }(_ => update())
      }
      buttons.add(refresh); buttons.add(DbtImpactUi.button("Configure…") { com.intellij.openapi.options.ShowSettingsUtil.getInstance().editConfigurable(project, new DbtConsumersConfigurable(project)) })
      panel.add(buttons, BorderLayout.SOUTH); DbtImpactUi.show(project, "dbt Consumers" + focus.map(s => ": " + s.uniqueId).getOrElse(""), panel)
    }
  }
}

final class DbtAirflowAction extends AnAction {
  override def actionPerformed(event: AnActionEvent): Unit = Option(event.getProject).foreach { project =>
    DbtImpactUi.background(project, "Resolve declarative dbt Airflow context") { DbtImpactUi.read { DbtAirflow.tasks(DbtWorkspace.get(project)) } } { tasks =>
      val panel = DbtImpactUi.panel()
      panel.add(new JLabel(if (tasks.isEmpty) "No mappings. Add .dbt-airflow.yml in a dbt project; see integrations documentation." else "Explicit mappings only; DAG Python is never evaluated."), BorderLayout.NORTH)
      val list = new JList[String](tasks.map(t => t.dag + " / " + t.task + " · " + t.project + " · " + t.selection + " exclude: " + t.exclude +
        " · " + t.resources.map(_.uniqueId).mkString(", ") + " · " + t.problems.mkString("; ") + " · evidence: " + t.evidence + ":" + t.offset +
        (if (t.ingestion.nonEmpty) " · upstream ingestion: " + t.ingestion.mkString(", ") else "")).toArray)
      panel.add(new JScrollPane(list), BorderLayout.CENTER)
      val buttons = new JPanel(new FlowLayout(FlowLayout.LEFT))
      def selected: Option[DbtAirflowTask] = Option(list.getSelectedIndex).filter(i => i >= 0 && i < tasks.size).map(tasks)
      buttons.add(DbtImpactUi.button("Open mapping definition") { selected.foreach(t => DbtImpactUi.open(project, t.evidence, t.offset)) })
      buttons.add(DbtImpactUi.button("Open Airflow task") { selected match {
        case Some(t) if t.url.nonEmpty => t.url.foreach(BrowserUtil.browse)
        case _ => Messages.showInfoMessage(project, "Set airflow_url in .dbt-airflow.yml and choose a task.", "Airflow context")
      } })
      panel.add(buttons, BorderLayout.SOUTH); DbtImpactUi.show(project, "dbt Airflow Context", panel)
    }
  }
}

final class DbtAgentAction extends AnAction {
  override def actionPerformed(event: AnActionEvent): Unit = Option(event.getProject).foreach { project =>
    val service = project.getService(classOf[DbtAgent]); val panel = DbtImpactUi.panel()
    val details = new JTextArea; details.setEditable(false); details.setLineWrap(true); details.setWrapStyleWord(true)
    def refresh(): Unit = details.setText(service.endpoint.map { case (url, token) =>
      "Read-only local tools are running for this IDE project.\nEndpoint: " + url + "\nAuthorization: Bearer " + token +
        "\nSchema: 1.0 · JSON-RPC initialize, tools/list, tools/call.\nToken is temporary; stop/restart rotates it. Do not publish it."
    }.getOrElse("Local tools are stopped. Enable local agent tools in Settings > dbt Integrations, then Start.\nNo AI subscription or warehouse connection is required.\nOnly read tools are exposed. See docs/integrations.md for protocol examples."))
    refresh(); panel.add(new JScrollPane(details), BorderLayout.CENTER)
    val buttons = new JPanel(new FlowLayout(FlowLayout.LEFT))
    buttons.add(DbtImpactUi.button("Configure…") { com.intellij.openapi.options.ShowSettingsUtil.getInstance().editConfigurable(project, new DbtConsumersConfigurable(project)); refresh() })
    buttons.add(DbtImpactUi.button("Start") { service.start().left.foreach(error => Messages.showInfoMessage(project, error, "Local dbt tools")); refresh() })
    buttons.add(DbtImpactUi.button("Stop") { service.stop(); refresh() })
    buttons.add(DbtImpactUi.button("Copy endpoint and authorization") { service.endpoint.foreach { case (url, token) => DbtImpactUi.copy(url + "\nAuthorization: Bearer " + token) } })
    panel.add(buttons, BorderLayout.SOUTH); DbtImpactUi.show(project, "dbt Local Agent Tools", panel)
  }
}
