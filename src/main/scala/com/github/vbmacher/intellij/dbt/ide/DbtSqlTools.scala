package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project._
import com.google.gson.{JsonObject, JsonParser}
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInspection.{LocalInspectionTool, LocalQuickFix, ProblemDescriptor, ProblemHighlightType, ProblemsHolder}
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.{CapturingProcessHandler, ProcessOutput}
import com.intellij.formatting.service.{AsyncDocumentFormattingService, AsyncFormattingRequest, FormattingService}
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.{FileDocumentManager, FileDocumentManagerListener}
import com.intellij.openapi.progress.{EmptyProgressIndicator, ProgressIndicator, ProgressManager}
import com.intellij.openapi.project.{Project, ProjectLocator}
import com.intellij.openapi.util.{Key, TextRange}
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.{PsiDocumentManager, PsiElement, PsiElementVisitor, PsiFile, PsiManager}
import com.intellij.util.ThrowableRunnable
import com.intellij.util.concurrency.AppExecutorUtil
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import java.util.concurrent.{CompletableFuture, TimeUnit}
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

/** Process launch specification shared by dbt and SQL tools: argv, working directory and environment, decoded as UTF-8. */
private[ide] final case class DbtToolCommand(arguments: Vector[String], directory: String, environment: Map[String, String]) {
  def commandLine: GeneralCommandLine = new GeneralCommandLine(arguments.asJava).withWorkDirectory(directory)
    .withEnvironment(environment.asJava).withCharset(StandardCharsets.UTF_8)
}
private[ide] object DbtToolCommand {
  def create(file: PsiFile, lint: Boolean = false): Either[String, DbtToolCommand] =
    DbtWorkspace.rootForFile(file.getProject, file.getVirtualFile)
      .filter(r => r.resource(file) && !r.excluded(file.getVirtualFile))
      .toRight("Open a SQL resource belonging to a configured dbt project.").flatMap { root =>
        val options = root.runOptions
        val configured = options.formatterExecutable.trim
        if (configured.isEmpty) Left("Configure a formatter executable in Settings > dbt Workspace.")
        else if (!Set("sqlfmt", "sqlfluff")(options.formatter)) Left("Choose sqlfmt or SQLFluff in Settings > dbt Workspace.")
        else if (lint && options.formatter != "sqlfluff") Left("SQL diagnostics require SQLFluff. Select SQLFluff in Settings > dbt Workspace.")
        else {
          val executable = if (configured.contains('/') || configured.contains('\\'))
            root.executionDirectory.resolve(configured).normalize().toString else configured
          val arguments = if (options.formatter == "sqlfluff") Vector(executable, if (lint) "lint" else "format",
            "--nocolor", "--disable-progress-bar", "--stdin-filename", file.getVirtualFile.getPath) ++
            (if (lint) Vector("--format", "json") else Vector.empty) ++ Vector("-")
          else Vector(executable, "-")
          Right(DbtToolCommand(arguments, root.executionDirectory.toString, DbtExecutionContext.environment(root)))
        }
      }
}

/** Only argv, cwd, inherited project environment and raw source stdin reach the configured executable. */
private[ide] final class DbtToolProcess {
  private val canceled = new AtomicBoolean(false)
  @volatile private var handler: Option[CapturingProcessHandler] = None
  def cancel(): Unit = { canceled.set(true); handler.foreach(_.destroyProcess()) }
  def run(command: DbtToolCommand, text: String, indicator: ProgressIndicator = new EmptyProgressIndicator,
    timeoutMs: Int = 60000): Either[String, ProcessOutput] = {
    if (canceled.get() || indicator.isCanceled) Left("Tool execution was canceled; source is unchanged.")
    else try {
      val process = new CapturingProcessHandler(command.commandLine)
      process.setShouldDestroyProcessRecursively(true)
      handler = Some(process)
      if (canceled.get()) process.destroyProcess()
      val inputError = new AtomicReference[Throwable]()
      // Feed stdin concurrently so cancellation and stdout/stderr draining also work for large SQL.
      val writer = CompletableFuture.runAsync(() => {
        val input = process.getProcessInput
        try input.write(text.getBytes(StandardCharsets.UTF_8))
        catch { case NonFatal(error) => inputError.set(error) }
        finally try input.close() catch { case NonFatal(_) => () }
      }, AppExecutorUtil.getAppExecutorService)
      val output = process.runProcessWithProgressIndicator(indicator, timeoutMs, true)
      try writer.get(1, TimeUnit.SECONDS) catch { case NonFatal(_) => writer.cancel(true) }
      if (output.isCancelled || canceled.get() || indicator.isCanceled) Left("Tool execution was canceled; source is unchanged.")
      else if (output.isTimeout) Left("Tool execution timed out after 60 seconds; source is unchanged.")
      else if (output.getExitCode == 0 && inputError.get() != null) Left("The tool did not read the SQL input; source is unchanged.")
      else Right(output)
    } catch {
      case NonFatal(error) => Left("Cannot run the configured tool. Check its executable, project configuration and environment: " + error.getMessage)
    } finally { handler.foreach(p => if (!p.isProcessTerminated) p.destroyProcess()); handler = None }
  }
}

