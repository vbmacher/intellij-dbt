package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.navigation.DbtSqlAnalysis
import com.github.vbmacher.intellij.dbt.navigation.DbtSqlSuppressionComment as Comment
import com.github.vbmacher.intellij.dbt.navigation.DbtSqlSuppressionLayout as Layout
import com.github.vbmacher.intellij.dbt.project.*
import com.github.vbmacher.intellij.dbt.syntax.DbtSyntax
import com.github.vbmacher.intellij.dbt.syntax.DbtYaml
import com.intellij.codeInspection.*
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile

/**
 * Checks unknown macro keyword names and conservative SQL/model-contract evidence through native inspections.
 * Documented macro parameter types are display metadata, not argument-type validation.
 */
class DbtStaticInspection : LocalInspectionTool(), CustomSuppressableInspectionTool {
    override fun getID() = DbtStaticSuppression.Id
    override fun getSuppressActions(element: PsiElement?): Array<SuppressIntentionAction> =
        DbtStaticSuppression.actions(element)

    override fun isSuppressedFor(element: PsiElement): Boolean =
        element?.containingFile?.let(DbtStaticSuppression::fileSuppressed) == true

    private fun acceptsUnknownKeywords(macroSymbol: DbtSymbol): Boolean {
        val blocks = DbtSyntax.inFile(macroSymbol.file)
        val declaration =
            blocks.firstOrNull { it.isTag("macro") && it.range.contains(macroSymbol.range) } ?: return false
        val end = blocks.firstOrNull { it.isTag("endmacro") && it.range.startOffset > declaration.range.startOffset }
            ?.range?.startOffset ?: declaration.range.endOffset
        return blocks.filter { it.range.startOffset >= declaration.range.endOffset && it.range.startOffset < end }
            .any { b -> b.tokens.any { !it.string && it.image == "kwargs" } }
    }

    override fun buildVisitor(holder: ProblemsHolder, onTheFly: Boolean): PsiElementVisitor =
        object : PsiElementVisitor() {
            override fun visitFile(file: PsiFile) {
                val w = DbtWorkspace.get(file.project)
                val root = w.root(file)?.takeIf { it.resource(file) && !it.excluded(file.virtualFile) } ?: return
                val suppressed = DbtStaticSuppression.ranges(file)
                fun report(message: String, range: TextRange) {
                    if (suppressed.none { it.contains(range) }) holder.registerProblem(
                        file,
                        message,
                        ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                        range
                    )
                }
                DbtSyntax.inFile(file).flatMap(DbtSyntax::calls).filter { it.closed }.forEach { call ->
                    val macros = w.macros(call, root)
                    if (macros.size == 1 && call.arguments.all { a ->
                            a.tokens.none {
                                it.image in setOf(
                                    "*",
                                    "**"
                                )
                            }
                        }) {
                        val parameters = DbtCompletion.parameters(w, macros.first()).map { it.first }
                        val knownSignature = parameters.isNotEmpty() || DbtSyntax.inFile(macros.first().file)
                            .any { it.isTag("macro") && it.range.contains(macros.first().range) }
                        if (knownSignature && !acceptsUnknownKeywords(macros.first())) call.arguments.forEach { arg ->
                            val name = arg.keyword
                            val range = arg.keywordRange
                            if (name != null && name !in parameters && range != null)
                                report(
                                    "Unknown macro argument '$name'; expected ${parameters.joinToString(", ")}",
                                    range
                                )
                        }
                    }
                }
                DbtSqlAnalysis.get(w, file)?.let { analysis ->
                    analysis.problems.forEach { report(it.message, it.range) }
                    w.codeSymbols(file).filter { it.kind == "model" }.forEach { resource ->
                        val enforced =
                            w.properties(resource).mapNotNull { it.mapping }.mapNotNull { DbtYaml.map(it, "config") }
                                .mapNotNull { DbtYaml.map(it, "contract") }
                                .any { DbtYaml.string(it, "enforced") == "true" }
                        if (enforced && analysis.issues.isEmpty() && analysis.columns.all { it.complete }) {
                            val declared = w.columns(resource).filter { it.mapping != null && !it.metadata }
                                .associate { it.name to it.attributes["data_type"].orEmpty().lowercase() }

                            fun family(t: String): String =
                                when (val base = t.lowercase().takeWhile { it != '(' && it != ' ' }) {
                                    "varchar", "char", "text", "string" -> "string"
                                    "int", "integer", "bigint", "smallint" -> "integer"
                                    "numeric", "decimal" -> "decimal"
                                    else -> base
                                }
                            analysis.columns.forEach { c ->
                                if (c.name !in declared) report(
                                    "Model contract: output '${c.name}' has no column declaration.",
                                    c.range
                                )
                                val actual = c.dataType
                                val expected = declared[c.name]
                                if (actual != null && !expected.isNullOrEmpty() && family(actual) != family(expected) &&
                                    family(actual) in setOf(
                                        "string",
                                        "integer",
                                        "decimal"
                                    ) && family(expected) in setOf("string", "integer", "decimal")
                                )
                                    report(
                                        "Model contract: '${c.name}' declares $expected but resolves to $actual.",
                                        c.range
                                    )
                            }
                        }
                    }
                }
            }
        }
}

