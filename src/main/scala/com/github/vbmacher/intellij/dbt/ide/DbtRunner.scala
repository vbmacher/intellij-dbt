package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project._
import com.google.gson.{JsonArray, JsonElement, JsonParser}
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.{ColoredProcessHandler, ProcessEvent, ProcessListener}
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

/** The executable prefix is captured separately from the dbt request, including wrapper arguments. */
private[ide] final case class DbtCommand(prefix: Vector[String], request: DbtCommandRequest, directory: String,
  environment: Map[String, String], target: String, notifySystem: Boolean, artifactDirectory: String,
  artifactContext: Vector[String], colors: Boolean = true, root: String = "", sourceSql: String = "",
  previewSource: Option[DbtPreviewSource] = None) {
  def action: String = request.action.label
  lazy val arguments: Vector[String] = prefix ++ request.arguments ++
    (if (target.nonEmpty) Vector("--target", target) else Vector.empty) :+ (if (colors) "--use-colors" else "--no-use-colors")
}

private[ide] object DbtCommand {
  val Actions: Vector[String] = DbtCommandAction.toolbar.map(_.label)
  def selection(w: DbtWorkspace, root: DbtRoot, action: String, fullRefresh: Boolean,
    requested: Option[DbtRunSelection] = None, cliIds: Option[Vector[String]] = None): Either[String, DbtCommand] = {
    val options = root.runOptions
    if (Set("Generate Docs", "Source Freshness")(action))
      return DbtRunSelection.all(w, root.directory.getPath).toRight("The execution root is unavailable.")
        .flatMap(create(w, _, action, fullRefresh = false, Some(root.runOptions)))
    val expression = DbtSelectionReview.expression(w, root, requested)
    val local = com.github.vbmacher.intellij.dbt.lineage.DbtSelection.resolve(w, root, expression, options.exclude)
    val available = cliIds.map(_ => com.github.vbmacher.intellij.dbt.lineage.DbtSelection.resolve(w, root, "").resources)
    val resolved = cliIds.fold(local) { ids =>
      val resources = available.toVector.flatten.filter(s => ids.contains(s.uniqueId))
      com.github.vbmacher.intellij.dbt.lineage.DbtSelection(resources,
        ids.filterNot(id => resources.exists(_.uniqueId == id)).map(id => "CLI resource is unavailable locally: " + id))
    }
    val stateSelection = (expression + " " + options.exclude).split("[\\s,]+").exists(_.replaceFirst("^[0-9]*\\+", "").startsWith("state:"))
    val state = if (options.defer || options.favorState || stateSelection || options.stateDirectory.nonEmpty) DbtState.manifest(root).map(_ => ()) else Right(())
    for {
      _ <- state
      _ <- Either.cond(!options.favorState || options.defer, (), "Favor state requires deferral.")
      _ <- Either.cond(!options.favorState || root.engine == "core" && root.supports(4), (), "Favor state is supported only for configured dbt Core 1.4+.")
      _ <- Either.cond(resolved.complete || stateSelection && resolved.problems.forall(_.contains("state:")), (), resolved.problems.mkString("\n"))
      _ <- Either.cond(stateSelection || resolved.resources.nonEmpty, (), "Selection matches no resources.")
      _ <- Either.cond(action != "Preview", (), "Use a model or CTE for Preview.")
      all <- DbtRunSelection.all(w, root.directory.getPath).toRight("The execution root is unavailable.")
      incremental = stateSelection || resolved.resources.exists(s => s.kind == "model" &&
        DbtResourceData.get(w, s).attributes.get("materialized").contains("incremental"))
      base <- create(w, all, action, fullRefresh && incremental, Some(root.runOptions))
      excluded <- DbtSelectionReview.cliTerms(w, root, options.exclude)
    } yield {
      val scope = if (expression.isEmpty && cliIds.isEmpty) DbtCommandScope.All
      else if (stateSelection && cliIds.isEmpty) DbtCommandScope.Selected(expression.split("\\s+").filter(_.nonEmpty).toVector)
      else DbtCommandScope.Selected(resolved.resources.map(com.github.vbmacher.intellij.dbt.lineage.DbtSelection.selector(w, _)).distinct)
      // Preserve explicit exclusions as well: dbt may otherwise select their tests indirectly.
      base.copy(request = base.request.copy(scope = scope, exclude = excluded))
    }
  }
  /** Explicit CLI resolution shares the normal managed process and execution context. */
  def resolveSelection(w: DbtWorkspace, root: DbtRoot, requested: Option[DbtRunSelection]): Either[String, DbtCommand] = {
    val expression = DbtSelectionReview.expression(w, root, requested)
    val exclude = root.runOptions.exclude.trim
    for {
      all <- DbtRunSelection.all(w, root.directory.getPath).toRight("The execution root is unavailable.")
      base <- prepare(w, all, DbtCommandAction.ResolveSelection, fullRefresh = false, Some(root.runOptions))
      baseline <- if ((expression + " " + exclude).contains("state:")) DbtState.baseline(root).map(p => Some(p.toString))
        else Right(None)
      exclusions <- DbtSelectionReview.cliTerms(w, root, exclude)
      selected <- {
        val named = "^selector:([^+,\\s]+)$".r
        expression match {
          case "" => Right(DbtCommandScope.All)
          case named(name) => Right(DbtCommandScope.Named(name))
          case other => DbtSelectionReview.cliTerms(w, root, other).flatMap(terms =>
            Either.cond(terms.nonEmpty, DbtCommandScope.Selected(terms), "Selection matches no locally known resources."))
        }
      }
    } yield {
      base.copy(request = base.request.copy(scope = selected, exclude = exclusions, state = baseline))
    }
  }
  def create(w: DbtWorkspace, requested: DbtRunSelection, action: String, fullRefresh: Boolean, options: Option[DbtRunOptions] = None): Either[String, DbtCommand] =
    DbtCommandAction.fromLabel(action).toRight("Unknown dbt command.").flatMap(prepare(w, requested, _, fullRefresh, options))

  private def prepare(w: DbtWorkspace, requested: DbtRunSelection, action: DbtCommandAction, fullRefresh: Boolean, executionOptions: Option[DbtRunOptions]): Either[String, DbtCommand] = {
    val selected = if (requested.path.isEmpty && requested.directory)
      DbtRunSelection.all(w, requested.root).filter(_.root == requested.root)
    else DbtRunSelection.available(w).find(s => s.path == requested.path && s.root == requested.root && s.directory == requested.directory)
    for {
      selection <- selected.toRight("Choose an available model or directory in the dbt run configuration.")
      owner <- DbtRunSelection.owner(w, selection).toRight("The selected dbt project is no longer available.")
      command <- {
        val original = w.executionRoot(owner)
        val root = executionOptions.fold(original)(original.withRunOptions)
        val options = root.runOptions
        val directory = root.executionDirectory
        val target = DbtExecutionContext.activeTarget(root).getOrElse("")
        val all = selection.directory && (selection.path.isEmpty || selection.path == root.directory.getPath)
        val selectors = if (all) Vector.empty else DbtRunSelection.selectors(w, selection)
        val state = if (!action.usesState) Right(None) else for {
          _ <- Either.cond(!options.defer || root.engine == "core", (), "Core deferral flags are unavailable for the configured engine.")
          _ <- Either.cond(!options.favorState || options.defer && root.engine == "core" && root.supports(4), (), "Favor state requires deferral and dbt Core 1.4+.")
          directory <- if (options.stateDirectory.nonEmpty || options.defer || options.favorState)
            DbtState.manifest(root).flatMap(_ => DbtState.baseline(root)).map(p => Some(p.toString)) else Right(None)
        } yield directory
        if (options.projectRoot.nonEmpty && !Files.isRegularFile(directory.resolve("dbt_project.yml")))
          Left("Project root must contain dbt_project.yml. Check dbt Workspace settings.")
        else if (action == DbtCommandAction.SourceFreshness && !all) Left("Choose the whole dbt project to check source freshness.")
        else if (action == DbtCommandAction.Preview && selection.directory) Left("Choose a single model to preview.")
        else if (action != DbtCommandAction.GenerateDocs && !all && selectors.isEmpty) Left("The selected directory contains no enabled dbt models.")
        else {
          val prefix = Vector(DbtExecutionContext.executable(root)) ++ ParametersListUtil.parse(options.executableArguments, false, true).asScala
          val incremental = DbtRunSelection.selectedModels(w, selection).exists(s =>
            DbtResourceData.get(w, s).attributes.get("materialized").contains("incremental"))
          val profiles = DbtExecutionContext.profiles(root).directory.toString
          val artifact = root.artifactDirectory
          state.map { baseline =>
            val request = DbtCommandRequest(action,
              if (action == DbtCommandAction.GenerateDocs || all) DbtCommandScope.All else DbtCommandScope.Selected(selectors),
              fullRefresh = fullRefresh && incremental && Set(DbtCommandAction.Run, DbtCommandAction.Build)(action),
              limit = options.previewLimit.max(1).min(1000), state = baseline,
              defer = action.usesState && options.defer, favorState = action.usesState && options.favorState)
            DbtCommand(prefix.toVector, request, directory.toString,
              DbtExecutionContext.environment(root) ++ Map("DBT_PROFILES_DIR" -> profiles, "DBT_TARGET_PATH" -> artifact.toString),
              target, options.notifications, artifact.toString, DbtArtifactContext.identity(root), options.colors, root.directory.getPath,
              if (action == DbtCommandAction.Preview) DbtRunSelection.selectedModels(w, selection).headOption.map(_.file.getText).getOrElse("") else "")
          }
        }
      }
    } yield command
  }
}

