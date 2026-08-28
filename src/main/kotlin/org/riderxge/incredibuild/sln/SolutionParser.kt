package org.riderxge.incredibuild.sln

import org.w3c.dom.Element
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory

/** Minimal reader for Visual Studio `.sln` and the XML-based `.slnx` formats. */
object SolutionParser {
    private val PROJECT_LINE = Regex(
        """^\s*Project\("(\{[0-9A-Fa-f-]+})"\)\s*=\s*"([^"]*)"\s*,\s*"([^"]*)"\s*,\s*"(\{[0-9A-Fa-f-]+})"\s*$"""
    )
    private val SOLUTION_FOLDER_TYPE = "{2150E333-8FDC-42A3-9474-1A3956D46DE8}"
    private val DEPENDENCY_LINE = Regex("""^\s*(\{[0-9A-Fa-f-]+})\s*=\s*(\{[0-9A-Fa-f-]+})\s*$""")
    private val SOLUTION_CFG_LINE = Regex("""^\s*([^|=]+)\|([^=]+?)\s*=\s*.*$""")
    private val PROJECT_CFG_LINE = Regex("""^\s*(\{[0-9A-Fa-f-]+})\.([^|]+)\|([^.]+?)\.Build\.0\s*=\s*(.+?)\s*$""")

    @Throws(IOException::class)
    fun parse(file: Path): SolutionModel {
        val name = file.fileName.toString()
        return when {
            name.endsWith(".slnx", ignoreCase = true) -> parseSlnx(file)
            else -> parseSln(file)
        }
    }

    fun parseSln(file: Path): SolutionModel = parseSlnText(file, Files.readString(file))

    fun parseSlnText(file: Path, text: String): SolutionModel {
        val dir = file.toAbsolutePath().parent
        val projects = ArrayList<SolutionProject>()
        val configurations = LinkedHashSet<SolutionConfiguration>()
        val projectCfgs = HashMap<Pair<String, SolutionConfiguration>, String>()

        var current: MutableProjectEntry? = null
        var section: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trimEnd()
            val trimmed = line.trim()
            if (current != null) {
                when {
                    trimmed == "EndProject" -> {
                        if (!current.isFolder) projects += current.toProject(dir)
                        current = null; section = null
                    }
                    trimmed.startsWith("ProjectSection(") -> section = trimmed.substringAfter('(').substringBefore(')')
                    trimmed == "EndProjectSection" -> section = null
                    section == "ProjectDependencies" -> DEPENDENCY_LINE.find(line)?.let { current.deps += it.groupValues[1].uppercase() }
                }
                continue
            }
            PROJECT_LINE.find(line)?.let { m ->
                val (typeGuid, projName, relPath, guid) = m.destructured
                val isFolder = typeGuid.equals(SOLUTION_FOLDER_TYPE, ignoreCase = true)
                current = MutableProjectEntry(projName, relPath, guid.uppercase(), isFolder)
            }
            if (current != null) continue
            when {
                trimmed.startsWith("GlobalSection(") -> section = trimmed.substringAfter('(').substringBefore(')')
                trimmed == "EndGlobalSection" -> section = null
                section == "SolutionConfigurationPlatforms" -> SOLUTION_CFG_LINE.find(line)?.let {
                    configurations += SolutionConfiguration(it.groupValues[1].trim(), it.groupValues[2].trim())
                }
                section == "ProjectConfigurationPlatforms" -> PROJECT_CFG_LINE.find(line)?.let {
                    val key = it.groupValues[1].uppercase() to SolutionConfiguration(it.groupValues[2].trim(), it.groupValues[3].trim())
                    projectCfgs[key] = it.groupValues[4]
                }
            }
        }
        return SolutionModel(file.toAbsolutePath().normalize(), projects, configurations.toList(), projectCfgs)
    }

    private class MutableProjectEntry(val name: String, val relPath: String, val guid: String, val isFolder: Boolean = false) {
        val deps = ArrayList<String>()
        fun toProject(dir: Path) = SolutionProject(name, resolve(dir, relPath), guid, deps.toList())
    }

    fun parseSlnx(file: Path): SolutionModel {
        val dir = file.toAbsolutePath().parent
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
            isExpandEntityReferences = false
        }
        val doc = Files.newInputStream(file).use { factory.newDocumentBuilder().parse(it) }
        val projects = ArrayList<SolutionProject>()
        val configs = LinkedHashSet<SolutionConfiguration>()
        val buildTypes = ArrayList<String>()
        val platforms = ArrayList<String>()
        fun walk(e: Element) {
            val children = e.childNodes
            for (i in 0 until children.length) {
                val c = children.item(i) as? Element ?: continue
                when (c.tagName) {
                    "Project" -> {
                        val rel = c.getAttribute("Path")
                        if (rel.isNotEmpty()) {
                            val path = resolve(dir, rel)
                            val name = c.getAttribute("DisplayName").ifEmpty { path.fileName.toString().substringBeforeLast('.') }
                            projects += SolutionProject(name, path, null)
                        }
                    }
                    "BuildType" -> if (e.tagName == "Configurations") buildTypes += c.getAttribute("Name")
                    "Platform" -> if (e.tagName == "Configurations") platforms += c.getAttribute("Name")
                }
                walk(c)
            }
        }
        walk(doc.documentElement)
        val bt = buildTypes.ifEmpty { listOf("Debug", "Release") }
        val pl = platforms.ifEmpty { listOf("Any CPU") }
        for (b in bt) for (p in pl) configs += SolutionConfiguration(b, p)
        // Solution folders in .slnx never carry project files themselves; projects are only "Project" nodes.
        return SolutionModel(file.toAbsolutePath().normalize(), projects, configs.toList())
    }

    private fun resolve(dir: Path, rel: String): Path {
        val normalized = rel.replace('\\', '/')
        return dir.resolve(normalized).toAbsolutePath().normalize()
    }
}
