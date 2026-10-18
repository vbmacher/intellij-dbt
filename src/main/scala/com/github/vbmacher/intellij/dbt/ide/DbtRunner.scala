package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project._
import com.google.gson.{JsonArray, JsonElement, JsonParser}
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.{ColoredProcessHandler, ProcessEvent, ProcessListener}
import com.intellij.execution.ui.{ConsoleView, ConsoleViewContentType}
import com.intellij.notification.{NotificationGroupManager, NotificationType}
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.util.execution.ParametersListUtil
import com.intellij.ui.SystemNotifications
import com.intellij.util.concurrency.AppExecutorUtil
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import scala.jdk.CollectionConverters._
import scala.collection.mutable.ArrayBuffer
import scala.util.Try
import scala.util.control.NonFatal

private[ide] final case class DbtCommand(arguments: Vector[String], directory: String, environment: Map[String, String],
  action: String, target: String, notifySystem: Boolean, artifactDirectory: String, artifactContext: Vector[String])

private[ide] object DbtCommand {
  val Actions: Vector[String] = Vector("Run", "Build", "Test", "Compile", "Preview", "Generate Docs", "Source Freshness")
  def selection(w: DbtWorkspace, root: DbtRoot, action: String, fullRefresh: Boolean): Either[String, DbtCommand] = {
    val options = root.runOptions
    val expression = options.selection.trim
    val resolved = com.github.vbmacher.intellij.dbt.lineage.DbtSelection.resolve(w, root, expression, options.exclude)
    val stateSelection = expression.split("\\s+").exists(_.replaceFirst("^[0-9]*\\+", "").startsWith("state:"))
    val state = if (options.defer || options.favorState || stateSelection || options.stateDirectory.nonEmpty) DbtState.manifest(root).map(_ => ()) else Right(())
    for {
      _ <- state
      _ <- Either.cond(!options.favorState || options.defer, (), "Favor state requires deferral.")
      _ <- Either.cond(!options.favorState || root.engine == "core" && root.supports(4), (), "Favor state is supported only for configured dbt Core 1.4+.")
      _ <- Either.cond(resolved.complete || stateSelection && resolved.problems.forall(_.contains("state:")), (), resolved.problems.mkString("\n"))
      _ <- Either.cond(stateSelection || resolved.resources.nonEmpty, (), "Selection matches no resources.")
      _ <- Either.cond(action != "Preview", (), "Use a model or CTE for Preview.")
      all <- DbtRunSelection.all(w, root.directory.getPath).toRight("The execution root is unavailable.")
      base <- create(w, all, action, fullRefresh)
    } yield {
      val selections = if (action == "Generate Docs" || expression.isEmpty) Vector.empty
      else if (stateSelection) Vector("--select") ++ expression.split("\\s+").filter(_.nonEmpty)
      else Vector("--select") ++ resolved.resources.map(com.github.vbmacher.intellij.dbt.lineage.DbtSelection.selector(w, _)).distinct
      // Preserve explicit exclusions as well: dbt may otherwise select their tests indirectly.
      val excludes = if (action == "Generate Docs" || options.exclude.trim.isEmpty) Vector.empty
      else Vector("--exclude") ++ options.exclude.trim.split("\\s+")
      base.copy(arguments = base.arguments ++ selections ++ excludes)
    }
  }
  def create(w: DbtWorkspace, requested: DbtRunSelection, action: String, fullRefresh: Boolean): Either[String, DbtCommand] = {
    val selected = if (requested.path.isEmpty && requested.directory)
      DbtRunSelection.all(w, requested.root).filter(_.root == requested.root)
    else DbtRunSelection.available(w).find(s => s.path == requested.path && s.root == requested.root && s.directory == requested.directory)
    for {
      selection <- selected.toRight("Choose an available model or directory in the Runner.")
      owner <- DbtRunSelection.owner(w, selection).toRight("The selected dbt project is no longer available.")
      command <- {
        val root = w.executionRoot(owner)
        val options = root.runOptions
        val directory = root.executionDirectory
        val target = DbtExecutionContext.activeTarget(root).getOrElse("")
        val all = selection.directory && (selection.path.isEmpty || selection.path == root.directory.getPath)
        val selectors = if (all) Vector.empty else DbtRunSelection.selectors(w, selection)
        val stateFlags = if (Set("Generate Docs", "Source Freshness")(action)) Right(Vector.empty[String]) else for {
          _ <- Either.cond(!options.defer || root.engine == "core", (), "Core deferral flags are unavailable for the configured engine.")
          _ <- Either.cond(!options.favorState || options.defer && root.engine == "core" && root.supports(4), (), "Favor state requires deferral and dbt Core 1.4+.")
          directory <- if (options.stateDirectory.nonEmpty || options.defer || options.favorState)
            DbtState.manifest(root).flatMap(_ => DbtState.baseline(root)).map(Some(_)) else Right(None)
        } yield directory.toVector.flatMap(path => Vector("--state", path.toString)) ++
          (if (options.defer) Vector("--defer") else Vector.empty) ++ (if (options.favorState) Vector("--favor-state") else Vector.empty)
        if (!Actions.contains(action)) Left("Unknown dbt command.")
        else if (options.projectRoot.nonEmpty && !Files.isRegularFile(directory.resolve("dbt_project.yml")))
          Left("Project root must contain dbt_project.yml. Check dbt Workspace settings.")
        else if (action == "Preview" && selection.directory) Left("Choose a single model to preview.")
        else if (action != "Generate Docs" && !all && selectors.isEmpty) Left("The selected directory contains no enabled dbt models.")
        else {
          val prefix = Vector(DbtExecutionContext.executable(root)) ++ ParametersListUtil.parse(options.executableArguments, false, true).asScala
          val command = if (action == "Generate Docs") Vector("docs", "generate") else Vector(if (action == "Preview") "show" else action.toLowerCase)
          val select = if (action == "Generate Docs" || all) Vector.empty else Vector("--select") ++ selectors
          val incremental = DbtRunSelection.selectedModels(w, selection).exists(s =>
            DbtResourceData.get(w, s).attributes.get("materialized").contains("incremental"))
          val refresh = if (fullRefresh && incremental && Set("Run", "Build")(action)) Vector("--full-refresh") else Vector.empty
          val preview = if (action == "Preview") Vector("--limit", options.previewLimit.max(1).min(1000).toString, "--output", "json") else Vector.empty
          val profiles = DbtExecutionContext.profiles(root).directory.toString
          val artifact = root.artifactDirectory
          stateFlags.map(flags => DbtCommand(prefix ++ command ++ structured ++ select ++ refresh ++ preview ++ flags ++
            (if (target.nonEmpty) Vector("--target", target) else Vector.empty) :+ (if (options.colors) "--use-colors" else "--no-use-colors"),
            directory.toString, DbtExecutionContext.environment(root) ++ Map("DBT_PROFILES_DIR" -> profiles, "DBT_TARGET_PATH" -> artifact.toString),
            action, target, options.notifications, artifact.toString, DbtArtifactContext.identity(root)))
        }
      }
    } yield command
  }
}

