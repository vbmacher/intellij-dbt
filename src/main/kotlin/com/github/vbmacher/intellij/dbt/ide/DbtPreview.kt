package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.navigation.*
import com.github.vbmacher.intellij.dbt.project.*
import com.github.vbmacher.intellij.dbt.syntax.DbtSyntax
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiFile
import com.intellij.ui.components.JBLabel
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JPanel

class DbtPreviewSqlAction : AnAction("Preview selected dbt SQL") {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = DbtEditorAction.sql(
            event,
            editor = true
        ) && event.getData(CommonDataKeys.EDITOR)!!.selectionModel.hasSelection()
    }

    override fun actionPerformed(event: AnActionEvent) {
        DbtCteInspection.launchPreview(event, "Preview selected dbt SQL", input = { file, editor ->
            val sql = editor.selectionModel.selectedText?.takeIf { it.trim().isNotEmpty() };
            { w, before ->
                dbtResult {
                    val text = sql ?: throw IllegalArgumentException("Select SQL to preview.")
                    require(DbtNavigation.projectedColumns(text) != null) { "Select one supported SELECT query to preview. Statements that modify data or schema cannot be previewed." }
                    val model = w.model(file) ?: throw IllegalArgumentException("Choose a dbt source model.")
                    require(
                        before == DbtCteInspection.context(
                            w,
                            model
                        )
                    ) { "Source or execution settings changed; select the query again." }
                    DbtCteInspection.command(w, model, text).getOrThrow()
                }
            }
        }, verify = { command: DbtCommand, before, current ->
            dbtResult {
                require(before != null && before == current()) { "Source or execution settings changed; no query was launched." }; command
            }
        })
    }
}

class DbtPreviewModelAction : DbtEditorAction() {
    init {
        templatePresentation.text = "Preview dbt model"
    }

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = DbtEditorAction.sql(event) && DbtEditorAction.fileContext(event)
            ?.let { (project, file) -> DbtWorkspace.get(project).model(file)?.isEnabled } == true
    }

    override fun actionPerformed(event: AnActionEvent) {
        DbtEditorAction.fileContext(event)?.let { (project, file) -> launch(project, file) }
    }

    companion object {
        fun launch(project: Project, file: PsiFile) {
            DbtUi.saveAndCommit(project)
            DbtUi.readLater(project, read = {
                dbtResult {
                    val w = DbtWorkspace.get(project)
                    val selection = DbtRunSelection.available(w).find { it.path == file.virtualFile.path }
                        ?: throw IllegalArgumentException("Choose an available dbt model.")
                    DbtCommand.create(w, selection, "Preview", false).getOrThrow()
                }
            }) { result ->
                result.fold(
                    { DbtRunConfiguration.launch(project, it) },
                    { DbtNotifications.show(project, "Preview dbt model", it.message.orEmpty()) })
            }
        }
    }
}

class DbtPreviewCteAction : AnAction("Preview dbt CTE") {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = DbtEditorAction.sql(event, editor = true)
    }

    override fun actionPerformed(event: AnActionEvent) {
        DbtCteInspection.launchPreview(event, "Preview dbt CTE", offerModelPreview = true, input = { file, editor ->
            val offset = editor.caretModel.offset;
            { w, _ ->
                dbtResult {
                    val resource = w.model(file) ?: throw IllegalArgumentException("Choose a dbt source model.")
                    val snapshot = DbtCteInspection.context(w, resource)
                        ?: throw IllegalArgumentException("The execution project is unavailable.")
                    val analysis = DbtSqlAnalysis.result(w, file)
                        .getOrElse { throw IllegalArgumentException("Cannot preview this CTE.\n\n" + it.message + "\n\nNo preview query was run.\nChoose Preview model to preview the complete model.") }
                    val cte = DbtCteInspection.at(w, file, analysis, offset)
                        ?: throw IllegalArgumentException("Place the caret at a recognized CTE definition or reference.")
                    val query = DbtCteInspection.plan(snapshot.source, analysis, cte).getOrThrow()
                    snapshot to DbtCteInspection.command(w, resource, query.sql, query.spans).getOrThrow()
                }
            }
        }, verify = { prepared: Pair<DbtCteContext, DbtCommand>, expected, current ->
            dbtResult {
                val (snapshot, command) = prepared
                require(expected == snapshot && current() == snapshot) { "Source or execution settings changed; no query was launched. Invoke Preview CTE again." }; command
            }
        })
    }
}

