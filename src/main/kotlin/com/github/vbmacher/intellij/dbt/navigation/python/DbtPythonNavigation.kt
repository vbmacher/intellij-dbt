package com.github.vbmacher.intellij.dbt.navigation.python

import com.github.vbmacher.intellij.dbt.navigation.*
import com.github.vbmacher.intellij.dbt.project.DbtConfiguration
import com.github.vbmacher.intellij.dbt.project.DbtSymbol
import com.github.vbmacher.intellij.dbt.syntax.DbtPython
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement

/**
 * Handles literal dbt calls in Python models through the optional Python PSI adapter.
 * The receiver must resolve to the `dbt` parameter of `model` or an assignment alias proved within eight steps;
 * unproved receivers and interpolated arguments do not become resource references.
 */
class DbtPythonNavigation : DbtReferenceProvider {
    private fun ancestor(element: PsiElement, simpleName: String): PsiElement? =
        generateSequence(element.parent) { it.parent }.find { it.javaClass.simpleName == simpleName }

    private fun modelParameter(expression: PsiElement, visited: Set<PsiElement> = emptySet()): Boolean {
        if (expression in visited || visited.size >= 8) return false
        val target = expression.reference?.resolve() ?: return false
        return when (target.javaClass.simpleName) {
            "PyNamedParameterImpl" -> DbtPython.invoke(target, "getName") == "dbt" && ancestor(
                target,
                "PyFunctionImpl"
            )?.let { DbtPython.invoke(it, "getName") == "model" } == true

            "PyTargetExpressionImpl" -> ancestor(target, "PyAssignmentStatementImpl")?.let {
                DbtPython.psi(
                    it,
                    "getAssignedValue"
                )
            }?.let { modelParameter(it, visited + expression) } == true

            else -> false
        }
    }

    private fun literalRange(element: PsiElement): TextRange {
        val source = element.text;
        val quote = source.indexOfFirst { it == '\'' || it == '"' }
        val triple = quote >= 0 && source.drop(quote).startsWith(source[quote].toString().repeat(3));
        val margin = if (triple) 3 else 1
        return if (quote >= 0 && !source.take(quote).lowercase()
                .contains('f')
        ) TextRange(element.textOffset + quote + margin, element.textRange.endOffset - margin) else element.textRange
    }

    override fun references(context: DbtNavigationContext): List<DbtNavigationReference> {
        val file = context.file;
        val root = context.root;
        val workspace = context.workspace
        if (root.kind(file.virtualFile) != "model" || file.virtualFile.extension != "py" || !DbtPython.isPython(file)) return emptyList()
        val result = mutableListOf<DbtNavigationReference>()
        fun add(e: PsiElement, name: String, targets: List<PsiElement>) {
            result += DbtNavigationReference(file, literalRange(e), targets, name)
        }

        fun walk(e: PsiElement) {
            ProgressManager.checkCanceled()
            if (e.javaClass.simpleName == "PyCallExpressionImpl") {
                val callee = DbtPython.psi(e, "getCallee")
                val arguments =
                    (DbtPython.invoke(e, "getArguments") as? Array<*>)?.filterIsInstance<PsiElement>().orEmpty()
                val name = callee?.text.orEmpty();
                val operation = name.split('.').drop(1).joinToString(".")
                val objectExpression = callee?.let { DbtPython.psi(it, "getQualifier") }?.let { qualifier ->
                    if (operation in setOf(
                            "config",
                            "config.get"
                        ) && qualifier.text.endsWith(".config")
                    ) DbtPython.psi(qualifier, "getQualifier") else qualifier
                }
                val positional = arguments.filterNot { it.javaClass.simpleName == "PyKeywordArgumentImpl" }
                val literals = positional.map { arg ->
                    (DbtPython.invoke(
                        arg,
                        "getStringValue"
                    ) as? String)?.takeUnless { DbtPython.invoke(arg, "isInterpolated") == true }?.let { arg to it }
                }
                val version = arguments.filter { it.javaClass.simpleName == "PyKeywordArgumentImpl" }
                    .find { DbtPython.invoke(it, "getName")?.toString() in setOf("v", "version") }
                    ?.let { DbtPython.psi(it, "getValueExpression") }
                    ?.let { DbtPython.invoke(it, "getStringValue") as? String ?: it.text }
                if (objectExpression?.let { modelParameter(it) } == true && operation in setOf(
                        "ref",
                        "source",
                        "config",
                        "config.get"
                    )) when {
                    operation == "ref" && literals.isNotEmpty() && literals.all { it != null } -> {
                        val ns = if (literals.size == 2) literals.first()?.second else null;
                        val selected = literals.last()!!
                        add(
                            selected.first,
                            selected.second,
                            DbtResolution.versioned(
                                workspace.declarations(
                                    DbtSymbol.RefKinds,
                                    selected.second,
                                    root,
                                    ns
                                ), version
                            ).map { it.element })
                    }

                    operation == "source" && literals.size == 2 && literals.all { it != null } -> {
                        val source = literals[0]!!;
                        val table = literals[1]!!
                        add(
                            source.first,
                            source.second,
                            workspace.declarations(setOf("source"), source.second, root).map { it.element })
                        add(
                            table.first,
                            table.second,
                            DbtResolution.sourceTables(workspace, root, source.second, table.second).map { it.element })
                    }

                    operation == "config.get" -> literals.firstOrNull()
                        ?.let { (host, key) -> add(host, key, DbtConfiguration.locations(workspace, file, key)) }

                    operation == "config" -> arguments.filter { it.javaClass.simpleName == "PyKeywordArgumentImpl" }
                        .forEach { argument ->
                            DbtPython.invoke(argument, "getName")?.let { key ->
                                result += DbtNavigationReference(
                                    file,
                                    TextRange(argument.textOffset, argument.textOffset + key.toString().length),
                                    DbtConfiguration.locations(workspace, file, key.toString()),
                                    key.toString()
                                )
                            }
                        }
                }
            }
            e.children.forEach(::walk)
        }
        walk(file)
        return result
    }
}
