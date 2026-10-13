package com.github.vbmacher.intellij.dbt

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.{PsiDocumentManager, PsiElement, PsiFile}
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert._

/** Regression cases from the navigation roadmap's shared acceptance criteria. */
final class DbtAcceptanceTest extends BasePlatformTestCase {
  private def add(path: String, text: String): PsiFile = myFixture.addFileToProject(path, text)
  private def root(config: String = "", path: String = ""): PsiFile = add(path + "dbt_project.yml", "name: analytics\n" + config)
  private def targets(file: PsiFile, text: String, occurrence: Int = 0): Vector[PsiElement] = {
    val offset = Iterator.iterate(file.getText.indexOf(text))(i => file.getText.indexOf(text, i + 1)).drop(occurrence).next()
    assertTrue(s"Missing $text", offset >= 0)
    DbtNavigation.at(file, offset + math.min(1, text.length - 1)).flatMap(_.candidates).distinct
  }
  private def edit(file: PsiFile, text: String): Unit = WriteCommandAction.runWriteCommandAction(getProject, new Runnable {
    override def run(): Unit = {
      val manager = PsiDocumentManager.getInstance(getProject)
      manager.getDocument(file).setText(text)
      manager.commitAllDocuments()
    }
  })

  def testDuplicateRootNamesKeepModelsPropertiesAndColumnsSeparate(): Unit = {
    root(path = "a/")
    root(path = "b/")
    val a = add("a/models/orders.sql", "select 1")
    val b = add("b/models/orders.sql", "select 2")
    val props = add("b/models/schema.yml", "models:\n- name: orders\n  config: {enabled: false}\n  columns:\n  - name: id\n")
    val use = add("a/models/use.sql", "select o.id from {{ ref('orders') }} o")
    assertEquals(Vector(a), targets(use, "orders").map(_.getContainingFile))
    assertTrue(targets(use, "id").isEmpty)
    val w = DbtWorkspace.get(getProject)
    assertTrue(w.properties(w.symbols.find(s => s.file == a && s.kind == "model").get).isEmpty)
    assertFalse(w.symbols.find(s => s.file == a && s.kind == "model").get.attributes.get("enabled").contains("false"))
    assertEquals(0, ReferencesSearch.search(w.symbols.find(s => s.file == b && s.kind == "model").get.element).findAll().size())
    assertTrue(props.isValid)
  }

  def testYamlContextMatrixAndDescriptionOnlyDocs(): Unit = {
    root("vars: {color: red}\nmodels:\n  analytics:\n    +schema: \"{{ ref('orders') }}\"\n    +post-hook: \"{{ audit() }}\"\n")
    add("models/orders.sql", "select 1")
    add("macros/audit.sql", "{% macro audit() %}{% endmacro %}")
    add("docs/catalog.md", "{% docs orders_doc %}Orders{% enddocs %}")
    val properties = add("models/schema.yml", "models:\n- name: orders\n  description: \"{{ doc('orders_doc') }}\"\n  config:\n    meta:\n      text: \"{{ ref('orders') }}\"\n      description: \"{{ doc('orders_doc') }}\"\n    post_hook: \"{{ audit() }}\"\n")
    assertEquals(1, targets(properties, "orders_doc").size)
    assertTrue(targets(properties, "orders_doc", 1).isEmpty)
    assertTrue(targets(properties, "'orders'").isEmpty)
    assertEquals(1, targets(properties, "audit").size)
    val sql = add("models/use.sql", "{{ doc('orders_doc') }}")
    assertTrue(targets(sql, "orders_doc").isEmpty)
    assertTrue(targets(getProjectFile("dbt_project.yml"), "'orders'").isEmpty)
    assertEquals(1, targets(getProjectFile("dbt_project.yml"), "audit").size)
    val unrelated = add("models/readme.yml", "message: \"{{ ref('orders') }}\"\n")
    assertTrue(DbtNavigation.inFile(unrelated).isEmpty)
    val meta = add("models/meta.yml", "models:\n- name: orders\n  config:\n    meta:\n      tests: [\"{{ ref('orders') }}\"]\n      given: [{input: \"source('raw', 'events')\"}]\n      defined_in: orders\n")
    assertTrue(targets(meta, "'orders'").isEmpty)
    assertTrue(targets(meta, "orders", 2).isEmpty)
  }

  private def getProjectFile(path: String): PsiFile = com.intellij.psi.PsiManager.getInstance(getProject).findFile(
    DbtWorkspace.get(getProject).roots.head.directory.findFileByRelativePath(path))

