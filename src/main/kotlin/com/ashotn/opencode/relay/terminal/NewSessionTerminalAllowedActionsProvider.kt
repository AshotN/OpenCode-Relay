@file:Suppress("UnstableApiUsage")

package com.ashotn.opencode.relay.terminal

import com.ashotn.opencode.relay.actions.NewSessionAction
import com.intellij.terminal.frontend.view.TerminalAllowedActionsProvider

// The terminal's "Override IDE shortcuts" feature blocks global IDE actions from firing when the
// terminal has focus, sending keystrokes directly to the PTY instead. Registering an action ID
// here via the allowedActionsProvider extension point (declared in withTerminal.xml) whitelists
// that action so whatever shortcut(default Shift+F12) the user has bound to it is honoured even when the embedded
// terminal is focused.
class NewSessionTerminalAllowedActionsProvider : TerminalAllowedActionsProvider {
    override fun getActionIds(): List<String> {
        return listOf(NewSessionAction::class.java.name)
    }
}
