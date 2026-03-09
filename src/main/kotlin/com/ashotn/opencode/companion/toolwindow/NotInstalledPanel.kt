package com.ashotn.opencode.companion.toolwindow

import com.intellij.ide.BrowserUtil
import com.intellij.icons.AllIcons
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JPanel
import javax.swing.SwingConstants

class NotInstalledPanel : JPanel(GridBagLayout()) {

    init {
        val gbc = GridBagConstraints().apply {
            gridx = 0
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.CENTER
            insets = Insets(JBUI.scale(4), JBUI.scale(16), JBUI.scale(4), JBUI.scale(16))
        }

        val base = UIUtil.getLabelFont()

        val emojiLabel = JBLabel(AllIcons.General.Warning, SwingConstants.CENTER).apply {
            alignmentX = CENTER_ALIGNMENT
        }

        val titleLabel = JBLabel("OpenCode not found", SwingConstants.CENTER).apply {
            font = base.deriveFont(Font.BOLD, base.size * 2f)
            alignmentX = CENTER_ALIGNMENT
        }

        val subtitleLabel = JBLabel(
            "<html><center>To get started, install OpenCode<br>and make sure it's available on your PATH.</center></html>",
            SwingConstants.CENTER
        ).apply {
            font = base.deriveFont(base.size * 1.1f)
            foreground = JBUI.CurrentTheme.Label.disabledForeground()
            alignmentX = CENTER_ALIGNMENT
        }

        val linkLabel = HyperlinkLabel("opencode.ai").apply {
            font = base.deriveFont(Font.BOLD, base.size * 1.1f)
            alignmentX = CENTER_ALIGNMENT
            addHyperlinkListener { BrowserUtil.browse("https://opencode.ai") }
        }

        gbc.gridy = 0; add(emojiLabel, gbc)
        gbc.gridy = 1; gbc.insets = Insets(JBUI.scale(8), JBUI.scale(16), JBUI.scale(4), JBUI.scale(16))
        add(titleLabel, gbc)
        gbc.gridy = 2; gbc.insets = Insets(JBUI.scale(4), JBUI.scale(16), JBUI.scale(4), JBUI.scale(16))
        add(subtitleLabel, gbc)
        gbc.gridy = 3; gbc.insets = Insets(JBUI.scale(16), JBUI.scale(16), JBUI.scale(4), JBUI.scale(16))
        add(linkLabel, gbc)
    }
}
