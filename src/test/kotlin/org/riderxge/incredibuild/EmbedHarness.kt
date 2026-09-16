package org.riderxge.incredibuild

import org.riderxge.incredibuild.ui.embed.EmbedState
import org.riderxge.incredibuild.ui.embed.NativeWindowPanel
import org.riderxge.incredibuild.ui.embed.NativeWindows
import org.riderxge.incredibuild.ui.embed.WindowCrop
import java.awt.BorderLayout
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.swing.JFrame
import javax.swing.SwingUtilities

/**
 * Manual integration check for the native-window embedding, run outside the IDE against the real Build Monitor:
 *
 *     ./gradlew.bat embedHarness
 *
 * It drives the shipping [NativeWindowPanel] through the whole cycle – adopt, resize, re-attach after the host peer
 * is recreated, release – and verifies each step through Win32 rather than by eye. Not part of `test`: it needs a
 * desktop session and an installed IncrediBuild.
 */
object EmbedHarness {

    private const val WS_CAPTION = 0x00C0_0000
    private const val WS_THICKFRAME = 0x0004_0000
    private const val VIEW_CLASS = "TMonitorViewForm"

    private var failures = 0

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.contains("--adopt")) {
            adoptFromBuild()
            return
        }
        val exe = locateBuildMonitor()
        if (exe == null) {
            println("SKIP: BuildMonitor.exe not found (IncrediBuild not installed?)")
            return
        }
        println("Build Monitor: $exe")

        val process = ProcessBuilder(exe.toString()).start()
        try {
            val form = awaitWindow(process.pid())
            if (form == null) {
                println("FAIL: the Build Monitor window never appeared")
                failures++
                return
            }
            println("Monitor form: $form class=${NativeWindows.className(form)} title='${NativeWindows.title(form)}'")
            check("the monitor view pane exists", NativeWindows.findChildWindows(form, "TMonitorViewForm").isNotEmpty())

            // The form itself is adopted; its chrome is pushed outside the host so only the view pane shows.
            val child = form
            val originalStyle = NativeWindows.style(child)
            describeStyle("before embedding", child)

            val frame = JFrame("IncrediBuild embed harness")
            val panel = NativeWindowPanel()
            var state: EmbedState? = null
            SwingUtilities.invokeAndWait {
                frame.contentPane.layout = BorderLayout()
                frame.contentPane.add(panel, BorderLayout.CENTER)
                frame.setSize(900, 500)
                frame.setLocationRelativeTo(null)
                frame.isVisible = true
            }
            Thread.sleep(300)
            SwingUtilities.invokeAndWait { state = panel.embed(child, insets = { WindowCrop.paneInsets(it, VIEW_CLASS) }) }
            println("embed() -> $state")
            check("embed() reports EMBEDDED", state == EmbedState.EMBEDDED)

            val parent = NativeWindows.parentOf(child)
            check("the monitor window has a parent", parent != null)
            check(
                "the parent window belongs to this JVM",
                parent != null && NativeWindows.processIdOf(parent) == ProcessHandle.current().pid()
            )
            check("WS_CHILD is set", NativeWindows.style(child) and 0x4000_0000 != 0)

            Thread.sleep(800)
            checkFills("after embedding", child, parent)
            check("docked: nothing of the monitor is left on the taskbar", taskbarWindows(process.pid()).isEmpty())
            screenshot("embedded", frame)
            describeStyle("at screenshot time", child)
            Thread.sleep(2000)
            describeStyle("2s later", child)

            // Resizing the host must resize the adopted window with it.
            SwingUtilities.invokeAndWait { frame.setSize(1200, 700) }
            Thread.sleep(600)
            checkFills("after resizing the host", child, NativeWindows.parentOf(child))

            // Hiding and re-showing the host recreates the canvas peer, which is what happens when the tool window
            // is undocked or moved; the panel must re-adopt the window instead of losing it.
            SwingUtilities.invokeAndWait {
                frame.contentPane.remove(panel)
                frame.contentPane.revalidate()
            }
            Thread.sleep(300)
            SwingUtilities.invokeAndWait {
                frame.contentPane.add(panel, BorderLayout.CENTER)
                frame.contentPane.revalidate()
            }
            Thread.sleep(600)
            val reParent = NativeWindows.parentOf(child)
            check(
                "re-adopted after the host peer was recreated",
                reParent != null && NativeWindows.processIdOf(reParent) == ProcessHandle.current().pid()
            )
            checkFills("after re-adoption", child, reParent)

            // Releasing must hand the monitor back as a normal, fully chromed window.
            SwingUtilities.invokeAndWait { panel.release() }
            Thread.sleep(400)
            check("released: no longer a child", NativeWindows.parentOf(child) == null)
            check("released: original style restored", NativeWindows.style(child) == originalStyle)
            check("released: window still alive", NativeWindows.isWindow(child))
            check("released: the monitor window is usable again", NativeWindows.isVisible(form))
            check("released: the taskbar button is back", taskbarWindows(process.pid()).isNotEmpty())
            screenshot("released", frame)

            SwingUtilities.invokeAndWait { frame.dispose() }
        } finally {
            process.destroy()
            process.waitFor()
        }

        println(if (failures == 0) "\nRESULT: all checks passed" else "\nRESULT: $failures check(s) FAILED")
        if (failures > 0) System.exit(1)
    }

    /** Tells a re-asserted native frame (VCL rebuilding it) apart from one the app draws itself. */
    private fun describeStyle(stage: String, child: com.sun.jna.platform.win32.WinDef.HWND) {
        val style = NativeWindows.style(child)
        val bits = buildString {
            if (style and WS_CAPTION == WS_CAPTION) append("WS_CAPTION ")
            if (style and WS_THICKFRAME != 0) append("WS_THICKFRAME ")
            if (style and 0x0080_0000 != 0) append("WS_BORDER ")
            if (style and 0x0040_0000 != 0) append("WS_DLGFRAME ")
            if (style and 0x4000_0000 != 0) append("WS_CHILD ")
        }
        val windowRect = NativeWindows.windowRect(child)
        val (clientWidth, clientHeight) = NativeWindows.clientSize(child)
        println(
            "  style $stage: 0x%08X [%s] window=%dx%d client=%dx%d nonClient=%dx%d".format(
                style, bits.trim(), windowRect?.get(2) ?: -1, windowRect?.get(3) ?: -1, clientWidth, clientHeight,
                (windowRect?.get(2) ?: 0) - clientWidth, (windowRect?.get(3) ?: 0) - clientHeight
            )
        )
    }

    /**
     * Reproduces what the plugin does during a build with `/OPENMONITOR`: snapshot the monitor processes, start
     * BuildConsole, then wait for the monitor it spawns and adopt that one.
     */
    private fun adoptFromBuild() {
        val solution = Paths.get("D:/Projects/RiderXGE/samples/InteropDemo/InteropDemo.sln")
        val buildConsole = Paths.get("C:/Program Files (x86)/Incredibuild/BuildConsole.exe")
        if (!Files.isRegularFile(solution) || !Files.isRegularFile(buildConsole)) {
            println("SKIP: sample solution or BuildConsole missing")
            return
        }
        val before = monitorPids()
        println("monitors before: $before")

        // Exactly what BuildMonitorHost watches: any monitor window that is not one of the already-open ones.
        val candidates = {
            NativeWindows.findTopLevelWindows("TBuildMonitorForm", requireVisible = false)
                .filter { NativeWindows.processIdOf(it) !in before }
        }

        // The host has its tab (and canvas) ready before the monitor exists; the frame stands in for it here.
        val frame = JFrame("IncrediBuild adopt harness")
        val panel = NativeWindowPanel()
        SwingUtilities.invokeAndWait {
            frame.contentPane.layout = BorderLayout()
            frame.contentPane.add(panel, BorderLayout.CENTER)
            frame.setSize(1000, 520)
            frame.setLocationRelativeTo(null)
            frame.isVisible = true
        }
        val flicker = DesktopVisibilityWatch(candidates)

        val build = ProcessBuilder(
            buildConsole.toString(), solution.toString(), "/REBUILD", "/CFG=Debug|x64",
            "/USEMSBUILD=64", "/MSBUILDARGS=/restore", "/NOLOGO", "/OPENMONITOR", "/TITLE=harness adopt test"
        ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()

        // Same shape as BuildMonitorHost.dock: re-discover and retry, because a Delphi form destroys and recreates
        // its window handle while it starts up.
        val parked = HashMap<com.sun.jna.platform.win32.WinDef.HWND, IntArray>()
        val start = System.currentTimeMillis()
        var attempts = 0
        var docked = false
        var window: com.sun.jna.platform.win32.WinDef.HWND? = null
        while (System.currentTimeMillis() - start < 25_000 && !docked) {
            window = awaitSettledWindow(candidates, 25_000 - (System.currentTimeMillis() - start), parked) ?: break
            attempts++
            var state: EmbedState? = null
            SwingUtilities.invokeAndWait {
                state = panel.embed(window!!, insets = { WindowCrop.paneInsets(it, VIEW_CLASS) }, restoreRect = parked[window!!])
            }
            println("  attempt $attempts on $window -> $state")
            docked = state != EmbedState.FAILED
            if (!docked) Thread.sleep(150)
        }
        val (flickerSamples, flickerSpanMs) = flicker.stop()
        println("docked after ${System.currentTimeMillis() - start} ms and $attempts attempt(s)")
        check("the monitor the build started gets docked", docked)
        println("  desktop-visible: $flickerSamples sample(s) spanning ${flickerSpanMs}ms (sampled every 1ms)")
        // The monitor creates its window already visible, and hiding another process's window only takes effect when
        // that process pumps messages, so roughly one frame of it is unavoidable from outside. This guards against
        // going back to the hundreds of milliseconds an unhurried discovery costs.
        check("the monitor is off the desktop within a frame or two", flickerSpanMs <= 40)
        if (docked) {
            Thread.sleep(1500)
            checkFills("adopted from the build", window!!, NativeWindows.parentOf(window!!))
            screenshot("adopted", frame)

            // A second build reuses the monitor that is already docked rather than starting another one, and takes
            // it over - moving and resizing it in the process. The plugin nudges it back on a timer; mirror that.
            val reuse = DesktopVisibilityWatch(candidates)
            val refitting = java.util.concurrent.atomic.AtomicBoolean(true)
            val refitter = Thread {
                while (refitting.get()) {
                    SwingUtilities.invokeAndWait { panel.reassert() }
                    Thread.sleep(500)
                }
            }.apply { isDaemon = true; start() }

            val second = ProcessBuilder(
                buildConsole.toString(), solution.toString(), "/REBUILD", "/CFG=Debug|x64",
                "/USEMSBUILD=64", "/MSBUILDARGS=/restore", "/NOLOGO", "/OPENMONITOR", "/TITLE=harness reuse test"
            ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            second.waitFor()
            Thread.sleep(1200)
            refitting.set(false)
            val (reuseSamples, _) = reuse.stop()

            println("  second build exit=${second.exitValue()}, monitors now: ${(monitorPids() - before).size}")
            check("the second build does not put a monitor on the desktop", reuseSamples == 0)
            check("the docked monitor survives the second build", NativeWindows.isWindow(window!!))
            checkFills("after a second build", window!!, NativeWindows.parentOf(window!!))

            // Closing the tab ends the monitor: it must go straight from docked to gone, never back onto the desktop.
            val onClose = DesktopVisibilityWatch(candidates)
            SwingUtilities.invokeAndWait { panel.discard() }
            (monitorPids() - before).forEach { pid -> ProcessHandle.of(pid).ifPresent { it.destroy() } }
            Thread.sleep(600)
            val (closeSamples, closeSpanMs) = onClose.stop()
            println("  desktop-visible while closing: $closeSamples sample(s) spanning ${closeSpanMs}ms")
            check("closing the tab does not put the monitor back on the desktop", closeSamples == 0)
        }
        SwingUtilities.invokeAndWait { panel.release(); frame.dispose() }

        build.waitFor()
        println("build exit=${build.exitValue()}")
        (monitorPids() - before).forEach { pid -> ProcessHandle.of(pid).ifPresent { it.destroy() } }
        println(if (failures == 0) "\nRESULT: all checks passed" else "\nRESULT: $failures check(s) FAILED")
        if (failures > 0) System.exit(1)
    }

    /** Mirrors BuildMonitorHost discovery: park the monitor on sight, return it once its view pane exists. */
    private fun awaitSettledWindow(
        candidates: () -> List<com.sun.jna.platform.win32.WinDef.HWND>,
        timeoutMs: Long,
        parked: MutableMap<com.sun.jna.platform.win32.WinDef.HWND, IntArray>,
    ): com.sun.jna.platform.win32.WinDef.HWND? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val form = candidates().firstOrNull()
            if (form != null) {
                if (!parked.containsKey(form)) NativeWindows.windowRect(form)?.let { parked[form] = it }
                NativeWindows.show(form, 0 /* SW_HIDE */)
                NativeWindows.parkOffScreen(form)
                if (NativeWindows.findChildWindows(form, VIEW_CLASS).isNotEmpty()) return form
            }
            Thread.sleep(1)
        }
        return null
    }

    /**
     * Samples whether the monitor is ever on the desktop as a window of its own before it is docked – the flicker,
     * measured instead of eyeballed. Sampling at 5ms catches anything that lasts a frame.
     */
    private class DesktopVisibilityWatch(private val candidates: () -> List<com.sun.jna.platform.win32.WinDef.HWND>) {
        private val running = java.util.concurrent.atomic.AtomicBoolean(true)
        private val seen = java.util.concurrent.atomic.AtomicInteger()
        private val firstAt = java.util.concurrent.atomic.AtomicLong()
        private val lastAt = java.util.concurrent.atomic.AtomicLong()
        private val thread = Thread {
            while (running.get()) {
                candidates().forEach { window ->
                    val onScreen = NativeWindows.windowRect(window)?.let { it[0] > -10_000 && it[1] > -10_000 } ?: false
                    if (onScreen && NativeWindows.isVisible(window) && NativeWindows.parentOf(window) == null) {
                        val now = System.currentTimeMillis()
                        firstAt.compareAndSet(0, now)
                        lastAt.set(now)
                        seen.incrementAndGet()
                    }
                }
                Thread.sleep(1)
            }
        }.apply { isDaemon = true; start() }

        /** Samples seen on the desktop, and how long the window was there for. */
        fun stop(): Pair<Int, Long> {
            running.set(false)
            thread.join(1_000)
            val span = if (firstAt.get() == 0L) 0 else lastAt.get() - firstAt.get()
            return seen.get() to span
        }
    }

    /**
     * Windows of [pid] that Windows would give a taskbar button: visible, top-level and marked `WS_EX_APPWINDOW`.
     * For a VCL app that is the hidden application window, not the form, which is why docking the form alone used
     * to leave a Build Monitor button on the taskbar.
     */
    private fun taskbarWindows(pid: Long): List<com.sun.jna.platform.win32.WinDef.HWND> =
        NativeWindows.findTopLevelWindows(setOf(pid))
            .filter { NativeWindows.exStyle(it) and 0x0004_0000 != 0 }

    private fun monitorPids(): Set<Long> {
        val pids = HashSet<Long>()
        ProcessHandle.allProcesses().forEach { handle ->
            val command = handle.info().command().orElse(null) ?: return@forEach
            if (command.endsWith("BuildMonitor.exe", ignoreCase = true)) pids.add(handle.pid())
        }
        return pids
    }

    private fun check(what: String, ok: Boolean) {
        println((if (ok) "  ok   " else "  FAIL ") + what)
        if (!ok) failures++
    }

    /**
     * The point of the exercise: the monitor's *view pane* – not the window around it – must cover the host exactly,
     * so the title bar, menu and command-line bar end up clipped outside the tab.
     */
    private fun checkFills(stage: String, child: com.sun.jna.platform.win32.WinDef.HWND, parent: com.sun.jna.platform.win32.WinDef.HWND?) {
        if (parent == null) {
            check("$stage: host known", false)
            return
        }
        val (hostWidth, hostHeight) = NativeWindows.clientSize(parent)
        val hostOrigin = NativeWindows.clientOrigin(parent)
        val pane = NativeWindows.findChildWindows(child, VIEW_CLASS).firstOrNull()
        val paneRect = pane?.let { NativeWindows.windowRect(it) }
        if (hostOrigin == null || paneRect == null) {
            check("$stage: the view pane and host are measurable", false)
            return
        }
        println(
            "  $stage: host=${hostWidth}x$hostHeight at (${hostOrigin[0]},${hostOrigin[1]})" +
                "  pane=${paneRect[2]}x${paneRect[3]} at (${paneRect[0]},${paneRect[1]})"
        )
        check("$stage: the view pane starts at the top-left of the tab", paneRect[0] == hostOrigin[0] && paneRect[1] == hostOrigin[1])
        check("$stage: the view pane fills the tab", paneRect[2] == hostWidth && paneRect[3] == hostHeight)
        val style = NativeWindows.style(child)
        check("$stage: no title bar", style and WS_CAPTION == 0)
        check("$stage: no resize frame", style and WS_THICKFRAME == 0)
    }

    /** Grabs just the harness window, so the result can be eyeballed after an automated run. */
    private fun screenshot(name: String, frame: JFrame) {
        try {
            SwingUtilities.invokeAndWait {
                frame.isAlwaysOnTop = true
                frame.toFront()
                frame.requestFocus()
            }
            Thread.sleep(700)
            val image = java.awt.Robot().createScreenCapture(frame.bounds)
            SwingUtilities.invokeAndWait { frame.isAlwaysOnTop = false }
            val file = Paths.get(System.getProperty("java.io.tmpdir"), "incredibuild-embed-$name.png").toFile()
            javax.imageio.ImageIO.write(image, "png", file)
            println("  screenshot: $file")
        } catch (t: Throwable) {
            println("  screenshot failed: $t")
        }
    }

    private fun awaitWindow(pid: Long, timeoutMs: Long = 20_000): com.sun.jna.platform.win32.WinDef.HWND? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            NativeWindows.findTopLevelWindows(setOf(pid), "TBuildMonitorForm").firstOrNull()?.let { return it }
            Thread.sleep(150)
        }
        return null
    }

    private fun locateBuildMonitor(): Path? {
        val roots = listOfNotNull(System.getenv("ProgramFiles(x86)"), System.getenv("ProgramFiles"))
        for (root in roots) {
            val candidate = Paths.get(root, "IncrediBuild", "BuildMonitor.exe")
            if (Files.isRegularFile(candidate)) return candidate
        }
        return null
    }
}
