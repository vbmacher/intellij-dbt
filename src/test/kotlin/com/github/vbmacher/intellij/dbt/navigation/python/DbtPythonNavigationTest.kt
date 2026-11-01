package com.github.vbmacher.intellij.dbt.navigation.python

import com.github.vbmacher.intellij.dbt.ide.DbtTestCase
import com.github.vbmacher.intellij.dbt.navigation.DbtNavigation
import com.intellij.codeInsight.navigation.CtrlMouseData
import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.util.TextRange
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.psi.search.searches.ReferencesSearch
import org.junit.Assert.*

import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit

/** Run with the free Python Community plugin enabled; the main suite also runs without it. */
class DbtPythonNavigationTest : DbtTestCase() {
    private fun setupProject() {
        bareProject()
        add("models/orders.sql", "select 1")
        add(
            "models/schema.yml",
            "sources:\n- name: raw\n  tables:\n  - name: events\nmodels:\n- name: python_model\n  config:\n    region: us\n"
        )
    }

    fun testPythonParameterCallsConfigAndMixedReverseUsages() {
        setupProject()
        val py = add(
            "models/python_model.py",
            "def model(dbt, session):\n    orders = dbt.ref('orders')\n    source = dbt.source('raw', 'events')\n    region = dbt.config.get('region')\n    return orders\n"
        )
        assertTrue(
            "Enable the free Python Community plugin to run these tests",
            py.javaClass.name.startsWith("com.jetbrains.python.")
        )
        val refs = DbtNavigation.inFile(py).filter { it.candidates.isNotEmpty() }
        assertEquals(setOf("orders", "raw", "events", "region"), refs.map { it.label }.toSet())
        val sql = add("models/use.sql", "{{ ref('orders') }}")
        val declaration = refs.find { it.label == "orders" }!!.candidates.first()
        assertEquals(2, ReferencesSearch.search(declaration).findAll().size)
    }

    fun testCtrlHoverHighlightsOnlyThePythonModelAndSourceNames() {
        setupProject()
        val py = add(
            "models/python_model.py",
            "def model(dbt, session):\n    orders = dbt.ref('orders')\n    events = dbt.source('raw', 'events')\n    return orders\n"
        )
        assertTrue(
            "Enable the free Python Community plugin to run these tests",
            py.javaClass.name.startsWith("com.jetbrains.python.")
        )
        myFixture.configureFromExistingVirtualFile(py.virtualFile)
        val editor = myFixture.editor

        listOf("orders", "raw", "events").forEach { name ->
            val start = py.text.indexOf("'$name'") + 1
            val data = ReadAction.nonBlocking(object : Callable<CtrlMouseData> {
                override fun call(): CtrlMouseData = GotoDeclarationAction().getCtrlMouseData(editor, py, start + 1)!!
            }).submit(AppExecutorUtil.getAppExecutorService()).get(10, TimeUnit.SECONDS)
            assertNotNull("Ctrl-hover should offer navigation for $name", data)
            assertTrue("Ctrl-hover on $name should highlight a link", data.isNavigatable)
            assertEquals(listOf(TextRange(start, start + name.length)), data.ranges.toList())
        }
    }

    fun testPythonCommentsStringsShadowingAndProvedAlias() {
        setupProject()
        val py = add(
            "models/python_model.py",
            "def model(dbt, session):\n    # dbt.ref('orders')\n    text = \"dbt.ref('orders')\"\n    context = dbt\n    first = context.ref('orders')\n    return first\n\ndef helper(dbt):\n    return dbt.ref('orders')\n"
        )
        assertEquals(1, DbtNavigation.inFile(py).count { it.label == "orders" })
        val shadowed =
            add("models/shadowed.py", "def model(dbt, session):\n    dbt = object()\n    return dbt.ref('orders')\n")
        assertTrue(DbtNavigation.inFile(shadowed).isEmpty())
    }

    fun testPythonVersionedReferences() {
        setupProject()
        add("models/versioned_v1.sql", "select 1")
        add("models/versioned_v2.sql", "select 2")
        add(
            "models/versioned.yml",
            "models:\n- name: versioned\n  latest_version: 2\n  versions:\n  - v: 1\n  - v: 2\n"
        )
        val py = add("models/python_model.py", "def model(dbt, session):\n    return dbt.ref('versioned', version=1)\n")
        val target = DbtNavigation.inFile(py).find { it.label == "versioned" }!!.candidates.first()
        assertTrue(target.containingFile.name.endsWith("_v1.sql"))
        listOf("version=\"1\"", "v='1'").withIndex().forEach { (index, argument) ->
            val quoted = add(
                "models/quoted_" + index + ".py",
                "def model(dbt, session):\n    return dbt.ref(\"versioned\", " + argument + ")\n"
            )
            val quotedTarget = DbtNavigation.inFile(quoted).find { it.label == "versioned" }!!.candidates
            assertTrue(argument, quotedTarget.firstOrNull()?.let { it.containingFile.name.endsWith("_v1.sql") } == true)
        }
    }

