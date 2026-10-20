package com.github.vbmacher.intellij.dbt.ide

import com.intellij.diff.DiffManager
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.testFramework.LightVirtualFile

private[ide] object DbtSqlEditors {
  def open(project: Project, name: String, sql: String): Unit = {
    val file = new LightVirtualFile(name.stripSuffix(".sql") + ".sql", FileTypeManager.getInstance.getFileTypeByExtension("sql"), sql)
    file.setWritable(false)
    FileEditorManager.getInstance(project).openFile(file, true)
    ()
  }
  def compare(project: Project, name: String, before: String, after: String, left: String, right: String): Unit = {
    val factory = DiffContentFactory.getInstance()
    val fileType = FileTypeManager.getInstance.getFileTypeByExtension("sql")
    DiffManager.getInstance.showDiff(project, new SimpleDiffRequest(name,
      factory.create(project, before, fileType), factory.create(project, after, fileType), left, right))
  }
}
