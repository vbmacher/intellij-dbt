package com.github.vbmacher.intellij.dbt

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.{ClassFileImporter, ImportOption, Location}
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.Architectures.layeredArchitecture
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import org.junit.Test

/**
 * Dependency rules of the feature packages:
 *
 *  - `syntax`: YAML, Jinja and Python source access shared by every feature.
 *  - `project`: the dbt domain (projects, declarations, configuration) and the declaration source port.
 *  - `navigation`: reference resolution and the reference provider port; its subpackages are the language adapters.
 *  - `lineage`: dependencies, node selection and the dependency source port.
 *  - `metadata`: artifact adapters implementing the declaration and dependency source ports.
 *  - `ide`: IntelliJ extensions and actions driving the features.
 *
 * Adapters are wired only through the extension points of `plugin.xml`, so nothing may depend on them.
 */
final class DbtArchitectureTest {
  private val Root = "com.github.vbmacher.intellij.dbt"

  /** The plugin classes; sbt compiles tests to `test-classes`, which ArchUnit's predefined options do not recognize. */
  private lazy val classes: JavaClasses = new ClassFileImporter().withImportOption(new ImportOption {
    override def includes(location: Location): Boolean = !location.contains("/test-classes/")
  }).importPackages(Root)

  @Test def featuresAreLayered(): Unit = layeredArchitecture().consideringOnlyDependenciesInLayers()
    .layer("syntax").definedBy(s"$Root.syntax..")
    .layer("project").definedBy(s"$Root.project..")
    .layer("navigation").definedBy(s"$Root.navigation")
    .layer("languages").definedBy(s"$Root.navigation.*..")
    .layer("lineage").definedBy(s"$Root.lineage..")
    .layer("metadata").definedBy(s"$Root.metadata..")
    .layer("ide").definedBy(s"$Root.ide..")
    .whereLayer("ide").mayNotBeAccessedByAnyLayer()
    .whereLayer("metadata").mayNotBeAccessedByAnyLayer()
    .whereLayer("languages").mayNotBeAccessedByAnyLayer()
    .whereLayer("lineage").mayOnlyBeAccessedByLayers("metadata", "ide")
    .whereLayer("navigation").mayOnlyBeAccessedByLayers("languages", "lineage", "metadata", "ide")
    .whereLayer("project").mayOnlyBeAccessedByLayers("navigation", "languages", "lineage", "metadata", "ide")
    .whereLayer("syntax").mayOnlyBeAccessedByLayers("project", "navigation", "languages", "lineage", "metadata", "ide")
    .check(classes)

  @Test def featuresAreFreeOfCycles(): Unit = slices().matching(s"$Root.(**)").should().beFreeOfCycles().check(classes)

  @Test def languagesAreIndependent(): Unit =
    slices().matching(s"$Root.navigation.(*)..").should().notDependOnEachOther().check(classes)

  private def confined(packages: Seq[String], libraries: String*): Unit =
    noClasses().that().resideOutsideOfPackages(packages: _*).should().dependOnClassesThat().resideInAnyPackage(libraries: _*)
      .check(classes)

  @Test def jinjaParserIsConfinedToSyntax(): Unit = confined(Seq(s"$Root.syntax.."), "com.hubspot.jinjava..", "jinjava..")

  @Test def sqlParserIsConfinedToSql(): Unit = confined(Seq(s"$Root.navigation.sql.."), "net.sf.jsqlparser..")

  @Test def artifactReadersAreConfinedToMetadata(): Unit =
    confined(Seq(s"$Root.metadata.."), "org.apache.parquet..", "org.apache.hadoop..", "com.fasterxml.jackson..", "com.intellij.json..")

  @Test def userInterfaceIsConfinedToIde(): Unit =
    confined(Seq(s"$Root.ide.."), "com.intellij.openapi.actionSystem..", "com.intellij.openapi.ui..", "com.intellij.ui..")
}
