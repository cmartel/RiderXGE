package org.riderxge.incredibuild.ui.embed

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.SystemInfo
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.RECT
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import java.awt.Component

/**
 * Win32 style bits and `SetWindowPos` flags used when docking a foreign top-level window into a Swing component.
 * JNA's `WinUser` exposes only part of these, and mixing the two sources reads worse than one small table.
 */
internal object Win32 {
    const val GWL_STYLE = -16
    const val GWL_EXSTYLE = -20

    const val WS_CHILD = 0x4000_0000
    val WS_POPUP = 0x8000_0000.toInt()
    const val WS_VISIBLE = 0x1000_0000
    const val WS_CLIPSIBLINGS = 0x0400_0000
    const val WS_CAPTION = 0x00C0_0000
    const val WS_SYSMENU = 0x0008_0000
    const val WS_THICKFRAME = 0x0004_0000
    const val WS_MINIMIZEBOX = 0x0002_0000
    const val WS_MAXIMIZEBOX = 0x0001_0000

    const val WS_EX_APPWINDOW = 0x0004_0000
    const val WS_EX_TOOLWINDOW = 0x0000_0080

    const val SWP_NOSIZE = 0x0001
    const val SWP_NOMOVE = 0x0002
    const val SWP_NOZORDER = 0x0004
    const val SWP_NOACTIVATE = 0x0010
    const val SWP_FRAMECHANGED = 0x0020
    const val SWP_SHOWWINDOW = 0x0040

    const val SW_HIDE = 0
    const val SW_SHOWNOACTIVATE = 4
    const val SW_SHOW = 5
}

/**
 * `GetParent` is not declared by JNA's bundled `User32` interface. Loading it without a function mapper keeps the
 * name exact (there is no `GetParentW`).
 */
private interface User32Extra : StdCallLibrary {
    fun GetParent(hWnd: HWND): HWND?
    fun ClientToScreen(hWnd: HWND, point: com.sun.jna.platform.win32.WinDef.POINT): Boolean

    companion object {
        val INSTANCE: User32Extra by lazy { Native.load("user32", User32Extra::class.java) }
    }
}

/**
 * Parts of a window that should sit outside the host's visible area when it is docked – for the Build Monitor, its
 * skinned title bar and menu (drawn in the non-client area) plus the command-line bar above the view.
 */
data class WindowInsets(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    companion object {
        val NONE = WindowInsets(0, 0, 0, 0)
    }
}

/** Thin, null-safe wrappers over the Win32 window calls the embedding needs. */
internal object NativeWindows {
    private val LOG = logger<NativeWindows>()

    val isSupported: Boolean get() = SystemInfo.isWindows

    private val user32: User32 get() = User32.INSTANCE

    /**
     * Native handle of a *heavyweight* AWT component. Lightweight Swing components have no window of their own and
     * return null, as does any platform without JAWT support in the bundled JNA.
     */
    fun hwndOf(component: Component): HWND? {
        if (!isSupported || !component.isDisplayable) return null
        return try {
            val id = Native.getComponentID(component)
            if (id == 0L) null else HWND(Pointer(id))
        } catch (t: Throwable) {
            LOG.warn("Cannot obtain a native handle for ${component.javaClass.name}", t)
            null
        }
    }

    fun isWindow(hwnd: HWND): Boolean = isSupported && user32.IsWindow(hwnd)

    fun isVisible(hwnd: HWND): Boolean = user32.IsWindowVisible(hwnd)

    fun parentOf(hwnd: HWND): HWND? = runCatching { User32Extra.INSTANCE.GetParent(hwnd) }.getOrNull()

    fun className(hwnd: HWND): String {
        val buffer = CharArray(256)
        val length = user32.GetClassName(hwnd, buffer, buffer.size)
        return if (length > 0) String(buffer, 0, length) else ""
    }

    fun title(hwnd: HWND): String {
        val buffer = CharArray(512)
        val length = user32.GetWindowText(hwnd, buffer, buffer.size)
        return if (length > 0) String(buffer, 0, length) else ""
    }

    fun processIdOf(hwnd: HWND): Long {
        val pid = IntByReference()
        user32.GetWindowThreadProcessId(hwnd, pid)
        return pid.value.toLong() and 0xFFFF_FFFFL
    }

    /** Client area in physical pixels, which is what child-window coordinates use regardless of the display scale. */
    fun clientSize(hwnd: HWND): Pair<Int, Int> {
        val rect = RECT()
        if (!user32.GetClientRect(hwnd, rect)) return 0 to 0
        return (rect.right - rect.left) to (rect.bottom - rect.top)
    }

    /**
     * Moves a window far off the desktop. Hiding alone is not enough to keep a window out of sight while its own
     * process is starting up: the app shows itself when it is ready and undoes the hide. A parked window stays
     * invisible even when shown, and adopting it puts it back at a real position anyway.
     */
    fun parkOffScreen(hwnd: HWND) {
        setBounds(
            hwnd, OFF_SCREEN, OFF_SCREEN, 0, 0,
            Win32.SWP_NOSIZE or Win32.SWP_NOZORDER or Win32.SWP_NOACTIVATE
        )
    }

