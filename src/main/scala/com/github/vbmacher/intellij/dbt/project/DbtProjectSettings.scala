package com.github.vbmacher.intellij.dbt.project

import com.intellij.openapi.components.{PersistentStateComponent, Service, State, Storage}
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.util.messages.Topic
import scala.beans.BeanProperty

final class DbtRunOptions {
  @BeanProperty var executable: String = "dbt"
  @BeanProperty var executableArguments: String = ""
  @BeanProperty var projectRoot: String = ""
  @BeanProperty var profilesDirectory: String = ""
  @BeanProperty var artifactDirectory: String = "target"
  @BeanProperty var target: String = ""
  @BeanProperty var previewLimit: Int = 10
  @BeanProperty var colors: Boolean = true
  @BeanProperty var notifications: Boolean = true
  @BeanProperty var selection: String = ""
  @BeanProperty var exclude: String = ""

  def values: Vector[Any] =
    Vector(executable, executableArguments, projectRoot, profilesDirectory, artifactDirectory, target, previewLimit, colors, notifications,
      selection, exclude)

  def copy(): DbtRunOptions = {
    val c = new DbtRunOptions
    c.executable = executable
    c.executableArguments = executableArguments
    c.projectRoot = projectRoot
    c.profilesDirectory = profilesDirectory
    c.artifactDirectory = artifactDirectory
    c.target = target
    c.previewLimit = previewLimit
    c.colors = colors
    c.notifications = notifications
    c.selection = selection
    c.exclude = exclude
    c
  }
}

final class DbtSettingsState {
  @BeanProperty var projects: java.util.Map[String, DbtRunOptions] = new java.util.TreeMap[String, DbtRunOptions]()
  @BeanProperty var runnerSelection: String = ""
  @BeanProperty var runnerRoot: String = ""
  @BeanProperty var upstreamDepth: Int = 5
  @BeanProperty var downstreamDepth: Int = 5
  @BeanProperty var direction: String = "LR"
  @BeanProperty var edgeStyle: String = "round-taxi"
  @BeanProperty var exposures: Boolean = true
  @BeanProperty var docsVisible: Boolean = true
  @BeanProperty var docsWidth: Int = 360

  def upstream: Int = DbtSettingsState.depth(upstreamDepth)
  def downstream: Int = DbtSettingsState.depth(downstreamDepth)
}

private[dbt] object DbtSettingsState {
  def depth(value: Int): Int = value.max(1).min(20)
}

@Service(Array(Service.Level.PROJECT))
@State(name = "DbtWorkspaceSettings", storages = Array(new Storage("dbt-workspace.xml")))
final class DbtProjectSettings(project: Project) extends SimpleModificationTracker with PersistentStateComponent[DbtSettingsState] {
  private var state = new DbtSettingsState
  override def getState: DbtSettingsState = state
  override def loadState(value: DbtSettingsState): Unit = { state = value; changed() }
  def changed(): Unit = { incModificationCount(); project.getMessageBus.syncPublisher(DbtProjectSettings.Changed).settingsChanged() }
  def options(root: String): DbtRunOptions = synchronized {
    state.projects.computeIfAbsent(root, _ => new DbtRunOptions)
  }
}

private[dbt] object DbtProjectSettings {
  val Changed: Topic[DbtSettingsListener] = Topic.create("dbt settings changed", classOf[DbtSettingsListener])
  def get(project: Project): DbtProjectSettings = project.getService(classOf[DbtProjectSettings])
}

private[dbt] trait DbtSettingsListener { def settingsChanged(): Unit }
