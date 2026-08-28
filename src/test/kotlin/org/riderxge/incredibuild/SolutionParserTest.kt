package org.riderxge.incredibuild

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.riderxge.incredibuild.sln.SolutionConfiguration
import org.riderxge.incredibuild.sln.SolutionParser
import java.nio.file.Files
import java.nio.file.Paths

class SolutionParserTest {
    private val sln = """
        Microsoft Visual Studio Solution File, Format Version 12.00
        # Visual Studio Version 17
        Project("{2150E333-8FDC-42A3-9474-1A3956D46DE8}") = "Native", "Native", "{AAAA0000-0000-0000-0000-000000000001}"
        EndProject
        Project("{9A19103F-16F7-4668-BE54-9A1E7A4F7556}") = "App", "App\App.csproj", "{11111111-1111-1111-1111-111111111111}"
        EndProject
        Project("{8BC9CEB8-8B4A-11D0-8D11-00A0C91BC942}") = "CppCliBridge", "CppCliBridge\CppCliBridge.vcxproj", "{22222222-2222-2222-2222-222222222222}"
        	ProjectSection(ProjectDependencies) = postProject
        		{33333333-3333-3333-3333-333333333333} = {33333333-3333-3333-3333-333333333333}
        	EndProjectSection
        EndProject
        Project("{8BC9CEB8-8B4A-11D0-8D11-00A0C91BC942}") = "NativeCore", "NativeCore\NativeCore.vcxproj", "{33333333-3333-3333-3333-333333333333}"
        EndProject
        Global
        	GlobalSection(SolutionConfigurationPlatforms) = preSolution
        		Debug|x64 = Debug|x64
        		Release|x64 = Release|x64
        	EndGlobalSection
        	GlobalSection(ProjectConfigurationPlatforms) = postSolution
        		{11111111-1111-1111-1111-111111111111}.Debug|x64.ActiveCfg = Debug|x64
        		{11111111-1111-1111-1111-111111111111}.Debug|x64.Build.0 = Debug|x64
        		{22222222-2222-2222-2222-222222222222}.Debug|x64.ActiveCfg = Debug|x64
        		{22222222-2222-2222-2222-222222222222}.Debug|x64.Build.0 = Debug|x64
        		{33333333-3333-3333-3333-333333333333}.Debug|x64.ActiveCfg = Debug|x64
        		{33333333-3333-3333-3333-333333333333}.Debug|x64.Build.0 = Debug|x64
        		{33333333-3333-3333-3333-333333333333}.Release|x64.ActiveCfg = Release|x64
        	EndGlobalSection
        EndGlobal
    """.trimIndent()

    @Test
    fun parsesProjectsConfigurationsAndDependencies() {
        val file = Paths.get("C:/src/Demo/Demo.sln")
        val model = SolutionParser.parseSlnText(file, sln)

        assertEquals(listOf("App", "CppCliBridge", "NativeCore"), model.projects.map { it.name })
        assertEquals(Paths.get("C:/src/Demo/App/App.csproj"), model.projects[0].path)
        assertFalse(model.projects[0].isCpp)
        assertTrue(model.projects[1].isCpp)
        assertEquals(listOf("{33333333-3333-3333-3333-333333333333}"), model.projects[1].explicitDependencyGuids)

        assertEquals(listOf(SolutionConfiguration("Debug", "x64"), SolutionConfiguration("Release", "x64")), model.configurations)

        val native = model.findByGuid("{33333333-3333-3333-3333-333333333333}")
        assertNotNull(native)
        assertTrue(model.isBuilt(native!!, SolutionConfiguration("Debug", "x64")))
        // Only ActiveCfg, no Build.0 → not built in Release
        assertFalse(model.isBuilt(native, SolutionConfiguration("Release", "x64")))
        // Unknown configuration → not built
        assertFalse(model.isBuilt(model.projects[0], SolutionConfiguration("Release", "x64")))
    }

    @Test
    fun findByPathIsCaseInsensitiveOnWindowsStylePaths() {
        val model = SolutionParser.parseSlnText(Paths.get("C:/src/Demo/Demo.sln"), sln)
        assertNotNull(model.findByPath(Paths.get("C:/src/Demo/app/APP.csproj")))
    }

    @Test
    fun parsesSlnx() {
        val dir = Files.createTempDirectory("slnx")
        val file = dir.resolve("Demo.slnx")
        Files.writeString(
            file,
            """
            <Solution>
              <Configurations>
                <BuildType Name="Debug" />
                <BuildType Name="Release" />
                <Platform Name="x64" />
              </Configurations>
              <Folder Name="/Native/">
                <Project Path="NativeCore/NativeCore.vcxproj" />
              </Folder>
              <Project Path="App/App.csproj" />
            </Solution>
            """.trimIndent()
        )
        val model = SolutionParser.parse(file)
        assertEquals(setOf("NativeCore", "App"), model.projects.map { it.name }.toSet())
        assertEquals(dir.resolve("App/App.csproj").toAbsolutePath().normalize(), model.findByPath(dir.resolve("App/App.csproj"))!!.path)
        assertEquals(listOf(SolutionConfiguration("Debug", "x64"), SolutionConfiguration("Release", "x64")), model.configurations)
    }
}
