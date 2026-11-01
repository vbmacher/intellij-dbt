package com.github.vbmacher.intellij.dbt.project

import com.github.vbmacher.intellij.dbt.syntax.DbtSyntax
import com.github.vbmacher.intellij.dbt.syntax.DbtYaml
import com.intellij.lang.Language
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.TextRange
import com.intellij.psi.impl.light.LightElement
import com.intellij.psi.*
import com.intellij.util.IncorrectOperationException
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLSequence
import javax.swing.Icon

/** A dbt declaration; code, property and stale attributes identify its source and provenance. */
data class DbtSymbol(
    val kind: String, val name: String, val namespace: String, val file: PsiFile,
    val range: TextRange, val owner: String = "", val version: String? = null,
    val attributes: Map<String, String> = emptyMap(), val mapping: YAMLMapping? = null, val metadata: Boolean = false
) {
    /** Identity of this declaration location; distinct source and property declarations can describe one logical resource. */
    val id: String get() = "${file.virtualFile.path}:${range.startOffset}:$kind:$name:$owner:${version.orEmpty()}"
    val element: DbtSymbolElement by lazy { DbtSymbolElement(this) }
    val isCode: Boolean get() = "code" in attributes
    val isProperty: Boolean get() = "property" in attributes
    val isTestOnlyProperty: Boolean
        get() = isProperty && kind in RefKinds && mapping?.let { m ->
            fun onlyKeys(mapping: YAMLMapping, keys: Set<String>): Boolean =
                mapping.keyValues.all { it.keyText in keys }

            fun hasTests(mapping: YAMLMapping): Boolean =
                TestKeys.any { (DbtYaml.value(mapping, it) as? YAMLSequence)?.items?.isNotEmpty() == true }

            val columns = DbtYaml.items(m, "columns")
            onlyKeys(m, TestKeys + setOf("name", "columns")) && columns.all {
                onlyKeys(
                    it,
                    TestKeys + "name"
                ) && hasTests(it)
            } && (hasTests(m) || columns.isNotEmpty())
        } == true
    val isStale: Boolean get() = attributes["stale"] == "true"
    val isEnabled: Boolean get() = !attributes["enabled"].equals("false", ignoreCase = true)
    /** Artifact resource identity, using an explicit `unique_id` when available and a kind/namespace/name/version fallback otherwise. */
    val uniqueId: String
        get() = attributes["unique_id"] ?: run {
            val resource = if (kind == "source_table") "source" else kind
            val qualified = if (kind == "unit_test") mapping?.let { DbtYaml.string(it, "model") }?.let { "$it.$name" }
                ?: relationName else relationName
            "$resource.$namespace.$qualified${version?.let { ".v$it" }.orEmpty()}"
        }
    val relationName: String get() = if (kind == "source_table") "$owner.$name" else name
    /** Compares logical names and versions, not project roots. Callers spanning roots must also compare workspace ownership. */
    fun sameResource(other: DbtSymbol): Boolean =
        kind == other.kind && namespace == other.namespace && name == other.name && version == other.version && (kind !in setOf(
            "source_table",
            "column"
        ) || owner == other.owner)

    val metadataSuffix: String
        get() = when {
            "settings" in attributes -> " (IntelliJ settings)"; isStale -> " (stale metadata)"; metadata -> " (metadata)"; else -> ""
        }
    val presentableText: String get() = "$namespace.$name${version?.let { " v$it" }.orEmpty()}$metadataSuffix"

    companion object {
        val RefKinds = setOf("model", "seed", "snapshot")
        val RelationKinds = RefKinds + "source_table"
        val ColumnKinds = setOf("column", "sql_projection")
        val TestKeys = setOf("tests", "data_tests")
    }
}