    fun testPythonSourcesRespectDuplicateRootsAndDisabledTables() {
        setupProject()
        add("other/dbt_project.yml", "name: analytics\n")
        add("other/models/source.yml", "sources:\n- name: raw\n  tables:\n  - name: events\n")
        val py = add("models/python_model.py", "def model(dbt, session):\n    return dbt.source('raw', 'events')\n")
        val refs = DbtNavigation.inFile(py).filter { it.label == "events" }
        assertEquals(1, refs.flatMap { it.candidates }.size)
        assertFalse(refs.first().candidates.first().containingFile.virtualFile.path.contains("/other/"))
        add(
            "models/disabled.yml",
            "sources:\n- name: private\n  tables:\n  - name: events\n    config: {enabled: false}\n"
        )
        val disabled =
            add("models/disabled.py", "def model(dbt, session):\n    return dbt.source('private', 'events')\n")
        assertTrue(DbtNavigation.inFile(disabled).filter { it.label == "events" }.all { it.candidates.isEmpty() })
        val partial = add("models/partial.py", "def model(dbt, session):\n    return dbt.ref('orders'\n")
        assertTrue(
            DbtNavigation.inFile(partial).all { it.candidates.all { c -> c.containingFile.name == "orders.sql" } })
    }

    fun testExplicitPythonBuiltinSourceWithoutExecutingIt() {
        setupProject()
        configureSettings { o -> o.builtinSources.put("adapter.get_relation", "vendor/adapter.py") }
        val source = add(
            "vendor/adapter.py",
            "class Adapter:\n    def get_relation(self, database, schema, identifier):\n        raise RuntimeError('must never execute')\n"
        )
        val sql = add("models/use.sql", "{{ adapter.get_relation('db', 'schema', 'table') }}")
        val reference = DbtNavigation.at(sql, sql.text.indexOf("get_relation") + 1).first()
        assertEquals(1, reference.candidates.size)
        val target = reference.candidates.first()
        assertEquals(source, target.containingFile)
        assertEquals(source.text.indexOf("get_relation"), target.textOffset)
        assertEquals(1, ReferencesSearch.search(target).findAll().size)
    }

    fun testModelRefactoringUpdatesOnlyResolvingPythonCallsAndUndoPreservesConsumers() {
        setupProject()
        val py = add(
            "models/python_model.py",
            "def model(dbt, session):\n    # dbt.ref('orders')\n    note = \"orders\"\n    return dbt.ref('orders')\n"
        )
        assertTrue(py.javaClass.name.startsWith("com.jetbrains.python."))
        add("other/dbt_project.yml", "name: other\n")
        add("other/models/orders.sql", "select 2")
        val unrelated = add("other/models/consumer.py", "def model(dbt, session):\n    return dbt.ref('orders')\n")
        val target = DbtNavigation.inFile(py).find { it.label == "orders" }!!.candidates.first()
        val source = target.containingFile
        val before = py.text
        com.intellij.refactoring.rename.RenameProcessor(getProject(), target, "customer_orders", false, false).run()
        assertEquals("customer_orders.sql", source.name)
        assertTrue(py.text, py.text.contains("return dbt.ref('customer_orders')"))
        assertTrue(py.text.contains("# dbt.ref('orders')"))
        assertTrue(py.text.contains("note = \"orders\""))
        assertTrue(unrelated.text.contains("return dbt.ref('orders')"))
        undo(null)
        assertEquals("orders.sql", source.name)
        assertEquals(before, py.text)
        val restored = DbtNavigation.inFile(py).find { it.label == "orders" }!!.candidates.first()
        val deletion = com.intellij.refactoring.safeDelete.SafeDeleteProcessor.createInstance(
            getProject(),
            null,
            arrayOf(restored),
            false,
            false,
            false
        )
        deletion.setPreviewUsages(false)
        assertThrows(com.intellij.refactoring.BaseRefactoringProcessor.ConflictsInTestsException::class.java) { deletion.run() }
        assertTrue(source.isValid)
        assertEquals(before, py.text)
    }
}
