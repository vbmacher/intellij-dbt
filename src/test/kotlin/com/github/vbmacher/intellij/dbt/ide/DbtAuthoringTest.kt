package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.navigation.DbtSqlAnalysis
import com.github.vbmacher.intellij.dbt.project.DbtDeclarationSource
import com.github.vbmacher.intellij.dbt.project.DbtSymbol
import com.github.vbmacher.intellij.dbt.project.DbtWorkspace
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiElement
import com.intellij.testFramework.utils.parameterInfo.MockCreateParameterInfoContext
import com.intellij.testFramework.utils.parameterInfo.MockParameterInfoUIContext
import com.intellij.testFramework.utils.parameterInfo.MockUpdateParameterInfoContext
import org.junit.Assert.*


/** SQL authoring assistance: templates, completion, column analysis, macro Parameter Info and static inspection suppression. */
class DbtAuthoringTest : DbtTestCase() {
    fun testPackageCompletionIncludesShadowedInstalledPackages() {
        project()
        add("models/orders.sql", "select 1 as id")
        add("dbt_packages/finance/dbt_project.yml", "name: finance\nconfig-version: 2\n")
        add("dbt_packages/finance/models/orders.sql", "select 2 as id")
        val file = configure("models/report.sql", "select * from {{ ref('<caret>', 'orders') }}")
        assertTrue(workspace.declarations(DbtSymbol.RefKinds, "orders", root, "finance").isNotEmpty())
        assertEquals(
            setOf("analytics", "finance"),
            DbtCompletion.suggestions(file, myFixture.getCaretOffset()).map { it.text }.toSet()
        )
    }

    fun testUnrelatedCompletionDoesNotDiscoverTheWorkspace() {
        project()
        val file = configure("frontend/index.js", "const value = <caret>")
        DbtDeclarationSource.EP.getPoint(null).registerExtension(object : DbtDeclarationSource {
            override fun declarations(workspace: DbtWorkspace): List<DbtSymbol> =
                throw AssertionError("Unrelated completion must not scan dbt resources")
        }, getTestRootDisposable())
        assertEquals(emptyList<DbtSuggestion>(), DbtCompletion.suggestions(file, myFixture.getCaretOffset()))
    }

    fun testBundledTemplatesLoadThroughNativeTemplateSettings() {
        val settings = com.intellij.codeInsight.template.impl.TemplateSettings.getInstance()
        listOf("dbtref", "dbtsource", "dbtconfig").forEach { name ->
            assertNotNull(name, settings.getTemplate(name, "dbt"))
        }
    }

    private fun schema() {
        project()
        add("models/orders.sql", "select 1 as id, 2 as amount")
        add(
            "models/schema.yml",
            "models: [{name: orders, columns: [{name: id, data_type: int, description: Identifier}, {name: amount, data_type: decimal}]}]\n" +
                    "sources: [{name: raw, tables: [{name: events, columns: [{name: event_id}]}]}, {name: crm, tables: [{name: customers}]}]\n"
        )
    }

    fun testNativeRefCompletionPreservesQuotesAndCaret() {
        schema()
        configure("models/report.sql", "select * from {{ ref('ord<caret>') }}")
        val items = myFixture.completeBasic().orEmpty().toList().map { it.getLookupString() }
        if (items.isNotEmpty()) {
            assertTrue(items.contains("orders"))
            myFixture.getLookup().setCurrentItem(myFixture.lookupElements!!.find { it.getLookupString() == "orders" }!!)
            myFixture.finishLookup('\n')
        }
        assertEquals("select * from {{ ref('orders') }}", myFixture.getFile().getText())
        assertEquals("select * from {{ ref('orders".length, myFixture.getCaretOffset())
    }

    fun testSourceTablesAreScopedAndRefreshAfterUnsavedChanges() {
        schema()
        val file = configure("models/report.sql", "select * from {{ source('raw', '<caret>') }}")

        fun names() = DbtCompletion.suggestions(file, myFixture.getCaretOffset()).map { it.text }

        assertEquals(listOf("events"), names())
        val properties = workspace.files.find { it.getName() == "schema.yml" }!!
        edit(properties, properties.getText().replace("name: events", "name: activity"))
        assertEquals(listOf("activity"), names())
    }