  def testProfileRuntimeVarsAndCustomProfilesPath(): Unit = {
    root("profile: analytics\nvars: {color: default}\n")
    add(".dbt-navigation.yml", "profiles-file: config/profiles.yml\nrun-config: run/dev.yml\ntarget: dev\n")
    val run = add("run/dev.yml", "vars: {color: explicit}\n")
    val profile = add("config/profiles.yml", "analytics:\n  target: prod\n  outputs:\n    dev:\n      type: postgres\n      schema: \"{{ var('color') }}\"\n")
    assertEquals(Vector(run), targets(profile, "color").map(_.getContainingFile))
    val use = add("models/use.sql", "{{ target.name }}")
    assertTrue(targets(use, "name").forall(_.getContainingFile.getName == ".dbt-navigation.yml"))
    assertEquals(1, targets(use, "name").size)
  }

  def testRelationshipsColumnsResolveTheActualSourceAndVersion(): Unit = {
    root()
    add("models/orders.sql", "select 1")
    add("models/customers_v1.sql", "select 1")
    add("models/customers_v2.sql", "select 2")
    val props = add("models/schema.yml", "sources:\n- name: raw\n  tables:\n  - name: customers\n    columns: [{name: customer_id}]\n- name: other\n  tables:\n  - name: customers\n    columns: [{name: customer_id}]\nmodels:\n- name: customers\n  latest_version: 2\n  versions:\n  - v: 1\n    columns: [{name: old_id}]\n  - v: 2\n    columns: [{name: customer_id}]\n- name: orders\n  data_tests:\n  - relationships:\n      arguments: {to: \"source('raw', 'customers')\", field: customer_id}\n  - relationships:\n      arguments: {to: \"ref('customers', v=2)\", field: customer_id}\n")
    val source = targets(props, "customer_id", 3).collect { case e: DbtSymbolElement => e.symbol }
    assertEquals(Vector("raw.customers"), source.map(_.owner))
    val version = targets(props, "customer_id", 4).collect { case e: DbtSymbolElement => e.symbol }
    assertEquals(Vector(Some("2")), version.map(_.version))
  }

  def testLegacyTestArgumentsGateAndDisabledChecks(): Unit = {
    root()
    add(".dbt-navigation.yml", "dbt-version: '1.9'\n")
    add("macros/test.sql", "{% test positive(model, minimum) %}{% endtest %}")
    val props = add("models/tests.yml", "models:\n- name: orders\n  tests:\n  - positive: {minimum: 0}\n  - positive: {arguments: {minimum: 0}}\n")
    assertEquals(1, targets(props, "minimum").size)
    assertTrue(targets(props, "minimum", 1).isEmpty)
    edit(getProjectFile(".dbt-navigation.yml"), "engine: v2\n")
    val check = add("checks/off.sql", "{{ config(enabled=false) }}select name from {{ info_schema('models') }}")
    assertTrue(targets(check, "models").isEmpty)
  }

  def testStaticNamesCannotEscapeParameterWithOrLoopShadowing(): Unit = {
    root()
    add("models/orders.sql", "select 1")
    val file = add("models/use.sql", "{% set name = 'orders' %}{% macro m(name) %}{{ ref(name) }}{% endmacro %}{% with name=unknown %}{{ ref(name) }}{% endwith %}{% for name in unknown %}{{ ref(name) }}{% endfor %}{{ ref(name) }}")
    (2 to 6 by 2).foreach(n => assertFalse(targets(file, "name", n).exists(_.getContainingFile.getName == "orders.sql")))
    assertTrue(targets(file, "name", 7).exists(_.getContainingFile.getName == "orders.sql"))
  }

  def testStaticSourceAndVarsUseTheSharedResolvers(): Unit = {
    root("vars: {start_date: today}\n")
    add("models/source.yml", "sources:\n- name: raw\n  tables:\n  - name: events\n")
    add("dbt_packages/util/dbt_project.yml", "name: util\n")
    add("dbt_packages/util/models/source.yml", "sources:\n- name: raw\n  tables:\n  - name: events\n")
    val file = add("models/use.sql", "{% set raw = 'raw' %}{{ source(raw, 'events') }}{{ var('start_' ~ 'date') }}")
    assertEquals(1, targets(file, "events").size)
    assertFalse(targets(file, "events").head.getContainingFile.getVirtualFile.getPath.contains("dbt_packages"))
    assertTrue(DbtNavigation.inFile(file).exists(r => r.label == "static reference" && r.candidates.exists(_.isInstanceOf[org.jetbrains.yaml.psi.YAMLKeyValue])))
  }

