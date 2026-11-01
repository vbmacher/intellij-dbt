package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.navigation.DbtNavigation
import com.github.vbmacher.intellij.dbt.project.*
import com.github.vbmacher.intellij.dbt.syntax.DbtSqlText
import com.github.vbmacher.intellij.dbt.syntax.DbtSyntax
import com.github.vbmacher.intellij.dbt.syntax.DbtYaml
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.HighlightDisplayKey
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.SuppressionUtil
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.psi.*
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.Alarm
import com.jetbrains.jsonSchema.extension.JsonSchemaFileProvider
import com.jetbrains.jsonSchema.extension.JsonSchemaProviderFactory
import com.jetbrains.jsonSchema.extension.SchemaType
import org.jetbrains.yaml.YAMLUtil
import org.jetbrains.yaml.psi.YAMLSequence
import java.awt.BorderLayout
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.Callable
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel

data class DbtDiagnostic(val range: TextRange, val message: String)
data class DbtAnnotationInput(val pointer: SmartPsiElementPointer<PsiFile>, val stamp: Long)
data class DbtAnnotationResult(val stamp: Long, val diagnostics: List<DbtDiagnostic>)

/**
 * Resolves references in a cancellable background phase and publishes only diagnostics for the captured file revision.
 * Missing-reference warnings require every declaration source to report that its metadata is conclusive.
 */
class DbtUnresolvedAnnotator : ExternalAnnotator<DbtAnnotationInput, DbtAnnotationResult>() {
    override fun getPairedBatchInspectionShortName() = "DbtUnresolvedReference"
    override fun collectInformation(file: PsiFile): DbtAnnotationInput? {
        if (file.virtualFile?.extension.orEmpty() !in Extensions) return null
        file.project.getService(DbtHighlightingRefresh::class.java)
        return DbtAnnotationInput(
            SmartPointerManager.getInstance(file.project).createSmartPsiElementPointer(file),
            file.modificationStamp
        )
    }

    override fun doAnnotate(input: DbtAnnotationInput): DbtAnnotationResult = ReadAction.nonBlocking(Callable {
        val file = input.pointer.element?.takeIf { it.modificationStamp == input.stamp }
        if (file == null) DbtAnnotationResult(input.stamp, emptyList()) else {
            val text = file.text
            val inactive = DbtSqlText.tokens(text).filter { it.protectedText }.map { it.range }
            val w = DbtWorkspace.get(file.project)
            val conclusive = w.root(file)
                ?.let { root -> DbtDeclarationSource.EP.extensionList.all { it.canReportMissing(w, root) } } == true
            val diagnostics =
                (if (conclusive) DbtNavigation.inFile(file) else emptyList()).groupBy { it.rangeInElement }
                    .filter { (range, refs) ->
                        refs.all { it.candidates.isEmpty() } && inactive.none {
                            it.containsOffset(
                                range.startOffset
                            )
                        }
                    }
                    .flatMap { (range, refs) ->
                        val messages = refs.mapNotNull { it.unresolvedMessage }.distinct()
                        if (messages.size == 1) messages.map { DbtDiagnostic(range, it) } else emptyList()
                    }.sortedBy { it.range.startOffset }
            DbtAnnotationResult(input.stamp, diagnostics)
        }
    }).expireWith(input.pointer.project).executeSynchronously()

    override fun apply(file: PsiFile, result: DbtAnnotationResult, holder: AnnotationHolder) {
        val key = HighlightDisplayKey.find(pairedBatchInspectionShortName)
        val profile = InspectionProjectProfileManager.getInstance(file.project).currentProfile
        val severity = key?.let { profile.getErrorLevel(it, file).severity } ?: HighlightSeverity.WARNING
        if (file.modificationStamp == result.stamp && (key?.let { profile.isToolEnabled(it, file) }
                ?: true)) result.diagnostics
            .filter { d ->
                file.findElementAt(d.range.startOffset)
                    ?.let { !SuppressionUtil.isSuppressed(it, pairedBatchInspectionShortName) } ?: true
            }
            .forEach {
                holder.newAnnotation(severity, it.message).range(it.range)
                    .withFix(DbtCreateResourceFix(it.range.startOffset)).create()
            }
    }

    companion object {
        val Extensions = setOf("sql", "jinja", "jinja2")
    }
}

/** External annotators otherwise rerun only when their own file is dirty, even if a referenced definition changed. */
@Service(Service.Level.PROJECT)
class DbtHighlightingRefresh(private val project: Project) : Disposable {
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val connection = project.messageBus.connect(this)