final class DbtInspectSqlAction extends DbtEditorAction {
  override def update(event: AnActionEvent): Unit = event.getPresentation.setEnabledAndVisible(DbtEditorAction.sql(event))
  override def actionPerformed(event: AnActionEvent): Unit =
    DbtEditorAction.fileContext(event).foreach { case (project, file) => inspect(project, file) }

  private[ide] def inspect(project: Project, file: PsiFile): Unit = {
    def report(message: String, kind: NotificationType = NotificationType.WARNING): Unit =
      DbtNotifications.show(project, "SQLFluff inspection", message, kind)
    val snapshot = DbtUi.read {
      val document = PsiDocumentManager.getInstance(project).getDocument(file)
      if (document == null) Left("Open the SQL source document before running SQLFluff.")
      else DbtToolCommand.create(file, lint = true).map(c => (c, document.getText, document.getModificationStamp))
    }
    snapshot match {
      case Left(error) => report(error)
      case Right((command, source, stamp)) =>
        file.putUserData(DbtSqlFluff.Result, null)
        DbtUi.background(project, "Inspect dbt SQL with SQLFluff", Some(error => report("Cannot inspect this source: " + error.getMessage))) {
          indicator => new DbtToolProcess().run(command, source, indicator).flatMap { output =>
            if (output.getExitCode != 0 && output.getExitCode != 1)
              Left("SQLFluff failed (exit " + output.getExitCode + "). Check the project configuration. " + output.getStderr.take(4000))
            else DbtSqlFluff.parse(source, output.getStdout, file.getVirtualFile.getPath)
          }
        } { result =>
          val current = DbtUi.read(file.isValid &&
            Option(PsiDocumentManager.getInstance(project).getDocument(file)).exists(d => d.getModificationStamp == stamp && d.getText == source))
          if (!current) report("The document changed during inspection. Rerun Inspect dbt SQL.")
          else result match {
            case Left(error) => report(error)
            case Right(diagnostics) =>
              file.putUserData(DbtSqlFluff.Result, DbtSqlFluffResult(source, stamp, diagnostics))
              DaemonCodeAnalyzer.getInstance(project).restart(file, "SQLFluff explicit inspection completed")
              report(if (diagnostics.isEmpty) "No SQLFluff violations."
                else s"${diagnostics.size} diagnostics are available in the editor. Use Alt+Enter for available fixes.", NotificationType.INFORMATION)
          }
        }
    }
  }
}

private[ide] final case class DbtSqlFluffEdit(range: TextRange, replacement: String)
private[ide] final case class DbtSqlFluffDiagnostic(code: String, message: String, range: TextRange,
  warning: Boolean, edits: Vector[DbtSqlFluffEdit])
