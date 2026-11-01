package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.lineage.DbtColumnLineage
import com.github.vbmacher.intellij.dbt.lineage.DbtSelection
import com.github.vbmacher.intellij.dbt.navigation.DbtSqlAnalysis
import com.github.vbmacher.intellij.dbt.navigation.DbtCte
import com.intellij.openapi.util.TextRange
import com.github.vbmacher.intellij.dbt.project.*
import com.github.vbmacher.intellij.dbt.syntax.DbtYaml
import com.intellij.openapi.application.WriteAction
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.rename.RenameProcessor
import org.jetbrains.yaml.YAMLFileType
import org.jetbrains.yaml.psi.YAMLMapping
import org.junit.Assert.*


/** End-to-end authoring workflows: rename, resource creation, properties editing, previews and selection commands. */
class DbtWorkflowTest : DbtTestCase() {
    private fun model(name: String) = workspace.symbols.find { s -> s.isCode && s.name == name }!!

    fun testSelectionIntersectionExcludesAndNamedSelectors() {
        project()
        add("models/a.sql", "{{ config(tags=['finance']) }} select 1")
        add("models/b.sql", "select * from {{ ref('a') }}")
        add("models/c.sql", "select 3")
        add("selectors.yml", "selectors:\n- name: finance\n  definition:\n    union:\n    - a+\n    - exclude: b\n")
        assertEquals(
            setOf("a", "b"),
            DbtSelection.resolve(workspace, workspace.roots.first(), "a+").resources.map { it.name }.toSet()
        )
        assertEquals(
            listOf("a"),
            DbtSelection.resolve(workspace, workspace.roots.first(), "selector:finance").resources.map { it.name })
        assertTrue(DbtSelection.resolve(workspace, workspace.roots.first(), "unsupported:foo").problems.isNotEmpty())
    }

    fun testNativeRenameUpdatesOnlyResolvingReferencesAndYaml() {
        project()
        val source = add("models/orders.sql", "{{ config(alias='warehouse_orders') }} select 1 as id")
        val properties = add("models/schema.yml", "# preserve\nmodels: [{name: orders, meta: {owner: Finance}}]\n")
        val consumer = add("models/report.sql", "select * from {{ ref('orders') }}")
        add("other/dbt_project.yml", "name: other\n")
        val unrelated = add("other/models/report.sql", "select * from {{ ref(var('model_name')) }}")
        RenameProcessor(getProject(), model("orders").element, "customer_orders", false, false).run()
        assertEquals("customer_orders.sql", source.name)
        assertTrue(consumer.text.contains("ref('customer_orders')"))
        assertTrue(properties.text.contains("name: customer_orders"))
        assertTrue(properties.text.contains("# preserve"))
        assertTrue(source.text.contains("alias='warehouse_orders'"))
        assertTrue(unrelated.text.contains("ref(var('model_name'))"))
        undo(null)
        assertEquals("orders.sql", source.name);
        assertTrue(consumer.text.contains("ref('orders')"))
    }

    fun testVersionFamilyRenamePreservesDefinedInAndPhysicalAliases() {
        project()
        val first = add("models/orders_v1.sql", "select 1 as id")
        val second = add("models/custom_orders.sql", "{{ config(alias='physical_orders') }} select 2 as id")
        val yaml = add(
            "models/schema.yml",
            "models:\n- name: orders\n  latest_version: 2\n  versions:\n  - v: 1\n  - {v: 2, defined_in: custom_orders}\n"
        )
        val report = add(
            "models/report.sql",
            "select * from {{ ref('orders', v=1) }} union all select * from {{ ref('orders') }}"
        )
        val selected = workspace.symbols.find { s -> s.isCode && s.name == "orders" && s.version == "1" }!!
        RenameProcessor(getProject(), selected.element, "customer_orders", false, false).run()
        assertEquals("customer_orders_v1.sql", first.name);
        assertEquals("custom_orders.sql", second.name)
        assertTrue(second.text.contains("alias='physical_orders'"));
        assertTrue(yaml.text.contains("name: customer_orders"))
        assertEquals(2, Regex("ref\\('customer_orders'").findAll(report.text).count())
        assertTrue(yaml.text.contains("defined_in: custom_orders"))
    }

