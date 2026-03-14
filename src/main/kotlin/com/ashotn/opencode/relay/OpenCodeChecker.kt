package com.ashotn.opencode.relay

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.SystemInfo
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

data class OpenCodeInfo(val path: String, val version: String)

object OpenCodeChecker {

    private val log = logger<OpenCodeChecker>()
    private val requiredHelpCommands = listOf("opencode serve", "opencode attach")
    private const val COMMAND_TIMEOUT_SECONDS = 10L
    private const val OUTPUT_JOIN_TIMEOUT_MILLIS = 1_000L

    private data class CommandResult(
        val exitCode: Int?,
        val output: String,
        val timedOut: Boolean,
    )

    private val osSpecificInstallLocations: List<String>
        get() = when {
            SystemInfo.isWindows -> {
                val localAppData = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
                val appData = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }
                buildList {
                    localAppData?.let {
                        add("$it\\OpenCode\\opencode-cli.exe")
                    }
                    appData?.let {
                        add("$it\\npm\\opencode")
                        add("$it\\npm\\opencode.cmd")
                    }
                }
            }

            SystemInfo.isMac -> {
                val home = System.getProperty("user.home")?.takeIf { it.isNotBlank() }
                buildList {
                    add("/usr/local/bin/opencode")    // Homebrew (Intel Mac)
                    add("/opt/homebrew/bin/opencode") // Homebrew (Apple Silicon)
                    home?.let {
                        add("$it/.local/bin/opencode")
                        add("$it/.bun/bin/opencode")
                        add("$it/.npm-global/bin/opencode")
                    }
                }
            }

            SystemInfo.isLinux -> {
                val home = System.getProperty("user.home")?.takeIf { it.isNotBlank() }
                buildList {
                    add("/usr/bin/opencode")
                    home?.let {
                        add("$it/.opencode/bin/opencode")
                        add("$it/.local/bin/opencode")
                        add("$it/.bun/bin/opencode")
                        add("$it/.npm-global/bin/opencode")
                    }
                }
            }

