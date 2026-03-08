package com.ashotn.opencode.actions

import com.ashotn.opencode.OpenCodePlugin
import com.ashotn.opencode.settings.OpenCodeSettings
import com.ashotn.opencode.util.applyStrings
import com.ashotn.opencode.util.showNotification
import com.ashotn.opencode.util.serverUrl
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo

class OpenTerminalAction(private val project: Project) : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val running = OpenCodePlugin.getInstance(project).isRunning
        e.presentation.icon = AllIcons.Debugger.Console
        e.applyStrings(ActionStrings.OPEN_TERMINAL, running)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val settings = OpenCodeSettings.getInstance(project)
        launchExternalTerminal("opencode attach ${serverUrl(settings.serverPort)}")
    }

    /**
     * Launches an external OS terminal window running [command].
     */
    private fun launchExternalTerminal(command: String) {
        val processCommand: List<String> = when {
            SystemInfo.isWindows -> listOf("cmd", "/c", "start", "cmd", "/k", command)
            SystemInfo.isMac -> listOf(
                "osascript", "-e",
                """tell application "Terminal" to do script "$command""""
            )
            else -> findLinuxTerminalCommand(command)
        }

        if (processCommand.isEmpty()) {
            project.showNotification(
                "Cannot open terminal",
                "No supported terminal emulator found. Install xterm, gnome-terminal, konsole, or xfce4-terminal.",
                NotificationType.WARNING,
            )
            return
        }

        try {
            ProcessBuilder(processCommand)
                .inheritIO()
                .start()
        } catch (ex: Exception) {
            project.showNotification(
                "Failed to open terminal",
                "Could not launch terminal: ${ex.message}",
                NotificationType.ERROR,
            )
        }
    }

    /**
     * Tries common Linux terminal emulators in order of preference.
     * Returns the full command list for the first one found on PATH,
     * or an empty list if none are available.
     */
    private fun findLinuxTerminalCommand(command: String): List<String> {
        val candidates = listOf(
            // Preferred: distro-agnostic default symlink
            listOf("x-terminal-emulator", "-e", command),
            // Common desktop environments
            listOf("gnome-terminal", "--", "bash", "-c", "$command; exec bash"),
            listOf("konsole", "-e", "bash", "-c", "$command; exec bash"),
            listOf("xfce4-terminal", "-e", "bash -c \"$command; exec bash\""),
            // Universal fallback
            listOf("xterm", "-e", "bash -c \"$command; exec bash\""),
        )

        for (candidate in candidates) {
            val executable = candidate.first()
            if (isOnPath(executable)) return candidate
        }
        return emptyList()
    }

    private fun isOnPath(executable: String): Boolean {
        val pathEnv = System.getenv("PATH") ?: return false
        return pathEnv.split(java.io.File.pathSeparator).any { dir ->
            java.io.File(dir, executable).let { it.isFile && it.canExecute() }
        }
    }
}
