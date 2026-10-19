package com.ashotn.opencode.relay.api.service

import com.ashotn.opencode.relay.api.health.HealthApiClient
import com.ashotn.opencode.relay.api.transport.ApiResult
import com.ashotn.opencode.relay.api.transport.OpenCodeHttpTransport
import com.ashotn.opencode.relay.settings.OpenCodeSettings
import com.ashotn.opencode.relay.settings.OpenCodeServerAuth
import com.ashotn.opencode.relay.util.getStringOrNull
import com.ashotn.opencode.relay.util.getIntOrNull
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.charset.StandardCharsets

/**
 * A running OpenCode 2.x background service (started via `opencode service start`).
 *
 * [port] is parsed from [url] and is the port the plugin should connect to.
 */
data class OpenCodeBackgroundService(
    val url: String,
    val port: Int,
    val password: String,
)

/**
 * Detects a running OpenCode 2.x background service.
 *
 * The service registration lives in the OpenCode state directory
 * (`$XDG_STATE_HOME/opencode/service.json`, falling back to
 * `~/.local/state/opencode/service.json` — the same layout v2 uses on all platforms).
 * A registration is only accepted when an authenticated `GET /api/info` against it
 * succeeds and the reported pid matches the registered pid (v2's own discovery
 * applies the same stale-file guard).
 */
class OpenCodeBackgroundServiceDetector(
    stateFileProvider: () -> Path = { defaultServiceStateFile() },
) {
    private val stateFile = stateFileProvider()
    private val log = logger<OpenCodeBackgroundServiceDetector>()

    fun detect(): OpenCodeBackgroundService? {
        val registration = readRegistration() ?: return null
        val port = portOfUrl(registration.url) ?: run {
            log.debug("Background service registration has no usable port: ${registration.url}")
            return null
        }

        val credentials = OpenCodeServerAuth.BasicAuthCredentials(
            username = OpenCodeSettings.DEFAULT_SERVER_AUTH_USERNAME,
            password = registration.password,
        )
        val probeTransport = OpenCodeHttpTransport(
            defaultConnectTimeoutMs = 1_000,
            defaultReadTimeoutMs = 1_000,
            authorizationHeaderProvider = { credentials.authorizationHeader() },
        )
        val info = HealthApiClient(transport = probeTransport).fetchInfo(port)
        return when (info) {
            is ApiResult.Failure -> {
                log.debug("Background service at ${registration.url} failed health check: ${info.error}")
                null
            }

            is ApiResult.Success -> {
                if (info.value.pid != registration.pid) {
                    log.debug(
                        "Background service registration is stale (registered pid ${registration.pid}, " +
                                "server reports ${info.value.pid})",
                    )
                    return null
                }
                OpenCodeBackgroundService(
                    url = registration.url,
                    port = port,
                    password = registration.password,
                )
            }
        }
    }

    private data class Registration(
        val url: String,
        val pid: Int,
        val password: String,
    )

    private fun readRegistration(): Registration? {
        if (!Files.isRegularFile(stateFile) || !Files.isReadable(stateFile)) return null

        val root = try {
            JsonParser.parseString(Files.readString(stateFile, StandardCharsets.UTF_8))
        } catch (e: Exception) {
            log.debug("Failed to parse background service state file: $stateFile", e)
            return null
        }
        if (!root.isJsonObject) return null

        val state = root.asJsonObject
        val url = state.getStringOrNull("url")
        val pid = state.getIntOrNull("pid")
        val password = state.getStringOrNull("password")
        if (url.isNullOrBlank() || pid == null || password.isNullOrBlank()) {
            log.debug("Background service state file is missing url/pid/password: $stateFile")
            return null
        }
        return Registration(url = url, pid = pid, password = password)
    }

    private fun portOfUrl(url: String): Int? = try {
        URI(url).port.takeIf { it in 1..65535 }
    } catch (e: IllegalArgumentException) {
        null
    }

    companion object {
        /**
         * The service state file location used by OpenCode 2.x on all platforms:
         * `$XDG_STATE_HOME/opencode/service.json` or `~/.local/state/opencode/service.json`.
         */
        fun defaultServiceStateFile(): Path {
            val stateHome = System.getenv("XDG_STATE_HOME")?.takeIf { it.isNotBlank() }?.let(Paths::get)
                ?: Paths.get(System.getProperty("user.home"), ".local", "state")
            return stateHome.resolve("opencode").resolve("service.json")
        }
    }
}
