package org.riderxge.incredibuild.actions

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.AnActionWrapper
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.jetbrains.rider.projectView.hasSolution
import org.riderxge.incredibuild.build.IncrediBuildRequest
import org.riderxge.incredibuild.build.IncrediBuildRunner
import org.riderxge.incredibuild.ib.BuildOperation
import org.riderxge.incredibuild.settings.IncrediBuildSettings

/** What a Rider build action builds. */
enum class OverrideScope { SOLUTION, SELECTION, STARTUP_PROJECT, CURRENT_PROJECT }

/**
 * Optional interception of Rider's stock build commands ("Use IncrediBuild for Rider's standard build actions"
 * in the settings). Rider's actions stay registered under their usual ids and keymaps; each is wrapped in an
 * [AnActionWrapper] that reroutes the invocation to IncrediBuild when the option is enabled and falls back to
 * the original action otherwise (option off, no solution, or a target the plugin cannot resolve).
 *
 * Builds Rider starts programmatically (default before-launch build step, build before unit tests) do not go
 * through these actions and are not intercepted; use the "Build with IncrediBuild" before-launch task for those.
 */
class StandardBuildActionCustomizer : ProjectActivity {

    /** Runs once per IDE session, when the first project opens (public API, unlike ActionConfigurationCustomizer). */
    override suspend fun execute(project: Project) {
        if (!installed.compareAndSet(false, true)) return
        install(ActionManager.getInstance())
    }

    private fun install(actionManager: ActionManager) {
        for (target in TARGETS) {
            try {
                val original = actionManager.getAction(target.id)
                if (original == null) {
                    LOG.info("Rider build action '${target.id}' not found; skipping IncrediBuild override")
                    continue
                }
                if (original is StandardBuildActionOverride) continue
                actionManager.replaceAction(target.id, StandardBuildActionOverride(original, target))
            } catch (t: Throwable) {
                LOG.warn("Failed to wrap Rider build action '${target.id}'", t)
            }
        }
    }

    class Target(val id: String, val operation: BuildOperation, val scope: OverrideScope, val withoutDependencies: Boolean = false)

    companion object {
        private val LOG = logger<StandardBuildActionCustomizer>()
        private val installed = java.util.concurrent.atomic.AtomicBoolean(false)

        /** Rider action ids are stable since at least 2019.x (see intellij.rider.xml; verified on 243 and 262). */
        private val TARGETS: List<Target> = buildList {
            add(Target("BuildWholeSolutionAction", BuildOperation.BUILD, OverrideScope.SOLUTION))
            // The main build button; also owns Ctrl+F9 and the Visual Studio keymap's Ctrl+Shift+B.
            add(Target("BuildSolutionAction", BuildOperation.BUILD, OverrideScope.SOLUTION))
            add(Target("RebuildSolutionAction", BuildOperation.REBUILD, OverrideScope.SOLUTION))
            add(Target("CleanSolutionAction", BuildOperation.CLEAN, OverrideScope.SOLUTION))

            add(Target("BuildSelectionAction", BuildOperation.BUILD, OverrideScope.SELECTION))
            add(Target("RebuildSelectionAction", BuildOperation.REBUILD, OverrideScope.SELECTION))
            add(Target("CleanSelectionAction", BuildOperation.CLEAN, OverrideScope.SELECTION))
            add(Target("BuildSelectedProjectWithoutDependencies", BuildOperation.BUILD, OverrideScope.SELECTION, withoutDependencies = true))
            add(Target("RebuildSelectedProjectWithoutDependencies", BuildOperation.REBUILD, OverrideScope.SELECTION, withoutDependencies = true))
            add(Target("CleanSelectedProjectWithoutDependencies", BuildOperation.CLEAN, OverrideScope.SELECTION, withoutDependencies = true))

            for ((prefix, scope) in listOf("StartupProject" to OverrideScope.STARTUP_PROJECT, "CurrentProject" to OverrideScope.CURRENT_PROJECT)) {
                add(Target("Build$prefix", BuildOperation.BUILD, scope))
                add(Target("Rebuild$prefix", BuildOperation.REBUILD, scope))
                add(Target("Clean$prefix", BuildOperation.CLEAN, scope))
                add(Target("BuildOnly$prefix", BuildOperation.BUILD, scope, withoutDependencies = true))
                add(Target("RebuildOnly$prefix", BuildOperation.REBUILD, scope, withoutDependencies = true))
                add(Target("CleanOnly$prefix", BuildOperation.CLEAN, scope, withoutDependencies = true))
            }
        }
    }
}

class StandardBuildActionOverride(
    delegate: AnAction,
    private val target: StandardBuildActionCustomizer.Target,
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
        val project = e.project ?: return null
        val paths = when (target.scope) {
            OverrideScope.SOLUTION -> emptyList()
            OverrideScope.SELECTION -> BuildTargetResolver.selectedProjects(e.dataContext) ?: return null
            OverrideScope.STARTUP_PROJECT -> listOf(BuildTargetResolver.startupProject(project) ?: return null)
            OverrideScope.CURRENT_PROJECT -> listOf(BuildTargetResolver.currentProject(project, e.dataContext) ?: return null)
        }
        return IncrediBuildRequest(operation = target.operation, projectPaths = paths, withoutDependencies = target.withoutDependencies)
    }
}
