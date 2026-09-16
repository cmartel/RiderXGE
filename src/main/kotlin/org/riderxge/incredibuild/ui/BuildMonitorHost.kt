package org.riderxge.incredibuild.ui

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.content.Content
import com.sun.jna.platform.win32.WinDef.HWND
import org.riderxge.incredibuild.ib.IncrediBuildLocator
import org.riderxge.incredibuild.settings.IncrediBuildSettings
import org.riderxge.incredibuild.settings.MonitorPlacement
import org.riderxge.incredibuild.ui.embed.EmbedState
import org.riderxge.incredibuild.ui.embed.NativeWindowPanel
import org.riderxge.incredibuild.ui.embed.NativeWindows
import org.riderxge.incredibuild.ui.embed.Win32
import org.riderxge.incredibuild.ui.embed.WindowCrop
import org.riderxge.incredibuild.ui.embed.WindowInsets

/**
 * Shows IncrediBuild's Build Monitor, either as its own window (what the Visual Studio add-in does when it is not
 * docked) or docked into the IncrediBuild tool window by reparenting the monitor's window into a tab.
 *
 * The monitor is a separate process; docking borrows its window and always hands it back intact, so a failed or
 * closed embedding degrades to a normal Build Monitor window rather than losing it.
 */
@Service(Service.Level.PROJECT)
class BuildMonitorHost(private val project: Project) : Disposable {

    private var panel: NativeWindowPanel? = null
    private var content: Content? = null

    /**
     * The monitor shown in the tab. The process is remembered by id read from the window itself rather than as a
     * [ProcessHandle]: looking a handle up can come back empty, and a docked monitor with no handle used to be
     * treated as one the tab does not own, which put it back on the desktop instead of closing it.
     */
    private class DockedMonitor(val window: HWND, val pid: Long)

    private var docked: DockedMonitor? = null

    /** Keeps the docked window where it belongs while it is in the tab; see [startGeometryWatch]. */
    private var geometryWatch: java.util.concurrent.ScheduledFuture<*>? = null

    /**
     * The monitor window hidden just before adoption. Releasing an embedded window already makes it visible again;
     * this covers the case where the tab was never shown, so the adoption never happened.
     */
    private var hiddenForm: HWND? = null

    /** Opens the Build Monitor on user request (menu/toolbar action). */
    fun open(activate: Boolean) {
        val exe = IncrediBuildLocator.buildMonitor()
        if (exe == null) {
            notify("BuildMonitor.exe was not found in the IncrediBuild installation.", NotificationType.ERROR)
            return
        }
        if (placement() != MonitorPlacement.EMBEDDED || !NativeWindows.isSupported) {
            launchDetached(exe.toString())
            return
        }
        // An embedded monitor is already up: just bring its tab forward.
        if (panel?.isEmbedding == true) {
            onEdt { showTab(activate) }
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val process = try {
                GeneralCommandLine(exe.toString()).createProcess().toHandle()
            } catch (t: Throwable) {
                LOG.warn("Cannot start the Build Monitor", t)
                notify("Cannot start the Build Monitor: ${t.message}", NotificationType.ERROR)
                return@executeOnPooledThread
            }
            // The tab (and with it the canvas to adopt into) is made ready first, so the monitor can be taken over
            // the moment its window exists instead of appearing on the desktop first.
            prepareTab(activate)
            val pids = setOf(process.pid())
            dock({ NativeWindows.findTopLevelWindows(pids, MONITOR_WINDOW_CLASS, requireVisible = false) }, process, activate)
        }
    }

