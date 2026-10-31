package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project._
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.ui.ComponentValidator
import com.intellij.util.xmlb.XmlSerializer
import org.junit.Assert._

import java.awt.{Component, Container, Point}
import javax.swing._

final class DbtSettingsTest extends DbtTestCase {
  private def toggleSection(form: Container, name: String): Unit = {
    val section = components(form).collectFirst { case panel: JPanel if panel.getName == "dbt." + name => panel }.get
    section.getComponents.collectFirst { case show: JCheckBox => show }.get.doClick()
  }

  private def generalScrollPane(tabs: JTabbedPane): JScrollPane =
    components(tabs.getComponentAt(0)).collectFirst { case scroll: JScrollPane => scroll }.get

  private def visibleInForm(field: Component, form: Container): Boolean =
    Iterator.iterate(field)(_.getParent).takeWhile(_ != form).forall(_.isVisible)

  private def named(form: Container, name: String): JComponent =
    components(form).collectFirst { case field: JComponent if field.getName == "dbt." + name => field }.get

  private def invalid(configurable: DbtSettingsConfigurable, message: String): ConfigurationException = {
    try { configurable.apply(); throw new AssertionError("Expected validation error: " + message) }
    catch { case error: ConfigurationException => assertTrue(error.getMessageHtml.toString, error.getMessageHtml.toString.contains(message)); error }
  }

  private def layout(container: Container): Unit = {
    container.doLayout()
    container.getComponents.collect { case child: Container => child }.foreach(layout)
  }

  private def capture(tabs: JTabbedPane, output: String): Unit = {
    val image = new java.awt.image.BufferedImage(tabs.getWidth, tabs.getHeight, java.awt.image.BufferedImage.TYPE_INT_RGB)
    val graphics = image.createGraphics()
    graphics.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    try tabs.printAll(graphics) finally graphics.dispose()
    assertTrue(javax.imageio.ImageIO.write(image, "png", new java.io.File(output)))
  }

  def testNavigationYamlIsIgnoredAndSettingsChangesRefreshDeclarations(): Unit = {
    project("profile: analytics\n")
    add("profiles.yml", "analytics: {target: dev, outputs: {dev: {type: postgres}}}\n")
    add(".env", "DBT_SCHEMA=default\n")
    add("custom.env", "DBT_SCHEMA=ignored\n")
    add(".dbt-navigation.yml", "engine: v2\ndbt-version: '1.12'\nadapter: snowflake\n" +
      "env-files: [custom.env]\nmanifest: missing.json\nrun-config: run.yml\n")
    add("run.yml", "env: {DBT_SCHEMA: ignored}\n")
    val function = add("functions/hash.sql", "select 1")
    val check = add("checks/check.sql", "select 1")
    assertEquals("core", root.engine)
    assertEquals("1.10", root.version)
    assertEquals(Some("postgres"), root.adapter)
    assertEquals("default", DbtEnvironment.values(root)("DBT_SCHEMA"))
    assertEquals(None, root.setting("run-config"))
    assertEquals(root.artifactDirectory.resolve("manifest.json").toString, root.setting("manifest").get)
    assertFalse(workspace.symbols.exists(s => s.file == function || s.file == check))

    configureSettings { o => o.dbtVersion = "1.12"; o.adapter = "snowflake" }
    assertEquals(Some("snowflake"), root.adapter)
    assertTrue(workspace.symbols.exists(_.file == function))
    assertFalse(workspace.symbols.exists(_.file == check))
    configureSettings(_.engine = "v2")
    assertTrue(workspace.symbols.exists(_.file == check))
    configureSettings { o => o.engine = "core"; o.dbtVersion = "1.7" }
    assertFalse(workspace.symbols.exists(s => s.file == function || s.file == check))
  }

