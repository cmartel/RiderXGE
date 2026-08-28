package org.riderxge.incredibuild.actions

import com.intellij.execution.RunManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.platform.backend.workspace.WorkspaceModel
import com.jetbrains.rider.projectView.workspace.containingProjectEntity
import com.jetbrains.rider.projectView.workspace.getFile
import com.jetbrains.rider.projectView.workspace.getProjectModelEntities
import com.jetbrains.rider.projectView.workspace.isProject
import com.jetbrains.rider.projectView.workspace.isSolution
import com.jetbrains.rider.run.configurations.IProjectBasedRunConfiguration
import java.nio.file.Path
import java.nio.file.Paths

/** Resolves what Rider's build actions would build into project files the plugin can dispatch. */
object BuildTargetResolver {
    private val LOG = logger<BuildTargetResolver>()

    /**
     * Project files selected in the Solution Explorer; an empty list means the solution node is selected;
     * null when the selection contains nothing buildable (e.g. the action was invoked from an editor).
     */
    fun selectedProjects(context: DataContext): List<Path>? {
        val entities = runCatching { context.getProjectModelEntities(false) }.getOrElse {
            LOG.debug("Cannot read project model selection", it); emptyList()
        }
        if (entities.isEmpty()) return null
        if (entities.any { it.isSolution() }) return emptyList()
        val projects = entities.filter { it.isProject() }.mapNotNull { runCatching { it.getFile()?.toPath() }.getOrNull() }
        return projects.takeIf { it.isNotEmpty() }
    }

    /** The project behind the currently selected run configuration ("startup project"), or null. */
    fun startupProject(project: Project): Path? {
        val configuration = RunManager.getInstance(project).selectedConfiguration?.configuration as? IProjectBasedRunConfiguration
            ?: return null
        return toPath(configuration.getProjectFilePath())
    }

    /** The project that owns the file in the active editor (Rider's "current project"), or null. */
    fun currentProject(project: Project, context: DataContext): Path? {
        val file = context.getData(CommonDataKeys.VIRTUAL_FILE) ?: return null
        return runCatching {
            val entities = WorkspaceModel.getInstance(project).getProjectModelEntities(file, project)
            entities.asSequence()
                .mapNotNull { if (it.isProject()) it else it.containingProjectEntity() }
                .firstOrNull()
                ?.getFile()?.toPath()?.toAbsolutePath()?.normalize()
        }.getOrElse { LOG.debug("Cannot resolve current project for $file", it); null }
    }

    private fun toPath(raw: String?): Path? =
        raw?.takeIf { it.isNotBlank() }?.let { runCatching { Paths.get(it).toAbsolutePath().normalize() }.getOrNull() }
}