    fun testColumnCompletionRespectsAliasAndCteProjection() {
        schema()
        val file =
            configure("models/report.sql", "with o as (select id from {{ ref('orders') }}) select x.<caret> from o x")
        assertEquals(listOf("id"), DbtCompletion.suggestions(file, myFixture.getCaretOffset()).map { it.text })
    }

    fun testLineageDistinguishesPassthroughAndCalculation() {
        schema()
        val file = configure(
            "models/report.sql",
            "select o.id as order_id, o.amount * 2 as gross from {{ ref('orders') }} o<caret>"
        )
        val result = DbtSqlAnalysis.get(workspace, file)!!
        assertEquals(listOf("order_id", "gross"), result.columns.map { it.name })
        assertTrue(result.columns.first().direct)
        assertEquals(listOf("id"), result.columns.first().inputs.map { it.name })
        assertFalse(result.columns[1].direct)
        assertEquals(listOf("amount"), result.columns[1].inputs.map { it.name })
        assertTrue(result.columns.all { it.complete })
    }

    fun testUnionRecordsEveryBranchAndUnknownSchemaStaysPartial() {
        schema()
        val file = configure(
            "models/report.sql",
            "select id from {{ ref('orders') }} union all select event_id from {{ source('raw', 'events') }}<caret>"
        )
        val columns = DbtSqlAnalysis.get(workspace, file)!!.columns
        assertEquals(setOf("id", "event_id"), columns.first().inputs.map { it.name }.toSet())
        assertFalse(columns.first().direct)
        edit(file, "select * from unknown_relation")
        val result = DbtSqlAnalysis.get(workspace, file)!!
        assertTrue(result.issues.isNotEmpty());
        assertTrue(result.wildcards.isEmpty())
    }

    fun testExpandQualifiedWildcardPreservesSqlAndDoesNotExpandCount() {
        schema()
        configure("models/report.sql", "select o.<caret>* from {{ ref('orders') }} o")
        try {
            myFixture.launchAction(myFixture.findSingleIntention("Expand dbt SQL columns"))
        } catch (e: AssertionError) {
            throw AssertionError(
                "After intention: PSI=" + myFixture.file.text + " DOCUMENT=" + myFixture.editor.document.text,
                e
            )
        }
        assertEquals("select o.id, o.amount from {{ ref('orders') }} o", myFixture.getFile().getText())
        configure("models/count.sql", "select count(<caret>*) from {{ ref('orders') }}")
        assertFalse(myFixture.getAvailableIntentions().any { it.getText() == "Expand dbt SQL columns" })
    }

    fun testMacroSignatureIncludesDefaultAndDocumentedType() {
        project()
        add("macros/money.sql", "{% macro money(amount, scale=2) %}{{ amount }}{% endmacro %}")
        add("macros/schema.yml", "macros: [{name: money, arguments: [{name: amount, type: decimal}]}]")
        val w = workspace
        val m = w.symbols.find { s -> s.kind == "macro" && !s.isProperty }!!
        assertEquals(listOf("amount: decimal", "scale=2"), DbtCompletion.parameters(w, m).map { it.second })
    }

    fun testCompletionKeepsModelVersionsSeparateAndHandlesUnfinishedQuote() {
        schema()
        add("models/versioned_v1.sql", "select 1 as id")
        add("models/versioned_v2.sql", "select 2 as id")
        add("models/versions.yml", "models: [{name: versioned, latest_version: 2, versions: [{v: 1}, {v: 2}]}]")
        val file = configure("models/report.sql", "select * from {{ ref('ord<caret>")
        assertTrue(DbtCompletion.suggestions(file, myFixture.getCaretOffset()).any { it.text == "orders" })
        val version = configure("models/version_report.sql", "select * from {{ ref('versioned', v='<caret>') }}")
        assertEquals(
            setOf("1", "2"),
            DbtCompletion.suggestions(version, myFixture.getCaretOffset()).map { it.text }.toSet()
        )
    }