private[ide] final case class DbtRunnerEvent(running: Boolean, message: String, refresh: Boolean = false, preview: Option[String] = None,
  result: Option[DbtQueryResult] = None, execution: Option[DbtExecutionRun] = None, command: Option[DbtCommand] = None,
  previewEvidence: Option[DbtPreviewEvidence] = None, selectionOutput: Option[String] = None, exitCode: Option[Int] = None)

@Service(Array(Service.Level.PROJECT))
final class DbtRunner(project: Project) extends Disposable {
  private val evidence = project.getService(classOf[DbtExecutionEvidence])
  private val busy = new AtomicBoolean(false)
  private val cancelled = new AtomicBoolean(false)
  @volatile private var disposed = false
  @volatile private var handler: ColoredProcessHandler = _
  private val listeners = new CopyOnWriteArrayList[DbtRunnerEvent => Unit]()
  def isRunning: Boolean = busy.get()
  def listen(parent: Disposable, listener: DbtRunnerEvent => Unit): Unit = {
    listeners.add(listener)
    Disposer.register(parent, new Disposable { override def dispose(): Unit = { listeners.remove(listener); () } })
  }
  private def onUi(action: => Unit): Unit = ApplicationManager.getApplication.invokeLater(() => if (!disposed && !project.isDisposed) action, com.intellij.openapi.application.ModalityState.any())
  private def fire(event: DbtRunnerEvent): Unit = listeners.asScala.foreach(_(event))