    fun testOutputColumnRenameAddsAliasWithoutRenamingPhysicalInput() {
        project()
        val source = add("models/orders.sql", "select customer_id from {{ source('raw', 'events') }}")
        val yaml = add(
            "models/schema.yml",
            "models: [{name: orders, columns: [{name: customer_id}]}]\nsources: [{name: raw, tables: [{name: events, columns: [{name: customer_id}]}]}]\n"
        )
        val report = add("models/report.sql", "select customer_id from {{ ref('orders') }}")
        val column = workspace.columns(model("orders")).find { it.name == "customer_id" }!!
        RenameProcessor(getProject(), column.element, "customer_key", false, false).run()
        assertEquals("select customer_id as customer_key from {{ source('raw', 'events') }}", source.text)
        assertEquals("select customer_key from {{ ref('orders') }}", report.text)
        assertTrue(yaml.text.contains("events, columns: [{name: customer_id}]"))
    }

    fun testCreationRespectsConfiguredPathAndExtendsExistingSource() {
        project("model-paths: [transforms]\n")
        val file = configure("transforms/report.sql", "select * from {{ ref('new_<caret>model') }}")
        val proposal = DbtCreateResourceFix.proposal(file, myFixture.caretOffset)!!
        assertEquals("transforms/new_model.sql", proposal.path)
        assertTrue(DbtCreateResourceFix.create(proposal, "../outside.sql").isFailure)
        assertTrue(DbtCreateResourceFix.create(proposal, proposal.path).isSuccess)
        assertTrue(
            com.github.vbmacher.intellij.dbt.navigation.DbtNavigation.at(file, myFixture.caretOffset)
                .any { it.candidates.isNotEmpty() })
        val source = add(
            "transforms/sources.yml",
            "# retain\nsources: [{name: raw, tables: [{name: old_table, meta: {owner: Finance}}]}]\n"
        )
        val consumer =
            configure("transforms/source_report.sql", "select * from {{ source('raw', 'new_<caret>table') }}")
        val table = DbtCreateResourceFix.proposal(consumer, myFixture.caretOffset)!!
        assertTrue(DbtCreateResourceFix.create(table, table.path).isSuccess)
        assertTrue(source.text.contains("# retain"));
        assertTrue(source.text.contains("owner: Finance"))
        assertTrue(workspace.symbols.any { s -> s.kind == "source_table" && s.name == "new_table" })
    }

    fun testPropertiesEditPreservesCommentsAndCustomFields() {
        project()
        add("models/orders.sql", "select 1 as id, 2 as amount")
        val properties = add(
            "models/schema.yml",
            "# preserve this\nmodels:\n  - name: orders\n    meta: {owner: Finance}\n    description: \"{{ doc('orders') }}\"\n    columns:\n      - name: id\n        tests: [unique]\n"
        )
        var editPlan: Result<DbtPropertyEdit> = Result.failure(IllegalArgumentException("not calculated"))
        WriteAction.run<RuntimeException> {
            editPlan = DbtPropertiesEditor.plan(
                workspace, model("orders"), "{{ doc('orders') }}", listOf(
                    DbtColumnData("id", "int", "Identifier"), DbtColumnData("amount", "decimal", "Amount")
                ), emptyList(), emptyList()
            )
        }
        val plan = editPlan.getOrThrow()
        assertTrue(plan.after, plan.after.contains("# preserve this"));
        assertTrue(plan.after, plan.after.contains("owner: Finance"))
        assertTrue(plan.after, plan.after.contains("tests: [unique]"));
        assertTrue(plan.after, plan.after.contains("doc('orders')"))
        assertEquals(plan.before, properties.text)
        plan.apply(getProject())
        assertEquals(setOf("id", "amount"), workspace.columns(model("orders")).map { it.name }.toSet())
        assertEquals(
            "Amount",
            DbtResourceData.get(workspace, model("orders")).columns.find { it.name == "amount" }!!.description
        )
    }

