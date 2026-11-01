package com.github.vbmacher.intellij.dbt.ide

import com.intellij.lang.annotation.HighlightSeverity
import org.jetbrains.yaml.schema.YamlJsonSchemaHighlightingInspection
import org.junit.Assert.*

/** Exercise IntelliJ's actual YAML schema completion/inspection, including local $ref resolution. */
class DbtYamlSchemaAcceptanceTest : DbtTestCase() {
    private val details: String
        get() {
            val service = com.jetbrains.jsonSchema.ide.JsonSchemaService.Impl.get(project)
            val file = myFixture.file
            val schema = service.getSchemaObject(file)
            val resources = schema?.let {
                com.jetbrains.jsonSchema.impl.JsonSchemaResolver(
                    project, it,
                    com.intellij.json.pointer.JsonPointerPosition.parsePointer("/models/0"),
                    null as com.jetbrains.jsonSchema.extension.adapters.JsonValueAdapter?
                ).resolve()
            } ?: emptyList()
            val resolved = resources.flatMap { it.propertyNames.asSequence().toList() }.distinct()
            return "resourceProperties=$resolved; service=${service.javaClass.name}; applicable=${
                service.isApplicableToFile(
                    file.virtualFile
                )
            }; providers=${providers(file)}; schemas=${service.getSchemaFilesForFile(file.virtualFile)}; schemaLoaded=${
                service.getSchemaObject(
                    file
                ) != null
            }; source=${file.text}"
        }

    private fun suggestions(): Set<String> = myFixture.completeBasic().orEmpty().map { it.lookupString }.toSet()

    private fun profile(version: String, engine: String = "core") {
        configureSettings { o -> o.dbtVersion = version; o.engine = engine }
    }

    private fun providers(file: com.intellij.psi.PsiFile): List<String> =
        DbtYamlSchemaProvider().getProviders(getProject())
            .filter { it.isAvailable(file.getVirtualFile()) }.map { it.getName() }.toList()

    fun testNativeNestedPropertiesCompletionInsertsYamlKey() {
        project()
        configure("models/schema.yml", "models:\n  - name: orders\n    <caret>\n")
        val choices = suggestions()
        assertTrue(
            "Native schema completions: " + choices + "; " + details,
            setOf("columns", "config").all { it in choices })
        val columns = myFixture.lookupElements!!.find { it.getLookupString() == "columns" }!!
        myFixture.getLookup().setCurrentItem(columns)
        myFixture.finishLookup('\n')
        assertTrue(myFixture.getFile().getText().contains("    columns:"))
    }

    fun testNativeColumnCompletionResolvesBundledCommonSchema() {
        project()
        configure(
            "models/schema.yml",
            "models:\n  - name: orders\n    columns:\n      - name: order_id\n        <caret>\n"
        )
        val choices = suggestions()
        assertTrue(
            "Column schema completions: " + choices + "; " + details,
            setOf("data_type", "description", "data_tests").all { it in choices })
    }

    fun testNativeSchemaInspectionMarksInvalidValueAndAcceptsTemplatesAndCustomConfig() {
        project()
        val invalid = configure("models/schema.yml", "models:\n- name: orders\n  columns: wrong<caret>\n")
        myFixture.enableInspections(YamlJsonSchemaHighlightingInspection())
        val problems = myFixture.doHighlighting()
            .filter { h -> h.getSeverity().compareTo(HighlightSeverity.WARNING) >= 0 && h.getDescription() != null }
        assertTrue("Native schema diagnostics: " + problems.map { it.getDescription() } + "; " + details,
            problems.any { h ->
                invalid.getText().substring(h.getStartOffset(), h.getEndOffset()).contains("wrong") &&
                        (h.getDescription()!!.lowercase().contains("array") || h.getDescription()!!.lowercase()
                            .contains("type") || h.getDescription()!!.lowercase().contains("pattern"))
            })
        configure(
            "models/templated.yml",
            "models:\n- name: orders\n  columns: \"{{ dynamic_columns }}\"\n  config:\n    custom_adapter_option: custom_value\n    meta: {owner: Finance}\n<caret>"
        )
        assertTrue("Templates and adapter config must not be rejected", myFixture.doHighlighting().filter { h ->
            h.getSeverity().compareTo(HighlightSeverity.WARNING) >= 0 && h.getDescription() != null
        }.isEmpty())
    }

    fun testNativeTopLevelCompletionsFollowCoreAndEngineVersions() {
        project()
        data class VersionCase(
            val version: String,
            val engine: String,
            val expected: Set<String>,
            val absent: Set<String>
        )

        val cases = listOf(
            VersionCase(
                "1.5",
                "core",
                emptySet<String>(),
                setOf("semantic_models", "saved_queries", "unit_tests", "functions")
            ),
            VersionCase("1.6", "core", setOf("semantic_models"), setOf("saved_queries", "unit_tests", "functions")),
            VersionCase("1.7", "core", setOf("semantic_models", "saved_queries"), setOf("unit_tests", "functions")),
            VersionCase("1.8", "core", setOf("semantic_models", "saved_queries", "unit_tests"), setOf("functions")),
            VersionCase(
                "1.11",
                "core",
                setOf("semantic_models", "saved_queries", "unit_tests", "functions"),
                emptySet<String>()
            ),
            VersionCase(
                "1.10",
                "v2",
                setOf("semantic_models", "saved_queries", "unit_tests", "functions"),
                emptySet<String>()
            )
        )
        configureSettings { it.dbtVersion = "1.5" }
        cases.withIndex().forEach { (index, case) ->
            val (version, engine, expected, absent) = case
            profile(version, engine)
            configure("models/profile_$index.yml", "models: []\n<caret>\n")
            val choices = suggestions()
            assertTrue("$engine $version suggestions: $choices", expected.all { it in choices })
            assertTrue("$engine $version must omit unsupported keys: $choices", absent.intersect(choices).isEmpty())
            com.intellij.codeInsight.lookup.LookupManager.hideActiveLookup(getProject())
        }
    }

    fun testNativeDiagnosticsRejectUnsupportedUnitTestsBefore18() {
        project();
        profile("1.7")
        val file = configure("models/units.yml", "models: []\nunit_tests: []<caret>\n")
        myFixture.enableInspections(YamlJsonSchemaHighlightingInspection())
        val problems = myFixture.doHighlighting()
            .filter { h -> h.getSeverity().compareTo(HighlightSeverity.WARNING) >= 0 && h.getDescription() != null }
        assertTrue(
            "Unsupported unit tests should have a native schema diagnostic: " + problems.map { it.getDescription() },
            problems.any { h ->
                file.getText().substring(h.getStartOffset(), h.getEndOffset())
                    .contains("unit_tests") || h.getDescription()!!.lowercase()
                    .contains("not allowed") || h.getDescription()!!.contains("unit_tests") && h.getDescription()!!
                    .contains("false")
            })
    }

    fun testForeignAndExcludedYamlDoNotReceiveDbtSchemaDiagnostics() {
        project();
        add(".dbtignore", "models/ignored.yml\n")
        val foreign = configure("services/service.yml", "models: wrong<caret>\n")
        assertTrue(providers(foreign).isEmpty())
        myFixture.enableInspections(YamlJsonSchemaHighlightingInspection())
        assertTrue(
            myFixture.doHighlighting()
                .all { h -> h.getDescription() == null || h.getSeverity().compareTo(HighlightSeverity.WARNING) < 0 })
        val ignored = configure("models/ignored.yml", "models: wrong<caret>\n")
        assertTrue(providers(ignored).isEmpty())
        assertTrue(
            myFixture.doHighlighting()
                .all { h -> h.getDescription() == null || h.getSeverity().compareTo(HighlightSeverity.WARNING) < 0 })
        val unrelated = configure("models/service.yml", "services: {http: {port: 8080}}<caret>\n")
        assertTrue(providers(unrelated).isEmpty())
    }

    fun testNativeInlineExposureKeysProvideNestedSuggestions() {
        project()
        configure("models/exposure.yml", "exposures:\n  - name: finance\n    <caret>\n")
        val choices = suggestions()
        assertTrue(
            "Inline exposure schema completions: " + choices + "; " + details,
            setOf("type", "url", "owner").all { it in choices })
    }

    fun testEveryProfileResolvesNestedResourceAndSourceColumnKeys() {
        project()
        configureSettings { it.dbtVersion = "1.4" }
        val cases = listOf(
            "1.4" to "core", "1.5" to "core", "1.6" to "core", "1.7" to "core",
            "1.8" to "core", "1.10" to "core", "1.11" to "core", "1.10" to "v2"
        )
        cases.withIndex().forEach { (index, case) ->
            val (version, engine) = case
            profile(version, engine)
            configure("models/nested_$index.yml", "models:\n  - name: orders\n    <caret>\n")
            val resourceKeys = suggestions()
            assertTrue(
                "$engine $version resource keys: $resourceKeys; $details",
                setOf("columns", "config", "description").all { it in resourceKeys })
            if (engine == "core" && setOf("1.4", "1.5", "1.6", "1.7").contains(version))
                assertFalse("$version must not suggest data_tests: $resourceKeys", resourceKeys.contains("data_tests"))
            com.intellij.codeInsight.lookup.LookupManager.hideActiveLookup(getProject())
            configure(
                "models/source_$index.yml",
                "sources:\n  - name: raw\n    tables:\n      - name: orders\n        columns:\n          - name: id\n            <caret>\n"
            )
            val columnKeys = suggestions()
            assertTrue(
                "$engine $version source column keys: $columnKeys; $details",
                setOf("description", "data_type").all { it in columnKeys })
            if (engine == "core" && setOf("1.4", "1.5", "1.6", "1.7").contains(version))
                assertFalse(
                    "$version must not suggest column data_tests: $columnKeys",
                    columnKeys.contains("data_tests")
                )
            com.intellij.codeInsight.lookup.LookupManager.hideActiveLookup(getProject())
        }
    }

}
