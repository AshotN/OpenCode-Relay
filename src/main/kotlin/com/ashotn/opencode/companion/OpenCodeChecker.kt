package com.ashotn.opencode.companion

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.SystemInfo
import java.io.File
import java.util.concurrent.TimeUnit

data class OpenCodeInfo(val path: String, val version: String)

object OpenCodeChecker {

    private val log = logger<OpenCodeChecker>()

    /**
     * Returns an [OpenCodeInfo] containing the resolved path and version of the `opencode`
     * executable, or null if no valid executable is found.
     *
     * If [userProvidedPath] is non-blank, it is validated (file exists, is executable, and
     * responds to `--version`). If it does not pass validation, a warning is logged and null
     * is returned immediately (auto-resolve is NOT attempted).
     *
     * If [userProvidedPath] is blank or null, the auto-resolve strategy is used:
     * PATH entries are searched first, followed by common install locations. The first
     * candidate that passes all validation gates (including `--version`) is returned.
     */
    fun findExecutable(userProvidedPath: String? = null): OpenCodeInfo? {
        if (userProvidedPath.isNullOrBlank()) {
            return autoResolve()
        }
        val file = File(userProvidedPath)
        return if (file.isFile && file.canExecute()) {
            val version = getVersion(file.absolutePath)
            if (version != null) {
                OpenCodeInfo(file.absolutePath, version)
            } else {
                log.warn("OpenCode executable at user-provided path did not respond to --version: $userProvidedPath")
                null
            }
        } else {
            log.warn("OpenCode executable not found at user-provided path: $userProvidedPath")
            null
        }
    }

    private fun autoResolve(): OpenCodeInfo? {
        val executableNames = if (SystemInfo.isWindows) listOf("opencode.exe") else listOf("opencode")

        val pathEnv = System.getenv("PATH") ?: ""
        for (dir in pathEnv.split(File.pathSeparator)) {
            for (executableName in executableNames) {
                val candidate = File(dir, executableName)
                if (candidate.isFile && candidate.canExecute()) {
                    val version = getVersion(candidate.absolutePath)
                    if (version != null) return OpenCodeInfo(candidate.absolutePath, version)
                }
            }
        }

        val home = System.getProperty("user.home")
        val extraLocations = if (SystemInfo.isWindows) {
            listOf(
                "${System.getenv("LOCALAPPDATA") ?: ""}\\Programs\\opencode\\opencode.exe",
            )
        } else {
            listOf(
                "/usr/local/bin/opencode",        // Homebrew (Intel Mac)
                "/opt/homebrew/bin/opencode",     // Homebrew (Apple Silicon)
                "/usr/bin/opencode",
                "$home/.local/bin/opencode",
                "$home/.bun/bin/opencode",
                "$home/.npm-global/bin/opencode",
            )
        }

        for (path in extraLocations) {
            val candidate = File(path)
            if (candidate.isFile && candidate.canExecute()) {
                val version = getVersion(candidate.absolutePath)
                if (version != null) return OpenCodeInfo(candidate.absolutePath, version)
            }
        }

        return null
    }

    private fun getVersion(path: String): String? {
        return try {
            val process = ProcessBuilder(path, "--version")
                .redirectErrorStream(true)
                .start()

            val completed = process.waitFor(3, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                log.warn("OpenCode --version timed out for: $path")
                return null
            }
            if (process.exitValue() != 0) {
                log.warn("OpenCode --version exited with code ${process.exitValue()} for: $path")
                return null
            }

            process.inputStream.bufferedReader().use { reader ->
                reader.readText().trim().ifBlank { null }
            }
        } catch (e: Exception) {
            log.warn("Failed to run --version for: $path", e)
            null
        }
    }
}
