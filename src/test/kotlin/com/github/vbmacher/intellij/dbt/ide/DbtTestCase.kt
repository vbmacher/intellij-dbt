package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.navigation.DbtNavigation
import com.github.vbmacher.intellij.dbt.navigation.DbtNavigationReference
import com.github.vbmacher.intellij.dbt.project.*
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.json.JsonFileType
import com.intellij.model.psi.impl.targetDeclarationAndReferenceSymbols
import com.intellij.openapi.Disposable
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.psi.*
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.replaceService
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.*
import java.awt.Component
import java.awt.Container
import java.nio.file.Files
import java.nio.file.Path

/** Shared light-fixture support; source locations define expected behavior. */
abstract class DbtTestCase : BasePlatformTestCase() {
    init {
        if (listOf(
                "DBT_CONSUMERS_SCREENSHOT_DIR",
                "DBT_INTEGRATIONS_SCREENSHOT",
                "DBT_SETTINGS_SCREENSHOT",
                "DBT_CTE_SCREENSHOT_DIR"
            ).any { System.getenv().containsKey(it) }
        ) {
            com.intellij.ui.IconManager.activate(null); com.intellij.openapi.util.IconLoader.activate()
        }
    }

    override fun tearDown() {
        try {
            project.getService(DbtConsumersSettings::class.java).loadState(DbtConsumersState()); DbtProjectSettings.get(
                project
            ).loadState(DbtSettingsState())
        } finally {
            super.tearDown()
        }
    }

    protected fun add(path: String, text: String): PsiFile = myFixture.addFileToProject(path, text)
    protected open fun project(config: String = "", path: String = ""): PsiFile =
        add(path + "dbt_project.yml", "name: analytics\nconfig-version: 2\n" + config)

    protected fun bareProject(config: String = "", path: String = ""): PsiFile =
        add(path + "dbt_project.yml", "name: analytics\n" + config)

    protected fun configureSettings(update: (DbtRunOptions) -> Unit) {
        val settings =
            DbtProjectSettings.get(project); update(settings.options(myFixture.findFileInTempDir("dbt_project.yml").parent.path)); settings.changed()
    }

    protected val workspace: DbtWorkspace get() = DbtWorkspace.get(project)
    protected open val root: DbtRoot get() = workspace.roots.first { it.name == "analytics" }
    protected fun projectFile(path: String, owner: DbtRoot = workspace.roots.first()): PsiFile =
        PsiManager.getInstance(project).findFile(owner.directory.findFileByRelativePath(path)!!)!!

    protected fun configure(path: String, text: String): PsiFile {
        val offset = text.indexOf("<caret>"); assertTrue(
            "The editor fixture must contain one caret",
            offset >= 0 && offset == text.lastIndexOf("<caret>")
        )
        return add(path, text.replace("<caret>", "")).also { open(it, offset) }
    }

    protected open fun open(file: PsiFile, offset: Int) {
        myFixture.configureFromExistingVirtualFile(file.virtualFile); myFixture.editor.caretModel.moveToOffset(offset)
    }

    protected fun edit(file: PsiFile, text: String) {
        WriteCommandAction.runWriteCommandAction(
            project,
            Runnable {
                val documents = PsiDocumentManager.getInstance(project); documents.getDocument(file)!!
                .setText(text); documents.commitAllDocuments()
            })
    }

    protected fun referencesAt(file: PsiFile, text: String, occurrence: Int = 0): List<DbtNavigationReference> {
        val start =
            generateSequence(file.text.indexOf(text)) { file.text.indexOf(text, it + 1) }.drop(occurrence).first()
        assertTrue("Missing text $text", start >= 0); return DbtNavigation.at(file, start + minOf(1, text.length - 1))
    }

    protected fun targetsAt(file: PsiFile, text: String, occurrence: Int = 0): List<PsiElement> =
        referencesAt(file, text, occurrence).flatMap { it.candidates }.distinct()

    protected fun <A> withSqlParsedAsJson(body: () -> A): A {
        val fileTypes = FileTypeManager.getInstance();
        val original = fileTypes.getFileTypeByExtension("sql")
        WriteCommandAction.runWriteCommandAction(
            project,
            Runnable { fileTypes.associateExtension(JsonFileType.INSTANCE, "sql") })
        try {
            return body()
        } finally {
            WriteCommandAction.runWriteCommandAction(
                project,
                Runnable {
                    fileTypes.removeAssociatedExtension(
                        JsonFileType.INSTANCE,
                        "sql"
                    ); fileTypes.associateExtension(original, "sql")
                })
        }
    }

    protected val reference: PsiReference get() = myFixture.getReferenceAtCaretPositionWithAssertion()
    protected val declarationTargets: List<PsiElement>
        get() = targetDeclarationAndReferenceSymbols(
            myFixture.file,
            myFixture.caretOffset
        ).second.map { (it as DbtPsiSymbol).element }

    protected fun assertTarget(file: PsiFile, offset: Int = 0): PsiElement {
        val targets = declarationTargets; assertEquals("Go to Declaration should find one target", 1, targets.size);
        val target = targets.first()
        assertEquals("Declaration file", file, target.containingFile); assertEquals(
            "Declaration offset",
            offset,
            target.textOffset
        ); return target
    }

    protected fun assertNoTarget() {
        assertEquals("Go to Declaration should not offer a target", emptyList<PsiElement>(), declarationTargets)
    }

    protected val selectedEditor: FileEditor? get() = FileEditorManager.getInstance(project).selectedEditor
    protected fun undo(editor: FileEditor? = selectedEditor, orProject: Boolean = false) {
        val manager = UndoManager.getInstance(project);
        val target = if (orProject && !manager.isUndoAvailable(editor)) null else editor
        assertTrue("One native Undo must restore the complete action", manager.isUndoAvailable(target));
        val dialog = TestDialogManager.setTestDialog(TestDialog.OK)
        try {
            manager.undo(target)
        } finally {
            TestDialogManager.setTestDialog(dialog)
        }; PsiDocumentManager.getInstance(project).commitAllDocuments()
    }

    protected fun components(component: Component): List<Component> =
        listOf(component) + if (component is Container) component.components.flatMap(::components) else emptyList()

    protected fun await(message: String, seconds: Int = 20, condition: () -> Boolean) {
        PlatformTestUtil.waitWithEventsDispatching(message, { condition() }, seconds)
    }

    protected fun tempDirectory(prefix: String): Path {
        val directory = Files.createTempDirectory(prefix); Disposer.register(
            testRootDisposable,
            Disposable { FileUtil.delete(directory.toFile()) }); return directory
    }

    protected fun fakeTool(path: Path, script: String): Path {
        Files.writeString(path, script); assertTrue(path.toFile().setExecutable(true)); return path
    }

    protected fun newRunner(service: Boolean = false, parent: Disposable = testRootDisposable): DbtRunner {
        val runner = DbtRunner(project); Disposer.register(parent, runner); if (service) project.replaceService(
            DbtRunner::class.java,
            runner,
            parent
        ); return runner
    }

    protected fun runConfiguration(name: String = "dbt command"): DbtRunConfiguration {
        val factory =
            ConfigurationTypeUtil.findConfigurationType(DbtConfigurationType::class.java).configurationFactories.first()
        return DbtRunConfiguration(project, factory, name).also { it.root = workspace.roots.first().directory.path }
    }
}
