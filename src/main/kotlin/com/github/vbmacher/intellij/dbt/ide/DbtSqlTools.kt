package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project.*
import com.github.vbmacher.intellij.dbt.syntax.DbtYaml
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInspection.*
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessOutput
import com.intellij.formatting.service.AsyncDocumentFormattingService
import com.intellij.formatting.service.AsyncFormattingRequest
import com.intellij.formatting.service.FormattingService
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectLocator
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.util.concurrency.AppExecutorUtil
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Captured argv, working directory and environment for a formatter or linter; SQL is supplied separately on stdin. */
internal data class DbtToolCommand(
    val arguments: List<String>,
    val directory: String,
    val environment: Map<String, String>
) {
    val commandLine: GeneralCommandLine
        get() = GeneralCommandLine(arguments).withWorkDirectory(directory).withEnvironment(environment)
            .withCharset(StandardCharsets.UTF_8)

    companion object {
        fun create(file: PsiFile, lint: Boolean = false): Result<DbtToolCommand> {
            val root = DbtWorkspace.rootForFile(file.project, file.virtualFile)
                ?.takeIf { it.resource(file) && !it.excluded(file.virtualFile) }
                ?: return Result.failure(IllegalArgumentException("Open a SQL resource belonging to a configured dbt project."))
            val options = root.runOptions
            val configured = options.formatterExecutable.trim()
            val error = when {
                configured.isEmpty() -> "Configure a formatter executable in Settings > dbt Workspace."
                options.formatter !in setOf(
                    "sqlfmt",
                    "sqlfluff"
                ) -> "Choose sqlfmt or SQLFluff in Settings > dbt Workspace."

                lint && options.formatter != "sqlfluff" -> "SQL diagnostics require SQLFluff. Select SQLFluff in Settings > dbt Workspace."
                else -> null
            }
            if (error != null) return Result.failure(IllegalArgumentException(error))
            val executable =
                if ('/' in configured || '\\' in configured) root.executionDirectory.resolve(configured).normalize()
                    .toString() else configured
            val arguments = if (options.formatter == "sqlfluff") listOf(
                executable,
                if (lint) "lint" else "format",
                "--nocolor",
                "--disable-progress-bar",
                "--stdin-filename",
                file.virtualFile.path
            ) +
                    (if (lint) listOf("--format", "json") else emptyList()) + "-" else listOf(executable, "-")
            return Result.success(
                DbtToolCommand(
                    arguments,
                    root.executionDirectory.toString(),
                    DbtExecutionContext.environment(root)
                )
            )
        }
    }
}

/** Only argv, cwd, inherited project environment and raw source stdin reach the configured executable. */
internal class DbtToolProcess {
    private val canceled = AtomicBoolean(false)
    @Volatile
    private var handler: CapturingProcessHandler? = null
    fun cancel() {
        canceled.set(true); handler?.destroyProcess()
    }

    fun run(
        command: DbtToolCommand,
        text: String,
        indicator: ProgressIndicator = EmptyProgressIndicator(),
        timeoutMs: Int = 60000
    ): Result<ProcessOutput> {
        fun fail(message: String): Result<ProcessOutput> = Result.failure(IllegalArgumentException(message))
        if (canceled.get() || indicator.isCanceled) return fail("Tool execution was canceled; source is unchanged.")
        return try {
            val process = CapturingProcessHandler(command.commandLine)
            process.setShouldDestroyProcessRecursively(true); handler = process
            if (canceled.get()) process.destroyProcess()
            val inputError = AtomicReference<Throwable>()
            val writer = CompletableFuture.runAsync({
                val input = process.processInput
                try {
                    input.write(text.toByteArray(StandardCharsets.UTF_8))
                } catch (error: Exception) {
                    inputError.set(error)
                } finally {
                    try {
                        input.close()
                    } catch (_: Exception) {
                    }
                }
            }, AppExecutorUtil.getAppExecutorService())
            val output = process.runProcessWithProgressIndicator(indicator, timeoutMs, true)
            try {
                writer.get(1, TimeUnit.SECONDS)
            } catch (_: Exception) {
                writer.cancel(true)
            }
            when {
                output.isCancelled || canceled.get() || indicator.isCanceled -> fail("Tool execution was canceled; source is unchanged.")
                output.isTimeout -> fail("Tool execution timed out after 60 seconds; source is unchanged.")
                output.exitCode == 0 && inputError.get() != null -> fail("The tool did not read the SQL input; source is unchanged.")
                else -> Result.success(output)
            }
        } catch (error: Exception) {
            fail("Cannot run the configured tool. Check its executable, project configuration and environment: " + error.message)
        } finally {
            handler?.let { if (!it.isProcessTerminated) it.destroyProcess() }; handler = null
        }
    }
}