    fun testOfflineSchemasSelectVersionAndIgnoreUnrelatedYaml() {
        project()
        val properties = add("models/schema.yml", "models: [{name: orders, columns: wrong}]\n")
        val unrelated = add("models/service.yml", "services: {http: {port: 8080}}\n")
        val providers = DbtYamlSchemaProvider().getProviders(getProject())
        val selected = providers.filter { it.isAvailable(properties.getVirtualFile()) }
        assertEquals(listOf("dbt properties (current)"), selected.map { it.getName() }.toList())
        assertNotNull(selected.first().getSchemaFile())
        assertTrue(providers.all { !it.isAvailable(unrelated.virtualFile) })
        configureSettings { it.dbtVersion = "1.7" }
        assertEquals(
            listOf("dbt properties (queries)"),
            providers.filter { it.isAvailable(properties.getVirtualFile()) }.map { it.getName() }.toList()
        )
    }

    fun testNativeStaticInspectionUsesExactMacroAndColumnRanges() {
        schema()
        add("macros/money.sql", "{% macro money(amount, scale=2) %}{{ amount }}{% endmacro %}")
        val file = configure(
            "models/report.sql",
            "select o.missing<caret> from {{ ref('orders') }} o where {{ money(1, unknown_argument=2) }} = 1"
        )
        myFixture.enableInspections(DbtStaticInspection())
        val problems = myFixture.doHighlighting()
            .filter { h -> h.description?.let { d -> d.startsWith("Unknown column") || d.startsWith("Unknown macro") } == true }
        assertEquals(
            setOf("missing", "unknown_argument"),
            problems.map { h -> file.getText().substring(h.getStartOffset(), h.getEndOffset()) }.toSet()
        )
        assertTrue(problems.all { it.getSeverity() == com.intellij.lang.annotation.HighlightSeverity.WARNING })
    }

    fun testJoinAndFilterDependenciesAreSeparateFromValueDerivation() {
        schema()
        val file = configure(
            "models/report.sql",
            "select a.id from {{ ref('orders') }} a join {{ ref('orders') }} b on a.id = b.id where b.amount > 0<caret>"
        )
        val analysis = DbtSqlAnalysis.get(workspace, file)!!
        assertEquals(listOf("id"), analysis.columns.first().inputs.map { it.name })
        assertEquals(setOf("join", "filter"), analysis.columns.first().contextInputs.map { it.second }.toSet())
    }

    fun testCurrentSqlProjectionOverridesStaleYamlColumnNames() {
        schema()
        val orders = workspace.files.find { it.getName() == "orders.sql" }!!
        edit(orders, "select 1 as id")
        val file = configure("models/report.sql", "select o.<caret> from {{ ref('orders') }} o")
        assertEquals(listOf("id"), DbtCompletion.suggestions(file, myFixture.getCaretOffset()).map { it.text })
        edit(file, "select o.amount from {{ ref('orders') }} o")
        val analysis = DbtSqlAnalysis.get(workspace, file)!!
        assertTrue(analysis.problems.any { it.message.contains("amount") })
        assertFalse(analysis.columns.first().complete)
    }

    fun testAggregatesWindowsAndContractNamesUseAuthoredOutputRanges() {
        schema()
        val file = configure(
            "models/report.sql",
            "select sum(o.amount) as total, row_number() over (partition by o.id order by o.amount) as position from {{ ref('orders') }} o<caret>"
        )
        add(
            "models/report.yml",
            "models: [{name: report, config: {contract: {enforced: true}}, columns: [{name: total, data_type: decimal}]}]"
        )
        val analysis = DbtSqlAnalysis.get(workspace, file)!!
        assertEquals(listOf("total", "position"), analysis.columns.map { it.name })
        assertEquals(listOf("amount"), analysis.columns.first().inputs.map { it.name })
        assertEquals(setOf("id", "amount"), analysis.columns[1].inputs.map { it.name }.toSet())
        assertTrue(analysis.columns.all { c -> c.complete && !c.direct })
        myFixture.enableInspections(DbtStaticInspection())
        val problem =
            myFixture.doHighlighting().find { h -> h.description?.contains("has no column declaration") == true }!!
        assertEquals("position", file.getText().substring(problem.getStartOffset(), problem.getEndOffset()))
    }

