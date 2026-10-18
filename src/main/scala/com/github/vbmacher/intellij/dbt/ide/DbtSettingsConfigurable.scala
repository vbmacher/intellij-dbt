package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project.{DbtProjectSettings, DbtRunOptions}
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.{Configurable, ConfigurationException}
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.{JBCheckBox, JBTextField}
import com.intellij.util.ui.JBUI
import java.awt.{Dimension, GridBagConstraints, GridBagLayout, Insets, Rectangle}
import javax.swing.{JComboBox, JComponent, JLabel, JPanel, JSpinner, Scrollable, SpinnerNumberModel, SwingConstants}
import scala.jdk.CollectionConverters._

final class DbtSettingsConfigurable(project: Project) extends Configurable with Configurable.NoMargin {
  private val settings = DbtProjectSettings.get(project)
  private val root = new JComboBox[String]()
  private val executable = new TextFieldWithBrowseButton {
    override protected def getIconTooltip: String = "Choose a dbt executable, script, or custom tool"
  }
  private val executableArguments = new JBTextField
  private val projectRoot = new TextFieldWithBrowseButton {
    override protected def getIconTooltip: String = "Choose a dbt project root directory"
  }
  private val profiles = new TextFieldWithBrowseButton {
    override protected def getIconTooltip: String = "Choose a dbt profiles directory"
  }
  private val artifacts = new JBTextField
  private val target = new JBTextField
  private val stateDirectory = new TextFieldWithBrowseButton
  private val defer = new JBCheckBox("Defer unselected upstream resources to baseline state")
  private val favorState = new JBCheckBox("Favor baseline relations (dbt Core 1.4+)")
  private val limit = new JSpinner(new SpinnerNumberModel(10, 1, 1000, 1))
  private val colors = new JBCheckBox("Colored output")
  private val notifications = new JBCheckBox("Desktop notifications when dbt commands finish")
  private val upstream = new JSpinner(new SpinnerNumberModel(5, 1, 20, 1))
  private val downstream = new JSpinner(new SpinnerNumberModel(5, 1, 20, 1))
  private val direction = new JComboBox[String](Array("LR", "TB", "RL", "BT"))
  private val edges = new JComboBox[String](Array("bezier", "taxi", "round-taxi", "segments", "straight", "unbundled-bezier", "haystack"))
  private val exposures = new JBCheckBox("Show dbt exposures in lineage")
  private var selectedRoot: String = ""
  private var drafts = Map.empty[String, DbtRunOptions]
  private var baseline = ""

  override def getDisplayName: String = "dbt Workspace"

  override def createComponent(): JComponent = {
    val panel = new JPanel(new GridBagLayout) with Scrollable {
      override def getPreferredScrollableViewportSize: Dimension = getPreferredSize

      override def getScrollableTracksViewportWidth: Boolean = true

      override def getScrollableTracksViewportHeight: Boolean = false

      override def getScrollableUnitIncrement(visible: Rectangle, orientation: Int, direction: Int): Int = JBUI.scale(16)

      override def getScrollableBlockIncrement(visible: Rectangle, orientation: Int, direction: Int): Int =
        if (orientation == SwingConstants.VERTICAL) visible.height else visible.width
    }
    // NoMargin keeps this Scrollable panel directly in the IDE viewport; supply the usual padding here.
    panel.setBorder(JBUI.Borders.empty(11, 16, 16, 16))
    executable.addBrowseFolderListener(project, FileChooserDescriptorFactory.singleFile()
      .withTitle("Select dbt executable")
      .withDescription("Choose a dbt executable, wrapper script, or custom tool."))
    projectRoot.addBrowseFolderListener(project, FileChooserDescriptorFactory.singleDir()
      .withTitle("Select dbt project root")
      .withDescription("Choose the directory containing dbt_project.yml."))
    profiles.addBrowseFolderListener(project, FileChooserDescriptorFactory.singleDir()
      .withTitle("Select dbt profiles directory")
      .withDescription("Choose the directory containing profiles.yml."))
    stateDirectory.addBrowseFolderListener(project, FileChooserDescriptorFactory.singleDir()
      .withTitle("Select baseline state directory").withDescription("Choose a separate directory containing manifest.json."))
    executableArguments.setToolTipText("Arguments passed before the dbt command. Quote arguments containing spaces.")
    exposures.setToolTipText("Show dashboards, notebooks, and other downstream consumers defined as dbt exposures, with their dependency links in the lineage graph.")
    val rows = Vector("Owning dbt root" -> root, "dbt executable" -> executable, "Executable arguments" -> executableArguments, "Project root override" -> projectRoot,
      "Profiles directory" -> profiles, "Artifact directory" -> artifacts, "Active target (blank uses profile default)" -> target,
      "Baseline state directory" -> stateDirectory, "" -> defer, "" -> favorState,
      "Preview row limit" -> limit, "" -> colors, "" -> notifications, "Upstream depth" -> upstream,
      "Downstream depth" -> downstream, "Layout direction" -> direction, "Edge style" -> edges, "" -> exposures)
    rows.zipWithIndex.foreach { case ((label, field), index) =>
      field.setMinimumSize(new Dimension(0, field.getMinimumSize.height))
      val c = new GridBagConstraints
      c.gridy = index
      c.gridx = 0
      c.anchor = GridBagConstraints.WEST
      c.insets = new Insets(4, 5, 4, 10)
      panel.add(new JLabel(label), c)
      c.gridx = 1
      c.weightx = 1
      c.fill = GridBagConstraints.HORIZONTAL
      panel.add(field, c)
    }
    root.addActionListener(_ => {
      if (selectedRoot.nonEmpty) drafts += selectedRoot -> readOptions()
      selectedRoot = Option(root.getSelectedItem).fold("")(_.toString)
      fill(drafts.getOrElse(selectedRoot, new DbtRunOptions))
    })
    reset()
    panel
  }

