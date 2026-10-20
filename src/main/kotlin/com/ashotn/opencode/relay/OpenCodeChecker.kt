package com.ashotn.opencode.relay

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.SystemInfo
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

data class OpenCodeInfo(val path: String, val version: String)

/**
 * Result of searching for an `opencode` executable.
 */
sealed interface OpenCodeExecutableDetection {
    /** A supported OpenCode 2.x executable was found. */
    data class Found(val info: OpenCodeInfo) : OpenCodeExecutableDetection

    /**
     * An executable was found but it is an unsupported major version (OpenCode 1.x).
     * [info.version] carries the detected version for display.
     */
    data class IncompatibleVersion(val info: OpenCodeInfo) : OpenCodeExecutableDetection

    /** No usable executable was found. */
    data object None : OpenCodeExecutableDetection
}

internal data class OpenCodeVersion(val major: Int, val semVer: String)

private val openCodeVersionPattern = Regex("""v?(\d+)\.(\d+)\.(\d+)(?:[-+][\w.]+)?""")
private val requiredHelpCommands = listOf("serve")

/**
 * Parses the output of `opencode --version`.
 *
 * v1 prints a bare semver ("1.4.0"); v2 prints "opencode v2.0.32".
 * Returns null when no semver can be extracted.
 */
internal fun parseOpenCodeVersion(output: String): OpenCodeVersion? {
    val match = openCodeVersionPattern.find(output) ?: return null
    val major = match.groupValues[1].toIntOrNull() ?: return null
    return OpenCodeVersion(major = major, semVer = match.value.removePrefix("v"))
}

/**
 * Checks that the `--help` output documents the subcommands the plugin relies on.
 * v2 lists bare subcommand names (e.g. `serve`); v1 listed them as "opencode serve".
 */
internal fun hasRequiredHelpCommands(helpOutput: String): Boolean =
    requiredHelpCommands.all { command ->
        Regex("\\b${Regex.escape(command)}\\b").containsMatchIn(helpOutput.lowercase())
    }

object OpenCodeChecker {

    private val log = logger<OpenCodeChecker>()

