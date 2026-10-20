package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project.DbtProjectSettings
import com.intellij.openapi.fileChooser.{FileChooser, FileChooserDescriptorFactory}
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import java.awt.{BorderLayout, FlowLayout, GridBagConstraints, GridBagLayout, Insets}
import java.nio.file.Paths
import javax.swing._
import scala.jdk.CollectionConverters._

private[ide] final class DbtAirflowSettingsPanel(project: Project) {
  private val paths = new DefaultListModel[String]
  private val folders = new JList[String](paths)
  private val url = new JTextField
  val component = new JPanel(new GridBagLayout)
  component.setBorder(BorderFactory.createTitledBorder("Airflow — local DAG discovery"))
  folders.setVisibleRowCount(3)
  folders.setToolTipText("DAG folders or Airflow project directories, scanned recursively for Python files. Relative paths use the IDEA project directory.")
  url.setToolTipText("Optional HTTP or HTTPS Airflow web URL, without credentials. Used only for browser links.")
  val folderPanel = new JPanel(new BorderLayout(4, 4))
  folderPanel.add(new JScrollPane(folders), BorderLayout.CENTER)
  val folderButtons = new JPanel(new FlowLayout(FlowLayout.LEFT))
  val add = new JButton("Add folder…")
  add.addActionListener(_ => FileChooser.chooseFiles(FileChooserDescriptorFactory.multiDirs().withTitle("Select local Airflow DAG folders or projects"),
    project, null).foreach(f => if (!paths.contains(f.getPath)) paths.addElement(f.getPath)))
  val remove = new JButton("Remove")
  remove.addActionListener(_ => folders.getSelectedIndices.reverse.foreach(paths.remove))
  folderButtons.add(add); folderButtons.add(remove); folderPanel.add(folderButtons, BorderLayout.SOUTH)
  Vector[(String, JComponent)]("DAG projects" -> folderPanel, "Airflow URL (optional)" -> url).zipWithIndex.foreach {
    case ((label, field), row) =>
      val c = new GridBagConstraints
      c.gridy = row; c.gridx = 0; c.anchor = GridBagConstraints.WEST; c.insets = new Insets(3, 5, 3, 8)
      component.add(new JLabel(label), c)
      c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL
      component.add(field, c)
  }
  private def settings = project.getService(classOf[DbtConsumersSettings]).getState
  private def folderValues: Vector[String] = (0 until paths.size()).map(paths.get).toVector
  private def values: Vector[Any] = Vector(folderValues, url.getText.trim)
  private def stored: Vector[Any] = Vector(settings.airflowPaths.asScala.toVector, settings.airflowUrl)
  def isModified: Boolean = values != stored
  def validate(): Unit = {
    if (url.getText.trim.nonEmpty && DbtAirflow.safeUrl(url.getText.trim).isEmpty)
      throw new ConfigurationException("Use an http(s) Airflow URL without embedded credentials.")
    try {
      folderValues.foreach(Paths.get(_))
    } catch { case _: java.nio.file.InvalidPathException => throw new ConfigurationException("Use valid local Airflow directory paths.") }
  }
  def apply(): Unit = {
    settings.airflowPaths = new java.util.ArrayList[String](folderValues.asJava)
    settings.airflowUrl = url.getText.trim
    DbtProjectSettings.get(project).changed()
  }
  def reset(): Unit = {
    paths.clear(); settings.airflowPaths.asScala.foreach(paths.addElement)
    url.setText(settings.airflowUrl)
  }
}