  def testIgnoreNegationsAndAnchoredPatternsUpdateOnEdits(): Unit = {
    root()
    val ignore = add(".dbtignore", "models/*\n!models/keep/\n!models/keep/**\n/root_only.sql\n")
    val keep = add("models/keep/orders.sql", "select 1")
    add("models/drop/orders.sql", "select 2")
    add("models/keep/root_only.sql", "select 3")
    val use = add("tests/use.sql", "{{ ref('orders') }}{{ ref('root_only') }}")
    assertEquals(Vector(keep), targets(use, "orders").map(_.getContainingFile))
    assertEquals(1, targets(use, "root_only").size)
    edit(ignore, "models/**\n")
    assertTrue(targets(use, "orders").isEmpty)
  }

  def testSelectorReferencesShorthandInheritanceAndVersion(): Unit = {
    root()
    val selectors = add("selectors.yml", "selectors:\n- name: nightly\n  description: 'orders'\n  definition: orders\n- name: use\n  definition:\n    intersection:\n    - method: selector\n      value: nightly\n    - tag: finance\n    - exclude: [missing]\n- name: reuse\n  definition: 'selector:nightly'\n")
    val model = add("models/orders.sql", "select 1")
    val w = DbtWorkspace.get(getProject)
    val r = w.roots.head
    assertEquals(1, targets(selectors, "nightly", 1).size)
    assertTrue(targets(selectors, "selector:nightly").isEmpty)
    add(".dbt-navigation.yml", "dbt-version: '1.12'\n")
    assertEquals(1, targets(selectors, "nightly", 2).size)
    assertTrue(targets(selectors, "orders").isEmpty)
    assertEquals(1, targets(selectors, "orders", 1).size)
    assertEquals(1, ReferencesSearch.search(DbtWorkspace.get(getProject).symbols.find(s => s.file == model && s.kind == "model").get.element,
      GlobalSearchScope.fileScope(getProject, selectors.getVirtualFile)).findAll().size())
    assertTrue(DbtSelectors.select(w, r, "state:modified", includeGraph = false).isEmpty)
  }

  def testDefaultDispatchPrefersRootOverrideAndProfileTypeIsDecoded(): Unit = {
    root("profile: analytics\n")
    add(".dbt-navigation.yml", "target: dev\n")
    add("profiles.yml", "analytics:\n  outputs:\n    dev:\n      type: 'redshift'\n")
    add("dbt_packages/util/dbt_project.yml", "name: util\n")
    add("dbt_packages/util/macros/format.sql", "{% macro default__format() %}{% endmacro %}")
    val overrideFile = add("macros/format.sql", "{% macro postgres__format() %}{% endmacro %}")
    val file = add("models/use.sql", "{{ adapter.dispatch('format', 'util')() }}")
    assertEquals(Vector(overrideFile), targets(file, "format").map(_.getContainingFile))
  }

  def testManifestSourceArgumentsAndMissingVersionStayDistinct(): Unit = {
    root()
    add(".dbt-navigation.yml", "manifest: target/manifest.json\n")
    add("models/orders_v1.sql", "select 1")
    add("models/versions.yml", "models:\n- name: orders\n  versions:\n  - v: 1\n")
    add("target/manifest.json", """{"metadata":{"dbt_schema_version":"https://schemas.getdbt.com/dbt/manifest/v12.json"},"sources":{"source.analytics.raw.events":{"unique_id":"source.analytics.raw.events","resource_type":"source","name":"events","source_name":"raw","package_name":"analytics"}},"nodes":{"model.analytics.orders.v2":{"unique_id":"model.analytics.orders.v2","resource_type":"model","name":"orders","version":2,"package_name":"analytics","original_file_path":"models/orders_v2.sql"}}}""")
    val file = add("models/use.sql", "{{ source('raw', 'events') }}{{ ref('orders', v=2) }}")
    assertEquals(Vector("source"), targets(file, "raw").collect { case e: DbtSymbolElement => e.symbol.kind })
    assertEquals(Vector("source_table"), targets(file, "events").collect { case e: DbtSymbolElement => e.symbol.kind })
    assertEquals(Vector(Some("2")), targets(file, "orders").collect { case e: DbtSymbolElement => e.symbol.version })
    assertEquals(1, ReferencesSearch.search(targets(file, "events").head).findAll().size())
  }

