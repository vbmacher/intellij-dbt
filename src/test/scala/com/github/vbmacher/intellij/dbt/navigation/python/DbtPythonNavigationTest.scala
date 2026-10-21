package com.github.vbmacher.intellij.dbt.navigation.python

import com.github.vbmacher.intellij.dbt.ide.DbtTestCase
import com.github.vbmacher.intellij.dbt.navigation.DbtNavigation
import com.intellij.codeInsight.navigation.CtrlMouseData
import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.util.TextRange
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.psi.search.searches.ReferencesSearch
import org.junit.Assert._

import java.util.concurrent.{Callable, TimeUnit}
import scala.jdk.CollectionConverters._

/** Run with the free Python Community plugin enabled; the main suite also runs without it. */
final class DbtPythonNavigationTest extends DbtTestCase {
  private def setupProject(): Unit = {
    bareProject()
    add("models/orders.sql", "select 1")
    add("models/schema.yml", "sources:\n- name: raw\n  tables:\n  - name: events\nmodels:\n- name: python_model\n  config:\n    region: us\n")
  }
  def testPythonParameterCallsConfigAndMixedReverseUsages(): Unit = {
    setupProject()
    val py = add("models/python_model.py", "def model(dbt, session):\n    orders = dbt.ref('orders')\n    source = dbt.source('raw', 'events')\n    region = dbt.config.get('region')\n    return orders\n")
    assertTrue("Enable the free Python Community plugin to run these tests", py.getClass.getName.startsWith("com.jetbrains.python."))
    val refs = DbtNavigation.inFile(py).filter(_.candidates.nonEmpty)
    assertEquals(Set("orders", "raw", "events", "region"), refs.map(_.label).toSet)
    val sql = add("models/use.sql", "{{ ref('orders') }}")
    val declaration = refs.find(_.label == "orders").get.candidates.head
    assertEquals(2, ReferencesSearch.search(declaration).findAll().size())
  }

  def testCtrlHoverHighlightsOnlyThePythonModelAndSourceNames(): Unit = {
    setupProject()
    val py = add("models/python_model.py",
      "def model(dbt, session):\n    orders = dbt.ref('orders')\n    events = dbt.source('raw', 'events')\n    return orders\n")
    assertTrue("Enable the free Python Community plugin to run these tests", py.getClass.getName.startsWith("com.jetbrains.python."))
    myFixture.configureFromExistingVirtualFile(py.getVirtualFile)
    val editor = myFixture.getEditor

    Seq("orders", "raw", "events").foreach { name =>
      val start = py.getText.indexOf(s"'$name'") + 1
      val data = ReadAction.nonBlocking(new Callable[CtrlMouseData] {
        override def call(): CtrlMouseData = new GotoDeclarationAction().getCtrlMouseData(editor, py, start + 1)
      }).submit(AppExecutorUtil.getAppExecutorService).get(10, TimeUnit.SECONDS)
      assertNotNull(s"Ctrl-hover should offer navigation for $name", data)
      assertTrue(s"Ctrl-hover on $name should highlight a link", data.isNavigatable)
      assertEquals(Vector(new TextRange(start, start + name.length)), data.getRanges.asScala.toVector)
    }
  }
  def testPythonCommentsStringsShadowingAndProvedAlias(): Unit = {
    setupProject()
    val py = add("models/python_model.py", "def model(dbt, session):\n    # dbt.ref('orders')\n    text = \"dbt.ref('orders')\"\n    context = dbt\n    first = context.ref('orders')\n    return first\n\ndef helper(dbt):\n    return dbt.ref('orders')\n")
    assertEquals(1, DbtNavigation.inFile(py).count(_.label == "orders"))
    val shadowed = add("models/shadowed.py", "def model(dbt, session):\n    dbt = object()\n    return dbt.ref('orders')\n")
    assertTrue(DbtNavigation.inFile(shadowed).isEmpty)
  }
  def testPythonVersionedReferences(): Unit = {
    setupProject()
    add("models/versioned_v1.sql", "select 1")
    add("models/versioned_v2.sql", "select 2")
    add("models/versioned.yml", "models:\n- name: versioned\n  latest_version: 2\n  versions:\n  - v: 1\n  - v: 2\n")
    val py = add("models/python_model.py", "def model(dbt, session):\n    return dbt.ref('versioned', version=1)\n")
    val target = DbtNavigation.inFile(py).find(_.label == "versioned").get.candidates.head
    assertTrue(target.getContainingFile.getName.endsWith("_v1.sql"))
    Vector("version=\"1\"", "v='1'").zipWithIndex.foreach { case (argument, index) =>
      val quoted = add("models/quoted_" + index + ".py", "def model(dbt, session):\n    return dbt.ref(\"versioned\", " + argument + ")\n")
      val quotedTarget = DbtNavigation.inFile(quoted).find(_.label == "versioned").get.candidates
      assertTrue(argument, quotedTarget.headOption.exists(_.getContainingFile.getName.endsWith("_v1.sql")))
    }
  }

