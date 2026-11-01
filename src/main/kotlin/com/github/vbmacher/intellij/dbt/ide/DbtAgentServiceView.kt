package com.github.vbmacher.intellij.dbt.ide

import com.intellij.execution.services.ServiceEventListener
import com.intellij.execution.services.ServiceViewContributor
import com.intellij.execution.services.ServiceViewDescriptor
import com.intellij.icons.AllIcons
import com.intellij.navigation.ItemPresentation
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import javax.swing.Icon
import javax.swing.JComponent

/**
 * Presents the project-local agent lifecycle in Services; Start remains an explicit action after opt-in.
 * Endpoint authorization is copied only on request and is never shown in the status component.
 */
class DbtAgentServiceView : ServiceViewContributor<DbtAgent> {
    override fun getServices(project: Project): List<DbtAgent> = listOf(project.getService(DbtAgent::class.java))
    override fun getViewDescriptor(project: Project): ServiceViewDescriptor = object : ServiceViewDescriptor {
        override fun getPresentation(): ItemPresentation = presentation("dbt Workspace")
        override fun getId(): String = "dbt.workspace"
    }

    override fun getServiceDescriptor(project: Project, service: DbtAgent): ServiceViewDescriptor =
        object : ServiceViewDescriptor {
            override fun getId(): String = "dbt.mcp"
            override fun getPresentation(): ItemPresentation =
                presentation("Local MCP Server — " + if (service.endpoint != null) "Running" else "Stopped")

            override fun getContentComponent(): JComponent {
                val status =
                    service.endpoint?.let { (url, _) -> "Running at $url. Copy MCP client configuration to connect your assistant." }
                        ?: if (project.getService(DbtConsumersSettings::class.java)
                                .getState().agentEnabled
                        ) "Stopped. Choose Start to allow read-only project access."
                        else "Disabled. Enable the local MCP server in Settings → dbt Workspace → Integrations, then choose Start."
                val text = DbtUi.textArea(
                    status + "\n\nRead-only access to this IDE project's dbt resources, documentation and lineage. " +
                            "No dbt commands or warehouse queries are executed.\n\nStopping or restarting the server invalidates its temporary authorization. " +
                            "Copy the client configuration again after a restart."
                )
                text.accessibleContext.accessibleName = "Local MCP server status"
                text.border = JBUI.Borders.empty(12)
                return JBScrollPane(text)
            }

            override fun getToolbarActions(): ActionGroup = actions(project, service)
            override fun getPopupActions(): ActionGroup = toolbarActions
        }

    companion object {
        fun changed(project: Project) {
            if (!project.isDisposed) project.messageBus.syncPublisher(ServiceEventListener.TOPIC)
                .handle(ServiceEventListener.ServiceEvent.createResetEvent(DbtAgentServiceView::class.java))
        }

        fun presentation(text: String): ItemPresentation = object : ItemPresentation {
            override fun getPresentableText(): String = text
            override fun getLocationString(): String? = null
            override fun getIcon(unused: Boolean): Icon = AllIcons.General.Web
        }

        fun actions(project: Project, service: DbtAgent): ActionGroup {
            fun action(label: String, icon: Icon, available: () -> Boolean, perform: () -> Unit): AnAction =
                object : AnAction(label, null, icon) {
                    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
                    override fun update(event: AnActionEvent) {
                        event.presentation.isEnabled = !project.isDisposed && available()
                    }

                    override fun actionPerformed(event: AnActionEvent) {
                        if (!project.isDisposed && available()) perform()
                    }
                }

            fun copy(configuration: Boolean) {
                service.endpoint?.let { (url, token) ->
                    DbtUi.copy(
                        if (configuration) DbtAgentProtocol.clientConfiguration(
                            url,
                            token
                        ) else "$url\nAuthorization: Bearer $token"
                    )
                    DbtNotifications.show(
                        project,
                        "dbt Local MCP Server",
                        if (configuration) "MCP client configuration copied." else "Endpoint and authorization copied.",
                        NotificationType.INFORMATION
                    )
                }
            }
            return DefaultActionGroup(
                action(
                    "Start",
                    AllIcons.Actions.Execute,
                    {
                        service.endpoint == null && project.getService(DbtConsumersSettings::class.java)
                            .getState().agentEnabled
                    }) {
                    service.start().onFailure {
                        DbtNotifications.show(
                            project,
                            "dbt Local MCP Server",
                            it.message.orEmpty(),
                            NotificationType.ERROR
                        )
                    }
                },
                action("Stop", AllIcons.Actions.Suspend, { service.endpoint != null }) { service.stop() },
                action(
                    "Copy MCP client configuration",
                    AllIcons.Actions.Copy,
                    { service.endpoint != null }) { copy(true) },
                action("Copy endpoint and authorization", AllIcons.Actions.Copy, { service.endpoint != null }) {
                    copy(
                        false
                    )
                },
                action("Configure…", AllIcons.General.Settings, { true }) {
                    DbtSettingsConfigurable.showIntegrations(
                        project
                    ); changed(project)
                }
            )
        }
    }
}
