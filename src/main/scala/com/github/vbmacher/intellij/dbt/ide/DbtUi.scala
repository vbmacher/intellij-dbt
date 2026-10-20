package com.github.vbmacher.intellij.dbt.ide

import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBTextArea
import java.nio.file.Paths
import javax.swing.{JComboBox, JList}

/** Presentation only: never resolves resources or launches a process. */
private[ide] object DbtUi {
  def name(path: String): String = scala.util.Try(Paths.get(path).getFileName.toString).getOrElse(path)
  def evidenceProblem(problem: String): String = problem.replace("Not run / result unavailable", "Execution result artifact unavailable")

  def projectRenderer(combo: JComboBox[String], names: () => Map[String, String]): Unit = {
    combo.setRenderer(new SimpleListCellRenderer[String] {
      override def customize(list: JList[_ <: String], value: String, index: Int, selected: Boolean, focus: Boolean): Unit = {
        val projects = names()
        val label = Option(value).map { v =>
          val short = projects.getOrElse(v, name(v))
          if (projects.values.count(_ == short) > 1) short + " (" + v + ")" else short
        }.getOrElse("Choose project")
        setText(label)
        setToolTipText(value)
      }
    })
  }

  def text(value: String): JBTextArea = {
    val area = new JBTextArea(value)
    area.setEditable(false)
    area.setLineWrap(true)
    area.setWrapStyleWord(true)
    area.setOpaque(false)
    area
  }
}