  def testNavigationSettingsApplyResetRootSwitchingAndPersistence(): Unit = {
    project()
    add("other/dbt_project.yml", "name: other\n")
    val own = root.directory.getPath
    val other = workspace.roots.find(_.name == "other").get.directory.getPath
    val settings = DbtProjectSettings.get(getProject)
    val configurable = new DbtSettingsConfigurable(getProject)
    val tabs = configurable.createComponent().asInstanceOf[JTabbedPane]
    val form = generalScrollPane(tabs).getViewport.getView.asInstanceOf[Container]
    def field(name: String): JComponent = components(form).collectFirst {
      case c: JComponent if c.getName == "dbt." + name => c
    }.getOrElse(throw new AssertionError("Missing settings field " + name))
    def text(name: String, value: String): Unit = field(name) match {
      case f: JTextField => f.setText(value)
      case f: JTextArea => f.setText(value)
      case f: TextFieldWithBrowseButton => f.setText(value)
    }
    val roots = components(tabs).collectFirst { case c: JComboBox[_] if c.getName == "dbt.root" => c.asInstanceOf[JComboBox[String]] }.get
    val builtinTable = components(form).collectFirst { case t: JTable if t.getName == "dbt.builtinSources" => t }.get
    try {
      roots.setSelectedItem(own)
      assertFalse(configurable.isModified)
      field("engine").asInstanceOf[JComboBox[String]].setSelectedItem("v2")
      text("dbtVersion", "1.12")
      text("profilesFile", "config/profiles.yml")
      text("adapter", "snowflake")
      text("variables", "{\"start_date\":\"2026-03-01\",\"limits\":[1,2],\"enabled\":true,\"nothing\":null}")
      val environment = field("environmentVariables").asInstanceOf[JTable].getModel.asInstanceOf[javax.swing.table.DefaultTableModel]
      environment.addRow(Array[AnyRef]("DBT_SCHEMA", "analytics"))
      environment.addRow(Array[AnyRef]("EMPTY", ""))
      text("catalogPath", "metadata/catalog.json")
      text("stateManifestPath", "state/manifest.json")
      text("infoSchemaDirectory", "target/info_schema/v1")
      field("loadEnvironmentFiles").asInstanceOf[JCheckBox].doClick()
      builtinTable.getModel.asInstanceOf[javax.swing.table.DefaultTableModel]
        .addRow(Array[AnyRef]("adapter.get_relation", "vendor/adapter.sql"))
      assertTrue(configurable.isModified)
      assertEquals("core", settings.options(own).engine)
      roots.setSelectedItem(other)
      assertEquals("core", field("engine").asInstanceOf[JComboBox[String]].getSelectedItem)
      assertEquals(0, builtinTable.getRowCount)
      assertEquals("", field("variables").asInstanceOf[JTextArea].getText)
      assertEquals(0, environment.getRowCount)
      roots.setSelectedItem(own)
      assertEquals("v2", field("engine").asInstanceOf[JComboBox[String]].getSelectedItem)
      assertEquals(1, builtinTable.getRowCount)
      sys.env.get("DBT_SETTINGS_SCREENSHOT").foreach { path =>
        tabs.addNotify()
        tabs.setSize(1200, 900)
        layout(tabs)
        layout(tabs)
        val toolbars = components(form).collect { case toolbar: com.intellij.openapi.actionSystem.ActionToolbar => toolbar }
        val updates = toolbars.map(_.updateActionsAsync())
        await("Mapping toolbar icons are rendered")(updates.forall(_.isDone) && toolbars.forall(_.hasVisibleActions))
        layout(tabs)
        try {
          assertFalse("Advanced metadata stays collapsed until requested", visibleInForm(builtinTable, form))
          assertFalse("Environment overrides stay collapsed until requested", visibleInForm(field("environmentVariables"), form))
          capture(tabs, path)
          toggleSection(form, "environmentOptions")
          layout(tabs); layout(tabs)
          capture(tabs, path.stripSuffix(".png") + "-environment.png")
          tabs.setSize(850, 680)
          layout(tabs); layout(tabs)
          val scroll = generalScrollPane(tabs)
          scroll.getViewport.setViewPosition(new Point(0, form.getHeight - scroll.getViewport.getHeight))
          assertEquals("Root selector remains fully visible at narrow widths", roots.getSize, roots.getVisibleRect.getSize)
          capture(tabs, path.stripSuffix(".png") + "-compact.png")
          toggleSection(form, "environmentOptions")
        } finally tabs.removeNotify()
      }
      configurable.apply()
      assertFalse(configurable.isModified)
      val saved = settings.options(own).copy()
      assertEquals("config/profiles.yml", saved.profilesFile)
      assertEquals("snowflake", saved.adapter)
      assertTrue(saved.variables.contains("start_date"))
      assertEquals("analytics", saved.environmentVariables.get("DBT_SCHEMA"))
      assertEquals("", saved.environmentVariables.get("EMPTY"))
      assertEquals("vendor/adapter.sql", saved.builtinSources.get("adapter.get_relation"))
      text("adapter", "discarded")
      builtinTable.setValueAt("discarded.sql", 0, 1)
      configurable.reset()
      roots.setSelectedItem(own)
      assertEquals("snowflake", field("adapter").asInstanceOf[JTextField].getText)
      assertEquals("vendor/adapter.sql", builtinTable.getValueAt(0, 1))
      val restored = XmlSerializer.deserialize(XmlSerializer.serialize(settings.getState), classOf[DbtSettingsState])
      assertEquals(saved.values, restored.projects.get(own).values)
      assertEquals("core", restored.projects.get(other).engine)
      assertEquals("", restored.projects.get(other).catalogPath)
      assertTrue(restored.projects.get(other).builtinSources.isEmpty)
      assertFalse(restored.projects.get(own).loadEnvironmentFiles)
      settings.loadState(restored)
      assertEquals("v2", root.engine)
      assertEquals("1.12", root.version)
      assertEquals("analytics", root.runOptions.environmentVariables.get("DBT_SCHEMA"))
      assertTrue(root.runOptions.variables.contains("start_date"))
      assertEquals("", restored.projects.get(other).variables)
      assertTrue(restored.projects.get(other).environmentVariables.isEmpty)
      assertEquals(Some("metadata/catalog.json"), root.setting("catalog"))
      assertEquals(Some("state/manifest.json"), root.setting("state-manifest"))
      assertEquals(Some("target/info_schema/v1"), root.setting("info-schema-dir"))
    } finally configurable.disposeUIResources()
  }

