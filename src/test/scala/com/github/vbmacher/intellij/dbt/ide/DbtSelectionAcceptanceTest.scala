package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.lineage.DbtSelection
import com.github.vbmacher.intellij.dbt.project._
import org.junit.Assert._
import scala.jdk.CollectionConverters._

/** Contract: features/selection-review.md and state-comparison.md: exact scope, partial selections and execution gates. */
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
    val atSelection = DbtSelection.resolve(w, root, "@c")
    assertTrue(atSelection.complete)
    assertEquals(Set("a", "b", "c", "d"), atSelection.resources.map(_.name).toSet)
    assertFalse(DbtSelection.resolve(w, root, "selector:missing").complete)
  }

  def testGeneratedSourceAndExposureSelectorsResolveLocally(): Unit = {
    project()
    add("models/orders.sql", "select * from {{ source('raw', 'orders') }}")
    add("models/schema.yml", "sources:\n- name: raw\n  tables:\n  - name: orders\nexposures:\n- name: board\n  type: dashboard\n  owner: {name: a}\n  depends_on: [ref('orders')]\n")
    val w = workspace
    val resources = DbtSelection.resolve(w, root, "").resources.filter(s => Set("source_table", "exposure")(s.kind))
    assertEquals(Set("source_table", "exposure"), resources.map(_.kind).toSet)
    resources.foreach { s =>
      val selector = DbtSelection.selector(w, s)
      assertEquals(selector, Vector(s.uniqueId), DbtSelection.resolve(w, root, selector).resources.map(_.uniqueId))
    }
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
      val selection = command.arguments.dropWhile(_ != "--select").drop(1).takeWhile(arg => !arg.startsWith("-")).takeWhile(value => !value.startsWith("--"))
      assertEquals(resolved.resources.map(DbtSelection.selector(w, _)).toSet, selection.toSet)
      assertEquals(Vector("--target", "qa"), command.arguments.dropWhile(_ != "--target").take(2))
      assertTrue(command.environment("DBT_PROFILES_DIR").endsWith("/profiles with spaces"))
      assertFalse(selection.exists(_.contains("other")))
      assertFalse(selection.exists(_.endsWith("v1")))
    }
    assertFalse(getProject.getService(classOf[DbtRunner]).isRunning)
  }

  def testFullRefreshDependsOnSelectedIncrementalModelsOnly(): Unit = {
    project()
    add("models/plain.sql", "select 1 as id")
    add("models/incremental.sql", "{{ config(materialized='incremental') }} select 2 as id")
    val w = workspace
    root.runOptions.selection = "plain"
    assertFalse(DbtCommand.selection(w, root, "Build", fullRefresh = true).toOption.get.arguments.contains("--full-refresh"))
    root.runOptions.selection = "incremental"
    assertTrue(DbtCommand.selection(w, root, "Build", fullRefresh = true).toOption.get.arguments.contains("--full-refresh"))
    assertFalse(DbtCommand.selection(w, root, "Test", fullRefresh = true).toOption.get.arguments.contains("--full-refresh"))
  }

  def testStateIntersectionUsesBaselineAndKeepsAuthoritativeExpressionInCommand(): Unit = {
    project()
    add("models/orders.sql", "select 2 as amount")
    add("baseline/manifest.json", """{"metadata":{"dbt_schema_version":"https://schemas.getdbt.com/dbt/manifest/v12.json"},"nodes":{"model.analytics.orders":{"raw_code":"select 1 as amount","config":{},"columns":{}}}}""")
    val w = workspace
    val options = root.runOptions
    options.stateDirectory = "baseline"; options.selection = "fqn:analytics.orders,state:modified"
    val command = DbtCommand.selection(w, root, "Build", fullRefresh = false).toOption.get
    assertEquals(Vector("fqn:analytics.orders,state:modified"), command.arguments.dropWhile(_ != "--select").drop(1).takeWhile(arg => !arg.startsWith("-")))
    assertTrue(command.arguments.sliding(2).exists(v => v.head == "--state" && v.last.endsWith("/baseline")))
    val preview = DbtState.preview(w, root, options.selection).toOption.get
    assertEquals(Vector("model.analytics.orders"), preview.selected.map(_.uniqueId))
    options.stateDirectory = "does-not-exist"
    assertTrue(DbtCommand.selection(w, root, "Build", fullRefresh = false).isLeft)
  }

  def testDocumentationAndFreshnessCommandsIgnoreModelSelectionAndInvalidState(): Unit = {
    project()
    add("models/orders.sql", "select 1")
    val w = workspace
    root.runOptions.selection = "unsupported:runtime"
    root.runOptions.stateDirectory = "missing-baseline"
    root.runOptions.defer = true; root.runOptions.favorState = true
    Vector("Generate Docs", "Source Freshness").foreach { action =>
      val command = DbtCommand.selection(w, root, action, fullRefresh = false).toOption.get
      assertFalse(command.arguments.contains("--select"))
      assertFalse(command.arguments.contains("--state"))
      assertFalse(command.arguments.contains("--defer"))
      assertFalse(command.arguments.contains("--favor-state"))
    }
  }

  def testFavorStateRequiresConfiguredCoreVersionEvenWithAValidManifest(): Unit = {
    project()
    val code = add("models/orders.sql", "select 1")
    add(".dbt-navigation.yml", "engine: core\ndbt-version: '1.3'\n")
    add("baseline/manifest.json", """{"metadata":{"dbt_schema_version":"https://schemas.getdbt.com/dbt/manifest/v12.json"},"nodes":{}}""")
    val w = workspace
    root.runOptions.stateDirectory = "baseline"; root.runOptions.defer = true; root.runOptions.favorState = true
    val selected = DbtRunSelection.available(w).find(_.path == code.getVirtualFile.getPath).get
    val error = DbtCommand.create(w, selected, "Build", fullRefresh = false).left.toOption.get
    assertTrue(error.contains("1.4"))
  }

  def testPerRootSelectionAndDeferralSettingsSurviveStateReloadWithoutLeaking(): Unit = {
    project()
    add("models/orders.sql", "select 1")
    add("other/dbt_project.yml", "name: other\nconfig-version: 2\n")
    add("other/models/orders.sql", "select 2")
    val w = workspace
    val other = w.roots.find(_.name == "other").get
    val options = root.runOptions
    options.selection = "orders"; options.exclude = "tag:slow"; options.stateDirectory = "/artifacts/prod"
    options.defer = true; options.favorState = true; options.target = "dev"
    other.runOptions.selection = "tag:other"; other.runOptions.target = "qa"
    val settings = DbtProjectSettings.get(getProject)
    val saved = new DbtSettingsState
    saved.projects = new java.util.TreeMap[String, DbtRunOptions](settings.getState.projects.asScala.map { case (path, value) => path -> value.copy() }.asJava)
    settings.loadState(new DbtSettingsState)
    settings.loadState(saved)
    assertEquals("orders", root.runOptions.selection)
    assertEquals("tag:slow", root.runOptions.exclude)
    assertEquals("/artifacts/prod", root.runOptions.stateDirectory)
    assertTrue(root.runOptions.defer); assertTrue(root.runOptions.favorState)
    assertEquals("tag:other", other.runOptions.selection)
    assertEquals("qa", other.runOptions.target)
    assertEquals("", other.runOptions.stateDirectory)
    assertFalse(other.runOptions.defer); assertFalse(other.runOptions.favorState)
  }
  def testSelectorCompletionPreservesIntersectionsDepthsAndOwningIdentities(): Unit = {
    project()
    add("models/orders.sql", "select 1")
    add("selectors.yml", "selectors: [{name: finance, definition: orders}]\n")
    add("other/dbt_project.yml", "name: other\nconfig-version: 2\n")
    add("other/models/orders.sql", "select 2")
    val candidates = DbtSelectionReview.completions(workspace, root)
    assertTrue(candidates.contains("fqn:analytics.orders"))
    assertTrue(candidates.contains("selector:finance"))
    assertFalse(candidates.exists(_.contains("other")))
    val text = "tag:finance,2+fqn:anal+1 package:pkg"
    val caret = text.indexOf("+1")
    val completed = DbtSelectionReview.complete(text, caret, candidates).find(_._1 == "fqn:analytics.orders").get
    assertEquals("tag:finance,2+fqn:analytics.orders+1 package:pkg", completed._2)
    assertTrue(DbtSelectionReview.complete("bad", 3, candidates).isEmpty)
  }

  def testExplicitCliRefreshKeepsTargetWrapperProfilesAndUsesLegacyNamedSelectorFlag(): Unit = {
    project()
    add("models/orders.sql", "select 1")
    root.runOptions.executableArguments = "'a project'"
    root.runOptions.target = "qa"
    root.runOptions.profilesDirectory = "profiles with spaces"
    root.runOptions.selection = "selector:finance"
    root.runOptions.exclude = "tag:slow"
    val command = DbtCommand.resolveSelection(workspace, root, None).toOption.get
    assertEquals(Vector("dbt", "a project", "ls", "--quiet", "--output", "json", "--selector", "finance", "--exclude", "tag:slow"), command.arguments.take(10))
    assertTrue(command.arguments.sliding(2).exists(_ == Vector("--target", "qa")))
    assertTrue(command.environment("DBT_PROFILES_DIR").endsWith("profiles with spaces"))
    assertEquals("Resolve Selection", command.action)
    assertEquals(root.directory.getPath, command.root)
  }

  def testExcludesWithoutAnExpressionStayInsideTheSelectedDirectory(): Unit = {
    project()
    val file = add("models/finance/orders.sql", "select 1")
    add("models/finance/slow.sql", "select 2")
    add("models/unrelated.sql", "select 3")
    val w = workspace
    val selected = DbtRunSelection.available(w).find(_.path == file.getVirtualFile.getParent.getPath).get
    root.runOptions.exclude = "slow"
    val command = DbtCommand.selection(w, root, "Build", fullRefresh = false, Some(selected)).toOption.get
    assertEquals(Vector("fqn:analytics.finance.orders"), command.arguments.dropWhile(_ != "--select").drop(1).takeWhile(arg => !arg.startsWith("-")).takeWhile(!_.startsWith("--")))
    assertFalse(command.arguments.exists(_.contains("unrelated")))
  }

  def testCliReviewedRuntimeSelectionUsesOnlyKnownExactResources(): Unit = {
    project()
    add("models/orders.sql", "select 1")
    add("models/unrelated.sql", "select 2")
    root.runOptions.selection = "config.materialized:view"
    assertTrue(DbtCommand.selection(workspace, root, "Build", fullRefresh = false).isLeft)
    val ids = Vector("model.analytics.orders")
    val command = DbtCommand.selection(workspace, root, "Build", fullRefresh = false, None, Some(ids)).toOption.get
    assertEquals(Vector("fqn:analytics.orders"), command.arguments.dropWhile(_ != "--select").drop(1).takeWhile(arg => !arg.startsWith("-")))
    assertTrue(DbtCommand.selection(workspace, root, "Build", fullRefresh = false, None, Some(Vector("model.other.orders"))).isLeft)
    assertEquals(Right(ids), DbtSelectionReview.cliResources("log line\n{\"unique_id\":\"model.analytics.orders\"}\n"))
    assertTrue(DbtSelectionReview.cliResources("{\"info\":{\"msg\":\"log\"}}\n").isLeft)
    assertEquals(Right(Vector.empty), DbtSelectionReview.cliResources(""))
  }

  def testNamedExclusionsReachBothManagedCommandsAsCanonicalResources(): Unit = {
    project()
    add("models/orders.sql", "select 1")
    add("models/slow.sql", "select 2")
    add("selectors.yml", "selectors: [{name: omit, definition: slow}]\n")
    val w = workspace
    root.runOptions.selection = "*"
    root.runOptions.exclude = "selector:omit"
    val commands = Vector(DbtCommand.selection(w, root, "Build", fullRefresh = false), DbtCommand.resolveSelection(w, root, None))
    commands.foreach { built =>
      val command = built.toOption.get
      assertTrue(command.arguments.sliding(2).exists(_ == Vector("--exclude", "fqn:analytics.slow")))
      assertFalse(command.arguments.contains("selector:omit"))
    }
    root.runOptions.selection = "+selector:omit"
    assertTrue(DbtCommand.resolveSelection(w, root, None).toOption.get.arguments.contains("fqn:analytics.slow"))
  }

}
