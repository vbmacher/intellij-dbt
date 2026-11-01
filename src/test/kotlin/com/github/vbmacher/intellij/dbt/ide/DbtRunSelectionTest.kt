package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project.*
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.command.WriteCommandAction
import org.junit.Assert.*

import java.nio.file.Files

/** dbt commands built from a chosen resource, directory or project, including configured wrapper executables. */
class DbtRunSelectionTest : DbtTestCase() {
    fun testPickerIncludesModelsAndDirectoriesAndExcludesDisabledAndGeneratedModels() {
        project()
        val orders = add("models/sales/nested/orders.sql", "select 1")
        add("models/disabled.sql", "{{ config(enabled=false) }} select 1")
        add("target/generated.sql", "select 2")
        add("other/dbt_project.yml", "name: marketing\nconfig-version: 2\n")
        val other = add("other/models/orders.sql", "select 3")
        val options = DbtRunSelection.available(workspace)
        assertTrue(options.any { s -> !s.directory && s.path == orders.virtualFile.path })
        assertTrue(options.any { s -> !s.directory && s.path == other.virtualFile.path })
        val root = workspace.roots.find { it.name == "analytics" }!!.directory.path
        for (path in listOf("", "/models", "/models/sales", "/models/sales/nested"))
            assertTrue(path, options.any { s -> s.directory && s.path == root + path })
        assertFalse(options.any { s -> s.path.endsWith("disabled.sql") || s.path.contains("/target") })
    }

    fun testDirectoryCommandsUseTheChosenPathProjectTargetAndIncrementalScope() {
        project("profile: analytics\n")
        add("profiles.yml", "analytics: {target: dev, outputs: {dev: {type: duckdb}}}\n")
        val chosen = add("models/sales/orders.sql", "{{ config(materialized='incremental') }} select 1")
        add("models/sales/nested/detail.sql", "select 2")
        add("models/sales_archive/old.sql", "select 3")
        val w = workspace
        val folder = DbtRunSelection.available(w).find { it.path == chosen.virtualFile.parent.path }!!
        assertEquals(setOf("orders", "detail"), DbtRunSelection.selectedModels(w, folder).map { it.name }.toSet())
        for (action in listOf("Run", "Build", "Test", "Compile")) {
            val command = DbtCommand.create(w, folder, action, fullRefresh = true).getOrThrow()
            assertEquals(w.roots.first().directory.path, command.directory)
            assertEquals("dev", command.target)
            assertTrue(command.arguments.contains("--select"))
            assertTrue(command.arguments.contains("fqn:analytics.sales.orders"))
            assertTrue(command.arguments.contains("fqn:analytics.sales.nested.detail"))
            assertFalse(command.arguments.contains("fqn:analytics.sales_archive.old"))
            assertEquals((action in setOf("Run", "Build")), command.arguments.contains("--full-refresh"))
        }
        assertTrue(DbtCommand.create(w, folder, "Preview", fullRefresh = false).isFailure)
        val docs = DbtCommand.create(w, folder, "Generate Docs", fullRefresh = false).getOrThrow()
        assertFalse(docs.arguments.contains("--select"))
        assertEquals(listOf("docs", "generate"), docs.arguments.subList(1, 3))
    }

    fun testDeletedOrForgedSelectionCannotLaunchACommand() {
        project()
        val file = add("models/orders.sql", "select 1")
        val selected = DbtRunSelection.available(workspace).find { it.path == file.virtualFile.path }!!
        WriteCommandAction.runWriteCommandAction(getProject(), Runnable { file.virtualFile.delete(this) })
        assertTrue(DbtCommand.create(workspace, selected, "Run", fullRefresh = false).isFailure)
        assertTrue(
            DbtCommand.create(
                workspace,
                selected.copy(path = selected.root + "/../outside.sql"),
                "Run",
                fullRefresh = false
            ).isFailure
        )
        assertTrue(DbtCommand.create(workspace, selected.copy(path = ""), "Run", fullRefresh = false).isFailure)
    }