/** Plans independent bounded previews from supported SELECT queries and their required CTE dependencies. */
internal object DbtCteInspection {
    /** Top-level CTEs: the inspectable query steps of a model. */
    fun frames(analysis: DbtSqlAnalysis): List<DbtCte> =
        analysis.ctes.filter { c -> analysis.ctes.none { it.body.contains(c.range) } }

    /**
     * Captures editor input before committing, then prepares against the model snapshot in a background read.
     * [verify] runs on the UI continuation with the original and current contexts before any command launches.
     */
    fun <T> launchPreview(
        event: AnActionEvent, title: String, offerModelPreview: Boolean = false,
        input: (PsiFile, Editor) -> (DbtWorkspace, DbtCteContext?) -> Result<T>,
        verify: (T, DbtCteContext?, () -> DbtCteContext?) -> Result<DbtCommand>
    ) {
        WriteIntentReadAction.run {
            DbtEditorAction.editorContext(event)?.let { (project, file, editor) ->
                val prepare = input(file, editor); DbtUi.saveAndCommit(project)
                fun current(): DbtCteContext? =
                    DbtUi.read { val w = DbtWorkspace.get(project); w.model(file)?.let { context(w, it) } }

                val before = current()
                DbtUi.readLater(project, read = { prepare(DbtWorkspace.get(project), before) }) { result ->
                    val checked = result.fold({ verify(it, before, ::current) }, { Result.failure(it) })
                    checked.fold({ DbtRunConfiguration.launch(project, it) }, { error ->
                        if (offerModelPreview) {
                            if (Messages.showYesNoDialog(
                                    project,
                                    error.message.orEmpty(),
                                    title,
                                    "Preview model",
                                    "Cancel",
                                    Messages.getInformationIcon()
                                ) == Messages.YES
                            ) DbtPreviewModelAction.launch(project, file)
                        } else DbtNotifications.show(project, title, error.message.orEmpty())
                    })
                }
            }
        }
    }

    fun at(w: DbtWorkspace, file: PsiFile, analysis: DbtSqlAnalysis, offset: Int): DbtCte? {
        val resolved = DbtNavigation.at(file, offset).flatMap { it.candidates }.filterIsInstance<DbtSymbolElement>()
            .filter { it.symbol.kind == "cte" && it.containingFile == file }.flatMap { element ->
                analysis.ctes.filter {
                    it.name.equals(
                        element.name,
                        true
                    ) && it.range.startOffset == element.textRange.startOffset
                }
            }.distinct()
        return if (resolved.size == 1) resolved.first() else if (resolved.isNotEmpty()) null else analysis.ctes.filter {
            it.range.containsOffset(
                offset
            )
        }.minByOrNull { it.range.length }
    }

    /** Preserve authored template setup and comments preceding the SQL WITH keyword. */
    private fun prefix(text: String, analysis: DbtSqlAnalysis, selected: List<DbtCte>, target: DbtCte): String {
        val initial = frames(analysis).minByOrNull { it.range.startOffset }
            ?: throw IllegalArgumentException("The outer WITH clause is unavailable.")
        val blocks = DbtSyntax.blocks(text)
        val prefix = DbtNavigation.withPrefix(text, initial.range.startOffset)
            ?: throw IllegalArgumentException("Template setup before WITH cannot be extracted safely.")
        val omittedSetup = blocks.any { b ->
            b.tag && b.range.startOffset >= prefix.length && b.range.startOffset < target.range.endOffset && selected.none {
                it.range.contains(b.range)
            }
        }
        require(!omittedSetup) { "Template setup between CTE declarations cannot be omitted safely. Move it before WITH or preview the whole model." }
        return prefix
    }