  def testSnapshotBlockNamesReplaceTheContainerFilename(): Unit = {
    root()
    val definition = add("snapshots/all.sql", "{% snapshot history %}{{ config(unique_key='id') }}select 1{% endsnapshot %}")
    val file = add("models/use.sql", "{{ ref('history') }}{{ ref('all') }}")
    assertEquals(Vector(definition), targets(file, "history").map(_.getContainingFile))
    assertTrue(targets(file, "all").isEmpty)
    assertEquals(definition.getText.indexOf("history"), targets(file, "history").head.getTextOffset)
  }

  def testUnitTestDuplicateMissingFixturesAndNamespace(): Unit = {
    root("test-paths: [tests, extra_tests]\n")
    add("dbt_packages/util/dbt_project.yml", "name: util\n")
    val model = add("dbt_packages/util/models/orders.sql", "select 1")
    add("tests/fixtures/input.csv", "id\n1\n")
    add("extra_tests/fixtures/input.csv", "id\n2\n")
    val unit = add("models/unit.yml", "unit_tests:\n- name: sample\n  model: util.orders\n  given:\n  - input: ref('util', 'orders')\n    format: csv\n    fixture: input\n  expect: {format: csv, fixture: missing}\n")
    assertEquals(Vector(model), targets(unit, "util.orders").map(_.getContainingFile))
    assertEquals(2, targets(unit, "input", 1).size)
    assertTrue(targets(unit, "missing").isEmpty)
    add(".dbt-navigation.yml", "dbt-version: '1.7'\n")
    assertTrue(targets(unit, "util.orders").isEmpty)
    assertTrue(targets(unit, "input", 1).isEmpty)
  }

  def testVersionSpecificTestArguments(): Unit = {
    root()
    val settings = add(".dbt-navigation.yml", "dbt-version: '1.10.4'\n")
    add("macros/test.sql", "{% test positive(model, minimum) %}{% endtest %}")
    val props = add("models/tests.yml", "models:\n- name: orders\n  data_tests:\n  - positive: {arguments: {minimum: 0}}\n")
    assertTrue(targets(props, "minimum").isEmpty)
    edit(settings, "dbt-version: '1.10.5'\n")
    assertEquals(1, targets(props, "minimum").size)
  }

  def testYamlConstantsRemainWithinTheirScalar(): Unit = {
    root()
    add("models/orders.sql", "select 1")
    val file = add("models/schema.yml", "models:\n- name: orders\n  config:\n    pre_hook: \"{% set name = 'orders' %}{{ ref(name) }}\"\n    post_hook: \"{{ ref(name) }}\"\n")
    assertTrue(targets(file, "name", 2).exists(_.getContainingFile.getName == "orders.sql"))
    assertFalse(targets(file, "name", 3).exists(_.getContainingFile.getName == "orders.sql"))
  }

  def testConfigurationFoldersPackagesSourcesAndNewResourceGates(): Unit = {
    val config = root("models:\n  analytics:\n    marts:\n      orders: {+materialized: table}\n  util:\n    packaged: {}\nsources:\n  analytics:\n    raw:\n      +enabled: false\n      events: {}\nfunctions:\n  analytics:\n    hash: {}\n")
    add("models/marts/orders.sql", "select 1")
    add("dbt_packages/util/dbt_project.yml", "name: util\n")
    val packageModel = add("dbt_packages/util/models/packaged.sql", "select 1")
    add("models/source.yml", "sources:\n- name: raw\n  tables: [{name: events}]\n")
    add("functions/hash.sql", "select 1")
    assertTrue(targets(config, "marts").head.isInstanceOf[com.intellij.psi.PsiDirectory])
    assertEquals(Vector(packageModel), targets(config, "packaged").map(_.getContainingFile))
    val use = add("models/use.sql", "{{ source('raw', 'events') }}")
    assertTrue(targets(use, "raw").isEmpty)
    assertTrue(targets(use, "events").isEmpty)
    assertTrue(targets(config, "hash").isEmpty)
    add(".dbt-navigation.yml", "dbt-version: '1.11'\n")
    assertEquals(1, targets(config, "hash").size)
  }

  def testBuiltinSourceAndDocumentationTargetsFindOnlyLocalUsages(): Unit = {
    root()
    add(".dbt-navigation.yml", "builtin-sources:\n  adapter.get_relation: vendor/adapter.sql\n")
    val source = add("vendor/adapter.sql", "{% macro get_relation(database, schema, identifier) %}{% endmacro %}")
    val use = add("models/use.sql", "{{ adapter.get_relation('db', 's', 't') }} {{ config.require('unique_key') }} {{ dbt_version }}")
    assertEquals(Vector(source), targets(use, "adapter.get_relation").map(_.getContainingFile))
    assertEquals(1, ReferencesSearch.search(targets(use, "adapter.get_relation").head).findAll().size())
    assertEquals(Vector("documentation"), targets(use, "config.require").collect { case e: DbtSymbolElement => e.symbol.kind })
    assertEquals(Vector("documentation"), targets(use, "dbt_version").collect { case e: DbtSymbolElement => e.symbol.kind })
    assertEquals(0, ReferencesSearch.search(targets(use, "dbt_version").head).findAll().size())
  }

