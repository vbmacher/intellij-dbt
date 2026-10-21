package com.github.vbmacher.intellij.dbt

import com.github.vbmacher.intellij.dbt.ide.DbtTestCase
import com.github.vbmacher.intellij.dbt.lineage.{DbtLineage, DbtSelectors}
import com.github.vbmacher.intellij.dbt.navigation.DbtNavigation
import com.github.vbmacher.intellij.dbt.project.DbtSymbolElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import org.junit.Assert._

/** Regression cases from the navigation roadmap's shared acceptance criteria. */
final class DbtAcceptanceTest extends DbtTestCase {
  def testSingularTestsInheritDataTestsConfiguration(): Unit = {
    val config = bareProject("data_tests:\n  analytics:\n    +enabled: false\n")
    add("tests/check.sql", "select 1")
    val enabled = add("tests/keep.sql", "{{ config(enabled=true) }} select 1")
    var w = workspace
    assertTrue(w.declarations(Set("test"), "check", w.roots.head).isEmpty)
    assertTrue(DbtSelectors.select(w, w.roots.head, "test:check", includeGraph = false).isEmpty)
    assertEquals(Vector(enabled), w.declarations(Set("test"), "keep", w.roots.head).map(_.file))

    edit(config, "name: analytics\ntests:\n  analytics:\n    +enabled: false\n")
    w = workspace
    assertTrue(w.declarations(Set("test"), "check", w.roots.head).isEmpty)
    assertEquals(Vector(enabled), w.declarations(Set("test"), "keep", w.roots.head).map(_.file))
  }

  def testDisabledLocalModelFallsBackToEnabledPackageModel(): Unit = {
    bareProject()
    val local = add("models/orders.sql", "{{ config(enabled=false) }} select 1")
    add("models/schema.yml", "models:\n- name: orders\n  description: local\n")
    val packaged = add("dbt_packages/util/models/orders.sql", "select 2")
    add("dbt_packages/util/dbt_project.yml", "name: util\n")
    val use = add("models/use.sql", "select * from {{ ref('orders') }}")
    assertEquals(Vector(packaged), targetsAt(use, "orders").map(_.getContainingFile))

    edit(local, "select 1")
    val enabled = targetsAt(use, "orders").map(_.getContainingFile)
    assertTrue(enabled.contains(local));
    assertFalse(enabled.contains(packaged))
  }

  def testRequireDbtVersionListDeterminesSupportedFeatures(): Unit = {
    val config = bareProject()
    add("models/semantic.yml", "semantic_models: [{name: orders, model: ref('orders')}]\n")
    Vector("[\">=1.5.0\", \"<1.6.0\"]", "[\"<1.6.0\", \">=1.5.0\"]", "\"<1.6.0,>=1.5.0\"").foreach { range =>
      edit(config, "name: analytics\nrequire-dbt-version: " + range + "\n")
      val w = workspace
      val from = w.roots.head
      assertEquals(range, "1.5.0", from.version)
      assertTrue(range, from.supports(5))
      assertFalse(range, from.supportsKind("semantic_model"))
      assertTrue(range, w.declarations(Set("semantic_model"), "orders", from).isEmpty)
    }
  }

  def testRequireDbtVersionUsesEveryLowerBound(): Unit = {
    val config = bareProject()
    add("models/orders.sql", "select 1 as id")
    val unit = add("models/unit.yml", "unit_tests: [{name: sample, model: orders, given: [], expect: {rows: []}}]\n")
    Vector(">=1.4.0", ">=1.8.0", "<1.9.0").permutations.foreach { bounds =>
      edit(config, "name: analytics\nrequire-dbt-version: [" + bounds.map(b => "\"" + b + "\"").mkString(", ") + "]\n")
      val w = workspace
      val from = w.roots.head
      assertEquals(bounds.toString, "1.8.0", from.version)
      assertTrue(bounds.toString, from.supportsKind("unit_test"))
      assertFalse(bounds.toString, from.supportsKind("function"))
      assertEquals(bounds.toString, Vector(unit), w.declarations(Set("unit_test"), "sample", from).map(_.file))
    }
  }

  def testRequireDbtVersionRetainsPatchBounds(): Unit = {
    val config = bareProject()
    Vector("=1.10.4" -> false, "1.10.5" -> true, "1.10.5+build.7" -> true, ">1.10.4,<1.11.0" -> true,
      ">=1.10.4,<=1.10.4" -> false).foreach { case (range, supportsArguments) =>
      edit(config, "name: analytics\nrequire-dbt-version: \"" + range + "\"\n")
      assertEquals(range, supportsArguments, workspace.roots.head.supports(10, 5))
    }
  }

  def testUnusableVersionRangesDoNotEnableVersionedFeatures(): Unit = {
    val config = bareProject()
    Vector("<1.5.0", ">=1.8.0,<1.8.0", ">1.8.0,<=1.8.0", "=1.8.0,=1.9.0",
      ">=1.8.0,invalid", ">=1.8.0,", ">=1.8.0-beta.1").foreach { range =>
      edit(config, "name: analytics\nrequire-dbt-version: \"" + range + "\"\n")
      assertFalse(range, workspace.roots.head.supports(5))
    }
  }

  def testExplicitDbtVersionOverridesRangeAndDefaultIsUnchanged(): Unit = {
    val config = bareProject()
    assertEquals("1.10", workspace.roots.head.version)
    edit(config, "name: analytics\nrequire-dbt-version: [\">=1.5.0\", \"<1.6.0\"]\n")
    add(".dbt-navigation.yml", "dbt-version: '1.12'\n")
    val from = workspace.roots.head
    assertEquals("1.12", from.version)
    assertTrue(from.supportsKind("function"))
  }

