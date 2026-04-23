package com.ashotn.opencode.relay

import com.ashotn.opencode.relay.settings.OpenCodeSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase

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
}
