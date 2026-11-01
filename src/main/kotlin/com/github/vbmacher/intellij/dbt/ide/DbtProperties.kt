package com.github.vbmacher.intellij.dbt.ide

import com.intellij.notification.NotificationType
import com.github.vbmacher.intellij.dbt.lineage.DbtColumnLineage
import com.github.vbmacher.intellij.dbt.project.*
import com.github.vbmacher.intellij.dbt.syntax.DbtYaml
import com.github.vbmacher.intellij.dbt.navigation.DbtSqlAnalysis
import com.intellij.codeInsight.hint.HintManager
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.diff.DiffManager
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import org.jetbrains.yaml.YAMLElementGenerator
import org.jetbrains.yaml.YAMLFileType
import org.jetbrains.yaml.YAMLUtil
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLSequence
import javax.swing.JComponent

/** An exact before/after YAML edit applied in one undoable command, with stale-source and destination checks. */
internal data class DbtPropertyEdit(
    val path: String, val before: String, val after: String, val file: PsiFile?,
    val directory: com.intellij.openapi.vfs.VirtualFile? = null
) {
    fun apply(project: Project) {
        WriteCommandAction.writeCommandAction(project).withName("Edit dbt properties").run<RuntimeException> {
            val documents = PsiDocumentManager.getInstance(project)
            if (file != null) {
                val document = documents.getDocument(file)!!
                if (document.text != before) throw IllegalStateException("Properties changed; reopen the editor to review current source.")
                document.setText(after); documents.commitAllDocuments()
            } else {
                val parent = directory ?: com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                    .findFileByPath(path.substringBeforeLast('/'))
                ?: throw IllegalStateException("The destination directory is unavailable. Reopen the editor.")
                if (parent.findChild(path.substringAfterLast('/')) != null) throw IllegalStateException("The destination already exists.")
                val created = parent.createChildData(this, path.substringAfterLast('/'))
                com.intellij.openapi.vfs.VfsUtil.saveText(created, after)
            }
        }
    }
}

/** Plans source-preserving YAML updates and presents a diff before an explicit apply action. */
internal object DbtPropertiesEditor {
    fun editableColumns(w: DbtWorkspace, resource: DbtSymbol) = DbtResourceData.get(w, resource).columns.map { c ->
        c.copy(description = w.columns(resource).firstOrNull { it.name == c.name }?.mapping?.let {
            DbtYaml.string(
                it,
                "description"
            )
        }.orEmpty())
    }

    fun origins(w: DbtWorkspace, resource: DbtSymbol, column: String): Pair<String, List<DbtSymbol>> {
        val edges = DbtColumnLineage.upstream(w, resource, column).filter { it.kind == "value" }
        val inputs = edges.map { it.input }.distinctBy { it.id }
        val state =
            if (edges.isEmpty() || !edges.all { it.complete }) "Unknown lineage" else if (inputs.size == 1 && edges.all { it.direct }) "Passthrough" else "Calculated / multiple inputs"
        val names = inputs.map {
            it.namespace + "." + (it.owner.takeIf { o -> o.isNotEmpty() }?.let { o -> "$o." }
                ?: "") + it.name + (it.version?.let { v -> " v$v" } ?: "") + " · " + it.file.name
        }
        return (state + if (names.isEmpty()) "" else ": " + names.joinToString(", ")) to inputs
    }

    private fun declarations(w: DbtWorkspace, resource: DbtSymbol) =
        if (resource.kind == "source_table" || resource.isProperty) listOf(resource) else w.properties(resource)

    fun appendItems(text: String, sequence: YAMLSequence, values: List<String>): String {
        val flow = sequence.text.trim().startsWith("[")
        val offset = sequence.textRange.endOffset - if (flow) 1 else 0
        val insertion = if (flow) (if (sequence.items.isEmpty()) "" else ", ") + values.joinToString(", ")
        else values.joinToString("") { "\n" + " ".repeat(YAMLUtil.getIndentInThisLine(sequence)) + "- " + it }
        return text.substring(0, offset) + insertion + text.substring(offset)
    }