    fun testDynamicProjectionNamesDoNotAppearAsInventedSqlColumns() {
        schema()
        add("models/dynamic.sql", "select {{ var('column_name') }} from {{ source('raw', 'events') }}")
        val file = configure("models/report.sql", "select d.<caret> from {{ ref('dynamic') }} d")
        assertTrue(DbtCompletion.suggestions(file, myFixture.getCaretOffset()).isEmpty())
    }

    private fun macroFixture() {
        project()
        add("macros/money.sql", "{% macro money(amount, scale=2, currency='USD') %}{{ amount }}{% endmacro %}")
        add(
            "macros/schema.yml",
            "macros: [{name: money, arguments: [{name: amount, type: decimal}, {name: scale, type: integer}]}]\n"
        )
    }

    fun testNativeParameterInfoActionDispatchCreatesControllerForDbtSql() {
        macroFixture()
        val file = configure("models/report.sql", "select {{ money(12, <caret>2) }} as amount")
        val editor = myFixture.getEditor()
        val language = com.intellij.psi.util.PsiUtilCore.getLanguageAtOffset(file, myFixture.getCaretOffset())
        val handlers = com.intellij.codeInsight.hint.ShowParameterInfoHandler.getHandlers(
            getProject(),
            language, file.getViewProvider().getBaseLanguage()
        )
        assertTrue(
            "Registered handler for " + language + " / " + file.getFileType().getName(),
            handlers.any { it is DbtParameterInfoHandler })
        myFixture.performEditorAction("ParameterInfo")
        com.intellij.openapi.application.impl.NonBlockingReadActionImpl.waitForAsyncTaskCompletion()
        com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
        val controller = com.intellij.codeInsight.hint.ParameterInfoControllerBase.findControllerAtOffset(
            editor,
            file.getText().indexOf("money(") + "money".length
        )
        assertNotNull(
            "The native Parameter Info action must create a hint controller for " + language + " / " + file.getFileType()
                .getName(), controller
        )
        try {
            val signature = controller!!.objects.first() as DbtMacroSignature
            assertEquals("money", signature.name)
            assertEquals(
                listOf("amount: decimal", "scale=2: integer", "currency='USD'"),
                signature.parameters.map { it.second })
        } finally {
            com.intellij.openapi.util.Disposer.dispose(controller!!)
        }
    }

    private class ParameterInfoCapture(editor: com.intellij.openapi.editor.Editor, file: com.intellij.psi.PsiFile) :
        MockCreateParameterInfoContext(editor, file) {
        var anchor = -1
        var hinted: PsiElement? = null
        override fun showHint(
            element: PsiElement,
            offset: Int,
            handler: com.intellij.lang.parameterInfo.ParameterInfoHandler<*, *>
        ) {
            anchor = offset
            hinted = element
        }
    }

    fun testNativeNamedArgumentHintHasExactSignatureAnchorAndHighlightRange() {
        macroFixture()
        val file = configure("models/report.sql", "{{ money(42, currency='EUR', scale=<caret>3) }}")
        val handler = DbtParameterInfoHandler()
        val create = ParameterInfoCapture(myFixture.getEditor(), file)
        val owner = handler.findElementForParameterInfo(create)
        assertSame(file, owner)
        assertEquals(1, create.itemsToShow!!.size)
        handler.showParameterInfo(owner!!, create)
        assertSame(file, create.hinted);
        assertEquals(file.getText().indexOf("money(") + "money".length, create.anchor)
        assertEquals('(', file.text[create.anchor])
        val signature = create.itemsToShow!!.first() as DbtMacroSignature
        val update = MockUpdateParameterInfoContext(myFixture.getEditor(), file, create.getItemsToShow())
        handler.updateParameterInfo(handler.findElementForUpdatingParameterInfo(update)!!, update)
        assertEquals(1, update.getCurrentParameter())
        val ui = MockParameterInfoUIContext<PsiElement>(file)
        ui.setCurrentParameterIndex(update.getCurrentParameter())
        handler.updateUI(signature, ui)
        val expected = "money(amount: decimal, scale=2: integer, currency='USD')"
        assertEquals(expected, ui.getText())
        assertEquals(expected.indexOf("scale"), ui.getHighlightStart())
        assertEquals(expected.indexOf("scale") + "scale=2: integer".length, ui.getHighlightEnd())
        assertEquals("scale=2: integer", ui.getText().substring(ui.getHighlightStart(), ui.getHighlightEnd()))
    }

