package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project._
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.util.xmlb.XmlSerializer
import org.junit.Assert._

import java.awt.Container
import javax.swing._

final class DbtSettingsTest extends DbtTestCase {
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
    val form = tabs.getComponentAt(0).asInstanceOf[JScrollPane].getViewport.getView.asInstanceOf[Container]
    def field(name: String): JComponent = components(form).collectFirst {
      case c: JComponent if c.getName == "dbt." + name => c
    }.getOrElse(throw new AssertionError("Missing settings field " + name))
    def text(name: String, value: String): Unit = field(name) match {
      case f: JTextField => f.setText(value)
      case f: JTextArea => f.setText(value)
      case f: TextFieldWithBrowseButton => f.setText(value)
    }
    val roots = form.getComponents.collectFirst { case c: JComboBox[_] => c.asInstanceOf[JComboBox[String]] }.get
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
        def layout(container: Container): Unit = {
          container.doLayout()
          container.getComponents.collect { case child: Container => child }.foreach(layout)
        }
        layout(tabs)
        layout(tabs)
        val toolbars = components(form).collect { case toolbar: com.intellij.openapi.actionSystem.ActionToolbar => toolbar }
        val updates = toolbars.map(_.updateActionsAsync())
        await("Mapping toolbar icons are rendered")(updates.forall(_.isDone) && toolbars.forall(_.hasVisibleActions))
        layout(tabs)
        val metadata = components(form).collectFirst {
          case box: JCheckBox if box.getText == "Show advanced metadata" => box
        }.get
        assertFalse("Advanced metadata stays collapsed until requested", metadata.isSelected)
        assertTrue("Environment rows remain visible", field("environmentVariables").getVisibleRect.height >= builtinTable.getRowHeight)
        val image = new java.awt.image.BufferedImage(tabs.getWidth, tabs.getHeight, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        try tabs.printAll(graphics) finally {
          graphics.dispose()
          tabs.removeNotify()
        }
        assertTrue(javax.imageio.ImageIO.write(image, "png", new java.io.File(path)))
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
}
