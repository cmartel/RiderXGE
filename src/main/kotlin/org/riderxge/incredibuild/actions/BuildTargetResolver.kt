package org.riderxge.incredibuild.actions

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.diagnostic.logger
import com.jetbrains.rider.projectView.workspace.getFile
import com.jetbrains.rider.projectView.workspace.getProjectModelEntities
import com.jetbrains.rider.projectView.workspace.isProject
import com.jetbrains.rider.projectView.workspace.isSolution
import java.nio.file.Path

/** Resolves what the user has selected in the Solution Explorer into buildable project files. */
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
}
