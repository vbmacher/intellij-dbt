package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.navigation.DbtNavigation
import com.github.vbmacher.intellij.dbt.project.*
import com.github.vbmacher.intellij.dbt.syntax.DbtSqlText
import com.github.vbmacher.intellij.dbt.syntax.DbtSyntax
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import java.awt.datatransfer.DataFlavor
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Converts between authored dbt SQL and physical relations using verified metadata for the selected execution context.
 * Unsupported Jinja is preserved in plugin-owned comments for a reversible copy/paste round trip.
 */
internal object DbtClipboard {
    private const val Prefix = "/* dbt-workspace-jinja:"
    private fun comment(text: String) =
        Prefix + Base64.getEncoder().encodeToString(text.toByteArray(StandardCharsets.UTF_8)) + " */"

    private fun edits(text: String, replacements: List<Pair<TextRange, String>>) =
        replacements.sortedByDescending { it.first.startOffset }
            .fold(text) { result, (range, value) ->
                result.substring(0, range.startOffset) + value + result.substring(
                    range.endOffset
                )
            }

    /** Rejects absent, unsupported, mismatched or relation-free manifests before any physical-name conversion. */
    fun ready(root: DbtRoot): Result<Unit> {
        val w = DbtWorkspace.get(root.config.project)
        val error = when {
            root.setting("manifest")
                ?.let(root::psiFile) == null -> "Generate Docs for this project and target to load physical relation metadata."

            !DbtDeclarationSource.EP.extensionList.all {
                it.canReportMissing(
                    w,
                    root
                )
            } -> "The manifest is malformed or unsupported. Generate Docs with a supported dbt version before converting SQL."

            !DbtArtifactContext.matches(root) -> "The manifest has no verified context for the selected project/target. Generate Docs with the dbt Runner before converting SQL."
            w.symbols.none { s ->
                s.kind in DbtSymbol.RelationKinds && w.owner(s)?.let { w.executionRoot(it) == root } == true &&
                        DbtResourceData.get(w, s).attributes["relation_name"]?.isNotEmpty() == true
            } -> "The manifest contains no usable physical relations for this project. Generate Docs before converting SQL."

            else -> null
        }
        return if (error == null) Result.success(Unit) else Result.failure(IllegalArgumentException(error))
    }

    fun copy(file: PsiFile, range: TextRange): Result<String> {
        val w = DbtWorkspace.get(file.project)
        val root = w.root(file)?.let(w::executionRoot)
            ?: return Result.failure(IllegalArgumentException("Open a file in a dbt project."))
        ready(root).exceptionOrNull()?.let { return Result.failure(it) }
        val full = file.text
        val refs = DbtNavigation.inFile(file)
        val text = range.substring(full)
        val changes = DbtSyntax.blocks(full)
            .filter { range.contains(it.range) && !DbtSqlText.inactive(full, it.range.startOffset) }.map { block ->
            val candidates = refs.filter { block.range.contains(it.rangeInElement) }.flatMap { it.candidates }
                .filterIsInstance<DbtSymbolElement>()
                .filter { it.symbol.kind in DbtSymbol.RelationKinds }.map { it.symbol }.distinctBy { it.id }
            val tokens = block.tokens
            val calls = DbtSyntax.calls(block)
            val isRelation =
                !block.tag && block.range.substring(full).endsWith("}}") && ((calls.size == 1 && calls.first().closed &&
                        calls.first().expressionRange == TextRange(
                    tokens.first().range.startOffset,
                    tokens.last().range.endOffset
                ) &&
                        calls.first().name in setOf(
                    "ref",
                    "source",
                    "builtins.ref",
                    "builtins.source"
                )) || tokens.map { it.image } == listOf("this"))
            val physical = if (isRelation && candidates.size == 1) DbtResourceData.get(
                w,
                candidates.first()
            ).attributes["relation_name"] else null
            TextRange(
                block.range.startOffset - range.startOffset,
                block.range.endOffset - range.startOffset
            ) to (physical ?: comment(block.range.substring(full)))
        }
        return Result.success(edits(text, changes))
    }

