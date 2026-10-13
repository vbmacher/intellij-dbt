package com.github.vbmacher.intellij.dbt.project

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.vfs.VirtualFile

/**
 * Port of declarations not written in project sources, such as SQL projections, environment variables and artifacts.
 * Sources are registered in `plugin.xml` in priority order; each one sees the declarations of the sources before it.
 */
private[dbt] trait DbtDeclarationSource {
  def declarations(workspace: DbtWorkspace): Vector[DbtSymbol]

  /** Files read outside of PSI whose changes invalidate the workspace. */
  def inputs(workspace: DbtWorkspace): Vector[VirtualFile] = Vector.empty
}

private[dbt] object DbtDeclarationSource {
  val EP: ExtensionPointName[DbtDeclarationSource] = ExtensionPointName.create("com.github.vbmacher.intellij-dbt.declarationSource")
}