    /**
     * Builds a detached YAML proposal for one writable local declaration.
     * Ambiguous declarations, inherited-version changes and edits that would discard test arguments, anchors or comments fail explicitly.
     */
    fun plan(
        w: DbtWorkspace,
        resource: DbtSymbol,
        description: String,
        columns: List<DbtColumnData>,
        tags: List<String>,
        tests: List<String>,
        updateTags: Boolean = false,
        updateTests: Boolean = false
    ): Result<DbtPropertyEdit> {
        fun fail(message: String): Result<DbtPropertyEdit> = Result.failure(IllegalArgumentException(message))
        val r = w.owner(resource) ?: return fail("The owning project is unavailable.")
        val properties = declarations(w, resource).filter { !it.metadata }
        if (resource.metadata || w.packageOwner(r) != null || !resource.file.isWritable) return fail("Choose a writable local source definition.")
        if (properties.size > 1) return fail("Multiple properties entries exist; choose an unambiguous declaration.")
        if (properties.any { !it.file.isWritable }) return fail("The properties declaration is read-only.")
        if (resource.version != null && properties.isEmpty()) return fail("Create explicit model-version properties before editing this version.")
        if ((updateTests || tests.isNotEmpty()) && properties.mapNotNull { it.mapping }.any { m ->
                listOf("tests", "data_tests").mapNotNull { DbtYaml.value(m, it) }
                    .filterIsInstance<YAMLSequence>().any { seq -> seq.items.any { it.value is YAMLMapping } }
            })
            return fail("This resource has parameterized tests. Edit those declarations in YAML to preserve their arguments and configuration.")
        val inheritedColumns = resource.version != null && properties.mapNotNull { it.mapping }
            .any { DbtYaml.value(it, "columns") == null }
        val inheritedChanges = inheritedColumns && columns.any { column ->
            w.columns(resource).firstOrNull { it.name == column.name }?.let { existing ->
                column.dataType != existing.attributes["data_type"].orEmpty() || column.description != (existing.mapping?.let {
                    DbtYaml.string(
                        it,
                        "description"
                    )
                } ?: existing.attributes["description"].orEmpty())
            } ?: true
        }
        if (inheritedChanges) return fail("This version inherits its columns. Edit the base columns or create explicit version overrides in YAML before synchronizing.")
        val editedColumns = if (inheritedColumns) emptyList() else columns
        val source = properties.firstOrNull()?.file
        val path = source?.virtualFile?.path ?: resource.file.virtualFile.parent.path + "/" + resource.name + ".yml"
        val before = source?.text.orEmpty()
        val section =
            mapOf("model" to "models", "seed" to "seeds", "snapshot" to "snapshots", "macro" to "macros")[resource.kind]
        if (source == null && section == null) return fail("Create this resource's properties declaration in YAML before editing it.")
        val initial = if (source != null) before else "version: 2\n$section:\n  - name: ${resource.name}\n"
        val copy = PsiFileFactory.getInstance(w.project).createFileFromText("properties.yml", YAMLFileType.YML, initial)
        val offset = properties.firstOrNull()?.mapping?.textOffset
        val target = if (source != null) offset?.let(copy::findElementAt)
            ?.let { generateSequence(it) { e -> e.parent }.filterIsInstance<YAMLMapping>().firstOrNull() }
        else DbtYaml.mapping(copy)?.let { DbtYaml.items(it, section!!) }?.firstOrNull()
        val mapping = target ?: return fail("The properties mapping could not be located.")
        return try {
            val generator = YAMLElementGenerator.getInstance(w.project)
            fun quoted(value: String) = com.google.gson.GsonBuilder().disableHtmlEscaping().create().toJson(value)
            fun put(m: YAMLMapping, key: String, value: String) {
                val replacement = generator.createYamlKeyValue(key, value)
                val existing = m.keyValues.firstOrNull { it.keyText == key }
                when {
                    existing?.value != null -> {
                        if (com.intellij.psi.util.PsiTreeUtil.findChildOfType(
                                existing.value,
                                org.jetbrains.yaml.psi.YAMLAnchor::class.java
                            ) != null
                        )
                            throw IllegalArgumentException("'$key' defines a YAML anchor. Edit it in source to preserve its aliases.")
                        if (com.intellij.psi.util.PsiTreeUtil.findChildOfType(
                                existing.value,
                                com.intellij.psi.PsiComment::class.java
                            ) != null
                        )
                            throw IllegalArgumentException("'$key' contains comments. Edit its sequence in source to preserve those comments.")
                        existing.setValue(replacement.value!!)
                    }

                    existing == null && m.firstChild.node.elementType == org.jetbrains.yaml.YAMLTokenTypes.LBRACE -> {
                        val closing = m.lastChild
                        m.keyValues.lastOrNull()?.let { previous ->
                            val trailingComma =
                                generateSequence(previous.nextSibling) { it.nextSibling }.takeWhile { it != closing }
                                    .any { it.node.elementType == org.jetbrains.yaml.YAMLTokenTypes.COMMA }
                            if (!trailingComma) m.addAfter(generator.createComma(), previous)
                        }
                        m.addBefore(generator.createSpace(), closing); m.addBefore(replacement, closing)
                    }

                    else -> m.putKeyValue(replacement)
                }
            }
            if (DbtYaml.string(mapping, "description") != description) put(mapping, "description", quoted(description))
            if (tags.isNotEmpty() || updateTags) {
                val tagTarget = DbtYaml.map(mapping, "config")?.takeIf { DbtYaml.value(it, "tags") != null } ?: mapping
                put(tagTarget, "tags", tags.joinToString(", ", "[", "]", transform = ::quoted))
            }
            val testKey = if (r.supports(8)) "data_tests" else "tests"
            if (tests.isNotEmpty() || updateTests) {
                val actualKey = if (DbtYaml.value(mapping, "tests") != null && DbtYaml.value(
                        mapping,
                        "data_tests"
                    ) == null
                ) "tests" else testKey
                put(mapping, actualKey, tests.joinToString(", ", "[", "]", transform = ::quoted))
            }
            val missing = editedColumns.filter { column ->
                DbtYaml.items(mapping, "columns").none { DbtYaml.string(it, "name") == column.name }
            }
            editedColumns.forEach { column ->
                DbtYaml.items(mapping, "columns").firstOrNull { DbtYaml.string(it, "name") == column.name }?.let { m ->
                    if (DbtYaml.string(m, "description") != column.description) put(
                        m,
                        "description",
                        quoted(column.description)
                    )
                    if (column.dataType.isNotEmpty() && DbtYaml.string(m, "data_type") != column.dataType) put(
                        m,
                        "data_type",
                        quoted(column.dataType)
                    )
                }
            }
            val values = missing.map { column ->
                val dataType = if (column.dataType.isNotEmpty()) ", data_type: ${quoted(column.dataType)}" else ""
                "{name: ${quoted(column.name)}$dataType, description: ${quoted(column.description)}}"
            }
            val columnSequence = DbtYaml.value(mapping, "columns")
            val after = if (values.isEmpty()) copy.text else when (columnSequence) {
                is YAMLSequence -> appendItems(copy.text, columnSequence, values)
                null -> {
                    val text = copy.text
                    val flow = mapping.text.trim().startsWith("{")
                    val insertionOffset = mapping.textRange.endOffset - if (flow) 1 else 0
                    val firstKey = mapping.keyValues.first().textOffset
                    val indent = firstKey - text.lastIndexOf('\n', firstKey - 1) - 1
                    val prefix =
                        if (flow) (if (mapping.keyValues.isEmpty()) "" else ", ") else "\n" + " ".repeat(indent)
                    text.substring(0, insertionOffset) + prefix + "columns: " + values.joinToString(
                        ", ",
                        "[",
                        "]"
                    ) + text.substring(insertionOffset)
                }

                else -> throw IllegalArgumentException("Columns must be a YAML sequence before synchronization.")
            }
            Result.success(DbtPropertyEdit(path, before, after, source, resource.file.virtualFile.parent))
        } catch (error: Exception) {
            fail(error.message ?: "The properties edit could not preserve the current YAML structure.")
        }
    }