    fun testInheritedDescriptionsRequireProvenPassthrough() {
        project()
        add("models/orders.sql", "select 1 as id, 2 as amount")
        add(
            "models/schema.yml",
            "models: [{name: orders, columns: [{name: id, description: Identifier}, {name: amount, description: Amount}]}]"
        )
        add(
            "models/report.sql",
            "with a as (select id, amount * 2 as gross from {{ ref('orders') }}) select id as key, gross from a"
        )
        assertEquals(listOf("key"), DbtColumnLineage.inherited(workspace, model("report")).map { it.first })
    }

    fun testCtePreviewUsesOnlyDependenciesAndKeepsJinja() {
        project()
        val file = configure(
            "models/report.sql",
            "with a as (select * from {{ ref('orders') }}), unused as (select 9), b as (select * from a) select * from b<caret>"
        )
        val analysis = DbtSqlAnalysis.get(workspace, file)!!
        val sql = DbtCteInspection.sql(file.text, analysis, analysis.ctes.find { it.name == "b" }!!).getOrThrow()
        assertTrue(sql.contains("ref('orders')"));
        assertFalse(sql.contains("unused"));
        assertTrue(sql.endsWith("select * from b"))
        assertTrue(
            DbtCteInspection.sql(file.text, analysis, analysis.ctes.find { it.name == "b" }!!, count = true)
                .getOrThrow().contains("count(*) as full_row_count")
        )
    }

    fun testGridPreservesTypesNullsUnicodeAndCsvQuoting() {
        val result = DbtQueryResult.parse(
            "{\"show\":[{\"id\":2,\"text\":\"a,b\\nž\",\"flag\":true,\"empty\":null},{\"id\":10}]}",
            "dev",
            20,
            23,
            "select *"
        ).getOrThrow()
        assertEquals(java.math.BigDecimal("2"), result.value(0, 0))
        assertEquals((true), result.value(0, 2));
        assertNull(result.value(0, 3))
        assertTrue(result.export().contains("\"a,b\nž\""))
        assertTrue(DbtQueryResult.parse("broken", "dev", 10, 0, "").isFailure)
    }

    fun testSelectionCommandsUseExactIdentitiesAndNeverOverwriteBaseline() {
        project()
        add("models/orders.sql", "select 1 as id")
        add("dbt_packages/pkg/dbt_project.yml", "name: pkg\n")
        add("dbt_packages/pkg/models/orders.sql", "select 2 as id")
        val root = workspace.roots.find { it.name == "analytics" }!!
        val options = root.runOptions;
        options.selection = "orders"
        val command = DbtCommand.selection(workspace, root, "Build", fullRefresh = false).getOrThrow()
        assertTrue(command.arguments.contains("fqn:analytics.orders"));
        assertFalse(command.arguments.contains("fqn:pkg.orders"))
        val modelSelection =
            DbtRunSelection.available(workspace).find { it.path == root.directory.path + "/models/orders.sql" }!!
        assertEquals(
            "select 1 as id",
            DbtCommand.create(workspace, modelSelection, "Preview", fullRefresh = false).getOrThrow().sourceSql
        )
        options.stateDirectory = "target";
        options.defer = true
        assertTrue(
            DbtCommand.selection(workspace, root, "Build", fullRefresh = false).exceptionOrNull()!!.message.orEmpty()
                .contains("separate")
        )
        options.stateDirectory = "baseline"
        add(
            "baseline/manifest.json",
            "{\"metadata\":{\"dbt_schema_version\":\"https://schemas.getdbt.com/dbt/manifest/v12.json\"},\"nodes\":{\"model.analytics.orders\":{\"raw_code\":\"select 0 as id\",\"columns\":{\"id\":{}},\"relation_name\":\"PROD.ANALYTICS.ORDERS\"}}}"
        )
        val changes = DbtState.compare(workspace, root).getOrThrow()
        assertTrue(changes.any { c -> c.id == "model.analytics.orders" && c.status == "modified" })
        val deferred = DbtCommand.selection(workspace, root, "Build", fullRefresh = false).getOrThrow()
        assertTrue(deferred.arguments.contains("--defer"));
        assertTrue(deferred.arguments.contains(root.directory.path + "/baseline"))
    }

