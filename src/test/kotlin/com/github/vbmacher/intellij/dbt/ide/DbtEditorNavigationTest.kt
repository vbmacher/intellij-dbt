package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.lineage.DbtLineage
import com.github.vbmacher.intellij.dbt.project.DbtSymbol
import com.github.vbmacher.intellij.dbt.project.DbtWorkspace
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiPolyVariantReference
import org.junit.Assert.*

import java.awt.Component
import javax.swing.JLabel
import javax.swing.JList

/** User-guide navigation contracts exercised through Go to Declaration, and the presentation of navigation targets. */
class DbtEditorNavigationTest : DbtTestCase() {
    fun testModelReferenceInPlainSqlSelectsOnlyTheName() {
        project()
        val orders = add("models/orders.sql", "select 1 as order_id")
        configure("models/report.sql", "select * from {{ ref('or<caret>ders') }}")

        assertTarget(orders)
        assertEquals("orders", reference.getRangeInElement().substring(reference.getElement().getText()))
    }

    fun testGoToDeclarationActionOpensTheReferencedModel() {
        project()
        val orders = add("models/orders.sql", "select 1 as order_id")
        configure("models/report.sql", "select * from {{ ref('or<caret>ders') }}")

        myFixture.performEditorAction("GotoDeclaration")
        val expected = PsiDocumentManager.getInstance(getProject()).getDocument(orders)
        await("Go to Declaration should open orders.sql") { FileEditorManager.getInstance(project).selectedTextEditor?.document == expected }
    }

    fun testSeedReferenceOpensTheCsvFile() {
        project()
        val seed = add("seeds/countries.csv", "code\nUS\n")
        configure("models/report.sql", "select * from {{ ref('cou<caret>ntries') }}")

        assertTarget(seed)
    }

    fun testSnapshotReferenceOpensTheNamedBlockInItsContainer() {
        project()
        val snapshots = add(
            "snapshots/history.sql",
            "{% snapshot first %}select 1{% endsnapshot %}\n{% snapshot orders_history %}select 2{% endsnapshot %}"
        )
        configure("models/report.sql", "select * from {{ ref('orders_<caret>history') }}")

        assertTarget(snapshots, snapshots.getText().indexOf("orders_history"))
    }

    fun testSourceNamespaceOpensItsYamlName() {
        project()
        val schema = add("models/sources.yml", "sources:\n- name: raw\n  tables:\n  - name: events\n")
        configure("models/report.sql", "select * from {{ source('r<caret>aw', 'events') }}")

        assertTarget(schema, schema.getText().indexOf("raw"))
    }

    fun testSourceTableUsesTheLogicalNameAndCorrectNamespace() {
        project()
        val schema = add(
            "models/sources.yml",
            "sources:\n- name: other\n  tables:\n  - name: events\n- name: raw\n  tables:\n  - name: events\n    identifier: warehouse_events\n"
        )
        configure("models/report.sql", "select * from {{ source('raw', 'ev<caret>ents') }}")

        assertTarget(schema, schema.getText().lastIndexOf("name: events") + "name: ".length)
    }

    fun testPhysicalSourceIdentifierIsNotALogicalTableName() {
        project()
        add(
            "models/sources.yml",
            "sources:\n- name: raw\n  tables:\n  - name: events\n    identifier: warehouse_events\n"
        )
        configure("models/report.sql", "select * from {{ source('raw', 'warehouse_<caret>events') }}")

        assertNoTarget()
    }

    fun testQualifiedReferenceUsesTheInstalledPackageProjectName() {
        project("packages-install-path: vendor\n")
        add("models/orders.sql", "select 1")
        add("vendor/finance/dbt_project.yml", "name: accounting\n")
        val packaged = add("vendor/finance/models/orders.sql", "select 2")
        configure("models/report.sql", "select * from {{ ref('accounting', 'or<caret>ders') }}")

        assertTarget(packaged)
    }

    fun testUnversionedModelUsesLatestVersion() {
        project()
        add("models/orders_old.sql", "select 1")
        val latest = add("models/orders_v2.sql", "select 2")
        add(
            "models/orders.yml",
            "models:\n- name: orders\n  latest_version: 2\n  versions:\n  - v: 1\n    defined_in: orders_old\n  - v: 2\n"
        )
        configure("models/report.sql", "select * from {{ ref('or<caret>ders') }}")

        assertTarget(latest)
    }