    fun show(w: DbtWorkspace, resource: DbtSymbol) {
        val entries = declarations(w, resource).distinctBy { it.id }
        when {
            entries.size == 1 -> entries.first().element.navigate(true)
            entries.isNotEmpty() -> DbtNavigationItem.popup(
                "dbt properties",
                entries.map { DbtNavigationItem.declaration(it.element) }).showCenteredInCurrentWindow(w.project)

            else -> generate(w, resource, inherit = false)
        }
    }

    fun generatedColumns(w: DbtWorkspace, resource: DbtSymbol, inherit: Boolean): List<DbtColumnData> =
        if (inherit) DbtColumnLineage.inherited(w, resource)
            .map { (name, text, _) -> DbtColumnData(name, "", text) } else editableColumns(w, resource)

    fun generate(w: DbtWorkspace, resource: DbtSymbol, inherit: Boolean) {
        val description = (listOf(resource) + w.properties(resource)).mapNotNull { it.mapping }
            .mapNotNull { DbtYaml.string(it, "description") }.firstOrNull().orEmpty()
        val proposedColumns = generatedColumns(w, resource, inherit)
        val prepared =
            if (inherit && proposedColumns.isEmpty()) Result.failure(IllegalArgumentException("No empty descriptions have an unambiguous documented passthrough source."))
            else com.intellij.openapi.application.WriteAction.compute<Result<DbtPropertyEdit>, RuntimeException> {
                plan(
                    w,
                    resource,
                    description,
                    proposedColumns,
                    emptyList(),
                    emptyList()
                )
            }
        prepared.fold(onFailure = {
            DbtNotifications.show(
                w.project,
                "Generate dbt properties",
                it.message.orEmpty(),
                NotificationType.INFORMATION
            )
        }, onSuccess = { edit ->
            val sources = w.files.map { it to it.modificationStamp }
            val settings = DbtProjectSettings.get(w.project).modificationCount
            object : DialogWrapper(w.project) {
                private val diff = DiffManager.getInstance().createRequestPanel(w.project, disposable, null)

                init {
                    val factory = DiffContentFactory.getInstance()
                    diff.setRequest(
                        SimpleDiffRequest(
                            edit.path,
                            factory.create(edit.before),
                            factory.create(edit.after),
                            "Current YAML",
                            "Generated YAML"
                        )
                    )
                    title = if (inherit) "Inherit empty dbt descriptions" else "Generate dbt columns"
                    setOKButtonText("Apply"); init()
                }

                override fun createCenterPanel(): JComponent =
                    diff.component.also { it.preferredSize = java.awt.Dimension(850, 550) }

                override fun doValidate(): ValidationInfo? =
                    if (settings != DbtProjectSettings.get(w.project).modificationCount || sources.any { (f, stamp) -> !f.isValid || f.modificationStamp != stamp })
                        ValidationInfo("Source or settings changed. Generate the properties again.") else null

                override fun doOKAction() {
                    if (doValidate() == null) {
                        edit.apply(w.project); super.doOKAction()
                    }
                }
            }.show()
        })
    }
}

