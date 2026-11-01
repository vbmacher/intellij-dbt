package com.github.vbmacher.intellij.dbt.syntax

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import org.jetbrains.yaml.psi.*

/** Null-safe PSI helpers for YAML and project files. */
object DbtYaml {
    fun psiFile(project: Project, file: VirtualFile?): PsiFile? =
        file?.let { PsiManager.getInstance(project).findFile(it) }

    fun mapping(file: PsiFile): YAMLMapping? =
        (file as? YAMLFile)?.documents?.firstOrNull()?.topLevelValue as? YAMLMapping

    fun keyValue(m: YAMLMapping, key: String): YAMLKeyValue? = m.getKeyValueByKey(key)
    fun value(m: YAMLMapping, key: String): YAMLValue? = keyValue(m, key)?.value
    fun map(m: YAMLMapping, key: String): YAMLMapping? = value(m, key) as? YAMLMapping
    fun string(m: YAMLMapping, key: String): String? = (value(m, key) as? YAMLScalar)?.textValue
    fun elements(m: YAMLMapping, key: String): List<YAMLValue> =
        (value(m, key) as? YAMLSequence)?.items?.mapNotNull { it.value }.orEmpty()

    fun items(m: YAMLMapping, key: String): List<YAMLMapping> = elements(m, key).filterIsInstance<YAMLMapping>()
    fun strings(m: YAMLMapping, key: String, default: List<String> = emptyList()): List<String> =
        when (val v = value(m, key)) {
            is YAMLSequence -> elements(m, key).filterIsInstance<YAMLScalar>().map { it.textValue }
            is YAMLScalar -> listOf(v.textValue)
            null -> default
            else -> emptyList()
        }

    fun nameRange(m: YAMLMapping, key: String = "name"): TextRange = value(m, key)?.textRange ?: m.textRange
    /** Collects scalar attributes and tags; nested `config` values override attributes at the same level. */
    fun attributes(m: YAMLMapping): Map<String, String> = m.keyValues.mapNotNull { k ->
        when (val v = k.value) {
            is YAMLScalar -> k.keyText to v.textValue
            is YAMLSequence -> if (k.keyText.removePrefix("+") == "tags") k.keyText to strings(
                m,
                k.keyText
            ).joinToString(",") else null

            else -> null
        }
    }.toMap() + (map(m, "config")?.let(::attributes) ?: emptyMap())

    fun ancestors(element: PsiElement): List<String> =
        generateSequence(element) { it.parent }.filterIsInstance<YAMLKeyValue>().map { it.keyText }.toList()

    fun parentMapping(element: PsiElement): YAMLMapping? =
        generateSequence(element.parent) { it.parent }.filterIsInstance<YAMLMapping>().firstOrNull()

    fun key(scalar: YAMLScalar): String = (scalar.parent as? YAMLKeyValue)?.keyText.orEmpty()
    /** Decodes a YAML scalar and maps decoded offsets back to absolute PSI offsets; returns null if decoding fails. */
    fun decoded(scalar: YAMLScalar): Pair<String, (Int) -> Int>? {
        val escaper = scalar.createLiteralTextEscaper()
        val host = TextRange(0, scalar.textLength)
        val text = StringBuilder()
        return if (escaper.decode(
                host,
                text
            )
        ) text.toString() to { i: Int -> scalar.textOffset + escaper.getOffsetInHost(i, host) } else null
    }
}

/** Optional Python Community PSI, accessed reflectively without linking Python APIs. */
object DbtPython {
    fun isPython(file: PsiFile): Boolean = file.javaClass.name.startsWith("com.jetbrains.python.")
    /** Calls optional Python PSI APIs. Missing APIs and invocation failures return null; platform cancellation propagates. */
    fun invoke(element: Any, method: String): Any? = try {
        element.javaClass.getMethod(method).invoke(element)
    } catch (e: java.lang.reflect.InvocationTargetException) {
        val cause = e.cause
        if (cause is ProcessCanceledException) throw cause
        null
    } catch (_: ReflectiveOperationException) {
        null
    }

    fun psi(element: Any, method: String): PsiElement? = invoke(element, method) as? PsiElement
    fun declarations(file: PsiFile, name: String): List<PsiElement> {
        if (!isPython(file)) return emptyList()
        val result = mutableListOf<PsiElement>()
        fun walk(e: PsiElement) {
            ProgressManager.checkCanceled()
            if (e.javaClass.simpleName in setOf("PyFunctionImpl", "PyTargetExpressionImpl") && invoke(
                    e,
                    "getName"
                ) == name
            )
                psi(e, "getNameIdentifier")?.let(result::add)
            e.children.forEach(::walk)
        }
        walk(file)
        return result
    }
}