    /**
     * Adopts the monitor that `BuildConsole /OPENMONITOR` spawns for a build. [before] is the set of monitor
     * process ids that existed just before the build started, so an already open monitor is left alone.
     */
    fun adoptSpawnedMonitor(before: Set<Long>) {
        if (placement() != MonitorPlacement.EMBEDDED || !NativeWindows.isSupported) return
        // A monitor may already be docked from an earlier build. BuildConsole either reuses it - in which case the
        // tab is already showing this build and only needs bringing forward - or starts a second one, which has to
        // take the tab over. Bailing out here is what used to leave that second monitor floating on the desktop.
        val replacing = panel?.isEmbedding == true
        if (replacing) onEdt { showTab(activate = false) }
        ApplicationManager.getApplication().executeOnPooledThread {
            // Watch for the monitor's window rather than for its process: the process list is much coarser to poll,
            // and by the time a new process turns up in it the monitor has usually already put itself on screen.
            val candidates = {
                NativeWindows.findTopLevelWindows(MONITOR_WINDOW_CLASS, requireVisible = false)
                    .filter { NativeWindows.processIdOf(it) !in before }
            }
            val parked = HashMap<HWND, IntArray>()
            val first = awaitMonitorWindow(candidates, SPAWN_TIMEOUT_MS, parked, requireViewPane = false)
            if (first == null) {
                LOG.info(
                    if (replacing) "The build reused the docked Build Monitor; keeping it"
                    else "The build did not start a Build Monitor within ${SPAWN_TIMEOUT_MS}ms; nothing to dock"
                )
                return@executeOnPooledThread
            }
            val pid = NativeWindows.processIdOf(first)
            LOG.info("Build Monitor started by the build: $pid")
            if (replacing) {
                LOG.info("Replacing the monitor already in the tab with the one this build started")
                ApplicationManager.getApplication().invokeAndWait({ dropDockedMonitor() }, ModalityState.any())
            }
            prepareTab(activate = false)
            dock(candidates, ProcessHandle.of(pid).orElse(null), activate = false, parked = parked)
        }
    }

    /**
     * Finds the monitor window among [candidates] and docks it, retrying while it keeps slipping away: a Delphi form
     * destroys and recreates its window handle during start-up, so the window seen a few milliseconds in is often not
     * the one it ends up with. Each attempt re-discovers the window rather than reusing a handle that may be dead.
     */
    private fun dock(
        candidates: () -> List<HWND>,
        process: ProcessHandle?,
        activate: Boolean,
        parked: MutableMap<HWND, IntArray> = HashMap(),
    ) {
        val deadline = System.currentTimeMillis() + DOCK_TIMEOUT_MS
        var attempt = 0
        var lastWindow: HWND? = null
        while (System.currentTimeMillis() < deadline) {
            val remaining = deadline - System.currentTimeMillis()
            val window = awaitMonitorWindow(candidates, remaining, parked, requireViewPane = true) ?: break
            lastWindow = window
            attempt++
            when (tryDock(window, process, activate, parked[window])) {
                EmbedState.EMBEDDED, EmbedState.PENDING -> return
                EmbedState.FAILED -> {
                    LOG.info("Docking attempt $attempt did not take (window $window went away); retrying")
                    Thread.sleep(DOCK_RETRY_DELAY_MS)
                }
            }
        }
        if (attempt == 0) {
            LOG.warn("The Build Monitor never showed a window to dock; leaving it as a separate window")
        } else {
            LOG.warn("Giving up on docking the Build Monitor after $attempt attempt(s); leaving it as a separate window")
        }
        // Discovery hid and parked every monitor window it saw; put them back so the user still gets a usable one.
        for ((window, rect) in parked) {
            if (!NativeWindows.isWindow(window)) continue
            NativeWindows.setBounds(
                window, rect[0], rect[1], rect[2], rect[3],
                Win32.SWP_NOZORDER or Win32.SWP_NOACTIVATE
            )
        }
        (candidates() + lastWindow)
            .filterNotNull()
            .distinct()
            .filter { NativeWindows.isWindow(it) }
            .forEach { NativeWindows.show(it, Win32.SW_SHOW) }
        onEdt {
            docked = null
            hiddenForm = null
            closeTab()
        }
        notify(
            "The Build Monitor window could not be docked; it is shown as a separate window.",
            NotificationType.WARNING
        )
    }