private[ide] final case class DbtSqlFluffResult(source: String, stamp: Long, diagnostics: Vector[DbtSqlFluffDiagnostic])
private[ide] object DbtSqlFluff {
  val Result: Key[DbtSqlFluffResult] = Key.create("dbt.sqlfluff.explicit.inspection")
  private[ide] val ParseErrors = Set("PRS", "TMP", "LXR")
  private def string(o: JsonObject, key: String): Option[String] =
    Option(o.get(key)).filterNot(_.isJsonNull).map(_.getAsString)
  private def number(o: JsonObject, key: String): Option[Int] =
    Option(o.get(key)).filter(_.isJsonPrimitive).filter(_.getAsJsonPrimitive.isNumber)
      .flatMap(value => scala.util.Try(value.getAsBigDecimal.intValueExact()).toOption)
  private def offset(source: String, position: Int): Option[Int] =
    if (position < 0 || position > source.codePointCount(0, source.length)) None
    else Some(source.offsetByCodePoints(0, position))
  private def lineOffset(source: String, line: Int, column: Int): Option[Int] = {
    val starts = Vector(0) ++ source.indices.filter(source.charAt(_) == '\n').map(_ + 1)
    starts.lift(line - 1).filter(_ => line > 0 && column > 0).flatMap { start =>
      val end = source.indexOf('\n', start) match { case -1 => source.length; case n => n }
      if (column - 1 > source.codePointCount(start, end)) None else Some(source.offsetByCodePoints(start, column - 1))
    }
  }
  private def position(source: String, row: JsonObject, prefix: String): Option[Int] = {
    val byLine = for (line <- number(row, prefix + "_line_no"); column <- number(row, prefix + "_line_pos");
      at <- lineOffset(source, line, column)) yield at
    val byFile = number(row, prefix + "_file_pos").flatMap(offset(source, _))
    if ((row.has(prefix + "_file_pos") && byFile.isEmpty) ||
      ((row.has(prefix + "_line_no") || row.has(prefix + "_line_pos")) && byLine.isEmpty)) None
    else (byLine, byFile) match {
      case (Some(a), Some(b)) if a != b => None
      case (Some(a), _) => Some(a)
      case (_, Some(b)) if !row.has(prefix + "_line_no") && !row.has(prefix + "_line_pos") => Some(b)
      case _ => None
    }
  }
  def parse(source: String, json: String, sourcePath: String = ""): Either[String, Vector[DbtSqlFluffDiagnostic]] = try {
    val files = JsonParser.parseString(json).getAsJsonArray.asScala.toVector
    if (files.size != 1) Left("SQLFluff must return one stdin document; diagnostics were not applied.")
    else {
      val file = files.head.getAsJsonObject
      val path = string(file, "filepath").getOrElse("")
      if (path.isEmpty || (path != "stdin" && path != "-" && path != sourcePath))
        Left("SQLFluff returned a different file; diagnostics were not applied.")
      else {
        val rows = file.getAsJsonArray("violations").asScala.map(_.getAsJsonObject).toVector
        val unsafe = rows.exists(r => ParseErrors(string(r, "code").getOrElse("")))
        val diagnostics = rows.map { row =>
          val start = position(source, row, "start")
          val hasEnd = Vector("end_line_no", "end_line_pos", "end_file_pos").exists(row.has)
          val end = if (hasEnd) position(source, row, "end")
            else start.map(s => if (s < source.length) source.offsetByCodePoints(s, 1) else s)
          for (a <- start; b <- end if a <= b && b <= source.length) yield {
            val code = string(row, "code").getOrElse("SQLFluff")
            val edits = Option(row.getAsJsonArray("fixes")).toVector.flatMap(_.asScala).map { raw =>
              val fix = raw.getAsJsonObject
              for (kind <- string(fix, "type") if Set("replace", "delete", "create_before", "create_after")(kind);
                from <- number(fix, "start_file_pos").flatMap(offset(source, _));
                to <- number(fix, "end_file_pos").flatMap(offset(source, _)) if from <= to;
                replacement <- string(fix, "edit") if (kind != "delete" || replacement.isEmpty) &&
                  (!kind.startsWith("create_") || from == to))
                yield DbtSqlFluffEdit(new TextRange(from, to), replacement)
            }
            val validEdits = if (unsafe || edits.exists(_.isEmpty)) Vector.empty else edits.flatten.toVector
            val sorted = validEdits.sortBy(_.range.getStartOffset)
            val disjoint = sorted.sliding(2).forall(pair => pair.size < 2 || pair.head.range.getEndOffset < pair(1).range.getStartOffset ||
              (pair.head.range.getEndOffset == pair(1).range.getStartOffset && !pair.head.range.isEmpty && !pair(1).range.isEmpty))
            DbtSqlFluffDiagnostic(code, string(row, "description").getOrElse("SQLFluff diagnostic"), new TextRange(a, b),
              string(row, "warning").contains("true"), if (disjoint) validEdits else Vector.empty)
          }
        }
        if (diagnostics.exists(_.isEmpty)) Left("SQLFluff returned invalid source positions; diagnostics were not applied. Update SQLFluff and rerun Inspect dbt SQL.")
        else Right(diagnostics.flatten)
      }
    }
  } catch { case NonFatal(_) => Left("SQLFluff returned unsupported JSON. Check the executable, SQLFluff version and project configuration.") }

  def applyEdits(file: PsiFile, result: DbtSqlFluffResult, diagnostic: DbtSqlFluffDiagnostic): Boolean = {
    val manager = PsiDocumentManager.getInstance(file.getProject)
    val document = manager.getDocument(file)
    if (document == null || !file.isWritable || document.getModificationStamp != result.stamp || document.getText != result.source || diagnostic.edits.isEmpty) false
    else {
      WriteCommandAction.writeCommandAction(file.getProject).withName("Apply SQLFluff " + diagnostic.code).run(new ThrowableRunnable[RuntimeException] {
        override def run(): Unit = {
          diagnostic.edits.sortBy(_.range.getStartOffset).reverse.foreach(e => document.replaceString(e.range.getStartOffset, e.range.getEndOffset, e.replacement))
          file.putUserData(Result, null); manager.commitDocument(document)
        }
      })
      true
    }
  }
}

