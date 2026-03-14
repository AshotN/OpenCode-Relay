package com.ashotn.opencode.relay.toolwindow

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JPanel
import javax.swing.SwingConstants

class ResolvingExecutablePanel : JPanel(GridBagLayout()) {

    init {
        val gbc = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.CENTER
            insets = JBUI.insets(JBUI.scale(4), JBUI.scale(16), JBUI.scale(4), JBUI.scale(16))
        }

        val base = UIUtil.getLabelFont()
        val label = JBLabel("Checking OpenCode installation…", SwingConstants.CENTER).apply {
            font = base.deriveFont(base.size * 1.1f)
            foreground = JBUI.CurrentTheme.Label.disabledForeground()
            alignmentX = CENTER_ALIGNMENT
        }

        add(label, gbc)
    }
}