    /** Gives the monitor back as a normal window and drops the tab. */
    fun detachToWindow() {
        onEdt {
            stopGeometryWatch()
            docked = null // it lives on as its own window from here; do not end it
            panel?.release()
            restoreHiddenForm()
            closeTab()
        }
    }

    /** Ends the docked monitor and lets go of its window, keeping the tab itself. Must run on the EDT. */
    private fun dropDockedMonitor() {
        stopGeometryWatch()
        endDockedProcess()
        panel?.discard()
        hiddenForm = null
    }

    /**
     * The Build Monitor moves and resizes itself when a build takes it over - BuildConsole reuses a monitor that is
     * already running rather than starting a second one - which inside the tab would leave it offset, or scrolled
     * out of view entirely. Nudging it back costs two Win32 reads when nothing has moved.
     */
    private fun startGeometryWatch() {
        stopGeometryWatch()
        geometryWatch = com.intellij.util.concurrency.AppExecutorUtil.getAppScheduledExecutorService()
            .scheduleWithFixedDelay(
                {
                    if (project.isDisposed) return@scheduleWithFixedDelay
                    onEdt {
                        panel?.takeIf { it.isEmbedding }?.reassert()
                    }
                },
                GEOMETRY_CHECK_MS, GEOMETRY_CHECK_MS, java.util.concurrent.TimeUnit.MILLISECONDS
            )
    }

    private fun stopGeometryWatch() {
        geometryWatch?.cancel(false)
        geometryWatch = null
    }

    /** Ends the monitor the tab owns, if it is still running. */
    private fun endDockedProcess() {
        val monitor = docked ?: return
        docked = null
        val handle = ProcessHandle.of(monitor.pid).orElse(null)
        if (handle == null) {
            LOG.info("Docked Build Monitor process ${monitor.pid} is already gone")
            return
        }
        handle.destroy()
    }

    /** Makes sure the monitor is a visible window again once the tab lets go of it. */
    private fun restoreHiddenForm() {
        val form = hiddenForm ?: return
        hiddenForm = null
        if (NativeWindows.isWindow(form)) NativeWindows.show(form, Win32.SW_SHOW)
    }

    fun close() {
        onEdt { closeTab() }
    }