/** A source declaration with stable identity, including plain-text Community files. */
class DbtSymbolElement internal constructor(val symbol: DbtSymbol) : LightElement(symbol.file.manager, SymbolLanguage),
    PsiNamedElement {
    private val pointer =
        SmartPointerManager.getInstance(symbol.file.project).createSmartPsiFileRangePointer(symbol.file, symbol.range)
    private var deletion: SmartPsiFileRange? = null
    private var insertOutputAlias = false
    fun prepareOutputAliasRename() {
        insertOutputAlias = true
    }

    fun prepareSourceDeletion(range: TextRange) {
        deletion = SmartPointerManager.getInstance(project).createSmartPsiFileRangePointer(symbol.file, range)
    }

    private fun edit(change: (Document) -> Unit) {
        val documents = PsiDocumentManager.getInstance(project)
        val document = documents.getDocument(symbol.file) ?: return
        change(document); documents.commitDocument(document)
    }

    override fun getName(): String = symbol.name
    override fun getIcon(flags: Int): Icon = icon(symbol.kind)
    override fun getPresentation(): ItemPresentation = object : ItemPresentation {
        override fun getPresentableText(): String = symbol.presentableText
        override fun getLocationString(): String = symbol.file.name
        override fun getIcon(unused: Boolean): Icon = icon(symbol.kind)
    }

    /** Edits only writable authored definitions; metadata and installed-package definitions reject rename operations. */
    override fun setName(name: String): PsiElement {
        if (!isWritable || !DbtSyntax.isIdentifier(name)) throw IncorrectOperationException("Choose a writable source definition and a valid dbt identifier.")
        if (symbol.isCode) {
            if ("defined_in" !in symbol.attributes) symbol.file.name =
                name + symbol.version?.let { "_v$it" }.orEmpty() + "." + symbol.file.virtualFile.extension
        } else edit { document ->
            if (insertOutputAlias || "insert-output-alias" in symbol.attributes) document.insertString(
                textRange.endOffset,
                " as $name"
            )
            else {
                val existing = document.getText(textRange)
                document.replaceString(
                    textRange.startOffset,
                    textRange.endOffset,
                    quote(existing)?.let { "$it$name$it" } ?: name)
            }
        }
        return this
    }

    override fun delete() {
        if (!isWritable) throw IncorrectOperationException("Choose a writable source definition.")
        if (deletion != null) deletion?.range?.let { range ->
            edit {
                it.deleteString(
                    range.startOffset,
                    range.endOffset
                )
            }
        }
        else if (symbol.isCode) symbol.file.delete()
        else if (symbol.isProperty || symbol.kind == "column" && symbol.mapping != null) symbol.mapping?.parent?.delete()
        else if (symbol.kind == "macro") DbtSyntax.tagged(DbtSyntax.inFile(symbol.file), "macro", textRange)
            ?.let { (a, b) -> edit { it.deleteString(a.range.startOffset, b.range.endOffset) } }
        else throw IncorrectOperationException("Safe deletion of this SQL definition requires an explicit projection edit.")
    }

    override fun getContainingFile(): PsiFile = symbol.file
    override fun getTextRange(): TextRange =
        pointer.range?.let { TextRange(it.startOffset, it.endOffset) } ?: TextRange.EMPTY_RANGE

    override fun getTextOffset(): Int = textRange.startOffset
    override fun getText(): String = symbol.name
    override fun getParent(): PsiElement = symbol.file
    override fun isValid(): Boolean = symbol.file.isValid && pointer.range != null
    override fun isWritable(): Boolean =
        !symbol.metadata && symbol.file.isWritable && DbtWorkspace.get(project).owner(symbol)
            ?.let { DbtWorkspace.get(project).packageOwner(it) == null } == true

    override fun navigate(requestFocus: Boolean) {
        if (symbol.kind == "documentation") project.getService(DbtDocumentationNavigation::class.java).show(this)
        else OpenFileDescriptor(project, symbol.file.virtualFile, textOffset).navigate(requestFocus)
    }

    override fun canNavigate(): Boolean = isValid
    override fun canNavigateToSource(): Boolean = isValid && !symbol.metadata
    override fun toString(): String = "dbt ${symbol.kind}: ${symbol.namespace}.${symbol.name}${symbol.metadataSuffix}"
    override fun equals(other: Any?): Boolean = other is DbtSymbolElement && symbol.id == other.symbol.id
    override fun hashCode(): Int = symbol.id.hashCode()

    companion object {
        fun quote(text: String): Char? =
            text.firstOrNull()?.takeIf { it in setOf('"', '\'', '`') && text.lastOrNull() == it }

        private val SymbolLanguage: Language = object : Language("dbt-symbol") {}
        fun icon(kind: String): Icon = when (kind) {
            "model", "source", "source_table", "information_schema_view", "cte" -> DbtIcons.Table
            "seed" -> DbtIcons.Seed
            "snapshot" -> DbtIcons.Snapshot
            "analysis", "saved_query" -> DbtIcons.Query
            "macro" -> DbtIcons.Macro
            "builtin" -> DbtIcons.Jinja
            "function" -> DbtIcons.Function
            "test", "generic_test", "unit_test", "fixture" -> DbtIcons.Test
            "check" -> DbtIcons.Check
            "doc", "documentation" -> DbtIcons.Doc
            "semantic_model" -> DbtIcons.SemanticModel
            "metric", "measure" -> DbtIcons.Metric
            "metric_alias", "sql_alias" -> DbtIcons.Alias
            "dimension" -> DbtIcons.Dimension
            "entity" -> DbtIcons.Entity
            "column", "sql_projection", "information_schema_column" -> DbtIcons.Column
            "exposure", "export" -> DbtIcons.Export
            "group" -> DbtIcons.Group
            "package", "project" -> DbtIcons.Package
            "var", "variable", "local", "environment" -> DbtIcons.Variable
            "parameter", "argument" -> DbtIcons.Parameter
            "config", "configuration", "profile", "target", "env" -> DbtIcons.Config
            "result" -> DbtIcons.Result
            "selector" -> DbtIcons.Selector
            "materialization" -> DbtIcons.Materialization
            else -> DbtIcons.Property
        }
    }
}

