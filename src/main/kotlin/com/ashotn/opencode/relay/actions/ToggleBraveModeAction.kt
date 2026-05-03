package com.ashotn.opencode.relay.actions

import com.ashotn.opencode.relay.OpenCodeIcons
import com.ashotn.opencode.relay.settings.OpenCodeSettings
import com.ashotn.opencode.relay.settings.OpenCodeSettingsChangedListener
import com.ashotn.opencode.relay.settings.snapshot
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project

class ToggleBraveModeAction(private val project: Project) :
    ToggleAction(
        "Brave Mode",
        "Automatically accept OpenCode permission requests",
        OpenCodeIcons.LightningFilled,
    ),
    DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        super.update(e)
        val braveModeEnabled = OpenCodeSettings.getInstance(project).braveModeEnabled
        e.presentation.icon = if (braveModeEnabled) OpenCodeIcons.LightningFilled else OpenCodeIcons.Lightning
        e.presentation.selectedIcon = OpenCodeIcons.LightningFilled
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
