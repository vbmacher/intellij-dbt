package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.lineage.DbtSelection
import com.github.vbmacher.intellij.dbt.project._
import org.junit.Assert._
import scala.jdk.CollectionConverters._

final class DbtSelectionAcceptanceTest extends DbtCodeInsightTestCase {
  private def workspace = DbtWorkspace.get(getProject)
  private def root = workspace.roots.find(_.name == "analytics").get
  override protected def tearDown(): Unit = try DbtProjectSettings.get(getProject).loadState(new DbtSettingsState) finally super.tearDown()

  def testGraphDepthIntersectionsNamedSelectorsAndCyclesRemainReviewable(): Unit = {
    project()
    add("models/a.sql", "select 1 as id")
    add("models/b.sql", "{{ config(tags=['finance']) }} select * from {{ ref('a') }}")
    add("models/c.sql", "{{ config(tags=['finance']) }} select * from {{ ref('b') }}")
    add("models/d.sql", "select * from {{ ref('c') }}")
    add("selectors.yml", """selectors:
- name: window
  definition: {method: fqn, value: c, parents: true, parents_depth: 1, children: true, children_depth: 1}
- name: cycle_a
  definition: {method: selector, value: cycle_b}
- name: cycle_b
  definition: {method: selector, value: cycle_a}
""")
    val w = workspace
    assertEquals(Set("b", "c", "d"), DbtSelection.resolve(w, root, "1+c+1").resources.map(_.name).toSet)
    assertEquals(Set("b", "c", "d"), DbtSelection.resolve(w, root, "selector:window").resources.map(_.name).toSet)
    assertEquals(Set("b", "c"), DbtSelection.resolve(w, root, "tag:finance,1+c+1").resources.map(_.name).toSet)
    assertEquals(Set("c"), DbtSelection.resolve(w, root, "tag:finance,1+c+1", "b").resources.map(_.name).toSet)
    val cycle = DbtSelection.resolve(w, root, "selector:cycle_a")
    assertTrue(cycle.resources.isEmpty); assertTrue(cycle.problems.exists(_.contains("cycle")))
    assertFalse(DbtSelection.resolve(w, root, "c,,d").complete)
    assertFalse(DbtSelection.resolve(w, root, "@c").complete)
    assertFalse(DbtSelection.resolve(w, root, "selector:missing").complete)
  }

  def testMatchedVersionAndPackageIdentitiesAgreeWithAllSelectionCommands(): Unit = {
    project()
    add("models/orders_v1.sql", "select 1")
    add("models/orders_v2.sql", "select 2")
    add("models/schema.yml", "models: [{name: orders, versions: [{v: 1}, {v: 2}]}]\n")
    add("dbt_packages/pkg/dbt_project.yml", "name: pkg\nconfig-version: 2\n")
    add("dbt_packages/pkg/models/orders.sql", "select 3")
    add("other/dbt_project.yml", "name: other\nconfig-version: 2\n")
    add("other/models/orders.sql", "select 4")
    val w = workspace
    val options = root.runOptions
    options.selection = "fqn:analytics.orders.v2 package:pkg"
    options.target = "qa"; options.profilesDirectory = "profiles with spaces"
    val resolved = DbtSelection.resolve(w, root, options.selection)
    assertTrue(resolved.problems.mkString("; "), resolved.complete)
    assertEquals(Set("model.analytics.orders.v2", "model.pkg.orders"), resolved.resources.map(_.uniqueId).toSet)
    Vector("Run", "Build", "Test", "Compile").foreach { action =>
      val command = DbtCommand.selection(w, root, action, fullRefresh = false).toOption.get
      val selection = command.arguments.dropWhile(_ != "--select").drop(1).takeWhile(value => !value.startsWith("--"))
      assertEquals(resolved.resources.map(DbtSelection.selector(w, _)).toSet, selection.toSet)
      assertEquals(Vector("--target", "qa"), command.arguments.dropWhile(_ != "--target").take(2))
      assertTrue(command.environment("DBT_PROFILES_DIR").endsWith("/profiles with spaces"))
      assertFalse(selection.exists(_.contains("other")))
      assertFalse(selection.exists(_.endsWith("v1")))
    }
    assertFalse(getProject.getService(classOf[DbtRunner]).isRunning)
  }


}
