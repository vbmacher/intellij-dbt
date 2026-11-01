package com.github.vbmacher.intellij.dbt

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.Architectures.layeredArchitecture
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import org.junit.Test

class DbtArchitectureTest {
    private val Root = "com.github.vbmacher.intellij.dbt"
    private val classes: JavaClasses by lazy {
        ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
            .withImportOption { location -> !location.contains("/instrumentTestCode/") }
            .importPackages(Root)
    }

    @Test
    fun featuresAreLayered() {
        layeredArchitecture().consideringOnlyDependenciesInLayers()
            .layer("syntax").definedBy("$Root.syntax..")
            .layer("project").definedBy("$Root.project..")
            .layer("navigation").definedBy("$Root.navigation")
            .layer("languages").definedBy("$Root.navigation.*..")
            .layer("lineage").definedBy("$Root.lineage..")
            .layer("metadata").definedBy("$Root.metadata..")
            .layer("ide").definedBy("$Root.ide..")
            .whereLayer("ide").mayNotBeAccessedByAnyLayer()
            .whereLayer("metadata").mayNotBeAccessedByAnyLayer()
            .whereLayer("languages").mayNotBeAccessedByAnyLayer()
            .whereLayer("lineage").mayOnlyBeAccessedByLayers("metadata", "ide")
            .whereLayer("navigation").mayOnlyBeAccessedByLayers("languages", "lineage", "metadata", "ide")
            .whereLayer("project").mayOnlyBeAccessedByLayers("navigation", "languages", "lineage", "metadata", "ide")
            .whereLayer("syntax")
            .mayOnlyBeAccessedByLayers("project", "navigation", "languages", "lineage", "metadata", "ide")
            .check(classes)
    }

    @Test
    fun featuresAreFreeOfCycles() {
        slices().matching("$Root.(**)").should().beFreeOfCycles().check(classes)
    }

    @Test
    fun languagesAreIndependent() {
        slices().matching("$Root.navigation.(*)..").should().notDependOnEachOther().check(classes)
    }

    private fun confined(packages: List<String>, vararg libraries: String) {
        noClasses().that().resideOutsideOfPackages(*packages.toTypedArray()).should().dependOnClassesThat()
            .resideInAnyPackage(*libraries).check(classes)
    }

    @Test
    fun jinjaParserIsConfinedToSyntax() {
        confined(listOf("$Root.syntax.."), "com.hubspot.jinjava..", "jinjava..")
    }

    @Test
    fun sqlParserIsConfinedToSql() {
        confined(listOf("$Root.navigation.sql.."), "net.sf.jsqlparser..")
    }

    @Test
    fun artifactReadersAreConfinedToMetadata() {
        confined(
            listOf("$Root.metadata.."),
            "org.apache.parquet..",
            "org.apache.hadoop..",
            "com.fasterxml.jackson..",
            "com.intellij.json.."
        )
    }

    @Test
    fun userInterfaceIsConfinedToIde() {
        confined(
            listOf("$Root.ide.."),
            "com.intellij.openapi.actionSystem..",
            "com.intellij.openapi.ui..",
            "com.intellij.ui.."
        )
    }
}
