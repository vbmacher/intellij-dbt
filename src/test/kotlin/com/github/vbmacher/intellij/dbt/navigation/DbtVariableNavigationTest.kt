package com.github.vbmacher.intellij.dbt.navigation

import com.github.vbmacher.intellij.dbt.project.DbtRoot
import com.github.vbmacher.intellij.dbt.ide.DbtPsiSymbol
import com.github.vbmacher.intellij.dbt.ide.DbtTestCase
import com.intellij.model.psi.impl.targetSymbols
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.junit.Assert.*


class DbtVariableNavigationTest : DbtTestCase() {
    private fun exampleProject(config: String = "vars:\n  start_date: '2026-01-01'\n"): PsiFile =
        add("dbt_project.yml", "name: example\nconfig-version: 2\n" + config)

    override val root: DbtRoot get() = workspace.roots.find { it.name == "example" }!!

    private fun declaration(name: String): YAMLKeyValue =
        DbtResolution.variable(workspace, root, name).first().let { it as YAMLKeyValue }

    // Variable name arguments, excluding the `var` builtin itself and dynamic expressions.
    private fun references(file: PsiFile): List<DbtNavigationReference> =
        DbtNavigation.inFile(file).filterNot { r -> setOf("var", "static reference").contains(r.canonicalText) }

    private fun usages(name: String): List<PsiReference> = ReferencesSearch.search(declaration(name)).findAll().toList()

    fun testNavigateToYamlAndFindUsages() {
        exampleProject()
        val model = add("models/events.sql", "select '{{ var('start_date') }}', {{ var(\"start_date\", 'default') }}")
        val test = add("tests/check_events.sql", "select {{ var('start_date') }}")
        val expected = declaration("start_date")

        assertEquals(2, references(model).size)
        references(model).forEach { reference ->
            assertSame(expected, reference.resolve())
            assertEquals("start_date", reference.rangeInElement.substring(model.text))
        }

        // Exercise the registered navigation extension, not just the resolver.
        myFixture.configureFromExistingVirtualFile(model.virtualFile)
        val offset = model.text.indexOf("start_date") + 3
        assertSame(expected, targetSymbols(model, offset).first().let { it as DbtPsiSymbol }.element)
        assertSame(expected, model.findReferenceAt(offset)!!.resolve())

        val found = usages("start_date")
        assertEquals(3, found.size)
        assertEquals(setOf(model, test), found.map { it.element.containingFile }.toSet())
        assertEquals(3, myFixture.findUsages(expected).size)
    }

    fun testProjectScopeOverridesGlobalAndOtherPackagesDoNotLeak() {
        exampleProject("vars:\n  start_date: global\n  fallback: global\n  example:\n    start_date: scoped\n  other_package:\n    hidden: other\n")
        val file = add("models/events.sql", "{{ var('start_date') }} {{ var('fallback') }} {{ var('hidden') }}")
        val refs = references(file)
        assertEquals("scoped", refs.first().resolve().let { it as YAMLKeyValue }.valueText)
        assertEquals("global", refs[1].resolve().let { it as YAMLKeyValue }.valueText)
        assertNull(refs[2].resolve())
        assertEquals(1, usages("start_date").size)
        val globals = root.config.getKeyValueByKey("vars")!!.value
            .let { it as org.jetbrains.yaml.psi.YAMLMapping }
        assertTrue(ReferencesSearch.search(globals.getKeyValueByKey("start_date")!!).findAll().isEmpty())
    }

    fun testDictionariesAndQuotedNames() {
        exampleProject("vars:\n  options:\n    region: us\n  'date.start': today\n  \"it's_ready\": true\n")
        val file = add(
            "models/events.sql",
            "{{ var('options')['region'] }} {{ var('region') }} {{ var('date.start') }} {{ var(\"it's_ready\") }}"
        )
        assertNotNull(references(file).first().resolve())
        assertNull(references(file)[1].resolve())
        assertEquals(1, usages("date.start").size)
        assertEquals(1, usages("it's_ready").size)
    }