    fun sql(text: String, analysis: DbtSqlAnalysis, cte: DbtCte, count: Boolean = false): Result<String> =
        plan(text, analysis, cte, count).map { it.sql }

    /**
     * Extracts the selected CTE and required dependencies, preserving safe template setup and authored spans.
     * Recursive queries, explicit CTE column lists and setup that cannot be retained safely are rejected.
     * [count] changes the final SELECT to a full count; ordinary previews apply their row limit at execution.
     */
    fun plan(text: String, analysis: DbtSqlAnalysis, cte: DbtCte, count: Boolean = false): Result<DbtCteQuery> =
        dbtResult {
            require(analysis.issues.none { it.startsWith("Explicit CTE column lists") }) { "Explicit CTE column lists cannot be inspected safely. Preview the complete model instead." }
            val selected = linkedSetOf<DbtCte>();
            val visiting = mutableSetOf<DbtCte>()
            fun visit(current: DbtCte) {
                require(current !in visiting) { "Recursive CTE inspection is unsupported." }
                visiting.add(current)
                val parents = analysis.ctes.filter { it.body.contains(current.range) }.toSet()
                current.dependencies.forEach { name ->
                    val candidates = analysis.ctes.filter { c ->
                        c.name.equals(name, true) && c.range.startOffset < current.range.startOffset &&
                                parents.containsAll(analysis.ctes.filter { it.body.contains(c.range) })
                    }
                    val dependency = candidates.maxByOrNull { it.range.startOffset }
                        ?: throw IllegalArgumentException("A CTE dependency is unavailable: $name")
                    visit(dependency)
                }
                visiting.remove(current); selected.add(current)
            }
            require(analysis.issues.none {
                it.lowercase().contains("recursive")
            }) { "Recursive CTE inspection is unsupported." }
            visit(cte)
            val setup = prefix(text, analysis, selected.toList(), cte)
            val name = if (DbtSyntax.isIdentifier(cte.name)) cte.name else "\"" + cte.name.replace("\"", "\"\"") + "\""
            val query = StringBuilder(setup + "with ");
            val spans = mutableListOf<DbtPreviewSpan>()
            if (setup.isNotEmpty()) spans.add(DbtPreviewSpan(0, 0, setup.length))
            selected.forEachIndexed { index, frame ->
                if (index > 0) query.append(",\n")
                spans.add(
                    DbtPreviewSpan(
                        query.length,
                        frame.range.startOffset,
                        frame.range.length
                    )
                ); query.append(frame.range.substring(text))
            }
            query.append("\nselect " + (if (count) "count(*) as full_row_count" else "*") + " from " + name)
            DbtCteQuery(query.toString(), spans)
        }

    fun context(w: DbtWorkspace, model: DbtSymbol): DbtCteContext? =
        w.owner(model)?.let(w::executionRoot)?.let { root ->
            val manager = FileDocumentManager.getInstance();
            val document = manager.getCachedDocument(model.file.virtualFile)
            val source = document?.text ?: model.file.text
            val execution = root.runOptions.values.map { java.lang.String.valueOf(it) } + listOf(
                root.directory.path,
                root.executionDirectory.toString(),
                DbtExecutionContext.activeTarget(root).orEmpty(),
                DbtExecutionContext.profiles(root).directory.toString(),
                root.config.containingFile.text,
                root.psiFile(root.profilesFile)?.let { DbtHash.sha256(it.text) }.orEmpty()
            )
            val dependencies = (w.files.filter { f ->
                w.root(f)
                    ?.let { r -> w.executionRoot(r) == root && (r.resource(f) || f.name == "dbt_project.yml" || f.name == "profiles.yml") } == true
            } + root.envFiles)
                .distinct().map {
                    it.virtualFile.path to (manager.getCachedDocument(it.virtualFile)?.modificationStamp
                        ?: it.modificationStamp)
                }.sortedBy { it.first }
            DbtCteContext(source, document?.modificationStamp ?: model.file.modificationStamp, execution, dependencies)
        }

