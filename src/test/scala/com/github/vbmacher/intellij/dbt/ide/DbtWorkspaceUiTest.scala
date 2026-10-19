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
import com.intellij.openapi.fileEditor.{FileEditorManager, FileEditorManagerEvent, FileEditorManagerListener}
import com.intellij.openapi.options.ex.ConfigurableCardPanel
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.{RegisterToolWindowTask, ToolWindowManager}
import com.intellij.testFramework.{PlatformTestUtil, PsiTestUtil, ServiceContainerUtil}
import com.intellij.testFramework.fixtures.{IdeaTestFixtureFactory, TempDirTestFixture}
import org.junit.Assert._
import java.awt.{Component, Container}
import java.nio.file.{Files, Paths}
import javax.swing.{JButton, JCheckBox, JComboBox, JScrollPane, JTextArea, JTextField, SwingUtilities}
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
      override def getProject: com.intellij.openapi.project.Project = DbtWorkspaceUiTest.this.getProject
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

  private def picker(panel: DbtToolWindowPanel): TextFieldWithBrowseButton = children(panel.runnerPanel).collectFirst {
    case field: TextFieldWithBrowseButton if field.getName == "dbt.runSelection" => field
  }.get

  private def button(panel: DbtToolWindowPanel, name: String): JButton = children(panel.runnerPanel).collectFirst {
    case button: JButton if button.getText == name => button
  }.get

  private def choose(panel: DbtToolWindowPanel, path: String): Unit = {
    picker(panel).setText(path)
    await("The explicit run selection enables commands")(button(panel, "Run").isEnabled)
  }

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
      assertEquals(8, fields.size)
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

  def testRunAndPreviewUseTheChosenModelAndSaveDocuments(): Unit = {
    setupEditor()
    val chosen = add("models/chosen.sql", "select 42")
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
      choose(panel, chosen.getVirtualFile.getPath)
      val sql = "select 1 -- unsaved editor contents\n"
      WriteCommandAction.runWriteCommandAction(getProject, new Runnable {
        override def run(): Unit = myFixture.getEditor.getDocument.setText(sql)
      })
      ApplicationManager.getApplication.invokeLater(() => run.doClick(), ModalityState.nonModal())
      val invocation = Paths.get(directory, "invocation.json")
      await("Run button launches the fake dbt executable")(Files.isRegularFile(invocation))
      val result = JsonParser.parseString(Files.readString(invocation)).getAsJsonObject
      assertEquals(sql, result.get("saved").getAsString)
      assertTrue(result.getAsJsonArray("args").asScala.exists(_.getAsString == "fqn:analytics.chosen"))
      await("Run returns to idle")(!getProject.getService(classOf[DbtRunner]).isRunning)
      Files.delete(invocation)
      myFixture.getEditor.getSelectionModel.setSelection(0, myFixture.getEditor.getDocument.getTextLength)
      ApplicationManager.getApplication.invokeLater(() => button(panel, "Preview").doClick(), ModalityState.nonModal())
      await("Preview launches the explicitly chosen model")(Files.isRegularFile(invocation))
      val preview = JsonParser.parseString(Files.readString(invocation)).getAsJsonObject.getAsJsonArray("args").asScala.map(_.getAsString).toVector
      assertTrue(preview.contains("fqn:analytics.chosen"))
      assertFalse(preview.contains("--inline"))
      await("Preview returns to idle")(!runner.isRunning)
    } finally toolWindow.remove()
  }

  def testFailedDbtLsRefreshDiscardsThePreviousCliReview(): Unit = {
    setupEditor()
    val chosen = add("models/chosen.sql", "select 42")
    val runner = new DbtRunner(getProject)
    Disposer.register(ui, runner)
    ServiceContainerUtil.replaceService(getProject, classOf[DbtRunner], runner, ui)
    val directory = myFixture.getFile.getVirtualFile.getParent.getParent.getPath
    val executable = add("fake-dbt", """#!/usr/bin/python3
import json,os,sys
if os.path.exists('fail-ls'): sys.exit(2)
if 'ls' in sys.argv: print(json.dumps({'unique_id':'model.analytics.chosen'}),flush=True)
""").getVirtualFile
    assertTrue(Paths.get(executable.getPath).toFile.setExecutable(true))
    val options = DbtProjectSettings.get(getProject).options(directory)
    options.executable = executable.getPath; options.notifications = false
    val panel = new DbtToolWindowPanel(getProject)
    Disposer.register(ui, panel)
    panel.refresh(follow = true)
    choose(panel, chosen.getVirtualFile.getPath)
    val review = children(panel.runnerPanel).collectFirst { case a: JTextArea if a.getName == "dbt.selectionReview" => a }.get
    ApplicationManager.getApplication.invokeLater(() => button(panel, "Refresh with dbt ls").doClick(), ModalityState.nonModal())
    await("dbt ls review succeeds")(review.getText.contains("model.analytics.chosen") && button(panel, "Copy command").isEnabled)
    Files.writeString(Paths.get(directory, "fail-ls"), "")
    ApplicationManager.getApplication.invokeLater(() => button(panel, "Refresh with dbt ls").doClick(), ModalityState.nonModal())
    await("Failed dbt ls replaces the review")(review.getText.contains("dbt ls did not complete") && !runner.isRunning)
    assertFalse(review.getText.contains("model.analytics.chosen"))
    assertFalse(button(panel, "Copy command").isEnabled)
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
    choose(panel, directory)
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

  def testDirectorySelectionSurvivesEditorChangesAndRunsWithoutAnOpenEditor(): Unit = {
    setupEditor()
    val runner = new DbtRunner(getProject)
    Disposer.register(ui, runner)
    ServiceContainerUtil.replaceService(getProject, classOf[DbtRunner], runner, ui)
    val model = add("models/batch/orders.sql", "{{ config(materialized='incremental') }} select 1")
    add("models/batch/nested/detail.sql", "select 2")
    val directory = DbtWorkspace.get(getProject).roots.head.directory.getPath
    val executable = add("fake-dbt", "#!/usr/bin/python3\nimport json,pathlib,sys\npathlib.Path('invocation.json').write_text(json.dumps(sys.argv[1:]))\n").getVirtualFile
    assertTrue(Paths.get(executable.getPath).toFile.setExecutable(true))
    val options = DbtProjectSettings.get(getProject).options(directory)
    options.executable = executable.getPath
    options.notifications = false
    val panel = new DbtToolWindowPanel(getProject)
    Disposer.register(ui, panel)
    panel.refresh(follow = true)
    val status = children(panel.statusPanel).collectFirst { case text: JTextArea => text }.get
    await("An empty model path enables all models")(button(panel, "Run").isEnabled)
    assertTrue(status.getText.contains("Selection: All models: analytics"))
    assertEquals("", picker(panel).getText)
    assertTrue(picker(panel).getTextField.isEditable)
    assertTrue(picker(panel).getButton.isEnabled)
    val path = model.getVirtualFile.getParent.getPath
    choose(panel, path)
    assertFalse("Folder previews require a single model", button(panel, "Preview").isEnabled)
    val refresh = children(panel.runnerPanel).collectFirst { case box: JCheckBox if box.getText == "Full Refresh" => box }.get
    assertTrue(refresh.isVisible)
    refresh.setSelected(true)
    add("other/dbt_project.yml", "name: marketing\nconfig-version: 2\n")
    val unrelated = add("other/models/unrelated.sql", "select 3").getVirtualFile
    ServiceContainerUtil.replaceService(getProject, classOf[FileEditorManager], new Mock.MyFileEditorManager {
      override def getProject: com.intellij.openapi.project.Project = DbtWorkspaceUiTest.this.getProject
      override def getSelectedTextEditor: com.intellij.openapi.editor.Editor = null
      override def getSelectedFiles: Array[com.intellij.openapi.vfs.VirtualFile] = Array(unrelated)
      override def getOpenFiles: Array[com.intellij.openapi.vfs.VirtualFile] = Array(unrelated)
    }, ui)
    val manager = FileEditorManager.getInstance(getProject)
    getProject.getMessageBus.syncPublisher(FileEditorManagerListener.FILE_EDITOR_MANAGER)
      .selectionChanged(new FileEditorManagerEvent(manager, null, null))
    panel.refresh(follow = true)
    await("Editor changes refresh lineage")(status.getText.contains("Lineage focus: marketing.unrelated"))
    assertEquals(path, picker(panel).getText)
    assertTrue("Changing editors preserves the explicit Full Refresh choice", refresh.isSelected)
    ServiceContainerUtil.replaceService(getProject, classOf[FileEditorManager], new Mock.MyFileEditorManager {
      override def getProject: com.intellij.openapi.project.Project = DbtWorkspaceUiTest.this.getProject
      override def getSelectedTextEditor: com.intellij.openapi.editor.Editor = null
      override def getSelectedFiles: Array[com.intellij.openapi.vfs.VirtualFile] = Array.empty
      override def getOpenFiles: Array[com.intellij.openapi.vfs.VirtualFile] = Array.empty
    }, ui)
    panel.refresh(follow = true)
    await("Runner remains usable after closing editors")(status.getText.contains("Lineage focus: None"))
    ApplicationManager.getApplication.invokeLater(() => button(panel, "Run").doClick(), ModalityState.nonModal())
    val invocation = Paths.get(directory, "invocation.json")
    await("Run launches the chosen folder without an editor")(Files.isRegularFile(invocation))
    val args = JsonParser.parseString(Files.readString(invocation)).getAsJsonArray.asScala.map(_.getAsString).toVector
    assertTrue(args.contains("fqn:analytics.batch.orders"))
    assertTrue(args.contains("fqn:analytics.batch.nested.detail"))
    assertTrue(args.contains("--full-refresh"))
    assertFalse(args.exists(_.contains("unrelated")))
    await("Folder run returns idle")(!getProject.getService(classOf[DbtRunner]).isRunning)
    choose(panel, directory + "/models/report.sql")
    assertTrue(button(panel, "Preview").isEnabled)
    assertFalse(refresh.isVisible)
    assertFalse(refresh.isSelected)
    choose(panel, "")
    Disposer.dispose(panel)
    val reopened = new DbtToolWindowPanel(getProject)
    Disposer.register(ui, reopened)
    reopened.refresh(follow = true)
    assertEquals("", picker(reopened).getText)
    await("Runner restores its remembered selection")(button(reopened, "Run").isEnabled)
    val restoredStatus = children(reopened.statusPanel).collectFirst { case text: JTextArea => text }.get
    assertTrue(restoredStatus.getText.contains("Selection: All models: analytics"))
  }

  def testTargetAndArgumentsRemainEditableAcrossRunnerRefreshes(): Unit = {
    setupEditor()
    val config = DbtWorkspace.get(getProject).roots.head.config.getContainingFile
    edit(config, config.getText + "\nprofile: analytics\n")
    add("profiles.yml", "analytics: {target: dev, outputs: {dev: {type: duckdb}, qa: {type: duckdb}}}\n")
    val runner = new DbtRunner(getProject)
    Disposer.register(ui, runner)
    ServiceContainerUtil.replaceService(getProject, classOf[DbtRunner], runner, ui)
    val model = myFixture.getFile.getVirtualFile
    val directory = model.getParent.getParent.getPath
    val executable = add("fake-dbt", "#!/usr/bin/python3\nimport json,pathlib,sys\npathlib.Path('invocation.json').write_text(json.dumps(sys.argv[1:]))\n").getVirtualFile
    assertTrue(Paths.get(executable.getPath).toFile.setExecutable(true))
    val settings = DbtProjectSettings.get(getProject)
    val options = settings.options(directory)
    options.executable = executable.getPath
    options.notifications = false
    val panel = new DbtToolWindowPanel(getProject)
    Disposer.register(ui, panel)
    panel.refresh(follow = true)
    val controls = children(panel.runnerPanel)
    val target = controls.collectFirst { case combo: JComboBox[_] if combo.getName == "dbt.target" => combo.asInstanceOf[JComboBox[String]] }.get
    val arguments = controls.collectFirst { case field: JTextField if field.getName == "dbt.executableArguments" => field }.get
    val status = children(panel.statusPanel).collectFirst { case text: JTextArea => text }.get
    for ((path, index) <- Vector(model.getPath, model.getParent.getPath, "", directory).zipWithIndex) {
      choose(panel, path)
      assertTrue(target.isEnabled)
      assertTrue("Targets supplied by wrappers must also be editable", target.isEditable)
      assertTrue(arguments.isEnabled)
      assertTrue((0 until target.getItemCount).map(target.getItemAt).contains("qa"))
      def layout(component: Component): Unit = component match {
        case container: Container => container.doLayout(); container.getComponents.foreach(layout)
        case _ => ()
      }
      panel.runnerPanel.setSize(1100, 600)
      layout(panel.runnerPanel)
      for (field <- Vector(target, arguments, picker(panel).getTextField)) {
        assertTrue("Runner inputs retain usable space on one row", field.getWidth > 50)
        assertTrue(SwingUtilities.convertPoint(field, field.getWidth, 0, panel.runnerPanel).x <= 1100)
      }
      val positions = Vector(target, picker(panel), arguments).map(field =>
        SwingUtilities.convertPoint(field, 0, field.getHeight / 2, panel.runnerPanel))
      assertTrue("All controls share one row: " + positions, positions.map(_.y).max - positions.map(_.y).min <= 2)
      assertEquals("Target, model, arguments appear from left to right", positions.map(_.x).sorted, positions.map(_.x))
      val targetField = target.getEditor.getEditorComponent.asInstanceOf[JTextField]
      targetField.setText("custom_" + index)
      arguments.setText(s"'scope $index'")
      val targetModel = target.getModel
      status.setText("")
      panel.refresh(follow = false)
      await("Background refresh completes while input is pending")(status.getText.nonEmpty)
      assertSame("Unchanged target choices keep the dropdown model", targetModel, target.getModel)
      assertEquals("custom_" + index, targetField.getText)
      assertEquals(s"'scope $index'", arguments.getText)
      // Run must save typed values even before Enter or focus loss commits them.
      ApplicationManager.getApplication.invokeLater(() => button(panel, "Run").doClick(), ModalityState.nonModal())
      val invocation = Paths.get(directory, "invocation.json")
      await("Run launches with the edited configuration")(Files.isRegularFile(invocation))
      val args = JsonParser.parseString(Files.readString(invocation)).getAsJsonArray.asScala.map(_.getAsString).toVector
      assertEquals(s"scope $index", args.head)
      assertEquals(path.nonEmpty && path != directory, args.contains("--select"))
      assertEquals("custom_" + index, args(args.indexOf("--target") + 1))
      assertEquals("custom_" + index, options.target)
      assertEquals(s"'scope $index'", options.executableArguments)
      await("Edited run returns idle")(!runner.isRunning)
      Files.delete(invocation)
    }
  }

  def testRunSelectionAcceptsRelativePathsAndKeepsInvalidInputForCorrection(): Unit = {
    setupEditor()
    val runner = new DbtRunner(getProject)
    Disposer.register(ui, runner)
    ServiceContainerUtil.replaceService(getProject, classOf[DbtRunner], runner, ui)
    val panel = new DbtToolWindowPanel(getProject)
    Disposer.register(ui, panel)
    val model = Paths.get(myFixture.getFile.getVirtualFile.getPath)
    val relative = Paths.get(getProject.getBasePath).relativize(model).toString
    choose(panel, relative)
    assertEquals(model.toString, DbtProjectSettings.get(getProject).getState.runnerSelection)
    assertEquals(relative, picker(panel).getText)
    assertTrue(picker(panel).getButton.getActionListeners.nonEmpty)
    val invalid = model.getParent.resolve("missing.sql").toString
    picker(panel).setText(invalid)
    val status = children(panel.statusPanel).collectFirst { case text: JTextArea => text }.get
    await("Invalid model cannot launch commands")(status.getText.contains("Selection: None"))
    assertFalse(button(panel, "Run").isEnabled)
    assertEquals(invalid, picker(panel).getText)
    choose(panel, model.getParent.toString)
    assertFalse(button(panel, "Preview").isEnabled)
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
