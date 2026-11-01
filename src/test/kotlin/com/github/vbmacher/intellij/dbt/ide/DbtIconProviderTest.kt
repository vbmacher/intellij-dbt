package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project.DbtIcons
import com.github.vbmacher.intellij.dbt.project.DbtWorkspace
import com.intellij.ide.IconProvider
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.IconLoader
import com.intellij.psi.PsiFile
import com.intellij.ui.icons.CompositeIcon
import com.intellij.util.IconUtil
import org.junit.Assert.*

import java.awt.GraphicsEnvironment
import java.awt.image.BufferedImage
import javax.swing.Icon

class DbtIconProviderTest : DbtTestCase() {
    private fun icon(file: PsiFile): Icon? = IconProvider.EXTENSION_POINT_NAME.extensionList
        .filterIsInstance<DbtIconProvider>().firstOrNull()?.getIcon(file, 0)
        ?: if (IconProvider.EXTENSION_POINT_NAME.extensionList.none { it is DbtIconProvider }) throw AssertionError("dbt file icon provider is not registered") else null

    fun testBrandedIconsRenderInLightAndDarkThemes() {
        // Headless IntelliJ returns placeholder icons until rendering is explicitly enabled.
        if (GraphicsEnvironment.isHeadless()) {
            IconLoader.activate()
            Disposer.register(getTestRootDisposable(), com.intellij.openapi.Disposable { IconLoader.deactivate() })
        }
        val icons = listOf(
            DbtIcons.Table, DbtIcons.Seed, DbtIcons.Snapshot, DbtIcons.Query, DbtIcons.Macro,
            DbtIcons.Jinja, DbtIcons.Function, DbtIcons.Test, DbtIcons.Check, DbtIcons.Doc, DbtIcons.SemanticModel,
            DbtIcons.Metric, DbtIcons.Alias, DbtIcons.Dimension, DbtIcons.Entity, DbtIcons.Column, DbtIcons.Export,
            DbtIcons.Group, DbtIcons.Package, DbtIcons.Variable, DbtIcons.Parameter, DbtIcons.Config,
            DbtIcons.Property, DbtIcons.Result, DbtIcons.Selector, DbtIcons.Materialization, DbtIcons.Gutter
        )

        fun orange(pixel: Int): Boolean {
            val red = (pixel ushr 16) and 255
            val green = (pixel ushr 8) and 255
            return red > green && green > (pixel and 255)
        }

        icons.forEach { icon ->
            val rendered = listOf(false, true).map { dark ->
                val themed = IconLoader.getDarkIcon(icon, dark)
                assertEquals(16, themed.getIconWidth())
                assertEquals(16, themed.getIconHeight())
                val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
                val graphics = image.createGraphics()
                try {
                    themed.paintIcon(null, graphics, 0, 0)
                } finally {
                    graphics.dispose()
                }
                val pixels = (0 until 16).flatMap { y -> (0 until 16).map { x -> image.getRGB(x, y) } }
                assertTrue("The translucent orange dbt mark must render", pixels.any { pixel ->
                    val alpha = pixel ushr 24
                    alpha > 0 && alpha < 255 && orange(pixel)
                })
                val foreground = (7 until 16).flatMap { y -> (7 until 16).map { x -> image.getRGB(x, y) } }
                assertTrue(
                    "The opaque symbol must contrast with the orange mark: $themed",
                    foreground.any { pixel -> (pixel ushr 24) == 255 && !orange(pixel) })
                if (icon == DbtIcons.Snapshot) {
                    assertEquals(
                        "The camera silhouette must have a tinted interior",
                        if (dark) 0xff25324d.toInt() else 0xffe7effd.toInt(), image.getRGB(8, 12)
                    )
                    assertEquals("The area outside the camera must remain transparent", 0, image.getRGB(15, 7) ushr 24)
                }
                pixels
            }
            assertNotEquals("Light and dark symbols must use theme colors: $icon", rendered[0], rendered[1])
        }
    }