  def testOptionalSectionsPreserveDraftsAndRootUsesFixedHeader(): Unit = {
    project()
    val own = root.directory.getPath
    val configurable = new DbtSettingsConfigurable(getProject)
    val tabs = configurable.createComponent().asInstanceOf[JTabbedPane]
    val scroll = generalScrollPane(tabs)
    val form = scroll.getViewport.getView.asInstanceOf[Container]
    val fields = components(tabs)
    val roots = fields.collectFirst { case combo: JComboBox[_] if combo.getName == "dbt.root" => combo }.get
    val variables = fields.collectFirst { case text: JTextArea => text }.get
    try {
      assertTrue(SwingUtilities.isDescendingFrom(roots, tabs.getComponentAt(0)))
      assertFalse(SwingUtilities.isDescendingFrom(roots, form))
      roots.setSelectedItem(own)
      val groups = Vector("commandOptions", "environmentOptions", "baselineOptions", "metadataOptions")
      val content = Vector(
        fields.collectFirst { case text: JTextField if Option(text.getToolTipText).exists(_.startsWith("Passed before every")) => text }.get,
        variables,
        fields.collectFirst { case box: JCheckBox if box.getText.startsWith("Defer unselected") => box }.get,
        fields.collectFirst { case table: JTable if table.getName == "dbt.builtinSources" => table }.get)
      groups.zip(content).foreach { case (group, field) =>
        assertFalse(group, visibleInForm(field, form))
        toggleSection(form, group)
        assertTrue(group, visibleInForm(field, form))
      }
      assertFalse("Disclosure changes are not settings edits", configurable.isModified)
      variables.setText("{\"region\":\"EU\"}")
      groups.foreach(toggleSection(form, _))
      assertTrue(configurable.isModified)
      configurable.apply()
      assertEquals("{\"region\":\"EU\"}", DbtProjectSettings.get(getProject).options(own).variables)
      toggleSection(form, "environmentOptions")
      assertEquals("{\"region\":\"EU\"}", variables.getText)
      assertFalse(configurable.isModified)
    } finally configurable.disposeUIResources()
  }

