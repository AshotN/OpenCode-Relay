package com.ashotn.opencode.relay

import com.ashotn.opencode.relay.settings.OpenCodeSettings
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class OpenCodeStartupActivity : ProjectActivity, DumbAware {

    override suspend fun execute(project: Project) {
        val plugin = OpenCodePlugin.getInstance(project)
        plugin.resolveExecutable { state ->
            if (state !is OpenCodeExecutableResolutionState.Resolved) return@resolveExecutable

            // Connect to a running background service when present; only spawn a
            // plugin-owned server when there is nothing to connect to.
            plugin.detectBackgroundService { service ->
                val settings = OpenCodeSettings.getInstance(project)
                if (service == null && settings.autoStartOnStartup) {
                    plugin.startServer(settings.serverPort)
                }
            }
        }
    }
}