    fun testProjectFilesUseModelMacroAndUnitTestIcons() {
        project()
        val model = add("models/wms/marts/final_packaging/wms_vpgm.sql", "select 1 as id")
        val python = add("models/wms/model.py", "def model(dbt, session): return session")
        val macroFile = add("macros/common/ab_test.sql", "{% macro ab_test(model) %}{{ model }}{% endmacro %}")
        val unit = add(
            "tests/wms/unit/packaging/test_dataset_partitions.yml",
            "version: 2\nunit_tests:\n- name: partitions\n  model: wms_vpgm\n  given: []\n  expect:\n    rows: []\n"
        )
        assertSame(DbtIcons.Table, icon(model))
        assertSame(DbtIcons.Table, icon(python))
        assertSame(DbtIcons.Macro, icon(macroFile))
        assertSame(DbtIcons.Test, icon(unit))
    }

    fun testIconsReachIdeFilePresentation() {
        project()
        val model = add("models/orders.sql", "select 1")
        val macroFile = add("macros/helper.sql", "{% macro helper() %}{% endmacro %}")
        val unit = add("tests/example.yml", "unit_tests:\n- name: example\n  model: orders\n")
        val fixture = add("tests/fixtures/orders.csv", "id\n1")
        val abTest =
            add("tests/wms/ab_tests/final_packaging.yml", "models:\n- name: orders\n  data_tests:\n  - ab_test\n")

        fun baseIcon(icon: Icon): Icon = if (icon is CompositeIcon) baseIcon(icon.getIcon(0)!!) else icon

        listOf(
            model to DbtIcons.Table, macroFile to DbtIcons.Macro, unit to DbtIcons.Test,
            fixture to DbtIcons.Test, abTest to DbtIcons.Test
        )
            .forEach { (file, expected) ->
                assertSame(
                    file.getName(),
                    expected,
                    baseIcon(IconUtil.computeFileIcon(file.getVirtualFile(), 0, getProject())!!)
                )
            }
    }

    fun testConfiguredPathsAndDeclarationsDistinguishSqlResources() {
        project("model-paths: [transform]\nmacro-paths: [helpers]\ntest-paths: [qa]\n")
        val model = add("transform/orders.sql", "select 1")
        val macroFile = add("helpers/format.sql", "{% macro format(value) %}{{ value }}{% endmacro %}")
        val generic = add("helpers/valid.sql", "{% test valid(model) %}select * from {{ model }}{% endtest %}")
        val materialization = add("helpers/custom.sql", "{% materialization custom, default %}{% endmaterialization %}")
        val singular = add("qa/unit/check_orders.sql", "select 1")
        val fixture = add("qa/fixtures/orders.sql", "select 1 as id")
        assertSame(DbtIcons.Table, icon(model))
        assertSame(DbtIcons.Macro, icon(macroFile))
        assertSame(DbtIcons.Test, icon(generic))
        assertSame(DbtIcons.Materialization, icon(materialization))
        assertSame(DbtIcons.Test, icon(singular))
        assertSame(DbtIcons.Test, icon(fixture))
        assertSame(DbtIcons.Test, icon(add("qa/fixtures/orders.csv", "id\n1")))
        assertSame(DbtIcons.Query, icon(add("analyses/report.sql", "select 1")))
        assertSame(DbtIcons.Seed, icon(add("seeds/data.csv", "id\n1")))
        assertSame(DbtIcons.Snapshot, icon(add("snapshots/orders.sql", "select 1")))
    }

    fun testPropertyFilesUseTopLevelDeclarationsAndMixedFilesKeepYamlIcon() {
        project()
        val properties = add("models/schema.yml", "models:\n- name: orders\n  columns:\n  - name: id\n")
        val sources = add("models/sources.yml", "sources:\n- name: raw\n  tables:\n  - name: orders\n")
        val mixed = add("models/mixed.yml", "models:\n- name: orders\nunit_tests:\n- name: example\n  model: orders\n")
        assertSame(DbtIcons.Table, icon(properties))
        assertSame(DbtIcons.Table, icon(sources))
        assertNull(icon(mixed))
        assertNull(icon(add("models/empty.yml", "version: 2\n")))
    }

