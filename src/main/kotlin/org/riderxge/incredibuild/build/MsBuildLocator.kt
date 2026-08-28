package org.riderxge.incredibuild.build

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** Locates `MSBuild.exe` of the newest Visual Studio via `vswhere` (only needed for `.slnx` solutions). */
object MsBuildLocator {
    fun find(): Path? {
        val pf86 = System.getenv("ProgramFiles(x86)") ?: return null
        val vswhere = Paths.get(pf86, "Microsoft Visual Studio", "Installer", "vswhere.exe")
        if (!Files.isRegularFile(vswhere)) return null
        return try {
            val cmd = GeneralCommandLine(
                vswhere.toString(), "-latest", "-products", "*", "-requires", "Microsoft.Component.MSBuild",
                "-find", "MSBuild\\**\\Bin\\MSBuild.exe"
            )
            val out = CapturingProcessHandler(cmd).runProcess(20_000)
            out.stdoutLines.map { it.trim() }.firstOrNull { it.isNotEmpty() }?.let { Paths.get(it) }?.takeIf { Files.isRegularFile(it) }
        } catch (_: Exception) {
            null
        }
    }
}