    fun testEverySqlResourceAndConfiguredPaths() {
        exampleProject("model-paths: [transformations]\nvars:\n  start_date: today\n")
        listOf("transformations", "tests", "snapshots", "macros", "analyses").forEach { directory ->
            val file = add("$directory/use.sql", "{% set date = var('start_date') %}")
            assertSame(declaration("start_date"), references(file).first().resolve())
        }
        assertTrue(references(add("models/ignored.sql", "{{ var('start_date') }}")).isEmpty())
        assertEquals(5, usages("start_date").size)
    }

    fun testEmptyResourcePathsDisableResources() {
        exampleProject("model-paths: []\nvars:\n  start_date: today\n")
        assertTrue(references(add("models/events.sql", "{{ var('start_date') }}")).isEmpty())
    }

    fun testGeneratedPackagesAndNestedProjectsAreExcluded() {
        exampleProject("model-paths: ['.']\ntarget-path: build\npackages-install-path: vendor\nvars:\n  start_date: today\n")
        listOf("build", "vendor", "logs").forEach { directory ->
            assertTrue(references(add("$directory/use.sql", "{{ var('start_date') }}")).isEmpty())
        }
        myFixture.addFileToProject("nested/dbt_project.yml", "name: nested\nvars:\n  start_date: other\n")
        assertEquals(
            "other", references(add("nested/models/use.sql", "{{ var('start_date') }}")).first().resolve()
            .let { it as YAMLKeyValue }.valueText
        )
        assertNotNull(references(add("models/use.sql", "{{ var('start_date') }}")).first().resolve())
        assertEquals(1, usages("start_date").size)
    }

    fun testFilesOutsideAProjectDoNotActivateNavigation() {
        assertTrue(references(add("models/use.sql", "{{ var('start_date') }}")).isEmpty())
        myFixture.addFileToProject("nested/dbt_project.yml", "name: nested\nvars:\n  start_date: other\n")
        assertTrue(references(add("models/other.sql", "{{ var('start_date') }}")).isEmpty())
        assertEquals(
            "other", references(add("nested/models/use.sql", "{{ var('start_date') }}")).first().resolve()
            .let { it as YAMLKeyValue }.valueText
        )
    }

    fun testJinjaCommentsRawBlocksAndNonCallsAreIgnored() {
        exampleProject()
        val file = add(
            "models/use.sql",
            """
        |select var('start_date');
        |{# {{ var('start_date') }} #}
        |{% raw %}{{ var('start_date') }}{% endraw %}
        |{{ "var('start_date')" }}
        |{{ object.var('start_date') }}
        |{{ value | var('start_date') }}
        |{{ value is var('start_date') }} {{ value is not var('start_date') }}
        |{% filter var('start_date') %}text{% endfilter %}
        |{{ var(prefix) }} {{ var('start_' ~ suffix) }}
        |-- {{ var('start_date') }}
        |{% if var('start_date') %}select 1{% endif %}
        |""".trimMargin()
        )
        assertEquals(2, references(file).size)
        assertEquals(2, usages("start_date").size)
    }

    fun testMultilineOffsetsNestedDefaultsAndWhitespaceControl() {
        exampleProject("vars:\n  start_date: today\n  fallback: yesterday\n")
        val file = add("models/use.sql", "select\n{{-\n var (\n 'start_date', var('fallback')\n )\n-}}")
        val refs = references(file)
        assertEquals(listOf("start_date", "fallback"), refs.map { it.canonicalText })
        refs.forEach { reference ->
            assertEquals(
                reference.canonicalText,
                reference.rangeInElement.substring(file.text)
            )
        }
        assertEquals(1, usages("start_date").size)
        assertEquals(1, usages("fallback").size)
    }

    fun testMissingDefinitionsRemainSoftAndDynamicNamesAreNotReferences() {
        exampleProject()
        val file =
            add("models/use.sql", "{{ var('missing', 'default') }} {{ var(dynamic_name) }} {{ var('prefix_' + name) }}")
        assertEquals(1, references(file).size)
        assertTrue(references(file).first().isSoft)
        assertNull(references(file).first().resolve())
    }