    fun testExplicitVersionUsesDefinedInRatherThanLatest() {
        project()
        val old = add("models/orders_old.sql", "select 1")
        add("models/orders_v2.sql", "select 2")
        add(
            "models/orders.yml",
            "models:\n- name: orders\n  latest_version: 2\n  versions:\n  - v: 1\n    defined_in: orders_old\n  - v: 2\n"
        )
        configure("models/report.sql", "select * from {{ ref('or<caret>ders', version=1) }}")

        assertTarget(old)
    }

    fun testMacroReferenceOpensItsDeclarationRatherThanTheFirstBlock() {
        project()
        val macros = add(
            "macros/math.sql",
            "{% macro triple(value) %}{{ value * 3 }}{% endmacro %}\n{% macro double(value) %}{{ value * 2 }}{% endmacro %}"
        )
        configure("models/report.sql", "select {{ dou<caret>ble(value=2) }}")

        assertTarget(macros, macros.getText().indexOf("double"))
    }

    fun testNamedMacroArgumentOpensTheParameter() {
        project()
        val macros = add("macros/math.sql", "{% macro double(value) %}{{ value * 2 }}{% endmacro %}")
        configure("models/report.sql", "select {{ double(val<caret>ue=2) }}")

        assertTarget(macros, macros.getText().indexOf("value"))
    }

    fun testDocsReferenceInResourceDescriptionOpensTheNamedBlock() {
        project()
        add("models/orders.sql", "select 1")
        val docs = add(
            "docs/catalog.md",
            "{% docs other %}Other{% enddocs %}\n{% docs orders_description %}Orders{% enddocs %}"
        )
        configure(
            "models/schema.yml",
            "models:\n- name: orders\n  description: \"{{ doc('orders_<caret>description') }}\"\n"
        )

        assertTarget(docs, docs.getText().indexOf("orders_description"))
    }

    fun testDocsCallInSqlHasNoDocumentationTarget() {
        project()
        add("docs/catalog.md", "{% docs orders_description %}Orders{% enddocs %}")
        configure("models/report.sql", "{{ doc('orders_<caret>description') }}")

        assertNoTarget()
    }

    fun testMultilineYamlHookOpensTheReferencedModel() {
        project()
        val orders = add("models/orders.sql", "select 1")
        configure(
            "models/report.yml",
            "models:\n- name: report\n  config:\n    pre_hook: |\n      delete from {{ ref('or<caret>ders') }}\n      where id < 0\n"
        )

        assertTarget(orders)
    }

    fun testCteReferenceOpensTheDefinitionInTheSameSqlFile() {
        project()
        val file = configure("models/report.sql", "with recent as (select 1 as id)\nselect id from re<caret>cent")

        assertTarget(file, file.getText().indexOf("recent"))
    }

    fun testQualifiedColumnOpensTheYamlColumnDeclaration() {
        project()
        add("models/orders.sql", "select 1 as order_id")
        val schema = add("models/orders.yml", "models:\n- name: orders\n  columns:\n  - name: order_id\n")
        configure("models/report.sql", "select o.order_<caret>id from {{ ref('orders') }} o")

        assertTarget(schema, schema.getText().indexOf("order_id"))
    }

    fun testUnitTestFixtureOpensTheCsvRatherThanTheSameNamedModel() {
        project()
        add("models/orders.sql", "select 1")
        val fixture = add("tests/fixtures/orders.csv", "id\n1\n")
        configure(
            "models/unit.yml",
            "unit_tests:\n- name: example\n  model: orders\n  given:\n  - input: ref('orders')\n    format: csv\n    fixture: or<caret>ders\n"
        )

        assertTarget(fixture)
    }

    fun testDuplicateModelNamesOfferAllDeclarationsWithoutChoosingOne() {
        project()
        val first = add("models/first/orders.sql", "select 1")
        val second = add("models/second/orders.sql", "select 2")
        configure("models/report.sql", "select * from {{ ref('or<caret>ders') }}")

        val ref = reference
        assertNull("An ambiguous reference should not choose a model", ref.resolve())
        assertTrue("IntelliJ must be able to offer a chooser", ref is PsiPolyVariantReference)
        val targets = (ref as PsiPolyVariantReference).multiResolve(false).toList()
        assertEquals(2, targets.size)
        assertEquals(setOf(first, second), targets.map { it.element!!.containingFile }.toSet())
        assertEquals(setOf(first, second), declarationTargets.map { it.getContainingFile() }.toSet())
    }

