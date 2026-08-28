package org.riderxge.incredibuild.ui

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory
import com.jetbrains.rider.projectView.hasSolution
import com.jetbrains.rider.projectView.solutionDirectoryPath

/** Registered in plugin.xml; content is created lazily per build by [IncrediBuildToolWindow.createTab]. */
class IncrediBuildToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // Tabs are added on demand when a build starts.
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}

object IncrediBuildToolWindow {
    const val ID = "IncrediBuild"
    private const val MAX_TABS = 6

    /** Creates a new console tab (on the EDT, blocking the caller until it exists). */
    fun createTab(project: Project, title: String, activate: Boolean): IncrediBuildConsoleTab {
        lateinit var tab: IncrediBuildConsoleTab
        ApplicationManager.getApplication().invokeAndWait({
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ID)
                ?: error("IncrediBuild tool window is not registered")
            val manager = toolWindow.contentManager

            // Keep the tab strip tidy: drop the oldest finished tabs.
            val finished = manager.contents.filter { c -> (c.getUserData(TAB_KEY)?.isRunning) == false }
            if (manager.contents.size >= MAX_TABS) {
                finished.take(manager.contents.size - MAX_TABS + 1).forEach { manager.removeContent(it, true) }
            }

            val baseDir = if (project.hasSolution) runCatching { project.solutionDirectoryPath }.getOrNull() else null
            val builder = TextConsoleBuilderFactory.getInstance().createBuilder(project)
            builder.addFilter(MsBuildDiagnosticFilter(project, baseDir))
            val console = builder.console

            val panel = SimpleToolWindowPanel(false, true)
            val group = DefaultActionGroup().apply {
                add(ActionManager.getInstance().getAction("IncrediBuild.Cancel"))
                add(ActionManager.getInstance().getAction("IncrediBuild.OpenMonitor"))
                add(ActionManager.getInstance().getAction("IncrediBuild.AgentStatus"))
                addSeparator()
                addAll(*console.createConsoleActions())
                addSeparator()
                add(ActionManager.getInstance().getAction("IncrediBuild.Settings"))
            }
            val toolbar = ActionManager.getInstance().createActionToolbar(ActionPlaces.TOOLWINDOW_TOOLBAR_BAR, group, false)
            toolbar.targetComponent = console.component
            panel.toolbar = toolbar.component
            panel.setContent(console.component)

            val content = ContentFactory.getInstance().createContent(panel, title, false)
            content.isCloseable = true
            Disposer.register(content, console)
            tab = IncrediBuildConsoleTab(console, content)
            content.putUserData(TAB_KEY, tab)
            manager.addContent(content)
            manager.setSelectedContent(content)
            if (activate) toolWindow.activate(null, false)
            else toolWindow.show(null)
        }, ModalityState.any())
        return tab
    }

    private val TAB_KEY = com.intellij.openapi.util.Key.create<IncrediBuildConsoleTab>("IncrediBuild.tab")
}