    fun testSearchRespectsGlobalAndLocalScopes() {
        exampleProject()
        val first = add("models/first.sql", "{{ var('start_date') }}")
        add("models/second.sql", "{{ var('start_date') }}")
        assertEquals(
            1, ReferencesSearch.search(
                declaration("start_date"),
                GlobalSearchScope.fileScope(getProject(), first.virtualFile)
            ).findAll().size
        )
        assertEquals(1, ReferencesSearch.search(declaration("start_date"), LocalSearchScope(first)).findAll().size)
    }

    fun testUnsavedEditsInvalidateCallsAndDefinitions() {
        val config = exampleProject("vars:\n  start_date: today\n  end_date: tomorrow\n")
        val file = add("models/use.sql", "{{ var('start_date') }}")
        assertEquals("start_date", references(file).first().canonicalText)
        val documents = PsiDocumentManager.getInstance(getProject())
        WriteCommandAction.runWriteCommandAction(getProject(), object : Runnable {
            override fun run() {
                documents.getDocument(file)!!.setText("{{ var('end_date') }}")
                documents.getDocument(config)!!.setText("name: example\nvars:\n  end_date: changed\n")
                documents.commitAllDocuments()
            }
        })
        assertEquals("end_date", references(file).first().canonicalText)
        assertEquals("changed", references(file).first().resolve().let { it as YAMLKeyValue }.valueText)
        assertEquals(1, usages("end_date").size)
    }

    fun testCompletedCallInsideAnUnclosedJinjaBlock() {
        exampleProject()
        val file = add("models/use.sql", "{{ var('start_date')")
        assertSame(declaration("start_date"), references(file).first().resolve())
    }

    fun testEscapedLiteralNamesKeepTheirSourceRanges() {
        exampleProject("vars:\n  \"it's_ready\": true\n")
        val file = add("models/use.sql", "{{ var('it\\'s_ready') }}")
        val reference = references(file).first()
        assertEquals("it's_ready", reference.canonicalText)
        assertEquals("it\\'s_ready", reference.rangeInElement.substring(file.text))
        assertSame(declaration("it's_ready"), reference.resolve())
        assertEquals(1, usages("it's_ready").size)
    }

    fun testWhitespaceControlledRawBlocks() {
        exampleProject()
        val file = add("models/use.sql", "{%- raw -%}{{ var('start_date') }}{%- endraw -%}\n{{ var('start_date') }}")
        assertEquals(1, references(file).size)
        assertEquals(file.text.lastIndexOf("start_date"), references(file).first().rangeInElement.startOffset)
    }

    fun testMalformedJinjaDoesNotBreakNavigation() {
        exampleProject()
        val file = add("models/use.sql", "{{ var('start_date') }}\n{% = %}")
        assertEquals(1, references(file).size)
    }

    fun testTrimNormalizationDoesNotChangeVariableNames() {
        exampleProject("vars:\n  '{%-name': true\n")
        val file = add("models/use.sql", "{{ var('{%-name') }}")
        assertEquals("{%-name", references(file).first().canonicalText)
        assertSame(declaration("{%-name"), references(file).first().resolve())
        assertEquals(1, usages("{%-name").size)
    }

    fun testNavigationAndSearchWithAnExistingLanguageParser() {
        exampleProject()
        withSqlParsedAsJson {
            val file = add("models/use.sql", "value: \"{{ var('start_date') }}\"")
            assertTrue(file.let { it is com.intellij.json.psi.JsonFile })
            val offset = file.text.indexOf("start_date") + 2
            myFixture.configureFromExistingVirtualFile(file.virtualFile)
            assertSame(
                declaration("start_date"),
                targetSymbols(file, offset).first().let { it as DbtPsiSymbol }.element
            )
            assertEquals(1, usages("start_date").size)
        }
    }
}
