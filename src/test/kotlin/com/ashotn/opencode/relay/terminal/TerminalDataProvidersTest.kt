package com.ashotn.opencode.relay.terminal

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.DataProvider
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.wm.ToolWindow
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.BorderLayout
import java.lang.reflect.Proxy
import javax.swing.JPanel

class TerminalDataProvidersTest : BasePlatformTestCase() {

    fun `test terminal panel override hides ancestor tool window from data context`() {
        val container = JPanel(BorderLayout())
        val terminalPanel = JPanel(BorderLayout())
        val toolWindow = toolWindowStub()

        container.putClientProperty("DataProvider", DataProvider { dataId ->
            when {
                PlatformDataKeys.TOOL_WINDOW.`is`(dataId) -> toolWindow
                else -> null
            }
        })
        container.add(terminalPanel, BorderLayout.CENTER)

        installTerminalToolWindowOverride(terminalPanel)
        try {
            ApplicationManager.getApplication().invokeAndWait {
                val dataContext = DataManager.getInstance().getDataContext(terminalPanel)
                assertNull(dataContext.getData(PlatformDataKeys.TOOL_WINDOW))
            }
        } finally {
            uninstallTerminalToolWindowOverride(terminalPanel)
        }
    }

    private fun toolWindowStub(): ToolWindow =
        Proxy.newProxyInstance(
            ToolWindow::class.java.classLoader,
            arrayOf(ToolWindow::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "toString" -> "ToolWindowStub"
                else -> if (method.returnType == Boolean::class.javaPrimitiveType) false else null
            }
        } as ToolWindow
}
