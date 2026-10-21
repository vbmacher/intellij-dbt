package com.github.vbmacher.intellij.dbt.project

import com.github.vbmacher.intellij.dbt.syntax.DbtYaml
import com.intellij.openapi.components.{PersistentStateComponent, Service, State, Storage}
import com.google.gson.{JsonObject, JsonParser}
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.{SimpleModificationTracker, TextRange}
import com.intellij.util.EnvironmentUtil
import com.intellij.util.messages.Topic
import com.intellij.util.xmlb.XmlSerializerUtil
import com.intellij.testFramework.LightVirtualFile

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.regex.{Matcher, Pattern}
import scala.beans.BeanProperty
import scala.jdk.CollectionConverters._
import scala.util.Try

final class DbtRunOptions {
  @BeanProperty var executable: String = "dbt"
  @BeanProperty var executableArguments: String = ""
  @BeanProperty var environmentFiles: String = ""
  @BeanProperty var loadEnvironmentFiles: Boolean = true
  @BeanProperty var engine: String = "core"
  @BeanProperty var dbtVersion: String = ""
  @BeanProperty var profilesFile: String = ""
  @BeanProperty var adapter: String = ""
  @BeanProperty var variables: String = ""
  @BeanProperty var environmentVariables: java.util.Map[String, String] = new java.util.TreeMap[String, String]()
  @BeanProperty var builtinSources: java.util.Map[String, String] = new java.util.TreeMap[String, String]()
  @BeanProperty var catalogPath: String = ""
  @BeanProperty var stateManifestPath: String = ""
  @BeanProperty var infoSchemaDirectory: String = ""
  @BeanProperty var projectRoot: String = ""
  @BeanProperty var profilesDirectory: String = ""
  @BeanProperty var artifactDirectory: String = "target"
  @BeanProperty var manifestPath: String = ""
  @BeanProperty var target: String = ""
  @BeanProperty var previewLimit: Int = 10
  @BeanProperty var colors: Boolean = true
  @BeanProperty var notifications: Boolean = true
  @BeanProperty var selection: String = ""
  @BeanProperty var exclude: String = ""
  @BeanProperty var stateDirectory: String = ""
  @BeanProperty var defer: Boolean = false
  @BeanProperty var favorState: Boolean = false
  @BeanProperty var formatter: String = "sqlfmt"
  @BeanProperty var formatterExecutable: String = ""
  @BeanProperty var formatOnSave: Boolean = false

  def values: Vector[Any] = Vector(executable, executableArguments, environmentFiles, projectRoot, profilesDirectory, artifactDirectory,
    manifestPath, target, previewLimit, colors, notifications, selection, exclude, stateDirectory, defer, favorState, formatter,
    formatterExecutable, formatOnSave, loadEnvironmentFiles, engine, dbtVersion, profilesFile, adapter, variables,
    environmentVariables, builtinSources, catalogPath, stateManifestPath, infoSchemaDirectory)

  def copy(): DbtRunOptions = XmlSerializerUtil.createCopy(this)
}

final class DbtSettingsState {
  @BeanProperty var projects: java.util.Map[String, DbtRunOptions] = new java.util.TreeMap[String, DbtRunOptions]()
  @BeanProperty var upstreamDepth: Int = 5
  @BeanProperty var downstreamDepth: Int = 5
  @BeanProperty var direction: String = "LR"
  @BeanProperty var pinnedFocus: String = ""

  def upstream: Int = depth(upstreamDepth)

  def downstream: Int = depth(downstreamDepth)

  private def depth(value: Int): Int = value.max(1).min(20)
}

@Service(Array(Service.Level.PROJECT))
@State(name = "DbtWorkspaceSettings", storages = Array(new Storage("dbt-workspace.xml")))
final class DbtProjectSettings(project: Project) extends SimpleModificationTracker with PersistentStateComponent[DbtSettingsState] {
  private var state = new DbtSettingsState

  override def getState: DbtSettingsState = state

  override def loadState(value: DbtSettingsState): Unit = {
    state = value;
    changed()
  }

  def changed(): Unit = {
    incModificationCount();
    project.getMessageBus.syncPublisher(DbtProjectSettings.Changed).settingsChanged()
  }