  def testSqlProjectionsFollowSourceAndPropertyChanges(): Unit = {
    val config = bareProject()
    val orders = add("models/orders.sql", "select 1 as old_id")
    val use = add("models/use.sql", "select o.old_id from {{ ref('orders') }} o")
    assertEquals(Vector(orders), targetsAt(use, "old_id").map(_.getContainingFile))

    edit(orders, "select 1 as new_id")
    assertTrue(targetsAt(use, "old_id").isEmpty)
    edit(use, "select o.new_id from {{ ref('orders') }} o")
    assertEquals(Vector(orders), targetsAt(use, "new_id").map(_.getContainingFile))

    val properties = add("models/schema.yml", "models:\n- name: orders\n  columns: [{name: new_id}]\n")
    assertEquals(Vector(properties), targetsAt(use, "new_id").map(_.getContainingFile))
    edit(config, "name: renamed\n")
    assertEquals(Vector(properties), targetsAt(use, "new_id").map(_.getContainingFile))
    edit(properties, "models:\n- name: orders\n")
    val projected = targetsAt(use, "new_id").head.asInstanceOf[DbtSymbolElement]
    assertEquals(orders, projected.getContainingFile)
    assertEquals("renamed", projected.symbol.namespace)
  }

  def testDuplicateRootNamesKeepModelsPropertiesAndColumnsSeparate(): Unit = {
    bareProject(path = "a/")
    bareProject(path = "b/")
    val a = add("a/models/orders.sql", "select 1")
    val b = add("b/models/orders.sql", "select 2")
    val props = add("b/models/schema.yml", "models:\n- name: orders\n  config: {enabled: false}\n  columns:\n  - name: id\n")
    val use = add("a/models/use.sql", "select o.id from {{ ref('orders') }} o")
    assertEquals(Vector(a), targetsAt(use, "orders").map(_.getContainingFile))
    assertTrue(targetsAt(use, "id").isEmpty)
    val w = workspace
    assertTrue(w.properties(w.symbols.find(s => s.file == a && s.kind == "model").get).isEmpty)
    assertFalse(w.symbols.find(s => s.file == a && s.kind == "model").get.attributes.get("enabled").contains("false"))
    assertEquals(0, ReferencesSearch.search(w.symbols.find(s => s.file == b && s.kind == "model").get.element).findAll().size())
    assertTrue(props.isValid)
  }

  def testYamlContextMatrixAndDescriptionOnlyDocs(): Unit = {
    bareProject("vars: {color: red}\nmodels:\n  analytics:\n    +schema: \"{{ ref('orders') }}\"\n    +post-hook: \"{{ audit() }}\"\n")
    add("models/orders.sql", "select 1")
    add("macros/audit.sql", "{% macro audit() %}{% endmacro %}")
    add("docs/catalog.md", "{% docs orders_doc %}Orders{% enddocs %}")
    val properties = add("models/schema.yml", "models:\n- name: orders\n  description: \"{{ doc('orders_doc') }}\"\n  config:\n    meta:\n      text: \"{{ ref('orders') }}\"\n      description: \"{{ doc('orders_doc') }}\"\n    post_hook: \"{{ audit() }}\"\n")
    assertEquals(1, targetsAt(properties, "orders_doc").size)
    assertTrue(targetsAt(properties, "orders_doc", 1).isEmpty)
    assertTrue(targetsAt(properties, "'orders'").isEmpty)
    assertEquals(1, targetsAt(properties, "audit").size)
    val sql = add("models/use.sql", "{{ doc('orders_doc') }}")
    assertTrue(targetsAt(sql, "orders_doc").isEmpty)
    assertTrue(targetsAt(projectFile("dbt_project.yml"), "'orders'").isEmpty)
    assertEquals(1, targetsAt(projectFile("dbt_project.yml"), "audit").size)
    val unrelated = add("models/readme.yml", "message: \"{{ ref('orders') }}\"\n")
    assertTrue(DbtNavigation.inFile(unrelated).isEmpty)
    val meta = add("models/meta.yml", "models:\n- name: orders\n  config:\n    meta:\n      tests: [\"{{ ref('orders') }}\"]\n      given: [{input: \"source('raw', 'events')\"}]\n      defined_in: orders\n")
    assertTrue(targetsAt(meta, "'orders'").isEmpty)
    assertTrue(targetsAt(meta, "orders", 2).isEmpty)
  }


  def testProfileRuntimeVarsAndCustomProfilesPath(): Unit = {
    bareProject("profile: analytics\nvars: {color: default}\n")
    add(".dbt-navigation.yml", "profiles-file: config/profiles.yml\nrun-config: run/dev.yml\ntarget: dev\n")
    val run = add("run/dev.yml", "vars: {color: explicit}\n")
    val profile = add("config/profiles.yml", "analytics:\n  target: prod\n  outputs:\n    dev:\n      type: postgres\n      schema: \"{{ var('color') }}\"\n")
    assertEquals(Vector(run), targetsAt(profile, "color").map(_.getContainingFile))
    val use = add("models/use.sql", "{{ target.name }}")
    assertTrue(targetsAt(use, "name").forall(_.getContainingFile.getName == ".dbt-navigation.yml"))
    assertEquals(1, targetsAt(use, "name").size)
  }