/** Uses only results from the user's explicit Inspect dbt SQL invocation. It never launches a tool while typing. */
final class DbtSqlFluffInspection extends LocalInspectionTool {
  override def getShortName: String = "DbtSqlFluff"
  override def buildVisitor(holder: ProblemsHolder, onTheFly: Boolean): PsiElementVisitor = new PsiElementVisitor {
    override def visitFile(file: PsiFile): Unit = Option(file.getUserData(DbtSqlFluff.Result))
      .filter(r => r.source == file.getText && Option(PsiDocumentManager.getInstance(file.getProject).getDocument(file)).exists(_.getModificationStamp == r.stamp))
      .foreach { result =>
        result.diagnostics.foreach { d =>
          val fixes: Array[LocalQuickFix] = if (d.edits.isEmpty) LocalQuickFix.EMPTY_ARRAY else Array(new DbtSqlFluffFix(result, d))
          holder.registerProblem(file, "SQLFluff " + d.code + ": " + d.message,
            if (DbtSqlFluff.ParseErrors(d.code)) ProblemHighlightType.GENERIC_ERROR else if (d.warning) ProblemHighlightType.WEAK_WARNING else ProblemHighlightType.GENERIC_ERROR_OR_WARNING, d.range, fixes: _*)
        }
      }
  }
}

final class DbtSqlFluffFix private[ide](result: DbtSqlFluffResult, diagnostic: DbtSqlFluffDiagnostic) extends LocalQuickFix {
  override def getFamilyName: String = "Apply available SQLFluff fix"
  override def getName: String = "Apply SQLFluff " + diagnostic.code + " fix"
  override def startInWriteAction(): Boolean = false
  override def getElementToMakeWritable(currentFile: PsiFile): PsiElement = currentFile
  override def applyFix(project: Project, descriptor: ProblemDescriptor): Unit = {
    DbtSqlFluff.applyEdits(descriptor.getPsiElement.getContainingFile, result, diagnostic)
    ()
  }
}

private[ide] object DbtFormatting {
  def result(process: DbtToolProcess, command: DbtToolCommand, before: String,
    indicator: ProgressIndicator): Either[String, String] =
    process.run(command, before, indicator).flatMap { output =>
      if (output.getExitCode != 0) Left("Formatter failed (exit " + output.getExitCode + "). Check the project configuration. " +
        output.getStderr.take(4000))
      else if (output.getStdout.trim.isEmpty && before.trim.nonEmpty) Left("The formatter returned empty SQL; source is unchanged.")
      else Right(output.getStdout)
    }
}