    fun command(
        w: DbtWorkspace,
        resource: DbtSymbol,
        sql: String,
        spans: List<DbtPreviewSpan> = emptyList()
    ): Result<DbtCommand> = dbtResult {
        val owner = w.owner(resource) ?: throw IllegalArgumentException("Owning dbt project is unavailable.")
        require(w.packageOwner(owner) == null) { "Inline SQL previews cannot preserve installed-package context. Preview the complete model instead." }
        require(DbtNavigation.projectedColumns(sql) != null) { "Inline preview requires one supported SELECT query without data or schema modification." }
        val root = w.executionRoot(owner)
        require(root.supports(5)) { "CTE preview requires a CLI supporting dbt show --inline (Core 1.5+)." }
        val selected = DbtRunSelection.available(w).find { it.path == resource.file.virtualFile.path }
            ?: throw IllegalArgumentException("Choose a source model.")
        val base = DbtCommand.create(w, selected, "Preview", false).getOrThrow()
        base.copy(
            request = base.request.copy(scope = DbtCommandScope.Inline(sql), debugSql = true),
            previewSource = DbtPreviewSource(
                resource.file.virtualFile.path,
                resource.file.text,
                sql,
                spans,
                "sql_operation.${root.name}.inline_query",
                resource.file.virtualFile.url
            )
        )
    }
}

/** Source text/stamp plus execution settings and contributing file stamps used to reject stale preparation/results. */
internal data class DbtCteContext(
    val source: String,
    val stamp: Long,
    val execution: List<String>,
    val dependencies: List<Pair<String, Long>>
)

/** Only unchanged authored spans can be navigated after CTE extraction and adapter wrapping. */
internal data class DbtPreviewSpan(val queryStart: Int, val sourceStart: Int, val length: Int)
internal data class DbtCteQuery(val sql: String, val spans: List<DbtPreviewSpan>)
internal data class DbtPreviewSource(
    val path: String,
    val text: String,
    val query: String,
    val spans: List<DbtPreviewSpan>,
    val nodeId: String,
    val fileUrl: String,
    val navigationFile: VirtualFile? = null
)

