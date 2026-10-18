package com.github.vbmacher.intellij.dbt.project

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters._
import scala.util.Try

/** Provenance binds the manifest bytes to the execution configuration captured before launching dbt. */
private[dbt] object DbtArtifactContext {
  private val Marker = ".dbt-workspace-context"
  private def textDigest(text: String): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(text.stripPrefix("\ufeff").replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8)).map(b => f"${b & 0xff}%02x").mkString
  private def digest(path: Path): String = textDigest(Files.readString(path, StandardCharsets.UTF_8))
  def fingerprint(directory: String): Option[String] = Try(digest(Paths.get(directory).resolve("manifest.json"))).toOption
  def identity(root: DbtRoot): Vector[String] = {
    val directory = root.executionDirectory
    val profiles = DbtExecutionContext.profiles(root).directory.normalize()
    val inputs = Vector(profiles.resolve("profiles.yml").toString, directory.resolve("dbt_project.yml").toString,
      directory.resolve("vars.yml").toString) ++ root.setting("run-config")
    val state = root.runOptions.stateDirectory.trim
    val baseline = if (state.isEmpty) Vector.empty else {
      val path = directory.resolve(state).resolve("manifest.json").normalize().toString
      Vector(path, root.psiFile(path).map(_.getModificationStamp.toString).getOrElse("unavailable"))
    }
    val configuration = inputs.flatMap(path => Vector(path, root.psiFile(path).map(_.getText).getOrElse(""))) ++
      root.envFiles.flatMap(file => Vector(file.getVirtualFile.getPath, file.getText)) ++
      Vector(root.runOptions.executable, root.runOptions.executableArguments, state, root.runOptions.defer.toString,
        root.runOptions.favorState.toString) ++ baseline
    Vector(directory.toString, DbtExecutionContext.activeTarget(root).getOrElse(""), textDigest(configuration.mkString("\u0000")))
  }
  def record(directory: String, context: Vector[String]): Unit = {
    val path = Paths.get(directory)
    val manifest = path.resolve("manifest.json")
    if (Files.isRegularFile(manifest)) Files.write(path.resolve(Marker), (context :+ digest(manifest)).asJava, StandardCharsets.UTF_8)
  }
  def matches(root: DbtRoot): Boolean = {
    val manifest = root.setting("manifest").map(Paths.get(_)).map(p => if (p.isAbsolute) p else Paths.get(root.directory.getPath).resolve(p))
    manifest.exists { path =>
      Try {
        val marker = Files.readAllLines(path.getParent.resolve(Marker), StandardCharsets.UTF_8).asScala.toVector
        marker.lastOption.contains(root.psiFile(path.toString).map(f => textDigest(f.getText)).getOrElse("")) &&
          marker == (identity(root) :+ digest(path))
      }.getOrElse(false)
    }
  }
}
