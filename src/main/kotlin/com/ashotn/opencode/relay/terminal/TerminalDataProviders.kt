package com.ashotn.opencode.relay.terminal

import com.intellij.openapi.actionSystem.CustomizedDataContext
import com.intellij.openapi.actionSystem.DataProvider
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.terminal.JBTerminalPanel
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.JComponent

private const val DATA_PROVIDER_CLIENT_PROPERTY = "DataProvider"

internal fun installTerminalToolWindowOverride(component: JComponent) {
    component.putClientProperty(DATA_PROVIDER_CLIENT_PROPERTY, DataProvider { dataId ->
        when {
            PlatformDataKeys.TOOL_WINDOW.`is`(dataId) -> CustomizedDataContext.EXPLICIT_NULL
            else -> null
        }
    })
}

internal fun uninstallTerminalToolWindowOverride(component: JComponent) {
    component.putClientProperty(DATA_PROVIDER_CLIENT_PROPERTY, null)
}

internal fun installEmbeddedTerminalKeyOverrides(panel: JBTerminalPanel) {
    panel.addPreKeyEventHandler { event ->
        if (consumeEmbeddedTerminalControlKey(event)) {
            event.consume()
        }
    }
}

internal fun consumeEmbeddedTerminalControlKey(event: KeyEvent): Boolean {
    return event.isCtrlZPress()
}

private fun KeyEvent.isCtrlZPress(): Boolean {
    if (id != KeyEvent.KEY_PRESSED) return false
    if (keyCode != KeyEvent.VK_Z) return false
    return modifiersEx == InputEvent.CTRL_DOWN_MASK
}
