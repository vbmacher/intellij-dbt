package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project._
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.{CapturingProcessHandler, ProcessOutput}
import com.intellij.openapi.progress.{EmptyProgressIndicator, ProgressIndicator}
import com.intellij.psi.PsiFile
import com.intellij.util.concurrency.AppExecutorUtil
import java.nio.charset.StandardCharsets
import java.util.concurrent.{CompletableFuture, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

private[ide] final case class DbtToolCommand(arguments: Vector[String], directory: String, environment: Map[String, String])
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
      val line = new GeneralCommandLine(command.arguments.asJava).withWorkDirectory(command.directory)
        .withEnvironment(command.environment.asJava).withCharset(StandardCharsets.UTF_8)
      val process = new CapturingProcessHandler(line)
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
