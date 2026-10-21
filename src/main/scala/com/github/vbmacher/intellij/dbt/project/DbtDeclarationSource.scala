package com.github.vbmacher.intellij.dbt.project

import com.google.gson.JsonObject
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Port of declarations not written in project sources, such as SQL projections, environment variables and artifacts.
 * Sources are registered in `plugin.xml` in priority order; each one sees the declarations of the sources before it.
 */
private[dbt] trait DbtDeclarationSource {
  def declarations(workspace: DbtWorkspace): Vector[DbtSymbol]

  /** Files read outside of PSI whose changes invalidate the workspace. */
  def inputs(workspace: DbtWorkspace): Vector[VirtualFile] = Vector.empty

  /** Optional documentation/physical metadata; source properties remain authoritative. */
  def documentation(workspace: DbtWorkspace, symbol: DbtSymbol): Option[DbtResourceData] = None

  /** Missing references are not conclusive while configured metadata is unavailable or malformed. */
  def canReportMissing(workspace: DbtWorkspace, root: DbtRoot): Boolean = true

  def artifactStatus(root: DbtRoot): Vector[String] = Vector.empty
}

private[dbt] object DbtDeclarationSource {
  val EP: ExtensionPointName[DbtDeclarationSource] = ExtensionPointName.create("com.github.vbmacher.intellij-dbt.declarationSource")
}

/** Consumers choose validation requirements; schema support and resource extraction have one owner.
 * Baseline snapshots contain only resources participating in source-state comparison.
 */
private[dbt] sealed abstract class DbtManifestPolicy(val maxBytes: Int) extends Product with Serializable
private[dbt] object DbtManifestPolicy {
  case object Navigation extends DbtManifestPolicy(Int.MaxValue)
  case object TestDiscovery extends DbtManifestPolicy(25000000)
  case object Baseline extends DbtManifestPolicy(50000000)
}

private[dbt] final case class DbtManifestResource(section: String, id: String, data: JsonObject)
private[dbt] final case class DbtManifest(schemaVersion: Int, projectName: Option[String], resources: Vector[DbtManifestResource]) {
  def in(sections: String*): Vector[DbtManifestResource] = resources.filter(r => sections.contains(r.section))
}

/** Local manifest access, implemented by metadata. Baseline paths must be checked by the caller before reading. */
private[dbt] trait DbtManifestReader {
  def parse(text: String, policy: DbtManifestPolicy): Either[String, DbtManifest]
  def read(root: DbtRoot, path: String, policy: DbtManifestPolicy): Either[String, DbtManifest]
}

private[dbt] object DbtManifestReader {
  def get(project: Project): DbtManifestReader = project.getService(classOf[DbtManifestReader])
}
