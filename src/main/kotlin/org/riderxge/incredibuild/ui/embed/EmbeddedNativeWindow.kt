package org.riderxge.incredibuild.ui.embed

import com.intellij.openapi.diagnostic.logger
import com.sun.jna.platform.win32.WinDef.HWND

/**
 * A foreign window that has been reparented into one of our own components, plus everything needed to hand it back
 * untouched. Only the window is borrowed – the process it belongs to keeps running and is owned by whoever launched
 * it. Both a top-level window and a child control of one can be adopted; the latter is how the monitor's view pane is
 * taken without its surrounding chrome, and is restored to its original parent rather than to the desktop.
 */
class EmbeddedNativeWindow private constructor(
    val hwnd: HWND,
    private val originalStyle: Int,
    private val originalExStyle: Int,
    private val originalRect: IntArray?,
    private val originalParent: HWND?,
    /**
     * Edges of the window to push outside the host so only the wanted part shows. Resolved on every fit rather than
     * once, because a window's non-client size can change when it is restyled as a child or moved between monitors.
     */
    private val insets: (HWND) -> WindowInsets,
) {
    @Volatile
    private var attachedTo: HWND? = null

    @Volatile
    private var released = false

    /**
     * The adopted window's application window, hidden while it is docked. A VCL application keeps its taskbar
     * button on a hidden application window rather than on the form, so restyling the form as a child leaves a
     * taskbar button behind for a window that now lives inside someone else's tab.
     */
    @Volatile
    private var hiddenTaskbarWindow: HWND? = null

    val isAlive: Boolean get() = !released && NativeWindows.isWindow(hwnd)

    /** Reparents into [parent] (used for the initial attach and again whenever the host component gets a new peer). */
    fun attachTo(parent: HWND): Boolean {
        if (!isAlive) return false
        if (attachedTo == parent && NativeWindows.parentOf(hwnd) == parent) {
            fitTo(parent)
            return true
        }
        NativeWindows.setParent(hwnd, parent)
        val actual = NativeWindows.parentOf(hwnd)
        if (actual != parent) {
            LOG.warn("SetParent did not take effect for $hwnd (parent is $actual)")
            return false
        }
        attachedTo = parent
        NativeWindows.show(hwnd, Win32.SW_SHOWNOACTIVATE)
        // The style change only reaches the non-client area once a SetWindowPos carries SWP_FRAMECHANGED.
        fitTo(parent, frameChanged = true)
        return true
    }

    /**
     * Lets go of the host without giving up ownership, for the window to be re-adopted by [attachTo] later.
     * Destroying a parent window destroys its children, so this *must* happen before the host's peer goes away.
     */
    fun detachFromHost() {
        if (!isAlive || attachedTo == null) return
        attachedTo = null
        NativeWindows.show(hwnd, Win32.SW_HIDE)
        NativeWindows.setParent(hwnd, null)
    }

    /**
     * Sizes the embedded window so that the part of it we want to show covers the host's client area exactly. With
     * insets the window is made correspondingly larger and shifted up/left, letting the host clip the rest away.
     */
    fun fitTo(parent: HWND, frameChanged: Boolean = false) {
        if (!isAlive) return
        val (width, height) = NativeWindows.clientSize(parent)
        if (width <= 0 || height <= 0) return
        val crop = runCatching { insets(hwnd) }.getOrDefault(WindowInsets.NONE)
        val x = -crop.left
        val y = -crop.top
        val targetWidth = width + crop.left + crop.right
        val targetHeight = height + crop.top + crop.bottom

        // Skip the call when nothing would change: this also runs periodically to undo any moving the embedded
        // window does of its own accord, and a no-op SetWindowPos on another process is not free.
        if (!frameChanged) {
            val parentOrigin = NativeWindows.clientOrigin(parent)
            val current = NativeWindows.windowRect(hwnd)
            if (parentOrigin != null && current != null &&
                current[0] - parentOrigin[0] == x && current[1] - parentOrigin[1] == y &&
                current[2] == targetWidth && current[3] == targetHeight
            ) {
                return
            }
        }

        var flags = Win32.SWP_NOZORDER or Win32.SWP_NOACTIVATE or Win32.SWP_SHOWWINDOW
        if (frameChanged) flags = flags or Win32.SWP_FRAMECHANGED
        NativeWindows.setBounds(hwnd, x, y, targetWidth, targetHeight, flags)
    }

    /**
     * Re-applies everything that makes the window look docked. The owner can move it, and bring its taskbar button
     * back, while it is being used - the Build Monitor does both when a build takes it over.
     */
    fun reassert(parent: HWND) {
        fitTo(parent)
        hiddenTaskbarWindow
            ?.takeIf { NativeWindows.isWindow(it) && NativeWindows.isVisible(it) }
            ?.let { NativeWindows.show(it, Win32.SW_HIDE) }
    }

    /** Takes the adopted window's application off the taskbar for as long as it is docked. */
    private fun hideTaskbarEntry() {
        val pid = NativeWindows.processIdOf(hwnd)
        if (pid == 0L) return
        val owner = NativeWindows.findTopLevelWindows(setOf(pid))
            .firstOrNull { it != hwnd && NativeWindows.exStyle(it) and Win32.WS_EX_APPWINDOW != 0 }
            ?: return
        hiddenTaskbarWindow = owner
        NativeWindows.show(owner, Win32.SW_HIDE)
    }

    /**
     * Lets go of the window without putting it back. For a window whose process is being closed anyway, restoring
     * it first would park it on the desktop for the frame or two before it dies.
     */
    fun discard() {
        released = true
        attachedTo = null
    }

    /** Puts the window back where it came from, so closing the tab never loses it. */
    fun release() {
        if (released) return
        released = true
        attachedTo = null
        hiddenTaskbarWindow?.let { owner ->
            hiddenTaskbarWindow = null
            if (NativeWindows.isWindow(owner)) NativeWindows.show(owner, Win32.SW_SHOW)
        }
        if (!NativeWindows.isWindow(hwnd)) return
        try {
            NativeWindows.setParent(hwnd, originalParent)
            NativeWindows.setStyle(hwnd, originalStyle)
            NativeWindows.setExStyle(hwnd, originalExStyle)
            if (originalParent != null) {
                // A child control goes back to the coordinates it had inside its own parent.
                originalRect?.let { rect ->
                    val parentOrigin = NativeWindows.windowRect(originalParent)
                    val x = if (parentOrigin != null) rect[0] - parentOrigin[0] else 0
                    val y = if (parentOrigin != null) rect[1] - parentOrigin[1] else 0
                    NativeWindows.setBounds(
                        hwnd, x, y, rect[2], rect[3],
                        Win32.SWP_NOZORDER or Win32.SWP_NOACTIVATE or Win32.SWP_FRAMECHANGED
                    )
                }
                NativeWindows.show(hwnd, Win32.SW_SHOW)
                return
            }
            if (originalRect != null) {
                NativeWindows.setBounds(
                    hwnd, originalRect[0], originalRect[1], originalRect[2], originalRect[3],
                    Win32.SWP_NOZORDER or Win32.SWP_NOACTIVATE or Win32.SWP_FRAMECHANGED
                )
            } else {
                NativeWindows.setBounds(
                    hwnd, 0, 0, 0, 0,
                    Win32.SWP_NOZORDER or Win32.SWP_NOACTIVATE or Win32.SWP_FRAMECHANGED or
                        Win32.SWP_NOSIZE or Win32.SWP_NOMOVE
                )
            }
            NativeWindows.show(hwnd, Win32.SW_SHOW)
        } catch (t: Throwable) {
            LOG.warn("Failed to restore embedded window $hwnd", t)
        }
    }

    companion object {
        private val LOG = logger<EmbeddedNativeWindow>()

        /**
         * Strips the frame off [child] and makes it a child of [parent]. Returns null (leaving the window untouched)
         * when the reparenting does not take – a window that refuses to be adopted must stay usable on its own.
         */
        /**
         * [restoreRect] overrides where the window goes when it is handed back. Callers that moved the window out
         * of sight before adopting it pass the position it had beforehand, so releasing does not put it back
         * off-screen.
         */
        fun embed(
            child: HWND,
            parent: HWND,
            insets: (HWND) -> WindowInsets = { WindowInsets.NONE },
            restoreRect: IntArray? = null,
        ): EmbeddedNativeWindow? {
            if (!NativeWindows.isSupported || !NativeWindows.isWindow(child) || !NativeWindows.isWindow(parent)) return null
            val style = NativeWindows.style(child)
            val exStyle = NativeWindows.exStyle(child)
            val previousParent = NativeWindows.parentOf(child)
            val embedded = EmbeddedNativeWindow(
                child, style, exStyle, restoreRect ?: NativeWindows.windowRect(child), previousParent, insets
            )

            if (previousParent == null) {
                // A top-level window has to lose its frame and become a child before it can be adopted. A control
                // that is already a child of something else needs none of this: it only changes parent.
                val frameless = (
                    style and (
                        Win32.WS_POPUP or Win32.WS_CAPTION or Win32.WS_THICKFRAME or
                            Win32.WS_SYSMENU or Win32.WS_MINIMIZEBOX or Win32.WS_MAXIMIZEBOX
                        ).inv()
                    ) or Win32.WS_CHILD or Win32.WS_VISIBLE or Win32.WS_CLIPSIBLINGS
                NativeWindows.setStyle(child, frameless)
                // Keep the borrowed window out of the taskbar and Alt+Tab while it lives inside the tool window.
                NativeWindows.setExStyle(child, (exStyle and Win32.WS_EX_APPWINDOW.inv()) or Win32.WS_EX_TOOLWINDOW)
            }

            if (!embedded.attachTo(parent)) {
                embedded.release()
                return null
            }
            if (previousParent == null) embedded.hideTaskbarEntry()
            return embedded
        }
    }
}