    fun testFullRefreshDoesNotIncludeIncrementalModelsOutsideTheChosenDirectory() {
        project()
        val file = add("models/chosen/orders.sql", "select 1")
        add("models/elsewhere/other.sql", "{{ config(materialized='incremental') }} select 2")
        val w = workspace
        val selected = DbtRunSelection.available(w).find { it.path == file.virtualFile.parent.path }!!
        assertFalse(
            DbtCommand.create(w, selected, "Run", fullRefresh = true).getOrThrow().arguments.contains("--full-refresh")
        )
    }

    fun testAllModelsRunsWithoutASelectorAndKeepsTheProjectConfiguration() {
        project("profile: analytics\n")
        add("profiles.yml", "analytics: {target: dev, outputs: {dev: {type: duckdb}}}\n")
        add("models/orders.sql", "{{ config(materialized='incremental') }} select 1")
        add("models/nested/detail.sql", "select 2")
        val w = workspace
        val root = w.roots.first().directory.path
        DbtProjectSettings.get(getProject()).options(root).executableArguments = "wms"
        val all = DbtRunSelection.all(w, "")!!
        assertEquals("", all.path)
        assertEquals(setOf("orders", "detail"), DbtRunSelection.selectedModels(w, all).map { it.name }.toSet())
        val projectDirectory = DbtRunSelection.available(w).find { it.path == root }!!
        for (selection in listOf(all, projectDirectory)) for (action in listOf(
            "Run",
            "Build",
            "Test",
            "Compile",
            "Generate Docs"
        )) {
            val command = DbtCommand.create(w, selection, action, fullRefresh = true).getOrThrow()
            assertEquals(root, command.directory)
            assertEquals("dev", command.target)
            assertEquals("wms", command.arguments[1])
            assertFalse(command.arguments.contains("--select"))
            assertFalse(command.arguments.any { it.startsWith("fqn:") })
            assertEquals((action in setOf("Run", "Build")), command.arguments.contains("--full-refresh"))
        }
        assertTrue(DbtCommand.create(w, all, "Preview", fullRefresh = false).isFailure)
        assertTrue(DbtCommand.create(w, all.copy(root = root + "/missing"), "Run", fullRefresh = false).isFailure)
    }

    fun testPackageRootRemainsScopedWhenItsConsumerIsTheExecutionProject() {
        project()
        add("models/local.sql", "select 1")
        val config = add("dbt_packages/util/dbt_project.yml", "name: util\nconfig-version: 2\n")
        add("dbt_packages/util/models/staging/orders.sql", "select 2")
        val w = workspace
        val chosen = DbtRunSelection.available(w).find { it.path == config.virtualFile.parent.path }!!
        val command = DbtCommand.create(w, chosen, "Compile", fullRefresh = false).getOrThrow()
        assertTrue(command.arguments.contains("--select"))
        assertTrue(command.arguments.contains("fqn:util.staging.orders"))
        assertFalse(command.arguments.contains("fqn:analytics.local"))
        assertEquals(w.roots.find { it.name == "analytics" }!!.directory.path, command.directory)
    }

    fun testAllModelsKeepsTheExplicitProjectInAMultiProjectWorkspace() {
        project()
        add("models/orders.sql", "select 1")
        add("other/dbt_project.yml", "name: marketing\nconfig-version: 2\n")
        add("other/models/campaigns.sql", "select 2")
        val w = workspace
        val marketing = w.roots.find { it.name == "marketing" }!!
        val all = DbtRunSelection.all(w, marketing.directory.path)!!
        assertEquals(listOf("campaigns"), DbtRunSelection.selectedModels(w, all).map { it.name })
        assertEquals(
            marketing.directory.path,
            DbtCommand.create(w, all, "Run", fullRefresh = false).getOrThrow().directory
        )
    }


    private fun setupWrapper(): DbtRoot {
        project("profile: analytics\n")
        add("profiles.yml", "analytics: {target: dev, outputs: {dev: {type: duckdb}}}\n")
        add("models/orders.sql", "select 1")
        val root = workspace.roots.first()
        root.runOptions.executable = "scripts/dbt-wrapper"
        root.runOptions.executableArguments = "analytics"
        return root
    }

