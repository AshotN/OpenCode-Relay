package com.ashotn.opencode

import com.intellij.openapi.diagnostic.logger
import java.io.File

object OpenCodeChecker {

    private val log = logger<OpenCodeChecker>()

    /**
     * Returns the resolved path to the `opencode` executable, or null if not found.
     *
     * If [userProvidedPath] is non-blank, it is validated and returned if the file exists
     * and is executable. If it does not pass validation, a warning is logged and null is
     * returned immediately (auto-resolve is NOT attempted).
     *
     * If [userProvidedPath] is blank or null, the auto-resolve strategy is used:
     * PATH entries are searched first, followed by common install locations.
     */
    fun findExecutable(userProvidedPath: String? = null): String? {
        if (userProvidedPath.isNullOrBlank()) {
            return autoResolve()
        }
        val file = File(userProvidedPath)
        return if (file.isFile && file.canExecute()) {
            file.absolutePath
        } else {
            log.warn("OpenCode executable not found at user-provided path: $userProvidedPath")
            null
        }
    }

    private fun autoResolve(): String? {
        val executableName = if (isWindows()) "opencode.exe" else "opencode"

        // Check PATH entries first
        val pathEnv = System.getenv("PATH") ?: ""
        for (dir in pathEnv.split(File.pathSeparator)) {
            val candidate = File(dir, executableName)
            if (candidate.isFile && candidate.canExecute()) {
                return candidate.absolutePath
            }
        }

        // Check common install locations not always on PATH
        val home = System.getProperty("user.home")
        val extraLocations = if (isWindows()) {
            listOf(
                "${System.getenv("APPDATA")}\\npm\\opencode.cmd",
                "${System.getenv("LOCALAPPDATA")}\\Programs\\opencode\\opencode.exe"
            )
        } else {
            listOf(
                "/usr/local/bin/opencode",
                "/usr/bin/opencode",
                "$home/.local/bin/opencode",
                "$home/.bun/bin/opencode",
                "$home/.npm-global/bin/opencode"
            )
        }

        return extraLocations
            .map(::File)
            .firstOrNull { it.isFile && it.canExecute() }
            ?.absolutePath
    }

    fun isInstalled(): Boolean = findExecutable() != null

    private fun isWindows(): Boolean =
        System.getProperty("os.name")?.lowercase()?.contains("win") == true
}