/** IDE-owned presentation service for documentation symbols; keeps the project model independent of the documentation UI. */
interface DbtDocumentationNavigation {
    fun show(element: DbtSymbolElement)
}

/** Shared dbt mark; IntelliJ selects its dark SVG variant. */
object DbtIcons {
    private fun load(name: String): Icon = IconLoader.getIcon("/icons/dbt/$name.svg", DbtIcons::class.java)
    val Table: Icon by lazy { load("table") }
    val Seed: Icon by lazy { load("seed") }
    val Snapshot: Icon by lazy { load("snapshot") }
    val Query: Icon by lazy { load("query") }
    val Macro: Icon by lazy { load("macro") }
    val Jinja: Icon by lazy { load("jinja") }
    val Function: Icon by lazy { load("function") }
    val Test: Icon by lazy { load("test") }
    val Check: Icon by lazy { load("check") }
    val Doc: Icon by lazy { load("doc") }
    val SemanticModel: Icon by lazy { load("semantic-model") }
    val Metric: Icon by lazy { load("metric") }
    val Alias: Icon by lazy { load("alias") }
    val Dimension: Icon by lazy { load("dimension") }
    val Entity: Icon by lazy { load("entity") }
    val Column: Icon by lazy { load("column") }
    val Export: Icon by lazy { load("export") }
    val Group: Icon by lazy { load("group") }
    val Package: Icon by lazy { load("package") }
    val Variable: Icon by lazy { load("variable") }
    val Parameter: Icon by lazy { load("parameter") }
    val Config: Icon by lazy { load("config") }
    val Property: Icon by lazy { load("property") }
    val Result: Icon by lazy { load("result") }
    val Selector: Icon by lazy { load("selector") }
    val Materialization: Icon by lazy { load("materialization") }
    val Gutter: Icon by lazy { load("gutter") }
}
