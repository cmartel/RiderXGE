package org.riderxge.incredibuild.ui.embed

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.ui.JBUI
import com.sun.jna.platform.win32.WinDef.HWND
import java.awt.BorderLayout
import java.awt.Canvas
import java.awt.Dimension
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Hosts a foreign native window inside a Swing container. The window is parented into a heavyweight [Canvas],
 * which is the only way to obtain a real `HWND` to adopt into – Swing components are lightweight and have none.
 *
 * The canvas peer is recreated whenever the tool window is undocked, moved to another split or hidden and shown
 * again, so the embedded window is re-attached on every [addNotify].
 */
class NativeWindowPanel : JPanel(BorderLayout()), Disposable {

    private val host = object : Canvas() {
        override fun getPreferredSize(): Dimension = JBUI.size(320, 200)
    }

    @Volatile
    private var embedded: EmbeddedNativeWindow? = null

    /** Set when [embed] is called before the panel has a peer; attached as soon as one exists. */
    @Volatile
    private var pending: HWND? = null

    @Volatile
    private var pendingInsets: (HWND) -> WindowInsets = { WindowInsets.NONE }

    /** Where the adopted window should go when released, if it was moved before being adopted. */
    @Volatile
    private var pendingRestoreRect: IntArray? = null

    val isEmbedding: Boolean get() = embedded?.isAlive == true

    init {
        host.isFocusable = false
        host.background = runCatching { JBUI.CurrentTheme.ToolWindow.background() }.getOrNull() ?: background
        add(host, BorderLayout.CENTER)
        host.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = fit()
            override fun componentShown(e: ComponentEvent) = fit()
        })
    }

    /**
     * Takes over [window], showing it in this panel. When the panel has no peer yet the adoption is deferred to the
     * next [addNotify]; when it fails outright the window is left alone as a normal top-level window.
     */
    fun embed(
        window: HWND,
        insets: (HWND) -> WindowInsets = { WindowInsets.NONE },
        restoreRect: IntArray? = null,
    ): EmbedState {
        require(SwingUtilities.isEventDispatchThread()) { "NativeWindowPanel.embed must be called on the EDT" }
        release()
        pending = window
        pendingInsets = insets
        pendingRestoreRect = restoreRect
        if (attachPending()) return EmbedState.EMBEDDED
        return if (pending != null) EmbedState.PENDING else EmbedState.FAILED
    }

    /** Hands the window back as a free-floating top-level window. */
    fun release() {
        embedded?.release()
        embedded = null
        pending = null
    }

    /** Lets go of the window without handing it back, for when it is being closed along with the panel. */
    fun discard() {
        embedded?.discard()
        embedded = null
        pending = null
    }

    override fun removeNotify() {
        // Windows destroys child windows together with their parent, so the adopted window has to be let go before
        // the canvas peer disappears (tool window hidden, undocked or moved to another split).
        embedded?.takeIf { it.isAlive }?.detachFromHost()
        super.removeNotify()
    }

    override fun addNotify() {
        super.addNotify()
        // The peer (and with it our HWND) is new; re-adopt whatever we are showing.
        val existing = embedded
        if (existing != null && existing.isAlive) {
            hostHandle()?.let { existing.attachTo(it) }
        } else {
            attachPending()
        }
    }

    override fun dispose() {
        release()
    }

    private fun attachPending(): Boolean {
        val window = pending ?: return false
        val parent = hostHandle() ?: return false // no peer yet; addNotify will retry
        val adopted = EmbeddedNativeWindow.embed(window, parent, pendingInsets, pendingRestoreRect)
        if (adopted == null) {
            LOG.warn("Could not embed native window $window")
            pending = null
            return false
        }
        embedded = adopted
        pending = null
        fit()
        return true
    }

    /**
     * Re-applies the docked state if the embedded window has undone any of it - moved or resized itself, or put its
     * taskbar button back. The Build Monitor does exactly that when a new build takes it over, which would otherwise
     * leave the tab showing part of it, or nothing at all.
     */
    fun reassert() {
        val parent = hostHandle() ?: return
        embedded?.takeIf { it.isAlive }?.reassert(parent)
    }

    private fun fit() {
        val parent = hostHandle() ?: return
        embedded?.takeIf { it.isAlive }?.fitTo(parent)
    }

    private fun hostHandle(): HWND? = if (host.isDisplayable) NativeWindows.hwndOf(host) else null

    private companion object {
        private val LOG = logger<NativeWindowPanel>()
    }
}

/** Outcome of [NativeWindowPanel.embed]. */
enum class EmbedState { EMBEDDED, PENDING, FAILED }
