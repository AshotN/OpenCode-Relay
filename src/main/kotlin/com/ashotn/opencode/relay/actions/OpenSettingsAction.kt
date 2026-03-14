package com.ashotn.opencode.relay.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.ashotn.opencode.relay.settings.OpenCodeSettingsConfigurable

class OpenSettingsAction(private val project: Project) : AnAction(
    ActionStrings.OPEN_SETTINGS.text,
    ActionStrings.OPEN_SETTINGS.description,
    AllIcons.General.Settings
) {
    override fun actionPerformed(e: AnActionEvent) {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, OpenCodeSettingsConfigurable::class.java)
    }
}
