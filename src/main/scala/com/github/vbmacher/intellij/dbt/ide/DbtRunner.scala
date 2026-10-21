package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project._
import com.github.vbmacher.intellij.dbt.lineage.DbtSelection
import com.google.gson.JsonParser
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.execution.process.{AnsiEscapeDecoder, KillableProcessHandler, ProcessEvent, ProcessListener, ProcessOutputTypes}
import com.intellij.notification.{NotificationGroupManager, NotificationType}
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.{ApplicationManager, ModalityState}
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.{Disposer, Key}
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.SystemNotifications
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.execution.ParametersListUtil
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.util.Try
import scala.util.control.NonFatal

private[ide] sealed abstract class DbtCommandAction(val label: String, val command: Vector[String],
  val usesState: Boolean = true, val structured: Boolean = false) extends Product with Serializable

private[ide] object DbtCommandAction {
  case object Run extends DbtCommandAction("Run", Vector("run"), structured = true)
  case object Build extends DbtCommandAction("Build", Vector("build"), structured = true)
  case object Test extends DbtCommandAction("Test", Vector("test"), structured = true)
  case object Compile extends DbtCommandAction("Compile", Vector("compile"))
  case object Preview extends DbtCommandAction("Preview", Vector("show"))
  case object GenerateDocs extends DbtCommandAction("Generate Docs", Vector("docs", "generate"), usesState = false)
  case object SourceFreshness extends DbtCommandAction("Source Freshness", Vector("source", "freshness"), usesState = false, structured = true)
  case object ResolveSelection extends DbtCommandAction("Resolve Selection", Vector("ls", "--quiet", "--output", "json"), usesState = false)

  lazy val toolbar: Vector[DbtCommandAction] = Vector(Run, Build, Test, Compile, Preview, GenerateDocs, SourceFreshness)
  def fromLabel(label: String): Option[DbtCommandAction] = (toolbar :+ ResolveSelection).find(_.label == label)
}

private[ide] sealed abstract class DbtCommandScope
private[ide] object DbtCommandScope {
  case object All extends DbtCommandScope
  final case class Selected(terms: Vector[String]) extends DbtCommandScope
  final case class Named(name: String) extends DbtCommandScope
  final case class Inline(sql: String) extends DbtCommandScope
}

/** Resolved command inputs. Rendering never reads settings or rewrites an existing argument vector. */
private[ide] final case class DbtCommandRequest(action: DbtCommandAction, scope: DbtCommandScope = DbtCommandScope.All,
  exclude: Vector[String] = Vector.empty, fullRefresh: Boolean = false, limit: Int = 10,
  state: Option[String] = None, defer: Boolean = false, favorState: Boolean = false,
  indirectSelection: Option[String] = None, debugSql: Boolean = false, extraArguments: Vector[String] = Vector.empty) {
  def arguments: Vector[String] = {
    val selection = scope match {
      case DbtCommandScope.All => Vector.empty
      case DbtCommandScope.Selected(terms) => Vector("--select") ++ terms
      case DbtCommandScope.Named(name) => Vector("--selector", name)
      case DbtCommandScope.Inline(sql) => Vector("--inline", sql)
    }
    action.command ++ (if (action.structured) Vector("--log-format", "json") else Vector.empty) ++ selection ++
      (if (exclude.nonEmpty) Vector("--exclude") ++ exclude else Vector.empty) ++
      (if (fullRefresh) Vector("--full-refresh") else Vector.empty) ++
      (if (action == DbtCommandAction.Preview) Vector("--limit", DbtCommandRequest.limit(limit).toString, "--output", "json") else Vector.empty) ++
      (if (debugSql) Vector("--log-format", "json", "--log-level", "debug") else Vector.empty) ++
      state.toVector.flatMap(path => Vector("--state", path)) ++
      (if (defer) Vector("--defer") else Vector.empty) ++ (if (favorState) Vector("--favor-state") else Vector.empty) ++
      indirectSelection.toVector.flatMap(value => Vector("--indirect-selection", value)) ++ extraArguments
  }
}
private[ide] object DbtCommandRequest {
  /** Preview row limits are bounded to 1–1000 wherever they are requested, rendered or displayed. */
  def limit(value: Int): Int = value.max(1).min(1000)
}

/** The executable prefix is captured separately from the dbt request, including wrapper arguments. */
private[ide] final case class DbtCommand(prefix: Vector[String], request: DbtCommandRequest, directory: String,
  environment: Map[String, String], target: String, notifySystem: Boolean, artifactDirectory: String,
  artifactContext: Vector[String], colors: Boolean = true, root: String = "", sourceSql: String = "",
  previewSource: Option[DbtPreviewSource] = None) {
  def action: String = request.action.label
  lazy val arguments: Vector[String] = prefix ++ request.arguments ++
    (if (target.nonEmpty) Vector("--target", target) else Vector.empty) :+ (if (colors) "--use-colors" else "--no-use-colors")
  def commandLine: String = ParametersListUtil.join(arguments.asJava)
  def executionRoot: String = Option(root).filter(_.nonEmpty).getOrElse(artifactContext.headOption.getOrElse(directory))
}

