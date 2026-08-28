package org.riderxge.incredibuild.actions

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.jetbrains.rider.build.BuildHost
import com.jetbrains.rider.projectView.hasSolution
import org.riderxge.incredibuild.build.IncrediBuildRequest
import org.riderxge.incredibuild.build.IncrediBuildRunner
import org.riderxge.incredibuild.ib.BuildOperation
import org.riderxge.incredibuild.ib.IncrediBuildLocator
import org.riderxge.incredibuild.settings.DispatchMode
import org.riderxge.incredibuild.settings.IncrediBuildConfigurable
import org.riderxge.incredibuild.ui.IncrediBuildToolWindow
import java.nio.file.Path

private val LOG = logger<IncrediBuildActionBase>()

/** Which projects an action applies to. */
enum class Scope { SOLUTION, SELECTION }

abstract class IncrediBuildActionBase(
    private val operation: BuildOperation,
    private val scope: Scope,
    private val withoutDependencies: Boolean = false,
    private val modeOverride: DispatchMode? = null,
    private val cppOnly: Boolean = false,
) : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null || !project.hasSolution) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        val selection = if (scope == Scope.SELECTION) BuildTargetResolver.selectedProjects(e.dataContext) else null
        if (scope == Scope.SELECTION && selection == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        e.presentation.isVisible = true
        e.presentation.isEnabled = !IncrediBuildRunner.getInstance(project).isBuilding && runCatching { BuildHost.isIdle(project) }.getOrDefault(true)
        if (scope == Scope.SELECTION && selection != null && selection.isNotEmpty()) {
            val names = selection.joinToString(", ") { it.fileName.toString().substringBeforeLast('.') }
            e.presentation.text = "${templateText.removeSuffix(" Selection").removeSuffix(" Selected Project(s)")} '$names' with IncrediBuild"
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val paths = if (scope == Scope.SELECTION) BuildTargetResolver.selectedProjects(e.dataContext) ?: return else emptyList()
        val request = IncrediBuildRequest(
            operation = operation,
            projectPaths = paths,
            withoutDependencies = withoutDependencies,
            modeOverride = modeOverride,
            cppOnly = cppOnly,
        )
        IncrediBuildRunner.getInstance(project).build(request)
    }

}

class BuildSolutionWithIncrediBuildAction : IncrediBuildActionBase(BuildOperation.BUILD, Scope.SOLUTION)
class RebuildSolutionWithIncrediBuildAction : IncrediBuildActionBase(BuildOperation.REBUILD, Scope.SOLUTION)
class CleanSolutionWithIncrediBuildAction : IncrediBuildActionBase(BuildOperation.CLEAN, Scope.SOLUTION)

class BuildSelectionWithIncrediBuildAction : IncrediBuildActionBase(BuildOperation.BUILD, Scope.SELECTION)
class RebuildSelectionWithIncrediBuildAction : IncrediBuildActionBase(BuildOperation.REBUILD, Scope.SELECTION)
class CleanSelectionWithIncrediBuildAction : IncrediBuildActionBase(BuildOperation.CLEAN, Scope.SELECTION)
class BuildSelectionWithoutDependenciesAction : IncrediBuildActionBase(BuildOperation.BUILD, Scope.SELECTION, withoutDependencies = true)

/** Dispatch only the C++ dependency closure of the selection to IncrediBuild – no Rider build afterwards. */
class DispatchCppDependenciesAction : IncrediBuildActionBase(BuildOperation.BUILD, Scope.SELECTION, modeOverride = DispatchMode.HYBRID, cppOnly = true)

/** Build the whole solution through BuildConsole regardless of the configured dispatch mode. */
class BuildSolutionFullyWithIncrediBuildAction : IncrediBuildActionBase(BuildOperation.BUILD, Scope.SOLUTION, modeOverride = DispatchMode.FULL)

class CancelIncrediBuildAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null && IncrediBuildRunner.getInstance(project).isBuilding
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        IncrediBuildRunner.getInstance(project).cancel()
    }
}

class OpenBuildMonitorAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = IncrediBuildLocator.buildMonitor() != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val monitor = IncrediBuildLocator.buildMonitor() ?: return
        try {
            GeneralCommandLine(monitor.toString()).createProcess()
        } catch (t: Throwable) {
            LOG.warn("Cannot start Build Monitor", t)
            notify(e.project, "Cannot start Build Monitor: ${t.message}", NotificationType.ERROR)
        }
    }
}

/** Runs `BuildConsole /QUERYAGENTSTATUS` and shows the result in the tool window. */
class AgentStatusAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null && IncrediBuildLocator.buildConsole() != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            val status = IncrediBuildRunner.query("/QUERYAGENTSTATUS", "/NOLOGO") ?: "BuildConsole.exe not found."
            val license = IncrediBuildRunner.query("/QUERYLICENSE", "/NOLOGO").orEmpty()
            val tab = IncrediBuildToolWindow.createTab(project, "Agent Status", true)
            tab.println("Agent status (${IncrediBuildLocator.buildConsole()}):", ConsoleViewContentType.SYSTEM_OUTPUT)
            tab.println(status, ConsoleViewContentType.NORMAL_OUTPUT)
            if (license.isNotEmpty()) {
                tab.println("\nLicense:", ConsoleViewContentType.SYSTEM_OUTPUT)
                tab.println(license, ConsoleViewContentType.NORMAL_OUTPUT)
            }
            tab.markFinished(org.riderxge.incredibuild.build.BuildOutcome.SKIPPED)
        }
    }
}

class IncrediBuildSettingsAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    override fun actionPerformed(e: AnActionEvent) {
        ShowSettingsUtil.getInstance().showSettingsDialog(e.project, IncrediBuildConfigurable::class.java)
    }
}

private fun notify(project: Project?, text: String, type: NotificationType) {
    NotificationGroupManager.getInstance().getNotificationGroup(IncrediBuildRunner.NOTIFICATION_GROUP)
        .createNotification(text, type).notify(project)
}