    fun testCteSessionInvalidatesDescendantsAndTargetButKeepsIndependentCache() {
        fun c(name: String, sql: String, vararg deps: String) =
            DbtCte(name, TextRange.EMPTY_RANGE, TextRange.EMPTY_RANGE, deps.toList(), sql)

        val initial = listOf(c("a", "select 1"), c("b", "select * from a", "a"), c("independent", "select 7"))
        val session = DbtCteSession()
        session.synchronize(initial, "dev")
        val result = DbtQueryResult.parse("{\"show\":[]}", "dev", 10, 3, "select 1").getOrThrow()
        initial.forEach { frame -> session.record(frame.name, result) }
        session.synchronize(initial.mapIndexed { index, frame -> if (index == 0) c("a", "select 2") else frame }, "dev")
        assertTrue(session.result("a") == null);
        assertTrue(session.result("b") == null);
        assertTrue(session.result("independent") != null)
        session.synchronize(initial, "prod");
        assertTrue(session.result("independent") == null)
        session.stop();
        session.record("a", result);
        assertTrue(session.result("a") == null)
    }

    fun testNativeCaretTargetsUnusedSqlDefinitionsBeforeTheModel() {
        project()
        val file = configure("models/orders.sql", "with un<caret>used as (select 1 as id) select 2 as total")
        val evaluator = DbtTargetElementEvaluator()

        fun target() = evaluator.adjustReferenceOrReferencedElement(
            file, myFixture.editor, myFixture.caretOffset,
            com.intellij.codeInsight.TargetElementUtil.ELEMENT_NAME_ACCEPTED, null
        ).let { it as DbtSymbolElement }

        assertEquals("cte", target().symbol.kind);
        assertEquals("unused", target().name)
        myFixture.editor.caretModel.moveToOffset(file.text.indexOf("total") + 1)
        assertEquals("sql_projection", target().symbol.kind);
        assertEquals("total", target().name)
        edit(file, "select 1 from (select 2 as id) unused_alias")
        myFixture.editor.caretModel.moveToOffset(file.text.indexOf("unused_alias") + 1)
        assertEquals("sql_alias", target().symbol.kind)
    }

    fun testQuotedCteRenamePreservesQuotesAndPhysicalColumnNames() {
        project()
        val file =
            add("models/orders.sql", "with \"Orders\" as (select 1 as physical_id) select physical_id from \"Orders\"")
        val definition = DbtSqlAnalysis.get(workspace, file)!!.declarations.find { it.kind == "cte" }!!
        RenameProcessor(getProject(), definition.element, "customer_orders", false, false).run()
        assertEquals(
            "with \"customer_orders\" as (select 1 as physical_id) select physical_id from \"customer_orders\"",
            file.text
        )
    }

    fun testInferredOutputRenameAddsAliasWithoutYaml() {
        project()
        add("models/upstream.sql", "select 1 as id")
        val source = add("models/report.sql", "select id from {{ ref('upstream') }}")
        val downstream = add("models/downstream.sql", "select id from {{ ref('report') }}")
        val column = workspace.columns(model("report")).find { it.name == "id" }!!
        RenameProcessor(getProject(), column.element, "customer_id", false, false).run()
        assertEquals("select id as customer_id from {{ ref('upstream') }}", source.text)
        assertEquals("select customer_id from {{ ref('report') }}", downstream.text)
    }

