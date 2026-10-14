package com.github.vbmacher.intellij.dbt.project

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters._
import scala.util.Try

/** Provenance is tied to the exact manifest bytes. Never silently reuse another runner target's physical relations. */
private[dbt] object DbtArtifactContext {
  private val Marker = ".dbt-workspace-context"
  private def textDigest(text: String): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(text.stripPrefix("\ufeff").replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8)).map(b => f"${b & 0xff}%02x").mkString
  private def digest(path: Path): String = textDigest(Files.readString(path, StandardCharsets.UTF_8))
  def record(directory: String, projectRoot: String, target: String): Unit = {
    val path = Paths.get(directory)
    val manifest = path.resolve("manifest.json")
    if (Files.isRegularFile(manifest)) Files.write(path.resolve(Marker), Vector(projectRoot, target, digest(manifest)).asJava, StandardCharsets.UTF_8)
  }
  def matches(root: DbtRoot): Boolean = {
    val manifest = root.setting("manifest").map(Paths.get(_)).map(p => if (p.isAbsolute) p else Paths.get(root.directory.getPath).resolve(p))
    manifest.exists { path =>
      Try {
        val marker = Files.readAllLines(path.getParent.resolve(Marker), StandardCharsets.UTF_8).asScala.toVector
        marker.lastOption.contains(root.psiFile(path.toString).map(f => textDigest(f.getText)).getOrElse("")) && marker == Vector(Option(root.runOptions.projectRoot).filter(_.nonEmpty).map(p => {
          val value = Paths.get(p); (if (value.isAbsolute) value else Paths.get(root.directory.getPath).resolve(value)).normalize().toString
        }).getOrElse(root.directory.getPath), DbtExecutionContext.activeTarget(root).getOrElse(""), digest(path))
      }.getOrElse(false)
    }
  }
}
