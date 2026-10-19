package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project.{DbtProjectSettings, DbtSettingsState}
import com.intellij.mock.Mock
import com.intellij.openapi.Disposable
import com.intellij.openapi.fileEditor.{FileEditorManager, FileEditorManagerListener}
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.{PlatformTestUtil, ServiceContainerUtil}
import com.intellij.ui.jcef.JBCefApp
import org.junit.Assert._
import javax.swing.{JScrollPane, JTextArea}

final class DbtLineageStartupTest extends DbtCodeInsightTestCase {
  private var ui: Disposable = _
  override protected def tearDown(): Unit = try {
    if (ui != null) Disposer.dispose(ui)
    DbtProjectSettings.get(getProject).loadState(new DbtSettingsState)
  } finally super.tearDown()

  private def status(panel: DbtToolWindowPanel): String = panel.statusPanel.getComponent(0)
    .asInstanceOf[JScrollPane].getViewport.getView.asInstanceOf[JTextArea].getText

  private def await(message: String)(condition: => Boolean): Unit =
    PlatformTestUtil.waitWithEventsDispatching(message, () => condition, 15)

  private def setupRestoringEditor(): (DbtToolWindowPanel, VirtualFile => Unit, VirtualFile) = {
    ui = Disposer.newDisposable()
    Disposer.register(getTestRootDisposable, ui)
    project()
    add("models/events.sql", "select 1")
    val model = add("models/orders.sql", "select * from {{ ref('events') }}").getVirtualFile
    add("models/report.sql", "select * from {{ ref('orders') }}")
    add("models/unrelated.sql", "select 2")
    var selected = Option.empty[VirtualFile]
    ServiceContainerUtil.replaceService(getProject, classOf[FileEditorManager], new Mock.MyFileEditorManager {
      override def getProject: com.intellij.openapi.project.Project = DbtLineageStartupTest.this.getProject
      override def getComposite(file: VirtualFile): com.intellij.openapi.fileEditor.impl.EditorComposite = null
      override def getOpenFiles: Array[VirtualFile] = selected.toArray
      override def getSelectedFiles: Array[VirtualFile] = selected.toArray
    }, ui)
    val runner = new DbtRunner(getProject)
    Disposer.register(ui, runner)
    ServiceContainerUtil.replaceService(getProject, classOf[DbtRunner], runner, ui)
    // Keep graph refresh tests independent of native browser availability and its ready callback.
    val testMode = Registry.get("ide.browser.jcef.testMode.enabled")
    val enabled = Registry.get("ide.browser.jcef.enabled")
    val wasTestMode = testMode.asBoolean(); val wasEnabled = enabled.asBoolean()
    val panel = try {
      testMode.setValue(true); enabled.setValue(false)
      new DbtToolWindowPanel(getProject)
    } finally {
      enabled.setValue(wasEnabled)
      JBCefApp.isSupported() // Restore the cached support result while test mode still forces a fresh check.
      testMode.setValue(wasTestMode)
    }
    Disposer.register(ui, panel)
    panel.refresh(follow = true)
    await("Initial refresh precedes editor restoration")(status(panel).contains("Lineage focus: None") && status(panel).contains("Resources: 4"))
    (panel, file => selected = Option(file), model)
  }

  def testBackgroundRefreshRetainsInitialModelFocusWhenItReplacesAFollowRequest(): Unit = {
    val (panel, select, model) = setupRestoringEditor()
    select(model)
    panel.refresh(follow = true)
    panel.refresh(follow = false)
    await("Restored model reaches the panel")(status(panel).contains("Lineage focus: analytics.orders"))
    assertTrue("Show the model's three connected resources, excluding the unrelated model", status(panel).contains("Resources: 3"))
    val unrelated = model.getParent.findChild("unrelated.sql")
    select(unrelated)
    panel.refresh(follow = false)
    await("Background refresh sees the new editor")(status(panel).contains("Lineage focus: analytics.unrelated"))
    assertTrue("An established graph focus survives background refreshes", status(panel).contains("Resources: 3"))
  }

