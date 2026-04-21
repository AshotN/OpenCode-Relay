package com.ashotn.opencode.relay.terminal

import com.intellij.openapi.actionSystem.CustomizedDataContext
import com.intellij.openapi.actionSystem.DataProvider
import com.intellij.openapi.actionSystem.PlatformDataKeys
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
