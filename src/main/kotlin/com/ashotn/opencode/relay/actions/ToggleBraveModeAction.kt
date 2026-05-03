package com.ashotn.opencode.relay.actions

import com.ashotn.opencode.relay.OpenCodeIcons
import com.ashotn.opencode.relay.settings.OpenCodeSettings
import com.ashotn.opencode.relay.settings.OpenCodeSettingsChangedListener
import com.ashotn.opencode.relay.settings.snapshot
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.util.IconUtil

class ToggleBraveModeAction(private val project: Project) :
    ToggleAction(
        "Brave Mode",
        "Automatically accept OpenCode permission requests",
        AllIcons.Actions.Lightning
    ),
    DumbAware {

    private val braveModeIcon = IconUtil.colorize(OpenCodeIcons.LightningFilled, JBColor(0xF59E0B, 0xFBBF24))
    private val inactiveBraveModeIcon = IconUtil.colorize(AllIcons.Actions.Lightning, JBColor(0x6B7280, 0x9CA3AF))

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        super.update(e)
        val braveModeEnabled = OpenCodeSettings.getInstance(project).braveModeEnabled
        e.presentation.icon = if (braveModeEnabled) braveModeIcon else inactiveBraveModeIcon
        e.presentation.selectedIcon = braveModeIcon
        e.presentation.description = if (braveModeEnabled) {
            "Disable Brave Mode and ask before accepting OpenCode permission requests"
        } else {
            "Enable Brave Mode to automatically accept OpenCode permission requests"
        }
    }

    override fun isSelected(e: AnActionEvent): Boolean = OpenCodeSettings.getInstance(project).braveModeEnabled

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        updateBraveMode(state)
    }

    private fun updateBraveMode(enabled: Boolean) {
        val settings = OpenCodeSettings.getInstance(project)
        if (settings.braveModeEnabled == enabled) return

        val oldSettings = settings.snapshot()
        settings.braveModeEnabled = enabled
        val newSettings = settings.snapshot()
        project.messageBus.syncPublisher(OpenCodeSettingsChangedListener.TOPIC)
            .onSettingsChanged(oldSettings, newSettings)
    }
}
