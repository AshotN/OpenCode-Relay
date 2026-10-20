package com.ashotn.opencode.relay.terminal

import com.intellij.openapi.Disposable
import javax.swing.JPanel

/**
 * Common contract for the embeddable terminal panel that runs
 * `opencode --server=<server-url>` inside the tool window.
 *
 * Two implementations exist:
 * - [ClassicTuiPanel] backed by the classic JediTerm widget
 * - [ReworkedTuiPanel] backed by the TerminalToolWindowTabsManager API
 *
 * The active implementation is chosen by
 * [com.ashotn.opencode.relay.settings.OpenCodeSettings.terminalEngine].
 *
 * OpenCode 2.x removed the v1 `/tui/` control routes, so the TUI is steered through its own
 * terminal: text is pasted into the prompt input ([appendToPrompt]) and a session is switched
 * by relaunching the TUI against `--session=<id>` ([relaunchForSession]).
 */
interface TuiPanel : Disposable {
    /** The Swing component to embed in the tool window. */
    val component: JPanel

    /** True while a terminal session is live. */
    val isStarted: Boolean

    /**
     * Creates and starts the terminal session if one is not already running, optionally
     * opening [initialSessionId] (via `--session`). Must be called on the EDT. Safe to call
     * multiple times.
     */
    fun startIfNeeded(initialSessionId: String? = null)

    /** Stops the running session (if any). Must be called on the EDT. */
    fun stop()

    /** Requests focus for the terminal input widget. */
    fun focusTerminal()

    /**
     * Inserts [text] into the TUI prompt input via bracketed paste, without submitting it
     * (the user still presses Enter to run it). Must be called on the EDT.
     *
     * Returns true if the terminal is live and the text was sent.
     */
    fun appendToPrompt(text: String): Boolean

    /**
     * Restarts the TUI pointed at [sessionId] by stopping the current session and relaunching
     * with `--session=<id>`. This is the v2 way to switch the active TUI session — the TUI
     * has no non-interactive "go to session" command. Must be called on the EDT.
     */
    fun relaunchForSession(sessionId: String) {
        stop()
        startIfNeeded(sessionId)
    }
}
