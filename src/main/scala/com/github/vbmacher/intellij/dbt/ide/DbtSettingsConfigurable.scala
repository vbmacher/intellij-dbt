package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project.{DbtProjectSettings, DbtRunOptions, DbtVariableOverrides}
import com.intellij.openapi.fileChooser.{FileChooser, FileChooserDescriptorFactory}
import com.intellij.openapi.options.{Configurable, ConfigurationException, ShowSettingsUtil}
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.{TitledSeparator, ToolbarDecorator}
import com.intellij.ui.components.{JBCheckBox, JBLabel, JBList, JBScrollPane, JBTextField}
import com.intellij.util.ui.{JBUI, UIUtil}
import java.awt.{BorderLayout, Dimension, GridBagConstraints, GridBagLayout, Insets, Rectangle}
import java.nio.file.{InvalidPathException, Paths}
import javax.swing._
import javax.swing.table.DefaultTableModel
import scala.jdk.CollectionConverters._

final class DbtSettingsConfigurable(project: Project) extends Configurable with Configurable.NoMargin with Configurable.NoScroll {
  private val settings = DbtProjectSettings.get(project)
  private val integrations = new DbtConsumersConfigurable(project)
  private val tabs = new JTabbedPane
  private val root = new JComboBox[String]()
  private val executable = new TextFieldWithBrowseButton {
    override protected def getIconTooltip: String = "Choose a dbt executable, script, or custom tool"
  }
  private val executableArguments = new JBTextField
  private val environmentFiles = new TextFieldWithBrowseButton
  private val loadEnvironmentFiles = new JBCheckBox("Load environment files")
  private val engine = new JComboBox[String](Array("core", "v2"))
  private val dbtVersion = new JBTextField
  private val profilesFile = new TextFieldWithBrowseButton
  private val adapter = new JBTextField
  private val variables = new JTextArea(3, 20)
  private val variablesPane = new JBScrollPane(variables)
  private val environmentModel = new DefaultTableModel(Array[AnyRef]("Variable name", "Value"), 0)
  private val environmentVariables = new JTable(environmentModel)
  private val catalog = new TextFieldWithBrowseButton
  private val stateManifest = new TextFieldWithBrowseButton
  private val infoSchema = new TextFieldWithBrowseButton
  private val builtinModel = new DefaultTableModel(Array[AnyRef]("Builtin name", "Local source file"), 0)
  private val builtinSources = new JTable(builtinModel)

  private def mappings(table: JTable, model: DefaultTableModel): JPanel = {
    table.setPreferredScrollableViewportSize(new Dimension(300, 65))
    table.putClientProperty("terminateEditOnFocusLost", java.lang.Boolean.TRUE)
    val panel = ToolbarDecorator.createDecorator(table).disableUpDownActions()
      .setAddAction(_ => model.addRow(Array[AnyRef]("", "")))
      .setRemoveAction(_ => table.getSelectedRows.reverse.foreach(model.removeRow)).createPanel()
    panel.setMinimumSize(JBUI.size(0, 85))
    panel.setPreferredSize(JBUI.size(300, 120))
    panel
  }

  private def fillMappings(model: DefaultTableModel, values: java.util.Map[String, String]): Unit = {
    model.setRowCount(0)
    values.asScala.toVector.sortBy(_._1).foreach { case (name, value) => model.addRow(Array[AnyRef](name, value)) }
  }

  private def readMappings(table: JTable, model: DefaultTableModel, allowEmpty: Boolean): java.util.Map[String, String] = {
    if (table.isEditing) table.getCellEditor.stopCellEditing()
    val values = new java.util.TreeMap[String, String]()
    (0 until model.getRowCount).foreach { row =>
      val name = Option(model.getValueAt(row, 0)).fold("")(_.toString.trim)
      val value = Option(model.getValueAt(row, 1)).fold("")(_.toString)
      if (name.nonEmpty && (allowEmpty || value.trim.nonEmpty)) values.put(name, if (allowEmpty) value else value.trim)
    }
    values
  }