  private def fill(o: DbtRunOptions): Unit = {
    executable.setText(o.executable)
    executableArguments.setText(o.executableArguments)
    projectRoot.setText(o.projectRoot)
    profiles.setText(o.profilesDirectory)
    artifacts.setText(o.artifactDirectory)
    target.setText(o.target)
    stateDirectory.setText(o.stateDirectory)
    defer.setSelected(o.defer)
    favorState.setSelected(o.favorState)
    limit.setValue(Int.box(o.previewLimit.max(1).min(1000)))
    colors.setSelected(o.colors)
    notifications.setSelected(o.notifications)
  }

  private def readOptions(): DbtRunOptions = {
    val o = drafts.getOrElse(selectedRoot, new DbtRunOptions).copy()
    o.executable = executable.getText.trim
    o.executableArguments = executableArguments.getText.trim
    o.projectRoot = projectRoot.getText.trim
    o.profilesDirectory = profiles.getText.trim
    o.artifactDirectory = artifacts.getText.trim
    o.target = target.getText.trim
    o.stateDirectory = stateDirectory.getText.trim
    o.defer = defer.isSelected
    o.favorState = favorState.isSelected
    o.previewLimit = limit.getValue.asInstanceOf[Number].intValue()
    o.colors = colors.isSelected
    o.notifications = notifications.isSelected
    o
  }

  private def snapshot(): String = {
    val options = drafts.updated(selectedRoot, readOptions()).toVector.sortBy(_._1).map { case (p, o) =>
      (p +: o.values).mkString("\u0000")
    }
    (options ++ Vector(upstream.getValue, downstream.getValue, direction.getSelectedItem, edges.getSelectedItem,
      exposures.isSelected).map(_.toString)).mkString("\u0001")
  }

  override def reset(): Unit = {
    selectedRoot = ""
    drafts = settings.getState.projects.asScala.toMap.view.mapValues(_.copy()).toMap
    // Root paths are recorded by normal workspace discovery, so opening Settings never scans the project on the EDT.
    root.removeAllItems()
    val paths = (drafts.keys.toVector ++ Option(project.getBasePath)).distinct.sorted
    paths.foreach(root.addItem)
    selectedRoot = Option(root.getSelectedItem).fold("")(_.toString)
    fill(drafts.getOrElse(selectedRoot, new DbtRunOptions))
    upstream.setValue(Int.box(settings.getState.upstream))
    downstream.setValue(Int.box(settings.getState.downstream))
    direction.setSelectedItem(settings.getState.direction)
    edges.setSelectedItem(settings.getState.edgeStyle)
    exposures.setSelected(settings.getState.exposures)
    baseline = snapshot()
  }

  override def isModified: Boolean = snapshot() != baseline

  override def disposeUIResources(): Unit = {
    executable.dispose()
    projectRoot.dispose()
    profiles.dispose()
    stateDirectory.dispose()
  }

  override def apply(): Unit = {
    if (executable.getText.trim.isEmpty || artifacts.getText.trim.isEmpty) throw new ConfigurationException("Executable and artifact directory must not be blank.")
    drafts += selectedRoot -> readOptions()
    settings.getState.projects = new java.util.TreeMap[String, DbtRunOptions](drafts.asJava)
    settings.getState.upstreamDepth = upstream.getValue.asInstanceOf[Number].intValue()
    settings.getState.downstreamDepth = downstream.getValue.asInstanceOf[Number].intValue()
    settings.getState.direction = direction.getSelectedItem.toString
    settings.getState.edgeStyle = edges.getSelectedItem.toString
    settings.getState.exposures = exposures.isSelected
    settings.changed()
    baseline = snapshot()
  }
}