  def testRelationshipsColumnsResolveTheActualSourceAndVersion(): Unit = {
    bareProject()
    add("models/orders.sql", "select 1")
    add("models/customers_v1.sql", "select 1")
    add("models/customers_v2.sql", "select 2")
    val props = add("models/schema.yml", "sources:\n- name: raw\n  tables:\n  - name: customers\n    columns: [{name: customer_id}]\n- name: other\n  tables:\n  - name: customers\n    columns: [{name: customer_id}]\nmodels:\n- name: customers\n  latest_version: 2\n  versions:\n  - v: 1\n    columns: [{name: old_id}]\n  - v: 2\n    columns: [{name: customer_id}]\n- name: orders\n  data_tests:\n  - relationships:\n      arguments: {to: \"source('raw', 'customers')\", field: customer_id}\n  - relationships:\n      arguments: {to: \"ref('customers', v=2)\", field: customer_id}\n")
    val source = targetsAt(props, "customer_id", 3).collect { case e: DbtSymbolElement => e.symbol }
    assertEquals(Vector("raw.customers"), source.map(_.owner))
    val version = targetsAt(props, "customer_id", 4).collect { case e: DbtSymbolElement => e.symbol }
    assertEquals(Vector(Some("2")), version.map(_.version))
  }

  def testLegacyTestArgumentsGateAndDisabledChecks(): Unit = {
    bareProject()
    add(".dbt-navigation.yml", "dbt-version: '1.9'\n")
    add("macros/test.sql", "{% test positive(model, minimum) %}{% endtest %}")
    val props = add("models/tests.yml", "models:\n- name: orders\n  tests:\n  - positive: {minimum: 0}\n  - positive: {arguments: {minimum: 0}}\n")
    assertEquals(1, targetsAt(props, "minimum").size)
    assertTrue(targetsAt(props, "minimum", 1).isEmpty)
    edit(projectFile(".dbt-navigation.yml"), "engine: v2\n")
    val check = add("checks/off.sql", "{{ config(enabled=false) }}select name from {{ info_schema('models') }}")
    assertTrue(targetsAt(check, "models").isEmpty)
  }

  def testStaticNamesCannotEscapeParameterWithOrLoopShadowing(): Unit = {
    bareProject()
    add("models/orders.sql", "select 1")
    val file = add("models/use.sql", "{% set name = 'orders' %}{% macro m(name) %}{{ ref(name) }}{% endmacro %}{% with name=unknown %}{{ ref(name) }}{% endwith %}{% for name in unknown %}{{ ref(name) }}{% endfor %}{{ ref(name) }}")
    (2 to 6 by 2).foreach(n => assertFalse(targetsAt(file, "name", n).exists(_.getContainingFile.getName == "orders.sql")))
    assertTrue(targetsAt(file, "name", 7).exists(_.getContainingFile.getName == "orders.sql"))
  }

  def testStaticSourceAndVarsUseTheSharedResolvers(): Unit = {
    bareProject("vars: {start_date: today}\n")
    add("models/source.yml", "sources:\n- name: raw\n  tables:\n  - name: events\n")
    add("dbt_packages/util/dbt_project.yml", "name: util\n")
    add("dbt_packages/util/models/source.yml", "sources:\n- name: raw\n  tables:\n  - name: events\n")
    val file = add("models/use.sql", "{% set raw = 'raw' %}{{ source(raw, 'events') }}{{ var('start_' ~ 'date') }}")
    assertEquals(1, targetsAt(file, "events").size)
    assertFalse(targetsAt(file, "events").head.getContainingFile.getVirtualFile.getPath.contains("dbt_packages"))
    assertTrue(DbtNavigation.inFile(file).exists(r => r.label == "static reference" && r.candidates.exists(_.isInstanceOf[org.jetbrains.yaml.psi.YAMLKeyValue])))
  }

  def testIgnoreNegationsAndAnchoredPatternsUpdateOnEdits(): Unit = {
    bareProject()
    val ignore = add(".dbtignore", "models/*\n!models/keep/\n!models/keep/**\n/root_only.sql\n")
    val keep = add("models/keep/orders.sql", "select 1")
    add("models/drop/orders.sql", "select 2")
    add("models/keep/root_only.sql", "select 3")
    val use = add("tests/use.sql", "{{ ref('orders') }}{{ ref('root_only') }}")
    assertEquals(Vector(keep), targetsAt(use, "orders").map(_.getContainingFile))
    assertEquals(1, targetsAt(use, "root_only").size)
    edit(ignore, "models/**\n")
    assertTrue(targetsAt(use, "orders").isEmpty)
  }

  def testSelectorReferencesShorthandInheritanceAndVersion(): Unit = {
    bareProject()
    val selectors = add("selectors.yml", "selectors:\n- name: nightly\n  description: 'orders'\n  definition: orders\n- name: use\n  definition:\n    intersection:\n    - method: selector\n      value: nightly\n    - tag: finance\n    - exclude: [missing]\n- name: reuse\n  definition: 'selector:nightly'\n")
    val model = add("models/orders.sql", "select 1")
    val w = workspace
    val r = w.roots.head
    assertEquals(1, targetsAt(selectors, "nightly", 1).size)
    assertTrue(targetsAt(selectors, "selector:nightly").isEmpty)
    add(".dbt-navigation.yml", "dbt-version: '1.12'\n")
    assertEquals(1, targetsAt(selectors, "nightly", 2).size)
    assertTrue(targetsAt(selectors, "orders").isEmpty)
    assertEquals(1, targetsAt(selectors, "orders", 1).size)
    assertEquals(1, ReferencesSearch.search(workspace.symbols.find(s => s.file == model && s.kind == "model").get.element,
      GlobalSearchScope.fileScope(getProject, selectors.getVirtualFile)).findAll().size())
    assertTrue(DbtSelectors.select(w, r, "state:modified", includeGraph = false).isEmpty)
  }