abstract class DbtPropertiesIntention(private val inherit: Boolean) : IntentionAction {
    override fun getText() = if (inherit) "Inherit empty dbt column descriptions" else "Generate missing dbt columns"
    override fun getFamilyName() = "dbt properties"
    override fun startInWriteAction() = false
    private fun resource(project: Project, editor: Editor, file: PsiFile) =
        DbtResourceAction.at(project, file, editor.caretModel.offset)
            ?.takeIf { (w, s) ->
                !s.metadata && s.file.isWritable && w.owner(s)?.let { w.packageOwner(it) == null } == true
            }

    override fun isAvailable(project: Project, editor: Editor, file: PsiFile) =
        resource(project, editor, file)?.let { (w, symbol) ->
            if (inherit) DbtColumnLineage.inherited(w, symbol).isNotEmpty() else {
                val names = w.columns(symbol).filter { !it.metadata && it.mapping != null }.map { it.name }.toSet()
                DbtResourceData.get(w, symbol).columns.any { it.name !in names }
            }
        } == true

    override fun invoke(project: Project, editor: Editor, file: PsiFile) {
        resource(project, editor, file)?.let { (w, s) -> DbtPropertiesEditor.generate(w, s, inherit) }
    }
}

class DbtGenerateColumnsIntention : DbtPropertiesIntention(inherit = false)
class DbtInheritDescriptionsIntention : DbtPropertiesIntention(inherit = true)
class DbtExpandColumnsIntention : IntentionAction {
    override fun getText() = "Expand dbt SQL columns"
    override fun getFamilyName() = "dbt SQL"
    override fun startInWriteAction() = true
    private fun analysis(file: PsiFile) = DbtSqlAnalysis.get(DbtWorkspace.get(file.project), file)
    private fun expansion(file: PsiFile, offset: Int) =
        analysis(file)?.wildcards?.firstOrNull { (range, columns) -> range.containsOffset(offset) && columns.isNotEmpty() }

    private fun problem(file: PsiFile, offset: Int) =
        analysis(file)?.wildcardProblems?.firstOrNull { it.range.containsOffset(offset) }

    override fun isAvailable(project: Project, editor: Editor, file: PsiFile) =
        expansion(file, editor.caretModel.offset) != null || problem(file, editor.caretModel.offset) != null

    override fun invoke(project: Project, editor: Editor, file: PsiFile) {
        val offset = editor.caretModel.offset
        val expansion = expansion(file, offset)
        if (expansion != null) editor.document.replaceString(
            expansion.first.startOffset,
            expansion.first.endOffset,
            expansion.second.joinToString(", ")
        )
        else problem(file, offset)?.let { HintManager.getInstance().showErrorHint(editor, it.message) }
    }

    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo {
        val original = file.originalFile
        expansion(original, editor.caretModel.offset)?.let { (range, columns) ->
            val text = original.text
            return IntentionPreviewInfo.CustomDiff(
                file.fileType,
                text,
                text.substring(0, range.startOffset) + columns.joinToString(", ") + text.substring(range.endOffset)
            )
        }
        return problem(
            original,
            editor.caretModel.offset
        )?.let { IntentionPreviewInfo.Html(StringUtil.escapeXmlEntities(it.message)) } ?: IntentionPreviewInfo.EMPTY
    }
}
