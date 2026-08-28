package org.riderxge.incredibuild.run

import com.intellij.execution.BeforeRunTask
import com.intellij.execution.BeforeRunTaskProvider
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.Key
import com.jetbrains.rider.run.configurations.project.DotNetProjectConfiguration
import org.riderxge.incredibuild.build.BuildOutcome
import org.riderxge.incredibuild.build.IncrediBuildRequest
import org.riderxge.incredibuild.build.IncrediBuildRunner
import org.riderxge.incredibuild.ib.BuildOperation
import org.riderxge.incredibuild.ui.IncrediBuildIcons
import java.nio.file.Path
import java.nio.file.Paths
import javax.swing.Icon

class IncrediBuildBeforeRunTask : BeforeRunTask<IncrediBuildBeforeRunTask>(IncrediBuildBeforeRunTaskProvider.ID)

/**
 * "Build with IncrediBuild" before-launch step. For a .NET project run configuration it builds that project
 * (dispatching its C++ dependencies to IncrediBuild per the configured mode); for any other configuration it
 * builds the whole solution. Intended as a replacement for Rider's default "Build Solution"/"Build Project" step.
 */
class IncrediBuildBeforeRunTaskProvider : BeforeRunTaskProvider<IncrediBuildBeforeRunTask>() {

    override fun getId(): Key<IncrediBuildBeforeRunTask> = ID

    override fun getName(): String = "Build with IncrediBuild"

    override fun getIcon(): Icon = IncrediBuildIcons.Logo

    override fun getDescription(task: IncrediBuildBeforeRunTask): String = "Build with IncrediBuild"

    override fun isConfigurable(): Boolean = false

    /** Never added automatically – the user opts in per run configuration. */
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
        val projectFile: String? = (configuration as? DotNetProjectConfiguration)?.getProjectFilePath()
        val projectPath: Path? = projectFile
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Paths.get(it).toAbsolutePath().normalize() }.getOrNull() }
        val request = IncrediBuildRequest(
            operation = BuildOperation.BUILD,
            projectPaths = listOfNotNull(projectPath),
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