  def testDefaultDispatchPrefersRootOverrideAndProfileTypeIsDecoded(): Unit = {
    bareProject("profile: analytics\n")
    add(".dbt-navigation.yml", "target: dev\n")
    add("profiles.yml", "analytics:\n  outputs:\n    dev:\n      type: 'redshift'\n")
    add("dbt_packages/util/dbt_project.yml", "name: util\n")
    add("dbt_packages/util/macros/format.sql", "{% macro default__format() %}{% endmacro %}")
    val overrideFile = add("macros/format.sql", "{% macro postgres__format() %}{% endmacro %}")
    val file = add("models/use.sql", "{{ adapter.dispatch('format', 'util')() }}")
    assertEquals(Vector(overrideFile), targetsAt(file, "format").map(_.getContainingFile))
  }

  def testManifestSourceArgumentsAndMissingVersionStayDistinct(): Unit = {
    bareProject()
    add(".dbt-navigation.yml", "manifest: target/manifest.json\n")
    add("models/orders_v1.sql", "select 1")
    add("models/versions.yml", "models:\n- name: orders\n  versions:\n  - v: 1\n")
    add("target/manifest.json", """{"metadata":{"dbt_schema_version":"https://schemas.getdbt.com/dbt/manifest/v12.json"},"sources":{"source.analytics.raw.events":{"unique_id":"source.analytics.raw.events","resource_type":"source","name":"events","source_name":"raw","package_name":"analytics"}},"nodes":{"model.analytics.orders.v2":{"unique_id":"model.analytics.orders.v2","resource_type":"model","name":"orders","version":2,"package_name":"analytics","original_file_path":"models/orders_v2.sql"}}}""")
    val file = add("models/use.sql", "{{ source('raw', 'events') }}{{ ref('orders', v=2) }}")
    assertEquals(Vector("source"), targetsAt(file, "raw").collect { case e: DbtSymbolElement => e.symbol.kind })
    assertEquals(Vector("source_table"), targetsAt(file, "events").collect { case e: DbtSymbolElement => e.symbol.kind })
    assertEquals(Vector(Some("2")), targetsAt(file, "orders").collect { case e: DbtSymbolElement => e.symbol.version })
    assertEquals(1, ReferencesSearch.search(targetsAt(file, "events").head).findAll().size())
  }

  def testSnapshotBlockNamesReplaceTheContainerFilename(): Unit = {
    bareProject()
    val definition = add("snapshots/all.sql", "{% snapshot history %}{{ config(unique_key='id') }}select 1{% endsnapshot %}")
    val file = add("models/use.sql", "{{ ref('history') }}{{ ref('all') }}")
    assertEquals(Vector(definition), targetsAt(file, "history").map(_.getContainingFile))
    assertTrue(targetsAt(file, "all").isEmpty)
    assertEquals(definition.getText.indexOf("history"), targetsAt(file, "history").head.getTextOffset)
  }

  def testUnitTestDuplicateMissingFixturesAndNamespace(): Unit = {
    bareProject("test-paths: [tests, extra_tests]\n")
    add("dbt_packages/util/dbt_project.yml", "name: util\n")
    val model = add("dbt_packages/util/models/orders.sql", "select 1")
    add("tests/fixtures/input.csv", "id\n1\n")
    add("extra_tests/fixtures/input.csv", "id\n2\n")
    val unit = add("models/unit.yml", "unit_tests:\n- name: sample\n  model: util.orders\n  given:\n  - input: ref('util', 'orders')\n    format: csv\n    fixture: input\n  expect: {format: csv, fixture: missing}\n")
    assertEquals(Vector(model), targetsAt(unit, "util.orders").map(_.getContainingFile))
    assertEquals(2, targetsAt(unit, "input", 1).size)
    assertTrue(targetsAt(unit, "missing").isEmpty)
    add(".dbt-navigation.yml", "dbt-version: '1.7'\n")
    assertTrue(targetsAt(unit, "util.orders").isEmpty)
    assertTrue(targetsAt(unit, "input", 1).isEmpty)
  }

  def testVersionSpecificTestArguments(): Unit = {
    bareProject()
    val settings = add(".dbt-navigation.yml", "dbt-version: '1.10.4'\n")
    add("macros/test.sql", "{% test positive(model, minimum) %}{% endtest %}")
    val props = add("models/tests.yml", "models:\n- name: orders\n  data_tests:\n  - positive: {arguments: {minimum: 0}}\n")
    assertTrue(targetsAt(props, "minimum").isEmpty)
    edit(settings, "dbt-version: '1.10.5'\n")
    assertEquals(1, targetsAt(props, "minimum").size)
  }

  def testYamlConstantsRemainWithinTheirScalar(): Unit = {
    bareProject()
    add("models/orders.sql", "select 1")
    val file = add("models/schema.yml", "models:\n- name: orders\n  config:\n    pre_hook: \"{% set name = 'orders' %}{{ ref(name) }}\"\n    post_hook: \"{{ ref(name) }}\"\n")
    assertTrue(targetsAt(file, "name", 2).exists(_.getContainingFile.getName == "orders.sql"))
    assertFalse(targetsAt(file, "name", 3).exists(_.getContainingFile.getName == "orders.sql"))
  }

