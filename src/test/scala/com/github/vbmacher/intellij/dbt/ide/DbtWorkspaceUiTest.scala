package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project.{DbtProjectSettings, DbtSettingsState, DbtWorkspace}
import com.google.gson.JsonParser
import com.intellij.codeHighlighting.Pass
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.codeInsight.multiverse.CodeInsightContexts
import com.intellij.mock.Mock
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.{ApplicationManager, ModalityState}
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.options.ex.ConfigurableCardPanel
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.{RegisterToolWindowTask, ToolWindowManager}
import com.intellij.testFramework.{PlatformTestUtil, PsiTestUtil, ServiceContainerUtil}
import com.intellij.testFramework.fixtures.{IdeaTestFixtureFactory, TempDirTestFixture}
import org.junit.Assert._
import java.awt.{Component, Container}
import java.nio.file.{Files, Paths}
import javax.swing.{JButton, JScrollPane, JTextField, SwingUtilities}
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

  private def children(component: Component): Vector[Component] = Vector(component) ++ (component match {
    case container: Container => container.getComponents.toVector.flatMap(children)
    case _ => Vector.empty
  })

  def testSettingsFieldsFitTheSettingsViewportWithLongValues(): Unit = {
    setupEditor()
    val directory = myFixture.getFile.getVirtualFile.getParent.getParent.getPath
    val options = DbtProjectSettings.get(getProject).options(directory)
    options.executable = "/long executable path/" * 30
    options.executableArguments = "long argument " * 100
    options.projectRoot = "/long project path/" * 30
    val configurable = new DbtSettingsConfigurable(getProject)
    val scroll = ConfigurableCardPanel.createConfigurableComponent(configurable).asInstanceOf[JScrollPane]
    try {
      val form = scroll.getViewport.getView.asInstanceOf[Container]
      val fields = form.getComponents.toVector.flatMap {
        case field: JTextField => Vector(field)
        case browse: TextFieldWithBrowseButton => Vector(browse.getTextField)
        case _ => Vector.empty
      }
      assertEquals(6, fields.size)
      for (width <- Vector(800, 500, 1000)) {
        scroll.setSize(width, 300); scroll.doLayout(); scroll.getViewport.doLayout()
        form.doLayout(); form.getComponents.collect { case container: Container => container }.foreach(_.doLayout())
        assertFalse("No horizontal scrollbar at width " + width, scroll.getHorizontalScrollBar.isVisible)
        assertEquals(scroll.getViewport.getExtentSize.width, scroll.getViewport.getView.getWidth)
        fields.foreach { field =>
          assertTrue("Text fields retain usable space", field.getWidth > 50)
          assertTrue("Text fields fit inside the viewport", SwingUtilities.convertPoint(field, field.getWidth, 0, form).x <= scroll.getViewport.getExtentSize.width)
        }
      }
      assertFalse("Resizing keeps saved values intact", configurable.isModified)
    } finally configurable.disposeUIResources()
  }

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
      val panel = new DbtToolWindowPanel(getProject)
      Disposer.register(ui, panel)
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
    } finally toolWindow.remove()
  }

  def testRunnerEditsSharedPrefixAndGeneratesRenderableDocs(): Unit = {
    setupEditor()
    val editorOptions = com.intellij.openapi.editor.ex.EditorSettingsExternalizable.getInstance()
    val ensureNewline = editorOptions.isEnsureNewLineAtEOF
    editorOptions.setEnsureNewLineAtEOF(true)
    Disposer.register(ui, () => editorOptions.setEnsureNewLineAtEOF(ensureNewline))
    val config = DbtWorkspace.get(getProject).roots.head.config.getContainingFile
    edit(config, config.getText.stripSuffix("\n"))
    val runner = new DbtRunner(getProject)
    Disposer.register(ui, runner)
    ServiceContainerUtil.replaceService(getProject, classOf[DbtRunner], runner, ui)
    val directory = myFixture.getFile.getVirtualFile.getParent.getParent.getPath
    val executable = add("fake-dbt", """#!/usr/bin/python3
import json,os,pathlib,sys
target=pathlib.Path(os.environ['DBT_TARGET_PATH'])
target.mkdir(parents=True,exist_ok=True)
target.joinpath('manifest.json').write_text(json.dumps({'metadata':{'dbt_schema_version':'https://schemas.getdbt.com/dbt/manifest/v12.json'},'nodes':{'model.analytics.report':{'unique_id':'model.analytics.report','resource_type':'model','name':'report','package_name':'analytics','original_file_path':'models/report.sql','description':'Generated for '+sys.argv[1]}}}))
pathlib.Path('invocation.json').write_text(json.dumps(sys.argv[1:]))
""").getVirtualFile
    assertTrue(Paths.get(executable.getPath).toFile.setExecutable(true))
    val settings = DbtProjectSettings.get(getProject)
    val options = settings.options(directory)
    options.executable = executable.getPath; options.executableArguments = "initial"; options.notifications = false
    val panel = new DbtToolWindowPanel(getProject)
    Disposer.register(ui, panel)
    val controls = children(panel.runnerPanel)
    val arguments = controls.collectFirst { case field: JTextField if field.getName == "dbt.executableArguments" => field }.get
    val generate = controls.collectFirst { case button: JButton if button.getText == "Generate Docs" => button }.get
    panel.refresh(follow = true)
    await("Runner loads the Workspace prefix")(arguments.isEnabled && arguments.getText == "initial")
    options.executableArguments = "updated-in-settings"; settings.changed()
    await("Workspace changes reach Runner")(arguments.getText == "updated-in-settings")
    arguments.setText("'analytics context'")
    // Launch immediately, without Enter or a focus-lost event committing the edit first.
    ApplicationManager.getApplication.invokeLater(() => generate.doClick(), ModalityState.nonModal())
    val invocation = Paths.get(directory, "invocation.json")
    await("Generate Docs launches the configured executable")(Files.isRegularFile(invocation))
    assertEquals(Vector("analytics context", "docs", "generate"), JsonParser.parseString(Files.readString(invocation)).getAsJsonArray.asScala.map(_.getAsString).take(3).toVector)
    assertEquals("'analytics context'", settings.options(directory).executableArguments)
    await("Generated artifacts refresh documentation") {
      val workspace = DbtWorkspace.get(getProject)
      workspace.codeSymbols(myFixture.getFile).find(_.kind == "model").exists(symbol =>
        DbtDocumentation.render(workspace, symbol).contains("Generated for analytics context"))
    }
    await("Docs command returns to idle")(!runner.isRunning)
    assertTrue("Save actions must be committed before capturing the command's artifact context",
      com.github.vbmacher.intellij.dbt.project.DbtArtifactContext.matches(DbtWorkspace.get(getProject).roots.head))
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
    status.markFileUpToDate(document, CodeInsightContexts.anyContext(), Pass.EXTERNAL_TOOLS, null)
    assertNull(status.getFileDirtyScope(document, myFixture.getFile, Pass.EXTERNAL_TOOLS))
    add("models/missing.sql", "select 1")
    await("Adding a definition invalidates the unchanged referencing editor")(
      status.getFileDirtyScope(document, myFixture.getFile, Pass.EXTERNAL_TOOLS) != null)
    assertEquals(stamp, document.getModificationStamp)
    assertFalse(myFixture.doHighlighting().asScala.exists(h => Option(h.getDescription).contains("Unresolved ref: 'missing'")))
  }
}
