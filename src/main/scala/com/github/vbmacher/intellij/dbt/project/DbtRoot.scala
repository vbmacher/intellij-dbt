package com.github.vbmacher.intellij.dbt.project

import com.github.vbmacher.intellij.dbt.syntax.DbtYaml
import com.intellij.openapi.vfs.{LocalFileSystem, VfsUtilCore, VirtualFile}
import com.intellij.psi.PsiFile
import org.jetbrains.yaml.psi.{YAMLKeyValue, YAMLMapping, YAMLScalar}

import java.nio.file.{FileSystems, Path, PathMatcher, Paths}
import scala.util.Try

/** A dbt project directory with its `dbt_project.yml` and per-root IntelliJ settings. */
private[dbt] final case class DbtRoot(directory: VirtualFile, config: YAMLMapping) {
  val name: String = DbtYaml.string(config, "name").getOrElse(directory.getName)

  private def base: Path = Paths.get(directory.getPath)

  def psiFile(path: String): Option[PsiFile] = {
    val requested = Paths.get(path).normalize()
    val relative = if (requested.isAbsolute && requested.startsWith(base)) base.relativize(requested).toString else path
    Option(directory.findFileByRelativePath(relative)).orElse(
      if (requested.isAbsolute) Option(LocalFileSystem.getInstance.findFileByPath(path)) else None).flatMap(DbtYaml.psiFile(config.getProject, _))
  }

  def yaml(path: String): Option[YAMLMapping] = psiFile(path).flatMap(DbtYaml.mapping)

  private var executionOptions: Option[DbtRunOptions] = None

  def runOptions: DbtRunOptions = executionOptions.getOrElse(DbtProjectSettings.get(config.getProject).options(directory.getPath))

  /** Execution overrides do not change this project's declaration/package identity. */
  def withRunOptions(options: DbtRunOptions): DbtRoot = {
    val configured = copy()
    configured.executionOptions = Some(options)
    configured
  }

  def executionDirectory: Path =
    Option(runOptions.projectRoot).filter(_.nonEmpty).map(base.resolve).getOrElse(base).normalize()

  def artifactDirectory: Path = {
    val path = Paths.get(runOptions.artifactDirectory)
    (if (path.isAbsolute) path else executionDirectory.resolve(path)).normalize()
  }

  def setting(key: String): Option[String] = key match {
    case "manifest" => Some(Option(runOptions.manifestPath).filter(_.nonEmpty)
      .map(p => executionDirectory.resolve(p).normalize()).getOrElse(artifactDirectory.resolve("manifest.json")).toString)
    case "catalog" => Some(Option(runOptions.catalogPath).filter(_.nonEmpty)
      .getOrElse(artifactDirectory.resolve("catalog.json").toString))
    case "engine" => Some(runOptions.engine)
    case "dbt-version" => Option(runOptions.dbtVersion).filter(_.nonEmpty)
    case "profiles-file" => Option(runOptions.profilesFile).filter(_.nonEmpty)
    case "adapter" => Option(runOptions.adapter).filter(_.nonEmpty)
    case "state-manifest" => Option(runOptions.stateManifestPath).filter(_.nonEmpty)
    case "info-schema-dir" => Option(runOptions.infoSchemaDirectory).filter(_.nonEmpty)
    case "target" => Option(runOptions.target).filter(_.nonEmpty)
    case _ => None
  }

  /** The target chosen in IntelliJ settings, before the profile default. */
  def selectedTarget: Option[String] = setting("target")

  def profilesFile: String = setting("profiles-file").getOrElse(DbtExecutionContext.profiles(this).directory.resolve("profiles.yml").toString)

  def envFiles: Vector[PsiFile] =
    (if (!runOptions.loadEnvironmentFiles) Vector.empty
    else if (runOptions.environmentFiles.trim.nonEmpty) runOptions.environmentFiles.split(";").iterator.map(_.trim).filter(_.nonEmpty).toVector
    else Vector(".env")).flatMap(psiFile)

  def profile: Option[YAMLMapping] = DbtYaml.string(config, "profile").flatMap(p => yaml(profilesFile).flatMap(DbtYaml.map(_, p)))

  /** Declarations of a key of the selected profile target; `name` resolves to the target selection itself. */
  def target(key: String): Vector[YAMLKeyValue] = profile.toVector.flatMap { p =>
    if (key == "name") {
      if (runOptions.target.nonEmpty) DbtYaml.map(p, "outputs").flatMap(DbtYaml.keyValue(_, runOptions.target)).toVector
      else DbtYaml.keyValue(p, "target").toVector
    }
    else selectedTarget.orElse(DbtYaml.string(p, "target")).filterNot(_.contains("{{")).toVector
      .flatMap(t => DbtYaml.map(p, "outputs").flatMap(DbtYaml.map(_, t)).flatMap(DbtYaml.keyValue(_, key)))
  }

  def adapter: Option[String] = setting("adapter").orElse(target("type").headOption
    .flatMap(k => Option(k.getValue)).collect { case s: YAMLScalar => s.getTextValue })

  /** Adapters whose implementations apply, most specific first. */
  def adapterFallbacks: Vector[String] =
    adapter.toVector ++ (if (adapter.contains("redshift")) Vector("postgres") else Vector.empty) :+ "default"

  val engine: String = setting("engine").getOrElse("core")

  val version: String = setting("dbt-version").getOrElse {
    val requirements = DbtYaml.strings(config, "require-dbt-version")
    if (requirements.isEmpty) "1.10" else DbtRoot.requiredVersion(requirements).getOrElse("unknown")
  }

  /** Whether the configured engine supports a dbt Core 1.x feature; dbt v2 supports all of them. */
  def supports(minor: Int, patch: Int = 0): Boolean = engine == "v2" || engine == "core" &&
    (version.split("\\.").take(2).flatMap(_.toIntOption).toVector match {
      case Vector(major, _) if major >= 2 => true
      case Vector(1, m) => m > minor || m == minor && version.split("\\.").lift(2).flatMap(_.toIntOption).forall(_ >= patch)
      case _ => false
    })

  /** Whether the configured engine supports a resource kind. */
  def supportsKind(kind: String): Boolean = kind match {
    case "function" => supports(11)
    case "check" => engine == "v2"
    case "semantic_model" | "metric" => supports(6)
    case "saved_query" => supports(7)
    case "unit_test" => supports(8)
    case _ => true
  }

  val pathKinds: Vector[(String, String)] = Vector(
    "model" -> "models", "seed" -> "seeds", "snapshot" -> "snapshots", "analysis" -> "analyses",
    "macro" -> "macros", "test" -> "tests", "doc" -> "docs", "function" -> "functions", "check" -> "checks")
    .filter { case (k, _) => supportsKind(k) }
    .flatMap { case (kind, default) =>
      val key = Map("model" -> "model-paths", "analysis" -> "analysis-paths", "doc" -> "docs-paths").getOrElse(kind, s"$kind-paths")
      DbtYaml.strings(config, key, Vector(default)).map(kind -> _)
    }

  val packagePath: String = DbtYaml.string(config, "packages-install-path").getOrElse("dbt_packages")

  private val ignores: Vector[(Boolean, Boolean, Vector[PathMatcher])] =
    DbtRoot.ignorePatterns(psiFile(".dbtignore").map(_.getText).getOrElse(""))

  private val configuredOutput =
    if (artifactDirectory.startsWith(base) && artifactDirectory != base) Vector(base.relativize(artifactDirectory).toString.replace('\\', '/'))
    else Vector.empty
  val exclusions: Vector[String] = Vector(".git", ".idea", ".venv", "venv", "logs", packagePath,
    DbtYaml.string(config, "target-path").getOrElse("target")) ++ configuredOutput

  def relative(file: VirtualFile): Option[String] = Option(VfsUtilCore.getRelativePath(file, directory, '/'))

  def isFile(file: PsiFile, path: String): Boolean =
    if (Paths.get(path).isAbsolute) Paths.get(file.getVirtualFile.getPath).normalize() == Paths.get(path).normalize()
    else relative(file.getVirtualFile).contains(path)

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

  def excluded(file: VirtualFile): Boolean = relative(file).exists(r => excludedPath(r, file.isDirectory))

  def excludedPath(r: String, directory: Boolean = false): Boolean = excludedPath(r, directory, ignores)

  /** Exclusions for a path under another `.dbtignore` text, such as one from a baseline revision. */
  def excludedPathWith(ignoreText: String): String => Boolean = {
    val parsed = DbtRoot.ignorePatterns(ignoreText)
    r => excludedPath(r, directory = false, parsed)
  }

  private def excludedPath(r: String, directory: Boolean, ignores: Vector[(Boolean, Boolean, Vector[PathMatcher])]): Boolean = {
    val parts = r.split("/").toVector
    val paths = parts.indices.map(i => Paths.get(parts.take(i + 1).mkString("/")) -> (i < parts.size - 1 || directory))
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

private[dbt] object DbtRoot {
  private val VersionConstraint = """(>=|<=|>|<|=)?([0-9]+)\.([0-9]+)(?:\.([0-9]+))?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?""".r

  /** Lowest stable version satisfying every bound; unsupported or inconsistent ranges have no hint. */
  private def requiredVersion(requirements: Vector[String]): Option[String] = {
    val parsed = requirements.flatMap(_.split(",", -1)).map(_.trim).map {
      case VersionConstraint(operator, major, minor, patch) =>
        Some(Option(operator).getOrElse("=") -> (BigInt(major), BigInt(minor), BigInt(Option(patch).getOrElse("0"))))
      case _ => None
    }
    if (parsed.isEmpty || parsed.exists(_.isEmpty)) None
    else {
      val constraints = parsed.flatten
      val lowest = constraints.collect {
        case (">", (major, minor, patch)) => (major, minor, patch + 1)
        case (">=" | "=", version) => version
      }.maxOption.getOrElse((BigInt(0), BigInt(0), BigInt(0)))
      val compatible = constraints.forall { case (operator, bound) =>
        val comparison = Ordering[(BigInt, BigInt, BigInt)].compare(lowest, bound)
        operator match {
          case ">" => comparison > 0
          case ">=" => comparison >= 0
          case "<" => comparison < 0
          case "<=" => comparison <= 0
          case _ => comparison == 0
        }
      }
      Option.when(compatible)(s"${lowest._1}.${lowest._2}.${lowest._3}")
    }
  }

  /** Parsed `.dbtignore` rules as (negated, directories only, matchers). */
  def ignorePatterns(text: String): Vector[(Boolean, Boolean, Vector[PathMatcher])] =
    text.linesIterator.map(_.trim).filter(s => s.nonEmpty && !s.startsWith("#")).map { pattern =>
      val negated = pattern.startsWith("!")
      val raw = if (negated) pattern.drop(1) else pattern
      val relative = raw.stripPrefix("/").stripSuffix("/")
      val patterns = Vector(relative) ++
        (if (!raw.startsWith("/") && !relative.contains("/")) Vector("**/" + relative) else Vector.empty) ++
        (if (relative.startsWith("**/")) Vector(relative.stripPrefix("**/")) else Vector.empty)
      (negated, raw.endsWith("/"), patterns.flatMap(p => Try(FileSystems.getDefault.getPathMatcher("glob:" + p)).toOption))
    }.toVector
}
