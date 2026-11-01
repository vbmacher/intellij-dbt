package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.navigation.DbtSqlAnalysis
import com.github.vbmacher.intellij.dbt.project.DbtSymbol
import com.github.vbmacher.intellij.dbt.project.DbtSymbolElement
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.refactoring.BaseRefactoringProcessor.ConflictsInTestsException
import com.intellij.refactoring.safeDelete.SafeDeleteProcessor
import com.intellij.testFramework.TestActionEvent
import org.junit.Assert.*

/** Native Safe Delete must preserve valid SQL, report consumers, and restore the complete edit in one undo. */
class DbtSafeDeleteTest : DbtTestCase() {
    private fun cte(file: PsiFile, name: String): DbtSymbolElement {
        val analysis = DbtSqlAnalysis.get(workspace, file)!!
        val value = analysis.ctes.find { it.name == name }!!
        val nameRange = analysis.deletions.find { it.first.getStartOffset() == value.range.getStartOffset() }!!.first
        return DbtSymbol("cte", name, workspace.root(file)!!.name, file, nameRange).element
    }

    private fun projection(file: PsiFile, name: String): DbtSymbolElement {
        val value = DbtSqlAnalysis.get(workspace, file)!!.columns.find { it.name == name }!!
        return DbtSymbol("sql_projection", name, workspace.root(file)!!.name, file, value.range).element
    }

    private fun delete(element: PsiElement) {
        open(element.getContainingFile(), element.getTextOffset())
        val processor = SafeDeleteProcessor.createInstance(getProject(), null, arrayOf(element), false, false, false)
        processor.setPreviewUsages(false)
        processor.run()
    }

    private val safeDelete get() = ActionManager.getInstance().getAction("SafeDelete")