    /** Matches what Windows itself uses for parked windows. */
    private const val OFF_SCREEN = -32000

    /** Screen position of the window's client area origin, in physical pixels. */
    fun clientOrigin(hwnd: HWND): IntArray? {
        val point = com.sun.jna.platform.win32.WinDef.POINT(0, 0)
        return try {
            if (User32Extra.INSTANCE.ClientToScreen(hwnd, point)) intArrayOf(point.x, point.y) else null
        } catch (t: Throwable) {
            LOG.debug("ClientToScreen failed", t)
            null
        }
    }

    /** Screen rectangle as `(x, y, width, height)`, in physical pixels. */
    fun windowRect(hwnd: HWND): IntArray? {
        val rect = RECT()
        if (!user32.GetWindowRect(hwnd, rect)) return null
        return intArrayOf(rect.left, rect.top, rect.right - rect.left, rect.bottom - rect.top)
    }

    fun style(hwnd: HWND): Int = user32.GetWindowLong(hwnd, Win32.GWL_STYLE)

    fun exStyle(hwnd: HWND): Int = user32.GetWindowLong(hwnd, Win32.GWL_EXSTYLE)

    fun setStyle(hwnd: HWND, style: Int) {
        user32.SetWindowLong(hwnd, Win32.GWL_STYLE, style)
    }

    fun setExStyle(hwnd: HWND, exStyle: Int) {
        user32.SetWindowLong(hwnd, Win32.GWL_EXSTYLE, exStyle)
    }

    fun setParent(child: HWND, parent: HWND?) {
        user32.SetParent(child, parent)
    }

    fun setBounds(hwnd: HWND, x: Int, y: Int, width: Int, height: Int, flags: Int) {
        user32.SetWindowPos(hwnd, null, x, y, width, height, flags)
    }

    fun show(hwnd: HWND, command: Int) {
        user32.ShowWindow(hwnd, command)
    }

    /** Direct children of [parent] whose window class starts with [classPrefix]. */
    fun findChildWindows(parent: HWND, classPrefix: String): List<HWND> {
        if (!isSupported) return emptyList()
        val found = ArrayList<HWND>()
        val callback = object : com.sun.jna.platform.win32.WinUser.WNDENUMPROC {
            override fun callback(hWnd: HWND, data: Pointer?): Boolean {
                try {
                    if (parentOf(hWnd) == parent && className(hWnd).startsWith(classPrefix, ignoreCase = true)) {
                        found.add(HWND(hWnd.pointer))
                    }
                } catch (t: Throwable) {
                    LOG.debug("EnumChildWindows callback failed", t)
                }
                return true
            }
        }
        user32.EnumChildWindows(parent, callback, null)
        return found
    }

    /** Every top-level window of a given class, whichever process owns it. */
    fun findTopLevelWindows(classPrefix: String, requireVisible: Boolean = true): List<HWND> {
        if (!isSupported) return emptyList()
        val found = ArrayList<HWND>()
        val callback = object : com.sun.jna.platform.win32.WinUser.WNDENUMPROC {
            override fun callback(hWnd: HWND, data: Pointer?): Boolean {
                try {
                    if ((!requireVisible || user32.IsWindowVisible(hWnd)) &&
                        className(hWnd).startsWith(classPrefix, ignoreCase = true)
                    ) {
                        found.add(HWND(hWnd.pointer))
                    }
                } catch (t: Throwable) {
                    LOG.debug("EnumWindows callback failed", t)
                }
                return true
            }
        }
        user32.EnumWindows(callback, null)
        return found
    }

    /**
     * Top-level windows owned by [pids], optionally restricted to a window class prefix. Windows that exist but are
     * not on screen yet count unless [requireVisible] is set – catching a window before it is first shown is what
     * lets it be adopted without flashing on the desktop.
     */
    fun findTopLevelWindows(pids: Set<Long>, classPrefix: String? = null, requireVisible: Boolean = true): List<HWND> {
        if (!isSupported || pids.isEmpty()) return emptyList()
        val found = ArrayList<HWND>()
        val callback = object : com.sun.jna.platform.win32.WinUser.WNDENUMPROC {
            override fun callback(hWnd: HWND, data: Pointer?): Boolean {
                try {
                    if ((!requireVisible || user32.IsWindowVisible(hWnd)) && processIdOf(hWnd) in pids) {
                        if (classPrefix == null || className(hWnd).startsWith(classPrefix, ignoreCase = true)) {
                            found.add(HWND(hWnd.pointer))
                        }
                    }
                } catch (t: Throwable) {
                    LOG.debug("EnumWindows callback failed", t)
                }
                return true
            }
        }
        user32.EnumWindows(callback, null)
        return found
    }
}
