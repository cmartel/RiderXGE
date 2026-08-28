package org.riderxge.incredibuild

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.riderxge.incredibuild.sln.ProjectGraph
import org.riderxge.incredibuild.sln.SolutionParser
import java.nio.file.Files
import java.nio.file.Path

class ProjectGraphTest {

    /**
     * App (C#) → CppCliBridge (C++/CLI) → NativeCore (C++)
     * App (C#) → Shared (C#)
     * Tool (C#) → Shared (C#)
     */
    private fun createSolution(): Path {
        val dir = Files.createTempDirectory("graph")
        fun proj(rel: String, vararg refs: String) {
            val f = dir.resolve(rel)
            Files.createDirectories(f.parent)
            val items = refs.joinToString("\n") { "    <ProjectReference Include=\"$it\" />" }
            Files.writeString(f, "<Project Sdk=\"Microsoft.NET.Sdk\">\n  <ItemGroup>\n$items\n  </ItemGroup>\n</Project>\n")
        }
        proj("App/App.csproj", "..\\CppCliBridge\\CppCliBridge.vcxproj", "..\\Shared\\Shared.csproj")
        proj("CppCliBridge/CppCliBridge.vcxproj", "..\\NativeCore\\NativeCore.vcxproj")
        proj("NativeCore/NativeCore.vcxproj")
        proj("Shared/Shared.csproj")
        proj("Tool/Tool.csproj", "..\\Shared\\Shared.csproj", "..\\Missing\\Missing.csproj")
        Files.writeString(
            dir.resolve("Demo.sln"),
            """
            Project("{9A19103F-16F7-4668-BE54-9A1E7A4F7556}") = "App", "App\App.csproj", "{10000000-0000-0000-0000-000000000000}"
            EndProject
            Project("{8BC9CEB8-8B4A-11D0-8D11-00A0C91BC942}") = "CppCliBridge", "CppCliBridge\CppCliBridge.vcxproj", "{20000000-0000-0000-0000-000000000000}"
            EndProject
            Project("{8BC9CEB8-8B4A-11D0-8D11-00A0C91BC942}") = "NativeCore", "NativeCore\NativeCore.vcxproj", "{30000000-0000-0000-0000-000000000000}"
            EndProject
            Project("{9A19103F-16F7-4668-BE54-9A1E7A4F7556}") = "Shared", "Shared\Shared.csproj", "{40000000-0000-0000-0000-000000000000}"
            EndProject
            Project("{9A19103F-16F7-4668-BE54-9A1E7A4F7556}") = "Tool", "Tool\Tool.csproj", "{50000000-0000-0000-0000-000000000000}"
            EndProject
            """.trimIndent()
        )
        return dir.resolve("Demo.sln")
    }

    @Test
    fun cppClosureOfManagedRootIsDependencyOrdered() {
        val model = SolutionParser.parse(createSolution())
        val graph = ProjectGraph.build(model)
        val app = model.projects.first { it.name == "App" }

        assertEquals(listOf("NativeCore", "CppCliBridge"), graph.cppProjectsFor(listOf(app)).map { it.name })
        assertEquals(listOf("NativeCore", "CppCliBridge", "Shared"), graph.transitiveDependencies(listOf(app)).map { it.name })
    }

    @Test
    fun cppRootIsIncludedItself() {
        val model = SolutionParser.parse(createSolution())
        val graph = ProjectGraph.build(model)
        val bridge = model.projects.first { it.name == "CppCliBridge" }
        assertEquals(listOf("NativeCore", "CppCliBridge"), graph.cppProjectsFor(listOf(bridge)).map { it.name })
    }

    @Test
    fun managedOnlyRootHasNothingToDispatch() {
        val model = SolutionParser.parse(createSolution())
        val graph = ProjectGraph.build(model)
        val tool = model.projects.first { it.name == "Tool" }
        assertTrue(graph.cppProjectsFor(listOf(tool)).isEmpty())
        assertEquals(1, graph.danglingReferences[tool.path]!!.size)
    }

    @Test
    fun wholeSolutionClosureDeduplicates() {
        val model = SolutionParser.parse(createSolution())
        val graph = ProjectGraph.build(model)
        assertEquals(listOf("NativeCore", "CppCliBridge"), graph.cppProjectsFor(model.projects).map { it.name })
    }
}
