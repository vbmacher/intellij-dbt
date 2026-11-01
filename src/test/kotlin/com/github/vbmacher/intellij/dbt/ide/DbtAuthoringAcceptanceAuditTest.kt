package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.lineage.DbtColumnLineage
import com.github.vbmacher.intellij.dbt.navigation.DbtNavigation
import com.github.vbmacher.intellij.dbt.navigation.DbtSqlAnalysis
import com.github.vbmacher.intellij.dbt.project.*
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import org.junit.Assert.*


/** User-visible regressions found while checking the authoring ticket acceptance criteria. */
class DbtAuthoringAcceptanceAuditTest : DbtTestCase() {
    private fun model(name: String) = workspace.symbols.find { s -> s.kind == "model" && s.isCode && s.name == name }!!

    fun testEditPropertiesNavigatesToYamlWhenSqlHasMergedPropertiesMetadata() {
        project()
        val yaml = add("models/orders.yml", "models:\n  - name: orders\n    description: Orders\n")
        configure("models/orders.sql", "<caret>select 1 as id")
        val resource = model("orders")
        assertTrue(resource.mapping != null)
        DbtPropertiesEditor.show(workspace, resource)
        assertEquals(yaml.virtualFile, FileEditorManager.getInstance(getProject()).selectedFiles.first())
    }


    fun testCreationCanReviewNewNameAndPropertiesAndUndoAllThreeChanges() {
        project("model-paths: [transforms]\n")
        val before = "select * from {{ ref('missing') }}"
        val caller = configure("transforms/report.sql", "select * from {{ ref('miss<caret>ing') }}")
        val proposal = DbtCreateResourceFix.proposal(caller, myFixture.caretOffset)!!
        val root = proposal.root.directory
        assertTrue(
            DbtCreateResourceFix.create(
                proposal,
                "transforms/new_orders.sql",
                includeProperties = true,
                logicalName = ("new_orders")
            ).isSuccess
        )
        assertEquals("select * from {{ ref('new_orders') }}", caller.text)
        assertEquals(
            "select\n    1 as id\n",
            String(root.findFileByRelativePath("transforms/new_orders.sql")!!.contentsToByteArray(), Charsets.UTF_8)
        )
        val properties = workspace.files.find { it.name == "new_orders.yml" }!!
        assertEquals("version: 2\nmodels:\n  - name: new_orders\n    columns:\n      - name: id\n", properties.text)
        assertEquals(
            "new_orders",
            DbtNavigation.inFile(caller).flatMap { it.candidates }.filterIsInstance<DbtSymbolElement>()
                .first { it.symbol.kind == "model" }.symbol.name
        )
        undo(orProject = true)
        assertEquals(before, caller.text)
        assertNull(root.findFileByRelativePath("transforms/new_orders.sql"))
        assertNull(root.findFileByRelativePath("transforms/new_orders.yml"))
    }

    fun testCreationRejectsIgnoredGeneratedForeignAndMismatchedDestinationsWithoutWrites() {
        project("model-paths: [models]\ntarget-path: models/generated\n")
        add(".dbtignore", "models/private/\n")
        add("models/foreign/dbt_project.yml", "name: foreign\n")
        val caller = configure("models/report.sql", "select * from {{ ref('miss<caret>ing') }}")
        val plan = DbtCreateResourceFix.proposal(caller, myFixture.caretOffset)!!
        listOf(
            "models/private/missing.sql", "models/generated/missing.sql", "models/foreign/models/missing.sql",
            "../missing.sql", "models/different.sql", "models/missing.yml"
        ).forEach { path ->
            assertTrue(path, DbtCreateResourceFix.create(plan, path).isFailure)
            assertNull(path, plan.root.directory.findFileByRelativePath(path))
        }
        assertEquals(plan.origin!!.third, caller.text)
    }

