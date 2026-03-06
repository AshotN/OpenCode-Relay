package com.ashotn.opencode.actions

import com.ashotn.opencode.OpenCodePlugin
import com.ashotn.opencode.settings.OpenCodeSettings
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo

class OpenTerminalAction(private val project: Project) :
    AnAction(ActionStrings.OPEN_TERMINAL.text, ActionStrings.OPEN_TERMINAL.description, AllIcons.Debugger.Console) {

    override fun update(e: AnActionEvent) {
        val running = OpenCodePlugin.getInstance(project).isRunning
        val strings = ActionStrings.OPEN_TERMINAL
        e.presentation.isEnabled = running
        e.presentation.text = if (running) strings.text else strings.disabledText
        e.presentation.description = if (running) strings.description else strings.disabledDescription
    }

    override fun actionPerformed(e: AnActionEvent) {
        val port = OpenCodeSettings.getInstance(project).serverPort
        val command = "opencode attach http://localhost:$port"

        val processCommand: List<String> = when {
            SystemInfo.isWindows -> listOf("cmd", "/c", "start", "cmd", "/k", command)
            SystemInfo.isMac -> listOf(
                "osascript", "-e",
                """tell application "Terminal" to do script "$command""""
            )
            else -> findLinuxTerminalCommand(command)
        }

        if (processCommand.isEmpty()) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("OpenCode")
                .createNotification(
                    "Cannot open terminal",
                    "No supported terminal emulator found. Install xterm, gnome-terminal, konsole, or xfce4-terminal.",
                    NotificationType.WARNING
                )
                .notify(project)
            return
        }

        try {
            ProcessBuilder(processCommand)
                .inheritIO()
                .start()
        } catch (ex: Exception) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("OpenCode")
                .createNotification(
                    "Failed to open terminal",
                    "Could not launch terminal: ${ex.message}",
                    NotificationType.ERROR
                )
                .notify(project)
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
        if (SystemInfo.isWindows) {
            // 'which' is not available on Windows; check PATH entries directly
            val pathEnv = System.getenv("PATH") ?: return false
            return pathEnv.split(java.io.File.pathSeparator).any { dir ->
                listOf(executable, "$executable.exe", "$executable.cmd", "$executable.bat").any { name ->
                    java.io.File(dir, name).let { it.isFile && it.canExecute() }
                }
            }
        }
        return try {
            ProcessBuilder("which", executable)
                .redirectErrorStream(true)
                .start()
                .waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }
}
