package org.riderxge.incredibuild.actions

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.AnActionWrapper
import com.intellij.openapi.actionSystem.impl.ActionConfigurationCustomizer
import com.intellij.openapi.diagnostic.logger
import com.jetbrains.rider.projectView.hasSolution
import org.riderxge.incredibuild.build.IncrediBuildRequest
import org.riderxge.incredibuild.build.IncrediBuildRunner
import org.riderxge.incredibuild.ib.BuildOperation
import org.riderxge.incredibuild.settings.IncrediBuildSettings

/**
 * Optional interception of Rider's stock build commands ("Use IncrediBuild for Rider's standard build actions"
 * in the settings). Rider's actions stay registered under their usual ids and keymaps; each is wrapped in an
 * [AnActionWrapper] that reroutes the invocation to IncrediBuild when the option is enabled and falls back to
 * the original action otherwise (option off, no solution, or a selection the plugin cannot resolve).
 *
 * Builds Rider starts programmatically (default before-launch build step, build before unit tests) do not go
 * through these actions and are not intercepted; use the "Build with IncrediBuild" before-launch task for those.
 */
class StandardBuildActionCustomizer : ActionConfigurationCustomizer {

    override fun customize(actionManager: ActionManager) {
        for ((id, target) in TARGETS) {
            try {
                val original = actionManager.getAction(id)
                if (original == null) {
                    LOG.info("Rider build action '$id' not found; skipping IncrediBuild override")
                    continue
                }
                if (original is StandardBuildActionOverride) continue
                actionManager.replaceAction(id, StandardBuildActionOverride(original, target.first, target.second))
            } catch (t: Throwable) {
                LOG.warn("Failed to wrap Rider build action '$id'", t)
            }
        }
    }

    companion object {
        private val LOG = logger<StandardBuildActionCustomizer>()

        /** Rider action id → (operation, scope). Ids are stable since at least 2019.x (see intellij.rider.xml). */
        private val TARGETS: Map<String, Pair<BuildOperation, Scope>> = mapOf(
            "BuildWholeSolutionAction" to (BuildOperation.BUILD to Scope.SOLUTION),
            // The main build button; also owns Ctrl+F9 and the Visual Studio keymap's Ctrl+Shift+B.
            "BuildSolutionAction" to (BuildOperation.BUILD to Scope.SOLUTION),
            "RebuildSolutionAction" to (BuildOperation.REBUILD to Scope.SOLUTION),
            "CleanSolutionAction" to (BuildOperation.CLEAN to Scope.SOLUTION),
            "BuildSelectionAction" to (BuildOperation.BUILD to Scope.SELECTION),
            "RebuildSelectionAction" to (BuildOperation.REBUILD to Scope.SELECTION),
            "CleanSelectionAction" to (BuildOperation.CLEAN to Scope.SELECTION),
        )
    }
}

class StandardBuildActionOverride(
    delegate: AnAction,
    private val operation: BuildOperation,
    private val scope: Scope,
) : AnActionWrapper(delegate) {

    override fun update(e: AnActionEvent) {
        super.update(e)
        if (!isActive(e)) return
        val text = e.presentation.text
        if (!text.isNullOrEmpty() && !text.contains("IncrediBuild")) {
            e.presentation.text = "$text (IncrediBuild)"
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val request = if (isActive(e)) resolveRequest(e) else null
        if (request == null) {
            super.actionPerformed(e)
            return
        }
        val project = e.project ?: return
        IncrediBuildRunner.getInstance(project).build(request)
    }

    private fun isActive(e: AnActionEvent): Boolean {
        if (!IncrediBuildSettings.getInstance().state.overrideStandardBuildActions) return false
        val project = e.project ?: return false
        return runCatching { project.hasSolution }.getOrDefault(false)
    }

    /** null → let Rider's own action handle the event. */
    private fun resolveRequest(e: AnActionEvent): IncrediBuildRequest? {
        val paths = when (scope) {
            Scope.SOLUTION -> emptyList()
            Scope.SELECTION -> BuildTargetResolver.selectedProjects(e.dataContext) ?: return null
        }
        return IncrediBuildRequest(operation = operation, projectPaths = paths)
    }
}