    fun testCreationRejectsNewDuplicateNameAndExistingPropertiesAndChangedCall() {
        project()
        val caller = configure("models/report.sql", "select * from {{ ref('miss<caret>ing') }}")
        val plan = DbtCreateResourceFix.proposal(caller, myFixture.caretOffset)!!
        val existing = add("models/missing.yml", "# Keep this file\nmodels: []\n")
        assertTrue(DbtCreateResourceFix.create(plan, plan.path, includeProperties = true).isFailure)
        assertEquals("# Keep this file\nmodels: []\n", existing.text)
        add("models/other.sql", "select 2 as id")
        assertTrue(DbtCreateResourceFix.create(plan, "models/nested/other.sql", logicalName = ("other")).isFailure)
        edit(caller, "select * from {{ ref('different') }}")
        assertTrue(DbtCreateResourceFix.create(plan, plan.path).isFailure)
        assertNull(plan.root.directory.findFileByRelativePath(plan.path))
    }

    fun testNewSourceCreationResolvesAndExistingSourceEditRejectsStaleYaml() {
        project()
        val caller = configure("models/report.sql", "select * from {{ source('c<caret>rm', 'customers') }}")
        val plan = DbtCreateResourceFix.proposal(caller, myFixture.caretOffset)!!
        assertTrue(plan.file == null)
        assertTrue(DbtCreateResourceFix.create(plan, plan.path).isSuccess)
        assertTrue(workspace.symbols.any { s -> s.kind == "source_table" && s.owner == "crm" && s.name == "customers" })
        val yaml = workspace.files.find { it.name == "crm_sources.yml" }!!
        val missing = configure("models/another.sql", "select * from {{ source('crm', 'ord<caret>ers') }}")
        val next = DbtCreateResourceFix.proposal(missing, myFixture.caretOffset)!!
        edit(yaml, yaml.text + "# concurrent change\n")
        val current = yaml.text
        assertTrue(DbtCreateResourceFix.create(next, next.path).isFailure)
        assertEquals(current, yaml.text)
    }

    fun testPackagesVersionsAndDynamicCallsNeverOfferWritableCreation() {
        project()
        listOf("ref('finance', 'missing')", "ref('missing', v=2)", "ref(var('name'))", "source(var('name'), 'missing')")
            .withIndex().forEach { (index, call) ->
                val file = configure("models/negative_$index.sql", "select * from {{ " + call + "<caret> }}")
                assertTrue(
                    call,
                    DbtCreateResourceFix.proposal(
                        file,
                        file.text.indexOf("missing").takeIf { it >= 0 } ?: myFixture.caretOffset) == null)
            }
    }

    fun testWildcardExpansionHasExactJoinOrderingAndSingleUndo() {
        project()
        add("models/left_orders.sql", "select 1 as id, 2 as amount")
        add("models/right_orders.sql", "select 3 as id, 4 as total")
        val before = "select * from {{ ref('left_orders') }} l join {{ ref('right_orders') }} r on l.id = r.id"
        configure("models/report.sql", before.replace("select *", "select <caret>*"))
        myFixture.launchAction(myFixture.findSingleIntention("Expand dbt SQL columns"))
        assertEquals(
            "select l.id, l.amount, r.id, r.total from {{ ref('left_orders') }} l join {{ ref('right_orders') }} r on l.id = r.id",
            myFixture.file.text
        )
        undo(orProject = true);
        assertEquals(before, myFixture.file.text)
    }

    fun testRegisteredWildcardIntentionLoadsNativeSettingsDescriptionAndExamples() {
        val entries =
            com.intellij.codeInsight.intention.impl.config.IntentionManagerSettings.getInstance().getMetaData()
                .filter { it.action.text == "Expand dbt SQL columns" }
        assertEquals("One registered native intention", 1, entries.size)
        val metadata = entries.first()
        assertTrue("Settings must load the intention description", metadata.description.getText().trim().isNotEmpty())
        val before = metadata.exampleUsagesBefore.map { it.getText() }
        val after = metadata.exampleUsagesAfter.map { it.getText() }
        assertTrue("Settings must show a wildcard example", before.any { it.contains("*") })
        assertTrue("Settings must show the changed projection", after.isNotEmpty() && after != before)
    }