    init {
        connection.subscribe(PsiModificationTracker.TOPIC, object : PsiModificationTracker.Listener {
            override fun modificationCountChanged() = schedule()
        })
        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) = schedule()
        })
        connection.subscribe(DbtProjectSettings.Changed, object : DbtSettingsListener {
            override fun settingsChanged() = schedule()
        })
    }

    private fun schedule() {
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed && !alarm.isDisposed) {
                alarm.cancelAllRequests()
                alarm.addRequest({
                    WriteIntentReadAction.run {
                        val psi = PsiManager.getInstance(project)
                        val daemon = DaemonCodeAnalyzer.getInstance(project)
                        FileEditorManager.getInstance(project).openFiles.filter { it.isValid && it.extension.orEmpty() in DbtUnresolvedAnnotator.Extensions }
                            .mapNotNull(psi::findFile).forEach { daemon.restart(it, "dbt workspace changed") }
                    }
                }, 200)
            }
        }
    }

    override fun dispose() {}
}

class DbtUnresolvedReferenceInspection : LocalInspectionTool() {
    override fun getShortName() = "DbtUnresolvedReference"
}

/** A reviewable resource-creation proposal with the original reference text and optional existing YAML destination. */
internal data class DbtCreation(
    val kind: String, val name: String, val source: String?, val root: DbtRoot,
    val file: PsiFile?, val before: String, val after: String, val path: String,
    val origin: Triple<PsiFile, TextRange, String>? = null
)