/** Native suppression actions share the SQL statement boundaries used to filter authored diagnostics. */
internal object DbtStaticSuppression {
    const val Id = "DbtStaticAnalysis"
    private fun layout(file: PsiFile): Layout = DbtSqlAnalysis.suppressionLayout(file)
    private fun supported(file: PsiFile): Boolean {
        if (file.virtualFile?.extension != "sql") return false
        val w = DbtWorkspace.get(file.project)
        return w.root(file)?.let { it.resource(file) && !it.excluded(file.virtualFile) } == true
    }

    private fun mentions(comment: Comment) = SuppressionUtil.isInspectionToolIdMentioned(comment.ids, Id)
    private fun fileSuppressed(data: Layout) = data.comments.any { c ->
        c.file && mentions(c) &&
                (data.statements.firstOrNull()?.let { c.range.endOffset <= it.first } ?: true)
    }

    fun fileSuppressed(file: PsiFile): Boolean = supported(file) && fileSuppressed(layout(file))
    fun ranges(file: PsiFile): List<TextRange> {
        if (!supported(file)) return emptyList()
        val data = layout(file)
        return if (fileSuppressed(data)) listOf(TextRange(0, file.textLength))
        else data.statements.filter { s ->
            data.comments.any { c ->
                !c.file && mentions(c) &&
                        c.range.startOffset >= s.range.startOffset && c.range.endOffset <= s.first
            }
        }.map { it.range }
    }

    fun actions(element: PsiElement?): Array<SuppressIntentionAction> =
        if (element?.containingFile?.let(::supported) == true) arrayOf(
            Action(false),
            Action(true)
        ) else SuppressIntentionAction.EMPTY_ARRAY

    private class Action(private val wholeFile: Boolean) : SuppressIntentionAction() {
        init {
            text = if (wholeFile) "Suppress for file" else "Suppress for statement"
        }

        override fun getFamilyName() = "Suppress dbt SQL, macro arguments and contracts"
        override fun getElementToMakeWritable(file: PsiFile): PsiElement = file
        override fun isAvailable(project: Project, editor: Editor?, element: PsiElement): Boolean =
            editor != null && element.containingFile?.let(::supported) == true

        override fun invoke(project: Project, editor: Editor, element: PsiElement) {
            val file = element.containingFile
            val document = editor.document
            if (wholeFile) document.insertString(0, "--file: noinspection $Id\n")
            else layout(file).statements.firstOrNull { it.range.containsOffset(editor.caretModel.offset) }
                ?.let { statement ->
                    val lineStart = document.getLineStartOffset(document.getLineNumber(statement.first))
                    val indentation = document.getText(TextRange(lineStart, statement.first))
                    if (indentation.all(Char::isWhitespace)) document.insertString(
                        lineStart,
                        "$indentation-- noinspection $Id\n"
                    )
                    else document.insertString(statement.first, "\n-- noinspection $Id\n")
                }
        }
    }
}
