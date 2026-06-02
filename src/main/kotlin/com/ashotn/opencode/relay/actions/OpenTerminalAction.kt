package com.ashotn.opencode.relay.actions

import com.ashotn.opencode.relay.OpenCodePlugin
import com.ashotn.opencode.relay.OpenCodeProcessEnvironment
import com.ashotn.opencode.relay.ServerState
import com.ashotn.opencode.relay.settings.OpenCodeSettings
import com.ashotn.opencode.relay.settings.OpenCodeServerAuth
import com.ashotn.opencode.relay.settings.processEnvironmentVariables
import com.ashotn.opencode.relay.util.applyStrings
import com.ashotn.opencode.relay.util.showNotification
import com.ashotn.opencode.relay.util.serverUrl
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import java.io.File

class OpenTerminalAction(private val project: Project) : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val plugin = OpenCodePlugin.getInstance(project)
        val ready = plugin.serverState == ServerState.READY
        val hasExecutable = !plugin.openCodeInfo?.path.isNullOrBlank()
        e.presentation.icon = AllIcons.Debugger.Console
        e.applyStrings(ActionStrings.OPEN_TERMINAL, ready && hasExecutable)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val settings = OpenCodeSettings.getInstance(project)
        val plugin = OpenCodePlugin.getInstance(project)
        if (plugin.serverState != ServerState.READY) return
        val executablePath = plugin.openCodeInfo?.path ?: return

        val url = serverUrl(settings.serverPort)
        launchExternalTerminal(executablePath, url)
    }

    /**
     * Launches an external OS terminal window running `<resolved-opencode> attach <url>`.
     */
    private fun launchExternalTerminal(executablePath: String, url: String) {
        val environmentVariables = OpenCodeSettings.getInstance(project)
            .processEnvironmentVariables(OpenCodeServerAuth.getInstance(project).connectionEnvironmentVariables())
        val attachArgs = listOf(executablePath, "attach", url)
        val processCommand: List<String> = when {
            SystemInfo.isWindows -> listOf(
                "cmd",
                "/c",
                buildWindowsAttachCommand(executablePath, url),
            )

            SystemInfo.isMac -> {
                val command = buildPosixAttachCommand(
                    OpenCodeProcessEnvironment.terminalCommand(attachArgs, environmentVariables)
                )
                listOf(
                    "osascript",
                    "-e",
                    "tell application \"Terminal\" to do script \"${escapeAppleScriptString(command)}\"",
                )
            }

            else -> findLinuxTerminalCommand(attachArgs)
        }

        if (processCommand.isEmpty()) {
            project.showNotification(
                "Cannot open terminal",
                "No supported terminal emulator found.",
                NotificationType.ERROR,
            )
            return
        }

        try {
            ProcessBuilder(processCommand)
                .inheritIO()
                .apply {
                    OpenCodeProcessEnvironment.configure(this, executablePath, environmentVariables)
                }
                .start()
        } catch (ex: Exception) {
            project.showNotification(
                "Failed to open terminal",
                "Could not launch terminal: ${ex.message}",
                NotificationType.ERROR,
            )
        }
    }

    private fun buildWindowsAttachCommand(executablePath: String, url: String): String =
        "start \"\" \"$executablePath\" attach \"$url\""

    private fun buildPosixAttachCommand(command: List<String>): String =
        command.joinToString(" ", transform = ::shellQuote)

    private fun shellQuote(value: String): String =
        "'${value.replace("'", "'\"'\"'")}'"

    private fun escapeAppleScriptString(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    /**
     * Tries common Linux terminal emulators in order of preference.
     * Returns the full command list for the first one found on PATH,
     * or an empty list if none are available.
     */
    private fun findLinuxTerminalCommand(attachArgs: List<String>): List<String> {
        val candidates = listOf(
            // Preferred: distro-agnostic default symlink
            listOf("x-terminal-emulator", "-e") + attachArgs,
            // Common desktop environments
            listOf("gnome-terminal", "--") + attachArgs,
            listOf("konsole", "-e") + attachArgs,
            listOf("xfce4-terminal", "-x") + attachArgs,
            // Universal fallback
            listOf("xterm", "-e") + attachArgs,
        )

        for (candidate in candidates) {
            val executable = candidate.first()
            if (isOnPath(executable)) return candidate
        }
        return emptyList()
    }

    private fun isOnPath(executable: String): Boolean {
        val pathEnv = OpenCodeProcessEnvironment.pathEnvironmentValue() ?: return false
        return pathEnv.split(File.pathSeparator).any { dir ->
            File(dir, executable).let { it.isFile && it.canExecute() }
        }
    }
}
