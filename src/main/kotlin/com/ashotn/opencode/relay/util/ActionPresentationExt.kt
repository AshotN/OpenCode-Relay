package com.ashotn.opencode.relay.util

import com.ashotn.opencode.relay.actions.ActionStrings
import com.intellij.openapi.actionSystem.AnActionEvent

/**
 * Applies the enabled state and the correct [ActionStrings] text/description to this
 * action event's presentation in one call
 */
fun AnActionEvent.applyStrings(strings: ActionStrings, enabled: Boolean) {
    presentation.isEnabled = enabled
    presentation.text = if (enabled) strings.text else strings.disabledText
    presentation.description = if (enabled) strings.description else strings.disabledDescription
}