    fun testNestedCommaDoesNotAdvanceThePositionalParameterAndLeavingCallClosesHint() {
        macroFixture()
        val file = configure("models/report.sql", "{{ money(coalesce(1, 2), <caret>4) }}")
        val handler = DbtParameterInfoHandler()
        val create = MockCreateParameterInfoContext(myFixture.getEditor(), file)
        assertNotNull(handler.findElementForParameterInfo(create))
        var removed = false
        val update = object : MockUpdateParameterInfoContext(myFixture.getEditor(), file, create.getItemsToShow()) {
            override fun removeHint() {
                removed = true
            }
        }
        handler.updateParameterInfo(handler.findElementForUpdatingParameterInfo(update)!!, update)
        assertEquals(1, update.getCurrentParameter())
        myFixture.getEditor().getCaretModel().moveToOffset(file.getTextLength())
        val owner = handler.findElementForUpdatingParameterInfo(update)
        assertNull(owner)
        DbtParameterInfoHandler::class.java.getMethod(
            "updateParameterInfo",
            PsiElement::class.java,
            com.intellij.lang.parameterInfo.UpdateParameterInfoContext::class.java
        )
            .invoke(handler, owner, update)
        assertTrue(removed)
    }

    fun testAmbiguousMacroDefinitionsNeverShowAnInventedUniqueSignature() {
        project()
        add("macros/one.sql", "{% macro collide(a) %}{{ a }}{% endmacro %}")
        add("macros/two.sql", "{% macro collide(a, b=2) %}{{ a }}{% endmacro %}")
        val file = configure("models/report.sql", "{{ collide(<caret>1) }}")
        val create = MockCreateParameterInfoContext(myFixture.getEditor(), file)
        assertNull(DbtParameterInfoHandler().findElementForParameterInfo(create))
        assertNull(create.getItemsToShow())
    }

    private fun suppressionFixture() {
        project()
        add("models/orders.sql", "select 1 as id")
        add("macros/money.sql", "{% macro money(amount, scale=2) %}{{ amount }}{% endmacro %}")
        myFixture.enableInspections(DbtStaticInspection())
    }

    private val diagnostics: List<HighlightInfo>
        get() = myFixture.doHighlighting()
            .filter { h -> h.description?.let { d -> d.startsWith("Unknown macro argument") || d.startsWith("Unknown column") } == true }
            .toList()

    private fun nativeSuppression(name: String): IntentionAction {
        val warning =
            diagnostics.find { h -> h.startOffset <= myFixture.getCaretOffset() && h.endOffset >= myFixture.getCaretOffset() }!!
        val element = myFixture.getFile().findElementAt(myFixture.getCaretOffset())
        val action = warning.findRegisteredQuickFix<IntentionAction> { descriptor, _ ->
            descriptor.getOptions(element!!, myFixture.editor).firstOrNull { it.text == name }
        }

        assertNotNull("Native inspection options must expose " + name, action)
        return action!!
    }