  private val projectRoot = new TextFieldWithBrowseButton {
    override protected def getIconTooltip: String = "Choose a dbt project root directory"
  }
  private val profiles = new TextFieldWithBrowseButton {
    override protected def getIconTooltip: String = "Choose a dbt profiles directory"
  }
  private val artifacts = new JBTextField
  private val manifest = new TextFieldWithBrowseButton
  private val target = new JBTextField
  private val stateDirectory = new TextFieldWithBrowseButton
  private val defer = new JBCheckBox("Defer unselected upstream resources to baseline state")
  private val favorState = new JBCheckBox("Favor baseline relations (dbt Core 1.4+)")
  private val formatter = new JComboBox[String](Array("sqlfmt", "sqlfluff"))
  private val formatterExecutable = new TextFieldWithBrowseButton
  private val formatOnSave = new JBCheckBox("Format dbt SQL on save")
  private val limit = new JSpinner(new SpinnerNumberModel(10, 1, 1000, 1))
  private val colors = new JBCheckBox("Colored output")
  private val notifications = new JBCheckBox("Desktop notifications when dbt commands finish")
  private val upstream = new JSpinner(new SpinnerNumberModel(5, 1, 20, 1))
  private val downstream = new JSpinner(new SpinnerNumberModel(5, 1, 20, 1))
  private val direction = new JComboBox[String](Array("LR", "TB", "RL", "BT"))
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
    // Each tab scrolls independently; supply the usual settings padding here.
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
    environmentFiles.setName("dbt.environmentFiles")
    environmentFiles.addBrowseFolderListener(project, FileChooserDescriptorFactory.singleFile()
      .withTitle("Select environment file").withShowHiddenFiles(true))
    environmentFiles.setToolTipText("Environment files separated by semicolons, relative to the owning dbt root or absolute. Later files override earlier files. Blank uses .env. Environment variables from IntelliJ settings override files.")
    Vector("engine" -> engine, "dbtVersion" -> dbtVersion, "profilesFile" -> profilesFile, "adapter" -> adapter,
      "variables" -> variables, "environmentVariables" -> environmentVariables, "catalogPath" -> catalog,
      "stateManifestPath" -> stateManifest, "infoSchemaDirectory" -> infoSchema, "builtinSources" -> builtinSources,
      "loadEnvironmentFiles" -> loadEnvironmentFiles).foreach { case (name, field) => field.setName("dbt." + name) }
    Vector(profilesFile -> "Select profiles YAML",
      catalog -> "Select catalog JSON", stateManifest -> "Select state manifest JSON").foreach { case (field, title) =>
      field.addBrowseFolderListener(project, FileChooserDescriptorFactory.singleFile().withTitle(title))
    }
    infoSchema.addBrowseFolderListener(project, FileChooserDescriptorFactory.singleDir().withTitle("Select Information Schema v1 directory"))
    dbtVersion.getEmptyText.setText("From require-dbt-version, or 1.10")
    profilesFile.setToolTipText("Optional profiles file for editor navigation, relative to the owning dbt root or absolute. Commands use Profiles directory.")
    adapter.setToolTipText("Optional adapter for editor navigation. Blank uses the selected profile output type.")
    variables.setToolTipText("JSON object of dbt variable overrides, for example {\"start_date\": \"2026-03-01\"}. Passed to dbt as --vars and used by editor navigation.")
    variablesPane.setMinimumSize(new Dimension(0, 60))
    variablesPane.setPreferredSize(new Dimension(300, 65))
    variables.setLineWrap(true)
    variables.setWrapStyleWord(true)
    environmentVariables.setToolTipText("Override inherited environment and environment files for dbt commands and formatters. Empty values are allowed.")
    catalog.setToolTipText("Optional catalog JSON, relative to the owning dbt root or absolute. Blank uses catalog.json in the artifact directory.")
    stateManifest.setToolTipText("Optional manifest for navigation to state resources, relative to the owning dbt root or absolute. Commands use Baseline state directory.")
    infoSchema.setToolTipText("Information Schema v1 directory, relative to the owning dbt root or absolute. Requires engine v2.")
    loadEnvironmentFiles.addActionListener(_ => environmentFiles.setEnabled(loadEnvironmentFiles.isSelected))
    stateDirectory.addBrowseFolderListener(project, FileChooserDescriptorFactory.singleDir()
      .withTitle("Select baseline state directory").withDescription("Choose a separate directory containing manifest.json."))
    manifest.addBrowseFolderListener(project, FileChooserDescriptorFactory.singleFile().withTitle("Select dbt manifest JSON"))
    manifest.setToolTipText("Optional manifest file, absolute or relative to the execution project. Blank uses manifest.json in the artifact directory.")
    formatterExecutable.addBrowseFolderListener(project, FileChooserDescriptorFactory.singleFile().withTitle("Select SQL formatter executable"))
    executable.setToolTipText("dbt, a wrapper script, or a tool such as uv, poetry, bash or zsh. Runs in the execution project directory.")
    executableArguments.getEmptyText.setText("For uv: run dbt; for zsh: scripts/run_dbt.zsh")
    executableArguments.setToolTipText("Passed before every dbt command. Example: uv + run dbt → uv run dbt test. Quote paths with spaces; shell expressions are not evaluated.")
    upstream.setToolTipText("How many dependency levels to show before the focused resource in Lineage. 1 shows its direct inputs; 2 also shows their inputs. Accepts 1–20.")
    downstream.setToolTipText("How many dependent levels to show after the focused resource in Lineage. 1 shows resources that use it directly; 2 also shows resources that use those. Accepts 1–20.")
    val rows = Vector("Owning dbt root" -> root,
      "Engine" -> engine, "dbt version (optional)" -> dbtVersion, "Adapter (optional)" -> adapter,
      "Profiles file for navigation (optional)" -> profilesFile, "Variables (JSON)" -> variablesPane,
      "Environment variables" -> mappings(environmentVariables, environmentModel),
      "Local builtin definitions" -> mappings(builtinSources, builtinModel),
      "Catalog file (optional)" -> catalog, "State manifest for navigation (optional)" -> stateManifest,
      "Information Schema v1 directory (optional)" -> infoSchema, "Executable or external tool" -> executable, "Arguments before dbt command" -> executableArguments, "Project root override" -> projectRoot,
      "" -> loadEnvironmentFiles, "Environment files" -> environmentFiles, "Profiles directory" -> profiles, "Artifact directory" -> artifacts, "Manifest file (optional)" -> manifest, "Active target (blank uses profile default)" -> target,
      "Baseline state directory" -> stateDirectory, "" -> defer, "" -> favorState,
      "SQL formatter" -> formatter, "Formatter executable (optional)" -> formatterExecutable, "" -> formatOnSave,
      "Preview row limit" -> limit, "" -> colors, "" -> notifications, "Upstream levels to show" -> upstream,
      "Downstream levels to show" -> downstream, "Layout direction" -> direction)
    rows.foreach { case (_, field) => field.setMinimumSize(new Dimension(0, field.getMinimumSize.height)) }
    DbtUi.labeledRows(panel, new Insets(4, 5, 4, 10), rows)
    root.addActionListener(_ => {
      if (selectedRoot.nonEmpty) drafts += selectedRoot -> readOptions()
      selectRoot()
    })
    tabs.addTab("General", new JBScrollPane(panel))
    tabs.addTab("Integrations", new JBScrollPane(integrations.createComponent()))
    reset()
    tabs
  }

  private def selectRoot(): Unit = {
    selectedRoot = Option(root.getSelectedItem).fold("")(_.toString)
    fill(drafts.getOrElse(selectedRoot, new DbtRunOptions))
  }

  private def fill(o: DbtRunOptions): Unit = {
    executable.setText(o.executable)
    executableArguments.setText(o.executableArguments)
    environmentFiles.setText(o.environmentFiles)
    loadEnvironmentFiles.setSelected(o.loadEnvironmentFiles)
    environmentFiles.setEnabled(o.loadEnvironmentFiles)
    engine.setSelectedItem(o.engine)
    dbtVersion.setText(o.dbtVersion)
    profilesFile.setText(o.profilesFile)
    adapter.setText(o.adapter)
    variables.setText(o.variables)
    fillMappings(environmentModel, o.environmentVariables)
    catalog.setText(o.catalogPath)
    stateManifest.setText(o.stateManifestPath)
    infoSchema.setText(o.infoSchemaDirectory)
    fillMappings(builtinModel, o.builtinSources)
    projectRoot.setText(o.projectRoot)
    profiles.setText(o.profilesDirectory)
    artifacts.setText(o.artifactDirectory)
    manifest.setText(o.manifestPath)
    target.setText(o.target)
    stateDirectory.setText(o.stateDirectory)
    defer.setSelected(o.defer)
    favorState.setSelected(o.favorState)
    formatter.setSelectedItem(o.formatter)
    formatterExecutable.setText(o.formatterExecutable)
    formatOnSave.setSelected(o.formatOnSave)
    limit.setValue(Int.box(o.previewLimit.max(1).min(1000)))
    colors.setSelected(o.colors)
    notifications.setSelected(o.notifications)
  }

  private def readOptions(): DbtRunOptions = {
    val o = drafts.getOrElse(selectedRoot, new DbtRunOptions).copy()
    o.executable = executable.getText.trim
    o.executableArguments = executableArguments.getText.trim
    o.environmentFiles = environmentFiles.getText.trim
    o.loadEnvironmentFiles = loadEnvironmentFiles.isSelected
    o.engine = engine.getSelectedItem.toString
    o.dbtVersion = dbtVersion.getText.trim
    o.profilesFile = profilesFile.getText.trim
    o.adapter = adapter.getText.trim
    o.variables = variables.getText.trim
    o.environmentVariables = readMappings(environmentVariables, environmentModel, allowEmpty = true)
    o.catalogPath = catalog.getText.trim
    o.stateManifestPath = stateManifest.getText.trim
    o.infoSchemaDirectory = infoSchema.getText.trim
    o.builtinSources = readMappings(builtinSources, builtinModel, allowEmpty = false)
    o.projectRoot = projectRoot.getText.trim
    o.profilesDirectory = profiles.getText.trim
    o.artifactDirectory = artifacts.getText.trim
    o.manifestPath = manifest.getText.trim
    o.target = target.getText.trim
    o.stateDirectory = stateDirectory.getText.trim
    o.defer = defer.isSelected
    o.favorState = favorState.isSelected
    o.formatter = formatter.getSelectedItem.toString
    o.formatterExecutable = formatterExecutable.getText.trim
    o.formatOnSave = formatOnSave.isSelected
    o.previewLimit = limit.getValue.asInstanceOf[Number].intValue()
    o.colors = colors.isSelected
    o.notifications = notifications.isSelected
    o
  }

  private def snapshot(): String = {
    val options = drafts.updated(selectedRoot, readOptions()).toVector.sortBy(_._1).map { case (p, o) =>
      (p +: o.values).mkString("\u0000")
    }
    (options ++ Vector(upstream.getValue, downstream.getValue, direction.getSelectedItem).map(_.toString)).mkString("\u0001")
  }

  override def reset(): Unit = {
    selectedRoot = ""
    drafts = settings.getState.projects.asScala.toMap.view.mapValues(_.copy()).toMap
    // Root paths are recorded by normal workspace discovery, so opening Settings never scans the project on the EDT.
    root.removeAllItems()
    val paths = (drafts.keys.toVector ++ Option(project.getBasePath)).distinct.sorted
    paths.foreach(root.addItem)
    selectRoot()
    upstream.setValue(Int.box(settings.getState.upstream))
    downstream.setValue(Int.box(settings.getState.downstream))
    direction.setSelectedItem(settings.getState.direction)
    integrations.reset()
    baseline = snapshot()
  }

  override def isModified: Boolean = snapshot() != baseline || integrations.isModified

  override def disposeUIResources(): Unit = {
    executable.dispose()
    environmentFiles.dispose()
    profilesFile.dispose()
    catalog.dispose()
    stateManifest.dispose()
    infoSchema.dispose()
    projectRoot.dispose()
    profiles.dispose()
    stateDirectory.dispose()
    manifest.dispose()
    formatterExecutable.dispose()
    integrations.disposeUIResources()
  }

  override def apply(): Unit = {
    if (executable.getText.trim.isEmpty || artifacts.getText.trim.isEmpty) throw new ConfigurationException("Executable and artifact directory must not be blank.")
    drafts += selectedRoot -> readOptions()
    drafts.values.foreach { options =>
      DbtVariableOverrides.parse(options.variables).left.foreach(message => throw new ConfigurationException(message))
      if (options.environmentVariables.keySet().asScala.exists(!_.matches("[A-Za-z_][A-Za-z_0-9]*")))
        throw new ConfigurationException("Environment variable names must start with a letter or underscore and contain only letters, digits or underscores.")
    }
    if (integrations.isModified) integrations.apply()
    settings.getState.projects = new java.util.TreeMap[String, DbtRunOptions](drafts.asJava)
    settings.getState.upstreamDepth = upstream.getValue.asInstanceOf[Number].intValue()
    settings.getState.downstreamDepth = downstream.getValue.asInstanceOf[Number].intValue()
    settings.getState.direction = direction.getSelectedItem.toString
    settings.changed()
    baseline = snapshot()
  }
}

