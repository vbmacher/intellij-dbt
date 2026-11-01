package com.github.vbmacher.intellij.dbt.ide

import com.intellij.execution.services.{ServiceEventListener, ServiceViewContributor, ServiceViewDescriptor}
import com.intellij.icons.AllIcons
import com.intellij.navigation.ItemPresentation
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.{ActionGroup, ActionUpdateThread, AnAction, AnActionEvent, DefaultActionGroup}
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import javax.swing.{Icon, JComponent}

/** The project-local MCP lifecycle is managed by the IDE's Services tool window. */
final class DbtAgentServiceView extends ServiceViewContributor[DbtAgent] {
  override def getServices(project: Project): java.util.List[DbtAgent] =
    java.util.List.of(project.getService(classOf[DbtAgent]))

  override def getViewDescriptor(project: Project): ServiceViewDescriptor = new ServiceViewDescriptor {
    override def getPresentation: ItemPresentation = DbtAgentServiceView.presentation("dbt Workspace")
    override def getId: String = "dbt.workspace"
  }

  override def getServiceDescriptor(project: Project, service: DbtAgent): ServiceViewDescriptor = new ServiceViewDescriptor {
    override def getId: String = "dbt.mcp"
    override def getPresentation: ItemPresentation = DbtAgentServiceView.presentation(
      "Local MCP Server — " + (if (service.endpoint.nonEmpty) "Running" else "Stopped"))

    override def getContentComponent: JComponent = {
      val status = service.endpoint.fold(
        if (project.getService(classOf[DbtConsumersSettings]).getState.agentEnabled) "Stopped. Choose Start to allow read-only project access."
        else "Disabled. Enable the local MCP server in Settings → dbt Workspace → Integrations, then choose Start."
      ) { case (url, _) => "Running at " + url + ". Copy MCP client configuration to connect your assistant." }
      val text = DbtUi.textArea(status + "\n\nRead-only access to this IDE project's dbt resources, documentation and lineage. " +
        "No dbt commands or warehouse queries are executed.\n\nStopping or restarting the server invalidates its temporary authorization. " +
        "Copy the client configuration again after a restart.")
      text.getAccessibleContext.setAccessibleName("Local MCP server status")
      text.setBorder(JBUI.Borders.empty(12))
      new JBScrollPane(text)
    }

    override def getToolbarActions: ActionGroup = DbtAgentServiceView.actions(project, service)
    override def getPopupActions: ActionGroup = getToolbarActions
  }
}

private[ide] object DbtAgentServiceView {
  def changed(project: Project): Unit = if (!project.isDisposed)
    project.getMessageBus.syncPublisher(ServiceEventListener.TOPIC)
      .handle(ServiceEventListener.ServiceEvent.createResetEvent(classOf[DbtAgentServiceView]))

  def presentation(text: String): ItemPresentation = new ItemPresentation {
    override def getPresentableText: String = text
    override def getLocationString: String = null
    override def getIcon(unused: Boolean): Icon = AllIcons.General.Web
  }

  def actions(project: Project, service: DbtAgent): ActionGroup = {
    def action(label: String, icon: Icon)(available: => Boolean)(perform: => Unit): AnAction =
      new AnAction(label, null, icon) {
        override def getActionUpdateThread: ActionUpdateThread = ActionUpdateThread.EDT
        override def update(event: AnActionEvent): Unit =
          event.getPresentation.setEnabled(!project.isDisposed && available)
        override def actionPerformed(event: AnActionEvent): Unit = if (!project.isDisposed && available) perform
      }

    def copy(configuration: Boolean): Unit = service.endpoint.foreach { case (url, token) =>
      DbtUi.copy(if (configuration) DbtAgentProtocol.clientConfiguration(url, token) else url + "\nAuthorization: Bearer " + token)
      DbtNotifications.show(project, "dbt Local MCP Server",
        if (configuration) "MCP client configuration copied." else "Endpoint and authorization copied.", NotificationType.INFORMATION)
    }

    new DefaultActionGroup(
      action("Start", AllIcons.Actions.Execute)(service.endpoint.isEmpty &&
        project.getService(classOf[DbtConsumersSettings]).getState.agentEnabled) {
        service.start().left.foreach(error => DbtNotifications.show(project, "dbt Local MCP Server", error, NotificationType.ERROR))
      },
      action("Stop", AllIcons.Actions.Suspend)(service.endpoint.nonEmpty) { service.stop() },
      action("Copy MCP client configuration", AllIcons.Actions.Copy)(service.endpoint.nonEmpty) { copy(configuration = true) },
      action("Copy endpoint and authorization", AllIcons.Actions.Copy)(service.endpoint.nonEmpty) { copy(configuration = false) },
      action("Configure…", AllIcons.General.Settings)(true) {
        DbtSettingsConfigurable.showIntegrations(project)
        changed(project)
      })
  }
}
