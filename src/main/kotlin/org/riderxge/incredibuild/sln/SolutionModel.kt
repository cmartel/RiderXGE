package org.riderxge.incredibuild.sln

import java.nio.file.Path

/** One project entry of a solution. */
data class SolutionProject(
    /** Name as shown in the solution (what `BuildConsole /PRJ=` expects). */
    val name: String,
    /** Absolute, normalized path of the project file. */
    val path: Path,
    /** Project GUID (upper-case, with braces) or null for `.slnx`. */
    val guid: String?,
    /** Explicit solution-level dependencies (`ProjectSection(ProjectDependencies)`), as GUIDs. */
    val explicitDependencyGuids: List<String> = emptyList(),
) {
    val extension: String get() = path.fileName.toString().substringAfterLast('.', "").lowercase()

    /** Native or C++/CLI project types that IncrediBuild distributes. */
    val isCpp: Boolean get() = extension in CPP_EXTENSIONS

    companion object {
        val CPP_EXTENSIONS = setOf("vcxproj", "vcproj", "icproj")
    }
}

data class SolutionConfiguration(val configuration: String, val platform: String) {
    override fun toString(): String = "$configuration|$platform"
}

data class SolutionModel(
    val file: Path,
    val projects: List<SolutionProject>,
    val configurations: List<SolutionConfiguration>,
    /** `(projectGuid, solutionConfig)` → project configuration `Cfg|Platform` (only for `.sln`). */
    val projectConfigurations: Map<Pair<String, SolutionConfiguration>, String> = emptyMap(),
) {
    val directory: Path get() = file.parent

    fun findByPath(path: Path): SolutionProject? {
        val norm = path.toAbsolutePath().normalize()
        return projects.firstOrNull { it.path == norm } ?: projects.firstOrNull { it.path.toString().equals(norm.toString(), ignoreCase = true) }
    }

    fun findByGuid(guid: String): SolutionProject? = projects.firstOrNull { it.guid.equals(guid, ignoreCase = true) }

    /** True when `cfg` is a solution configuration under which `project` is built. */
    fun isBuilt(project: SolutionProject, cfg: SolutionConfiguration): Boolean {
        val guid = project.guid ?: return true
        if (projectConfigurations.isEmpty()) return true
        return projectConfigurations.containsKey(guid to cfg)
    }
}