    fun testAttachedDataTestsUseTestIconWithoutChangingModelDeclarations() {
        project()
        val abTest = add(
            "tests/wms/ab_tests/final_packaging.yml",
            "version: 2\nmodels:\n- name: wms_vpgm\n  data_tests:\n  - ab_test:\n" +
                    "      arguments:\n        a_source: \"{{ source('legacy', 'vpgm') }}\"\n" +
                    "        b_source: \"{{ ref('wms_vpgm') }}\"\n      config: {tags: [wms_ab]}\n"
        )
        assertSame(DbtIcons.Test, icon(abTest))
        val models = DbtWorkspace.declarationsInFile(abTest).filter { it.kind == "model" }
        assertFalse(models.isEmpty())
        models.forEach { s -> assertSame(DbtIcons.Table, s.element.getIcon(0)) }
        assertSame(
            DbtIcons.Test, icon(
                add(
                    "models/legacy_tests.yml",
                    "models:\n- name: orders\n  tests:\n  - not_null\n"
                )
            )
        )
        assertSame(
            DbtIcons.Test, icon(
                add(
                    "models/column_tests.yml",
                    "models:\n- name: orders\n  columns:\n  - name: id\n    data_tests:\n    - not_null\n"
                )
            )
        )
    }

    fun testModelMetadataAndEmptyTestsDoNotBecomeTestFiles() {
        project()
        assertSame(
            DbtIcons.Table, icon(
                add(
                    "models/metadata.yml",
                    "models:\n- name: orders\n  description: Order data\n  data_tests:\n  - ab_test\n"
                )
            )
        )
        assertSame(
            DbtIcons.Table, icon(
                add(
                    "models/empty_tests.yml",
                    "models:\n- name: orders\n  data_tests: []\n"
                )
            )
        )
        assertSame(
            DbtIcons.Table, icon(
                add(
                    "models/column_metadata.yml",
                    "models:\n- name: orders\n  columns:\n  - name: id\n    description: Identifier\n    tests:\n    - not_null\n"
                )
            )
        )
        assertNull(
            icon(
                add(
                    "tests/mixed_properties.yml",
                    "models:\n- name: orders\n  data_tests:\n  - ab_test\n- name: customers\n  description: Customer data\n"
                )
            )
        )
    }

    fun testUnrelatedIgnoredAndGeneratedFilesKeepTheirFileTypeIcons() {
        project()
        add(".dbtignore", "models/ignored.sql\n")
        assertNull(icon(add("models/ignored.sql", "select 1")))
        assertNull(icon(add("target/compiled/models/orders.sql", "select 1")))
        assertNull(icon(add("scripts/query.sql", "{% macro helper() %}{% endmacro %}")))
        assertNull(icon(add("tests/unit/readme.yml", "notes: unit test directory\n")))
    }

    fun testConfigurationAndYamlEditsUpdateIcons() {
        val config = project()
        val file = add("models/orders.sql", "select 1")
        assertSame(DbtIcons.Table, icon(file))
        edit(config, "name: analytics\nmodel-paths: [transform]\nanalysis-paths: [models]\n")
        assertSame(DbtIcons.Query, icon(file))
        val yaml = add("tests/example.yml", "unit_tests:\n- name: example\n  model: orders\n")
        assertSame(DbtIcons.Test, icon(yaml))
        edit(yaml, "models:\n- name: orders\n")
        assertSame(DbtIcons.Table, icon(yaml))
    }

    fun testNestedProjectsUseTheirOwnPathsAndNonDbtFilesKeepTheirIcons() {
        val unrelated = add("plain/query.sql", "select 1")
        assertNull(icon(unrelated))
        project()
        add("models/nested/dbt_project.yml", "name: nested\nmodel-paths: [transform]\nanalysis-paths: [reports]\n")
        assertNull(icon(add("models/nested/other.sql", "select 1")))
        assertSame(DbtIcons.Table, icon(add("models/nested/transform/orders.sql", "select 1")))
        assertSame(DbtIcons.Query, icon(add("models/nested/reports/orders.sql", "select 1")))
    }
}
