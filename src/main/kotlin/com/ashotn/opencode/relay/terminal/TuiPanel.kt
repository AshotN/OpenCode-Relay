package com.ashotn.opencode.relay.terminal

import com.intellij.openapi.Disposable
import javax.swing.JPanel

/**
 * Common contract for the embeddable terminal panel that runs
 * `opencode attach <server-url>` inside the tool window.
 *
 * The active implementation is chosen by
 * [com.ashotn.opencode.relay.settings.OpenCodeSettings.terminalEngine].
 *
 * The reworked implementation is currently parked and excluded from the build,
 * so the runtime always uses [ClassicTuiPanel].
 */
interface TuiPanel : Disposable {
    /** The Swing component to embed in the tool window. */
    val component: JPanel

    /** True while a terminal session is live. */
    val isStarted: Boolean

    /**
     * Creates and starts the terminal session if one is not already running.
     * Must be called on the EDT. Safe to call multiple times.
     */
    fun startIfNeeded()

    /** Stops the running session (if any). */
    fun stop()

    /** Requests focus for the terminal input widget. */
    fun focusTerminal()
}