  def testPythonSourcesRespectDuplicateRootsAndDisabledTables(): Unit = {
    setupProject()
    add("other/dbt_project.yml", "name: analytics\n")
    add("other/models/source.yml", "sources:\n- name: raw\n  tables:\n  - name: events\n")
    val py = add("models/python_model.py", "def model(dbt, session):\n    return dbt.source('raw', 'events')\n")
    val refs = DbtNavigation.inFile(py).filter(_.label == "events")
    assertEquals(1, refs.flatMap(_.candidates).size)
    assertFalse(refs.head.candidates.head.getContainingFile.getVirtualFile.getPath.contains("/other/"))
    add("models/disabled.yml", "sources:\n- name: private\n  tables:\n  - name: events\n    config: {enabled: false}\n")
    val disabled = add("models/disabled.py", "def model(dbt, session):\n    return dbt.source('private', 'events')\n")
    assertTrue(DbtNavigation.inFile(disabled).filter(_.label == "events").forall(_.candidates.isEmpty))
    val partial = add("models/partial.py", "def model(dbt, session):\n    return dbt.ref('orders'\n")
    assertTrue(DbtNavigation.inFile(partial).forall(_.candidates.forall(_.getContainingFile.getName == "orders.sql")))
  }

  def testExplicitPythonBuiltinSourceWithoutExecutingIt(): Unit = {
    setupProject()
    add(".dbt-navigation.yml", "builtin-sources:\n  adapter.get_relation: vendor/adapter.py\n")
    val source = add("vendor/adapter.py", "class Adapter:\n    def get_relation(self, database, schema, identifier):\n        raise RuntimeError('must never execute')\n")
    val sql = add("models/use.sql", "{{ adapter.get_relation('db', 'schema', 'table') }}")
    val reference = DbtNavigation.at(sql, sql.getText.indexOf("get_relation") + 1).head
    assertEquals(1, reference.candidates.size)
    val target = reference.candidates.head
    assertEquals(source, target.getContainingFile)
    assertEquals(source.getText.indexOf("get_relation"), target.getTextOffset)
    assertEquals(1, ReferencesSearch.search(target).findAll().size())
  }

  def testModelRefactoringUpdatesOnlyResolvingPythonCallsAndUndoPreservesConsumers(): Unit = {
    setupProject()
    val py = add("models/python_model.py", "def model(dbt, session):\n    # dbt.ref('orders')\n    note = \"orders\"\n    return dbt.ref('orders')\n")
    assertTrue(py.getClass.getName.startsWith("com.jetbrains.python."))
    add("other/dbt_project.yml", "name: other\n")
    add("other/models/orders.sql", "select 2")
    val unrelated = add("other/models/consumer.py", "def model(dbt, session):\n    return dbt.ref('orders')\n")
    val target = DbtNavigation.inFile(py).find(_.label == "orders").get.candidates.head
    val source = target.getContainingFile
    val before = py.getText
    new com.intellij.refactoring.rename.RenameProcessor(getProject, target, "customer_orders", false, false).run()
    assertEquals("customer_orders.sql", source.getName)
    assertTrue(py.getText, py.getText.contains("return dbt.ref('customer_orders')"))
    assertTrue(py.getText.contains("# dbt.ref('orders')"))
    assertTrue(py.getText.contains("note = \"orders\""))
    assertTrue(unrelated.getText.contains("return dbt.ref('orders')"))
    undo(null)
    assertEquals("orders.sql", source.getName)
    assertEquals(before, py.getText)
    val restored = DbtNavigation.inFile(py).find(_.label == "orders").get.candidates.head
    val deletion = com.intellij.refactoring.safeDelete.SafeDeleteProcessor.createInstance(getProject, null, Array(restored), false, false, false)
    deletion.setPreviewUsages(false)
    assertThrows(classOf[com.intellij.refactoring.BaseRefactoringProcessor.ConflictsInTestsException], () => deletion.run())
    assertTrue(source.isValid)
    assertEquals(before, py.getText)
  }
}