class DbtInspectSqlAction : DbtEditorAction() {
    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = DbtEditorAction.sql(event)
    }

    override fun actionPerformed(event: AnActionEvent) {
        DbtEditorAction.fileContext(event)?.let { (project, file) -> inspect(project, file) }
    }

    internal fun inspect(project: Project, file: PsiFile) {
        fun report(message: String, kind: NotificationType = NotificationType.WARNING) =
            DbtNotifications.show(project, "SQLFluff inspection", message, kind)

        val snapshot = DbtUi.read {
            val document = PsiDocumentManager.getInstance(project).getDocument(file)
            if (document == null) Result.failure(IllegalArgumentException("Open the SQL source document before running SQLFluff."))
            else DbtToolCommand.create(file, lint = true).map { Triple(it, document.text, document.modificationStamp) }
        }
        snapshot.fold(onFailure = { report(it.message.orEmpty()) }, onSuccess = { (command, source, stamp) ->
            file.putUserData(DbtSqlFluff.Result, null)
            DbtUi.background(
                project,
                "Inspect dbt SQL with SQLFluff",
                failed = { report("Cannot inspect this source: " + it.message) },
                work = { indicator ->
                    val output = DbtToolProcess().run(command, source, indicator)
                    output.fold(onFailure = { Result.failure(it) }, onSuccess = {
                        if (it.exitCode != 0 && it.exitCode != 1) Result.failure(
                            IllegalArgumentException(
                                "SQLFluff failed (exit ${it.exitCode}). Check the project configuration. " + it.stderr.take(
                                    4000
                                )
                            )
                        )
                        else DbtSqlFluff.parse(source, it.stdout, file.virtualFile.path)
                    })
                }) { result ->
                val current = DbtUi.read {
                    file.isValid && PsiDocumentManager.getInstance(project).getDocument(file)
                        ?.let { it.modificationStamp == stamp && it.text == source } == true
                }
                if (!current) report("The document changed during inspection. Rerun Inspect dbt SQL.") else result.fold(
                    onFailure = { report(it.message.orEmpty()) },
                    onSuccess = { diagnostics ->
                        file.putUserData(DbtSqlFluff.Result, DbtSqlFluffResult(source, stamp, diagnostics))
                        DaemonCodeAnalyzer.getInstance(project).restart(file, "SQLFluff explicit inspection completed")
                        report(
                            if (diagnostics.isEmpty()) "No SQLFluff violations." else "${diagnostics.size} diagnostics are available in the editor. Use Alt+Enter for available fixes.",
                            NotificationType.INFORMATION
                        )
                    })
            }
        })
    }
}

internal data class DbtSqlFluffEdit(val range: TextRange, val replacement: String)
internal data class DbtSqlFluffDiagnostic(
    val code: String,
    val message: String,
    val range: TextRange,
    val warning: Boolean,
    val edits: List<DbtSqlFluffEdit>
)

internal data class DbtSqlFluffResult(val source: String, val stamp: Long, val diagnostics: List<DbtSqlFluffDiagnostic>)
internal object DbtSqlFluff {
    val Result: Key<DbtSqlFluffResult> = Key.create("dbt.sqlfluff.explicit.inspection")
    val ParseErrors = setOf("PRS", "TMP", "LXR")
    private fun string(o: JsonObject, key: String): String? = o.get(key)?.takeUnless { it.isJsonNull }?.asString
    private fun number(o: JsonObject, key: String): Int? =
        o.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.let {
            try {
                it.asBigDecimal.intValueExact()
            } catch (_: ArithmeticException) {
                null
            }
        }