  def testConfigurationFoldersPackagesSourcesAndNewResourceGates(): Unit = {
    val config = bareProject("models:\n  analytics:\n    marts:\n      orders: {+materialized: table}\n  util:\n    packaged: {}\nsources:\n  analytics:\n    raw:\n      +enabled: false\n      events: {}\nfunctions:\n  analytics:\n    hash: {}\n")
    add("models/marts/orders.sql", "select 1")
    add("dbt_packages/util/dbt_project.yml", "name: util\n")
    val packageModel = add("dbt_packages/util/models/packaged.sql", "select 1")
    add("models/source.yml", "sources:\n- name: raw\n  tables: [{name: events}]\n")
    add("functions/hash.sql", "select 1")
    assertTrue(targetsAt(config, "marts").head.isInstanceOf[com.intellij.psi.PsiDirectory])
    assertEquals(Vector(packageModel), targetsAt(config, "packaged").map(_.getContainingFile))
    val use = add("models/use.sql", "{{ source('raw', 'events') }}")
    assertTrue(targetsAt(use, "raw").isEmpty)
    assertTrue(targetsAt(use, "events").isEmpty)
    assertTrue(targetsAt(config, "hash").isEmpty)
    add(".dbt-navigation.yml", "dbt-version: '1.11'\n")
    assertEquals(1, targetsAt(config, "hash").size)
  }

  def testBuiltinSourceAndDocumentationTargetsFindOnlyLocalUsages(): Unit = {
    bareProject()
    add(".dbt-navigation.yml", "builtin-sources:\n  adapter.get_relation: vendor/adapter.sql\n")
    val source = add("vendor/adapter.sql", "{% macro get_relation(database, schema, identifier) %}{% endmacro %}")
    val use = add("models/use.sql", "{{ adapter.get_relation('db', 's', 't') }} {{ config.require('unique_key') }} {{ dbt_version }}")
    assertEquals(Vector(source), targetsAt(use, "adapter.get_relation").map(_.getContainingFile))
    assertEquals(1, ReferencesSearch.search(targetsAt(use, "adapter.get_relation").head).findAll().size())
    assertEquals(Vector("documentation"), targetsAt(use, "config.require").collect { case e: DbtSymbolElement => e.symbol.kind })
    assertEquals(Vector("documentation"), targetsAt(use, "dbt_version").collect { case e: DbtSymbolElement => e.symbol.kind })
    assertEquals(0, ReferencesSearch.search(targetsAt(use, "dbt_version").head).findAll().size())
  }

  def testFunctionLanguageAdapterOverloadAndCallerGates(): Unit = {
    bareProject()
    val settings = add(".dbt-navigation.yml", "dbt-version: '1.11'\nadapter: snowflake\n")
    val js = add("functions/hash.js", "return input;")
    val sql = add("functions/hash_numeric.sql", "input > 0")
    val props = add("functions/hash.yml", "functions:\n- name: hash\n  config: {language: javascript}\n  overloads:\n  - defined_in: hash_numeric\n")
    val use = add("models/use.sql", "{{ function('hash') }}")
    assertTrue(targetsAt(use, "hash").isEmpty)
    edit(settings, "dbt-version: '1.12'\nadapter: snowflake\n")
    assertEquals(Set(js, sql), targetsAt(use, "hash").map(_.getContainingFile).toSet)
    val hook = add("models/hook.yml", "models:\n- name: use\n  config:\n    post_hook: \"{{ function('hash') }}\"\n")
    assertEquals(3, ReferencesSearch.search(targetsAt(use, "hash").find(_.getContainingFile == js).get).findAll().size())
    edit(settings, "dbt-version: '1.12'\nadapter: postgres\n")
    assertFalse(targetsAt(use, "hash").exists(_.getContainingFile == js))
    assertTrue(hook.isValid && props.isValid)
  }

  def testLineageHonorsScopeEditsCyclesAndCancellation(): Unit = {
    bareProject()
    val orders = add("models/orders.sql", "{{ ref('customers') }}")
    val customers = add("models/customers.sql", "{{ ref('orders') }}")
    val w = workspace
    val edges = DbtLineage.edges(w, Some(GlobalSearchScope.fileScope(getProject, orders.getVirtualFile)))
    assertEquals(1, edges.size)
    assertEquals("orders", edges.head.from.name)
    edit(orders, "{{ ref('missing') }}")
    assertEquals(1, DbtLineage.edges(workspace).size)
    val start = edges.head.from
    val canceled = new java.util.concurrent.atomic.AtomicBoolean(false)
    com.intellij.openapi.application.ApplicationManager.getApplication.executeOnPooledThread(new Runnable {
      override def run(): Unit = {
        val indicator = new com.intellij.openapi.progress.EmptyProgressIndicator
        try com.intellij.openapi.progress.ProgressManager.getInstance.runProcess(new Runnable {
          override def run(): Unit = {
            indicator.cancel();
            DbtLineage.traverse(edges, start, downstream = false, 100);
            ()
          }
        }, indicator) catch {
          case _: com.intellij.openapi.progress.ProcessCanceledException => canceled.set(true)
        }
      }
    }).get(10, java.util.concurrent.TimeUnit.SECONDS)
    assertTrue(canceled.get())
    assertTrue(customers.isValid)
  }

