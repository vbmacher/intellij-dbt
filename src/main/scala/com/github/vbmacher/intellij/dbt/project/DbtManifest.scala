package com.github.vbmacher.intellij.dbt.project

import com.google.gson.JsonObject
import com.intellij.openapi.project.Project

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
