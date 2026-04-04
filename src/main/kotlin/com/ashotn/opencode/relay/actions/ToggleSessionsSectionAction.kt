package com.ashotn.opencode.relay.actions

import com.ashotn.opencode.relay.settings.OpenCodeSettings
import com.ashotn.opencode.relay.settings.OpenCodeSettingsChangedListener
import com.ashotn.opencode.relay.settings.snapshot
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.Project

class ToggleSessionsSectionAction(private val project: Project) :
    ToggleAction("Sessions", "Show or hide the sessions section above the inline terminal", null) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.icon = AllIcons.Actions.Preview
        val settings = OpenCodeSettings.getInstance(project)
        e.presentation.isEnabled = settings.inlineTerminalEnabled
        e.presentation.description = if (settings.inlineTerminalEnabled) {
            if (settings.sessionsSectionVisible) {
                "Hide the sessions section and let the inline terminal use the full height"
            } else {
                "Show the sessions section above the inline terminal"
            }
        } else {
            "Enable the inline terminal in settings to toggle the sessions section"
        }
    }

    override fun isSelected(e: AnActionEvent): Boolean = OpenCodeSettings.getInstance(project).sessionsSectionVisible

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        updateSessionsSectionVisibility(state)
    }

    private fun updateSessionsSectionVisibility(visible: Boolean) {
        val settings = OpenCodeSettings.getInstance(project)
        if (settings.sessionsSectionVisible == visible) return

        val oldSettings = settings.snapshot()
        settings.sessionsSectionVisible = visible
        val newSettings = settings.snapshot()
        project.messageBus.syncPublisher(OpenCodeSettingsChangedListener.TOPIC)
            .onSettingsChanged(oldSettings, newSettings)
    }
}