    fun testUnknownSchemasWildcardModifiersAndCommentsNeverExpand() {
        project()
        add("models/orders.sql", "select 1 as id, 2 as amount")
        listOf(
            "select * except (id) from {{ ref('orders') }}", "select * exclude id from {{ ref('orders') }}",
            "select * replace (3 as id) from {{ ref('orders') }}", "select * from unknown_relation",
            "select count(*) from {{ ref('orders') }}", "select '*' as value", "select 1 /* * */"
        )
            .withIndex().forEach { (index, sql) ->
                configure("models/negative_star_$index.sql", sql.replaceFirst(Regex("\\*"), "<caret>*"))
                val before = myFixture.file.text
                if (index >= 4) assertFalse(
                    sql,
                    DbtExpandColumnsIntention().isAvailable(getProject(), myFixture.editor, myFixture.file)
                )
                assertTrue(sql, DbtSqlAnalysis.get(workspace, myFixture.file)?.let { it.wildcards.isEmpty() } != false)
                assertEquals(before, myFixture.file.text)
            }
    }

    fun testMissingPropertiesScaffoldAppliesAndOneUndoRemovesIt() {
        project()
        val source = configure("models/orders.sql", "select 1 as id<caret>")
        var planned: Result<DbtPropertyEdit> = Result.failure(IllegalArgumentException("not planned"))
        WriteAction.run<RuntimeException> {
            planned = DbtPropertiesEditor.plan(
                workspace,
                model("orders"),
                "Orders",
                listOf(DbtColumnData("id", "int", "Identifier")),
                emptyList(),
                emptyList()
            )
        }
        val plan = planned.getOrThrow()
        assertTrue(plan.file == null)
        assertNull(source.virtualFile.parent.findChild("orders.yml"))
        plan.apply(getProject())
        assertEquals("Orders", DbtResourceData.get(workspace, model("orders")).description)
        assertEquals(
            plan.after + "\nColumns: " + workspace.columns(model("orders"))
            .map { c -> (c.name to c.attributes) } +
                "\nMappings: " + workspace.properties(model("orders")).map { it.mapping?.text },
            "Identifier", DbtResourceData.get(workspace, model("orders")).columns.find { it.name == "id" }!!.description
        )
        undo(orProject = true);
        assertNull(source.virtualFile.parent.findChild("orders.yml"))
    }

    fun testDescriptionInheritanceKeepsExactOriginAndRejectsUnionAggregateAndExistingDoc() {
        project()
        val upstream = add("models/orders.sql", "select 1 as id, 2 as amount")
        add(
            "models/schema.yml",
            "models:\n- name: orders\n  columns:\n  - {name: id, description: Identifier}\n  - {name: amount, description: Amount}\n- name: report\n  columns:\n  - {name: kept, description: \"{{ doc('keep_me') }}\"}\n"
        )
        add(
            "models/report.sql",
            "select id as inherited, id as kept, sum(amount) as calculated from {{ ref('orders') }} group by id"
        )
        val inherited = DbtColumnLineage.inherited(workspace, model("report"))
        val editable = DbtPropertiesEditor.editableColumns(workspace, model("report"))
        assertEquals(
            "Opening Edit must not implicitly accept an inherited description",
            "",
            editable.find { it.name == "inherited" }!!.description
        )
        assertEquals("{{ doc('keep_me') }}", editable.find { it.name == "kept" }!!.description)
        assertEquals(listOf("inherited"), inherited.map { it.first })
        assertEquals("Identifier", inherited.first().second)
        assertEquals("id", inherited.first().third.name)
        assertEquals("orders", inherited.first().third.owner)
        val generated = DbtPropertiesEditor.generatedColumns(workspace, model("report"), inherit = true)
        assertEquals(listOf(DbtColumnData("inherited", "", "Identifier")), generated)
        val plan = WriteAction.compute<Result<DbtPropertyEdit>, RuntimeException> {
            DbtPropertiesEditor.plan(
                workspace,
                model("report"),
                "",
                generated,
                emptyList(),
                emptyList()
            )
        }.getOrThrow()
        assertFalse(
            "Inheriting descriptions must not generate unrelated types or columns",
            plan.after.contains("data_type") || plan.after.contains("name: \"calculated\"")
        )
        assertTrue(plan.after.contains("{{ doc('keep_me') }}"))
        val review = DbtPropertiesEditor.origins(workspace, model("report"), "inherited")
        assertTrue(review.first, review.first.contains("Passthrough") && review.first.contains("analytics.orders.id"))
        assertEquals(inherited.first().third.id, review.second.first().id)
        assertTrue(DbtPropertiesEditor.origins(workspace, model("report"), "calculated").first.startsWith("Calculated"))
        add("models/unioned.sql", "select id from {{ ref('orders') }} union all select id from {{ ref('orders') }}")
        assertTrue(DbtColumnLineage.inherited(workspace, model("unioned")).isEmpty())
        edit(upstream, "select 1 as changed")
        assertTrue(
            "Unsaved source edits invalidate prior origins",
            DbtColumnLineage.inherited(workspace, model("report")).isEmpty()
        )
    }