  def testFunctionLanguageAdapterOverloadAndCallerGates(): Unit = {
    root()
    val settings = add(".dbt-navigation.yml", "dbt-version: '1.11'\nadapter: snowflake\n")
    val js = add("functions/hash.js", "return input;")
    val sql = add("functions/hash_numeric.sql", "input > 0")
    val props = add("functions/hash.yml", "functions:\n- name: hash\n  config: {language: javascript}\n  overloads:\n  - defined_in: hash_numeric\n")
    val use = add("models/use.sql", "{{ function('hash') }}")
    assertTrue(targets(use, "hash").isEmpty)
    edit(settings, "dbt-version: '1.12'\nadapter: snowflake\n")
    assertEquals(Set(js, sql), targets(use, "hash").map(_.getContainingFile).toSet)
    val hook = add("models/hook.yml", "models:\n- name: use\n  config:\n    post_hook: \"{{ function('hash') }}\"\n")
    assertEquals(3, ReferencesSearch.search(targets(use, "hash").find(_.getContainingFile == js).get).findAll().size())
    edit(settings, "dbt-version: '1.12'\nadapter: postgres\n")
    assertFalse(targets(use, "hash").exists(_.getContainingFile == js))
    assertTrue(hook.isValid && props.isValid)
  }

  def testLineageHonorsScopeEditsCyclesAndCancellation(): Unit = {
    root()
    val orders = add("models/orders.sql", "{{ ref('customers') }}")
    val customers = add("models/customers.sql", "{{ ref('orders') }}")
    val w = DbtWorkspace.get(getProject)
    val edges = DbtLineage.edges(w, Some(GlobalSearchScope.fileScope(getProject, orders.getVirtualFile)))
    assertEquals(1, edges.size)
    assertEquals("orders", edges.head.from.name)
    edit(orders, "{{ ref('missing') }}")
    assertEquals(1, DbtLineage.edges(DbtWorkspace.get(getProject)).size)
    val start = edges.head.from
    val canceled = new java.util.concurrent.atomic.AtomicBoolean(false)
    com.intellij.openapi.application.ApplicationManager.getApplication.executeOnPooledThread(new Runnable {
      override def run(): Unit = {
        val indicator = new com.intellij.openapi.progress.EmptyProgressIndicator
        try com.intellij.openapi.progress.ProgressManager.getInstance.runProcess(new Runnable {
          override def run(): Unit = { indicator.cancel(); DbtLineage.traverse(edges, start, downstream = false, 100); () }
        }, indicator) catch { case _: com.intellij.openapi.progress.ProcessCanceledException => canceled.set(true) }
      }
    }).get(10, java.util.concurrent.TimeUnit.SECONDS)
    assertTrue(canceled.get())
    assertTrue(customers.isValid)
  }