object DbtSettingsConfigurable {
  def showIntegrations(project: Project): Unit = {
    val configurable = new DbtSettingsConfigurable(project)
    ShowSettingsUtil.getInstance().editConfigurable(project, configurable,
      new Runnable {
        override def run(): Unit = configurable.tabs.setSelectedIndex(1)
      })
  }
}

/** The Integrations tab: Metabase, the local MCP server and local Airflow DAG discovery. */
final class DbtConsumersConfigurable(project: Project) extends Configurable {
  private val url = new JBTextField()
  private val secret = new JPasswordField()
  private val agent = new JBCheckBox("Enable local read-only MCP server")
  private val airflowPaths = new DefaultListModel[String]
  private val airflowFolders = new JBList[String](airflowPaths)
  private val airflowUrl = new JBTextField
  private val panel = new JPanel(new GridBagLayout)

  private def help(text: String): JBLabel = {
    val label = new JBLabel("<html>" + text + "</html>")
    label.setForeground(UIUtil.getContextHelpForeground)
    label
  }

  private def fieldWithHelp(field: JComponent, text: String): JPanel = {
    val result = new JPanel(new BorderLayout(0, JBUI.scale(4)))
    result.add(field, BorderLayout.NORTH)
    result.add(help(text), BorderLayout.CENTER)
    result
  }