class DbtCreateResourceFix(private val offset: Int) : IntentionAction {
    override fun getText() = "Create missing dbt resource"
    override fun getFamilyName() = "dbt resource creation"
    override fun startInWriteAction() = false
    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile) = proposal(file, offset) != null
    override fun invoke(project: Project, editor: Editor?, file: PsiFile) {
        proposal(file, offset)?.let { DbtCreateResourceDialog(project, it).show() }
    }

    companion object {
        internal fun properties(name: String) = "version: 2\nmodels:\n  - name: $name\n    columns:\n      - name: id\n"
        internal fun proposal(file: PsiFile, offset: Int): DbtCreation? {
            val w = DbtWorkspace.get(file.project)
            val root =
                w.root(file)?.takeIf { w.packageOwner(it) == null && !it.excluded(file.virtualFile) } ?: return null
            val ref = DbtNavigation.inFile(file)
                .firstOrNull { it.unresolvedMessage != null && it.rangeInElement.containsOffset(offset) } ?: return null
            val call = DbtSyntax.inFile(file).flatMap(DbtSyntax::calls)
                .firstOrNull { it.closed && it.expressionRange.contains(ref.rangeInElement) } ?: return null
            val folder = root.pathKinds.firstOrNull { it.first == "model" }?.second ?: return null
            val result = when (call.name) {
                "ref", "builtins.ref" -> if (call.positional.size == 1 && call.version == null) call.literal(0)
                    ?.takeIf(DbtSyntax::isIdentifier)?.let { name ->
                    DbtCreation("model", name, null, root, null, "", "select\n    1 as id\n", "$folder/$name.sql")
                } else null

                "source", "builtins.source" -> if (call.positional.size == 2) {
                    val source = call.literal(0)?.takeIf(DbtSyntax::isIdentifier) ?: return null
                    val table = call.literal(1)?.takeIf(DbtSyntax::isIdentifier) ?: return null
                    sourcePlan(w, root, folder, source, table)
                } else null

                else -> null
            }
            return result?.copy(origin = Triple(file, ref.rangeInElement, file.text))
        }

        private fun sourcePlan(
            w: DbtWorkspace,
            root: DbtRoot,
            folder: String,
            source: String,
            table: String
        ): DbtCreation? {
            val parents = w.declarations(setOf("source"), source, root).filter { !it.metadata && w.owner(it) == root }
            if (parents.size > 1 || parents.any { !it.file.isWritable }) return null
            val parent = parents.firstOrNull()
            if (parent != null) {
                val mapping = parent.mapping ?: return null
                val before = parent.file.text
                val value = "{name: $table}"
                val tables = DbtYaml.value(mapping, "tables")
                val after = when (tables) {
                    is YAMLSequence -> DbtPropertiesEditor.appendItems(before, tables, listOf(value))
                    null -> {
                        val offset = mapping.textRange.endOffset
                        before.substring(
                            0,
                            offset
                        ) + "\n" + " ".repeat(YAMLUtil.getIndentInThisLine(mapping)) + "tables: [$value]" + before.substring(
                            offset
                        )
                    }

                    else -> return null
                }
                return DbtCreation(
                    "source",
                    table,
                    source,
                    root,
                    parent.file,
                    before,
                    after,
                    root.relative(parent.file.virtualFile).orEmpty()
                )
            }
            val path = "${folder}/${source}_sources.yml"
            if (root.directory.findFileByRelativePath(path) != null) return null
            return DbtCreation(
                "source",
                table,
                source,
                root,
                null,
                "",
                "version: 2\nsources:\n  - name: $source\n    tables:\n      - name: $table\n",
                path
            )
        }

        /** Rechecks names, project ownership, exclusions and source snapshots before accepting a creation destination. */
        internal fun validate(
            proposal: DbtCreation,
            relativePath: String,
            includeProperties: Boolean = false,
            logicalName: String? = null
        ): Result<Path> {
            val path = try {
                Paths.get(relativePath).normalize()
            } catch (_: Exception) {
                return Result.failure(IllegalArgumentException("Choose a valid destination path."))
            }
            val name = logicalName ?: proposal.name
            val workspace = DbtWorkspace.get(proposal.root.config.project)
            val current = workspace.roots.firstOrNull { it.directory == proposal.root.directory }
            val normalized = path.toString().replace('\\', '/')
            val valid = !path.isAbsolute && !path.startsWith("..") && current?.let { root ->
                !root.excludedPath(normalized) && root.pathKinds.any { (kind, folder) ->
                    kind == "model" && root.pathContains(
                        folder,
                        normalized
                    )
                }
            } == true
            val propertiesPath = path.resolveSibling("$name.yml").toString().replace('\\', '/')
            val filename = path.fileName?.toString().orEmpty()
            val wrongExtension = if (proposal.kind == "model") filename != "$name.sql" else filename.split(".")
                .lastOrNull() !in setOf("yml", "yaml")
            val error = when {
                !DbtSyntax.isIdentifier(name) || proposal.kind != "model" && name != proposal.name -> "Choose a valid dbt model name."
                wrongExtension -> "Use the model name followed by .sql, or a .yml/.yaml source file."
                !valid -> "Choose a file inside a configured model path, outside generated and ignored files."
                current?.let { root ->
                    workspace.roots.any { other ->
                        other.directory != root.directory && root.relative(
                            other.directory
                        )?.let { root.pathContains(it, normalized) } == true
                    }
                } == true -> "The destination belongs to another dbt project."

                proposal.kind == "model" && current?.let {
                    workspace.declarations(DbtSymbol.RefKinds, name, it).isNotEmpty()
                } == true -> "A resource with that logical name already exists."

                includeProperties && (proposal.kind != "model" || current?.excludedPath(propertiesPath) == true) -> "Choose an unexcluded model properties destination."
                includeProperties && proposal.root.directory.findFileByRelativePath(propertiesPath) != null -> "The properties destination already exists."
                proposal.origin?.let { (file, _, before) -> !file.isValid || !file.isWritable || file.text != before } == true -> "The reference changed. Reopen the quick fix."
                name != proposal.name && proposal.origin == null -> "Reopen the quick fix to change the reference name."
                proposal.file?.let { it.text != proposal.before } == true -> "Source properties changed. Reopen the quick fix."
                proposal.file == null && proposal.root.directory.findFileByRelativePath(path.toString()) != null -> "The destination already exists."
                else -> null
            }
            return if (error == null) Result.success(path) else Result.failure(IllegalArgumentException(error))
        }

        internal fun create(
            proposal: DbtCreation,
            relativePath: String,
            includeProperties: Boolean = false,
            logicalName: String? = null
        ): Result<Unit> =
            validate(proposal, relativePath, includeProperties, logicalName).map { path ->
                val name = logicalName ?: proposal.name
                val project = proposal.root.config.project
                WriteCommandAction.writeCommandAction(project).withName("Create dbt resource").run<RuntimeException> {
                    val file = proposal.file
                    if (file != null) {
                        val manager = PsiDocumentManager.getInstance(file.project)
                        manager.getDocument(file)!!.setText(proposal.after)
                        manager.commitAllDocuments()
                    } else {
                        val parent = path.parent?.let {
                            VfsUtil.createDirectoryIfMissing(
                                proposal.root.directory,
                                it.toString()
                            )
                        } ?: proposal.root.directory
                        val created = parent.createChildData(this, path.fileName.toString())
                        VfsUtil.saveText(created, proposal.after)
                        if (includeProperties) VfsUtil.saveText(
                            parent.createChildData(this, "$name.yml"),
                            properties(name)
                        )
                        if (name != proposal.name) proposal.origin?.let { (originFile, range, _) ->
                            val manager = PsiDocumentManager.getInstance(project)
                            manager.getDocument(originFile)!!.replaceString(range.startOffset, range.endOffset, name)
                            manager.commitAllDocuments()
                        }
                    }
                }
            }
    }
}

internal class DbtCreateResourceDialog(project: Project, private val proposal: DbtCreation) : DialogWrapper(project) {
    val path = JBTextField(proposal.path, 55)
    val name = JBTextField(proposal.name, 30).also { it.isEditable = proposal.kind == "model" }
    val properties = JCheckBox("Add model properties beside SQL").also { it.isVisible = proposal.kind == "model" }
    val content = JPanel(BorderLayout(0, 8))
    val preview = JBTextArea(proposal.after, 12, 70).also {
        it.isEditable = false; it.accessibleContext.accessibleName = "Proposed resource contents"
    }