    fun testAnUnknownVersionDoesNotFallBackToLatest() {
        project()
        add("models/orders_v2.sql", "select 2")
        add("models/orders.yml", "models:\n- name: orders\n  latest_version: 2\n  versions: [{v: 2}]\n")
        configure("models/report.sql", "select * from {{ ref('or<caret>ders', v=runtime_version) }}")

        assertNoTarget()
    }

    fun testIgnoredModelCannotBeADeclarationTarget() {
        project()
        add("models/experimental/orders.sql", "select 1")
        add(".dbtignore", "models/experimental/\n")
        configure("models/report.sql", "select * from {{ ref('or<caret>ders') }}")

        assertNoTarget()
    }

    fun testExplicitlyReincludedModelInsideAnIgnoredDirectoryIsNavigable() {
        project()
        val ignore = add(".dbtignore", "models/experimental/\n!models/experimental/keep.sql\n")
        val keep = add("models/experimental/keep.sql", "select 1")
        add("models/experimental/drop.sql", "select 2")
        val caller = configure("models/report.sql", "{{ ref('ke<caret>ep') }} {{ ref('drop') }}")

        assertTarget(keep)
        myFixture.getEditor().getCaretModel().moveToOffset(caller.getText().indexOf("drop") + 2)
        assertNoTarget()

        edit(ignore, "models/experimental/\n")
        myFixture.getEditor().getCaretModel().moveToOffset(caller.getText().indexOf("keep") + 2)
        assertNoTarget()
    }

    fun testWildcardReinclusionReachesFilesBelowIgnoredAncestors() {
        project()
        add(".dbtignore", "models/experimental/\n!models/experimental/**/keep.sql\n")
        val keep = add("models/experimental/nested/keep.sql", "select 1")
        configure("models/report.sql", "{{ ref('ke<caret>ep') }}")

        assertTarget(keep)
    }

    fun testIgnoreNegationsCannotReincludeGeneratedOutput() {
        project("model-paths: [models, generated]\ntarget-path: generated\n")
        add(".dbtignore", "models/experimental/\n!generated/**\n")
        add("generated/orders.sql", "select 1")
        configure("models/report.sql", "{{ ref('or<caret>ders') }}")

        assertNoTarget()
    }

    fun testInformationSchemaNamePredicateRespectsEnabledConfiguration() {
        project("models:\n  analytics:\n    +enabled: false\n")
        configureSettings { it.engine = "v2" }
        val orders = add("models/orders.sql", "select 1")
        configure("checks/exists.sql", "select name from {{ info_schema('models') }} where name = 'or<caret>ders'")

        assertNoTarget()
        edit(orders, "{{ config(enabled=true) }} select 1")
        assertTarget(orders)
        edit(orders, "{{ config(enabled=false) }} select 1")
        assertNoTarget()
    }

    fun testAddingAMissingModelMakesTheExistingReferenceNavigable() {
        project()
        configure("models/report.sql", "select * from {{ ref('or<caret>ders') }}")
        assertNoTarget()

        val orders = add("models/orders.sql", "select 1")
        assertTarget(orders)
    }

    fun testDeletingAModelRemovesTheExistingNavigationTarget() {
        project()
        val orders = add("models/orders.sql", "select 1")
        configure("models/report.sql", "select * from {{ ref('or<caret>ders') }}")
        assertTarget(orders)

        WriteCommandAction.runWriteCommandAction(getProject(), Runnable { orders.virtualFile.delete(this) })
        assertNoTarget()
    }

    fun testUnsavedCallerEditNavigatesToTheNewModel() {
        project()
        val orders = add("models/orders.sql", "select 1")
        val customers = add("models/customers.sql", "select 2")
        val caller = configure("models/report.sql", "select * from {{ ref('or<caret>ders') }}")
        assertTarget(orders)

        edit(caller, "select * from {{ ref('customers') }}")
        myFixture.getEditor().getCaretModel().moveToOffset(caller.getText().indexOf("customers") + 2)
        assertTarget(customers)
    }

    fun testNestedProjectDoesNotResolveAnOuterProjectModel() {
        project()
        add("models/orders.sql", "select 1")
        add("nested/dbt_project.yml", "name: nested\n")
        configure("nested/models/report.sql", "select * from {{ ref('or<caret>ders') }}")

        assertNoTarget()
    }