  def testRunYamlFilesDoNotSupplyOverridesOrSelections(): Unit = {
    project("profile: analytics\nvars: {color: project_default}\n")
    add("profiles.yml", "analytics: {target: dev, outputs: {dev: {type: postgres}}}\n")
    add(".env", "DBT_SCHEMA=project_default\n")
    add("models/orders.sql", "select 1")
    val run = add("run.yml", "vars: {color: ignored}\nenv: {DBT_SCHEMA: ignored}\n" +
      "target: ignored\nadapter: snowflake\nprofiles-file: missing.yml\nselect: orders\nexclude: orders\nselector: nightly\n")
    add("run/dev.yml", run.getText)
    val selection = add("run/selection.yml", "select: orders\n")
    assertTrue(com.github.vbmacher.intellij.dbt.navigation.DbtNavigation.inFile(run).isEmpty)
    assertTrue(targetsAt(selection, "orders").isEmpty)
    assertEquals("project_default", DbtEnvironment.values(root)("DBT_SCHEMA"))
    assertEquals(Some("dev"), DbtExecutionContext.activeTarget(root))
    assertEquals(Some("postgres"), root.adapter)
    val use = add("models/use.sql", "{{ var('color') }}")
    assertEquals(Vector(projectFile("dbt_project.yml")), targetsAt(use, "color").map(_.getContainingFile))
  }

  def testInvalidVariableOverridesCannotBeAppliedOrRun(): Unit = {
    project()
    add("models/orders.sql", "select 1")
    val configurable = new DbtSettingsConfigurable(getProject)
    val form = configurable.createComponent()
    try {
      val variables = components(form).collectFirst { case text: JTextArea if text.getName == "dbt.variables" => text }.get
      for (invalid <- Vector("[]", "null", "{broken")) {
        variables.setText(invalid)
        try {
          configurable.apply()
          fail("Invalid variables must be rejected")
        } catch { case _: com.intellij.openapi.options.ConfigurationException => () }
        assertEquals("", root.runOptions.variables)
      }
      configureSettings(_.variables = "[]")
      val selection = DbtRunSelection.all(workspace, root.directory.getPath).get
      assertTrue(DbtCommand.create(workspace, selection, "Run", fullRefresh = false).isLeft)
    } finally configurable.disposeUIResources()
  }

