package org.riderxge.incredibuild.run

import com.intellij.execution.BeforeRunTask
import com.intellij.execution.BeforeRunTaskProvider
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.Key
import com.jetbrains.rider.run.configurations.IProjectBasedRunConfiguration
import org.riderxge.incredibuild.build.IncrediBuildRequest
import org.riderxge.incredibuild.build.IncrediBuildRunner
import org.riderxge.incredibuild.ib.BuildOperation
import org.riderxge.incredibuild.ui.IncrediBuildIcons
import java.nio.file.Path
import java.nio.file.Paths
import javax.swing.Icon

class IncrediBuildBeforeRunTaskState : BaseState() {
    /** Explicit project to build; empty means "the run configuration's project" (or the whole solution). */
    var projectPath by string("")
    /** Build the whole solution rather than a single project (replacement for Rider's "Build Solution" step). */
    var wholeSolution by property(false)
    /** Installed automatically by [BeforeRunTaskSwapper] in place of Rider's build step; restored when the option is turned off. */
    var autoInstalled by property(false)
}

class IncrediBuildBeforeRunTask :
    BeforeRunTask<IncrediBuildBeforeRunTask>(IncrediBuildBeforeRunTaskProvider.ID),
    PersistentStateComponent<IncrediBuildBeforeRunTaskState> {

    private var state = IncrediBuildBeforeRunTaskState()

    override fun getState(): IncrediBuildBeforeRunTaskState = state

    override fun loadState(state: IncrediBuildBeforeRunTaskState) {
        this.state = state
    }

    override fun clone(): BeforeRunTask<IncrediBuildBeforeRunTask> {
        val copy = super.clone() as IncrediBuildBeforeRunTask
        copy.state = IncrediBuildBeforeRunTaskState().also {
            it.projectPath = state.projectPath
            it.wholeSolution = state.wholeSolution
            it.autoInstalled = state.autoInstalled
        }
        return copy
    }

    /** The project this step builds for [configuration], or null for the whole solution. */
    fun projectPathFor(configuration: RunConfiguration): Path? {
        if (state.wholeSolution) return null
        val explicit = state.projectPath?.takeIf { it.isNotBlank() }
        val fromConfiguration = (configuration as? IProjectBasedRunConfiguration)?.getProjectFilePath()?.takeIf { it.isNotBlank() }
        return (explicit ?: fromConfiguration)?.let { runCatching { Paths.get(it).toAbsolutePath().normalize() }.getOrNull() }
    }
}

/**
 * "Build with IncrediBuild" before-launch step. For a .NET project run configuration it builds that project
 * (through the configured dispatch mode); for any other configuration it builds the whole solution.
 * Replaces Rider's default "Build Project"/"Build Solution" step – automatically when
 * "Use IncrediBuild for Rider's standard build actions" is on (see [BeforeRunTaskSwapper]), or by hand from
 * Run > Edit Configurations > Before launch.
 */
class IncrediBuildBeforeRunTaskProvider : BeforeRunTaskProvider<IncrediBuildBeforeRunTask>() {

    override fun getId(): Key<IncrediBuildBeforeRunTask> = ID

    override fun getName(): String = "Build with IncrediBuild"

    override fun getIcon(): Icon = IncrediBuildIcons.Logo

    override fun getDescription(task: IncrediBuildBeforeRunTask): String {
        val s = task.state
        return when {
            s.wholeSolution -> "Build solution with IncrediBuild"
            !s.projectPath.isNullOrBlank() -> "Build ${Paths.get(s.projectPath!!).fileName} with IncrediBuild"
            else -> "Build with IncrediBuild"
        }
    }

    override fun isConfigurable(): Boolean = false

    /** Never added automatically by the platform – the user opts in per run configuration (or via the settings option). */
    override fun createTask(runConfiguration: RunConfiguration): IncrediBuildBeforeRunTask =
        IncrediBuildBeforeRunTask().apply { isEnabled = false }

    override fun canExecuteTask(configuration: RunConfiguration, task: IncrediBuildBeforeRunTask): Boolean = true

    override fun executeTask(
        context: DataContext,
        configuration: RunConfiguration,
        environment: ExecutionEnvironment,
        task: IncrediBuildBeforeRunTask,
    ): Boolean {
        val project = configuration.project
        val request = IncrediBuildRequest(
            operation = BuildOperation.BUILD,
            projectPaths = listOfNotNull(task.projectPathFor(configuration)),
        )
        return try {
            val outcome = IncrediBuildRunner.getInstance(project).build(request).get()
            outcome.isSuccess
        } catch (t: Throwable) {
            LOG.warn("IncrediBuild before-run task failed", t)
            false
        }
    }

    companion object {
        val ID: Key<IncrediBuildBeforeRunTask> = Key.create("IncrediBuild.BuildBeforeRun")
        private val LOG = logger<IncrediBuildBeforeRunTaskProvider>()
    }
}