final class DbtFormattingService extends AsyncDocumentFormattingService {
  override def getName: String = "dbt SQL formatter"
  override def getNotificationGroupId: String = "dbt Workspace"
  override def getFeatures: java.util.Set[FormattingService.Feature] = java.util.Set.of(FormattingService.Feature.AD_HOC_FORMATTING)
  override def canFormat(file: PsiFile): Boolean = file.getName.endsWith(".sql") && file.isWritable &&
    DbtWorkspace.rootForFile(file.getProject, file.getVirtualFile).exists(r => r.resource(file) &&
      !r.excluded(file.getVirtualFile) && r.runOptions.formatterExecutable.nonEmpty)
  override def createFormattingTask(request: AsyncFormattingRequest): AsyncDocumentFormattingService.FormattingTask = {
    val file = request.getContext.getContainingFile
    val before = request.getDocumentText
    val command = DbtUi.read(DbtToolCommand.create(file))
    val process = new DbtToolProcess
    new AsyncDocumentFormattingService.FormattingTask {
      override def run(): Unit = {
        val indicator = Option(ProgressManager.getInstance.getProgressIndicator).getOrElse(new EmptyProgressIndicator)
        command.flatMap(DbtFormatting.result(process, _, before, indicator)) match {
          case Left(error) => request.onError("dbt formatter", error)
          case Right(text) =>
            val current = DbtUi.read(!file.getProject.isDisposed &&
              Option(PsiDocumentManager.getInstance(file.getProject).getDocument(file)).exists(_.getText == before))
            if (current) request.onTextReady(text)
            else request.onError("dbt formatter", "The document changed during formatting. Run Reformat Code again; source is unchanged.")
        }
      }
      override def cancel(): Boolean = { process.cancel(); true }
      override def isRunUnderProgress: Boolean = true
    }
  }
}

private[ide] object DbtFormatOnSave {
  private val pending = ConcurrentHashMap.newKeySet[Document]()
  def enabled(project: Project, file: VirtualFile): Boolean =
    file.isWritable && file.getExtension == "sql" &&
      DbtWorkspace.rootForFile(project, file).exists(r => r.kind(file).nonEmpty && !r.excluded(file) &&
        r.runOptions.formatOnSave && r.runOptions.formatterExecutable.nonEmpty)

  /** Shared with tests: reject stale output and keep each tool result in one native undo command. */
  def applyText(project: Project, document: Document, before: String, stamp: Long, after: String): Boolean = {
    if (project.isDisposed || !document.isWritable || document.getModificationStamp != stamp || document.getText != before || before == after) false
    else {
      WriteCommandAction.writeCommandAction(project).withName("Format dbt SQL on save").run(new ThrowableRunnable[RuntimeException] {
        override def run(): Unit = {
          document.setText(after)
          PsiDocumentManager.getInstance(project).commitDocument(document)
        }
      })
      true
    }
  }
}

/** Save first, format in a cancellable background task, then save only the still-current source. */
final class DbtFormatOnSave extends FileDocumentManagerListener {
  override def beforeDocumentSaving(document: Document): Unit = {
    val manager = FileDocumentManager.getInstance()
    val selected = for {
      file <- Option(manager.getFile(document))
      project <- Option(ProjectLocator.getInstance.guessProjectForFile(file))
      snapshot <- DbtUi.read {
        if (!project.isDisposed && DbtFormatOnSave.enabled(project, file))
          Option(PsiManager.getInstance(project).findFile(file)).flatMap(psi => DbtToolCommand.create(psi).toOption)
            .map(command => (project, command, document.getText, document.getModificationStamp))
        else None
      }
    } yield snapshot
    selected.foreach { case (project, command, source, stamp) =>
      if (DbtFormatOnSave.pending.add(document)) ApplicationManager.getApplication.invokeLater(() => {
        if (project.isDisposed) DbtFormatOnSave.pending.remove(document)
        else DbtUi.background(project, "Format dbt SQL on save",
          Some(error => DbtNotifications.show(project, "dbt format on save", error.getMessage)),
          () => { DbtFormatOnSave.pending.remove(document); () })(DbtFormatting.result(new DbtToolProcess, command, source, _)) {
          case Left(error) => DbtNotifications.show(project, "dbt format on save", error)
          case Right(after) => if (DbtFormatOnSave.applyText(project, document, source, stamp, after)) manager.saveDocument(document)
        }
      })
    }
  }
}