    /** The registered Safe Delete action, updated for a Project view selection of `file`. */
    private fun projectViewSafeDelete(file: PsiFile): AnActionEvent {
        val context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, getProject())
            .add(CommonDataKeys.PSI_ELEMENT, file)
            .add(PlatformCoreDataKeys.PSI_ELEMENT_ARRAY, arrayOf<PsiElement>(file))
            .add(LangDataKeys.CONTEXT_LANGUAGES, arrayOf(file.language)).build()
        val event = TestActionEvent.createTestEvent(safeDelete, context)
        safeDelete.update(event)
        return event
    }

    fun testUnusedCteDeletionLeavesValidWithClauseAndUndoRestoresSource() {
        project()
        val before = "with unused as (select 0 as id), kept as (select 1 as id) select id from kept"
        val file = add("models/orders.sql", before)
        delete(cte(file, "unused"))
        assertEquals("with kept as (select 1 as id) select id from kept", file.getText().replace(Regex("\\s+"), " "))
        assertTrue(DbtSqlAnalysis.get(workspace, file)!!.issues.isEmpty())
        undo();
        assertEquals(before, file.getText())
    }

    fun testUnusedProjectionDeletionPreservesOtherColumnsAndUndo() {
        project()
        val before = "select 1 as unused, 2 as kept"
        val file = add("models/orders.sql", before)
        delete(projection(file, "unused"))
        assertEquals("select 2 as kept", file.getText().replace(Regex("\\s+"), " "))
        assertEquals(listOf("kept"), DbtSqlAnalysis.get(workspace, file)!!.columns.map { it.name })
        undo();
        assertEquals(before, file.getText())
    }

    fun testUsedCteHasNativeConflictsAndLeavesSourceUnchanged() {
        project()
        val before = "with used as (select 1 as id) select id from used"
        val file = add("models/orders.sql", before)
        org.junit.Assert.assertThrows(ConflictsInTestsException::class.java) { delete(cte(file, "used")) }
        assertEquals(before, file.getText())
    }

    fun testDownstreamColumnConsumerBlocksProjectionDeletion() {
        project()
        val before = "select 1 as id, 2 as amount"
        val file = add("models/orders.sql", before)
        val downstream = add("models/report.sql", "select amount from {{ ref('orders') }}")
        org.junit.Assert.assertThrows(ConflictsInTestsException::class.java) { delete(projection(file, "amount")) }
        assertEquals(before, file.getText())
        assertEquals("select amount from {{ ref('orders') }}", downstream.getText())
    }

    fun testUnusedModelAndItsYamlPropertiesDeleteTogetherAndUndo() {
        project()
        val source = add("models/orders.sql", "select 1 as id")
        add("models/other.sql", "select 2 as id")
        val before = "models:\n- name: orders\n  description: Orders\n- name: other\n  description: Other\n"
        val yaml = add("models/schema.yml", before)
        val root = workspace.root(source)!!
        val model = workspace.codeSymbols(source).find { it.kind == "model" }!!
        delete(model.element)
        assertNull(root.directory.findFileByRelativePath("models/orders.sql"))
        assertFalse(yaml.getText().contains("name: orders"));
        assertTrue(yaml.getText().contains("name: other"))
        undo()
        assertEquals("select 1 as id", root.psiFile("models/orders.sql")!!.getText())
        assertEquals(before, yaml.getText())
    }

    fun testUnusedMacroDeletesOnlyItsBlockAndUndo() {
        project()
        val unused = "{% macro unused() %}1{% endmacro %}"
        val kept = "{% macro kept() %}2{% endmacro %}"
        val before = unused + "\n" + kept + "\n"
        val file = add("macros/helpers.sql", before)
        val definition = workspace.symbols.find { s -> s.kind == "macro" && s.name == "unused" }!!
        delete(definition.element)
        assertFalse(file.getText().contains("macro unused"));
        assertTrue(file.getText().contains(kept))
        undo();
        assertEquals(before, file.getText())
    }

    fun testNativeEditorActionDeletesUnusedCteAndOneUndoRestoresIt() {
        project()
        val before = "with unused as (select 1 as id), kept as (select 2 as id) select id from kept"
        configure("models/orders.sql", before.replace("unused", "un<caret>used"))
        myFixture.performEditorAction("SafeDelete")
        assertEquals(
            "with kept as (select 2 as id) select id from kept",
            myFixture.getFile().getText().replace(Regex("\\s+"), " ")
        )
        myFixture.performEditorAction(IdeActions.ACTION_UNDO)
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments()
        assertEquals(before, myFixture.getFile().getText())
    }

    fun testNativeEditorActionReportsUsedCteAndPreservesSource() {
        project()
        val before = "with used as (select 1 as id) select id from used"
        configure("models/orders.sql", before.replaceFirst("used", "us<caret>ed"))
        org.junit.Assert.assertThrows(ConflictsInTestsException::class.java) { myFixture.performEditorAction("SafeDelete") }
        assertEquals(before, myFixture.getFile().getText())
    }

    fun testNativeProjectFileActionMapsModelIdentityAndBlocksResolvingConsumers() {
        project()
        val source = add("models/orders.sql", "select 1 as id")
        val yaml = add("models/schema.yml", "models: [{name: orders, description: Keep}]\n")
        val consumer = add("models/report.sql", "select * from {{ ref('orders') }}")
        val event = projectViewSafeDelete(source)
        assertTrue("Native Project file selection supports Safe Delete", event.getPresentation().isEnabled)
        org.junit.Assert.assertThrows(ConflictsInTestsException::class.java) { safeDelete.actionPerformed(event) }
        assertTrue(source.isValid)
        assertEquals("select 1 as id", source.getText())
        assertEquals("models: [{name: orders, description: Keep}]\n", yaml.getText())
        assertEquals("select * from {{ ref('orders') }}", consumer.getText())
    }

    fun testDynamicBuiltinsRefBlocksModelDeletion() {
        project()
        val source = add("models/orders.sql", "select 1 as id")
        add("models/report.sql", "select * from {{ builtins.ref(var('model_name')) }}")
        val event = projectViewSafeDelete(source)
        val conflict =
            org.junit.Assert.assertThrows(ConflictsInTestsException::class.java) { safeDelete.actionPerformed(event) }
        assertTrue(conflict.message, conflict.message.orEmpty().contains("Dynamic consumers exist"))
        assertTrue(source.isValid)
    }

    fun testForeignYamlAndPlainTextDoNotGainDbtDeletionTargets() {
        project()
        val foreign = configure("services/service.yml", "services: {name: ord<caret>ers}\n")
        assertTrue(DbtSafeDelete.targets(foreign.findElementAt(myFixture.getCaretOffset())!!).isEmpty())
        assertFalse(DbtSafeDeleteSupport().isSafeDeleteAvailable(foreign.findElementAt(myFixture.getCaretOffset())!!))
    }
}
