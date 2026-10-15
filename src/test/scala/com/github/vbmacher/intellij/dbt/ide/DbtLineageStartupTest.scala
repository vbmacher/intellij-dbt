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
    await("Initial refresh precedes editor restoration")(status(panel).contains("Model: None") && status(panel).contains("Resources: 4"))
    (panel, file => selected = Option(file), model)
  }

  def testBackgroundRefreshRetainsInitialModelFocusWhenItReplacesAFollowRequest(): Unit = {
    val (panel, select, model) = setupRestoringEditor()
    select(model)
    panel.refresh(follow = true)
    panel.refresh(follow = false)
    await("Restored model reaches the panel")(status(panel).contains("Model: analytics.orders"))
    assertTrue("Show the model's three connected resources, excluding the unrelated model", status(panel).contains("Resources: 3"))
    val unrelated = model.getParent.findChild("unrelated.sql")
    select(unrelated)
    panel.refresh(follow = false)
    await("Background refresh sees the new editor")(status(panel).contains("Model: analytics.unrelated"))
    assertTrue("An established graph focus survives background refreshes", status(panel).contains("Resources: 3"))
  }

  def testRestoredEditorOpeningInitializesFocusWithoutASelectionChange(): Unit = {
    val (panel, select, model) = setupRestoringEditor()
    select(model)
    getProject.getMessageBus.syncPublisher(FileEditorManagerListener.FILE_EDITOR_MANAGER)
      .fileOpened(FileEditorManager.getInstance(getProject), model)
    await("Opening the restored editor refreshes the panel")(status(panel).contains("Model: analytics.orders"))
    assertTrue(status(panel).contains("Resources: 3"))
  }
}