    private fun model(workspace: DbtWorkspace, file: PsiFile): DbtSymbol =
        workspace.symbols.find { s -> s.file == file && s.attributes.contains("code") }
            ?: throw AssertionError("Missing model in ${file.name}")

    fun testResourceIconsAreSharedByDeclarationsAndNativePresentation() {
        bareProject()
        val orders = add("models/orders.sql", "select 1 as id")
        val yaml = add(
            "models/schema.yml",
            "models:\n- name: orders\n  columns:\n  - name: id\nsources:\n- name: raw\n  tables:\n  - name: events\n"
        )
        val declaration = model(workspace, orders).element
        val properties = workspace.properties(declaration.symbol)
        assertFalse(properties.isEmpty())
        properties.forEach { s -> assertSame(declaration.getIcon(0), s.element.getIcon(0)) }
        assertSame(declaration.getIcon(0), declaration.getPresentation().getIcon(false))
        assertEquals("analytics.orders", declaration.getPresentation().getPresentableText())
        val source = workspace.symbols.find { s -> s.file == yaml && s.kind == "source" }!!
        val table = workspace.symbols.find { s -> s.file == yaml && s.kind == "source_table" }!!
        assertSame(declaration.getIcon(0), source.element.getIcon(0))
        assertSame(source.element.getIcon(0), table.element.getIcon(0))
    }

    fun testDependenciesGroupReferencesAndKeepDirectionAndExactTargets() {
        bareProject()
        val orders = add("models/orders.sql", "select 1 as id")
        val events =
            add("models/events.sql", "\nselect * from {{ ref('orders') }}\nunion all select * from {{ ref('orders') }}")
        val report = add("models/report.sql", "select * from {{ ref('events') }}")
        val edges = DbtLineage.edges(workspace)
        val downstream = DbtDependenciesAction.choices(edges, setOf(model(workspace, orders).id))
        assertEquals(listOf("analytics.events", "events.sql:2", "events.sql:3"), downstream.map { it.text })
        assertEquals("Used by", downstream.first().separator)
        assertFalse(downstream.first().reference)
        assertTrue(downstream.drop(1).all { it.reference })
        assertEquals(events, downstream.first().target.getContainingFile())
        assertEquals(events.getText().indexOf("orders"), downstream[1].target.getTextOffset())
        assertEquals(events.getText().lastIndexOf("orders"), downstream[2].target.getTextOffset())
        val both = DbtDependenciesAction.choices(edges, setOf(model(workspace, events).id))
        assertEquals(listOf("Uses", "Used by"), both.mapNotNull { it.separator })
        assertEquals(listOf(orders, report), both.filterNot { it.reference }.map { it.target.getContainingFile() })
    }

    fun testIdenticalReferenceLabelsKeepTheirOwnNavigationTargets() {
        bareProject()
        val orders = add("models/orders.sql", "select 1")
        val first = add("models/a/use.sql", "{{ ref('orders') }}")
        val second = add("models/b/use.sql", "{{ ref('orders') }}")
        val choices = DbtDependenciesAction.choices(DbtLineage.edges(workspace), setOf(model(workspace, orders).id))
        val references = choices.filter { it.reference }
        assertEquals(listOf("use.sql:1", "use.sql:1"), references.map { it.text })
        assertEquals(setOf(first, second), references.map { it.target.getContainingFile() }.toSet())
    }

    fun testRendererIndentsReferencesAndRestoresResourceIcon() {
        val file = add("models/orders.sql", "select 1")
        val symbol = DbtSymbol("model", "orders", "analytics", file, TextRange.EMPTY_RANGE).element
        val renderer = DbtNavigationItemRenderer()
        val list = JList<DbtNavigationItem>()

        fun labels(component: Component): List<JLabel> = components(component).filterIsInstance<JLabel>()

        val reference = DbtNavigationItem(symbol, "orders.sql:1", reference = true)
        val referenceLabel = labels(renderer.getListCellRendererComponent(list, reference, 0, false, false))
            .find { it.getText() == reference.text }!!
        val referenceIndent = referenceLabel.getInsets().left
        assertNull(referenceLabel.getIcon())
        val resource = DbtNavigationItem(symbol, "analytics.orders")
        val resourceLabel = labels(renderer.getListCellRendererComponent(list, resource, 1, false, false))
            .find { it.getText() == resource.text }!!
        assertTrue(referenceIndent > resourceLabel.getInsets().left)
        assertNotNull(resourceLabel.getIcon())
    }
}