  def testRestoredEditorOpeningInitializesFocusWithoutASelectionChange(): Unit = {
    val (panel, select, model) = setupRestoringEditor()
    select(model)
    getProject.getMessageBus.syncPublisher(FileEditorManagerListener.FILE_EDITOR_MANAGER)
      .fileOpened(FileEditorManager.getInstance(getProject), model)
    await("Opening the restored editor refreshes the panel")(status(panel).contains("Lineage focus: analytics.orders"))
    assertTrue(status(panel).contains("Resources: 3"))
  }
  def testSelectionShortcutsReviewCopyAndEditsInvalidateTheDisplayedPreview(): Unit = {
    val (panel, select, model) = setupRestoringEditor()
    select(model)
    panel.refresh(follow = true)
    await("Model becomes the shortcut context")(status(panel).contains("Lineage focus: analytics.orders"))
    def components(c: java.awt.Component): Vector[java.awt.Component] = Vector(c) ++ (c match {
      case container: java.awt.Container => container.getComponents.toVector.flatMap(components)
      case _ => Vector.empty
    })
    def button(label: String) = components(panel.runnerPanel).collectFirst {
      case b: javax.swing.JButton if b.getText == label => b
    }.get
    val fields = components(panel.runnerPanel)
    val expression = fields.collectFirst { case f: javax.swing.JTextField if f.getName == "dbt.selection" => f }.get
    val exclude = fields.collectFirst { case f: javax.swing.JTextField if f.getName == "dbt.exclude" => f }.get
    val review = fields.collectFirst { case a: JTextArea if a.getName == "dbt.selectionReview" => a }.get
    button("Both").doClick()
    await("Review displays exact graph matches")(review.getText.contains("Matched resources: 3"))
    assertEquals("+fqn:analytics.orders+", expression.getText)
    assertFalse(review.getText.contains("model.analytics.unrelated"))
    assertTrue(button("Copy command").isEnabled)
    button("Copy command").doClick()
    val copied: String = com.intellij.openapi.ide.CopyPasteManager.getInstance.getContents(java.awt.datatransfer.DataFlavor.stringFlavor)
    assertTrue(copied.contains("fqn:analytics.orders"))
    exclude.setText("events")
    assertFalse(button("Copy command").isEnabled)
    assertTrue(review.getText.contains("Review selection again"))
    button("Review selection").doClick()
    await("Exclusion changes preview")(review.getText.contains("Matched resources: 2"))
    val target = fields.collectFirst { case c: javax.swing.JComboBox[_] if c.getName == "dbt.target" => c }.get
    target.getEditor.setItem("qa")
    assertTrue(review.getText.contains("Review selection again"))
    assertFalse(button("Copy command").isEnabled)
    button("Review selection").doClick()
    await("Target is included in refreshed preview")(review.getText.contains("target: qa"))
    add("target/manifest.json", "{}")
    panel.refresh(follow = false)
    await("Generated artifact becomes visible without clearing the review")(status(panel).contains("Manifest:"))
    assertTrue("dbt output artifacts do not invalidate an authored selection", button("Copy command").isEnabled)
    com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(getProject, new Runnable {
      override def run(): Unit = com.intellij.psi.PsiDocumentManager.getInstance(getProject).getDocument(
        com.intellij.psi.PsiManager.getInstance(getProject).findFile(model)).insertString(0, "-- changed\n")
    })
    await("Source edit invalidates selection preview")(review.getText.contains("Review selection again"))
  }

  def testEditingASelectionWhileItsCommandIsPreparingPreventsExecution(): Unit = {
    val (panel, select, model) = setupRestoringEditor()
    select(model); panel.refresh(follow = true)
    await("Model context loaded")(status(panel).contains("Lineage focus: analytics.orders"))
    def components(c: java.awt.Component): Vector[java.awt.Component] = Vector(c) ++ (c match {
      case container: java.awt.Container => container.getComponents.toVector.flatMap(components)
      case _ => Vector.empty
    })
    val fields = components(panel.runnerPanel)
    val button = fields.collectFirst { case b: javax.swing.JButton if b.getText == "Build" => b }.get
    val expression = fields.collectFirst { case f: javax.swing.JTextField if f.getName == "dbt.selection" => f }.get
    val context = fields.collectFirst { case l: javax.swing.JLabel if l.getName == "dbt.context" => l }.get
    val events = scala.collection.mutable.ArrayBuffer.empty[DbtRunnerEvent]
    getProject.getService(classOf[DbtRunner]).listen(ui, events += _)
    button.doClick()
    expression.setText("events")
    await("Stale command is rejected")(context.getText.contains("changed while preparing"))
    assertFalse(events.exists(_.running))
  }

  def testBackgroundLineageFilteringCannotReplaceTheRunnerSelection(): Unit = {
    val (panel, select, model) = setupRestoringEditor()
    select(model); panel.refresh(follow = true)
    await("Model context loaded")(status(panel).contains("Lineage focus: analytics.orders"))
    panel.handleMessage("""{"type":"filter","id":"orders","value":"","shared":true}""")
    val options = DbtProjectSettings.get(getProject).getState.projects.values().iterator().next()
    assertEquals("orders", options.selection)
    panel.handleMessage("""{"type":"filter","id":"events","value":""}""")
    assertEquals("A background filter response cannot overwrite a newer Runner expression", "orders", options.selection)
    panel.handleMessage("""{"type":"filter","id":"","value":"","shared":true}""")
    assertEquals("Explicit Clear filter also clears the Runner expression", "", options.selection)
  }

}
