package com.github.vbmacher.intellij.dbt.project

import com.github.vbmacher.intellij.dbt.syntax.DbtYaml
import com.intellij.util.EnvironmentUtil

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters._

private[dbt] final case class DbtProfileContext(directory: Path, targets: Vector[String], defaultTarget: Option[String], adapter: Option[String])
private[dbt] object DbtExecutionContext {
  def profiles(root: DbtRoot, environment: Map[String, String] = sys.env): DbtProfileContext = {
    val effectiveEnvironment = environment ++ DbtEnvironment.values(root)
    val configured = Option(root.runOptions.profilesDirectory).filter(_.nonEmpty).map(Paths.get(_))
    val directory = configured.orElse(effectiveEnvironment.get("DBT_PROFILES_DIR").filter(_.nonEmpty).map(Paths.get(_)))
      .map(p => if (p.isAbsolute) p else root.executionDirectory.resolve(p))
      .getOrElse(if (root.psiFile(root.executionDirectory.resolve("profiles.yml").toString).nonEmpty) root.executionDirectory
        else Paths.get(System.getProperty("user.home"), ".dbt"))
    val mapping = root.psiFile(directory.resolve("profiles.yml").toString).flatMap(DbtYaml.mapping)
    val config = root.psiFile(root.executionDirectory.resolve("dbt_project.yml").toString).flatMap(DbtYaml.mapping).getOrElse(root.config)
    val profile = DbtYaml.string(config, "profile").flatMap(name => mapping.flatMap(DbtYaml.map(_, name)))
    val outputs = profile.flatMap(DbtYaml.map(_, "outputs"))
    val targets = outputs.toVector.flatMap(_.getKeyValues.asScala.map(_.getKeyText))
    val default = profile.flatMap(DbtYaml.string(_, "target")).filterNot(_.contains("{{"))
    val selected = root.selectedTarget.orElse(default)
    val adapter = selected.flatMap(t => outputs.flatMap(DbtYaml.map(_, t))).flatMap(DbtYaml.string(_, "type"))
    DbtProfileContext(directory, targets, default, adapter)
  }

  def executable(root: DbtRoot): String = {
    val configured = root.runOptions.executable.trim
    if (configured == "dbt") virtualEnvironmentExecutable(root).map(_.toString).getOrElse("dbt")
    else if (configured.contains('/') || configured.contains('\\')) root.executionDirectory.resolve(configured).normalize().toString
    else configured
  }

  private def virtualEnvironmentExecutable(root: DbtRoot): Option[Path] =
    Vector(".venv/bin/dbt", "venv/bin/dbt", ".venv/Scripts/dbt.exe", "venv/Scripts/dbt.exe")
      .map(root.executionDirectory.resolve(_)).find(Files.isRegularFile(_))

  /** Wrappers and tools share configured variables and the discovered local dbt/Python. */
  def environment(root: DbtRoot): Map[String, String] = {
    val configured = DbtEnvironment.values(root)
    configured ++ virtualEnvironmentExecutable(root).map { executable =>
      val inheritedPath = configured.getOrElse("PATH", Option(EnvironmentUtil.getValue("PATH")).getOrElse(""))
      Map("PATH" -> (executable.getParent.toString + java.io.File.pathSeparator + inheritedPath))
    }.getOrElse(Map.empty)
  }

  def activeTarget(root: DbtRoot): Option[String] = root.selectedTarget.orElse(profiles(root).defaultTarget)
}