  def testCurrentMetricInputsAliasesSavedQueriesAndTimeSpines(): Unit = {
    root()
    add(".dbt-navigation.yml", "dbt-version: '1.12'\n")
    add("models/orders.sql", "select 1")
    val schema = add("models/semantics.yml", "models:\n- name: orders\n  semantic_model: {name: sales}\n  time_spine: {standard_granularity_column: ordered_at}\n  columns:\n  - name: customer_id\n    entity: {name: customer, type: primary}\n  - name: ordered_at\n    dimension: {type: time}\n  - name: country\n    dimension: {type: categorical}\n  - name: amount\n  metrics:\n  - name: revenue\n    type: simple\n    expr: amount\n  - name: running_revenue\n    type: cumulative\n    input_metric: revenue\n  - name: conversion\n    type: conversion\n    entity: customer\n    base_metric: revenue\n    conversion_metric: running_revenue\nmetrics:\n- name: growth\n  type: derived\n  input_metrics:\n  - name: revenue\n    alias: gross\n  expr: gross + 1\nsaved_queries:\n- name: report\n  query_params:\n    metrics: [growth]\n    group_by: [\"Dimension('customer__country')\"]\n    where: \"TimeDimension('ordered_at', 'DAY') > '2026-01-01'\"\n    order_by: [\"Metric('growth')\"]\n  exports: [{name: daily}]\n")
    assertEquals(1, targets(schema, "ordered_at").size)
    assertEquals(Vector("column"), targets(schema, "amount", 1).collect { case e: DbtSymbolElement => e.symbol.kind })
    assertEquals(Vector("metric"), targets(schema, "revenue", 2).collect { case e: DbtSymbolElement => e.symbol.kind })
    assertEquals(Vector("metric_alias"), targets(schema, "gross", 1).collect { case e: DbtSymbolElement => e.symbol.kind })
    assertEquals(Vector("entity"), targets(schema, "customer", 2).collect { case e: DbtSymbolElement => e.symbol.kind })
    assertEquals(Vector("dimension"), targets(schema, "country", 1).collect { case e: DbtSymbolElement => e.symbol.kind })
    assertEquals(Vector("saved_query"), targets(schema, "daily").collect { case e: DbtSymbolElement => e.symbol.kind })
    val metric = DbtWorkspace.get(getProject).symbols.find(s => s.kind == "metric" && s.name == "revenue").get.element
    assertEquals(3, ReferencesSearch.search(metric).findAll().size())
    val legacy = add("models/legacy.yml", "semantic_models:\n- name: activity\n  entities: [{name: actor, type: primary}]\n  measures: [{name: visits}, {name: purchases}]\nmetrics:\n- name: conversion_legacy\n  type: conversion\n  type_params:\n    entity: actor\n    base_measure: {name: visits}\n    conversion_measure: {name: purchases}\n")
    assertEquals(Vector("measure"), targets(legacy, "visits", 1).collect { case e: DbtSymbolElement => e.symbol.kind })
    assertEquals(Vector("measure"), targets(legacy, "purchases", 1).collect { case e: DbtSymbolElement => e.symbol.kind })
    assertEquals(1, ReferencesSearch.search(targets(legacy, "visits", 1).head).findAll().size())
  }

  def testNumericAdditionAndReassignedWrapperArgumentsStayUnknown(): Unit = {
    root()
    add("models/orders.sql", "select 1")
    add("models/12.sql", "select 1")
    add("macros/wrapper.sql", "{% macro lookup(name) %}{% set name = env_var('MODEL') %}{{ builtins.ref(name) }}{% endmacro %}")
    val file = add("models/use.sql", "{{ ref(1 + 2) }}{{ lookup('orders') }}")
    assertTrue(targets(file, "orders").isEmpty)
    assertFalse(DbtNavigation.inFile(file).flatMap(_.candidates).exists(_.getContainingFile.getName == "12.sql"))
  }

  def testTransitivePackageMacroVariablesUseTheirOwnCallingNode(): Unit = {
    root("vars:\n  analytics: {color: root_node}\n  util: {color: package_node}\n")
    add("dbt_packages/util/dbt_project.yml", "name: util\n")
    val macros = add("dbt_packages/util/macros/colors.sql", "{% macro root_color() %}{{ var('color') }}{% endmacro %}{% macro package_color() %}{{ var('color') }}{% endmacro %}{% macro wrapper() %}{{ util.root_color() }}{% endmacro %}")
    add("models/use.sql", "{{ util.wrapper() }}")
    add("dbt_packages/util/models/use.sql", "{{ util.package_color() }}")
    val rootColor = targets(macros, "'color'").head.asInstanceOf[org.jetbrains.yaml.psi.YAMLKeyValue]
    val packageColor = targets(macros, "'color'", 1).head.asInstanceOf[org.jetbrains.yaml.psi.YAMLKeyValue]
    assertEquals("root_node", rootColor.getValueText)
    assertEquals("package_node", packageColor.getValueText)
  }

  def testStaticEvidenceInvalidatesAfterConstantAndWrapperEdits(): Unit = {
    root()
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
    assertTrue(targets(use, "'orders'").isEmpty)
  }

  def testChecksIgnorePathsUnsupportedVersionsAndIncompleteQueries(): Unit = {
    root("check-paths: [metadata_checks]\ninfo_schema: {version: 1}\n")
    val settings = add(".dbt-navigation.yml", "engine: v2\n")
    add("models/orders.sql", "select 1")
    val check = add("metadata_checks/partial.sql", "select name from {{ info_schema('models') }} where name =")
    assertEquals(1, targets(check, "models").size)
    add(".dbtignore", "metadata_checks/ignored.sql\n")
    val ignored = add("metadata_checks/ignored.sql", "select name from {{ info_schema('models') }}")
    assertTrue(DbtNavigation.inFile(ignored).isEmpty)
    edit(getProjectFile("dbt_project.yml"), "name: analytics\ncheck-paths: [metadata_checks]\ninfo_schema: {version: 999}\n")
    assertTrue(targets(check, "models").isEmpty)
    edit(settings, "engine: core\ndbt-version: '1.12'\n")
    assertTrue(DbtNavigation.inFile(check).isEmpty)
  }

