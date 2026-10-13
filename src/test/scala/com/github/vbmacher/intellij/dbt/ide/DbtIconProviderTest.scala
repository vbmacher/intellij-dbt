package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project.DbtWorkspace
import com.intellij.icons.AllIcons
import com.intellij.ide.IconProvider
import com.intellij.psi.PsiFile
import com.intellij.ui.icons.CompositeIcon
import com.intellij.util.IconUtil
import org.junit.Assert._

import javax.swing.Icon
import scala.jdk.CollectionConverters._

final class DbtIconProviderTest extends DbtCodeInsightTestCase {
  private def icon(file: PsiFile): Icon = IconProvider.EXTENSION_POINT_NAME.getExtensionList.asScala
    .collectFirst { case provider: DbtIconProvider => provider.getIcon(file, 0) }
    .getOrElse(throw new AssertionError("dbt file icon provider is not registered"))

  def testProjectFilesUseModelMacroAndUnitTestIcons(): Unit = {
    project()
    val model = add("models/wms/marts/final_packaging/wms_vpgm.sql", "select 1 as id")
    val python = add("models/wms/model.py", "def model(dbt, session): return session")
    val macroFile = add("macros/common/ab_test.sql", "{% macro ab_test(model) %}{{ model }}{% endmacro %}")
    val unit = add("tests/wms/unit/packaging/test_dataset_partitions.yml",
      "version: 2\nunit_tests:\n- name: partitions\n  model: wms_vpgm\n  given: []\n  expect:\n    rows: []\n")
    assertSame(AllIcons.Nodes.DataSchema, icon(model))
    assertSame(AllIcons.Nodes.DataSchema, icon(python))
    assertSame(AllIcons.Nodes.Method, icon(macroFile))
    assertSame(AllIcons.Nodes.Test, icon(unit))
  }

  def testIconsReachIdeFilePresentation(): Unit = {
    project()
    val model = add("models/orders.sql", "select 1")
    val macroFile = add("macros/helper.sql", "{% macro helper() %}{% endmacro %}")
    val unit = add("tests/example.yml", "unit_tests:\n- name: example\n  model: orders\n")
    val fixture = add("tests/fixtures/orders.csv", "id\n1")
    val abTest = add("tests/wms/ab_tests/final_packaging.yml", "models:\n- name: orders\n  data_tests:\n  - ab_test\n")
    def baseIcon(icon: Icon): Icon = icon match {
      case composite: CompositeIcon => baseIcon(composite.getIcon(0))
      case _ => icon
    }
    Vector(model -> AllIcons.Nodes.DataSchema, macroFile -> AllIcons.Nodes.Method, unit -> AllIcons.Nodes.Test,
      fixture -> AllIcons.Nodes.Test, abTest -> AllIcons.Nodes.Test)
      .foreach { case (file, expected) =>
        assertSame(file.getName, expected, baseIcon(IconUtil.computeFileIcon(file.getVirtualFile, 0, getProject)))
      }
  }

  def testConfiguredPathsAndDeclarationsDistinguishSqlResources(): Unit = {
    project("model-paths: [transform]\nmacro-paths: [helpers]\ntest-paths: [qa]\n")
    val model = add("transform/orders.sql", "select 1")
    val macroFile = add("helpers/format.sql", "{% macro format(value) %}{{ value }}{% endmacro %}")
    val generic = add("helpers/valid.sql", "{% test valid(model) %}select * from {{ model }}{% endtest %}")
    val materialization = add("helpers/custom.sql", "{% materialization custom, default %}{% endmaterialization %}")
    val singular = add("qa/unit/check_orders.sql", "select 1")
    val fixture = add("qa/fixtures/orders.sql", "select 1 as id")
    assertSame(AllIcons.Nodes.DataSchema, icon(model))
    assertSame(AllIcons.Nodes.Method, icon(macroFile))
    assertSame(AllIcons.Nodes.Test, icon(generic))
    assertSame(AllIcons.Nodes.Artifact, icon(materialization))
    assertSame(AllIcons.Nodes.Test, icon(singular))
    assertSame(AllIcons.Nodes.Test, icon(fixture))
    assertSame(AllIcons.Nodes.Test, icon(add("qa/fixtures/orders.csv", "id\n1")))
    assertSame(AllIcons.FileTypes.Sql, icon(add("analyses/report.sql", "select 1")))
    assertSame(AllIcons.FileTypes.Csv, icon(add("seeds/data.csv", "id\n1")))
    assertSame(AllIcons.General.History, icon(add("snapshots/orders.sql", "select 1")))
  }

