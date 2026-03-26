package com.ashotn.opencode.relay

import com.intellij.openapi.util.SystemInfo
import java.io.File

internal object OpenCodeProcessEnvironment {

    fun configure(processBuilder: ProcessBuilder, executablePath: String) {
        nvmBinDirectory(executablePath)?.let { binDir ->
            prependPath(processBuilder.environment(), binDir)
        }
    }

    fun terminalCommand(command: List<String>): List<String> {
        if (command.isEmpty()) return command

        val executablePath = command.first()
        val nvmBinDirectory = nvmBinDirectory(executablePath) ?: return command
        if (SystemInfo.isWindows) return command

        return listOf(
            "/usr/bin/env",
            "PATH=${pathWithPrependedDirectory(System.getenv("PATH"), nvmBinDirectory)}",
        ) + command
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

    private fun prependPath(environment: MutableMap<String, String>, directory: String) {
        val pathKey = environment.keys.firstOrNull { it.equals("PATH", ignoreCase = true) } ?: "PATH"
        environment[pathKey] = pathWithPrependedDirectory(environment[pathKey], directory)
    }

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
