package org.riderxge.incredibuild

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.riderxge.incredibuild.build.Diagnostic
import org.riderxge.incredibuild.build.MsBuildOutputParser
import org.riderxge.incredibuild.ib.BuildOperation
import org.riderxge.incredibuild.ib.IncrediBuildCommandLine
import org.riderxge.incredibuild.settings.BuildEngine
import org.riderxge.incredibuild.settings.IncrediBuildSettingsState
import org.riderxge.incredibuild.settings.TriState
import java.nio.file.Paths

class CommandLineAndOutputTest {
    private val exe = Paths.get("C:/Program Files (x86)/IncrediBuild/BuildConsole.exe")

    @Test
    fun solutionBuildUsesVisualStudioSyntax() {
        val settings = IncrediBuildSettingsState().apply { showAgent = true; showTime = false; msBuildArgs = "/v:m"; restorePackages = false }
        val args = IncrediBuildCommandLine(exe, settings).build(
            IncrediBuildCommandLine.Spec(
                solution = Paths.get("C:/src/Demo/Demo.sln"),
                operation = BuildOperation.REBUILD,
                configuration = "Debug",
                platform = "Any CPU",
                projectNames = listOf("NativeCore", "CppCliBridge"),
                title = "Rider: Rebuild",
            )
        )
        assertEquals(
            listOf(
                "C:\\src\\Demo\\Demo.sln", "/REBUILD", "/CFG=Debug|Any CPU", "/PRJ=NativeCore,CppCliBridge",
                "/USEMSBUILD=64", "/MSBUILDARGS=/v:m", "/NOLOGO", "/SHOWAGENT", "/TITLE=Rider: Rebuild"
            ),
            args.map { it.replace('/', '/').let { a -> if (a.startsWith("C:")) a.replace('/', '\\') else a } }
        )
    }

    @Test
    fun overridesAndExtraArgsAreAppended() {
        val settings = IncrediBuildSettingsState().apply {
            showAgent = false; showTime = false; buildEngine = BuildEngine.DEVENV
            maxCpus = 12; avoidLocal = TriState.ON; standalone = TriState.OFF; stopOnErrors = true
            extraArgs = "/USECLOUDHELPERS=TRUE \"/ADDKNOWNPATH=VS:C:\\Program Files\\VS\""
        }
        val args = IncrediBuildCommandLine(exe, settings).build(
            IncrediBuildCommandLine.Spec(Paths.get("C:/src/Demo/Demo.sln"), BuildOperation.BUILD, "Release", "x64", withoutDependencies = true)
        )
        assertTrue("/NORECURSE" in args)
        assertTrue("/USEMSBUILD=64" !in args)
        assertEquals(listOf("/STOPONERRORS", "/MAXCPUS=12", "/AVOIDLOCAL=ON", "/STANDALONE=NO", "/USECLOUDHELPERS=TRUE", "/ADDKNOWNPATH=VS:C:\\Program Files\\VS"), args.takeLast(6))
    }

    @Test
    fun slnxFallsBackToMsBuildCommand() {
        val settings = IncrediBuildSettingsState().apply { showAgent = false; showTime = false }
        val args = IncrediBuildCommandLine(exe, settings).build(
            IncrediBuildCommandLine.Spec(
                Paths.get("C:/src/Demo/Demo.slnx"), BuildOperation.CLEAN, "Debug", "x64",
                msBuildExe = Paths.get("C:/VS/MSBuild/Current/Bin/MSBuild.exe")
            )
        )
        assertEquals("/COMMAND=C:\\VS\\MSBuild\\Current\\Bin\\MSBuild.exe C:\\src\\Demo\\Demo.slnx /t:Clean \"/p:Configuration=Debug\" \"/p:Platform=x64\" /m /nologo /restore", args[0])
    }

    @Test
    fun restoreIsAddedOnceByDefault() {
        val settings = IncrediBuildSettingsState().apply { showAgent = false; showTime = false }
        val spec = IncrediBuildCommandLine.Spec(Paths.get("C:/src/Demo/Demo.sln"), BuildOperation.BUILD, "Debug", "x64")
        assertTrue("/MSBUILDARGS=/restore" in IncrediBuildCommandLine(exe, settings).build(spec))

        settings.msBuildArgs = "-restore /v:m"
        assertTrue("/MSBUILDARGS=-restore /v:m" in IncrediBuildCommandLine(exe, settings).build(spec))

        settings.msBuildArgs = "/v:m"
        assertTrue("/MSBUILDARGS=/restore /v:m" in IncrediBuildCommandLine(exe, settings).build(spec))
    }

    @Test
    fun splitsQuotedExtraArgs() {
        assertEquals(listOf("/A", "/B=x y", "/C"), IncrediBuildCommandLine.splitExtraArgs("  /A \"/B=x y\"   /C "))
        assertEquals(emptyList<String>(), IncrediBuildCommandLine.splitExtraArgs("   "))
    }

    @Test
    fun parsesLocatedDiagnostics() {
        val d = MsBuildOutputParser.parseLine("""3>C:\src\NativeCore\core.cpp(12,5): error C2065: 'foo': undeclared identifier [C:\src\NativeCore\NativeCore.vcxproj]""")!!
        assertEquals(Diagnostic.Severity.ERROR, d.severity)
        assertEquals("""C:\src\NativeCore\core.cpp""", d.file)
        assertEquals(12, d.line)
        assertEquals(5, d.column)
        assertEquals("C2065", d.code)

        val w = MsBuildOutputParser.parseLine("""App\Program.cs(7,13): warning CS0219: The variable 'x' is assigned but never used""")!!
        assertEquals(Diagnostic.Severity.WARNING, w.severity)
        assertEquals(7, w.line)
        assertEquals(13, w.column)

        val f = MsBuildOutputParser.parseLine("""core.cpp(3): fatal error C1083: Cannot open include file: 'x.h'""")!!
        assertEquals(Diagnostic.Severity.ERROR, f.severity)
        assertEquals(0, f.column)
    }

    @Test
    fun parsesUnlocatedDiagnostics() {
        val link = MsBuildOutputParser.parseLine("LINK : fatal error LNK1104: cannot open file 'NativeCore.lib'")!!
        assertEquals(Diagnostic.Severity.ERROR, link.severity)
        assertNull(link.file)
        assertEquals("LNK1104", link.code)

        val msb = MsBuildOutputParser.parseLine("MSBUILD : error MSB1009: Project file does not exist.")!!
        assertEquals("MSB1009", msb.code)

        assertNull(MsBuildOutputParser.parseLine("  NativeCore.cpp (Agent 'Helper01', CPU 3)"))
        assertNull(MsBuildOutputParser.parseLine("Build succeeded."))
        assertNull(MsBuildOutputParser.parseLine("1>------ Build started: Project: NativeCore, Configuration: Debug x64 ------"))
    }

    @Test
    fun parsesSummaryLines() {
        assertEquals(2 to true, MsBuildOutputParser.parseSummary("    2 Error(s)"))
        assertEquals(0 to false, MsBuildOutputParser.parseSummary("0 Warning(s)"))
        assertNull(MsBuildOutputParser.parseSummary("Time Elapsed 00:00:05.12"))
    }
}