  def testPropertyFilesUseTopLevelDeclarationsAndMixedFilesKeepYamlIcon(): Unit = {
    project()
    val properties = add("models/schema.yml", "models:\n- name: orders\n  columns:\n  - name: id\n")
    val sources = add("models/sources.yml", "sources:\n- name: raw\n  tables:\n  - name: orders\n")
    val mixed = add("models/mixed.yml", "models:\n- name: orders\nunit_tests:\n- name: example\n  model: orders\n")
    assertSame(AllIcons.Nodes.DataSchema, icon(properties))
    assertSame(AllIcons.Nodes.DataSchema, icon(sources))
    assertNull(icon(mixed))
    assertNull(icon(add("models/empty.yml", "version: 2\n")))
  }

  def testAttachedDataTestsUseTestIconWithoutChangingModelDeclarations(): Unit = {
    project()
    val abTest = add("tests/wms/ab_tests/final_packaging.yml",
      "version: 2\nmodels:\n- name: wms_vpgm\n  data_tests:\n  - ab_test:\n" +
        "      arguments:\n        a_source: \"{{ source('legacy', 'vpgm') }}\"\n" +
        "        b_source: \"{{ ref('wms_vpgm') }}\"\n      config: {tags: [wms_ab]}\n")
    assertSame(AllIcons.Nodes.Test, icon(abTest))
    val models = DbtWorkspace.declarationsInFile(abTest).filter(_.kind == "model")
    assertFalse(models.isEmpty)
    models.foreach(s => assertSame(AllIcons.Nodes.DataSchema, s.element.getIcon(0)))
    assertSame(AllIcons.Nodes.Test, icon(add("models/legacy_tests.yml",
      "models:\n- name: orders\n  tests:\n  - not_null\n")))
    assertSame(AllIcons.Nodes.Test, icon(add("models/column_tests.yml",
      "models:\n- name: orders\n  columns:\n  - name: id\n    data_tests:\n    - not_null\n")))
  }

  def testModelMetadataAndEmptyTestsDoNotBecomeTestFiles(): Unit = {
    project()
    assertSame(AllIcons.Nodes.DataSchema, icon(add("models/metadata.yml",
      "models:\n- name: orders\n  description: Order data\n  data_tests:\n  - ab_test\n")))
    assertSame(AllIcons.Nodes.DataSchema, icon(add("models/empty_tests.yml",
      "models:\n- name: orders\n  data_tests: []\n")))
    assertSame(AllIcons.Nodes.DataSchema, icon(add("models/column_metadata.yml",
      "models:\n- name: orders\n  columns:\n  - name: id\n    description: Identifier\n    tests:\n    - not_null\n")))
    assertNull(icon(add("tests/mixed_properties.yml",
      "models:\n- name: orders\n  data_tests:\n  - ab_test\n- name: customers\n  description: Customer data\n")))
  }

  def testUnrelatedIgnoredAndGeneratedFilesKeepTheirFileTypeIcons(): Unit = {
    project()
    add(".dbtignore", "models/ignored.sql\n")
    assertNull(icon(add("models/ignored.sql", "select 1")))
    assertNull(icon(add("target/compiled/models/orders.sql", "select 1")))
    assertNull(icon(add("scripts/query.sql", "{% macro helper() %}{% endmacro %}")))
    assertNull(icon(add("tests/unit/readme.yml", "notes: unit test directory\n")))
  }

  def testConfigurationAndYamlEditsUpdateIcons(): Unit = {
    val config = project()
    val file = add("models/orders.sql", "select 1")
    assertSame(AllIcons.Nodes.DataSchema, icon(file))
    edit(config, "name: analytics\nmodel-paths: [transform]\nanalysis-paths: [models]\n")
    assertSame(AllIcons.FileTypes.Sql, icon(file))
    val yaml = add("tests/example.yml", "unit_tests:\n- name: example\n  model: orders\n")
    assertSame(AllIcons.Nodes.Test, icon(yaml))
    edit(yaml, "models:\n- name: orders\n")
    assertSame(AllIcons.Nodes.DataSchema, icon(yaml))
  }

  def testNestedProjectsUseTheirOwnPathsAndNonDbtFilesKeepTheirIcons(): Unit = {
    val unrelated = add("plain/query.sql", "select 1")
    assertNull(icon(unrelated))
    project()
    add("models/nested/dbt_project.yml", "name: nested\nmodel-paths: [transform]\nanalysis-paths: [reports]\n")
    assertNull(icon(add("models/nested/other.sql", "select 1")))
    assertSame(AllIcons.Nodes.DataSchema, icon(add("models/nested/transform/orders.sql", "select 1")))
    assertSame(AllIcons.FileTypes.Sql, icon(add("models/nested/reports/orders.sql", "select 1")))
  }
}