    init {
        path.isEditable = proposal.file == null
        val header = JPanel(BorderLayout(0, 8))
        header.add(JBLabel("Project: ${proposal.root.name} · ${proposal.kind} ${proposal.source?.let { "$it." } ?: ""}${proposal.name}"),
            BorderLayout.NORTH)
        val fields = JPanel(java.awt.GridBagLayout())
        DbtUi.labeledRows(
            fields,
            com.intellij.util.ui.JBUI.insets(4, 0),
            listOf("Logical name:" to name, "Destination:" to path, "" to properties)
        )
        header.add(fields, BorderLayout.CENTER)
        content.add(header, BorderLayout.NORTH)
        content.add(com.intellij.ui.components.JBScrollPane(preview), BorderLayout.CENTER)
        name.document.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(event: javax.swing.event.DocumentEvent) {
                path.text = path.text.substring(0, path.text.lastIndexOf('/') + 1) + name.text + ".sql"
                updatePreview()
            }
        })
        properties.addActionListener { updatePreview() }
        title = "Create dbt resource"
        setOKButtonText("Create")
        init(); initValidation()
    }

    fun updatePreview() {
        preview.text =
            proposal.after + if (properties.isSelected) "\n--- ${name.text}.yml ---\n" + DbtCreateResourceFix.properties(
                name.text
            ) else ""
    }

    override fun createCenterPanel(): JComponent = content
    override fun getPreferredFocusedComponent(): JComponent = if (name.isEditable) name else path
    override fun getDimensionServiceKey() = "dbt.create.resource"
    public override fun doValidate(): ValidationInfo? =
        DbtCreateResourceFix.validate(proposal, path.text.trim(), properties.isSelected, name.text.trim())
            .exceptionOrNull()?.let {
            ValidationInfo(it.message.orEmpty(), if (!DbtSyntax.isIdentifier(name.text.trim())) name else path)
        }

    override fun doOKAction() {
        DbtCreateResourceFix.create(proposal, path.text.trim(), properties.isSelected, name.text.trim())
            .fold(onSuccess = { super.doOKAction() }, onFailure = { setErrorText(it.message) })
    }
}

/** Offline schemas use IntelliJ's YAML completion, documentation and inspection infrastructure. */
class DbtYamlSchemaProvider : JsonSchemaProviderFactory {
    override fun getProviders(project: Project): List<JsonSchemaFileProvider> =
        listOf("project", "packages", "selectors", "properties").flatMap { kind ->
            listOf(
                "precontracts",
                "legacy",
                "semantic",
                "queries",
                "units",
                "current",
                "functions",
                "v2"
            ).map { version ->
                object : JsonSchemaFileProvider {
                    override fun getName() = "dbt $kind ($version)"
                    override fun getSchemaType() = SchemaType.embeddedSchema
                    override fun getSchemaFile(): VirtualFile? = JsonSchemaProviderFactory.getResourceFile(
                        DbtYamlSchemaProvider::class.java,
                        if (kind == "properties") "/schemas/dbt-$kind-$version.json" else "/schemas/dbt-$kind.json"
                    )

                    override fun isAvailable(file: VirtualFile): Boolean {
                        if (project.isDisposed || !file.isValid || file.extension.orEmpty() !in setOf(
                                "yml",
                                "yaml"
                            )
                        ) return false
                        val root =
                            DbtWorkspace.rootForFile(project, file)?.takeIf { !it.excluded(file) } ?: return false
                        val selectedVersion =
                            if (root.engine == "v2") "v2" else if (root.supports(11)) "functions" else if (root.supports(
                                    10
                                )
                            ) "current"
                            else if (root.supports(8)) "units" else if (root.supports(7)) "queries" else if (root.supports(
                                    6
                                )
                            ) "semantic" else if (root.supports(5)) "legacy" else "precontracts"
                        val selectedKind = when (file.name) {
                            "dbt_project.yml" -> "project"
                            "packages.yml", "dependencies.yml" -> "packages"
                            "selectors.yml" -> "selectors"
                            else -> if (root.resourceFolders(file) != null) DbtYaml.psiFile(project, file)
                                ?.let(DbtYaml::mapping)?.takeIf { m ->
                                m.keyValues.any {
                                    it.keyText in setOf(
                                        "models",
                                        "sources",
                                        "seeds",
                                        "snapshots",
                                        "macros",
                                        "semantic_models",
                                        "metrics",
                                        "unit_tests",
                                        "exposures",
                                        "saved_queries",
                                        "functions",
                                        "checks"
                                    )
                                }
                            }?.let { "properties" } else null
                        }
                        return selectedKind == kind && selectedVersion == version
                    }
                }
            }
        }
}