    fun testRenameFromMacroYamlCoordinatesDefinitionAndCallers() {
        project()
        val source = add(
            "macros/money.sql",
            "{% macro money(amount) %}{{ amount }}{% endmacro %}\n{% macro other() %}2{% endmacro %}"
        )
        val yaml = add("macros/schema.yml", "macros: [{name: money, description: Converts money}]")
        val report = add("models/report.sql", "select {{ money(1) }} as amount")
        val definition = workspace.symbols.find { s -> s.kind == "macro" && s.isProperty && s.name == "money" }!!
        RenameProcessor(getProject(), definition.element, "currency", false, false).run()
        assertTrue(source.text, source.text.contains("macro currency(amount)"))
        assertTrue(source.text, source.text.contains("macro other()"))
        assertTrue(yaml.text, yaml.text.contains("name: currency"))
        assertTrue(report.text, report.text.contains("currency(1)"))
    }

    fun testPropertiesPreserveInlineCommentsAnchoredTypesAndInheritedVersionColumns() {
        project()
        add("models/orders_v1.sql", "select 1 as id")
        add("models/orders_v2.sql", "select 2 as id")
        val source = add(
            "models/schema.yml",
            "models:\n  - name: orders\n    description: Old description # preserve inline\n    columns:\n      - name: id\n        data_type: &identifier_type int\n        description: Identifier\n        tests: [unique]\n        meta: {owner: Finance}\n    versions: [{v: 1}, {v: 2}]\n"
        )
        val resource = workspace.symbols.find { s -> s.isCode && s.name == "orders" && s.version == "2" }!!
        val columns = DbtResourceData.get(workspace, resource).columns
        var planned: Result<DbtPropertyEdit> = Result.failure(IllegalArgumentException("not planned"))
        WriteAction.run<RuntimeException> {
            planned = DbtPropertiesEditor.plan(workspace, resource, "Version two", columns, emptyList(), emptyList())
        }
        val after = planned.getOrThrow().after
        assertEquals(1, Regex("columns:").findAll(after).count())
        assertTrue(after, after.contains("&identifier_type int"));
        assertTrue(after, after.contains("tests: [unique]"))
        assertTrue(after, after.contains("# preserve inline"));
        assertTrue(after, after.contains("owner: Finance"))
        assertEquals("Source must remain unchanged before Apply", source.text, planned.getOrThrow().before)
        WriteAction.run<RuntimeException> {
            planned = DbtPropertiesEditor.plan(
                workspace,
                resource,
                "Version two",
                columns.map { it.copy(description = "Changed") },
                emptyList(),
                emptyList()
            )
        }
        assertTrue(planned.isFailure)
    }

    fun testNewSeedPropertiesUseTheSeedSection() {
        project()
        add("seeds/countries.csv", "code,name\nSK,Slovakia\n")
        val seed = workspace.symbols.find { s -> s.isCode && s.kind == "seed" }!!
        var planned: Result<DbtPropertyEdit> = Result.failure(IllegalArgumentException("not planned"))
        WriteAction.run<RuntimeException> {
            planned = DbtPropertiesEditor.plan(workspace, seed, "Countries", emptyList(), emptyList(), emptyList())
        }
        assertTrue(planned.getOrThrow().after, planned.getOrThrow().after.contains("seeds:"))
        assertFalse(planned.getOrThrow().after.contains("models:"))
    }