    override fun dispose() {
        stopGeometryWatch()
        hiddenForm = null
        if (docked != null) {
            endDockedProcess()
            panel?.discard()
        } else {
            panel?.release()
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /** One docking attempt. Returns what the panel made of the window; the caller decides whether to retry. */
    private fun tryDock(form: HWND, process: ProcessHandle?, activate: Boolean, restoreRect: IntArray?): EmbedState {
        // Keep it off the desktop for the moment it takes to hop to the EDT; adopting makes it visible again.
        NativeWindows.show(form, Win32.SW_HIDE)
        var state = EmbedState.FAILED
        ApplicationManager.getApplication().invokeAndWait({
            if (project.isDisposed) return@invokeAndWait
            val hosted = ensurePanel()
            state = hosted.embed(form, ::monitorChromeInsets, restoreRect)
            if (state == EmbedState.FAILED) return@invokeAndWait

            val pid = NativeWindows.processIdOf(form).takeIf { it != 0L } ?: process?.pid() ?: 0L
            LOG.info("Build Monitor docked ($state), process $pid")
            docked = DockedMonitor(form, pid)
            hiddenForm = form
            // The monitor can exit on its own (it has a close button of its own, cropped out of the tab but still
            // reachable by other means); the tab would then be left showing nothing, so drop it.
            ProcessHandle.of(pid).ifPresent { handle ->
                handle.onExit().thenRun {
                    onEdt {
                        if (!project.isDisposed && docked?.pid == pid) closeTab()
                    }
                }
            }
            startGeometryWatch()
            // PENDING: the tab is not on screen yet, addNotify() adopts it as soon as it is.
            showTab(activate)
        }, ModalityState.any())
        return state
    }

    /**
     * Runs on the EDT with [ModalityState.any].
     *
     * The default modality would be inherited from whatever started the work – for a build that is the build's own
     * progress – and the runnable would then be dropped once that progress finishes, which is exactly when the
     * monitor needs docking.
     */
    private fun onEdt(block: () -> Unit) {
        ApplicationManager.getApplication().invokeLater(block, ModalityState.any())
    }

    /** Creates the tab up front so the canvas to adopt into exists before the monitor's window does. */
    private fun prepareTab(activate: Boolean) {
        ApplicationManager.getApplication().invokeAndWait({
            if (project.isDisposed) return@invokeAndWait
            ensurePanel()
            showTab(activate)
        }, ModalityState.any())
    }

    /** Leaves only the monitor's view pane visible inside the tab. */
    private fun monitorChromeInsets(form: HWND): WindowInsets = WindowCrop.paneInsets(form, MONITOR_VIEW_CLASS)

    private fun ensurePanel(): NativeWindowPanel {
        panel?.let { return it }
        val created = NativeWindowPanel()
        panel = created
        return created
    }

    private fun showTab(activate: Boolean) {
        val hosted = panel ?: return
        val existing = content
        if (existing != null && existing.isValid) {
            IncrediBuildToolWindow.selectContent(project, existing, activate)
            return
        }
        val toolbar = DefaultActionGroup().apply {
            add(DetachAction())
            add(RelaunchAction())
        }
        val created = IncrediBuildToolWindow.addTab(project, MONITOR_TAB_TITLE, hosted, toolbar, activate, closeable = true)
            ?: return
        content = created
        Disposer.register(created, hosted)
        Disposer.register(created) {
            // Tab closed (by the user or with the project). The monitor goes with it, so end the process and drop
            // its window rather than restoring it first - restoring would flash it onto the desktop for the moment
            // before it dies. Only a monitor this tab does not own is handed back intact.
            stopGeometryWatch()
            if (docked != null) {
                endDockedProcess()
                hosted.discard()
                hiddenForm = null
            } else {
                hosted.release()
                restoreHiddenForm()
            }
            if (content === created) {
                content = null
                panel = null
            }
        }
    }

    private fun closeTab() {
        val existing = content ?: return
        content = null
        panel = null
        IncrediBuildToolWindow.removeContent(project, existing)
    }

    private fun launchDetached(exe: String) {
        try {
            GeneralCommandLine(exe).createProcess()
        } catch (t: Throwable) {
            LOG.warn("Cannot start the Build Monitor", t)
            notify("Cannot start the Build Monitor: ${t.message}", NotificationType.ERROR)
        }
    }

    private fun placement(): MonitorPlacement = IncrediBuildSettings.getInstance().state.monitorPlacement

    private fun notify(text: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup("IncrediBuild")
            .createNotification(text, type)
            .notify(project)
    }

    private inner class DetachAction : AnAction(
        "Open in Separate Window", "Undock the Build Monitor and show it as its own window", com.intellij.icons.AllIcons.Actions.MoveToWindow
    ), DumbAware {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = panel?.isEmbedding == true
        }

        override fun actionPerformed(e: AnActionEvent) = detachToWindow()
    }

    private inner class RelaunchAction : AnAction(
        "Restart Build Monitor", "Close this Build Monitor and start a new one", com.intellij.icons.AllIcons.Actions.Refresh
    ), DumbAware {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) {
            dropDockedMonitor()
            closeTab()
            open(activate = true)
        }
    }