  def testCurrentMetricInputsAliasesSavedQueriesAndTimeSpines(): Unit = {
    bareProject()
    add(".dbt-navigation.yml", "dbt-version: '1.12'\n")
    add("models/orders.sql", "select 1")
    val schema = add("models/semantics.yml", "models:\n- name: orders\n  semantic_model: {name: sales}\n  time_spine: {standard_granularity_column: ordered_at}\n  columns:\n  - name: customer_id\n    entity: {name: customer, type: primary}\n  - name: ordered_at\n    dimension: {type: time}\n  - name: country\n    dimension: {type: categorical}\n  - name: amount\n  metrics:\n  - name: revenue\n    type: simple\n    expr: amount\n  - name: running_revenue\n    type: cumulative\n    input_metric: revenue\n  - name: conversion\n    type: conversion\n    entity: customer\n    base_metric: revenue\n    conversion_metric: running_revenue\nmetrics:\n- name: growth\n  type: derived\n  input_metrics:\n  - name: revenue\n    alias: gross\n  expr: gross + 1\nsaved_queries:\n- name: report\n  query_params:\n    metrics: [growth]\n    group_by: [\"Dimension('customer__country')\"]\n    where: \"TimeDimension('ordered_at', 'DAY') > '2026-01-01'\"\n    order_by: [\"Metric('growth')\"]\n  exports: [{name: daily}]\n")
    assertEquals(1, targetsAt(schema, "ordered_at").size)
    assertEquals(Vector("column"), targetsAt(schema, "amount", 1).collect { case e: DbtSymbolElement => e.symbol.kind })
    assertEquals(Vector("metric"), targetsAt(schema, "revenue", 2).collect { case e: DbtSymbolElement => e.symbol.kind })
    assertEquals(Vector("metric_alias"), targetsAt(schema, "gross", 1).collect { case e: DbtSymbolElement => e.symbol.kind })
    assertEquals(Vector("entity"), targetsAt(schema, "customer", 2).collect { case e: DbtSymbolElement => e.symbol.kind })
    assertEquals(Vector("dimension"), targetsAt(schema, "country", 1).collect { case e: DbtSymbolElement => e.symbol.kind })
    assertEquals(Vector("saved_query"), targetsAt(schema, "daily").collect { case e: DbtSymbolElement => e.symbol.kind })
    val metric = workspace.symbols.find(s => s.kind == "metric" && s.name == "revenue").get.element
    assertEquals(3, ReferencesSearch.search(metric).findAll().size())
    val legacy = add("models/legacy.yml", "semantic_models:\n- name: activity\n  entities: [{name: actor, type: primary}]\n  measures: [{name: visits}, {name: purchases}]\nmetrics:\n- name: conversion_legacy\n  type: conversion\n  type_params:\n    entity: actor\n    base_measure: {name: visits}\n    conversion_measure: {name: purchases}\n")
    assertEquals(Vector("measure"), targetsAt(legacy, "visits", 1).collect { case e: DbtSymbolElement => e.symbol.kind })
    assertEquals(Vector("measure"), targetsAt(legacy, "purchases", 1).collect { case e: DbtSymbolElement => e.symbol.kind })
    assertEquals(1, ReferencesSearch.search(targetsAt(legacy, "visits", 1).head).findAll().size())
  }

  def testNumericAdditionAndReassignedWrapperArgumentsStayUnknown(): Unit = {
    bareProject()
    add("models/orders.sql", "select 1")
    add("models/12.sql", "select 1")
    add("macros/wrapper.sql", "{% macro lookup(name) %}{% set name = env_var('MODEL') %}{{ builtins.ref(name) }}{% endmacro %}")
    val file = add("models/use.sql", "{{ ref(1 + 2) }}{{ lookup('orders') }}")
    assertTrue(targetsAt(file, "orders").isEmpty)
    assertFalse(DbtNavigation.inFile(file).flatMap(_.candidates).exists(_.getContainingFile.getName == "12.sql"))
  }

  def testTransitivePackageMacroVariablesUseTheirOwnCallingNode(): Unit = {
    bareProject("vars:\n  analytics: {color: root_node}\n  util: {color: package_node}\n")
    add("dbt_packages/util/dbt_project.yml", "name: util\n")
    val macros = add("dbt_packages/util/macros/colors.sql", "{% macro root_color() %}{{ var('color') }}{% endmacro %}{% macro package_color() %}{{ var('color') }}{% endmacro %}{% macro wrapper() %}{{ util.root_color() }}{% endmacro %}")
    add("models/use.sql", "{{ util.wrapper() }}")
    add("dbt_packages/util/models/use.sql", "{{ util.package_color() }}")
    val rootColor = targetsAt(macros, "'color'").head.asInstanceOf[org.jetbrains.yaml.psi.YAMLKeyValue]
    val packageColor = targetsAt(macros, "'color'", 1).head.asInstanceOf[org.jetbrains.yaml.psi.YAMLKeyValue]
    assertEquals("root_node", rootColor.getValueText)
    assertEquals("package_node", packageColor.getValueText)
  }

  def testStaticEvidenceInvalidatesAfterConstantAndWrapperEdits(): Unit = {
    bareProject()
    val orders = add("models/orders.sql", "select 1")
    val customers = add("models/customers.sql", "select 1")
    val wrapper = add("macros/lookup.sql", "{% macro lookup(name) %}{{ builtins.ref(name) }}{% endmacro %}")
    val use = add("models/use.sql", "{% set name = 'orders' %}{{ ref(name) }}{{ lookup('orders') }}")
    val static = DbtNavigation.inFile(use).find(_.label == "static reference").get
    assertEquals(Vector(orders), static.candidates.map(_.getContainingFile))
    assertTrue(static.evidence.nonEmpty)
    assertTrue(DbtNavigation.inFile(use).find(_.label == "delegated reference").get.evidence.exists(_.contains("analytics.lookup")))
    edit(use, "{% set name = 'customers' %}{{ ref(name) }}{{ lookup('orders') }}")
    assertEquals(Vector(customers), DbtNavigation.inFile(use).find(_.label == "static reference").get.candidates.map(_.getContainingFile))
    edit(wrapper, "{% macro lookup(name) %}{{ builtins.ref('missing') }}{% endmacro %}")
    assertTrue(targetsAt(use, "'orders'").isEmpty)
  }

