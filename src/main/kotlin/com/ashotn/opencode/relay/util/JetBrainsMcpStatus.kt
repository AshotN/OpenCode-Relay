package com.ashotn.opencode.relay.util

sealed class JetBrainsMcpStatus {
    data object Unknown : JetBrainsMcpStatus()

    data class Available(
        val enabled: Boolean,
        val port: Int?,
        val sseUrl: String?,
        val streamUrl: String?,
    ) : JetBrainsMcpStatus()
}

object JetBrainsMcpDetector {
    private const val MCP_SETTINGS_CLASS = "com.intellij.mcpserver.settings.McpServerSettings"
    private const val MCP_SERVICE_CLASS = "com.intellij.mcpserver.impl.McpServerService"

    fun status(): JetBrainsMcpStatus {
        return runCatching {
            val classLoader = JetBrainsMcpDetector::class.java.classLoader
            val settingsClass = Class.forName(MCP_SETTINGS_CLASS, false, classLoader)
            val settings = settingsClass.getMethod("getInstance").invoke(null)
            val state = settingsClass.getMethod("getState").invoke(settings)
            val enabled = state.javaClass.getMethod("getEnableMcpServer").invoke(state) as Boolean
            val port = state.javaClass.getMethod("getMcpServerPort").invoke(state) as Int
            if (!enabled) {
                return@runCatching JetBrainsMcpStatus.Available(
                    enabled = false,
                    port = port,
                    sseUrl = null,
                    streamUrl = null,
                )
            }

            val serviceClass = Class.forName(MCP_SERVICE_CLASS, false, classLoader)
            val companion = serviceClass.getField("Companion").get(null)
            val service = companion.javaClass.getMethod("getInstance").invoke(companion)

            JetBrainsMcpStatus.Available(
                enabled = true,
                port = port,
                sseUrl = serviceClass.getMethod("getServerSseUrl").invoke(service) as? String,
                streamUrl = serviceClass.getMethod("getServerStreamUrl").invoke(service) as? String,
            )
        }.getOrDefault(JetBrainsMcpStatus.Unknown)
    }
}
