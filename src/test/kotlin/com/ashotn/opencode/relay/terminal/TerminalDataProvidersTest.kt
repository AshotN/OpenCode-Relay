package com.ashotn.opencode.relay.terminal

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.DataProvider
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.terminal.JBTerminalPanel
import com.intellij.terminal.JBTerminalSystemSettingsProviderBase
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jediterm.terminal.model.StyleState
import com.jediterm.terminal.model.TerminalTextBuffer
import java.awt.BorderLayout
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.lang.reflect.Proxy
import java.util.function.Consumer
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

    fun `test classic tui panel installs ctrl z key override without intercepting escape`() {
        val terminalPanel = createTerminalPanel()
        val classicTuiPanel = ClassicTuiPanel(project, testRootDisposable)
        val existingHandlers = preKeyEventHandlers(terminalPanel)

        try {
            installEmbeddedTerminalDataProvider(classicTuiPanel, terminalPanel)

            val handlers = preKeyEventHandlers(terminalPanel)
            val addedHandlers = handlers.drop(existingHandlers.size)
            assertEquals(1, addedHandlers.size)

            val ctrlZ = KeyEvent(
                terminalPanel,
                KeyEvent.KEY_PRESSED,
                System.currentTimeMillis(),
                InputEvent.CTRL_DOWN_MASK,
                KeyEvent.VK_Z,
                'Z',
            )
            addedHandlers.forEach { it.accept(ctrlZ) }
            assertTrue(ctrlZ.isConsumed)

            val escape = KeyEvent(
                terminalPanel,
                KeyEvent.KEY_PRESSED,
                System.currentTimeMillis(),
                0,
                KeyEvent.VK_ESCAPE,
                KeyEvent.CHAR_UNDEFINED,
            )
            addedHandlers.forEach { it.accept(escape) }
            assertFalse(escape.isConsumed)
        } finally {
            ensureTerminalPanelCanBeDisposed(terminalPanel)
            Disposer.dispose(terminalPanel)
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

    private fun createTerminalPanel(): JBTerminalPanel {
        lateinit var terminalPanel: JBTerminalPanel
        ApplicationManager.getApplication().invokeAndWait {
            val styleState = StyleState()
            terminalPanel = JBTerminalPanel(
                JBTerminalSystemSettingsProviderBase(),
                TerminalTextBuffer(80, 24, styleState),
                styleState,
            )
        }
        return terminalPanel
    }

    private fun installEmbeddedTerminalDataProvider(classicTuiPanel: ClassicTuiPanel, terminalPanel: JBTerminalPanel) {
        val method = ClassicTuiPanel::class.java.getDeclaredMethod(
            "installEmbeddedTerminalDataProvider",
            JBTerminalPanel::class.java,
        )
        method.isAccessible = true
        ApplicationManager.getApplication().invokeAndWait {
            method.invoke(classicTuiPanel, terminalPanel)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun preKeyEventHandlers(terminalPanel: JBTerminalPanel): List<Consumer<KeyEvent>> {
        val field = JBTerminalPanel::class.java.getDeclaredField("myPreKeyEventConsumers")
        field.isAccessible = true
        return (field.get(terminalPanel) as List<Consumer<KeyEvent>>).toList()
    }

    private fun ensureTerminalPanelCanBeDisposed(terminalPanel: JBTerminalPanel) {
        val field = terminalPanel.javaClass.superclass.getDeclaredField("myRepaintTimer")
        field.isAccessible = true
        if (field.get(terminalPanel) == null) {
            field.set(terminalPanel, javax.swing.Timer(0) { })
        }
    }
}