            else -> emptyList()
        }

    /**
     * Returns an [OpenCodeInfo] containing the resolved path and version of the `opencode`
     * executable, or null if no valid executable is found.
     *
     * If [userProvidedPath] is non-blank, it is validated (file exists, is runnable on this OS, and
     * responds to `--version` and `--help`). If it does not pass validation, a warning is
     * logged and null is returned immediately (auto-resolve is NOT attempted).
     *
     * If [userProvidedPath] is blank or null, the auto-resolve strategy is used:
     * PATH entries are searched first, followed by common install locations. The first
     * candidate that passes all validation gates is returned.
     */
    fun findExecutable(userProvidedPath: String? = null): OpenCodeInfo? {
        val normalizedUserProvidedPath = normalizeUserProvidedPath(userProvidedPath) ?: return autoResolve()

        val file = File(normalizedUserProvidedPath)
        if (!isCandidateFile(file)) {
            log.warn(
                "OpenCode executable at user-provided path is invalid: $normalizedUserProvidedPath " +
                        "(exists=${file.exists()}, isFile=${file.isFile}, canExecute=${file.canExecute()}, os=${SystemInfo.OS_NAME})"
            )
            return null
        }

        val absolutePath = file.absolutePath
        return validateCandidate(absolutePath) ?: run {
            log.warn("OpenCode executable at user-provided path failed validation: $absolutePath")
            null
        }
    }

    private fun autoResolve(): OpenCodeInfo? {
        val executableNames =
            if (SystemInfo.isWindows) {
                listOf("opencode", "opencode.cmd", "opencode-cli.exe")
            } else {
                listOf("opencode")
            }

        val pathEnv = System.getenv("PATH")
        if (pathEnv.isNullOrBlank()) {
            log.debug("PATH environment variable is empty; skipping PATH scan")
        } else {
            for (dir in pathEnv.split(File.pathSeparator)) {
                if (dir.isBlank()) continue
                for (executableName in executableNames) {
                    val candidate = File(dir, executableName)
                    if (isCandidateFile(candidate)) {
                        validateCandidate(candidate.absolutePath)?.let { return it }
                    }
                }
            }
        }

        for (path in osSpecificInstallLocations) {
            val candidate = File(path)
            if (isCandidateFile(candidate)) {
                validateCandidate(candidate.absolutePath)?.let { return it }
            }
        }

        return null
    }

    private fun normalizeUserProvidedPath(path: String?): String? {
        return path
            ?.trim()
            ?.removeSurrounding("\"")
            ?.removeSurrounding("'")
            ?.takeIf { it.isNotBlank() }
    }

    private fun isCandidateFile(candidate: File): Boolean {
        if (!candidate.exists() || !candidate.isFile) {
            return false
        }
        return if (SystemInfo.isWindows) {
            true
        } else {
            candidate.canExecute()
        }
    }

    private fun validateCandidate(path: String): OpenCodeInfo? {
        val version = getVersion(path) ?: return null
        val helpOutput = getHelpOutput(path) ?: return null
        if (!hasRequiredHelpCommands(helpOutput, path)) return null
        return OpenCodeInfo(path, version)
    }

    private fun getVersion(path: String): String? {
        val result = runCommand(path, "--version") ?: return null

        if (result.timedOut) {
            log.warn("OpenCode --version timed out for: $path")
            return null
        }

        val exitCode = result.exitCode
        if (exitCode != 0) {
            log.warn("OpenCode --version exited with code $exitCode for: $path")
            return null
        }

        val output = result.output
        if (output.isBlank()) {
            log.warn("OpenCode --version returned empty output for: $path")
            return null
        }

        val startsWithSemVer = Regex("^\\d+\\.\\d+\\.\\d+.*").matches(output)
        if (!startsWithSemVer) {
            log.warn("OpenCode --version output did not start with semantic version for: $path. Output: '$output'")
            return null
        }

        return output
    }

    private fun getHelpOutput(path: String): String? {
        val result = runCommand(path, "--help") ?: return null

        if (result.timedOut) {
            log.warn("OpenCode --help timed out for: $path")
            return null
        }

        val exitCode = result.exitCode
        if (exitCode != 0) {
            log.warn("OpenCode --help exited with code $exitCode for: $path")
            return null
        }

        val output = result.output
        if (output.isBlank()) {
            log.warn("OpenCode --help returned empty output for: $path")
            return null
        }

        return output
    }

    private fun runCommand(path: String, arg: String): CommandResult? {
        return try {
            val process = ProcessBuilder(path, arg)
                .redirectErrorStream(true)
                .start()

            var output = ""
            val readerThread = thread(start = true, isDaemon = true, name = "opencode-checker-$arg") {
                output = process.inputStream.bufferedReader().use { it.readText() }
            }

            val completed = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                process.waitFor(1, TimeUnit.SECONDS)
                runCatching { process.inputStream.close() }
                readerThread.join(OUTPUT_JOIN_TIMEOUT_MILLIS)
                return CommandResult(exitCode = null, output = output.trim(), timedOut = true)
            }

            readerThread.join(OUTPUT_JOIN_TIMEOUT_MILLIS)
            if (readerThread.isAlive) {
                log.warn("OpenCode command output reader did not finish for: $path $arg")
            }

            CommandResult(exitCode = process.exitValue(), output = output.trim(), timedOut = false)
        } catch (e: Exception) {
            log.warn("Failed to run command '$arg' for: $path", e)
            null
        }
    }

    private fun hasRequiredHelpCommands(helpOutput: String, path: String): Boolean {
        val normalizedOutput = helpOutput.lowercase()
        val missingCommands = requiredHelpCommands.filterNot { normalizedOutput.contains(it) }
        if (missingCommands.isNotEmpty()) {
            log.warn(
                "OpenCode --help output missing required commands for: $path. Missing: ${
                    missingCommands.joinToString(
                        ", "
                    )
                }"
            )
            return false
        }
        return true
    }
}
