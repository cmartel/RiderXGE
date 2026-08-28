package org.riderxge.incredibuild.ib

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.SystemInfo
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import org.riderxge.incredibuild.settings.IncrediBuildSettings
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** Finds the IncrediBuild installation (BuildConsole.exe, BuildMonitor.exe) on this machine. */
object IncrediBuildLocator {
    private val LOG = logger<IncrediBuildLocator>()
    private const val REG_KEY_WOW = "SOFTWARE\\WOW6432Node\\Xoreax\\IncrediBuild\\Builder"
    private const val REG_KEY = "SOFTWARE\\Xoreax\\IncrediBuild\\Builder"

    /** BuildConsole.exe, honouring the explicit path from settings first. */
    fun buildConsole(): Path? {
        val configured = IncrediBuildSettings.getInstance().state.buildConsolePath?.trim().orEmpty()
        if (configured.isNotEmpty()) {
            val p = Paths.get(configured)
            return if (Files.isRegularFile(p)) p else null
        }
        return detectBuildConsole()
    }

    fun installFolder(): Path? = buildConsole()?.parent

    fun buildMonitor(): Path? = installFolder()?.resolve("BuildMonitor.exe")?.takeIf { Files.isRegularFile(it) }

    fun detectBuildConsole(): Path? =
        detectInstallFolder()?.resolve("BuildConsole.exe")?.takeIf { Files.isRegularFile(it) }

    fun detectInstallFolder(): Path? {
        if (!SystemInfo.isWindows) return null
        registryFolder()?.let { return it }
        val candidates = listOfNotNull(
            System.getenv("ProgramFiles(x86)"),
            System.getenv("ProgramFiles"),
            System.getenv("ProgramW6432")
        ).map { Paths.get(it, "IncrediBuild") }
        return candidates.firstOrNull { Files.isRegularFile(it.resolve("BuildConsole.exe")) }
    }

    private fun registryFolder(): Path? {
        for (key in listOf(REG_KEY_WOW, REG_KEY)) {
            try {
                if (Advapi32Util.registryValueExists(WinReg.HKEY_LOCAL_MACHINE, key, "Folder")) {
                    val folder = Advapi32Util.registryGetStringValue(WinReg.HKEY_LOCAL_MACHINE, key, "Folder")
                    val p = Paths.get(folder)
                    if (Files.isRegularFile(p.resolve("BuildConsole.exe"))) return p
                }
            } catch (t: Throwable) {
                LOG.debug("Registry lookup failed for $key", t)
            }
        }
        return null
    }

    /** Installed IncrediBuild version text from the registry, if available. */
    fun versionText(): String? {
        if (!SystemInfo.isWindows) return null
        for (key in listOf(REG_KEY_WOW, REG_KEY)) {
            try {
                if (Advapi32Util.registryValueExists(WinReg.HKEY_LOCAL_MACHINE, key, "VersionText")) {
                    return Advapi32Util.registryGetStringValue(WinReg.HKEY_LOCAL_MACHINE, key, "VersionText")
                }
            } catch (t: Throwable) {
                LOG.debug("Registry lookup failed for $key", t)
            }
        }
        return null
    }

    /**
     * BuildConsole writes console output in the OEM code page (it converts with CharToOem by default),
     * so decode its output accordingly instead of with the JVM default charset.
     */
    fun consoleCharset(): Charset {
        if (!SystemInfo.isWindows) return Charset.defaultCharset()
        return try {
            val oem = Advapi32Util.registryGetStringValue(
                WinReg.HKEY_LOCAL_MACHINE, "SYSTEM\\CurrentControlSet\\Control\\Nls\\CodePage", "OEMCP"
            )
            Charset.forName("IBM$oem")
        } catch (t: Throwable) {
            LOG.debug("OEM code page lookup failed", t)
            runCatching { Charset.forName("IBM437") }.getOrDefault(Charset.defaultCharset())
        }
    }
}