internal data class DbtPreviewLocation(val source: DbtPreviewSource, val offset: Int, val line: Int, val column: Int)
/** Submitted query and optional adapter SQL/error position, attributed only to one unambiguous preview invocation. */
internal data class DbtPreviewEvidence(
    val submitted: String,
    val adapterSql: String?,
    val raw: String,
    val invocation: String?,
    val location: DbtPreviewLocation?,
    val explanation: String
) {
    val displaySql: String
        get() = (adapterSql?.let { "Adapter SQL reported by dbt (execution may have failed):\n$it" }
            ?: "Adapter SQL unavailable: this CLI/adapter did not emit one unambiguous SQLQuery event for this preview.") + "\n\nSubmitted SQL (before dbt compilation):\n" + submitted + "\n\n" + explanation

    companion object {
        private data class Event(val info: JsonObject, val data: JsonObject) {
            val name get() = DbtJson.text(info, "name").orEmpty()
            val invocation get() = DbtJson.text(info, "invocation_id").orEmpty()
            val node get() = DbtJson.child(data, "node_info")?.let { DbtJson.text(it, "unique_id") }.orEmpty()
        }

        fun capture(command: DbtCommand, output: String, failed: Boolean, canceled: Boolean): DbtPreviewEvidence {
            val submitted = (command.request.scope as? DbtCommandScope.Inline)?.sql ?: command.sourceSql
            val events = DbtPreview.Ansi.replace(output, "").lineSequence()
                .mapNotNull { dbtResult { JsonParser.parseString(it) }.getOrNull() }.filter { it.isJsonObject }
                .mapNotNull {
                    val root = it.asJsonObject;
                    val info = DbtJson.child(root, "info");
                    val data = DbtJson.child(root, "data")
                    if (info != null && data != null) Event(info, data) else null
                }.toList()
            val invocations = events.map { it.invocation }.filter { it.isNotEmpty() }.distinct()
            val invocation = invocations.singleOrNull()
                ?.takeIf { id -> events.any { it.name == "MainReportVersion" && it.invocation == id } }
            val expected = command.previewSource?.nodeId?.takeIf { it.isNotEmpty() }
            val queries = if (invocation == null) emptyList() else events.filter { e ->
                e.invocation == invocation && e.name == "SQLQuery" && expected == e.node &&
                        DbtJson.child(e.data, "node_info")?.let { DbtJson.text(it, "node_status") } == "executing"
            }.mapNotNull { DbtJson.text(it.data, "sql") }
                .filter { it.isNotEmpty() && !it.trim().endsWith("...") && DbtNavigation.projectedColumns(it) != null }
                .distinct()
            val containing = queries.filter { submitted.isNotEmpty() && it.contains(submitted) }
            val selected = containing.singleOrNull() ?: if (containing.size != 1) queries.singleOrNull() else null
            val matchingShow = events.filter { it.name == "ShowNode" }
                .any { invocation == it.invocation && expected == DbtJson.text(it.data, "unique_id").orEmpty() }
            val adapter = if (canceled || !failed && !matchingShow) null else selected
            val errors = events.filter { e ->
                invocation == e.invocation && expected == e.node && (e.name == "CatchableExceptionOnRun" || DbtJson.text(
                    e.info,
                    "level"
                ) in setOf("error", "fatal"))
            }
                .mapNotNull { DbtJson.text(it.info, "msg") ?: DbtJson.text(it.data, "exc") }.distinct()
            val source = command.previewSource;
            val positions = errors.flatMap(::errorPositions).distinct();
            val position = positions.singleOrNull()
            val offset =
                if (!failed || canceled || adapter == null || source == null || position == null) null else authoredOffset(
                    source,
                    adapter,
                    position.first,
                    position.second
                )
            val location = if (offset != null && source != null) {
                val before = source.text.take(offset); DbtPreviewLocation(
                    source,
                    offset,
                    before.count { it == '\n' } + 1,
                    offset - before.lastIndexOf('\n'))
            } else null
            val explanation = if (canceled) "Preview canceled; no SQL execution or error location is certified."
            else if (!failed) "Adapter SQL is available only when the current invocation identifies its preview statement."
            else location?.let { "Error maps to ${if (it.source.navigationFile != null) "compiled CTE SQL" else "authored CTE source"} at line ${it.line}, column ${it.column}. Navigation is disabled if the source changes." }
                ?: "Error location unavailable: an exact unchanged authored span and a supported unambiguous adapter location were not both available. See raw output."
            return DbtPreviewEvidence(submitted, adapter, output, invocation, location, explanation)
        }

        /** Recognized adapter formats only: one-based line/column and Snowflake's zero-based position. */
        fun errorPositions(message: String): List<Pair<Int, Int>> {
            val bracket = Regex("(?i)\\bat \\[([1-9][0-9]*):([1-9][0-9]*)\\]").findAll(message).mapNotNull { m ->
                val line = m.groupValues[1].toIntOrNull();
                val column =
                    m.groupValues[2].toIntOrNull(); if (line != null && column != null) line to column else null
            }
            val snowflake =
                Regex("(?i)\\bline ([1-9][0-9]*) at position ([0-9]+)\\b").findAll(message).mapNotNull { m ->
                    val line = m.groupValues[1].toIntOrNull();
                    val position =
                        m.groupValues[2].toIntOrNull(); if (line != null && position != null && position < Int.MAX_VALUE) line to position + 1 else null
                }
            return (bracket + snowflake).distinct().toList()
        }

        /**
         * Maps a one-based adapter location through one exact query occurrence and an unchanged authored span.
         * Returns null for generated/transformed text, ambiguous occurrences or unsupported Unicode column units.
         */
        fun authoredOffset(source: DbtPreviewSource, adapter: String, line: Int, column: Int): Int? {
            val query = source.query;
            val start = if (query.isNotEmpty()) adapter.indexOf(query) else -1;
            val lines = adapter.split('\n')
            if (start < 0 || adapter.indexOf(
                    query,
                    start + 1
                ) >= 0 || source.navigationFile == null && DbtSyntax.blocks(query).isNotEmpty()
            ) return null
            val row = lines.getOrNull(line - 1) ?: return null
            if (line <= 0 || column <= 0 || column > row.length || row.take(column).any { it.code > 127 }) return null
            val adapterOffset = lines.take(line - 1).sumOf { it.length + 1 } + column - 1;
            val queryOffset = adapterOffset - start
            val span =
                source.spans.find { queryOffset >= it.queryStart && queryOffset < it.queryStart.toLong() + it.length }
                    ?: return null
            val sourceOffset = span.sourceStart + queryOffset - span.queryStart
            if (span.length < 0 || span.queryStart < 0 || span.sourceStart < 0 || span.queryStart.toLong() + span.length > query.length || span.sourceStart.toLong() + span.length > source.text.length ||
                query.substring(
                    span.queryStart,
                    span.queryStart + span.length
                ) != source.text.substring(span.sourceStart, span.sourceStart + span.length)
            ) return null
            return sourceOffset
        }
    }
}

