package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project.{DbtProjectSettings, DbtSettingsState}
import com.google.gson.JsonParser
import com.intellij.codeHighlighting.Pass
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.mock.Mock
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.{ApplicationManager, ModalityState}
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.{RegisterToolWindowTask, ToolWindowManager}
import com.intellij.testFramework.{PlatformTestUtil, PsiTestUtil, ServiceContainerUtil}
import com.intellij.testFramework.fixtures.{IdeaTestFixtureFactory, TempDirTestFixture}
import org.junit.Assert._
import java.awt.{Component, Container}
import java.nio.file.{Files, Paths}
import javax.swing.JButton
import scala.jdk.CollectionConverters._

/** Exercise the Swing start button and daemon invalidation rather than forcing a new resolver/highlighting run. */
final class DbtWorkspaceUiTest extends DbtCodeInsightTestCase {
  private var ui: Disposable = _
  override protected def tearDown(): Unit = try {
    if (ui != null) Disposer.dispose(ui)
    DbtProjectSettings.get(getProject).loadState(new DbtSettingsState)
  } finally super.tearDown()
  override protected def createTempDirTestFixture(): TempDirTestFixture = IdeaTestFixtureFactory.getFixtureFactory.createTempDirTestFixture()

  private def setupEditor(): Unit = {
    ui = Disposer.newDisposable()
    Disposer.register(getTestRootDisposable, ui)
    val config = project()
    PsiTestUtil.addContentRoot(getModule, config.getVirtualFile.getParent)
    configure("models/report.sql", "select * from {{ ref('mis<caret>sing') }}")
    ServiceContainerUtil.replaceService(getProject, classOf[FileEditorManager], new Mock.MyFileEditorManager {
      override def getSelectedTextEditor: com.intellij.openapi.editor.Editor = myFixture.getEditor
      override def getOpenFiles: Array[com.intellij.openapi.vfs.VirtualFile] = Array(myFixture.getFile.getVirtualFile)
      override def getSelectedFiles: Array[com.intellij.openapi.vfs.VirtualFile] = getOpenFiles
    }, ui)
  }

  private def await(message: String)(condition: => Boolean): Unit =
    PlatformTestUtil.waitWithEventsDispatching(message, () => condition, 15)

  def testRunButtonSavesTheEditorAndLaunchesDbt(): Unit = {
    setupEditor()
    val runner = new DbtRunner(getProject)
    Disposer.register(ui, runner)
    ServiceContainerUtil.replaceService(getProject, classOf[DbtRunner], runner, ui)
    val directory = myFixture.getFile.getVirtualFile.getParent.getParent.getPath
    val executable = add("fake-dbt", """#!/usr/bin/python3
import json,pathlib,sys
pathlib.Path('invocation.json').write_text(json.dumps({'args':sys.argv[1:],'saved':pathlib.Path('models/report.sql').read_text()}))
print('started from the Run button',flush=True)
""").getVirtualFile
    assertTrue(Paths.get(executable.getPath).toFile.setExecutable(true))
    val options = DbtProjectSettings.get(getProject).options(directory)
    options.executable = executable.getPath; options.notifications = false
    val manager = ToolWindowManager.getInstance(getProject)
    val toolWindow = manager.registerToolWindow(RegisterToolWindowTask.notClosable("dbt-ui-test"))
    try {
      val panel = new DbtToolWindowPanel(getProject, toolWindow)
      Disposer.register(ui, panel)
      def children(component: Component): Vector[Component] = Vector(component) ++ (component match {
        case container: Container => container.getComponents.toVector.flatMap(children)
        case _ => Vector.empty
      })
      val run = children(panel.runnerPanel).collectFirst { case button: JButton if button.getText == "Run" => button }.get
      panel.refresh(follow = true)
      await("Current model enables Run")(run.isEnabled)
      val sql = "select 1 -- unsaved editor contents\n"
      WriteCommandAction.runWriteCommandAction(getProject, new Runnable {
        override def run(): Unit = myFixture.getEditor.getDocument.setText(sql)
      })
      ApplicationManager.getApplication.invokeLater(() => run.doClick(), ModalityState.nonModal())
      val invocation = Paths.get(directory, "invocation.json")
      await("Run button launches the fake dbt executable")(Files.isRegularFile(invocation))
      val result = JsonParser.parseString(Files.readString(invocation)).getAsJsonObject
      assertEquals(sql, result.get("saved").getAsString)
      assertTrue(result.getAsJsonArray("args").asScala.exists(_.getAsString == "fqn:analytics.report"))
      await("Run returns to idle")(!getProject.getService(classOf[DbtRunner]).isRunning)
    } finally manager.unregisterToolWindow("dbt-ui-test")
  }

  def testAddingAReferencedDefinitionInvalidatesOpenEditorHighlighting(): Unit = {
    setupEditor()
    myFixture.enableInspections(new DbtUnresolvedReferenceInspection)
    assertTrue(myFixture.doHighlighting().asScala.exists(h => Option(h.getDescription).contains("Unresolved ref: 'missing'")))
    val document = myFixture.getEditor.getDocument
    val stamp = document.getModificationStamp
    val daemon = com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(getProject).asInstanceOf[DaemonCodeAnalyzerImpl]
    val status = daemon.getFileStatusMap
    // Pending setup changes must settle before testing a new change in another file.
    val quiet = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(500)
    PlatformTestUtil.waitWithEventsDispatching("Initial refresh settles", () => System.nanoTime() >= quiet, 2)
    status.markFileUpToDate(document, Pass.EXTERNAL_TOOLS)
    assertNull(status.getFileDirtyScope(document, myFixture.getFile, Pass.EXTERNAL_TOOLS))
    add("models/missing.sql", "select 1")
    await("Adding a definition invalidates the unchanged referencing editor")(
      status.getFileDirtyScope(document, myFixture.getFile, Pass.EXTERNAL_TOOLS) != null)
    assertEquals(stamp, document.getModificationStamp)
    assertFalse(myFixture.doHighlighting().asScala.exists(h => Option(h.getDescription).contains("Unresolved ref: 'missing'")))
  }
}