  private def section(title: String, fields: Seq[(String, JComponent)]): JPanel = {
    val result = new JPanel(new BorderLayout(0, JBUI.scale(8)))
    val form = new JPanel(new GridBagLayout)
    result.add(new TitledSeparator(title), BorderLayout.NORTH)
    DbtUi.labeledRows(form, JBUI.insets(4, 8), fields)
    val layout = form.getLayout.asInstanceOf[GridBagLayout]
    form.getComponents.collect { case label: JLabel if label.getText.nonEmpty => label }.foreach { label =>
      label.setPreferredSize(new Dimension(JBUI.scale(90), label.getPreferredSize.height))
      val constraints = layout.getConstraints(label)
      constraints.anchor = GridBagConstraints.FIRST_LINE_START
      layout.setConstraints(label, constraints)
    }
    result.add(form, BorderLayout.CENTER)
    result
  }

  private def stack(rows: Vector[JComponent]): Unit = {
    rows.zipWithIndex.foreach { case (field, row) =>
      val c = new GridBagConstraints
      c.gridx = 0;
      c.gridy = row;
      c.weightx = 1;
      c.fill = GridBagConstraints.HORIZONTAL
      c.anchor = GridBagConstraints.NORTHWEST;
      c.insets = JBUI.insets(4, 8, 12, 8)
      panel.add(field, c)
    }
    val filler = new GridBagConstraints
    filler.gridy = rows.size
    filler.weighty = 1
    panel.add(new JPanel, filler)
  }