  def testMappingDraftsKeepIncompleteDuplicateAndActivelyEditedRows(): Unit = {
    project()
    add("other/dbt_project.yml", "name: other\n")
    val own = root.directory.getPath
    val other = workspace.roots.find(_.name == "other").get.directory.getPath
    val configurable = new DbtSettingsConfigurable(getProject)
    val tabs = configurable.createComponent().asInstanceOf[JTabbedPane]
    val roots = named(tabs, "root").asInstanceOf[JComboBox[String]]
    val environment = named(tabs, "environmentVariables").asInstanceOf[JTable]
    val model = environment.getModel.asInstanceOf[javax.swing.table.DefaultTableModel]
    val builtins = named(tabs, "builtinSources").asInstanceOf[JTable]
    val builtinsModel = builtins.getModel.asInstanceOf[javax.swing.table.DefaultTableModel]
    try {
      roots.setSelectedItem(own)
      model.addRow(Array[AnyRef]("ORIGINAL", ""))
      model.addRow(Array[AnyRef](" DUPLICATE ", "中文 العربية فارسی\u200c 😀"))
      assertTrue(environment.editCellAt(0, 0))
      environment.getEditorComponent.asInstanceOf[JTextField].setText("DUPLICATE")
      assertTrue(configurable.isModified)
      assertTrue("Checking modification must not commit or close the cell editor", environment.isEditing)
      roots.setSelectedItem(other)
      roots.setSelectedItem(own)
      assertEquals(2, model.getRowCount)
      assertEquals("DUPLICATE", model.getValueAt(0, 0))
      assertEquals(" DUPLICATE ", model.getValueAt(1, 0))
      invalid(configurable, "already used")
      assertEquals(1, environment.getSelectedRow)
      assertTrue(root.runOptions.environmentVariables.isEmpty)
      model.setValueAt("", 1, 0)
      roots.setSelectedItem(other)
      roots.setSelectedItem(own)
      invalid(configurable, "Enter a name")
      assertEquals(2, model.getRowCount)
      model.setValueAt("9INVALID", 1, 0)
      invalid(configurable, "Names must start")
      model.setValueAt("UNICODE_VALUE", 1, 0)
      builtinsModel.addRow(Array[AnyRef]("adapter.get_relation", ""))
      invalid(configurable, "Choose a local source file")
      assertTrue("Error reveals the collapsed metadata section", visibleInForm(builtins, generalScrollPane(tabs).getViewport.getView.asInstanceOf[Container]))
      assertEquals(0, builtins.getSelectedRow)
      assertTrue(root.runOptions.environmentVariables.isEmpty)
      builtinsModel.setValueAt("bad\u0000path", 0, 1)
      invalid(configurable, "valid local source file path")
      assertEquals("bad\u0000path", builtinsModel.getValueAt(0, 1))
      builtinsModel.setValueAt("vendor/adapter.sql", 0, 1)
      model.addRow(Array[AnyRef]("", ""))
      invalid(configurable, "row 3: Enter a name")
      model.removeRow(2)
      configurable.apply()
      assertEquals("", root.runOptions.environmentVariables.get("DUPLICATE"))
      assertEquals("中文 العربية فارسی\u200c 😀", root.runOptions.environmentVariables.get("UNICODE_VALUE"))
      assertEquals("vendor/adapter.sql", root.runOptions.builtinSources.get("adapter.get_relation"))
      assertFalse(configurable.isModified)
      assertTrue(environment.editCellAt(0, 1))
      environment.getEditorComponent.asInstanceOf[JTextField].setText("edited without leaving the cell")
      configurable.apply()
      assertEquals("edited without leaving the cell", root.runOptions.environmentVariables.get("DUPLICATE"))
      configurable.reset()
      roots.setSelectedItem(own)
      assertEquals("edited without leaving the cell", model.getValueAt(0, 1))
    } finally configurable.disposeUIResources()
  }

  def testApplyRevealsErrorsInOtherRootsWithoutSavingAnyDraft(): Unit = {
    project()
    add("other/dbt_project.yml", "name: other\n")
    val own = root.directory.getPath
    val other = workspace.roots.find(_.name == "other").get.directory.getPath
    val configurable = new DbtSettingsConfigurable(getProject)
    val tabs = configurable.createComponent().asInstanceOf[JTabbedPane]
    val roots = named(tabs, "root").asInstanceOf[JComboBox[String]]
    try {
      Vector(("executable", "", "Executable or external tool"), ("artifacts", "", "Artifact directory"),
        ("artifacts", "bad\u0000path", "valid local path"), ("variables", "[]", "Variables (JSON)")).foreach { case (name, value, message) =>
        configurable.reset()
        roots.setSelectedItem(own)
        val field = named(tabs, name)
        val input = field match {
          case browse: TextFieldWithBrowseButton => browse.getTextField
          case text: JTextArea => text
          case text: JTextField => text
        }
        input.setText(value)
        roots.setSelectedItem(other)
        named(tabs, "adapter").asInstanceOf[JTextField].setText("snowflake")
        tabs.setSelectedIndex(1)
        val error = invalid(configurable, message)
        assertTrue(error.getMessageHtml.toString, error.getMessageHtml.toString.contains(own))
        assertEquals(own, roots.getSelectedItem)
        assertEquals(0, tabs.getSelectedIndex)
        assertEquals(value, input.getText)
        assertTrue(visibleInForm(input, generalScrollPane(tabs).getViewport.getView.asInstanceOf[Container]))
        assertNotNull(ComponentValidator.getInstance(input).get.getValidationInfo)
        assertEquals(com.intellij.openapi.util.text.StringUtil.unescapeXmlEntities(error.getMessageHtml.toString), input.getAccessibleContext.getAccessibleDescription)
        if (name == "variables") sys.env.get("DBT_SETTINGS_SCREENSHOT").foreach { path =>
          tabs.addNotify()
          try {
            tabs.setSize(1200, 900)
            layout(tabs); layout(tabs)
            input.scrollRectToVisible(new java.awt.Rectangle(input.getSize))
            capture(tabs, path.stripSuffix(".png") + "-validation.png")
          } finally tabs.removeNotify()
        }
        assertEquals("dbt", DbtProjectSettings.get(getProject).options(own).executable)
        assertEquals("", DbtProjectSettings.get(getProject).options(other).adapter)
        roots.setSelectedItem(other)
        assertEquals("snowflake", named(tabs, "adapter").asInstanceOf[JTextField].getText)
      }
    } finally configurable.disposeUIResources()
  }

