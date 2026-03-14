package com.ashotn.opencode.relay

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class OpenCodeStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        val plugin = OpenCodePlugin.getInstance(project)
        plugin.resolveExecutable()
    }
}
