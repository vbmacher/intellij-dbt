package com.github.vbmacher.intellij.dbt.ide

import com.intellij.execution.{ExecutionListener, ExecutionManager, RunManager}
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.{RunContentDescriptor, RunContentManager}
import com.intellij.openapi.actionSystem.{ActionManager, CommonDataKeys}
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.TestActionEvent
import org.junit.Assert._

import java.nio.file.Files
import scala.jdk.CollectionConverters._

final class DbtCompileProjectActionTest extends DbtTestCase {
  def testProjectContextChoosesTheNearestOwnerAndOtherwiseRequiresAChoice(): Unit = {
    project()
    val local = add("models/orders.sql", "select 1")
    val first = workspace.roots.head.directory.getPath
    assertEquals(Vector(first), DbtCompileProjectAction.projects(workspace, None))
    project(path = "other/")
    val nested = add("other/models/orders.sql", "select 2")
    val second = workspace.root(nested).get.directory.getPath
    assertEquals(Vector(first, second).sorted, DbtCompileProjectAction.projects(workspace, None))
    assertEquals(Vector(first), DbtCompileProjectAction.projects(workspace, Some(local.getVirtualFile)))
    assertEquals(Vector(second), DbtCompileProjectAction.projects(workspace, Some(nested.getVirtualFile)))
  }

  def testInstalledPackagesCompileThroughTheirConsumerAndAppearOnlyOnce(): Unit = {
    project()
    val consumer = workspace.roots.head.directory.getPath
    project(path = "dbt_packages/util/")
    val model = add("dbt_packages/util/models/example.sql", "select 1")
    assertEquals(Vector(consumer), DbtCompileProjectAction.projects(workspace, Some(model.getVirtualFile)))
    assertEquals(Vector(consumer), DbtCompileProjectAction.projects(workspace, None))
  }

  def testActionStartsWholeProjectCompileWithoutOpeningAConfigurationEditor(): Unit = {
    project()
    val model = configure("models/orders.sql", "select 1<caret>")
    val directory = tempDirectory("dbt compile action ")
    Files.writeString(directory.resolve("dbt_project.yml"), "name: analytics\n")
    val script = directory.resolve("wrapper.py")
    Files.writeString(script,
      """import pathlib,sys
pathlib.Path('arguments').write_text('\n'.join(sys.argv[1:]))
""")
    configureSettings { options =>
      options.projectRoot = directory.toString
      options.executable = "/usr/bin/python3"
      options.executableArguments = "\"" + script + "\" 'wrapper argument'"
      options.target = "dev"
      options.notifications = false
      options.selection = "tag:unrelated"
      options.exclude = "orders"
    }
    val context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, getProject)
      .add(CommonDataKeys.VIRTUAL_FILE, model.getVirtualFile).build()
    val action = ActionManager.getInstance().getAction("Dbt.CompileProject")
    assertNotNull(action)
    val contents = RunContentManager.getInstance(getProject)
    var sessions = Vector.empty[RunContentDescriptor]
    getProject.getMessageBus.connect(getTestRootDisposable).subscribe(ExecutionManager.EXECUTION_TOPIC, new ExecutionListener {
      override def processStartScheduled(executorId: String, environment: ExecutionEnvironment): Unit =
        environment.setCallback((descriptor: RunContentDescriptor) => sessions :+= descriptor)
    })
    try {
      action.actionPerformed(TestActionEvent.createTestEvent(action, context))
      await("Compile selects a temporary Run configuration without a dialog") {
        Option(RunManager.getInstance(getProject).getSelectedConfiguration).exists(
          _.getConfiguration.asInstanceOf[DbtRunConfiguration].action == "Compile")
      }
      val selected = RunManager.getInstance(getProject).getSelectedConfiguration.getConfiguration.asInstanceOf[DbtRunConfiguration]
      assertEquals(directory.toString, selected.captured.get.directory)
      await("Compile starts directly and finishes") {
        Files.exists(directory.resolve("arguments")) && !getProject.getService(classOf[DbtRunner]).isRunning &&
          sessions.exists(_.getProcessHandler.isProcessTerminated)
      }
      assertEquals(Vector("wrapper argument", "compile", "--target", "dev", "--use-colors"),
        Files.readAllLines(directory.resolve("arguments")).asScala.toVector)
      val configuration = RunManager.getInstance(getProject).getSelectedConfiguration.getConfiguration.asInstanceOf[DbtRunConfiguration]
      assertEquals("Compile", configuration.action)
      assertEquals("", configuration.scope)
      assertTrue(configuration.selectors.isEmpty)
      assertEquals(DbtCommandScope.All, configuration.captured.get.request.scope)
    } finally sessions.foreach { descriptor =>
      if (!contents.removeRunContent(DefaultRunExecutor.getRunExecutorInstance, descriptor)) Disposer.dispose(descriptor)
    }
  }
}