    fun testNativeSchemaCompletionAlsoCoversProjectPackagesAndSelectors() {
        val settings = com.intellij.codeInsight.CodeInsightSettings.getInstance()
        val previous = settings.AUTOCOMPLETE_ON_CODE_COMPLETION
        settings.AUTOCOMPLETE_ON_CODE_COMPLETION = false
        try {
            val config = project()
            open(config, config.text.length)

            fun keys(): Set<String> = myFixture.completeBasic()?.map { it.lookupString }?.toSet().orEmpty()

            assertTrue(keys().contains("model-paths"))
            com.intellij.codeInsight.lookup.LookupManager.hideActiveLookup(getProject())
            configure("packages.yml", "<caret>\n")
            assertTrue(keys().contains("packages"))
            com.intellij.codeInsight.lookup.LookupManager.hideActiveLookup(getProject())
            configure("selectors.yml", "<caret>\n")
            assertTrue(keys().contains("selectors"))
        } finally {
            settings.AUTOCOMPLETE_ON_CODE_COMPLETION = previous
        }
    }

    fun testCompletionAndColumnAnalysisHonorCancellation() {
        project()
        (1..100).forEach { i -> add("models/orders_$i.sql", "select 1 as id") }
        val file = configure("models/report.sql", "select * from {{ ref('ord<caret>') }}")
        val offset = myFixture.caretOffset

        fun cancels(action: () -> Unit) {
            val canceled = java.util.concurrent.atomic.AtomicBoolean()
            com.intellij.openapi.application.ApplicationManager.getApplication()
                .executeOnPooledThread(object : Runnable {
                    override fun run() {
                        val indicator = EmptyProgressIndicator()
                        try {
                            ProgressManager.getInstance().runProcess(object : Runnable {
                                override fun run() {
                                    indicator.cancel()
                                    com.intellij.openapi.application.ReadAction.runBlocking<RuntimeException> { action() }
                                }
                            }, indicator)
                        } catch (_: ProcessCanceledException) {
                            canceled.set(true)
                        }
                    }
                }).get(10, java.util.concurrent.TimeUnit.SECONDS)
            assertTrue("Background work must observe cancellation", canceled.get())
        }

        cancels { DbtCompletion.suggestions(file, offset); Unit }
        cancels { DbtSqlAnalysis.get(workspace, file); Unit }
        assertTrue(
            "Cancellation must not poison future completion",
            DbtCompletion.suggestions(file, myFixture.caretOffset).any { it.text == "orders_1" })
    }

    fun testUnrelatedKwargsAndCommentsDoNotHideStrictMacroWarnings() {
        project()
        add(
            "macros/helpers.sql", "{# kwargs in another macro must not disable checks here #}\n" +
                    "{% macro strict(amount, scale=2) %}{{ amount }}{% endmacro %}\n" +
                    "{% macro zero() %}1{% endmacro %}\n" +
                    "{% macro permissive(amount) %}{{ kwargs.get('extra', 0) }}{% endmacro %}\n"
        )
        val file = configure(
            "models/report.sql",
            "select {{ strict(1, typo=2) }} as strict_result, {{ zero(typo2=2) }} as zero_result, {{ permissive(1, extra=2) }} as flexible_result<caret>"
        )
        myFixture.enableInspections(DbtStaticInspection())
        val diagnostics = myFixture.doHighlighting()
            .filter { h -> (h.description)?.let { it.startsWith("Unknown macro argument") } == true }
        assertEquals(
            listOf("typo", "typo2"),
            diagnostics.map { h -> file.text.substring(h.startOffset, h.endOffset) }.toList()
        )
        assertTrue(diagnostics.all { it.severity == com.intellij.lang.annotation.HighlightSeverity.WARNING })
    }

