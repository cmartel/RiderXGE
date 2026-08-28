package org.riderxge.incredibuild.run

import com.intellij.execution.BeforeRunTask
import com.intellij.execution.BeforeRunTaskProvider
import com.intellij.execution.RunManager
import com.intellij.execution.RunManagerEx
import com.intellij.execution.RunManagerListener
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.startup.ProjectActivity
import com.jetbrains.rider.build.tasks.BeforeRunTaskWithProject
import com.jetbrains.rider.build.tasks.BuildProjectBeforeRunTask
import com.jetbrains.rider.build.tasks.BuildProjectBeforeRunTaskProvider
import com.jetbrains.rider.build.tasks.BuildSolutionBeforeRunTask
import com.jetbrains.rider.build.tasks.BuildSolutionBeforeRunTaskProvider
import org.riderxge.incredibuild.settings.IncrediBuildSettings
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps the "Before launch" steps of every run configuration in line with the
 * "Use IncrediBuild for Rider's standard build actions" option.
 *
 * Rider adds a "Build Project" (or "Build Solution") step to each run configuration, executed on Run/Debug (F5,
 * Shift+F10) straight through Rider's build host – the action override cannot intercept it. When the option is on,
 * that step is replaced by the plugin's "Build with IncrediBuild" step carrying the same project; when the option is
 * turned off again, the steps the plugin installed are swapped back to Rider's.
 */
@Service(Service.Level.PROJECT)
class BeforeRunTaskSwapper(private val project: Project) {

    private val updating = AtomicBoolean(false)

    private val enabled: Boolean
        get() = IncrediBuildSettings.getInstance().state.let { it.overrideStandardBuildActions && it.replaceBeforeRunBuildSteps }

    fun syncAll() {
        val runManager = RunManager.getInstance(project)
        for (settings in runManager.allSettings) sync(settings)
    }

    fun sync(settings: RunnerAndConfigurationSettings) {
        if (settings.isTemplate) return
        if (!updating.compareAndSet(false, true)) return
        try {
            val configuration = settings.configuration
            val tasks = configuration.beforeRunTasks
            val replacement = if (enabled) toIncrediBuild(configuration, tasks) else toRider(configuration, tasks)
            if (replacement != null) {
                RunManagerEx.getInstanceEx(project).setBeforeRunTasks(configuration, replacement)
                LOG.info("Before-launch steps of '${configuration.name}' now: ${replacement.joinToString { it.javaClass.simpleName }}")
            }
        } catch (t: Throwable) {
            LOG.warn("Failed to update before-launch steps of '${settings.name}'", t)
        } finally {
            updating.set(false)
        }
    }

    /** Rider build steps → IncrediBuild steps. Returns null when nothing changes. */
    private fun toIncrediBuild(configuration: RunConfiguration, tasks: List<BeforeRunTask<*>>): List<BeforeRunTask<*>>? {
        if (tasks.none { it is BuildProjectBeforeRunTask || it is BuildSolutionBeforeRunTask }) return null
        return tasks.map { task ->
            when (task) {
                is BuildProjectBeforeRunTask -> IncrediBuildBeforeRunTask().apply {
                    isEnabled = task.isEnabled
                    val riderState = (task as BeforeRunTaskWithProject<*>).state
                    state.projectPath = if (riderState.isDefault) "" else riderState.projectPath.orEmpty()
                    state.autoInstalled = true
                }
                is BuildSolutionBeforeRunTask -> IncrediBuildBeforeRunTask().apply {
                    isEnabled = task.isEnabled
                    state.wholeSolution = true
                    state.autoInstalled = true
                }
                else -> task
            }
        }
    }

    /** Auto-installed IncrediBuild steps → Rider build steps. Returns null when nothing changes. */
    private fun toRider(configuration: RunConfiguration, tasks: List<BeforeRunTask<*>>): List<BeforeRunTask<*>>? {
        if (tasks.none { it is IncrediBuildBeforeRunTask && it.state.autoInstalled }) return null
        val projectProvider = BeforeRunTaskProvider.getProvider(project, BuildProjectBeforeRunTaskProvider.providerId)
        val solutionProvider = BeforeRunTaskProvider.getProvider(project, BuildSolutionBeforeRunTaskProvider.providerId)
        return tasks.map { task ->
            if (task !is IncrediBuildBeforeRunTask || !task.state.autoInstalled) return@map task
            val restored: BeforeRunTask<*>? = if (task.state.wholeSolution) {
                solutionProvider?.createTask(configuration)
            } else {
                projectProvider?.createTask(configuration)?.also { riderTask ->
                    val explicit = task.state.projectPath?.takeIf { it.isNotBlank() }
                    if (explicit != null) {
                        val riderState = (riderTask as BeforeRunTaskWithProject<*>).state
                        riderState.isDefault = false
                        riderState.projectPath = explicit
                        riderState.projectName = Paths.get(explicit).fileName.toString().substringBeforeLast('.')
                    }
                }
            }
            (restored ?: task).also { it.isEnabled = task.isEnabled }
        }
    }

    companion object {
        private val LOG = logger<BeforeRunTaskSwapper>()

        fun getInstance(project: Project): BeforeRunTaskSwapper = project.service()

        /** Called when the settings change; re-syncs every open project. */
        fun syncAllProjects() {
            ApplicationManager.getApplication().invokeLater {
                for (project in ProjectManager.getInstance().openProjects) {
                    if (!project.isDisposed) getInstance(project).syncAll()
                }
            }
        }
    }
}

/** Initial pass once the project (and its run configurations) are loaded. */
class BeforeRunTaskSwapperStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        BeforeRunTaskSwapper.getInstance(project).syncAll()
    }
}

/** Follows run configurations that are created or edited later (Rider adds its build step to every new one). */
class BeforeRunTaskSwapperListener(private val project: Project) : RunManagerListener {
    override fun runConfigurationAdded(settings: RunnerAndConfigurationSettings) {
        BeforeRunTaskSwapper.getInstance(project).sync(settings)
    }

    override fun runConfigurationChanged(settings: RunnerAndConfigurationSettings) {
        BeforeRunTaskSwapper.getInstance(project).sync(settings)
    }

    override fun stateLoaded(runManager: RunManager, isFirstLoadState: Boolean) {
        BeforeRunTaskSwapper.getInstance(project).syncAll()
    }
}