  def testChecksIgnorePathsUnsupportedVersionsAndIncompleteQueries(): Unit = {
    bareProject("check-paths: [metadata_checks]\ninfo_schema: {version: 1}\n")
    val settings = add(".dbt-navigation.yml", "engine: v2\n")
    add("models/orders.sql", "select 1")
    val check = add("metadata_checks/partial.sql", "select name from {{ info_schema('models') }} where name =")
    assertEquals(1, targetsAt(check, "models").size)
    add(".dbtignore", "metadata_checks/ignored.sql\n")
    val ignored = add("metadata_checks/ignored.sql", "select name from {{ info_schema('models') }}")
    assertTrue(DbtNavigation.inFile(ignored).isEmpty)
    edit(projectFile("dbt_project.yml"), "name: analytics\ncheck-paths: [metadata_checks]\ninfo_schema: {version: 999}\n")
    assertTrue(targetsAt(check, "models").isEmpty)
    edit(settings, "engine: core\ndbt-version: '1.12'\n")
    assertTrue(DbtNavigation.inFile(check).isEmpty)
  }

  def testFindUsagesFromPropertiesKeepsTheResourceAndVersionIdentity(): Unit = {
    bareProject()
    add("models/orders_v1.sql", "select 1")
    add("models/orders_v2.sql", "select 2")
    val properties = add("models/orders.yml", "models:\n- name: orders\n  latest_version: 2\n  versions:\n  - v: 1\n  - v: 2\n")
    val use = add("models/use.sql", "{{ ref('orders', v=1) }}{{ ref('orders', v=2) }}")
    val symbols = workspace.symbols.filter(s => s.file == properties && s.kind == "model")
    symbols.foreach { symbol =>
      val usages = ReferencesSearch.search(symbol.element, GlobalSearchScope.fileScope(getProject, use.getVirtualFile)).findAll()
      assertEquals(1, usages.size())
      val target = usages.iterator().next().resolve().asInstanceOf[DbtSymbolElement]
      assertEquals(symbol.version, target.symbol.version)
    }
    val missing = add("models/missing.yml", "models:\n- name: missing\n")
    assertEquals(0, ReferencesSearch.search(workspace.symbols.find(s => s.file == missing).get.element).findAll().size())
  }

  def testTupleAssignmentsAndCaptureBodiesRetainLexicalScope(): Unit = {
    bareProject()
    add("models/orders.sql", "select 1")
    val file = add("models/use.sql", "{% set name = 'orders' %}{% set first, name = unknown %}{{ name }}{{ ref(name) }}{% set label = 'outer' %}{% set label %}{{ label }}{% endset %}{{ label }}")
    assertEquals(file.getText.indexOf("name", file.getText.indexOf("name") + 1), targetsAt(file, "name", 2).head.getTextOffset)
    assertFalse(targetsAt(file, "name", 3).exists(_.getContainingFile.getName == "orders.sql"))
    val outer = targetsAt(file, "label", 2).head
    val captured = targetsAt(file, "label", 3).head
    assertEquals(file.getText.indexOf("label"), outer.getTextOffset)
    assertEquals(file.getText.indexOf("label", outer.getTextOffset + 1), captured.getTextOffset)
  }

  def testCatalogColumnsRespectProjectAndInstalledPackageOwners(): Unit = {
    bareProject()
    add(".dbt-navigation.yml", "catalog: target/catalog.json\n")
    add("models/orders.sql", "select 1")
    bareProject(path = "other/")
    add("other/models/orders.sql", "select 2")
    add("other/models/schema.yml", "models:\n- name: orders\n  columns: [{name: total}]\n")
    add("dbt_packages/util/dbt_project.yml", "name: util\n")
    add("dbt_packages/util/models/orders.sql", "select 3")
    add("target/catalog.json", """{"metadata":{"dbt_schema_version":"https://schemas.getdbt.com/dbt/catalog/v1.json","generated_at":"2099-01-01T00:00:00Z"},"nodes":{"model.analytics.orders":{"unique_id":"model.analytics.orders","columns":{"total":{"name":"total"}}},"model.util.orders":{"unique_id":"model.util.orders","columns":{"package_total":{"name":"package_total"}}}}}""")
    val use = add("models/use.sql", "select a.total, p.package_total from {{ ref('orders') }} a join {{ ref('util', 'orders') }} p on true")
    assertEquals(Vector("target/catalog.json"), targetsAt(use, "total").map(e => workspace.root(use).get.relative(e.getContainingFile.getVirtualFile).get))
    assertEquals(1, targetsAt(use, "package_total").size)
    assertTrue(targetsAt(use, "package_total").head.asInstanceOf[DbtSymbolElement].symbol.metadata)
  }

  def testSemanticJoinCandidatesStayAmbiguousAndUpdateOnEdits(): Unit = {
    bareProject()
    val schema = add("models/semantic.yml", "semantic_models:\n- name: sales\n  entities: [{name: customer, type: primary}]\n  dimensions: [{name: country}]\n- name: customers\n  entities: [{name: customer, type: primary}]\n  dimensions: [{name: country}]\n")
    val use = add("models/use.sql", "{{ Dimension('customer__country') }}{{ Dimension('missing__country') }}")
    val ref = DbtNavigation.at(use, use.getText.indexOf("country") + 1).head
    assertEquals(2, ref.candidates.size)
    assertNull(ref.resolve())
    assertTrue(targetsAt(use, "missing__country").isEmpty)
    edit(schema, schema.getText.replace("name: sales\n  entities: [{name: customer, type: primary}]", "name: sales\n  entities: [{name: customer, type: foreign}]"))
    assertEquals(1, targetsAt(use, "country").size)
  }