  private val metabase = section("Metabase", Vector(
    "Server URL" -> fieldWithHelp(url, "Optional. Use the final HTTP or HTTPS server URL, without embedded credentials."),
    "API key" -> fieldWithHelp(secret, "Stored in IDE Password Safe. Clear the field and click Apply to remove the saved key.")))
  secret.setToolTipText("Stored in IDE Password Safe. Clear this field and click Apply to remove the saved API key.")
  airflowFolders.setVisibleRowCount(3)
  airflowFolders.getEmptyText.setText("No DAG folders configured")
  airflowFolders.setToolTipText("DAG folders or Airflow project directories, scanned recursively for Python files. Relative paths use the IDEA project directory.")
  airflowUrl.setToolTipText("Optional HTTP or HTTPS Airflow web URL, without credentials. Used only for browser links.")
  private val folderPanel = ToolbarDecorator.createDecorator(airflowFolders).disableUpDownActions()
    .setAddActionName("Add folder…").setRemoveActionName("Remove folder")
    .setAddAction(_ => {
      FileChooser.chooseFiles(FileChooserDescriptorFactory.multiDirs().withTitle("Select local Airflow DAG folders or projects"),
        project, null).foreach(f => if (!airflowPaths.contains(f.getPath)) airflowPaths.addElement(f.getPath))
    })
    .setRemoveAction(_ => {
      airflowFolders.getSelectedIndices.reverse.foreach(airflowPaths.remove)
    }).createPanel()
  private val airflow = section("Airflow — local DAG discovery", Vector(
    "DAG folders" -> fieldWithHelp(folderPanel, "Scan local folders and Airflow checkouts recursively. Remove all folders to stop discovery."),
    "Web URL" -> fieldWithHelp(airflowUrl, "Optional. Used only to open detected DAGs and tasks in your browser.")))
  private val mcp = section("AI assistants", Vector("" -> fieldWithHelp(agent,
    "Start the server explicitly from Tools → dbt Local MCP Server. Disabling this option stops it.")))
  stack(Vector(metabase, airflow, mcp))

