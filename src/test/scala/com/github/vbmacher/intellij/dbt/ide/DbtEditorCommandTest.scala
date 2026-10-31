package com.github.vbmacher.intellij.dbt.ide

import com.intellij.execution.{ExecutionListener, ExecutionManager, RunManager}
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.{RunContentDescriptor, RunContentManager}
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ui.{Messages, TestDialog, TestDialogManager}
import com.intellij.openapi.util.Disposer
import org.junit.Assert._

import java.nio.file.Files
import scala.jdk.CollectionConverters._

final class DbtEditorCommandTest extends DbtTestCase {
  private def withCommands(body: (com.intellij.psi.PsiFile, java.nio.file.Path) => Unit): Unit = {
    project()
    val model = configure("models/orders.sql", "select 1 as id<caret>")
    val directory = tempDirectory("dbt editor commands")
    Files.writeString(directory.resolve("dbt_project.yml"), "name: analytics\n")
    val script = directory.resolve("dbt.py")
    Files.writeString(script, """import json,pathlib,sys
pathlib.Path('arguments').write_text('\n'.join(sys.argv[1:]))
if sys.argv[1]=='compile':
 pathlib.Path('target').mkdir(exist_ok=True)
 manifest={'metadata':{'dbt_schema_version':'https://schemas.getdbt.com/dbt/manifest/v12.json','generated_at':'2099-01-01T00:00:00Z'},'nodes':{'model.analytics.orders':{'unique_id':'model.analytics.orders','resource_type':'model','name':'orders','package_name':'analytics','original_file_path':'models/orders.sql','raw_code':'select 1 as id','compiled_code':'select 42 as id'}},'sources':{}}
 pathlib.Path('target/manifest.json').write_text(json.dumps(manifest))
elif sys.argv[1]=='show': print(json.dumps({'show':[{'id':1}]}),flush=True)
""")
    configureSettings { options =>
      options.projectRoot = directory.toString
      options.executable = "/usr/bin/python3"
      options.executableArguments = "\"" + script + "\""
      options.target = "dev"
      options.notifications = false
    }
    var sessions = Vector.empty[RunContentDescriptor]
    val manager = RunManager.getInstance(getProject)
    val configurations = manager.getAllSettings.asScala.toSet
    val selected = manager.getSelectedConfiguration
    getProject.getMessageBus.connect(getTestRootDisposable).subscribe(ExecutionManager.EXECUTION_TOPIC, new ExecutionListener {
      override def processStartScheduled(executorId: String, environment: ExecutionEnvironment): Unit =
        environment.setCallback((descriptor: RunContentDescriptor) => sessions :+= descriptor)
    })
    try body(model, directory)
    finally {
      sessions.foreach { descriptor =>
        if (!RunContentManager.getInstance(getProject).removeRunContent(DefaultRunExecutor.getRunExecutorInstance, descriptor))
          Disposer.dispose(descriptor)
      }
      manager.getAllSettings.asScala.filterNot(configurations).foreach(manager.removeConfiguration)
      manager.setSelectedConfiguration(selected)
    }
  }

  def testWholeModelPreviewRunsDirectlyFromTheEditorWithItsTarget(): Unit = withCommands { (_, directory) =>
    myFixture.testAction(new DbtPreviewModelAction)
    await("Whole-model preview runs without a configuration dialog") { Files.exists(directory.resolve("arguments")) }
    val arguments = Files.readString(directory.resolve("arguments"))
    assertTrue(arguments.startsWith("show\n"))
    assertTrue(arguments.contains("--target\ndev"))
    assertTrue(arguments.contains("orders"))
    assertFalse(arguments.contains("--inline"))
    await("The preview completes") { !getProject.getService(classOf[DbtRunner]).isRunning }
  }

  def testMissingCompiledSqlCanBeCanceledOrCompiledAndOpened(): Unit = withCommands { (model, directory) =>
    var answer = Messages.NO
    val previous = TestDialogManager.setTestDialog(new TestDialog {
      override def show(message: String): Int = answer
    })
    try {
      val symbol = workspace.model(model).get
      DbtCompiledSql.show(workspace, symbol, compare = false)
      assertFalse(Files.exists(directory.resolve("arguments")))
      answer = Messages.YES
      DbtCompiledSql.show(workspace, symbol, compare = false)
      await("Compile and open displays the generated SQL", 30) {
        FileEditorManager.getInstance(getProject).getOpenFiles.exists(file =>
          com.intellij.openapi.fileEditor.FileDocumentManager.getInstance.getDocument(file).getText == "select 42 as id")
      }
      val arguments = Files.readString(directory.resolve("arguments"))
      assertTrue(arguments.startsWith("compile\n"))
      assertTrue(arguments.contains("--target\ndev"))
      assertTrue(arguments.contains("orders"))
    } finally TestDialogManager.setTestDialog(previous)
  }
}