    private const val MIN_SUPPORTED_MAJOR = 2
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
                        addAll(nvmInstallLocations("$it/.nvm"))
                        add("$it/.local/bin/opencode")
                        add("$it/.bun/bin/opencode")
                        add("$it/.npm-global/bin/opencode")
                    }
                    System.getenv("NVM_BIN")?.takeIf { it.isNotBlank() }?.let {
                        add(File(it, "opencode").path)
                    }
                    System.getenv("NVM_DIR")?.takeIf { it.isNotBlank() }?.let {
                        addAll(nvmInstallLocations(it))
                    }
                }
            }

            SystemInfo.isLinux -> {
                val home = System.getProperty("user.home")?.takeIf { it.isNotBlank() }
                buildList {
                    add("/usr/bin/opencode")
                    home?.let {
                        addAll(nvmInstallLocations("$it/.nvm"))
                        add("$it/.opencode/bin/opencode")
                        add("$it/.local/bin/opencode")
                        add("$it/.bun/bin/opencode")
                        add("$it/.npm-global/bin/opencode")
                    }
                    System.getenv("NVM_BIN")?.takeIf { it.isNotBlank() }?.let {
                        add(File(it, "opencode").path)
                    }
                    System.getenv("NVM_DIR")?.takeIf { it.isNotBlank() }?.let {
                        addAll(nvmInstallLocations(it))
                    }
                }
            }

            else -> emptyList()
        }

    /**
     * Searches for an `opencode` executable and validates it against the OpenCode 2.x
     * requirements (version output + `serve` subcommand).
     *
     * If [userProvidedPath] is non-blank, it is validated in isolation (auto-resolve is
     * NOT attempted when it fails). If it is blank or null, the auto-resolve strategy
     * is used: PATH entries are searched first, followed by common install locations.
     * During auto-resolve, OpenCode 1.x candidates are skipped so a newer install
     * elsewhere can still be found; when only 1.x candidates exist, the last one is
     * reported as [OpenCodeExecutableDetection.IncompatibleVersion].
     */
    fun findExecutable(userProvidedPath: String? = null): OpenCodeExecutableDetection {
        val normalizedUserProvidedPath = normalizeUserProvidedPath(userProvidedPath) ?: return autoResolve()

        val file = File(normalizedUserProvidedPath)
        if (!isCandidateFile(file)) {
            log.warn(
                "OpenCode executable at user-provided path is invalid: $normalizedUserProvidedPath " +
                        "(exists=${file.exists()}, isFile=${file.isFile}, canExecute=${file.canExecute()}, os=${SystemInfo.OS_NAME})"
            )
            return OpenCodeExecutableDetection.None
        }

        val detection = validateCandidate(file.absolutePath)
        if (detection !is OpenCodeExecutableDetection.Found) {
            log.warn("OpenCode executable at user-provided path failed validation: ${file.absolutePath} ($detection)")
        }
        return detection
    }

    // NVM keeps packages under each installed Node version, so scan versions/node/*/bin/opencode.
    private fun nvmInstallLocations(rootPath: String): List<String> {
        val root = File(rootPath)
        val versionsRoot = File(root, "versions/node")
        if (!versionsRoot.isDirectory) return emptyList()

        return versionsRoot.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isDirectory }
            .sortedByDescending { it.name }
            .map { versionDir -> File(versionDir, "bin/opencode").path }
            .distinct()
            .toList()
    }

    private fun autoResolve(): OpenCodeExecutableDetection {
        val executableNames =
            if (SystemInfo.isWindows) {
                listOf("opencode", "opencode.cmd", "opencode-cli.exe")
            } else {
                listOf("opencode")
            }

        var incompatible: OpenCodeExecutableDetection.IncompatibleVersion? = null

        val pathEnv = OpenCodeProcessEnvironment.pathEnvironmentValue()
        if (pathEnv.isNullOrBlank()) {
            log.debug("PATH environment variable is empty; skipping PATH scan")
        } else {
            for (dir in pathEnv.split(File.pathSeparator)) {
                if (dir.isBlank()) continue
                for (executableName in executableNames) {
                    val candidate = File(dir, executableName)
                    if (!isCandidateFile(candidate)) continue
                    when (val detection = validateCandidate(candidate.absolutePath)) {
                        is OpenCodeExecutableDetection.Found -> return detection
                        is OpenCodeExecutableDetection.IncompatibleVersion -> incompatible = detection
                        OpenCodeExecutableDetection.None -> Unit
                    }
                }
            }
        }

        for (path in osSpecificInstallLocations) {
            val candidate = File(path)
            if (!isCandidateFile(candidate)) continue
            when (val detection = validateCandidate(candidate.absolutePath)) {
                is OpenCodeExecutableDetection.Found -> return detection
                is OpenCodeExecutableDetection.IncompatibleVersion -> incompatible = detection
                OpenCodeExecutableDetection.None -> Unit
            }
        }

        return incompatible ?: OpenCodeExecutableDetection.None
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

    private fun validateCandidate(path: String): OpenCodeExecutableDetection {
        val version = getVersion(path) ?: return OpenCodeExecutableDetection.None
        val info = OpenCodeInfo(path, version.semVer)
        if (version.major < MIN_SUPPORTED_MAJOR) {
            log.warn("OpenCode $version.semVer at $path is not supported; OpenCode 2.x is required")
            return OpenCodeExecutableDetection.IncompatibleVersion(info)
        }
        val helpOutput = getHelpOutput(path) ?: return OpenCodeExecutableDetection.None
        if (!hasRequiredHelpCommands(helpOutput)) {
            log.warn("OpenCode --help output missing required commands for: $path")
            return OpenCodeExecutableDetection.None
        }
        return OpenCodeExecutableDetection.Found(info)
    }

    private fun getVersion(path: String): OpenCodeVersion? {
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

        return parseOpenCodeVersion(output) ?: run {
            log.warn("Could not parse OpenCode version for: $path. Output: '$output'")
            null
        }
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
                .apply { OpenCodeProcessEnvironment.configure(this, path) }
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
}
