package com.github.vbmacher.intellij.dbt.ide

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.{JBScrollPane, JBTextArea}
import javax.swing.JComponent

private[ide] object DbtTextDialog {
  def show(title: String, text: String): Unit = {
    val dialog = new DialogWrapper(false) {
      private val area = new JBTextArea(text, 24, 90)
      area.setEditable(false)
      setTitle(title); init()
      override def createCenterPanel(): JComponent = new JBScrollPane(area)
    }
    dialog.show()
  }
}
