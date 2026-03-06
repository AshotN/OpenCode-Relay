package com.ashotn.opencode.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.ashotn.opencode.settings.OpenCodeSettingsConfigurable

class OpenSettingsAction(private val project: Project) : AnAction(
    "OpenCode Settings",
    "Open OpenCode settings",
    AllIcons.General.Settings
) {
    override fun actionPerformed(e: AnActionEvent) {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, OpenCodeSettingsConfigurable::class.java)
    }
}