  def testPrerequisitesFollowUnsavedEngineVersionBaselineAndFormatterChanges(): Unit = {
    project("require-dbt-version: '>=1.3,<1.4'\n")
    add("baseline/manifest.json", "{}")
    val own = root.directory.getPath
    val configurable = new DbtSettingsConfigurable(getProject)
    val tabs = configurable.createComponent().asInstanceOf[JTabbedPane]
    val roots = named(tabs, "root").asInstanceOf[JComboBox[String]]
    val engine = named(tabs, "engine").asInstanceOf[JComboBox[String]]
    val version = named(tabs, "dbtVersion").asInstanceOf[JTextField]
    val state = named(tabs, "stateDirectory").asInstanceOf[TextFieldWithBrowseButton]
    val defer = named(tabs, "defer").asInstanceOf[JCheckBox]
    val favor = named(tabs, "favorState").asInstanceOf[JCheckBox]
    val schema = named(tabs, "infoSchemaDirectory").asInstanceOf[TextFieldWithBrowseButton]
    val executable = named(tabs, "formatterExecutable").asInstanceOf[TextFieldWithBrowseButton]
    val onSave = named(tabs, "formatOnSave").asInstanceOf[JCheckBox]
    try {
      roots.setSelectedItem(own)
      assertFalse(defer.isEnabled)
      assertFalse(favor.isEnabled)
      assertFalse(schema.isEnabled)
      assertFalse(onSave.isEnabled)
      state.setText("baseline")
      assertTrue(defer.isEnabled)
      defer.doClick()
      assertFalse("Version from dbt_project.yml is below 1.4", favor.isEnabled)
      version.setText("1.4")
      assertTrue(favor.isEnabled)
      favor.doClick()
      engine.setSelectedItem("v2")
      assertTrue(schema.isEnabled)
      assertTrue("Incompatible selections can be cleared", defer.isEnabled)
      invalid(configurable, "Deferral requires Engine")
      assertTrue(defer.isSelected)
      defer.doClick()
      invalid(configurable, "Favor baseline relations requires")
      favor.doClick()
      assertFalse(defer.isEnabled)
      assertFalse(favor.isEnabled)
      schema.setText("target/info_schema/v1")
      engine.setSelectedItem("core")
      assertFalse(schema.isEnabled)
      assertEquals("target/info_schema/v1", schema.getText)
      defer.doClick()
      state.setText("missing")
      invalid(configurable, "directory containing manifest.json")
      assertEquals("missing", state.getText)
      state.setText("target")
      invalid(configurable, "separate from the active artifact directory")
      state.setText("baseline")
      configurable.apply()
      executable.setText("sqlfmt")
      assertTrue(onSave.isEnabled)
      onSave.doClick()
      executable.setText("")
      invalid(configurable, "Formatter executable: enter")
      assertTrue(onSave.isEnabled)
      assertTrue(onSave.isSelected)
      onSave.doClick()
      assertFalse(onSave.isEnabled)
      executable.setText("sqlfmt")
      onSave.doClick()
      configurable.apply()
      assertTrue(root.runOptions.formatOnSave)
      assertFalse(configurable.isModified)
    } finally configurable.disposeUIResources()
  }

