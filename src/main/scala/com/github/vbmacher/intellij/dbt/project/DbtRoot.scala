package com.github.vbmacher.intellij.dbt.project

import com.github.vbmacher.intellij.dbt.syntax.DbtYaml
import com.intellij.openapi.vfs.{VfsUtilCore, VirtualFile}
import com.intellij.psi.PsiFile
import org.jetbrains.yaml.psi.{YAMLKeyValue, YAMLMapping, YAMLScalar}

import java.nio.file.{FileSystems, PathMatcher, Paths}
import scala.util.Try

/** A dbt project directory with its `dbt_project.yml` and optional `.dbt-navigation.yml` settings. */
private[dbt] final case class DbtRoot(directory: VirtualFile, config: YAMLMapping, settings: Option[YAMLMapping]) {
  val name: String = DbtYaml.string(config, "name").getOrElse(directory.getName)

  def psiFile(path: String): Option[PsiFile] =
    Option(directory.findFileByRelativePath(path)).flatMap(DbtYaml.psiFile(config.getProject, _))

  def yaml(path: String): Option[YAMLMapping] = psiFile(path).flatMap(DbtYaml.mapping)

  def setting(key: String): Option[String] = settings.flatMap(DbtYaml.string(_, key))

  def runConfiguration: Option[YAMLMapping] = setting("run-config").flatMap(yaml)

  def runtimeSetting(key: String): Option[String] = setting(key).orElse(runConfiguration.flatMap(DbtYaml.string(_, key)))

  def profilesFile: String = runtimeSetting("profiles-file").getOrElse("profiles.yml")

  def envFiles: Vector[PsiFile] =
    settings.map(DbtYaml.strings(_, "env-files", Vector(".env"))).getOrElse(Vector(".env")).flatMap(psiFile)

  /** Whether a file holds node selection expressions, in addition to `selectors.yml`. */
  def isSelectionFile(file: PsiFile): Boolean = relative(file.getVirtualFile)
    .exists((settings.toVector.flatMap(DbtYaml.strings(_, "selection-files")) ++ setting("run-config")).contains)

  def profile: Option[YAMLMapping] = DbtYaml.string(config, "profile").flatMap(p => yaml(profilesFile).flatMap(DbtYaml.map(_, p)))

  /** Declarations of a key of the selected profile target; `name` resolves to the target selection itself. */
  def target(key: String): Vector[YAMLKeyValue] = profile.toVector.flatMap { p =>
    if (key == "name") settings.flatMap(DbtYaml.keyValue(_, "target")).orElse(runConfiguration.flatMap(DbtYaml.keyValue(_, "target")))
      .orElse(DbtYaml.keyValue(p, "target")).toVector
    else runtimeSetting("target").orElse(DbtYaml.string(p, "target")).filterNot(_.contains("{{")).toVector
      .flatMap(t => DbtYaml.map(p, "outputs").flatMap(DbtYaml.map(_, t)).flatMap(DbtYaml.keyValue(_, key)))
  }

  def adapter: Option[String] = runtimeSetting("adapter").orElse(target("type").headOption
    .flatMap(k => Option(k.getValue)).collect { case s: YAMLScalar => s.getTextValue })

  /** Adapters whose implementations apply, most specific first. */
  def adapterFallbacks: Vector[String] =
    adapter.toVector ++ (if (adapter.contains("redshift")) Vector("postgres") else Vector.empty) :+ "default"

  val engine: String = setting("engine").getOrElse("core")

  val version: String = setting("dbt-version").orElse(DbtYaml.string(config, "require-dbt-version")
    .flatMap("[0-9]+\\.[0-9]+".r.findFirstIn)).getOrElse("1.10")

  /** Whether the configured engine supports a dbt Core 1.x feature; dbt v2 supports all of them. */
  def supports(minor: Int, patch: Int = 0): Boolean = engine == "v2" || engine == "core" &&
    (version.split("\\.").take(2).flatMap(_.toIntOption).toVector match {
      case Vector(major, _) if major >= 2 => true
      case Vector(1, m) => m > minor || m == minor && version.split("\\.").lift(2).flatMap(_.toIntOption).forall(_ >= patch)
      case _ => false
    })

  val pathKinds: Vector[(String, String)] = Vector(
    "model" -> "models", "seed" -> "seeds", "snapshot" -> "snapshots", "analysis" -> "analyses",
    "macro" -> "macros", "test" -> "tests", "doc" -> "docs", "function" -> "functions", "check" -> "checks")
    .filter { case (k, _) => (k != "function" || supports(11)) && (k != "check" || engine == "v2") }
    .flatMap { case (kind, default) =>
      val key = Map("model" -> "model-paths", "analysis" -> "analysis-paths", "doc" -> "docs-paths").getOrElse(kind, s"$kind-paths")
      DbtYaml.strings(config, key, Vector(default)).map(kind -> _)
    }

  val packagePath: String = DbtYaml.string(config, "packages-install-path").getOrElse("dbt_packages")

  private val ignores: Vector[(Boolean, Boolean, Vector[PathMatcher])] = psiFile(".dbtignore").map(_.getText).getOrElse("")
    .linesIterator.map(_.trim).filter(s => s.nonEmpty && !s.startsWith("#")).map { pattern =>
      val negated = pattern.startsWith("!")
      val raw = if (negated) pattern.drop(1) else pattern
      val relative = raw.stripPrefix("/").stripSuffix("/")
      val patterns = Vector(relative) ++
        (if (!raw.startsWith("/") && !relative.contains("/")) Vector("**/" + relative) else Vector.empty) ++
        (if (relative.startsWith("**/")) Vector(relative.stripPrefix("**/")) else Vector.empty)
      (negated, raw.endsWith("/"), patterns.flatMap(p => Try(FileSystems.getDefault.getPathMatcher("glob:" + p)).toOption))
    }.toVector

  val exclusions: Vector[String] = Vector(".git", ".idea", "logs", packagePath, DbtYaml.string(config, "target-path").getOrElse("target"))

  def relative(file: VirtualFile): Option[String] = Option(VfsUtilCore.getRelativePath(file, directory, '/'))

  def isFile(file: PsiFile, path: String): Boolean = relative(file.getVirtualFile).contains(path)

  def pathContains(path: String, relative: String): Boolean =
    path == "." || relative == path || relative.startsWith(path.stripSuffix("/") + "/")

  /** Folders between a resource path and a file inside it. */
  def folders(path: String, relative: String): Option[Vector[String]] =
    if (pathContains(path, relative)) Some(relative.stripPrefix(path.stripSuffix("/") + "/").split("/").toVector.dropRight(1))
    else None

  /** The resource kind of a file and its folders within the resource path. */
  def resourceFolders(file: VirtualFile): Option[(String, Vector[String])] = relative(file).flatMap { r =>
    pathKinds.iterator.flatMap { case (k, path) => folders(path, r).map(k -> _) }.nextOption()
  }

  def excluded(file: VirtualFile): Boolean = relative(file).exists { r =>
    val parts = r.split("/").toVector
    val paths = parts.indices.map(i => Paths.get(parts.take(i + 1).mkString("/")) -> (i < parts.size - 1 || file.isDirectory))
    exclusions.exists(pathContains(_, r)) || ignores.foldLeft(false) { case (ignored, (negated, directoriesOnly, matchers)) =>
      if (paths.exists { case (path, directory) => (!directoriesOnly || directory) && matchers.exists(_.matches(path)) }) !negated
      else ignored
    }
  }

  /** Ignored directories may contain re-included descendants; generated paths stay pruned. */
  def traversable(file: VirtualFile): Boolean = !excluded(file) || file.isDirectory && ignores.exists(_._1) &&
    relative(file).exists(r => !exclusions.exists(pathContains(_, r)))

  def kind(file: VirtualFile): Option[String] = resourceFolders(file).map(_._1)

  def resource(file: PsiFile): Boolean = !excluded(file.getVirtualFile) && kind(file.getVirtualFile).nonEmpty
}
