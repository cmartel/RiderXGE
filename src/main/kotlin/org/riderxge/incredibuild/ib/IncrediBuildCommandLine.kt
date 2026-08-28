package org.riderxge.incredibuild.ib

import org.riderxge.incredibuild.settings.BuildEngine
import org.riderxge.incredibuild.settings.IncrediBuildSettingsState
import org.riderxge.incredibuild.settings.TriState
import java.nio.file.Path

enum class BuildOperation(val label: String, val vsSwitch: String?, val msbuildTarget: String) {
    BUILD("Build", null, "Build"),
    REBUILD("Rebuild", "/REBUILD", "Rebuild"),
    CLEAN("Clean", "/CLEAN", "Clean");
}

/**
 * Pure builder that turns a build request into `BuildConsole.exe` arguments. Kept free of IDE types so it is
 * unit-testable. Argument values are *not* quoted here – the caller hands each element to
 * [com.intellij.execution.configurations.GeneralCommandLine], which quotes per Windows rules.
 */
class IncrediBuildCommandLine(
    private val buildConsole: Path,
    private val settings: IncrediBuildSettingsState,
) {
    data class Spec(
        /** `.sln` (or `.slnx`) file, absolute. */
        val solution: Path,
        val operation: BuildOperation,
        val configuration: String,
        val platform: String,
        /** Solution project *names* to restrict the build to; empty = whole solution. */
        val projectNames: List<String> = emptyList(),
        /** `/NORECURSE`: do not build dependencies of the selected projects. */
        val withoutDependencies: Boolean = false,
        val title: String? = null,
        /** For `.slnx` (unsupported by BuildConsole's VS syntax) the build goes through `/COMMAND=msbuild ...`. */
        val msBuildExe: Path? = null,
    )

    val exePath: String get() = buildConsole.toString()

    fun build(spec: Spec): List<String> {
        val args = ArrayList<String>()
        val isSln = spec.solution.fileName.toString().endsWith(".sln", ignoreCase = true)
        if (isSln) {
            args += spec.solution.toString()
            spec.operation.vsSwitch?.let { args += it }
            args += "/CFG=${spec.configuration}|${spec.platform}"
            if (spec.projectNames.isNotEmpty()) args += "/PRJ=${spec.projectNames.joinToString(",")}"
            if (spec.withoutDependencies) args += "/NORECURSE"
            settings.buildEngine.switch?.let { args += it }
            val msbuildArgs = msBuildArguments()
            if (settings.buildEngine != BuildEngine.DEVENV && msbuildArgs.isNotEmpty()) args += "/MSBUILDARGS=$msbuildArgs"
        } else {
            // .slnx or a single project file: drive MSBuild directly under IncrediBuild's automatic interception.
            val msbuild = spec.msBuildExe?.toString() ?: "msbuild.exe"
            val cmd = StringBuilder()
            cmd.append(quoteForShell(msbuild)).append(' ').append(quoteForShell(spec.solution.toString()))
            cmd.append(" /t:").append(spec.operation.msbuildTarget)
            cmd.append(" \"/p:Configuration=").append(spec.configuration).append('"')
            cmd.append(" \"/p:Platform=").append(spec.platform).append('"')
            cmd.append(" /m /nologo")
            msBuildArguments().takeIf { it.isNotEmpty() }?.let { cmd.append(' ').append(it) }
            args += "/COMMAND=$cmd"
        }

        args += "/NOLOGO"
        if (settings.showAgent) args += "/SHOWAGENT"
        if (settings.showTime) args += "/SHOWTIME"
        if (settings.openMonitor) args += "/OPENMONITOR"
        if (settings.stopOnErrors) args += "/STOPONERRORS"
        if (settings.beepOnFinish) args += "/BEEP"
        if (settings.maxCpus > 0) args += "/MAXCPUS=${settings.maxCpus}"
        when (settings.avoidLocal) {
            TriState.ON -> args += "/AVOIDLOCAL=ON"
            TriState.OFF -> args += "/AVOIDLOCAL=OFF"
            TriState.DEFAULT -> {}
        }
        when (settings.standalone) {
            TriState.ON -> args += "/STANDALONE=YES"
            TriState.OFF -> args += "/STANDALONE=NO"
            TriState.DEFAULT -> {}
        }
        spec.title?.let { args += "/TITLE=$it" }
        args += splitExtraArgs(settings.extraArgs.orEmpty())
        return args
    }

    /** `/restore` (when enabled) followed by the user's additional MSBuild arguments. */
    private fun msBuildArguments(): String {
        val user = settings.msBuildArgs?.trim().orEmpty()
        val restore = if (settings.restorePackages && !user.contains("/restore", ignoreCase = true) && !user.contains("-restore", ignoreCase = true)) "/restore" else ""
        return listOf(restore, user).filter { it.isNotEmpty() }.joinToString(" ")
    }

    companion object {
        fun quoteForShell(s: String): String = if (s.any { it == ' ' || it == '\t' }) "\"$s\"" else s

        /** Splits a free-form argument string, honouring double quotes. */
        fun splitExtraArgs(text: String): List<String> {
            val result = ArrayList<String>()
            val cur = StringBuilder()
            var inQuotes = false
            var hasToken = false
            for (ch in text) {
                when {
                    ch == '"' -> { inQuotes = !inQuotes; hasToken = true }
                    ch.isWhitespace() && !inQuotes -> {
                        if (hasToken) { result += cur.toString(); cur.setLength(0); hasToken = false }
                    }
                    else -> { cur.append(ch); hasToken = true }
                }
            }
            if (hasToken) result += cur.toString()
            return result
        }
    }
}
