package com.github.vbmacher.intellij.dbt.ide

import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.TestActionEvent
import org.junit.Assert.*

import java.nio.file.Files

class DbtCompileProjectActionTest : DbtTestCase() {
    fun testProjectContextChoosesTheNearestOwnerAndOtherwiseRequiresAChoice() {
        project()
        val local = add("models/orders.sql", "select 1")
        val first = workspace.roots.first().directory.path
        assertEquals(listOf(first), DbtCompileProjectAction.projects(workspace, null))
        project(path = "other/")
        val nested = add("other/models/orders.sql", "select 2")
        val second = workspace.root(nested)!!.directory.path
        assertEquals(listOf(first, second).sorted(), DbtCompileProjectAction.projects(workspace, null))
        assertEquals(listOf(first), DbtCompileProjectAction.projects(workspace, local.virtualFile))
        assertEquals(listOf(second), DbtCompileProjectAction.projects(workspace, nested.virtualFile))
    }

    fun testInstalledPackagesCompileThroughTheirConsumerAndAppearOnlyOnce() {
        project()
        val consumer = workspace.roots.first().directory.path
        project(path = "dbt_packages/util/")
        val model = add("dbt_packages/util/models/example.sql", "select 1")
        assertEquals(listOf(consumer), DbtCompileProjectAction.projects(workspace, model.virtualFile))
        assertEquals(listOf(consumer), DbtCompileProjectAction.projects(workspace, null))
    }

    fun testActionStartsWholeProjectCompileWithoutOpeningAConfigurationEditor() {
        project()
        val model = configure("models/orders.sql", "select 1<caret>")
        val directory = tempDirectory("dbt compile action ")
        Files.writeString(directory.resolve("dbt_project.yml"), "name: analytics\n")
        val script = directory.resolve("wrapper.py")
        Files.writeString(
            script,
            """import pathlib,sys
pathlib.Path('arguments').write_text('\n'.join(sys.argv[1:]))
"""
        )
        configureSettings { options ->
            options.projectRoot = directory.toString()
            options.executable = "/usr/bin/python3"
            options.executableArguments = "\"" + script + "\" 'wrapper argument'"
            options.target = "dev"
            options.notifications = false
            options.selection = "tag:unrelated"
            options.exclude = "orders"
        }
        val context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, getProject())
            .add(CommonDataKeys.VIRTUAL_FILE, model.virtualFile).build()
        val action = ActionManager.getInstance().getAction("Dbt.CompileProject")
        assertNotNull(action)
        val contents = RunContentManager.getInstance(getProject())
        val sessions = mutableListOf<RunContentDescriptor>()
        getProject().messageBus.connect(testRootDisposable)
            .subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
                override fun processStartScheduled(executorId: String, environment: ExecutionEnvironment): Unit =
                    environment.setCallback { descriptor: RunContentDescriptor -> sessions += descriptor }
            })
        try {
            action.actionPerformed(TestActionEvent.createTestEvent(action, context))
            await("Compile selects a temporary Run configuration without a dialog") {
                (RunManager.getInstance(getProject()).selectedConfiguration?.configuration as? DbtRunConfiguration)?.action == "Compile"
            }
            val selected =
                RunManager.getInstance(getProject()).selectedConfiguration!!.configuration as DbtRunConfiguration
            assertEquals(directory.toString(), selected.captured!!.directory)
            await("Compile starts directly and finishes") {
                Files.exists(directory.resolve("arguments")) && !getProject().getService(DbtRunner::class.java).isRunning &&
                        sessions.any { it.processHandler!!.isProcessTerminated }
            }
            assertEquals(
                listOf("wrapper argument", "compile", "--target", "dev", "--use-colors"),
                Files.readAllLines(directory.resolve("arguments"))
            )
            val configuration =
                RunManager.getInstance(getProject()).selectedConfiguration!!.configuration as DbtRunConfiguration
            assertEquals("Compile", configuration.action)
            assertEquals("", configuration.scope)
            assertTrue(configuration.selectors.isEmpty())
            assertEquals(DbtCommandScope.All, configuration.captured!!.request.scope)
        } finally {
            sessions.forEach { descriptor ->
                if (!contents.removeRunContent(
                        DefaultRunExecutor.getRunExecutorInstance(),
                        descriptor
                    )
                ) Disposer.dispose(descriptor)
            }
        }
    }
}