  def testFindUsagesFromPropertiesKeepsTheResourceAndVersionIdentity(): Unit = {
    root()
    add("models/orders_v1.sql", "select 1")
    add("models/orders_v2.sql", "select 2")
    val properties = add("models/orders.yml", "models:\n- name: orders\n  latest_version: 2\n  versions:\n  - v: 1\n  - v: 2\n")
    val use = add("models/use.sql", "{{ ref('orders', v=1) }}{{ ref('orders', v=2) }}")
    val symbols = DbtWorkspace.get(getProject).symbols.filter(s => s.file == properties && s.kind == "model")
    symbols.foreach { symbol =>
      val usages = ReferencesSearch.search(symbol.element, GlobalSearchScope.fileScope(getProject, use.getVirtualFile)).findAll()
      assertEquals(1, usages.size())
      val target = usages.iterator().next().resolve().asInstanceOf[DbtSymbolElement]
      assertEquals(symbol.version, target.symbol.version)
    }
    val missing = add("models/missing.yml", "models:\n- name: missing\n")
    assertEquals(0, ReferencesSearch.search(DbtWorkspace.get(getProject).symbols.find(s => s.file == missing).get.element).findAll().size())
  }

  def testTupleAssignmentsAndCaptureBodiesRetainLexicalScope(): Unit = {
    root()
    add("models/orders.sql", "select 1")
    val file = add("models/use.sql", "{% set name = 'orders' %}{% set first, name = unknown %}{{ name }}{{ ref(name) }}{% set label = 'outer' %}{% set label %}{{ label }}{% endset %}{{ label }}")
    assertEquals(file.getText.indexOf("name", file.getText.indexOf("name") + 1), targets(file, "name", 2).head.getTextOffset)
    assertFalse(targets(file, "name", 3).exists(_.getContainingFile.getName == "orders.sql"))
    val outer = targets(file, "label", 2).head
    val captured = targets(file, "label", 3).head
    assertEquals(file.getText.indexOf("label"), outer.getTextOffset)
    assertEquals(file.getText.indexOf("label", outer.getTextOffset + 1), captured.getTextOffset)
  }

  def testCatalogColumnsRespectProjectAndInstalledPackageOwners(): Unit = {
    root()
    add(".dbt-navigation.yml", "catalog: target/catalog.json\n")
    add("models/orders.sql", "select 1")
    root(path = "other/")
    add("other/models/orders.sql", "select 2")
    add("other/models/schema.yml", "models:\n- name: orders\n  columns: [{name: total}]\n")
    add("dbt_packages/util/dbt_project.yml", "name: util\n")
    add("dbt_packages/util/models/orders.sql", "select 3")
    add("target/catalog.json", """{"metadata":{"dbt_schema_version":"https://schemas.getdbt.com/dbt/catalog/v1.json","generated_at":"2099-01-01T00:00:00Z"},"nodes":{"model.analytics.orders":{"unique_id":"model.analytics.orders","columns":{"total":{"name":"total"}}},"model.util.orders":{"unique_id":"model.util.orders","columns":{"package_total":{"name":"package_total"}}}}}""")
    val use = add("models/use.sql", "select a.total, p.package_total from {{ ref('orders') }} a join {{ ref('util', 'orders') }} p on true")
    assertEquals(Vector("target/catalog.json"), targets(use, "total").map(e => DbtWorkspace.get(getProject).root(use).get.relative(e.getContainingFile.getVirtualFile).get))
    assertEquals(1, targets(use, "package_total").size)
    assertTrue(targets(use, "package_total").head.asInstanceOf[DbtSymbolElement].symbol.metadata)
  }

  def testSemanticJoinCandidatesStayAmbiguousAndUpdateOnEdits(): Unit = {
    root()
    val schema = add("models/semantic.yml", "semantic_models:\n- name: sales\n  entities: [{name: customer, type: primary}]\n  dimensions: [{name: country}]\n- name: customers\n  entities: [{name: customer, type: primary}]\n  dimensions: [{name: country}]\n")
    val use = add("models/use.sql", "{{ Dimension('customer__country') }}{{ Dimension('missing__country') }}")
    val ref = DbtNavigation.at(use, use.getText.indexOf("country") + 1).head
    assertEquals(2, ref.candidates.size)
    assertNull(ref.resolve())
    assertTrue(targets(use, "missing__country").isEmpty)
    edit(schema, schema.getText.replace("name: sales\n  entities: [{name: customer, type: primary}]", "name: sales\n  entities: [{name: customer, type: foreign}]"))
    assertEquals(1, targets(use, "country").size)
  }

