package com.ashotn.opencode.relay.toolwindow

import com.ashotn.opencode.relay.OpenCodeInfo
import com.intellij.ide.BrowserUtil
import com.intellij.icons.AllIcons
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * Shown when an OpenCode executable is installed but it is an unsupported 1.x version.
 */
class IncompatibleVersionPanel(openCodeInfo: OpenCodeInfo) : JPanel(GridBagLayout()) {

    companion object {
        private const val MIGRATION_URL = "https://opencode.ai/v2/docs/migrate-v1"
    }

    init {
        val gbc = GridBagConstraints().apply {
            gridx = 0
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.CENTER
            insets = JBUI.insets(JBUI.scale(4), JBUI.scale(16), JBUI.scale(4), JBUI.scale(16))
        }

        val base = UIUtil.getLabelFont()

        val emojiLabel = JBLabel(AllIcons.General.Warning, SwingConstants.CENTER).apply {
            alignmentX = CENTER_ALIGNMENT
        }

        val titleLabel = JBLabel("OpenCode 2.x required", SwingConstants.CENTER).apply {
            font = base.deriveFont(Font.BOLD, base.size * 2f)
            alignmentX = CENTER_ALIGNMENT
        }

        val subtitleLabel = JBLabel(
            "<html><center>Detected OpenCode ${escapeHtml(openCodeInfo.version)} at<br>" +
                    "${escapeHtml(openCodeInfo.path)}<br>" +
                    "Upgrade to OpenCode 2.0 or newer to continue.</center></html>",
            SwingConstants.CENTER
        ).apply {
            font = base.deriveFont(base.size * 1.1f)
            foreground = JBUI.CurrentTheme.Label.disabledForeground()
            alignmentX = CENTER_ALIGNMENT
        }

        val linkLabel = HyperlinkLabel("Upgrade OpenCode").apply {
            font = base.deriveFont(Font.BOLD, base.size * 1.1f)
            alignmentX = CENTER_ALIGNMENT
            addHyperlinkListener { BrowserUtil.browse(MIGRATION_URL) }
        }

        gbc.gridy = 0; add(emojiLabel, gbc)
        gbc.gridy = 1; gbc.insets = JBUI.insets(JBUI.scale(8), JBUI.scale(16), JBUI.scale(4), JBUI.scale(16))
        add(titleLabel, gbc)
        gbc.gridy = 2; gbc.insets = JBUI.insets(JBUI.scale(4), JBUI.scale(16), JBUI.scale(4), JBUI.scale(16))
        add(subtitleLabel, gbc)
        gbc.gridy = 3; gbc.insets = JBUI.insets(JBUI.scale(16), JBUI.scale(16), JBUI.scale(4), JBUI.scale(16))
        add(linkLabel, gbc)
    }

    private fun escapeHtml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