  def testTypedPreviewLimitsStayInDraftsAndAreValidatedBeforeSaving(): Unit = {
    project()
    add("other/dbt_project.yml", "name: other\n")
    val own = root.directory.getPath
    val other = workspace.roots.find(_.name == "other").get.directory.getPath
    val configurable = new DbtSettingsConfigurable(getProject)
    val tabs = configurable.createComponent().asInstanceOf[JTabbedPane]
    val roots = named(tabs, "root").asInstanceOf[JComboBox[String]]
    val label = components(tabs).collectFirst { case label: JLabel if label.getText == "Preview row limit" => label }.get
    val editor = label.getLabelFor.asInstanceOf[JFormattedTextField]
    try {
      roots.setSelectedItem(own)
      editor.setText("1001")
      assertTrue(configurable.isModified)
      roots.setSelectedItem(other)
      roots.setSelectedItem(own)
      assertEquals("1001", editor.getText)
      invalid(configurable, "whole number from 1 to 1000")
      assertEquals(10, root.runOptions.previewLimit)
      editor.setText("25")
      configurable.apply()
      assertEquals(25, root.runOptions.previewLimit)
      assertFalse(configurable.isModified)
      editor.getFormatter.asInstanceOf[javax.swing.text.NumberFormatter].setFormat(java.text.NumberFormat.getIntegerInstance(java.util.Locale.FRANCE))
      editor.setText("1\u202f000")
      configurable.apply()
      assertEquals(1000, root.runOptions.previewLimit)
      assertFalse(configurable.isModified)
      val upstream = components(tabs).collectFirst {
        case label: JLabel if label.getText == "Upstream levels to show" => label.getLabelFor.asInstanceOf[JFormattedTextField]
      }.get
      upstream.setText("21")
      assertTrue(configurable.isModified)
      invalid(configurable, "allowed range")
      assertEquals(5, DbtProjectSettings.get(getProject).getState.upstreamDepth)
      assertEquals("21", upstream.getText)
      upstream.setText("7")
      configurable.apply()
      assertEquals(7, DbtProjectSettings.get(getProject).getState.upstreamDepth)
      assertFalse(configurable.isModified)
    } finally configurable.disposeUIResources()
  }

  def testLabelsIdentifyTheEditableInputsInBothTabs(): Unit = {
    project()
    val configurable = new DbtSettingsConfigurable(getProject)
    val tabs = configurable.createComponent().asInstanceOf[JTabbedPane]
    try {
      Vector("Owning dbt root", "Executable or external tool", "Environment variables", "Variables (JSON)",
        "Local builtin definitions", "Information Schema v1 directory (optional)", "Preview row limit", "Upstream levels to show",
        "Server URL", "API key", "DAG folders", "Web URL").foreach { text =>
        val label = components(tabs).collectFirst { case label: JLabel if label.getText == text => label }.get
        val input = label.getLabelFor.asInstanceOf[JComponent]
        assertNotNull(text, input)
        assertTrue(text, input.isFocusable)
        assertEquals(text, input.getAccessibleContext.getAccessibleName)
        assertFalse("Label must identify an input, not its layout wrapper", input.isInstanceOf[JPanel])
      }
      val url = components(tabs).collectFirst { case label: JLabel if label.getText == "Server URL" => label.getLabelFor.asInstanceOf[JTextField] }.get
      url.setText("https://user:secret@metabase.test")
      invalid(configurable, "Metabase Server URL")
      assertEquals(1, tabs.getSelectedIndex)
      assertNotNull(ComponentValidator.getInstance(url).get.getValidationInfo)
      assertFalse(url.getAccessibleContext.getAccessibleDescription.contains("secret"))
      url.setText("")
      val folders = components(tabs).collectFirst { case label: JLabel if label.getText == "DAG folders" => label.getLabelFor.asInstanceOf[JList[String]] }.get
      folders.getModel.asInstanceOf[DefaultListModel[String]].addElement("bad\u0000path")
      tabs.setSelectedIndex(0)
      invalid(configurable, "DAG folders, row 1")
      assertEquals(1, tabs.getSelectedIndex)
      assertEquals(0, folders.getSelectedIndex)
      assertEquals("bad\u0000path", folders.getModel.getElementAt(0))
      assertTrue(getProject.getService(classOf[DbtConsumersSettings]).getState.airflowPaths.isEmpty)
    } finally configurable.disposeUIResources()
  }
}
