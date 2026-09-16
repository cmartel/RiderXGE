package org.riderxge.incredibuild.ui.embed

import com.sun.jna.platform.win32.WinDef.HWND

/**
 * Works out which edges of a window have to be pushed outside the host so that only one inner pane of it shows.
 *
 * This is how the Build Monitor is docked without its chrome: the monitor paints its title bar and menu into the
 * form's non-client area (it is skinned, so clearing `WS_CAPTION` changes nothing) and stacks a command-line bar
 * above the view inside the client area. Reparenting the view pane on its own is the obvious alternative and does
 * work, but VCL owns that control's bounds and immediately resets any size given to it from outside, so the window
 * that gets adopted is the form and the unwanted parts are simply clipped away by the host.
 *
 * Everything is measured from the live window rather than remembered, because the non-client size changes when the
 * window is restyled as a child and again between displays with different scaling.
 */
object WindowCrop {

    /** Insets that leave only the first child of class [paneClass] visible. */
    fun paneInsets(window: HWND, paneClass: String): WindowInsets {
        val chrome = nonClientInsets(window) ?: return WindowInsets.NONE
        val origin = NativeWindows.clientOrigin(window) ?: return WindowInsets.NONE
        val (_, clientHeight) = NativeWindows.clientSize(window)
        val paneTop = NativeWindows.findChildWindows(window, paneClass).firstOrNull()
            ?.let { NativeWindows.windowRect(it) }
            ?.let { (it[1] - origin[1]).coerceIn(0, clientHeight) }
            ?: 0
        return WindowInsets(chrome.left, chrome.top + paneTop, chrome.right, chrome.bottom)
    }

    /** The window's frame: everything outside its client area. */
    fun nonClientInsets(window: HWND): WindowInsets? {
        val rect = NativeWindows.windowRect(window) ?: return null
        val origin = NativeWindows.clientOrigin(window) ?: return null
        val (clientWidth, clientHeight) = NativeWindows.clientSize(window)
        if (clientWidth <= 0 || clientHeight <= 0) return null
        val left = (origin[0] - rect[0]).coerceAtLeast(0)
        val top = (origin[1] - rect[1]).coerceAtLeast(0)
        return WindowInsets(
            left = left,
            top = top,
            right = (rect[2] - clientWidth - left).coerceAtLeast(0),
            bottom = (rect[3] - clientHeight - top).coerceAtLeast(0),
        )
    }
}