private[ide] final case class DbtRunnerEvent(running: Boolean, message: String, refresh: Boolean = false, preview: Option[String] = None)

@Service(Array(Service.Level.PROJECT))
final class DbtRunner(project: Project) extends Disposable {
  private val busy = new AtomicBoolean(false)
  private val cancelled = new AtomicBoolean(false)
  @volatile private var disposed = false
  @volatile private var handler: ColoredProcessHandler = _
  private val listeners = new CopyOnWriteArrayList[DbtRunnerEvent => Unit]()
  lazy val console: ConsoleView = {
    val builder = TextConsoleBuilderFactory.getInstance().createBuilder(project)
    builder.setViewer(true)
    val view = builder.getConsole
    Disposer.register(this, view); view
  }
  def isRunning: Boolean = busy.get()
  def listen(parent: Disposable, listener: DbtRunnerEvent => Unit): Unit = {
    listeners.add(listener)
    Disposer.register(parent, new Disposable { override def dispose(): Unit = { listeners.remove(listener); () } })
  }
  private def onUi(action: => Unit): Unit = ApplicationManager.getApplication.invokeLater(() => if (!disposed && !project.isDisposed) action)
  private def fire(event: DbtRunnerEvent): Unit = listeners.asScala.foreach(_(event))

  private[ide] def start(command: DbtCommand): Boolean = {
    if (disposed || !busy.compareAndSet(false, true)) false
    else {
      cancelled.set(false)
      console.print(command.arguments.mkString(" ") + "\n", ConsoleViewContentType.SYSTEM_OUTPUT)
      fire(DbtRunnerEvent(running = true, s"${command.action} running"))
      AppExecutorUtil.getAppExecutorService.execute(() => {
        if (disposed || cancelled.get()) onUi {
          busy.set(false); fire(DbtRunnerEvent(running = false, s"${command.action} cancelled"))
        }
        else try {
          val manifestBefore = DbtArtifactContext.fingerprint(command.artifactDirectory)
          val line = new GeneralCommandLine(command.arguments.asJava).withWorkDirectory(command.directory)
            .withEnvironment(command.environment.asJava).withCharset(StandardCharsets.UTF_8)
          val process = new ColoredProcessHandler(line)
          handler = process
          val output = new StringBuilder
          process.addProcessListener(new ProcessListener {
            override def onTextAvailable(event: ProcessEvent, outputType: com.intellij.openapi.util.Key[_]): Unit =
              output.synchronized { if (output.length < 10000000) output.append(event.getText) }
            override def processTerminated(event: ProcessEvent): Unit = {
              val wasCancelled = cancelled.get()
              val success = event.getExitCode == 0 && !wasCancelled
              val message = if (wasCancelled) s"${command.action} cancelled" else s"${command.action} finished (exit ${event.getExitCode})"
              val preview = if (success && command.action == "Preview") Some(DbtPreview.format(output.synchronized(output.toString))) else None
              val refresh = success && Set("Run", "Build", "Compile", "Generate Docs")(command.action)
              if (refresh) {
                if (DbtArtifactContext.fingerprint(command.artifactDirectory) != manifestBefore)
                  Try(DbtArtifactContext.record(command.artifactDirectory, command.artifactContext))
                LocalFileSystem.getInstance.refreshIoFiles(java.util.List.of(new java.io.File(command.artifactDirectory)), true, true, null)
              }
              onUi {
                handler = null; busy.set(false)
                console.print("\n" + message + "\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                fire(DbtRunnerEvent(running = false, message, refresh, preview))
                DbtNotifications.publish(project, command, message, success, wasCancelled)
              }
            }
          })
          onUi {
            console.attachToProcess(process)
            process.startNotify()
            if (cancelled.get()) process.destroyProcess()
          }
          if (disposed) { process.startNotify(); process.destroyProcess() }
        } catch {
          case NonFatal(error) => onUi {
            busy.set(false); handler = null
            val message = "Cannot launch dbt: " + Option(error.getMessage).getOrElse(error.toString) + ". Check the executable in dbt Workspace settings."
            console.print(message + "\n", ConsoleViewContentType.ERROR_OUTPUT)
            fire(DbtRunnerEvent(running = false, message)); DbtNotifications.publish(project, command, message, success = false, wasCancelled = cancelled.get())
          }
        }
      })
      true
    }
  }
  def stop(): Unit = { cancelled.set(true); Option(handler).foreach(_.destroyProcess()) }
  override def dispose(): Unit = { disposed = true; stop(); listeners.clear() }
}

private[ide] object DbtNotifications {
  def publish(project: Project, command: DbtCommand, message: String, success: Boolean, wasCancelled: Boolean,
    system: SystemNotifications = SystemNotifications.getInstance()): Unit = {
    val kind = if (success || wasCancelled) NotificationType.INFORMATION else NotificationType.ERROR
    NotificationGroupManager.getInstance().getNotificationGroup("dbt Workspace").createNotification(message, kind).notify(project)
    if (command.notifySystem) system.notify("dbt Workspace", "dbt " + command.action, message)
  }
}

private[ide] object DbtPreview {
  private val ansi = "\u001b\\[[0-9;]*[A-Za-z]".r
  def format(output: String): String = {
    val plain = ansi.replaceAllIn(output, "")
    def rows(element: JsonElement): Option[JsonArray] = {
      if (element.isJsonArray) Some(element.getAsJsonArray)
      else if (element.isJsonObject) {
        val obj = element.getAsJsonObject
        Option(obj.get("preview")).flatMap(e => if (e.isJsonPrimitive && e.getAsJsonPrimitive.isString)
          Try(JsonParser.parseString(e.getAsString)).toOption.flatMap(rows) else rows(e))
          .orElse(Option(obj.get("data")).flatMap(rows))
          .orElse(Option(obj.get("show")).flatMap(rows))
      } else None
    }
    // dbt's ordinary log format prints a multiline {"show": [...]} object among human-readable messages.
    // Extract balanced JSON values without assuming one log record per line or rewriting string contents.
    val values = ArrayBuffer.empty[String]
    var stack = List.empty[(Char, Int)]
    var quoted = false
    var escaped = false
    plain.indices.foreach { i =>
      val c = plain(i)
      if (quoted) {
        if (escaped) escaped = false
        else if (c == '\\') escaped = true
        else if (c == '"') quoted = false
      } else if (c == '"' && stack.nonEmpty) quoted = true
      else if (c == '{' || c == '[') stack = (c -> i) :: stack
      else if (c == '}' || c == ']') stack match {
        case (open, start) :: rest if open == (if (c == '}') '{' else '[') => values += plain.substring(start, i + 1); stack = rest
        case _ => stack = Nil
      }
    }
    val candidates = Vector(plain) ++ values
    val parsed = candidates.iterator.flatMap(s => Try(JsonParser.parseString(s.trim)).toOption.flatMap(rows)).toVector.lastOption
    parsed match {
      case Some(array) if array.isEmpty => "No data returned."
      case Some(array) =>
        val objects = array.asScala.filter(_.isJsonObject).map(_.getAsJsonObject).toVector
        val headers = objects.flatMap(_.keySet().asScala).distinct
        if (headers.isEmpty) "No data returned."
        else {
          val values = objects.map(o => headers.map(h => Option(o.get(h)).filterNot(_.isJsonNull)
            .map(e => if (e.isJsonPrimitive) e.getAsString else e.toString).getOrElse("null").replace("\n", "\\n")))
          val widths = headers.indices.map(i => (headers(i).length +: values.map(_(i).length)).max)
          def row(xs: Seq[String]): String = xs.indices.map(i => xs(i).padTo(widths(i), ' ')).mkString(" | ")
          row(headers) + "\n" + widths.map(n => "-" * n).mkString("-+-") + "\n" + values.map(row).mkString("\n")
        }
      case _ => "Preview JSON was unavailable. See dbt output for details."
    }
  }
}
