package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project.{DbtProjectSettings, DbtSettingsState, DbtWorkspace}
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import org.junit.Assert._
import java.awt.{Component, Container}
import javax.swing.{JButton, JComboBox, JList, JPasswordField, JTabbedPane, JTextField}
import scala.jdk.CollectionConverters._

final class DbtAirflowSettingsTest extends DbtCodeInsightTestCase {
  private def settings = getProject.getService(classOf[DbtConsumersSettings]).getState
  override protected def tearDown(): Unit = try {
    getProject.getService(classOf[DbtConsumersSettings]).loadState(new DbtConsumersState)
    DbtProjectSettings.get(getProject).loadState(new DbtSettingsState)
  } finally super.tearDown()
  private def children(c: Component): Vector[Component] = Vector(c) ++ (c match {
    case container: Container => container.getComponents.toVector.flatMap(children)
    case _ => Vector.empty
  })

  def testDagProjectsApplyResetAndAllowEmptyFolders(): Unit = {
    project()
    settings.airflowPaths.add("dags")
    val root = DbtWorkspace.get(getProject).roots.head
    val configurable = new DbtSettingsConfigurable(getProject)
    try {
      val tabs = configurable.createComponent().asInstanceOf[JTabbedPane]
      assertEquals(Vector("General", "Integrations"), (0 until tabs.getTabCount).map(tabs.getTitleAt).toVector)
      val fields = children(tabs)
      fields.collectFirst { case combo: JComboBox[_] if (0 until combo.getItemCount).exists(i => combo.getItemAt(i) == root.directory.getPath) => combo }.get
        .setSelectedItem(root.directory.getPath)
      val manifest = fields.collectFirst {
        case picker: TextFieldWithBrowseButton if Option(picker.getToolTipText).exists(_.startsWith("Optional manifest file")) => picker
      }.get
      assertTrue(fields.exists { case button: JButton => button.getText == "Add folder…"; case _ => false })
      val folders = fields.collectFirst { case list: JList[_] => list }.get
      val url = fields.collectFirst { case text: JTextField if Option(text.getToolTipText).exists(_.startsWith("Optional HTTP")) => text }.get
      assertFalse(configurable.isModified)
      manifest.setText("artifacts/current.json")
      tabs.setSelectedIndex(1)
      url.setText("https://airflow.test")
      assertTrue(configurable.isModified)
      configurable.apply()
      assertEquals("https://airflow.test", settings.airflowUrl)
      assertEquals("artifacts/current.json", DbtProjectSettings.get(getProject).options(root.directory.getPath).manifestPath)
      assertFalse(configurable.isModified)
      folders.setSelectedIndex(0)
      fields.collectFirst { case button: JButton if button.getText == "Remove" => button }.get.doClick()
      assertTrue(configurable.isModified)
      manifest.setText("discarded.json")
      configurable.reset()
      assertEquals(1, folders.getModel.getSize)
      assertEquals("artifacts/current.json", manifest.getText)
      folders.setSelectedIndex(0)
      fields.collectFirst { case button: JButton if button.getText == "Remove" => button }.get.doClick()
      configurable.apply()
      assertTrue(settings.airflowPaths.isEmpty)
      configurable.reset()
      assertEquals(0, folders.getModel.getSize)
      assertFalse(configurable.isModified)
      manifest.setText("must-not-save.json")
      url.setText("https://user:password@airflow.test")
      try { configurable.apply(); fail("Credential-bearing browser links must be rejected") } catch { case _: ConfigurationException => () }
      assertEquals("https://airflow.test", settings.airflowUrl)
      assertEquals("artifacts/current.json", DbtProjectSettings.get(getProject).options(root.directory.getPath).manifestPath)
      val secret = fields.collectFirst { case field: JPasswordField => field }.get
      secret.setText("unsaved-secret")
      configurable.disposeUIResources()
      assertEquals(0, secret.getPassword.length)
    } finally configurable.disposeUIResources()
  }

  def testManifestPickerSavesPerProjectAndCopiesWithRunOptions(): Unit = {
    val config = project()
    val root = DbtWorkspace.get(getProject).roots.head
    val configurable = new DbtSettingsConfigurable(getProject)
    try {
      val fields = children(configurable.createComponent())
      fields.collectFirst { case combo: JComboBox[_] if (0 until combo.getItemCount).exists(i => combo.getItemAt(i) == root.directory.getPath) => combo }.get
        .setSelectedItem(root.directory.getPath)
      val manifest = fields.collectFirst {
        case picker: TextFieldWithBrowseButton if Option(picker.getToolTipText).exists(_.startsWith("Optional manifest file")) => picker
      }.get
      manifest.setText("artifacts/current.json")
      assertTrue(configurable.isModified)
      configurable.apply()
      val options = DbtProjectSettings.get(getProject).options(config.getVirtualFile.getParent.getPath)
      assertEquals("artifacts/current.json", options.manifestPath)
      assertEquals("artifacts/current.json", options.copy().manifestPath)
      configurable.reset()
      assertEquals("artifacts/current.json", manifest.getText)
    } finally configurable.disposeUIResources()
  }
}