    fun testNativeStatementSuppressionRemovesColumnAndMacroWarningsAndUndoRestoresThem() {
        suppressionFixture()
        val file = configure(
            "models/report.sql",
            "select o.miss<caret>ing from {{ ref('orders') }} o where {{ money(1, typo=2) }} = 1"
        )
        val before = file.getText()
        assertEquals(2, diagnostics.size)
        assertTrue(diagnostics.all { it.getSeverity() == HighlightSeverity.WARNING })
        myFixture.launchAction(nativeSuppression("Suppress for statement"))
        assertEquals("-- noinspection DbtStaticAnalysis\n" + before, file.getText())
        assertTrue(diagnostics.isEmpty())
        undo(orProject = true)
        assertEquals(before, file.getText())
        assertEquals(2, diagnostics.size)
    }

    fun testStatementDirectiveStopsAtSqlSemicolonAndIgnoresQuotedSemicolons() {
        suppressionFixture()
        configure(
            "models/statements.sql", "--noinspection OtherInspection, DbtStaticAnalysis\n" +
                    "select ';' as value, {{ money(1, ignored=2) }} as amount;\n" +
                    "select {{ money(1, visible<caret>=2) }} as amount"
        )
        val warnings = diagnostics
        assertEquals(1, warnings.size)
        assertEquals(
            "visible",
            myFixture.getFile().getText().substring(warnings.first().startOffset, warnings.first().endOffset)
        )
    }

    fun testNativeFileSuppressionCoversAllStatementsAndOneUndoRestoresSource() {
        suppressionFixture()
        val file = configure(
            "models/statements.sql",
            "select {{ money(1, first=2) }};\nselect {{ money(1, sec<caret>ond=2) }}"
        )
        val before = file.getText()
        assertEquals(2, diagnostics.size)
        myFixture.launchAction(nativeSuppression("Suppress for file"))
        assertEquals("--file: noinspection DbtStaticAnalysis\n" + before, file.getText())
        assertTrue(diagnostics.isEmpty())
        undo(orProject = true)
        assertEquals(before, file.getText())
        assertEquals(2, diagnostics.size)
    }

    fun testSuppressionTextInsideSqlStringsAndUnrelatedIdsDoNotHideProblems() {
        suppressionFixture()
        configure(
            "models/literals.sql", "--noinspection DifferentInspection\n" +
                    "select '--file: noinspection DbtStaticAnalysis; --noinspection ALL' as label, {{ money(1, ty<caret>po=2) }} as amount"
        )
        assertEquals(1, diagnostics.size)
        assertTrue(diagnostics.first().getDescription()!!.contains("typo"))
    }

    fun testFileDirectiveInsideLaterStatementDoesNotSuppressEntireFile() {
        suppressionFixture()
        configure(
            "models/later.sql",
            "select {{ money(1, first=2) }};\n--file: noinspection DbtStaticAnalysis\nselect {{ money(1, sec<caret>ond=2) }}"
        )
        assertEquals(2, diagnostics.size)
    }

    fun testSuppressionActionsAreNotOfferedOutsideDbtSqlResources() {
        suppressionFixture()
        val file = configure("notes.sql", "select <caret>1")
        assertTrue(DbtStaticInspection().getSuppressActions(file).isEmpty())
    }

    fun testNativeSeveritySettingRemainsEffectiveAndCanStillBeSuppressed() {
        suppressionFixture()
        configure("models/severity.sql", "select {{ money(1, ty<caret>po=2) }}")
        val initial = diagnostics.first()
        val key = com.intellij.codeInsight.daemon.HighlightDisplayKey.find(initial.inspectionToolId!!)!!
        assertNotNull(key)
        val profile = com.intellij.profile.codeInspection.InspectionProjectProfileManager.getInstance(getProject())
            .getCurrentProfile()
        val previous = profile.getErrorLevel(key, myFixture.getFile())
        try {
            profile.setErrorLevel(key, com.intellij.codeHighlighting.HighlightDisplayLevel.ERROR, getProject())
            com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(getProject())
                .restart(myFixture.getFile(), "dbt inspection severity changed")
            assertEquals(HighlightSeverity.ERROR, diagnostics.first().getSeverity())
            myFixture.launchAction(nativeSuppression("Suppress for statement"))
            assertTrue(diagnostics.isEmpty())
        } finally {
            profile.setErrorLevel(key, previous, getProject())
        }
    }
}