/** Shared by the Runner and CTE inspector; failed queries have no result grid to hold these controls. */
internal class DbtPreviewEvidencePanel(private val project: Project) : JPanel(BorderLayout()) {
    private var evidence: DbtPreviewEvidence? = null
    private val label = JBLabel().apply { putClientProperty("html.disable", true) }
    private val details = DbtUi.button("Open preview SQL") {
        evidence?.let { e ->
            DbtSqlEditors.open(project, "dbt submitted preview", e.submitted); e.adapterSql?.let {
            DbtSqlEditors.open(
                project,
                "dbt adapter preview",
                it
            )
        }
        }
    }
    private val navigate: JButton = DbtUi.button("Go to CTE error") { navigateError() }

    init {
        add(DbtUi.buttons(details, navigate), BorderLayout.NORTH); add(label, BorderLayout.CENTER); showEvidence(null)
    }

    private fun navigateError() {
        WriteIntentReadAction.run {
            evidence?.location?.let { at ->
                val file = at.source.navigationFile?.takeIf { it.isValid } ?: VirtualFileManager.getInstance()
                    .findFileByUrl(at.source.fileUrl)
                val current = file?.let { FileDocumentManager.getInstance().getDocument(it) }
                if (current?.text == at.source.text && file != null) {
                    val descriptor = OpenFileDescriptor(project, file, at.offset)
                    FileEditorManager.getInstance(project).openTextEditor(descriptor, true)?.let(descriptor::navigateIn)
                } else {
                    navigate.isEnabled = false; label.text =
                        "Source changed; rerun the preview before navigating to its error."; label.toolTipText =
                        label.text
                }
            }
        }
    }

    fun showEvidence(value: DbtPreviewEvidence?) {
        evidence = value; details.isEnabled = value != null; navigate.isEnabled = value?.location != null; label.text =
            value?.explanation.orEmpty(); label.toolTipText = label.text
    }
}