  def testSelectorsKeepDuplicateNamesAnchorsAndShorthandConservative(): Unit = {
    bareProject("models:\n  analytics:\n    +tags: [finance]\n")
    val orders = add("models/orders.sql", "select 1")
    val selectors = add("selectors.yml", "selectors:\n- name: nightly\n  definition: &finance {tag: finance}\n- name: nightly\n  definition: 'fqn:missing'\n- name: use\n  definition:\n    union:\n    - selector: nightly\n    - *finance\n")
    assertEquals(Vector(orders), targetsAt(selectors, "finance", 1).map(_.getContainingFile))
    val named = DbtNavigation.at(selectors, selectors.getText.lastIndexOf("nightly") + 1).head
    assertEquals(2, named.candidates.size)
    assertNull(named.resolve())
    assertTrue(targetsAt(selectors, "*finance").isEmpty)
  }

  def testLineageKeepsSnapshotAndGenericTestBlockOwners(): Unit = {
    bareProject()
    add("models/orders.sql", "select 1")
    add("models/customers.sql", "select 2")
    add("snapshots/history.sql", "{% snapshot first %}{{ ref('orders') }}{% endsnapshot %}{% snapshot second %}{{ ref('customers') }}{% endsnapshot %}{% snapshot off %}{{ config(enabled=false) }}select 3{% endsnapshot %}")
    add("macros/tests.sql", "{% test compares(model) %}{{ ref('orders') }}{% endtest %}")
    val use = add("models/use.sql", "{{ ref('off') }}")
    val w = workspace
    val edges = DbtLineage.edges(w)
    assertTrue(edges.exists(e => e.from.name == "first" && e.to.name == "orders"))
    assertTrue(edges.exists(e => e.from.name == "second" && e.to.name == "customers"))
    assertFalse(edges.exists(e => e.from.name == "first" && e.to.name == "customers"))
    assertTrue(edges.exists(e => e.from.kind == "generic_test" && e.from.name == "compares" && e.to.name == "orders"))
    assertTrue(targetsAt(use, "off").isEmpty)
  }

  def testDeferredHookVarsAndEnabledSourceTableOverrides(): Unit = {
    val config = bareProject("vars: {start_date: today}\non-run-start: \"{{ var('start_' ~ 'date') }}\"\nsources:\n  analytics:\n    raw:\n      +enabled: false\n")
    val schema = add("models/source.yml", "sources:\n- name: raw\n  tables:\n  - name: off\n  - name: events\n    config: {enabled: true}\n")
    val use = add("models/use.sql", "{{ source('raw', 'events') }}{{ source('raw', 'off') }}")
    assertEquals(Vector(schema), targetsAt(use, "events").map(_.getContainingFile))
    assertTrue(targetsAt(use, "off").isEmpty)
    assertTrue(DbtNavigation.inFile(config).exists(r => r.label == "static reference" && r.candidates.nonEmpty))
    val profile = add("profiles.yml", "analytics:\n  outputs:\n    dev:\n      schema: \"{{ var('start_' ~ 'date') }}\"\n")
    assertTrue(DbtNavigation.inFile(profile).filter(_.label == "static reference").forall(_.candidates.isEmpty))
  }

  def testNativeEnvironmentFindUsagesTargetsOnlyTheKey(): Unit = {
    bareProject()
    val env = add(".env", "DBT_ENV_SECRET_TOKEN=secret-value\n")
    val use = add("models/use.sql", "{{ env_var('DBT_ENV_SECRET_TOKEN') }}")
    myFixture.configureFromExistingVirtualFile(env.getVirtualFile)
    myFixture.getEditor.getCaretModel.moveToOffset(1)
    val flags = com.intellij.codeInsight.TargetElementUtil.ELEMENT_NAME_ACCEPTED | com.intellij.codeInsight.TargetElementUtil.REFERENCED_ELEMENT_ACCEPTED
    val declaration = com.intellij.codeInsight.TargetElementUtil.findTargetElement(myFixture.getEditor, flags)
    assertTrue(declaration.isInstanceOf[DbtSymbolElement])
    assertEquals("DBT_ENV_SECRET_TOKEN", declaration.getText)
    assertEquals(1, myFixture.findUsages(declaration).size())
    assertEquals(Vector(declaration), targetsAt(use, "DBT_ENV_SECRET_TOKEN"))
  }

  def testRuntimeVersionArgumentsDoNotChooseLatest(): Unit = {
    bareProject()
    add("models/orders_v1.sql", "select 1")
    add("models/orders_v2.sql", "select 2")
    add("models/orders.yml", "models:\n- name: orders\n  latest_version: 2\n  versions: [{v: 1}, {v: 2}]\n")
    val use = add("models/use.sql", "{{ ref('orders', v=runtime_version) }}{{ ref('orders', version=env_var('VERSION')) }}{% set name = 'orders' %}{{ ref(name, v=runtime_version) }}")
    assertTrue(targetsAt(use, "orders").isEmpty)
    assertTrue(targetsAt(use, "orders", 1).isEmpty)
    assertTrue(DbtNavigation.inFile(use).filter(_.label == "static reference").forall(_.candidates.isEmpty))
  }
}