    /** Rewrites uniquely matched relation ranges, leaving ambiguous names, quoted SQL strings and ordinary comments intact. */
    fun paste(w: DbtWorkspace, root: DbtRoot, text: String): Result<String> {
        ready(root).exceptionOrNull()?.let { return Result.failure(it) }
        fun parts(relation: String): List<String> = DbtSqlText.tokens(relation).filter { it.identifier }.flatMap { t ->
            val value = t.text
            if (value.startsWith("\"") || value.startsWith("`") || value.startsWith("[")) {
                val decoded = value.drop(1).dropLast(1).replace("\"\"", "\"").replace("``", "`").replace("]]", "]")
                if (value.startsWith("`")) decoded.split(".") else listOf(decoded)
            } else listOf(value.lowercase(java.util.Locale.ROOT))
        }

        val resources = w.symbols.filter { s ->
            s.kind in DbtSymbol.RelationKinds && !s.isProperty &&
                    w.declarations(setOf(s.kind), s.name, root, s.namespace).any { it.id == s.id }
        }.distinctBy { it.id }
        val relations =
            resources.mapNotNull { s -> DbtResourceData.get(w, s).attributes["relation_name"]?.let { parts(it) to s } }
        val changes = DbtNavigation.relationRanges(text).mapNotNull { range ->
            val name = parts(range.substring(text))
            val matches = if (name.size < 2 || name.size > 3) emptyList() else relations.filter { (relation, _) ->
                relation == name || name.size == 2 && relation.takeLast(2) == name
            }
                .map { it.second }.distinctBy { it.id }
            if (matches.size != 1) null else {
                val s = matches.first()
                fun quote(value: String) = "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"
                val call = if (s.kind == "source_table") "source(${quote(s.owner)}, ${quote(s.name)})"
                else "ref(" + (if (s.namespace != root.name) quote(s.namespace) + ", " else "") + quote(s.name) +
                        (s.version?.let { ", v=" + quote(it) } ?: "") + ")"
                range to "{{ $call }}"
            }
        }
        val converted = edits(text, changes)
        val restores = DbtSqlText.tokens(converted)
            .filter { it.protectedText && it.text.startsWith(Prefix) && it.text.endsWith(" */") }.mapNotNull { token ->
            val decoded = try {
                String(
                    Base64.getDecoder().decode(token.text.removePrefix(Prefix).removeSuffix(" */")),
                    StandardCharsets.UTF_8
                )
            } catch (_: IllegalArgumentException) {
                null
            }
            decoded?.takeIf { s -> DbtSyntax.blocks(s).any { it.range == TextRange(0, s.length) } }
                ?.let { token.range to it }
        }
        return Result.success(edits(converted, restores))
    }
}

abstract class DbtClipboardAction(private val copying: Boolean) : DbtEditorAction() {
    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible =
            DbtEditorAction.sql(event, editor = true) && DbtEditorAction.editorContext(event)
                ?.let { (project, file, _) ->
                    file.virtualFile?.let { f -> DbtWorkspace.rootForFile(project, f)?.let { !it.excluded(f) } }
                } == true
    }

    override fun actionPerformed(event: AnActionEvent) {
        val (project, file, editor) = DbtEditorAction.editorContext(event) ?: return
        val document = editor.document
        val stamp = document.modificationStamp
        val selection = editor.selectionModel
        val start =
            if (selection.hasSelection()) selection.selectionStart else if (copying) 0 else editor.caretModel.offset
        val end = if (selection.hasSelection()) selection.selectionEnd else if (copying) document.textLength else start
        val clipboard: String? =
            if (copying) null else CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor)
        if (!copying && clipboard == null) return
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        DbtUi.readLater(project, smartIn = project, read = {
            if (copying) DbtClipboard.copy(file, TextRange(start, end)) else {
                val w = DbtWorkspace.get(project)
                val root = w.root(file)?.let(w::executionRoot)
                if (root == null) Result.failure(IllegalArgumentException("Open a file in a dbt project.")) else DbtClipboard.paste(
                    w,
                    root,
                    clipboard.orEmpty()
                )
            }
        }) { result ->
            if (document.modificationStamp == stamp && !editor.isDisposed) result.fold(onFailure = {
                com.intellij.codeInsight.hint.HintManager.getInstance().showErrorHint(editor, it.message.orEmpty())
            }, onSuccess = { text ->
                if (copying) DbtUi.copy(text) else WriteCommandAction.runWriteCommandAction(
                    project,
                    "Paste as dbt Refs",
                    null,
                    Runnable {
                        document.replaceString(start, end, text)
                        editor.selectionModel.removeSelection()
                        editor.caretModel.moveToOffset(start + text.length)
                    })
            })
        }
    }
}

class DbtCopyForTargetAction : DbtClipboardAction(copying = true)
class DbtPasteAsRefsAction : DbtClipboardAction(copying = false)