  def testSelectorsKeepDuplicateNamesAnchorsAndShorthandConservative(): Unit = {
    root("models:\n  analytics:\n    +tags: [finance]\n")
    val orders = add("models/orders.sql", "select 1")
    val selectors = add("selectors.yml", "selectors:\n- name: nightly\n  definition: &finance {tag: finance}\n- name: nightly\n  definition: 'fqn:missing'\n- name: use\n  definition:\n    union:\n    - selector: nightly\n    - *finance\n")
    assertEquals(Vector(orders), targets(selectors, "finance", 1).map(_.getContainingFile))
    val named = DbtNavigation.at(selectors, selectors.getText.lastIndexOf("nightly") + 1).head
    assertEquals(2, named.candidates.size)
    assertNull(named.resolve())
    assertTrue(targets(selectors, "*finance").isEmpty)
  }

  def testLineageKeepsSnapshotAndGenericTestBlockOwners(): Unit = {
    root()
    add("models/orders.sql", "select 1")
    add("models/customers.sql", "select 2")
    add("snapshots/history.sql", "{% snapshot first %}{{ ref('orders') }}{% endsnapshot %}{% snapshot second %}{{ ref('customers') }}{% endsnapshot %}{% snapshot off %}{{ config(enabled=false) }}select 3{% endsnapshot %}")
    add("macros/tests.sql", "{% test compares(model) %}{{ ref('orders') }}{% endtest %}")
    val use = add("models/use.sql", "{{ ref('off') }}")
    val w = DbtWorkspace.get(getProject)
    val edges = DbtLineage.edges(w)
    assertTrue(edges.exists(e => e.from.name == "first" && e.to.name == "orders"))
    assertTrue(edges.exists(e => e.from.name == "second" && e.to.name == "customers"))
    assertFalse(edges.exists(e => e.from.name == "first" && e.to.name == "customers"))
    assertTrue(edges.exists(e => e.from.kind == "generic_test" && e.from.name == "compares" && e.to.name == "orders"))
    assertTrue(targets(use, "off").isEmpty)
  }

  def testDeferredHookVarsAndEnabledSourceTableOverrides(): Unit = {
    val config = root("vars: {start_date: today}\non-run-start: \"{{ var('start_' ~ 'date') }}\"\nsources:\n  analytics:\n    raw:\n      +enabled: false\n")
    val schema = add("models/source.yml", "sources:\n- name: raw\n  tables:\n  - name: off\n  - name: events\n    config: {enabled: true}\n")
    val use = add("models/use.sql", "{{ source('raw', 'events') }}{{ source('raw', 'off') }}")
    assertEquals(Vector(schema), targets(use, "events").map(_.getContainingFile))
    assertTrue(targets(use, "off").isEmpty)
    assertTrue(DbtNavigation.inFile(config).exists(r => r.label == "static reference" && r.candidates.nonEmpty))
    val profile = add("profiles.yml", "analytics:\n  outputs:\n    dev:\n      schema: \"{{ var('start_' ~ 'date') }}\"\n")
    assertTrue(DbtNavigation.inFile(profile).filter(_.label == "static reference").forall(_.candidates.isEmpty))
  }

  def testNativeEnvironmentFindUsagesTargetsOnlyTheKey(): Unit = {
    root()
    val env = add(".env", "DBT_ENV_SECRET_TOKEN=secret-value\n")
    val use = add("models/use.sql", "{{ env_var('DBT_ENV_SECRET_TOKEN') }}")
    myFixture.configureFromExistingVirtualFile(env.getVirtualFile)
    myFixture.getEditor.getCaretModel.moveToOffset(1)
    val flags = com.intellij.codeInsight.TargetElementUtil.ELEMENT_NAME_ACCEPTED | com.intellij.codeInsight.TargetElementUtil.REFERENCED_ELEMENT_ACCEPTED
    val declaration = com.intellij.codeInsight.TargetElementUtil.findTargetElement(myFixture.getEditor, flags)
    assertTrue(declaration.isInstanceOf[DbtSymbolElement])
    assertEquals("DBT_ENV_SECRET_TOKEN", declaration.getText)
    assertEquals(1, myFixture.findUsages(declaration).size())
    assertEquals(Vector(declaration), targets(use, "DBT_ENV_SECRET_TOKEN"))
  }
}
