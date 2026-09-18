package com.ashotn.opencode.relay

import com.ashotn.opencode.relay.settings.OpenCodeSettings
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class OpenCodeStartupActivity : ProjectActivity, DumbAware {

    override suspend fun execute(project: Project) {
        val plugin = OpenCodePlugin.getInstance(project)
        plugin.resolveExecutable { state ->
            val settings = OpenCodeSettings.getInstance(project)
            if (settings.autoStartOnStartup && state is OpenCodeExecutableResolutionState.Resolved) {
                plugin.startServer(settings.serverPort)
            }
        }
    }
}
