package org.riderxge.incredibuild.sln

import org.w3c.dom.Element
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Project-reference graph of a solution, built from the `<ProjectReference Include="...">` items of each
 * MSBuild project file plus explicit `ProjectDependencies` recorded in the `.sln`.
 */
class ProjectGraph private constructor(
    val solution: SolutionModel,
    /** project path → directly referenced project paths (only those that are part of the solution). */
    private val edges: Map<Path, List<Path>>,
    /** project path → referenced project files that are *not* part of the solution (informational). */
    val danglingReferences: Map<Path, List<Path>>,
) {
    fun directDependencies(project: SolutionProject): List<SolutionProject> =
        edges[project.path].orEmpty().mapNotNull { solution.findByPath(it) }

    /** Transitive dependencies of [roots], excluding the roots themselves, in dependency-first order. */
    fun transitiveDependencies(roots: Collection<SolutionProject>): List<SolutionProject> {
        val visited = LinkedHashSet<Path>()
        val ordered = ArrayList<SolutionProject>()
        fun visit(p: SolutionProject) {
            if (!visited.add(p.path)) return
            for (d in directDependencies(p)) visit(d)
            ordered += p
        }
        val rootPaths = roots.map { it.path }.toSet()
        roots.forEach(::visit)
        return ordered.filter { it.path !in rootPaths }
    }

    /**
     * The set of C++ projects IncrediBuild should build so that [roots] can be built afterwards:
     * every transitive C++ dependency, plus the roots themselves when they are C++ projects.
     * Order is dependency-first.
     */
    fun cppProjectsFor(roots: Collection<SolutionProject>): List<SolutionProject> {
        val visited = LinkedHashSet<Path>()
        val ordered = ArrayList<SolutionProject>()
        fun visit(p: SolutionProject) {
            if (!visited.add(p.path)) return
            for (d in directDependencies(p)) visit(d)
            ordered += p
        }
        roots.forEach(::visit)
        return ordered.filter { it.isCpp }
    }

    companion object {
        private val PROJECT_REFERENCE_ITEMS = setOf("ProjectReference")

        fun build(solution: SolutionModel): ProjectGraph {
            val edges = HashMap<Path, List<Path>>()
            val dangling = HashMap<Path, List<Path>>()
            for (p in solution.projects) {
                val refs = LinkedHashSet<Path>()
                if (Files.isRegularFile(p.path)) {
                    refs.addAll(readProjectReferences(p.path))
                }
                for (guid in p.explicitDependencyGuids) solution.findByGuid(guid)?.let { refs.add(it.path) }
                val (inSolution, missing) = refs.partition { solution.findByPath(it) != null }
                edges[p.path] = inSolution.mapNotNull { solution.findByPath(it)?.path }
                if (missing.isNotEmpty()) dangling[p.path] = missing
            }
            return ProjectGraph(solution, edges, dangling)
        }

        /** Reads `ProjectReference` items of an MSBuild project file. Unresolvable `$(...)` includes are skipped. */
        fun readProjectReferences(projectFile: Path): List<Path> {
            val dir = projectFile.toAbsolutePath().parent
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
                isExpandEntityReferences = false
            }
            val doc = try {
                Files.newInputStream(projectFile).use { factory.newDocumentBuilder().parse(it) }
            } catch (e: Exception) {
                return emptyList()
            }
            val result = ArrayList<Path>()
            val items = doc.getElementsByTagName("ProjectReference")
            for (i in 0 until items.length) {
                val el = items.item(i) as? Element ?: continue
                val include = el.getAttribute("Include")
                if (include.isEmpty() || include.contains("$(")) continue
                for (part in include.split(';')) {
                    val rel = part.trim().replace('\\', '/')
                    if (rel.isEmpty()) continue
                    result.add(dir.resolve(rel).toAbsolutePath().normalize())
                }
            }
            return result
        }
    }
}