    fun testUnsafePhysicalColumnsAndExplicitCteColumnListsHaveNativeConflicts() {
        project()
        val yaml =
            add("models/sources.yml", "sources: [{name: raw, tables: [{name: events, columns: [{name: event_id}]}]}]")
        val column = workspace.symbols.find { s -> s.kind == "column" && s.name == "event_id" }!!
        val before = yaml.text
        assertThrows(
            com.intellij.refactoring.BaseRefactoringProcessor.ConflictsInTestsException::class.java,
            { RenameProcessor(getProject(), column.element, "event_key", false, false).run() })
        assertEquals(before, yaml.text)
        val file = add("models/report.sql", "with x(x) as (select 1 as value) select * from x")
        val analysis = DbtSqlAnalysis.get(workspace, file)!!
        val cte = analysis.declarations.find { it.kind == "cte" }!!
        assertEquals(file.text.indexOf("x(x)"), cte.range.startOffset)
        assertTrue(analysis.issues.any { it.startsWith("Explicit CTE column lists") })
        assertThrows(
            com.intellij.refactoring.BaseRefactoringProcessor.ConflictsInTestsException::class.java,
            { RenameProcessor(getProject(), cte.element, "renamed", false, false).run() })
        assertTrue(file.text.startsWith("with x(x)"))
    }

    fun testSafeDeleteFromModelPropertiesAlsoDeletesTheSource() {
        project()
        val source = add("models/orders.sql", "select 1 as id")
        val yaml = add("models/schema.yml", "models: [{name: orders, description: Orders}, {name: kept}]\n")
        val property = workspace.symbols.find { s -> s.isProperty && s.kind == "model" && s.name == "orders" }!!
        val processor = com.intellij.refactoring.safeDelete.SafeDeleteProcessor.createInstance(
            getProject(),
            null,
            arrayOf(property.element),
            false,
            false,
            false
        )
        processor.setPreviewUsages(false);
        processor.run()
        assertFalse(source.isValid)
        assertFalse(yaml.text.contains("name: orders"));
        assertTrue(yaml.text.contains("name: kept"))
    }

    fun testInlinePreviewAcceptsOnlyOneParsedSelectQuery() {
        assertTrue(com.github.vbmacher.intellij.dbt.navigation.DbtNavigation.projectedColumns("select * from orders") != null)
        listOf(
            "delete from orders",
            "drop table orders",
            "select 1; drop table orders",
            "update orders set id = 1",
            "select * into copied from orders",
            "with deleted as (delete from orders returning *) select * from deleted",
            "select 'unterminated"
        )
            .forEach { sql ->
                assertTrue(
                    sql,
                    com.github.vbmacher.intellij.dbt.navigation.DbtNavigation.projectedColumns(sql) == null
                )
            }
    }

    fun testInlinePackagePreviewRefusesToLoseOriginalMacroAndRefContext() {
        project()
        add("dbt_packages/pkg/dbt_project.yml", "name: pkg\n")
        add("dbt_packages/pkg/models/orders.sql", "select 1 as id")
        val resource = workspace.symbols.find { s -> s.isCode && s.namespace == "pkg" && s.name == "orders" }!!
        assertTrue(
            DbtCteInspection.command(workspace, resource, "select 1").exceptionOrNull()!!.message.orEmpty()
                .contains("installed-package context")
        )
    }

