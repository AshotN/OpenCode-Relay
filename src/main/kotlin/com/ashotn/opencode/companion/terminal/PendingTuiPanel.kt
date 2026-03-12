package com.ashotn.opencode.companion.terminal

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * A no-op [TuiPanel] shown while the OpenCode executable path is still being
 * resolved (i.e. before the executable info is available in the tool window).
 *
 * It renders a simple "Loading…" label and does nothing on lifecycle calls.
 */
class PendingTuiPanel : JPanel(BorderLayout()), TuiPanel {

    init {
        val label = JBLabel("Loading...", SwingConstants.CENTER).apply {
            foreground = JBColor.GRAY
            border = JBUI.Borders.empty(8)
        }
        add(label, BorderLayout.CENTER)
    }

    override val component: JPanel get() = this

    override val isStarted: Boolean = false

    override fun startIfNeeded() = Unit
    override fun stop() = Unit
    override fun focusTerminal() = Unit
    override fun dispose() = Unit
}
