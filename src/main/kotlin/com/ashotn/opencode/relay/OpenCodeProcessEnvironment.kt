package com.ashotn.opencode.relay

import com.intellij.openapi.util.SystemInfo
import java.io.File

internal object OpenCodeProcessEnvironment {

    fun configure(
        processBuilder: ProcessBuilder,
        executablePath: String,
        environmentVariables: Map<String, String> = emptyMap(),
    ) {
        applyEnvironmentVariables(processBuilder.environment(), environmentVariables)
        nvmBinDirectory(executablePath)?.let { binDir ->
            prependPath(processBuilder.environment(), binDir)
        }
    }

    fun terminalCommand(
        command: List<String>,
        environmentVariables: Map<String, String>,
    ): List<String> {
        if (command.isEmpty()) return command

        val environment = linkedMapOf<String, String>()
        applyEnvironmentVariables(environment, environmentVariables)
        nvmBinDirectory(command.first())?.let { binDir ->
            prependPath(environment, binDir)
        }
        if (environment.isEmpty()) return command

        return if (SystemInfo.isWindows) {
            listOf("cmd", "/c", buildWindowsTerminalCommand(command, environment))
        } else {
            listOf("/usr/bin/env") + environment.map { (name, value) -> "$name=$value" } + command
        }
    }

    private fun nvmBinDirectory(executablePath: String): String? {
        val executable = File(executablePath)
        val parent = executable.parentFile ?: return null
        val normalizedParent = parent.invariantSeparatorsPath
        val normalizedExecutable = executable.invariantSeparatorsPath

        return if (
            normalizedParent.endsWith("/bin") &&
            normalizedExecutable.contains("/.nvm/versions/node/")
        ) {
            parent.absolutePath
        } else {
            null
        }
    }

    private fun applyEnvironmentVariables(
        environment: MutableMap<String, String>,
        environmentVariables: Map<String, String>,
    ) {
        environmentVariables.forEach { (name, value) ->
            val normalizedName = name.trim()
            if (normalizedName.isBlank()) return@forEach

            val key = environment.keys.firstOrNull { it.equals(normalizedName, ignoreCase = true) } ?: normalizedName
            environment[key] = value
        }
    }

    private fun prependPath(environment: MutableMap<String, String>, directory: String) {
        val pathKey = environment.keys.firstOrNull { it.equals("PATH", ignoreCase = true) } ?: "PATH"
        environment[pathKey] = pathWithPrependedDirectory(environment[pathKey], directory)
    }

    private fun buildWindowsTerminalCommand(
        command: List<String>,
        environment: Map<String, String>,
    ): String = buildString {
        environment.forEach { (name, value) ->
            append("set \"")
            append(name)
            append('=')
            append(value.replace("\"", "\"\""))
            append("\" && ")
        }
        append(command.joinToString(" ") { windowsQuote(it) })
    }

    private fun windowsQuote(value: String): String =
        "\"${value.replace("\"", "\\\"")}\""

    private fun pathWithPrependedDirectory(currentPath: String?, directory: String): String {
        val pathEntries = currentPath.orEmpty().split(File.pathSeparator).filter { it.isNotBlank() }
        if (pathEntries.any { it == directory }) return currentPath.orEmpty()

        return if (currentPath.isNullOrBlank()) {
            directory
        } else {
            directory + File.pathSeparator + currentPath
        }
    }
}