  private def settings = project.getService(classOf[DbtConsumersSettings]).getState

  private def service = project.getService(classOf[DbtConsumers])

  private def folderValues: Vector[String] = (0 until airflowPaths.size()).map(airflowPaths.get).toVector

  override def getDisplayName: String = "Integrations"

  override def createComponent(): JComponent = {
    reset();
    panel
  }

  override def isModified: Boolean = url.getText != settings.metabaseUrl || agent.isSelected != settings.agentEnabled ||
    new String(secret.getPassword) != service.apiKey || folderValues != settings.airflowPaths.asScala.toVector || airflowUrl.getText.trim != settings.airflowUrl

  override def apply(): Unit = {
    val value = url.getText.trim
    if (value.nonEmpty && DbtAirflow.safeUrl(value).isEmpty) throw new ConfigurationException("Use an http(s) Metabase URL without embedded credentials.")
    val airflowLink = airflowUrl.getText.trim
    if (airflowLink.nonEmpty && DbtAirflow.safeUrl(airflowLink).isEmpty)
      throw new ConfigurationException("Use an http(s) Airflow URL without embedded credentials.")
    try folderValues.foreach(Paths.get(_))
    catch {
      case _: InvalidPathException => throw new ConfigurationException("Use valid local Airflow directory paths.")
    }
    settings.airflowPaths = new java.util.ArrayList[String](folderValues.asJava)
    settings.airflowUrl = airflowLink
    DbtProjectSettings.get(project).changed()
    settings.metabaseUrl = value;
    settings.agentEnabled = agent.isSelected
    service.setSecret(new String(secret.getPassword))
    service.clear();
    if (!agent.isSelected) project.getService(classOf[DbtAgent]).stop()
  }

  override def reset(): Unit = {
    url.setText(settings.metabaseUrl);
    agent.setSelected(settings.agentEnabled);
    secret.setText(service.apiKey)
    airflowPaths.clear();
    settings.airflowPaths.asScala.foreach(airflowPaths.addElement)
    airflowUrl.setText(settings.airflowUrl)
  }

  override def disposeUIResources(): Unit = secret.setText("")
}