  private[ide] def start(command: DbtCommand, outputSink: (String, com.intellij.openapi.util.Key[_]) => Unit = (_, _) => ()): Boolean = {
    if (disposed || !busy.compareAndSet(false, true)) false
    else {
      cancelled.set(false)
      evidence.begin(command)
      fire(DbtRunnerEvent(running = true, s"${command.action} running", execution = evidence.active, command = Some(command)))
      AppExecutorUtil.getAppExecutorService.execute(() => {
        if (disposed || cancelled.get()) onUi {
          busy.set(false); fire(DbtRunnerEvent(running = false, s"${command.action} cancelled", execution = evidence.finish(command, canceled = true), command = Some(command), exitCode = Some(130)))
        }
        else try {
          val manifestBefore = DbtArtifactContext.fingerprint(command.artifactDirectory)
          val started = System.nanoTime()
          val line = new GeneralCommandLine(command.arguments.asJava).withWorkDirectory(command.directory)
            .withEnvironment(command.environment.asJava).withCharset(StandardCharsets.UTF_8)
          val process = new ColoredProcessHandler(line)
          handler = process
          val output = new StringBuilder
          process.addProcessListener(new ProcessListener {
            override def onTextAvailable(event: ProcessEvent, outputType: com.intellij.openapi.util.Key[_]): Unit = {
              output.synchronized { if (output.length < 10000000) output.append(event.getText) }
              outputSink(event.getText, outputType)
              evidence.stream(event.getText)
              evidence.active.foreach(run => onUi { fire(DbtRunnerEvent(running = true, s"${command.action} running", execution = Some(run))) })
            }
            override def processTerminated(event: ProcessEvent): Unit = {
              val wasCancelled = cancelled.get()
              val success = event.getExitCode == 0 && !wasCancelled
              val message = if (wasCancelled) s"${command.action} cancelled" else s"${command.action} finished (exit ${event.getExitCode})"
              val capturedOutput = output.synchronized(output.toString)
              val previewEvidence = Option.when(command.action == "Preview")(
                DbtPreviewEvidence.capture(command, capturedOutput, failed = !success, canceled = wasCancelled))
              val preview = if (success && command.action == "Preview") Some(DbtPreview.format(capturedOutput)) else None
              val result = if (success && command.action == "Preview") DbtQueryResult.parse(capturedOutput,
                command.target, command.request.limit.max(1).min(1000), (System.nanoTime() - started) / 1000000L,
                previewEvidence.flatMap(e => e.adapterSql.orElse(Some(e.submitted))).getOrElse(command.sourceSql)).toOption else None
              val refresh = success && Set("Run", "Build", "Compile", "Generate Docs")(command.action)
              if (refresh) {
                if (DbtArtifactContext.fingerprint(command.artifactDirectory) != manifestBefore)
                  Try(DbtArtifactContext.record(command.artifactDirectory, command.artifactContext))
                LocalFileSystem.getInstance.refreshIoFiles(java.util.List.of(new java.io.File(command.artifactDirectory)), true, true, null)
              }
              val execution = evidence.finish(command, wasCancelled)
              val displayedPreview = if (preview.nonEmpty && result.isEmpty)
                Some("Malformed or unavailable dbt preview JSON. See raw process output.") else preview
              onUi {
                handler = null; busy.set(false)
                fire(DbtRunnerEvent(running = false, message, refresh, displayedPreview, result, execution, Some(command), previewEvidence,
                  Option.when(success && command.action == "Resolve Selection")(capturedOutput), Some(event.getExitCode)))
                com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(project).restart("dbt execution results changed")
                DbtNotifications.publish(project, command, message, success, wasCancelled)
              }
            }
          })
          onUi {
            process.startNotify()
            if (cancelled.get()) process.destroyProcess()
          }
          if (disposed) { process.startNotify(); process.destroyProcess() }
        } catch {
          case NonFatal(error) => onUi {
            busy.set(false); handler = null
            val message = "Cannot launch dbt: " + Option(error.getMessage).getOrElse(error.toString) + ". Check the executable in dbt Workspace settings."
            fire(DbtRunnerEvent(running = false, message, execution = evidence.finish(command, canceled = cancelled.get()), command = Some(command), exitCode = Some(1))); DbtNotifications.publish(project, command, message, success = false, wasCancelled = cancelled.get())
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
  def rows(output: String): Option[JsonArray] = {
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
    candidates.iterator.flatMap(s => Try(JsonParser.parseString(s.trim)).toOption.flatMap(rows)).toVector.lastOption
  }

  def format(output: String): String = {
    rows(output) match {
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