private[ide] object DbtCommand {
  val Actions: Vector[String] = DbtCommandAction.toolbar.map(_.label)
  private def wholeProject(w: DbtWorkspace, root: DbtRoot): Either[String, DbtRunSelection] =
    DbtRunSelection.all(w, root.directory.getPath).toRight("The execution root is unavailable.")
  def selection(w: DbtWorkspace, root: DbtRoot, action: String, fullRefresh: Boolean,
    requested: Option[DbtRunSelection] = None, cliIds: Option[Vector[String]] = None): Either[String, DbtCommand] = {
    val options = root.runOptions
    if (Set("Generate Docs", "Source Freshness")(action))
      return wholeProject(w, root).flatMap(create(w, _, action, fullRefresh = false, Some(root.runOptions)))
    val expression = DbtSelectionReview.expression(w, root, requested)
    val local = DbtSelection.resolve(w, root, expression, options.exclude)
    val cliMatches = cliIds.map(ids => DbtSelectionReview.knownCliResources(w, root).filter(s => ids.contains(s.id)))
    val resolved = cliIds.fold(local) { ids =>
      val matches = cliMatches.toVector.flatten
      DbtSelection(matches.flatMap(_.symbol),
        ids.filterNot(id => matches.exists(_.id == id)).map(id => "CLI resource is unavailable locally: " + id))
    }
    val stateSelection = (expression + " " + options.exclude).split("[\\s,]+").exists(_.replaceFirst("^[0-9]*\\+", "").startsWith("state:"))
    val state = if (options.defer || options.favorState || stateSelection || options.stateDirectory.nonEmpty) DbtState.manifest(root).map(_ => ()) else Right(())
    for {
      _ <- state
      _ <- Either.cond(!options.favorState || options.defer, (), "Favor state requires deferral.")
      _ <- Either.cond(!options.favorState || root.engine == "core" && root.supports(4), (), "Favor state is supported only for configured dbt Core 1.4+.")
      _ <- Either.cond(resolved.complete || stateSelection && resolved.problems.forall(_.contains("state:")), (), resolved.problems.mkString("\n"))
      _ <- Either.cond(stateSelection || cliMatches.fold(resolved.resources.nonEmpty)(_.nonEmpty), (), "Selection matches no resources.")
      _ <- Either.cond(action != "Preview", (), "Use a model or CTE for Preview.")
      everything <- wholeProject(w, root)
      incremental = stateSelection || resolved.resources.exists(s => s.kind == "model" &&
        DbtResourceData.get(w, s).attributes.get("materialized").contains("incremental"))
      base <- create(w, everything, action, fullRefresh && incremental, Some(root.runOptions))
      excluded <- DbtSelectionReview.cliTerms(w, root, options.exclude)
    } yield {
      val scope = if (expression.isEmpty && cliIds.isEmpty) DbtCommandScope.All
      else if (stateSelection && cliIds.isEmpty) DbtCommandScope.Selected(expression.split("\\s+").filter(_.nonEmpty).toVector)
      else DbtCommandScope.Selected(cliMatches.map(_.map(_.selector)).getOrElse(
        resolved.resources.map(DbtSelection.selector(w, _))).distinct)
      // Preserve explicit exclusions as well: dbt may otherwise select their tests indirectly.
      base.copy(request = base.request.copy(scope = scope, exclude = excluded))
    }
  }
  /** Explicit CLI resolution shares the normal managed process and execution context. */
  def resolveSelection(w: DbtWorkspace, root: DbtRoot, requested: Option[DbtRunSelection]): Either[String, DbtCommand] = {
    val expression = DbtSelectionReview.expression(w, root, requested)
    val exclude = root.runOptions.exclude.trim
    for {
      everything <- wholeProject(w, root)
      base <- prepare(w, everything, DbtCommandAction.ResolveSelection, fullRefresh = false, Some(root.runOptions))
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
              limit = DbtCommandRequest.limit(options.previewLimit), state = baseline,
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
  @volatile private var handler: KillableProcessHandler = _
  private val listeners = new CopyOnWriteArrayList[DbtRunnerEvent => Unit]()
  def isRunning: Boolean = busy.get()
  def listen(parent: Disposable, listener: DbtRunnerEvent => Unit): Unit = {
    listeners.add(listener)
    Disposer.register(parent, new Disposable { override def dispose(): Unit = { listeners.remove(listener); () } })
  }
  private def fire(event: DbtRunnerEvent): Unit = listeners.asScala.foreach(_(event))

  private[ide] def start(command: DbtCommand, outputSink: (String, Key[_]) => Unit = (_, _) => ()): Boolean = {
    if (disposed || !busy.compareAndSet(false, true)) false
    else {
      val modality = ModalityState.defaultModalityState()
      def onUi(action: => Unit): Unit = ApplicationManager.getApplication.invokeLater(() => if (!disposed && !project.isDisposed) action, modality)
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
          val process = new KillableProcessHandler(DbtToolCommand(command.arguments, command.directory, command.environment).commandLine)
          process.setShouldKillProcessSoftly(false)
          val display = new DbtConsoleOutput(outputSink)
          handler = process
          val output = new StringBuilder
          process.addProcessListener(new ProcessListener {
            override def onTextAvailable(event: ProcessEvent, outputType: Key[_]): Unit = {
              output.synchronized { if (output.length < 10000000) output.append(event.getText) }
              display.textAvailable(event.getText, outputType)
              evidence.stream(event.getText)
              evidence.active.foreach(run => onUi { fire(DbtRunnerEvent(running = true, s"${command.action} running", execution = Some(run))) })
            }
            override def processTerminated(event: ProcessEvent): Unit = {
              display.finish()
              val wasCancelled = cancelled.get()
              val success = event.getExitCode == 0 && !wasCancelled
              val message = if (wasCancelled) s"${command.action} cancelled" else s"${command.action} finished (exit ${event.getExitCode})"
              val capturedOutput = output.synchronized(output.toString)
              val isPreview = command.action == "Preview"
              val previewEvidence = Option.when(isPreview)(
                DbtPreviewEvidence.capture(command, capturedOutput, failed = !success, canceled = wasCancelled))
              val preview = if (success && isPreview) Some(DbtPreview.format(capturedOutput)) else None
              val result = if (success && isPreview) DbtQueryResult.parse(capturedOutput,
                command.target, DbtCommandRequest.limit(command.request.limit), (System.nanoTime() - started) / 1000000L,
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
                DaemonCodeAnalyzer.getInstance(project).restart("dbt execution results changed")
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

/** Formats the display stream; execution evidence and query results use the original output. */
private[ide] final class DbtConsoleOutput(sink: (String, Key[_]) => Unit) {
  private val decoder = new AnsiEscapeDecoder
  private val acceptor = new AnsiEscapeDecoder.ColoredTextAcceptor {
    override def coloredTextAvailable(text: String, attributes: Key[_]): Unit = sink(text, attributes)
  }
  private val pending = mutable.LinkedHashMap.empty[Key[_], StringBuilder]
  private val continuing = mutable.Set.empty[Key[_]]
  private val time = "(?:T|^)([0-9]{2}:[0-9]{2}:[0-9]{2})".r

  def textAvailable(text: String, kind: Key[_]): Unit = synchronized {
    if (kind == ProcessOutputTypes.SYSTEM) emit(text, kind)
    else {
      val buffer = pending.getOrElseUpdate(kind, new StringBuilder)
      var start = 0
      while (start < text.length) {
        val end = text.indexOf('\n', start)
        val chunk = text.substring(start, if (end < 0) text.length else end + 1)
        if (buffer.nonEmpty || !continuing(kind) && chunk.trim.startsWith("{")) {
          buffer.append(chunk)
          if (end >= 0) {
            emit(format(buffer.toString), kind)
            buffer.clear()
          } else if (buffer.length > 1000000) {
            emit(buffer.toString, kind)
            buffer.clear()
          }
        } else emit(chunk, kind)
        if (end >= 0) continuing -= kind else if (chunk.trim.nonEmpty) continuing += kind
        start = if (end < 0) text.length else end + 1
      }
    }
  }

  def finish(): Unit = synchronized {
    pending.foreach { case (kind, buffer) =>
      if (buffer.nonEmpty) emit(format(buffer.toString), kind)
    }
    pending.clear()
    continuing.clear()
  }

  private def emit(text: String, kind: Key[_]): Unit = decoder.escapeText(text, kind, acceptor)

  private def format(line: String): String = {
    val message = Try(JsonParser.parseString(line)).toOption.filter(_.isJsonObject).flatMap { element =>
      val obj = element.getAsJsonObject
      for {
        _ <- Option(obj.get("data")).filter(_.isJsonObject)
        info <- DbtExecutionEvidence.child(obj, "info")
        text <- DbtExecutionEvidence.text(info, "msg")
      } yield {
        val timestamp = DbtExecutionEvidence.text(info, "ts").flatMap(value => time.findFirstMatchIn(value).map(_.group(1)))
          .map(_ + "  ").getOrElse("")
        val level = DbtExecutionEvidence.text(info, "level").map(_.toUpperCase(java.util.Locale.ROOT))
          .filterNot(_ == "INFO").map(value => s"[$value] ").getOrElse("")
        if (text.isEmpty) "\n" else timestamp + level + text.stripSuffix("\n") + "\n"
      }
    }
    message.getOrElse(line)
  }
}