    private fun offset(source: String, position: Int): Int? =
        if (position < 0 || position > source.codePointCount(0, source.length)) null else source.offsetByCodePoints(
            0,
            position
        )

    private fun lineOffset(source: String, line: Int, column: Int): Int? {
        val starts = listOf(0) + source.indices.filter { source[it] == '\n' }.map { it + 1 }
        val start = starts.getOrNull(line - 1)?.takeIf { line > 0 && column > 0 } ?: return null
        val end = source.indexOf('\n', start).let { if (it == -1) source.length else it }
        return if (column - 1 > source.codePointCount(start, end)) null else source.offsetByCodePoints(
            start,
            column - 1
        )
    }

    private fun position(source: String, row: JsonObject, prefix: String): Int? {
        val line = number(row, prefix + "_line_no");
        val column = number(row, prefix + "_line_pos")
        val byLine = if (line != null && column != null) lineOffset(source, line, column) else null
        val byFile = number(row, prefix + "_file_pos")?.let { offset(source, it) }
        if ((row.has(prefix + "_file_pos") && byFile == null) || ((row.has(prefix + "_line_no") || row.has(prefix + "_line_pos")) && byLine == null)) return null
        return when {
            byLine != null && byFile != null && byLine != byFile -> null
            byLine != null -> byLine
            byFile != null && !row.has(prefix + "_line_no") && !row.has(prefix + "_line_pos") -> byFile
            else -> null
        }
    }

    /**
     * Maps raw-source Unicode coordinates back to editor offsets and rejects inconsistent diagnostic positions.
     * Fixes are offered only when every edit is understood and disjoint; parse/template/lexer errors disable fixes.
     */
    fun parse(source: String, json: String, sourcePath: String = ""): kotlin.Result<List<DbtSqlFluffDiagnostic>> {
        fun fail(message: String): kotlin.Result<List<DbtSqlFluffDiagnostic>> =
            kotlin.Result.failure(IllegalArgumentException(message))
        return try {
            val files = JsonParser.parseString(json).asJsonArray.toList()
            if (files.size != 1) return fail("SQLFluff must return one stdin document; diagnostics were not applied.")
            val file = files.first().asJsonObject
            val path = string(file, "filepath").orEmpty()
            if (path.isEmpty() || path != "stdin" && path != "-" && path != sourcePath) return fail("SQLFluff returned a different file; diagnostics were not applied.")
            val rows = file.getAsJsonArray("violations").map { it.asJsonObject }
            val unsafe = rows.any { string(it, "code").orEmpty() in ParseErrors }
            val diagnostics = rows.map { row ->
                val start = position(source, row, "start")
                val hasEnd = listOf("end_line_no", "end_line_pos", "end_file_pos").any(row::has)
                val end = if (hasEnd) position(
                    source,
                    row,
                    "end"
                ) else start?.let { if (it < source.length) source.offsetByCodePoints(it, 1) else it }
                if (start == null || end == null || start > end || end > source.length) null else {
                    val code = string(row, "code") ?: "SQLFluff"
                    val edits = row.getAsJsonArray("fixes")?.map { raw ->
                        val fix = raw.asJsonObject
                        val kind = string(fix, "type")
                        val from = number(fix, "start_file_pos")?.let { offset(source, it) }
                        val to = number(fix, "end_file_pos")?.let { offset(source, it) }
                        val replacement = string(fix, "edit")
                        if (kind in setOf(
                                "replace",
                                "delete",
                                "create_before",
                                "create_after"
                            ) && from != null && to != null && from <= to && replacement != null &&
                            (kind != "delete" || replacement.isEmpty()) && (kind?.startsWith("create_") != true || from == to)
                        ) DbtSqlFluffEdit(TextRange(from, to), replacement) else null
                    } ?: emptyList()
                    val validEdits = if (unsafe || edits.any { it == null }) emptyList() else edits.filterNotNull()
                    val sorted = validEdits.sortedBy { it.range.startOffset }
                    val disjoint = sorted.windowed(2, 1, partialWindows = true).all { pair ->
                        pair.size < 2 || pair.first().range.endOffset < pair[1].range.startOffset ||
                                pair.first().range.endOffset == pair[1].range.startOffset && !pair.first().range.isEmpty && !pair[1].range.isEmpty
                    }
                    DbtSqlFluffDiagnostic(
                        code,
                        string(row, "description") ?: "SQLFluff diagnostic",
                        TextRange(start, end),
                        string(row, "warning") == "true",
                        if (disjoint) validEdits else emptyList()
                    )
                }
            }
            if (diagnostics.any { it == null }) fail("SQLFluff returned invalid source positions; diagnostics were not applied. Update SQLFluff and rerun Inspect dbt SQL.") else kotlin.Result.success(
                diagnostics.filterNotNull()
            )
        } catch (_: Exception) {
            fail("SQLFluff returned unsupported JSON. Check the executable, SQLFluff version and project configuration.")
        }
    }

