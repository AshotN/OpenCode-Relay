package com.ashotn.opencode.relay

import com.ashotn.opencode.relay.core.OpenCodeCoreService
import com.ashotn.opencode.relay.settings.OpenCodeSettings
import com.ashotn.opencode.relay.tui.OpenCodeTuiClient
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledExecutorService

class ServerManagerTest : BasePlatformTestCase() {

    fun testBuildServeArgumentsIncludesHostnameAndCorsOrigins() {
        val settings = OpenCodeSettings.getInstance(project)
        settings.serverHostname = "0.0.0.0"
        settings.serverMdnsEnabled = true
        settings.serverMdnsDomain = "relay.local"
        settings.serverCorsOrigins = "http://localhost:5173\nhttps://app.example.com"

        val manager = ServerManager(project) { }
        try {
            assertEquals(
                listOf(
                    "serve",
                    "--port",
                    "4096",
                    "--hostname",
                    "0.0.0.0",
                    "--mdns",
                    "--mdns-domain",
                    "relay.local",
                    "--cors",
                    "http://localhost:5173",
                    "--cors",
                    "https://app.example.com",
                ),
                manager.buildServeArguments(settings, 4096),
            )
        } finally {
            manager.dispose()
        }
    }

    fun testBuildServeArgumentsOmitsBlankHostnameAndCorsOrigins() {
        val settings = OpenCodeSettings.getInstance(project)
        settings.serverHostname = "   "
        settings.serverMdnsEnabled = false
        settings.serverMdnsDomain = "   "
        settings.serverCorsOrigins = "\n  \n"

        val manager = ServerManager(project) { }
        try {
            assertEquals(
                listOf("serve", "--port", "4096"),
                manager.buildServeArguments(settings, 4096),
            )
        } finally {
            manager.dispose()
        }
    }

    fun `test disposed server manager ignores late ui callbacks`() {
        val observedStates = mutableListOf<ServerState>()
        val manager = ServerManager(project) { observedStates += it }

        manager.dispose()
        manager.reportAuthenticationRequired()
        UIUtil.dispatchAllInvocationEvents()

        assertEmpty(observedStates)
        assertEquals(ServerState.UNKNOWN, manager.serverState)
    }

    fun `test disposed plugin ignores late connection sync requests`() {
        val plugin = OpenCodePlugin(project)
        val coreService = OpenCodeCoreService.getInstance(project)
        val tuiClient = OpenCodeTuiClient.getInstance(project)
        val scheduleConnectionSync = OpenCodePlugin::class.java.getDeclaredMethod(
            "scheduleConnectionSync",
            ServerState::class.java,
        )
        scheduleConnectionSync.isAccessible = true

        plugin.dispose()

        ApplicationManager.getApplication().invokeAndWait {
            scheduleConnectionSync.invoke(plugin, ServerState.STOPPED)
            scheduleConnectionSync.invoke(plugin, ServerState.READY)
        }

        assertEquals(0, readIntField(coreService, "port"))
        assertEquals(0, readIntField(tuiClient, "port"))
    }

    fun `test checkPort ignores scheduler rejections during shutdown race`() {
        val observedStates = mutableListOf<ServerState>()
        val manager = ServerManager(project) { observedStates += it }
        try {
            val schedulerField = ServerManager::class.java.getDeclaredField("scheduler")
            schedulerField.isAccessible = true
            val scheduler = schedulerField.get(manager) as ScheduledExecutorService

            scheduler.shutdownNow()

            manager.checkPort(4096)

            assertEmpty(observedStates)
            assertEquals(ServerState.UNKNOWN, manager.serverState)
        } finally {
            manager.dispose()
        }
    }

    fun `test startPolling ignores scheduler rejections during shutdown race`() {
        val observedStates = mutableListOf<ServerState>()
        val manager = ServerManager(project) { observedStates += it }
        try {
            val schedulerField = ServerManager::class.java.getDeclaredField("scheduler")
            schedulerField.isAccessible = true
            val scheduler = schedulerField.get(manager) as ScheduledExecutorService

            scheduler.shutdownNow()

            manager.startPolling(4096)

            assertEmpty(observedStates)
            assertEquals(ServerState.UNKNOWN, manager.serverState)
            assertNull(readField(manager, "portPollFuture"))
        } finally {
            manager.dispose()
        }
    }

    fun `test resetConnection clears override state when sync task is rejected`() {
        val plugin = OpenCodePlugin(project)
        val coreService = OpenCodeCoreService.getInstance(project)
        val tuiClient = OpenCodeTuiClient.getInstance(project)
        try {
            val executorField = OpenCodePlugin::class.java.getDeclaredField("connectionSyncExecutor")
            executorField.isAccessible = true
            val executor = executorField.get(plugin) as ExecutorService

            executor.shutdownNow()

            plugin.resetConnection()

            assertEquals(ServerState.UNKNOWN, plugin.serverState)
            assertEquals(0, readIntField(coreService, "port"))
            assertEquals(0, readIntField(tuiClient, "port"))
        } finally {
            plugin.dispose()
        }
    }

    private fun readIntField(instance: Any, fieldName: String): Int = readField(instance, fieldName) as Int

    private fun readField(instance: Any, fieldName: String): Any? {
        val field = instance.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(instance)
    }
}
