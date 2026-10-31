package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project.{DbtEnvironment, DbtExecutionContext, DbtProjectSettings, DbtRoot, DbtRunOptions, DbtVariableOverrides}
import com.github.vbmacher.intellij.dbt.syntax.DbtYaml
import com.intellij.openapi.fileChooser.{FileChooser, FileChooserDescriptorFactory}
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.options.{Configurable, ConfigurationException, ShowSettingsUtil}
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.{ComponentValidator, TextFieldWithBrowseButton, ValidationInfo}
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.{TitledSeparator, ToolbarDecorator}
import com.intellij.ui.components.{JBCheckBox, JBLabel, JBList, JBScrollPane, JBTextField}
import com.intellij.util.ui.{JBUI, UIUtil}
import java.awt.{BorderLayout, Dimension, GridBagConstraints, GridBagLayout, Rectangle}
import java.nio.file.{Files, InvalidPathException, Paths}
import javax.swing._
import javax.swing.table.DefaultTableModel
import javax.swing.event.{DocumentEvent, DocumentListener}
import scala.jdk.CollectionConverters._
import scala.util.Try

final class DbtSettingsConfigurable(project: Project) extends Configurable with Configurable.NoMargin with Configurable.NoScroll {
  private val settings = DbtProjectSettings.get(project)
  private val tabs = new JTabbedPane
  private val integrations = new DbtConsumersConfigurable(project, () => tabs.setSelectedIndex(1))
  private val validation = new DbtSettingsValidation
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
      .setRemoveAction(_ => {
        if (!table.isEditing || table.getCellEditor.stopCellEditing()) table.getSelectedRows.reverse.foreach(model.removeRow)
      }).createPanel()
    panel.setMinimumSize(JBUI.size(0, 85))
    panel.setPreferredSize(JBUI.size(300, 120))
    panel.putClientProperty("dbt.input", table)
    panel
  }

  private def fillMappings(table: JTable, model: DefaultTableModel, rows: Vector[(String, String)]): Unit = {
    if (table.isEditing) table.getCellEditor.cancelCellEditing()
    model.setRowCount(0)
    rows.foreach { case (name, value) => model.addRow(Array[AnyRef](name, value)) }
  }

  private def readMappings(table: JTable, model: DefaultTableModel): Vector[(String, String)] = {
    def value(row: Int, column: Int): String = {
      val current = if (table.isEditing && row == table.getEditingRow && column == table.getEditingColumn) {
        table.getEditorComponent match {
          case text: javax.swing.text.JTextComponent => text.getText
          case _ => table.getCellEditor.getCellEditorValue
        }
      } else model.getValueAt(row, column)
      Option(current).fold("")(_.toString)
    }
    (0 until model.getRowCount).map(row => value(row, 0) -> value(row, 1)).toVector
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
  private val deferHelp = new JBLabel
  private val favorHelp = new JBLabel
  private val schemaHelp = new JBLabel
  private val formatterHelp = new JBLabel
  private val navigationHelp = new JBLabel("<html>These settings guide editor navigation; they do not change the dbt executable.</html>")
  navigationHelp.setForeground(UIUtil.getContextHelpForeground)
  navigationHelp.setAllowAutoWrapping(true).setCopyable(true)
  private val executionContext = new JTextArea(7, 20)
  executionContext.setName("dbt.executionContext")
  executionContext.setEditable(false)
  executionContext.setLineWrap(true)
  executionContext.setWrapStyleWord(true)
  executionContext.setFont(UIUtil.getLabelFont)
  executionContext.getAccessibleContext.setAccessibleName("Resolved execution context")
  private val contextHelpText = "Environment: IDE → files → settings overrides; resolved profiles and artifact paths take final precedence.\n" +
    "Environment, variable and wrapper argument values are not repeated.\n" +
    "Run configurations may override these defaults. No commands or connection checks are run."
  private val contextHelp = new JBLabel("<html>" + contextHelpText.replace("\n", "<br>") + "</html>")
  executionContext.getAccessibleContext.setAccessibleDescription(contextHelpText)
  private var selectedRoot: String = ""
  private case class Draft(options: DbtRunOptions, environment: Vector[(String, String)], builtins: Vector[(String, String)], preview: String) {
    def values: Vector[Any] = Vector(options.values, environment, builtins, preview)
  }
  private def savedDraft(options: DbtRunOptions): Draft = Draft(options.copy(),
    options.environmentVariables.asScala.toVector.sortBy(_._1), options.builtinSources.asScala.toVector.sortBy(_._1), options.previewLimit.toString)
  private var drafts = Map.empty[String, Draft]
  private var baseline = Vector.empty[Any]
  private var contextDisposed = false
  private val contextUpdate = new Timer(150, _ => if (!contextDisposed) updateResolvedContext())
  contextUpdate.setRepeats(false)

  private def displayNames(combo: JComboBox[String], names: Map[String, String]): Unit = combo.setRenderer(new DefaultListCellRenderer {
    override def getListCellRendererComponent(list: JList[_], value: Any, index: Int, selected: Boolean, focus: Boolean): java.awt.Component =
      super.getListCellRendererComponent(list, names.getOrElse(String.valueOf(value), String.valueOf(value)), index, selected, focus)
  })

  private def contextFields: Vector[JBTextField] = Vector(target, profiles.getTextField.asInstanceOf[JBTextField],
    projectRoot.getTextField.asInstanceOf[JBTextField], environmentFiles.getTextField.asInstanceOf[JBTextField],
    profilesFile.getTextField.asInstanceOf[JBTextField], manifest.getTextField.asInstanceOf[JBTextField],
    catalog.getTextField.asInstanceOf[JBTextField], dbtVersion)

  private def resolvedContext(draft: Draft): Either[String, (Vector[(JBTextField, String)], String)] = {
    val options = draft.options
    val names = draft.environment.map(_._1.trim)
    if (names.exists(!_.matches("[A-Za-z_][A-Za-z_0-9]*")) || names.distinct.size != names.size)
      Left("Correct the incomplete, invalid or duplicate names in Environment variables to resolve this context.")
    else rootDirectory(selectedRoot).flatMap { directory =>
      Option(directory.findChild("dbt_project.yml")).flatMap(DbtYaml.psiFile(project, _)).flatMap(DbtYaml.mapping).map(directory -> _)
    }.toRight("No readable dbt_project.yml was found for this root. Choose a dbt project to resolve its context.").flatMap { case (directory, config) =>
      try {
        if (options.executable.isEmpty || options.artifactDirectory.isEmpty)
          Left("Enter an executable and artifact directory to resolve this context.")
        else {
          options.environmentVariables = new java.util.TreeMap[String, String](names.zip(draft.environment.map(_._2)).toMap.asJava)
          val readFile: String => Option[com.intellij.psi.PsiFile] = DbtRoot.psiFile(directory, project, _)
          val base = Paths.get(selectedRoot)
          val commandDirectory = base.resolve(options.projectRoot).normalize()
          val profile = DbtExecutionContext.profiles(commandDirectory, options, config, readFile)
          val commandProfiles = profile.directory.resolve("profiles.yml").toString
          val artifactDirectory = commandDirectory.resolve(options.artifactDirectory).normalize()
          val environment = DbtEnvironment.paths(options).map(path => base.resolve(path).normalize().toString -> readFile(path).nonEmpty)
          val defaults = Vector(target -> profile.defaultTarget.getOrElse("Profile default is not resolved locally"),
            profiles.getTextField.asInstanceOf[JBTextField] -> profile.directory.toString,
            projectRoot.getTextField.asInstanceOf[JBTextField] -> selectedRoot,
            environmentFiles.getTextField.asInstanceOf[JBTextField] -> (if (options.loadEnvironmentFiles) base.resolve(".env").toString else "Environment file loading is off"),
            profilesFile.getTextField.asInstanceOf[JBTextField] -> commandProfiles,
            manifest.getTextField.asInstanceOf[JBTextField] -> artifactDirectory.resolve("manifest.json").toString,
            catalog.getTextField.asInstanceOf[JBTextField] -> artifactDirectory.resolve("catalog.json").toString,
            dbtVersion -> (DbtRoot.configuredVersion(config, "") match {
              case "unknown" => "Cannot infer from require-dbt-version; enter the installed version"
              case value => value
            }))
          val executable = DbtExecutionContext.executable(commandDirectory, options.executable)
          val executableDisplay = if (executable.contains('/') || executable.contains('\\')) executable else executable + " (looked up on PATH when run)"
          val targetDisplay = if (options.target.nonEmpty) options.target + " (settings override)"
            else profile.defaultTarget.map(_ + " (profile default)").getOrElse("Not resolved locally; dbt may resolve it or report an error")
          val envDisplay = if (!options.loadEnvironmentFiles) "Disabled" else if (environment.isEmpty) "None configured" else environment.map { case (path, found) =>
            path + (if (found) "" else " (missing; skipped)")
          }.mkString("; ")
          val text = Vector("Command directory: " + commandDirectory +
            (if (readFile(commandDirectory.resolve("dbt_project.yml").toString).isEmpty) " (dbt_project.yml not found locally)" else ""), "Executable: " + executableDisplay,
            "Arguments before dbt command: " + (if (options.executableArguments.isEmpty) "None" else "Configured; see Show command options"),
            "Profiles file for commands: " + commandProfiles + (if (readFile(commandProfiles).isEmpty) " (not found locally)" else ""),
            "Target: " + targetDisplay, "Artifact directory: " + artifactDirectory, "Environment files: " + envDisplay).mkString("\n")
          Right(defaults -> text)
        }
      } catch { case _: InvalidPathException => Left("Correct invalid paths in the settings or DBT_PROFILES_DIR to resolve this context.") }
    }
  }

  private def updateResolvedContext(): Unit = {
    contextUpdate.stop()
    if (selectedRoot.nonEmpty) {
      contextFields.foreach { field =>
        field.getEmptyText.setText("Resolve after correcting the execution settings")
        if (!ComponentValidator.getInstance(field).filter(_.getValidationInfo != null).isPresent)
          field.getAccessibleContext.setAccessibleDescription("Resolve after correcting the execution settings")
      }
      val draft = readDraft()
      val resolved = ReadAction.computeBlocking[Either[String, (Vector[(JBTextField, String)], String)], RuntimeException](() => resolvedContext(draft))
      val text = resolved match {
        case Right((defaults, text)) =>
          defaults.foreach { case (field, value) =>
            field.getEmptyText.setText("Default: " + value)
            if (!ComponentValidator.getInstance(field).filter(_.getValidationInfo != null).isPresent)
              field.getAccessibleContext.setAccessibleDescription("When blank: " + value)
          }
          text
        case Left(message) => message
      }
      if (executionContext.getText != text) {
        executionContext.setText(text)
        executionContext.setCaretPosition(0)
      }
    }
  }

  private def previewLimit(text: String): Option[Int] = Try {
    DbtUi.input(limit).asInstanceOf[JFormattedTextField].getFormatter.stringToValue(text)
  }.toOption.collect { case number: java.lang.Number if number.doubleValue() == number.intValue() && number.intValue() >= 1 && number.intValue() <= 1000 => number.intValue() }

  private def withHelp(field: JComponent, hint: JBLabel): JPanel = {
    val result = new JPanel(new BorderLayout(0, JBUI.scale(4))) {
      override def getMinimumSize: Dimension = new Dimension(0, getPreferredSize.height)
    }
    hint.setForeground(UIUtil.getContextHelpForeground)
    hint.setAllowAutoWrapping(true).setCopyable(true)
    result.add(field, BorderLayout.NORTH)
    result.add(hint, BorderLayout.CENTER)
    result.putClientProperty("dbt.input", field)
    result
  }

  /** Look up the selected project's config without discovering or indexing the workspace. */
  private def rootDirectory(owner: String): Option[com.intellij.openapi.vfs.VirtualFile] =
    ProjectRootManager.getInstance(project).getContentRoots.iterator.flatMap { base =>
      if (base.getPath == owner) Some(base)
      else if (owner.startsWith(base.getPath + "/")) Option(base.findFileByRelativePath(owner.stripPrefix(base.getPath + "/")))
      else None
    }.nextOption().orElse(Option(LocalFileSystem.getInstance.findFileByPath(owner)))

  private def version(owner: String, configured: String): String = ReadAction.computeBlocking[String, RuntimeException](() =>
    rootDirectory(owner).flatMap(dir => Option(dir.findChild("dbt_project.yml")))
      .flatMap(DbtYaml.psiFile(project, _)).flatMap(DbtYaml.mapping)
      .map(DbtRoot.configuredVersion(_, configured)).getOrElse(if (configured.nonEmpty) configured else "unknown"))

  private def updatePrerequisites(): Unit = {
    val core = engine.getSelectedItem == "core"
    val hasState = stateDirectory.getText.trim.nonEmpty
    val favors = core && DbtRoot.supportsVersion(version(selectedRoot, dbtVersion.getText.trim), 4)
    // An incompatible selection can still be cleared; it cannot be newly selected.
    defer.setEnabled(core && hasState || defer.isSelected)
    favorState.setEnabled(favors && hasState && defer.isSelected || favorState.isSelected)
    infoSchema.setEnabled(!core)
    formatOnSave.setEnabled(formatterExecutable.getText.trim.nonEmpty || formatOnSave.isSelected)
    Vector((defer, deferHelp, "Requires dbt Core and a baseline directory containing manifest.json."),
      (favorState, favorHelp, "Requires deferral and dbt Core 1.4 or later."),
      (infoSchema, schemaHelp, "Used only with dbt v2. The path is kept when dbt Core is selected."),
      (formatOnSave, formatterHelp, "Choose a formatter executable to enable formatting on save.")).foreach { case (field, hint, text) =>
      hint.setText("<html>" + text + "</html>")
      DbtUi.input(field).getAccessibleContext.setAccessibleDescription(text)
    }
  }

  override def getDisplayName: String = "dbt Workspace"

  override def createComponent(): JComponent = {
    displayNames(engine, Map("core" -> "dbt Core", "v2" -> "dbt v2"))
    displayNames(direction, Map("LR" -> "Left to right", "TB" -> "Top to bottom", "RL" -> "Right to left", "BT" -> "Bottom to top"))
    val panel = DbtSettingsConfigurable.formPanel()
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
    Vector("root" -> root, "target" -> target, "profiles" -> profiles, "projectRoot" -> projectRoot,
      "executable" -> executable, "artifacts" -> artifacts, "stateDirectory" -> stateDirectory,
      "defer" -> defer, "favorState" -> favorState, "formatterExecutable" -> formatterExecutable, "formatOnSave" -> formatOnSave,
      "engine" -> engine, "dbtVersion" -> dbtVersion, "profilesFile" -> profilesFile, "adapter" -> adapter,
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
    variablesPane.setMinimumSize(JBUI.size(0, 60))
    variablesPane.setPreferredSize(JBUI.size(300, 65))
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
    target.setToolTipText("Optional. Blank uses the default target from the profiles.yml used by commands. Dynamic defaults are resolved by dbt when it runs.")
    profiles.setToolTipText("Optional. Blank uses DBT_PROFILES_DIR, a profiles.yml in the command directory, then ~/.dbt. Relative paths use the command directory.")
    projectRoot.setToolTipText("Optional. Blank runs commands in the owning dbt root. Relative overrides use the owning root.")
    artifacts.setToolTipText("Where dbt writes artifacts. Relative paths use the command directory.")
    adapter.getEmptyText.setText("From the selected navigation profile output")
    executableArguments.getEmptyText.setText("For uv: run dbt; for zsh: scripts/run_dbt.zsh")
    executableArguments.setToolTipText("Passed before every dbt command. Example: uv + run dbt → uv run dbt test. Quote paths with spaces; shell expressions are not evaluated.")
    upstream.setToolTipText("How many dependency levels to show before the focused resource in Lineage. 1 shows its direct inputs; 2 also shows their inputs. Accepts 1–20.")
    downstream.setToolTipText("How many dependent levels to show after the focused resource in Lineage. 1 shows resources that use it directly; 2 also shows resources that use those. Accepts 1–20.")
    def form(rows: Seq[(String, JComponent)]): JPanel = {
      val result = new JPanel(new GridBagLayout)
      DbtUi.labeledRows(result, JBUI.insets(4, 5, 4, 10), rows)
      result
    }
    def collapsed(name: String, title: String, rows: Seq[(String, JComponent)]): JPanel = {
      val result = new JPanel(new BorderLayout(0, JBUI.scale(4)))
      result.setName("dbt." + name)
      val content = form(rows)
      val show = new JBCheckBox("Show " + title)
      show.setMnemonic(title.head.toUpper)
      content.setVisible(false)
      show.addActionListener(_ => {
        content.setVisible(show.isSelected)
        if (name == "resolvedContext" && show.isSelected) updateResolvedContext()
        result.revalidate()
        result.repaint()
      })
      result.add(show, BorderLayout.NORTH)
      result.add(content, BorderLayout.CENTER)
      result.putClientProperty("dbt.disclosure", show)
      result
    }
    val sections = Vector(
      DbtSettingsConfigurable.separator("Execution"),
      form(Vector("Executable or tool" -> executable, "Target" -> target, "Profiles directory" -> profiles)),
      collapsed("resolvedContext", "resolved execution context", Vector("" -> withHelp(new JBScrollPane(executionContext), contextHelp))),
      collapsed("commandOptions", "command options", Vector(
        "Arguments before dbt command" -> executableArguments,
        "Project root override" -> projectRoot, "Artifact directory" -> artifacts,
        "Preview row limit" -> limit, "" -> colors, "" -> notifications)),
      collapsed("environmentOptions", "environment and variables", Vector(
        "" -> loadEnvironmentFiles, "Environment files" -> environmentFiles,
        "Environment variables" -> mappings(environmentVariables, environmentModel), "Variables (JSON)" -> variablesPane)),
      collapsed("baselineOptions", "baseline state", Vector(
        "Baseline state directory" -> stateDirectory, "" -> withHelp(defer, deferHelp), "" -> withHelp(favorState, favorHelp))),
      DbtSettingsConfigurable.separator("Navigation"),
      form(Vector("Engine" -> engine, "dbt version (optional)" -> dbtVersion, "Adapter (optional)" -> adapter)),
      navigationHelp,
      collapsed("metadataOptions", "advanced metadata", Vector(
        "Profiles file for navigation (optional)" -> profilesFile,
        "Local builtin definitions" -> mappings(builtinSources, builtinModel),
        "Catalog file (optional)" -> catalog, "Manifest file (optional)" -> manifest,
        "State manifest for navigation (optional)" -> stateManifest,
        "Information Schema v1 directory (optional)" -> withHelp(infoSchema, schemaHelp))),
      DbtSettingsConfigurable.separator("Formatting"),
      form(Vector("SQL formatter" -> formatter, "Formatter executable (optional)" -> formatterExecutable, "" -> withHelp(formatOnSave, formatterHelp))),
      DbtSettingsConfigurable.separator("Lineage — shared across dbt roots"),
      form(Vector("Upstream levels to show" -> upstream, "Downstream levels to show" -> downstream, "Layout direction" -> direction)))
    val labels = sections.collect { case form: JPanel if !form.isInstanceOf[TitledSeparator] && form.getLayout.isInstanceOf[GridBagLayout] =>
      form.getComponents.collect { case label: JLabel => label }
    }.flatten
    val labelWidth = labels.map(_.getPreferredSize.width).max
    labels.foreach(label => label.setPreferredSize(new Dimension(labelWidth, label.getPreferredSize.height)))
    sections.zipWithIndex.foreach { case (section, row) =>
      val c = new GridBagConstraints
      c.gridx = 0; c.gridy = row; c.weightx = 1
      c.fill = GridBagConstraints.HORIZONTAL
      c.anchor = GridBagConstraints.NORTHWEST
      c.insets = JBUI.insets(if (row > 0 && section.isInstanceOf[TitledSeparator]) 12 else 4, 0, 4, 0)
      panel.add(section, c)
    }
    val filler = new GridBagConstraints
    filler.gridy = sections.size
    filler.weighty = 1
    panel.add(new JPanel, filler)
    root.addActionListener(_ => {
      if (selectedRoot.nonEmpty) drafts += selectedRoot -> readDraft()
      validation.clear()
      selectRoot()
    })
    engine.addActionListener(_ => updatePrerequisites())
    defer.addActionListener(_ => updatePrerequisites())
    favorState.addActionListener(_ => updatePrerequisites())
    formatOnSave.addActionListener(_ => updatePrerequisites())
    val prerequisitesChanged = new DocumentListener {
      override def insertUpdate(event: DocumentEvent): Unit = updatePrerequisites()
      override def removeUpdate(event: DocumentEvent): Unit = updatePrerequisites()
      override def changedUpdate(event: DocumentEvent): Unit = updatePrerequisites()
    }
    Vector(dbtVersion.getDocument, stateDirectory.getTextField.getDocument, formatterExecutable.getTextField.getDocument)
      .foreach(_.addDocumentListener(prerequisitesChanged))
    val contextChanged = new DocumentListener {
      override def insertUpdate(event: DocumentEvent): Unit = contextUpdate.restart()
      override def removeUpdate(event: DocumentEvent): Unit = contextUpdate.restart()
      override def changedUpdate(event: DocumentEvent): Unit = contextUpdate.restart()
    }
    Vector(executable.getTextField, executableArguments, target, profiles.getTextField, projectRoot.getTextField,
      artifacts, environmentFiles.getTextField, profilesFile.getTextField, manifest.getTextField, catalog.getTextField, dbtVersion)
      .foreach(_.getDocument.addDocumentListener(contextChanged))
    loadEnvironmentFiles.addActionListener(_ => contextUpdate.restart())
    environmentModel.addTableModelListener(_ => contextUpdate.restart())
    var environmentEditor = Option.empty[javax.swing.text.JTextComponent]
    environmentVariables.addPropertyChangeListener("tableCellEditor", _ => {
      environmentEditor.foreach(_.getDocument.removeDocumentListener(contextChanged))
      environmentEditor = Option(environmentVariables.getEditorComponent).collect { case text: javax.swing.text.JTextComponent => text }
      environmentEditor.foreach(_.getDocument.addDocumentListener(contextChanged))
      contextUpdate.restart()
    })
    val owner = new JPanel(new BorderLayout(0, JBUI.scale(4)))
    owner.setBorder(JBUI.Borders.empty(11, 16, 4, 16))
    root.setMinimumSize(new Dimension(0, root.getMinimumSize.height))
    owner.add(form(Vector("Owning dbt root" -> root)), BorderLayout.NORTH)
    val scope = new JBLabel("<html>Execution, Navigation and Formatting apply to this root. Lineage is shared across roots.</html>")
    scope.setForeground(UIUtil.getContextHelpForeground)
    scope.setAllowAutoWrapping(true).setCopyable(true)
    owner.add(scope, BorderLayout.SOUTH)
    val general = new JPanel(new BorderLayout)
    general.add(owner, BorderLayout.NORTH)
    general.add(new JBScrollPane(panel), BorderLayout.CENTER)
    tabs.addTab("General", general)
    tabs.addTab("Integrations", new JBScrollPane(integrations.createComponent()))
    reset()
    tabs
  }

  private def selectRoot(): Unit = {
    selectedRoot = Option(root.getSelectedItem).fold("")(_.toString)
    fill(drafts.getOrElse(selectedRoot, savedDraft(new DbtRunOptions)))
  }

  private def fill(draft: Draft): Unit = {
    val o = draft.options
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
    fillMappings(environmentVariables, environmentModel, draft.environment)
    catalog.setText(o.catalogPath)
    stateManifest.setText(o.stateManifestPath)
    infoSchema.setText(o.infoSchemaDirectory)
    fillMappings(builtinSources, builtinModel, draft.builtins)
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
    DbtUi.input(limit).asInstanceOf[JFormattedTextField].setText(draft.preview)
    colors.setSelected(o.colors)
    notifications.setSelected(o.notifications)
    updatePrerequisites()
    updateResolvedContext()
  }

  private def readDraft(): Draft = {
    val o = drafts.getOrElse(selectedRoot, savedDraft(new DbtRunOptions)).options.copy()
    o.executable = executable.getText.trim
    o.executableArguments = executableArguments.getText.trim
    o.environmentFiles = environmentFiles.getText.trim
    o.loadEnvironmentFiles = loadEnvironmentFiles.isSelected
    o.engine = engine.getSelectedItem.toString
    o.dbtVersion = dbtVersion.getText.trim
    o.profilesFile = profilesFile.getText.trim
    o.adapter = adapter.getText.trim
    o.variables = variables.getText.trim
    o.catalogPath = catalog.getText.trim
    o.stateManifestPath = stateManifest.getText.trim
    o.infoSchemaDirectory = infoSchema.getText.trim
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
    val preview = DbtUi.input(limit).asInstanceOf[JFormattedTextField].getText
    o.previewLimit = previewLimit(preview).getOrElse(limit.getValue.asInstanceOf[Number].intValue())
    o.colors = colors.isSelected
    o.notifications = notifications.isSelected
    Draft(o, readMappings(environmentVariables, environmentModel), readMappings(builtinSources, builtinModel), preview)
  }

  private def snapshot(): Vector[Any] =
    Vector(drafts.updated(selectedRoot, readDraft()).toVector.sortBy(_._1).map { case (path, draft) => (path, draft.values) },
      DbtUi.input(upstream).asInstanceOf[JFormattedTextField].getText,
      DbtUi.input(downstream).asInstanceOf[JFormattedTextField].getText, direction.getSelectedItem)

  override def reset(): Unit = {
    selectedRoot = ""
    validation.clear()
    drafts = settings.getState.projects.asScala.toMap.view.mapValues(savedDraft).toMap
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
    contextDisposed = true
    contextUpdate.stop()
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
    validation.dispose()
  }

  private def reject(owner: String, field: JComponent, message: String, row: Int = -1, column: Int = 0): Nothing = {
    if (owner != selectedRoot) root.setSelectedItem(owner)
    tabs.setSelectedIndex(0)
    Iterator.iterate(field: java.awt.Component)(_.getParent).takeWhile(_ != null).foreach {
      case component: JComponent => component.getClientProperty("dbt.disclosure") match {
        case show: JCheckBox if !show.isSelected => show.doClick()
        case _ => ()
      }
      case _ => ()
    }
    field match {
      case table: JTable if row >= 0 => table.changeSelection(row, column, false, false)
      case _ => ()
    }
    validation.reject(field, s"$owner — $message")
  }

  private def checkedMappings(owner: String, rows: Vector[(String, String)], table: JTable, allowEmpty: Boolean): java.util.Map[String, String] = {
    val values = new java.util.TreeMap[String, String]()
    val title = table.getAccessibleContext.getAccessibleName
    rows.zipWithIndex.foreach { case ((rawName, rawValue), row) =>
      val name = rawName.trim
      def invalid(column: Int, problem: String): Nothing = reject(owner, table, s"$title, row ${row + 1}: $problem", row, column)
      if (name.isEmpty) invalid(0, "Enter a name, or remove this row.")
      if (allowEmpty && !name.matches("[A-Za-z_][A-Za-z_0-9]*"))
        invalid(0, "Names must start with a letter or underscore and contain only letters, digits or underscores.")
      if (values.containsKey(name)) invalid(0, "This name is already used in an earlier row. Rename or remove the duplicate.")
      if (!allowEmpty && rawValue.trim.isEmpty) invalid(1, "Choose a local source file, or remove this row.")
      if (!allowEmpty) try Paths.get(rawValue.trim)
      catch { case _: InvalidPathException => invalid(1, "Use a valid local source file path.") }
      values.put(name, if (allowEmpty) rawValue else rawValue.trim)
    }
    values
  }

  private def checkedOptions(owner: String, draft: Draft): DbtRunOptions = {
    val options = draft.options.copy()
    if (options.executable.isEmpty) reject(owner, executable, "Executable or tool: enter a command or choose an executable.")
    if (options.artifactDirectory.isEmpty) reject(owner, artifacts, "Artifact directory: enter an output directory, such as target.")
    val paths = Vector((projectRoot, "Project root override", options.projectRoot),
      (profiles, "Profiles directory", options.profilesDirectory), (artifacts, "Artifact directory", options.artifactDirectory),
      (manifest, "Manifest file", options.manifestPath), (stateDirectory, "Baseline state directory", options.stateDirectory),
      (profilesFile, "Profiles file for navigation", options.profilesFile), (catalog, "Catalog file", options.catalogPath),
      (stateManifest, "State manifest for navigation", options.stateManifestPath)) ++
      (if (options.engine == "v2") Vector((infoSchema, "Information Schema v1 directory", options.infoSchemaDirectory)) else Vector.empty)
    paths.foreach { case (field, label, path) =>
      try Paths.get(path)
      catch { case _: InvalidPathException => reject(owner, field, s"$label: enter a valid local path.") }
    }
    options.previewLimit = previewLimit(draft.preview)
      .getOrElse(reject(owner, limit, "Preview row limit: enter a whole number from 1 to 1000."))
    DbtVariableOverrides.parse(options.variables).left.foreach(message => reject(owner, variables, "Variables (JSON): " + message))
    options.environmentVariables = checkedMappings(owner, draft.environment, environmentVariables, allowEmpty = true)
    options.builtinSources = checkedMappings(owner, draft.builtins, builtinSources, allowEmpty = false)
    if (options.defer && options.engine != "core") reject(owner, defer, "Deferral requires Engine set to dbt Core. Clear deferral or choose dbt Core.")
    if (options.favorState && (!options.defer || options.engine != "core" || !DbtRoot.supportsVersion(version(owner, options.dbtVersion), 4)))
      reject(owner, favorState, "Favor baseline relations requires deferral and dbt Core 1.4 or later. Clear the option or restore its prerequisites.")
    if (options.formatOnSave && options.formatterExecutable.isEmpty)
      reject(owner, formatterExecutable, "Formatter executable: enter a command, or clear Format dbt SQL on save.")
    if (options.defer) {
      if (options.stateDirectory.isEmpty) reject(owner, stateDirectory, "Baseline state directory: choose a directory containing manifest.json before enabling deferral.")
      try {
        val execution = Paths.get(owner).resolve(options.projectRoot).normalize()
        val state = execution.resolve(options.stateDirectory).normalize()
        DbtState.separate(state, execution.resolve(options.artifactDirectory)).left.foreach(message => reject(owner, stateDirectory, message))
        val manifestPath = state.resolve("manifest.json")
        val localManifest = rootDirectory(owner).filter(_ => manifestPath.startsWith(Paths.get(owner))).flatMap(dir =>
          Option(dir.findFileByRelativePath(Paths.get(owner).relativize(manifestPath).toString)))
        if (!Files.isRegularFile(manifestPath) && !localManifest.exists(!_.isDirectory))
          reject(owner, stateDirectory, "Baseline state directory: choose a directory containing manifest.json.")
      } catch { case _: InvalidPathException => reject(owner, stateDirectory, "Baseline state directory: use a valid local directory path.") }
    }
    options
  }

  override def apply(): Unit = {
    validation.clear()
    Vector(upstream, downstream).foreach { spinner =>
      try spinner.commitEdit()
      catch { case _: java.text.ParseException => reject(selectedRoot, spinner, spinner.getAccessibleContext.getAccessibleName + ": enter a number within the allowed range.") }
    }
    drafts += selectedRoot -> readDraft()
    val checked = drafts.toVector.sortBy(_._1).map { case (owner, draft) => owner -> checkedOptions(owner, draft) }.toMap
    if (integrations.isModified) integrations.apply()
    settings.getState.projects = new java.util.TreeMap[String, DbtRunOptions](checked.asJava)
    settings.getState.upstreamDepth = upstream.getValue.asInstanceOf[Number].intValue()
    settings.getState.downstreamDepth = downstream.getValue.asInstanceOf[Number].intValue()
    settings.getState.direction = direction.getSelectedItem.toString
    settings.changed()
    baseline = snapshot()
  }
}

/** Native field errors share the settings UI lifetime, including their focus and accessible descriptions. */
private final class DbtSettingsValidation {
  private val lifetime = Disposer.newDisposable("dbt Settings validation")
  private var disposed = false
  private var descriptions = Map.empty[JComponent, String]

  def clear(): Unit = {
    descriptions.foreach { case (field, description) =>
      ComponentValidator.getInstance(field).ifPresent(_.updateInfo(null))
      field.getAccessibleContext.setAccessibleDescription(description)
    }
    descriptions = Map.empty
  }

  def reject(field: JComponent, message: String): Nothing = {
    val input = DbtUi.input(field)
    descriptions += input -> input.getAccessibleContext.getAccessibleDescription
    ComponentValidator.getInstance(input).orElseGet(() => new ComponentValidator(lifetime).installOn(input))
      .updateInfo(new ValidationInfo(com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(message), input))
    input.getAccessibleContext.setAccessibleDescription(message)
    SwingUtilities.invokeLater(() => if (!disposed && input.isShowing) {
      val location = input match {
        case table: JTable if table.getSelectedRow >= 0 => table.getCellRect(table.getSelectedRow, table.getSelectedColumn.max(0), true)
        case _ => new Rectangle(input.getSize)
      }
      input.scrollRectToVisible(location)
      input.requestFocusInWindow()
    })
    throw new ConfigurationException(message)
  }

  def dispose(): Unit = if (!disposed) { clear(); disposed = true; Disposer.dispose(lifetime) }
}

object DbtSettingsConfigurable {
  private[ide] def formPanel(): JPanel = new JPanel(new GridBagLayout) with Scrollable {
    override def getPreferredScrollableViewportSize: Dimension = getPreferredSize
    override def getScrollableTracksViewportWidth: Boolean = true
    override def getScrollableTracksViewportHeight: Boolean = false
    override def getScrollableUnitIncrement(visible: Rectangle, orientation: Int, direction: Int): Int = JBUI.scale(16)
    override def getScrollableBlockIncrement(visible: Rectangle, orientation: Int, direction: Int): Int =
      if (orientation == SwingConstants.VERTICAL) visible.height else visible.width
  }

  private[ide] def separator(title: String): TitledSeparator = {
    val result = new TitledSeparator(title)
    result.getLabel.setBorder(JBUI.Borders.emptyRight(4))
    result
  }

  def showIntegrations(project: Project): Unit = {
    val configurable = new DbtSettingsConfigurable(project)
    ShowSettingsUtil.getInstance().editConfigurable(project, configurable,
      new Runnable {
        override def run(): Unit = configurable.tabs.setSelectedIndex(1)
      })
  }
}

/** The Integrations tab: Metabase, the local MCP server and local Airflow DAG discovery. */
final class DbtConsumersConfigurable(project: Project, onError: () => Unit = () => ()) extends Configurable {
  private val validation = new DbtSettingsValidation
  private val url = new JBTextField()
  private val secret = new JPasswordField()
  private val agent = new JBCheckBox("Enable local read-only MCP server")
  private val airflowPaths = new DefaultListModel[String]
  private val airflowFolders = new JBList[String](airflowPaths)
  private val airflowUrl = new JBTextField
  private val panel = DbtSettingsConfigurable.formPanel()
  panel.setBorder(JBUI.Borders.empty(11, 16, 16, 16))

  private def help(text: String): JBLabel = {
    val label = new JBLabel("<html>" + text + "</html>")
    label.setForeground(UIUtil.getContextHelpForeground)
    label.setAllowAutoWrapping(true).setCopyable(true)
    label
  }

  private def fieldWithHelp(field: JComponent, text: String): JPanel = {
    val result = new JPanel(new BorderLayout(0, JBUI.scale(4))) {
      override def getMinimumSize: Dimension = new Dimension(0, getPreferredSize.height)
    }
    result.add(field, BorderLayout.NORTH)
    result.add(help(text), BorderLayout.CENTER)
    result.putClientProperty("dbt.input", field)
    DbtUi.input(field).getAccessibleContext.setAccessibleDescription(text)
    result
  }

  private def section(title: String, fields: Seq[(String, JComponent)]): JPanel = {
    val result = new JPanel(new BorderLayout(0, JBUI.scale(8)))
    val form = new JPanel(new GridBagLayout)
    result.add(DbtSettingsConfigurable.separator(title), BorderLayout.NORTH)
    DbtUi.labeledRows(form, JBUI.insets(4, 5, 4, 10), fields)
    val layout = form.getLayout.asInstanceOf[GridBagLayout]
    form.getComponents.collect { case label: JLabel if label.getText.nonEmpty => label }.foreach { label =>
      val constraints = layout.getConstraints(label)
      constraints.anchor = GridBagConstraints.FIRST_LINE_START
      layout.setConstraints(label, constraints)
    }
    result.add(form, BorderLayout.CENTER)
    result
  }

  private def stack(rows: Vector[JComponent]): Unit = {
    val labels = rows.flatMap(_.getComponents.toVector.collect {
      case form: JPanel if !form.isInstanceOf[TitledSeparator] && form.getLayout.isInstanceOf[GridBagLayout] =>
        form.getComponents.toVector.collect { case label: JLabel if label.getText.nonEmpty => label }
    }.flatten)
    val labelWidth = labels.map(_.getPreferredSize.width).max
    labels.foreach(label => label.setPreferredSize(new Dimension(labelWidth, label.getPreferredSize.height)))
    rows.zipWithIndex.foreach { case (field, row) =>
      val c = new GridBagConstraints
      c.gridx = 0;
      c.gridy = row;
      c.weightx = 1;
      c.fill = GridBagConstraints.HORIZONTAL
      c.anchor = GridBagConstraints.NORTHWEST;
      c.insets = JBUI.insets(if (row == 0) 4 else 12, 0, 4, 0)
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
  folderPanel.putClientProperty("dbt.input", airflowFolders)
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
    validation.clear()
    def invalid(field: JComponent, message: String): Nothing = { onError(); validation.reject(field, message) }
    val value = url.getText.trim
    if (value.nonEmpty && DbtAirflow.safeUrl(value).isEmpty) invalid(url, "Metabase Server URL: use an HTTP or HTTPS URL without embedded credentials.")
    val airflowLink = airflowUrl.getText.trim
    if (airflowLink.nonEmpty && DbtAirflow.safeUrl(airflowLink).isEmpty)
      invalid(airflowUrl, "Airflow Web URL: use an HTTP or HTTPS URL without embedded credentials.")
    folderValues.zipWithIndex.foreach { case (path, row) =>
      try Paths.get(path)
      catch {
        case _: InvalidPathException =>
          airflowFolders.setSelectedIndex(row)
          invalid(airflowFolders, s"DAG folders, row ${row + 1}: use a valid local directory path.")
      }
    }
    settings.airflowPaths = new java.util.ArrayList[String](folderValues.asJava)
    settings.airflowUrl = airflowLink
    DbtProjectSettings.get(project).changed()
    settings.metabaseUrl = value;
    settings.agentEnabled = agent.isSelected
    service.setSecret(new String(secret.getPassword))
    service.clear();
    if (!agent.isSelected) project.getService(classOf[DbtAgent]).stop()
    else DbtAgentServiceView.changed(project)
  }

  override def reset(): Unit = {
    validation.clear()
    url.setText(settings.metabaseUrl);
    agent.setSelected(settings.agentEnabled);
    secret.setText(service.apiKey)
    airflowPaths.clear();
    settings.airflowPaths.asScala.foreach(airflowPaths.addElement)
    airflowUrl.setText(settings.airflowUrl)
  }

  override def disposeUIResources(): Unit = { secret.setText(""); validation.dispose() }
}
