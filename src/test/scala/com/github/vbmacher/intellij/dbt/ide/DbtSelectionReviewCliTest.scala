package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project.DbtWorkspace
import org.junit.Assert._

final class DbtSelectionReviewCliTest extends DbtCodeInsightTestCase {
  private def workspace = DbtWorkspace.get(getProject)
  private def root = workspace.roots.find(_.name == "analytics").get

  def testNamedExclusionsRetainPackageVersionAndIndependentRootIdentity(): Unit = {
    project()
    add("models/orders_v1.sql", "select 1")
    add("models/orders_v2.sql", "select 2")
    add("models/schema.yml", "models: [{name: orders, versions: [{v: 1}, {v: 2}]}]\n")
    add("dbt_packages/pkg/dbt_project.yml", "name: pkg\nconfig-version: 2\n")
    add("dbt_packages/pkg/models/orders.sql", "select 3")
    add("other/dbt_project.yml", "name: other\nconfig-version: 2\n")
    add("other/models/orders.sql", "select 4")
    add("selectors.yml", """selectors:
- name: omit
  definition:
    union: [fqn:analytics.orders.v2, package:pkg]
""")
    val w = workspace
    val terms = DbtSelectionReview.cliTerms(w, root, "selector:omit").toOption.get
    assertEquals(Set("fqn:analytics.orders.v2", "fqn:pkg.orders"), terms.toSet)
    assertFalse(terms.exists(_.contains("other")))
    assertFalse(terms.exists(_.endsWith("v1")))
    assertFalse(terms.exists(_.startsWith("selector:")))
  }

  def testNamedGraphDepthsIntersectionsAndUnionsBecomeExactCliTerms(): Unit = {
    project()
    add("models/a.sql", "select 1 as id")
    add("models/b.sql", "{{ config(tags=['finance']) }} select * from {{ ref('a') }}")
    add("models/c.sql", "{{ config(tags=['finance']) }} select * from {{ ref('b') }}")
    add("models/d.sql", "select * from {{ ref('c') }}")
    add("selectors.yml", """selectors:
- {name: center, definition: c}
- {name: window, definition: '1+c+1'}
""")
    val w = workspace
    assertEquals(Set("fqn:analytics.a", "fqn:analytics.b", "fqn:analytics.c", "fqn:analytics.d"),
      DbtSelectionReview.cliTerms(w, root, "@selector:center").toOption.get.toSet)
    assertEquals(Set("fqn:analytics.b", "fqn:analytics.c", "fqn:analytics.d"),
      DbtSelectionReview.cliTerms(w, root, "1+selector:center+1").toOption.get.toSet)
    assertEquals(Set("fqn:analytics.a", "fqn:analytics.b", "fqn:analytics.c"),
      DbtSelectionReview.cliTerms(w, root, "selector:window,tag:finance fqn:analytics.a").toOption.get.toSet)
  }

  def testCyclicMissingAndMixedRuntimeNamedExclusionsAreRejected(): Unit = {
    project()
    add("models/orders.sql", "select 1")
    add("selectors.yml", """selectors:
- {name: ordinary, definition: orders}
- {name: runtime, definition: 'state:modified'}
- {name: cycle_a, definition: 'selector:cycle_b'}
- {name: cycle_b, definition: 'selector:cycle_a'}
""")
    val w = workspace
    Vector("selector:cycle_a", "selector:missing", "selector:runtime", "selector:ordinary result:error",
      "selector:ordinary,state:modified", "selector:ordinary,,orders").foreach { expression =>
      val result = DbtSelectionReview.cliTerms(w, root, expression)
      assertTrue(expression + " must not be sent to dbt as a named exclusion", result.isLeft)
      assertTrue(result.left.toOption.get.contains("resolve completely"))
    }
  }

  def testOrdinaryExplicitCliExclusionsStayUnchangedAndEmptyNamedSetsStayEmpty(): Unit = {
    project()
    add("models/orders.sql", "select 1")
    add("selectors.yml", "selectors: [{name: none, definition: 'tag:absent'}]\n")
    val w = workspace
    assertEquals(Right(Vector.empty), DbtSelectionReview.cliTerms(w, root, " \n\t "))
    assertEquals(Right(Vector.empty), DbtSelectionReview.cliTerms(w, root, "selector:none"))
    assertEquals(Right(Vector("config.materialized:incremental", "state:modified,tag:slow", "@orders")),
      DbtSelectionReview.cliTerms(w, root, "config.materialized:incremental\n state:modified,tag:slow\t@orders"))
    assertEquals(Right(Vector("tag:selector:literal")), DbtSelectionReview.cliTerms(w, root, "tag:selector:literal"))
  }
}