  def options(root: String): DbtRunOptions = synchronized {
    state.projects.computeIfAbsent(root, _ => new DbtRunOptions)
  }
}

private[dbt] object DbtProjectSettings {
  val Changed: Topic[DbtSettingsListener] = Topic.create("dbt settings changed", classOf[DbtSettingsListener])

  def get(project: Project): DbtProjectSettings = project.getService(classOf[DbtProjectSettings])
}

private[dbt] trait DbtSettingsListener {
  def settingsChanged(): Unit
}

/** Environment variables declared by `.env` files and IntelliJ settings. */
private[dbt] object DbtEnvironment {
  private val Assignment = """\s*(?:export\s+)?([A-Za-z_][A-Za-z_0-9]*)\s*=\s*(.*)""".r
  private val DoubleQuoted = """"((?:\\.|[^"\\])*)"\s*(?:#.*)?""".r
  private val SingleQuoted = """'([^']*)'\s*(?:#.*)?""".r
  private val Escape = """\\([nrt"\\])""".r

  /** Single-line dotenv assignments; values are data, never shell expressions. */
  private[dbt] def parse(text: String): Map[String, String] = text.stripPrefix("\ufeff").linesIterator.flatMap {
    case Assignment(name, raw) =>
      val value = raw.trim match {
        case DoubleQuoted(value) => Some(Escape.replaceAllIn(value, m => Matcher.quoteReplacement(m.group(1) match {
          case "n" => "\n"
          case "r" => "\r"
          case "t" => "\t"
          case other => other
        })))
        case SingleQuoted(value) => Some(value)
        case value if !value.startsWith("\"") && !value.startsWith("'") =>
          Some(if (value.startsWith("#")) "" else value.replaceFirst("\\s+#.*$", "").trim)
        case _ => None
      }
      value.map(name -> _)
    case _ => None
  }.toMap

  def values(r: DbtRoot): Map[String, String] = {
    val files = r.envFiles.foldLeft(Map.empty[String, String])((values, file) => values ++ parse(file.getText))
    files ++ r.runOptions.environmentVariables.asScala
  }

  private def files(r: DbtRoot, namePattern: String): Vector[DbtSymbol] = r.envFiles.flatMap { file =>
    s"(?m)^\\s*(?:export\\s+)?($namePattern)\\s*=".r.findAllMatchIn(file.getText)
      .map(m => DbtSymbol("environment", m.group(1), r.name, file, new TextRange(m.start(1), m.end(1))))
  }

  /** IntelliJ overrides take precedence over `.env` files. */
  def apply(w: DbtWorkspace, r: DbtRoot, name: String): Vector[DbtSymbol] = {
    val found = w.symbols.filter(s => s.kind == "environment" && s.name == name &&
      s.attributes.contains("settings") && w.owner(s).contains(r))
    if (found.nonEmpty) found else files(r, Pattern.quote(name))
  }

  def declarations(r: DbtRoot): Vector[DbtSymbol] =
    DbtSettingsDeclarations.names(r, "environment", r.runOptions.environmentVariables.keySet().asScala.toVector) ++
      files(r, "[A-Za-z_][A-Za-z_0-9]*")
}

/** Variable values are JSON so nested objects, lists, booleans and null retain their dbt types. */
private[dbt] object DbtVariableOverrides {
  def parse(text: String): Either[String, JsonObject] = {
    val message = "Variables must be a JSON object, for example {\"start_date\": \"2026-03-01\"}."
    if (text.trim.isEmpty) Right(new JsonObject)
    else Try(JsonParser.parseString(text)).toEither.left.map(_ => message)
      .flatMap(value => Either.cond(value.isJsonObject, value.getAsJsonObject, message))
  }
}