    private fun explain(sql: String, expected: String, index: Int = 0) {
        configure("models/report_$index.sql", sql)
        val before = myFixture.file.text
        val action = myFixture.findSingleIntention("Expand dbt SQL columns")
        val preview = action.generatePreview(getProject(), myFixture.editor, myFixture.file)
        assertTrue(
            "Unsupported expansion must explain instead of previewing an edit",
            preview.let { it is IntentionPreviewInfo.Html })
        assertTrue(preview.let { it as IntentionPreviewInfo.Html }.content().toString().contains(expected))
        myFixture.launchAction(action)
        assertEquals("Explaining unsupported expansion must preserve source", before, myFixture.file.text)
    }

    fun testNativeIntentionExplainsMissingSchemaWithoutEditingSql() {
        project()
        explain("select <caret>* from unknown_relation", "Add or update")
    }

    fun testNativeIntentionExplainsWildcardModifiersAndPreservesThem() {
        project();
        add("models/orders.sql", "select 1 as id, 2 as amount")
        listOf("except (id)", "exclude id", "replace (3 as id)").withIndex().forEach { (index, modifier) ->
            explain("select <caret>* $modifier from {{ ref('orders') }}", "remove the modifier", index)
        }
    }

    fun testNativeIntentionExplainsUnresolvedQualifierInsteadOfGuessingAnotherRelation() {
        project();
        add("models/orders.sql", "select 1 as id")
        explain("select other_alias.<caret>* from {{ ref('orders') }} o", "declared relation alias")
    }

    fun testCountMultiplicationCommentsAndStringStarsHaveNoExpansionAction() {
        project();
        add("models/orders.sql", "select 1 as id")
        listOf(
            "select count(<caret>*) from {{ ref('orders') }}", "select id <caret>* 2 from {{ ref('orders') }}",
            "select '<caret>*' as value", "select 1 /* <caret>* */", "select 1 -- <caret>*\n"
        )
            .withIndex().forEach { (i, sql) ->
                configure("models/no_wildcard_$i.sql", sql)
                assertFalse(sql, myFixture.availableIntentions.any { it.text == "Expand dbt SQL columns" })
            }
    }

    fun testDuplicateRelationAliasesAreExplainedWithoutChoosingOne() {
        project();
        add("models/orders.sql", "select 1 as id")
        explain("select o.<caret>* from {{ ref('orders') }} o join {{ ref('orders') }} o on 1=1", "No unique relation")
    }

    fun testKnownWildcardStillHasEditablePreviewAfterUnknownSchemaBecomesKnown() {
        project()
        val upstream = add("models/orders.sql", "select * from unknown_relation")
        configure("models/report.sql", "select o.<caret>* from {{ ref('orders') }} o")
        val action = myFixture.findSingleIntention("Expand dbt SQL columns")
        assertTrue(
            action.generatePreview(getProject(), myFixture.editor, myFixture.file)
                .let { it is IntentionPreviewInfo.Html })
        edit(upstream, "select 1 as id, 2 as amount")
        val ready = myFixture.findSingleIntention("Expand dbt SQL columns")
        assertTrue(
            ready.generatePreview(getProject(), myFixture.editor, myFixture.file)
                .let { it is IntentionPreviewInfo.CustomDiff })
        myFixture.launchAction(ready)
        assertEquals("select o.id, o.amount from {{ ref('orders') }} o", myFixture.file.text)
        assertTrue(DbtSqlAnalysis.get(workspace, myFixture.file) != null)
    }
}