    /** Applies a validated fix as one undoable write only while the original source and modification stamp still match. */
    fun applyEdits(file: PsiFile, result: DbtSqlFluffResult, diagnostic: DbtSqlFluffDiagnostic): Boolean {
        val manager = PsiDocumentManager.getInstance(file.project)
        val document = manager.getDocument(file)
        if (document == null || !file.isWritable || document.modificationStamp != result.stamp || document.text != result.source || diagnostic.edits.isEmpty()) return false
        WriteCommandAction.writeCommandAction(file.project).withName("Apply SQLFluff " + diagnostic.code)
            .run<RuntimeException> {
                diagnostic.edits.sortedByDescending { it.range.startOffset }
                    .forEach { document.replaceString(it.range.startOffset, it.range.endOffset, it.replacement) }
                file.putUserData(Result, null); manager.commitDocument(document)
            }
        return true
    }
}

/** Uses only results from the user's explicit Inspect dbt SQL invocation. It never launches a tool while typing. */
class DbtSqlFluffInspection : LocalInspectionTool() {
    override fun getShortName() = "DbtSqlFluff"
    override fun buildVisitor(holder: ProblemsHolder, onTheFly: Boolean): PsiElementVisitor =
        object : PsiElementVisitor() {
            override fun visitFile(file: PsiFile) {
                file.getUserData(DbtSqlFluff.Result)?.takeIf {
                    it.source == file.text && PsiDocumentManager.getInstance(file.project)
                        .getDocument(file)?.modificationStamp == it.stamp
                }?.let { result ->
                    result.diagnostics.forEach { d ->
                        val fixes: Array<LocalQuickFix> =
                            if (d.edits.isEmpty()) LocalQuickFix.EMPTY_ARRAY else arrayOf(DbtSqlFluffFix(result, d))
                        holder.registerProblem(
                            file,
                            "SQLFluff ${d.code}: ${d.message}",
                            if (d.code in DbtSqlFluff.ParseErrors) ProblemHighlightType.GENERIC_ERROR else if (d.warning) ProblemHighlightType.WEAK_WARNING else ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                            d.range,
                            *fixes
                        )
                    }
                }
            }
        }
}

class DbtSqlFluffFix internal constructor(
    private val result: DbtSqlFluffResult,
    private val diagnostic: DbtSqlFluffDiagnostic
) : LocalQuickFix {
    override fun getFamilyName() = "Apply available SQLFluff fix"
    override fun getName() = "Apply SQLFluff " + diagnostic.code + " fix"
    override fun startInWriteAction() = false
    override fun getElementToMakeWritable(currentFile: PsiFile): PsiElement = currentFile
    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        DbtSqlFluff.applyEdits(descriptor.psiElement.containingFile, result, diagnostic)
    }
}

internal object DbtFormatting {
    fun result(
        process: DbtToolProcess,
        command: DbtToolCommand,
        before: String,
        indicator: ProgressIndicator
    ): Result<String> {
        val output = process.run(command, before, indicator).getOrElse { return Result.failure(it) }
        return when {
            output.exitCode != 0 -> Result.failure(
                IllegalArgumentException(
                    "Formatter failed (exit ${output.exitCode}). Check the project configuration. " + output.stderr.take(
                        4000
                    )
                )
            )

            output.stdout.trim().isEmpty() && before.trim()
                .isNotEmpty() -> Result.failure(IllegalArgumentException("The formatter returned empty SQL; source is unchanged."))

            else -> Result.success(output.stdout)
        }
    }
}