/** Read-only name lists let navigation and Find Usages identify settings without exposing their values. */
private[project] object DbtSettingsDeclarations {
  def names(root: DbtRoot, kind: String, names: Vector[String]): Vector[DbtSymbol] = {
    if (names.isEmpty) Vector.empty
    else {
      val header = "Configure these values in Settings → dbt Workspace.\n\n"
      val sorted = names.sorted
      val text = header + sorted.mkString("\n")
      val virtual = new LightVirtualFile(s"dbt.$kind.${DbtHash.sha256(root.directory.getPath).take(12)}.settings.txt",
        PlainTextFileType.INSTANCE, text)
      virtual.setWritable(false)
      DbtYaml.psiFile(root.config.getProject, virtual).toVector.flatMap { file =>
        var offset = header.length
        sorted.map { name =>
          val range = new TextRange(offset, offset + name.length)
          offset += name.length + 1
          DbtSymbol(kind, name, root.name, file, range, metadata = true,
            attributes = Map("resource-root" -> root.directory.getPath, "settings" -> "true"))
        }
      }
    }
  }
}

private[project] final class DbtEnvironmentSource extends DbtDeclarationSource {
  override def declarations(workspace: DbtWorkspace): Vector[DbtSymbol] = workspace.roots.flatMap { root =>
    DbtEnvironment.declarations(root) ++ DbtVariableOverrides.parse(root.runOptions.variables).toOption.toVector.flatMap(values =>
      DbtSettingsDeclarations.names(root, "var", values.keySet().asScala.toVector))
  }
}

/** The profiles directory used to run dbt and the default target of the project's profile. */
private[dbt] final case class DbtProfileContext(directory: Path, defaultTarget: Option[String])

private[dbt] object DbtExecutionContext {
  def profiles(root: DbtRoot): DbtProfileContext = {
    val environment = sys.env ++ DbtEnvironment.values(root)
    val directory = Option(root.runOptions.profilesDirectory).filter(_.nonEmpty).map(Paths.get(_))
      .orElse(environment.get("DBT_PROFILES_DIR").filter(_.nonEmpty).map(Paths.get(_)))
      .map(p => if (p.isAbsolute) p else root.executionDirectory.resolve(p))
      .getOrElse(if (root.psiFile(root.executionDirectory.resolve("profiles.yml").toString).nonEmpty) root.executionDirectory
      else Paths.get(System.getProperty("user.home"), ".dbt"))
    val config = root.yaml(root.executionDirectory.resolve("dbt_project.yml").toString).getOrElse(root.config)
    val profile = DbtYaml.string(config, "profile")
      .flatMap(name => root.yaml(directory.resolve("profiles.yml").toString).flatMap(DbtYaml.map(_, name)))
    DbtProfileContext(directory, profile.flatMap(DbtYaml.string(_, "target")).filterNot(_.contains("{{")))
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

/** Lowercase hexadecimal SHA-256 digests; text is encoded as UTF-8. */
private[dbt] object DbtHash {
  def sha256(bytes: Array[Byte]): String =
    java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes))

  def sha256(text: String): String = sha256(text.getBytes(StandardCharsets.UTF_8))
}

/** Provenance binds the manifest bytes to the execution configuration captured before launching dbt. */
private[dbt] object DbtArtifactContext {
  private val Marker = ".dbt-workspace-context"

  private def textDigest(text: String): String = DbtHash.sha256(text.stripPrefix("\ufeff").replace("\r\n", "\n"))

  private def digest(path: Path): String = textDigest(Files.readString(path, StandardCharsets.UTF_8))

  def fingerprint(directory: String): Option[String] = Try(digest(Paths.get(directory).resolve("manifest.json"))).toOption

  def identity(root: DbtRoot): Vector[String] = {
    val directory = root.executionDirectory
    val profiles = DbtExecutionContext.profiles(root).directory.normalize()
    val inputs = Vector(profiles.resolve("profiles.yml").toString, directory.resolve("dbt_project.yml").toString,
      directory.resolve("vars.yml").toString)
    val state = root.runOptions.stateDirectory.trim
    val baseline = if (state.isEmpty) Vector.empty else {
      val path = directory.resolve(state).resolve("manifest.json").normalize().toString
      Vector(path, root.psiFile(path).map(_.getModificationStamp.toString).getOrElse("unavailable"))
    }
    val configuration = inputs.flatMap(path => Vector(path, root.psiFile(path).map(_.getText).getOrElse(""))) ++
      root.envFiles.flatMap(file => Vector(file.getVirtualFile.getPath, file.getText)) ++
      Vector(root.runOptions.executable, root.runOptions.executableArguments, root.runOptions.variables,
        root.runOptions.environmentVariables.toString, state, root.runOptions.defer.toString,
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