    companion object {
        private val LOG = logger<BuildMonitorHost>()
        private const val MONITOR_TAB_TITLE = "Build Monitor"

        /** Delphi/VCL form class of the Build Monitor (verified on IncrediBuild 10.37: `TBuildMonitorForm_`). */
        private const val MONITOR_WINDOW_CLASS = "TBuildMonitorForm"

        /**
         * The monitor's view pane inside that form – the agents/timeline notebook, the progress bar and the icon
         * strip, i.e. what IncrediBuild's own Visual Studio add-in shows. Falling back to the whole form if the
         * class ever changes costs the chrome, not the feature.
         */
        private const val MONITOR_VIEW_CLASS = "TMonitorViewForm"


        /**
         * Tight, because this loop is also what keeps the monitor off the desktop. It cannot win outright: hiding a
         * window of another process is queued to *that* process's thread, so the monitor stays up until its own
         * start-up gets round to the message - about one frame, measured. Polling faster than this does not shorten
         * that, it only burns CPU.
         */
        private const val WINDOW_POLL_INTERVAL_MS = 5L

        /** How long to wait for the view pane before settling for the monitor window as a whole. */
        private const val VIEW_GRACE_MS = 2_000L

        /** How often a docked monitor is checked for having moved itself. */
        private const val GEOMETRY_CHECK_MS = 500L

        /** Total budget for getting the monitor docked, across however many handle recreations it takes. */
        private const val DOCK_TIMEOUT_MS = 25_000L
        private const val DOCK_RETRY_DELAY_MS = 150L
        private const val SPAWN_TIMEOUT_MS = 30_000L

        @JvmStatic
        fun getInstance(project: Project): BuildMonitorHost = project.getService(BuildMonitorHost::class.java)

        /** Process ids of every running BuildMonitor.exe. */
        fun monitorPids(): Set<Long> {
            val pids = HashSet<Long>()
            try {
                ProcessHandle.allProcesses().forEach { handle ->
                    val command = handle.info().command().orElse(null) ?: return@forEach
                    if (command.endsWith("BuildMonitor.exe", ignoreCase = true)) pids.add(handle.pid())
                }
            } catch (t: Throwable) {
                LOG.debug("Cannot enumerate Build Monitor processes", t)
            }
            return pids
        }

        /**
         * Waits for the monitor's main form of one of [pids] to exist. Polled tightly and without requiring the
         * window to be on screen yet: catching it before its first show is what keeps it from flashing on the
         * desktop on its way into the tab.
         */
        private fun awaitMonitorWindow(
            candidates: () -> List<HWND>,
            timeoutMs: Long,
            parked: MutableMap<HWND, IntArray>,
            requireViewPane: Boolean,
        ): HWND? {
            val deadline = System.currentTimeMillis() + timeoutMs
            var chromeless: HWND? = null
            var chromelessSince = 0L
            while (System.currentTimeMillis() < deadline) {
                val form = candidates().firstOrNull()
                if (form != null) {
                    // Keep it out of sight from the first moment it is seen, and again on every pass: the monitor
                    // shows itself partway through start-up, which undoes a plain hide, so it is parked off-screen
                    // as well. Where it was is remembered first, so handing it back does not leave it off-screen.
                    if (!parked.containsKey(form)) NativeWindows.windowRect(form)?.let { parked[form] = it }
                    NativeWindows.show(form, Win32.SW_HIDE)
                    NativeWindows.parkOffScreen(form)
                    if (!requireViewPane) return form
                    // Wait for the view pane to exist, otherwise the insets would be measured against a half-built
                    // form and the command-line bar would stay visible until the next resize. Waiting for the handle
                    // to settle is deliberately not done: that costs a visible frame, and a handle that dies under
                    // us is cheap to recover from by re-discovering and trying again.
                    if (NativeWindows.findChildWindows(form, MONITOR_VIEW_CLASS).isNotEmpty()) return form
                    val now = System.currentTimeMillis()
                    if (chromeless != form) {
                        chromeless = form
                        chromelessSince = now
                    } else if (now - chromelessSince > VIEW_GRACE_MS) {
                        LOG.info("No $MONITOR_VIEW_CLASS child appeared; docking the whole monitor window instead")
                        return form
                    }
                }
                Thread.sleep(WINDOW_POLL_INTERVAL_MS)
            }
            return null
        }
    }
}