class DbtFormattingService : AsyncDocumentFormattingService() {
    override fun getName() = "dbt SQL formatter"
    override fun getNotificationGroupId() = "dbt Workspace"
    override fun getFeatures(): Set<FormattingService.Feature> = setOf(FormattingService.Feature.AD_HOC_FORMATTING)
    override fun canFormat(file: PsiFile) =
        file.name.endsWith(".sql") && file.isWritable && DbtWorkspace.rootForFile(file.project, file.virtualFile)?.let {
            it.resource(file) && !it.excluded(file.virtualFile) && it.runOptions.formatterExecutable.isNotEmpty()
        } == true

    override fun createFormattingTask(request: AsyncFormattingRequest): FormattingTask {
        val file = request.context.containingFile
        val before = request.documentText
        val command = DbtUi.read { DbtToolCommand.create(file) }
        val process = DbtToolProcess()
        return object : FormattingTask {
            override fun run() {
                val indicator = ProgressManager.getInstance().progressIndicator ?: EmptyProgressIndicator()
                val result = command.fold(
                    onFailure = { Result.failure(it) },
                    onSuccess = { DbtFormatting.result(process, it, before, indicator) })
                result.fold(
                    onFailure = { request.onError("dbt formatter", it.message.orEmpty()) },
                    onSuccess = { text ->
                        val current = DbtUi.read {
                            !file.project.isDisposed && PsiDocumentManager.getInstance(file.project)
                                .getDocument(file)?.text == before
                        }
                        if (current) request.onTextReady(text) else request.onError(
                            "dbt formatter",
                            "The document changed during formatting. Run Reformat Code again; source is unchanged."
                        )
                    })
            }

            override fun cancel(): Boolean {
                process.cancel(); return true
            }

            override fun isRunUnderProgress() = true
        }
    }
}

class DbtFormatOnSave : FileDocumentManagerListener {
    override fun beforeDocumentSaving(document: Document) {
        val manager = FileDocumentManager.getInstance()
        val file = manager.getFile(document) ?: return
        val project = ProjectLocator.getInstance().guessProjectForFile(file) ?: return
        val selected = DbtUi.read {
            if (!project.isDisposed && enabled(project, file)) DbtYaml.psiFile(project, file)
                ?.let { DbtToolCommand.create(it).getOrNull() }
                ?.let { Triple(it, document.text, document.modificationStamp) } else null
        } ?: return
        val (command, source, stamp) = selected
        if (pending.add(document)) ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) pending.remove(document) else DbtUi.background(
                project,
                "Format dbt SQL on save",
                failed = { DbtNotifications.show(project, "dbt format on save", it.message.orEmpty()) },
                finished = { pending.remove(document) },
                work = { DbtFormatting.result(DbtToolProcess(), command, source, it) }) { result ->
                result.fold(
                    onFailure = { DbtNotifications.show(project, "dbt format on save", it.message.orEmpty()) },
                    onSuccess = { after ->
                        if (applyText(project, document, source, stamp, after)) manager.saveDocument(document)
                    })
            }
        }
    }

    companion object {
        private val pending = ConcurrentHashMap.newKeySet<Document>()
        internal fun enabled(project: Project, file: VirtualFile) =
            file.isWritable && file.extension == "sql" && DbtWorkspace.rootForFile(project, file)?.let {
                it.kind(file) != null && !it.excluded(file) && it.runOptions.formatOnSave && it.runOptions.formatterExecutable.isNotEmpty()
            } == true

        internal fun applyText(
            project: Project,
            document: Document,
            before: String,
            stamp: Long,
            after: String
        ): Boolean {
            if (project.isDisposed || !document.isWritable || document.modificationStamp != stamp || document.text != before || before == after) return false
            WriteCommandAction.writeCommandAction(project).withName("Format dbt SQL on save").run<RuntimeException> {
                document.setText(after); PsiDocumentManager.getInstance(project).commitDocument(document)
            }
            return true
        }
    }
}