    private fun parsed(text: String): PsiFile {
        val file = PsiFileFactory.getInstance(getProject()).createFileFromText("review.yml", YAMLFileType.YML, text)
        val errors = PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java).toList()
        assertTrue(text + "\nParser errors: " + errors.map { it.errorDescription }.joinToString(", "), errors.isEmpty())
        return file
    }

    private fun orders(file: PsiFile): YAMLMapping = DbtYaml.items(DbtYaml.mapping(file)!!, "models")
        .find { m -> DbtYaml.string(m, "name") == "orders" }!!

    fun testAddingDescriptionsToExistingFlowColumnsPreservesValidYamlFieldsCommentsAndUndo() {
        project()
        add("models/orders.sql", "select 1 as id, 2 as gross_amount, 'kept' as kept")
        val before =
            """# retain file comment
version: 2
models:
  - name: orders
    description: Existing model
    meta: {owner: Finance}
    columns:
      - {name: id, data_type: integer, meta: {flag: keep}} # identity comment
      - {name: gross_amount, # retain amount comment
         data_type: decimal,}
      - {name: kept, data_type: varchar, description: Preserved}
  - {name: unrelated, description: Unchanged, meta: {custom: retained}}
"""
        val source = add("models/schema.yml", before)
        parsed(before)
        val w = workspace
        val resource = workspace.symbols.find { s -> s.isCode && s.name == "orders" }!!
        var result: Result<DbtPropertyEdit> = Result.failure(IllegalArgumentException("not calculated"))
        WriteAction.run<RuntimeException> {
            result = DbtPropertiesEditor.plan(
                workspace, resource, "Existing model", listOf(
                    DbtColumnData("id", "integer", "Order identifier"), DbtColumnData("gross_amount", "decimal", ""),
                    DbtColumnData("kept", "varchar", "Preserved")
                ), emptyList(), emptyList()
            )
        }
        val plan = result.getOrElse { throw AssertionError(it) }
        assertEquals("Review must not change source", before, source.text)
        val mapping = orders(parsed(plan.after))
        val columns = DbtYaml.items(mapping, "columns")
        assertEquals(listOf("id", "gross_amount", "kept"), columns.mapNotNull { DbtYaml.string(it, "name") })
        assertEquals(listOf("integer", "decimal", "varchar"), columns.mapNotNull { DbtYaml.string(it, "data_type") })
        assertEquals(
            listOf("Order identifier", "", "Preserved"),
            columns.mapNotNull { DbtYaml.string(it, "description") })
        assertEquals(
            listOf("name", "data_type", "meta", "description"),
            columns.first().keyValues.map { it.keyText }.toList()
        )
        assertEquals(("keep"), DbtYaml.map(columns.first(), "meta")?.let { DbtYaml.string(it, "flag") })
        assertEquals(("Finance"), DbtYaml.map(mapping, "meta")?.let { DbtYaml.string(it, "owner") })
        listOf(
            "# retain file comment", "# identity comment", "# retain amount comment",
            "{name: unrelated, description: Unchanged, meta: {custom: retained}}"
        ).forEach { value -> assertTrue(plan.after, plan.after.contains(value)) }
        open(source, 0)
        plan.apply(getProject())
        assertEquals(plan.after, source.text)
        assertEquals(
            ("Order identifier"),
            DbtYaml.string(DbtYaml.items(orders(parsed(source.text)), "columns").first(), "description")
        )
        undo()
        assertEquals("One undo restores the entire prior YAML", before, source.text)
        parsed(source.text)
    }

    fun testAddingMultipleResourceKeysToFlowMappingRetainsSeparatorsAndCustomFields() {
        project()
        add("models/orders.sql", "select 1 as id")
        val source = add(
            "models/schema.yml",
            "models: [{name: orders, columns: [{name: id, data_type: integer}], meta: {custom: retained},}]\n"
        )
        val w = workspace
        val resource = workspace.symbols.find { s -> s.isCode && s.name == "orders" }!!
        var result: Result<DbtPropertyEdit> = Result.failure(IllegalArgumentException("not calculated"))
        WriteAction.run<RuntimeException> {
            result = DbtPropertiesEditor.plan(
                workspace, resource, "Orders", listOf(DbtColumnData("id", "integer", "Identifier")),
                listOf("finance"), listOf("unique"), updateTags = true, updateTests = true
            )
        }
        val plan = result.getOrElse { throw AssertionError(it) }
        val mapping = orders(parsed(plan.after))
        assertEquals(("Orders"), DbtYaml.string(mapping, "description"))
        assertEquals(listOf("finance"), DbtYaml.strings(mapping, "tags"))
        assertEquals(listOf("unique"), DbtYaml.strings(mapping, "data_tests"))
        assertEquals(("retained"), DbtYaml.map(mapping, "meta")?.let { DbtYaml.string(it, "custom") })
        assertEquals(("id"), DbtYaml.items(mapping, "columns").firstOrNull()?.let { DbtYaml.string(it, "name") })
        assertEquals("Review is read-only", plan.before, source.text)
    }
}