    fun testEveryActionPassesConfiguredArgumentsBeforeTheDbtCommand() {
        val root = setupWrapper()
        val file = root.psiFile("models/orders.sql")!!
        DbtCommand.Actions.forEach { action ->
            val selectedPath = if (action == "Source Freshness") root.directory.path else file.virtualFile.path
            val command = DbtCommand.create(
                workspace,
                DbtRunSelection.available(workspace).find { it.path == selectedPath }!!,
                action,
                fullRefresh = false
            ).getOrThrow()
            val subcommand =
                if (action == "Generate Docs") "docs" else if (action == "Preview") "show" else if (action == "Source Freshness") "source" else action.lowercase()
            assertEquals(
                listOf(
                    root.executionDirectory.resolve("scripts/dbt-wrapper").toString(),
                    "analytics",
                    subcommand
                ), command.arguments.take(3)
            )
            assertTrue(command.arguments.windowed(2).any { it == listOf("--target", "dev") })
            if (action == "Generate Docs") assertEquals("generate", command.arguments[3])
            else if (action == "Source Freshness") {
                assertEquals("freshness", command.arguments[3]);
                assertFalse(command.arguments.contains("--select"))
            } else assertTrue(command.arguments.windowed(2).any { it == listOf("--select", "fqn:analytics.orders") })
            assertEquals(root.directory.path, command.directory)
        }
    }

    fun testRootsKeepIndependentArgumentsAndDirectorySelection() {
        val root = setupWrapper()
        add("other/dbt_project.yml", "name: marketing\nconfig-version: 2\n")
        val file = add("other/models/orders.sql", "select 1")
        val other = workspace.root(file)!!
        other.runOptions.executable = "custom-dbt"
        other.runOptions.executableArguments = "'marketing data'"
        val selected = DbtRunSelection.available(workspace).find { it.path == other.directory.path + "/models" }!!
        val command = DbtCommand.create(workspace, selected, "Run", fullRefresh = false).getOrThrow()
        assertEquals(listOf("custom-dbt", "marketing data", "run"), command.arguments.take(3))
        assertTrue(command.arguments.windowed(2).any { it == listOf("--select", "fqn:marketing.orders") })
        assertEquals(other.directory.path, command.directory)
        assertEquals("analytics", root.runOptions.executableArguments)
    }

    fun testProjectRootDoesNotAddSelectorsToTheWrapperCommand() {
        val root = setupWrapper()
        add("dbt_packages/util/dbt_project.yml", "name: util\nconfig-version: 2\n")
        add("dbt_packages/util/models/example.sql", "select 2")
        val selected = DbtRunSelection.available(workspace).find { it.path == root.directory.path }!!
        val command = DbtCommand.create(workspace, selected, "Compile", fullRefresh = false).getOrThrow()
        assertEquals(
            listOf(
                root.executionDirectory.resolve("scripts/dbt-wrapper").toString(),
                "analytics", "compile", "--target", "dev", "--use-colors"
            ), command.arguments
        )
    }

    fun testWrapperFindsLocalDbtAndPreservesQuotedArgumentsWithoutAShell() {
        val root = setupWrapper()
        val directory = tempDirectory("dbt wrapper with spaces ")
        Files.writeString(directory.resolve("dbt_project.yml"), "name: analytics\nconfig-version: 2\n")
        val bin = Files.createDirectories(directory.resolve(".venv/bin"))
        fakeTool(bin.resolve("dbt"), "#!/bin/sh\nprintf '%s\\n' \"\$@\"\n")
        val wrapper = fakeTool(directory.resolve("runner script"), "#!/bin/sh\nexec dbt \"\$@\"\n")
        root.runOptions.projectRoot = directory.toString()
        root.runOptions.executable = "./runner script"
        root.runOptions.executableArguments = "\"a project\" '\$(echo untouched)'"
        val command = DbtCommand.create(
            workspace,
            DbtRunSelection.available(workspace).find { it.path == root.directory.path }!!,
            "Generate Docs",
            fullRefresh = false
        ).getOrThrow()
        assertEquals(
            listOf(wrapper.toString(), "a project", "\$(echo untouched)", "docs", "generate"),
            command.arguments.take(5)
        )
        assertTrue(command.environment.getValue("PATH").startsWith(bin.toString() + java.io.File.pathSeparator))
        val process = GeneralCommandLine(command.arguments).withWorkDirectory(command.directory)
            .withEnvironment(command.environment).createProcess()
        val output = String(process.inputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
        assertEquals(0, process.waitFor())
        assertEquals(command.arguments.drop(1), output.reader().readLines())
    }
}
