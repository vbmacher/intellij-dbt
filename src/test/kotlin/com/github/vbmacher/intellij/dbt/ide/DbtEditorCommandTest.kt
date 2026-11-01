package com.github.vbmacher.intellij.dbt.ide

import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.util.Disposer
import org.junit.Assert.*
import java.nio.file.Files

class DbtEditorCommandTest : DbtTestCase() {
    private fun withCommands(body: (com.intellij.psi.PsiFile, java.nio.file.Path) -> Unit) {
        project()
        val model = configure("models/orders.sql", "select 1 as id<caret>")
        val directory = tempDirectory("dbt editor commands")
        Files.writeString(directory.resolve("dbt_project.yml"), "name: analytics\n")
        val script = directory.resolve("dbt.py")
        Files.writeString(
            script, """import json,pathlib,sys
pathlib.Path('arguments').write_text('\n'.join(sys.argv[1:]))
if sys.argv[1]=='compile':
 pathlib.Path('target').mkdir(exist_ok=True)
 manifest={'metadata':{'dbt_schema_version':'https://schemas.getdbt.com/dbt/manifest/v12.json','generated_at':'2099-01-01T00:00:00Z'},'nodes':{'model.analytics.orders':{'unique_id':'model.analytics.orders','resource_type':'model','name':'orders','package_name':'analytics','original_file_path':'models/orders.sql','raw_code':'select 1 as id','compiled_code':'select 42 as id'}},'sources':{}}
 pathlib.Path('target/manifest.json').write_text(json.dumps(manifest))
elif sys.argv[1]=='show': print(json.dumps({'show':[{'id':1}]}),flush=True)
"""
        )
        configureSettings { options ->
            options.projectRoot = directory.toString(); options.executable = "/usr/bin/python3"
            options.executableArguments = "\"$script\""; options.target = "dev"; options.notifications = false
        }
        var sessions = emptyList<RunContentDescriptor>()
        val manager = RunManager.getInstance(project)
        val configurations = manager.allSettings.toSet()
        val selected = manager.selectedConfiguration
        project.messageBus.connect(testRootDisposable)
            .subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
                override fun processStartScheduled(executorId: String, environment: ExecutionEnvironment) {
                    environment.setCallback { descriptor -> sessions += descriptor }
                }
            })
        try {
            body(model, directory)
        } finally {
            sessions.forEach {
                if (!RunContentManager.getInstance(project)
                        .removeRunContent(DefaultRunExecutor.getRunExecutorInstance(), it)
                ) Disposer.dispose(it)
            }
            manager.allSettings.filter { it !in configurations }.forEach(manager::removeConfiguration)
            manager.selectedConfiguration = selected?.takeIf { it in manager.allSettings }
        }
    }

    fun testWholeModelPreviewRunsDirectlyFromTheEditorWithItsTarget() = withCommands { _, directory ->
        myFixture.testAction(DbtPreviewModelAction())
        await("Whole-model preview runs without a configuration dialog") { Files.exists(directory.resolve("arguments")) }
        val arguments = Files.readString(directory.resolve("arguments"))
        assertTrue(arguments.startsWith("show\n")); assertTrue(arguments.contains("--target\ndev")); assertTrue(
        arguments.contains("orders")
    ); assertFalse(arguments.contains("--inline"))
        await("The preview completes") { !project.getService(DbtRunner::class.java).isRunning }
    }

    fun testMissingCompiledSqlCanBeCanceledOrCompiledAndOpened() = withCommands { model, directory ->
        var answer = Messages.NO
        val previous = TestDialogManager.setTestDialog(object : TestDialog {
            override fun show(message: String) = answer
        })
        try {
            val symbol = workspace.model(model)!!
            DbtCompiledSql.show(workspace, symbol, compare = false)
            assertFalse(Files.exists(directory.resolve("arguments")))
            answer = Messages.YES; DbtCompiledSql.show(workspace, symbol, compare = false)
            await("Compile and open displays the generated SQL", 30) {
                FileEditorManager.getInstance(project).openFiles.any {
                    com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
                        .getDocument(it)?.text == "select 42 as id"
                }
            }
            val arguments = Files.readString(directory.resolve("arguments"))
            assertTrue(arguments.startsWith("compile\n")); assertTrue(arguments.contains("--target\ndev")); assertTrue(
                arguments.contains("orders")
            )
        } finally {
            TestDialogManager.setTestDialog(previous)
        }
    }
}
