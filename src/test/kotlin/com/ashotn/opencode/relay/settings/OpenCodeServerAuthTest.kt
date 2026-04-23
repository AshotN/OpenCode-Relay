package com.ashotn.opencode.relay.settings

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertFailsWith

class OpenCodeServerAuthTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            val settings = OpenCodeSettings.getInstance(project)
            settings.serverAuthUsername = OpenCodeSettings.DEFAULT_SERVER_AUTH_USERNAME
            settings.protectPluginLaunchedServerWithAuth = false
            OpenCodeServerAuth.getInstance(project).setPassword("")
        } finally {
            super.tearDown()
        }
    }

    fun testConnectionAuthorizationHeaderUsesConfiguredCredentials() {
        val settings = OpenCodeSettings.getInstance(project)
        val auth = OpenCodeServerAuth.getInstance(project)

        settings.serverAuthUsername = "alice"
        auth.setPassword("secret")

        assertEquals("Basic YWxpY2U6c2VjcmV0", auth.connectionAuthorizationHeader())
        assertEquals(
            mapOf(
                "OPENCODE_SERVER_USERNAME" to "alice",
                "OPENCODE_SERVER_PASSWORD" to "secret",
            ),
            auth.connectionEnvironmentVariables(),
        )
    }

    fun testServerLaunchEnvironmentVariablesRespectProtectionToggle() {
        val settings = OpenCodeSettings.getInstance(project)
        val auth = OpenCodeServerAuth.getInstance(project)

        settings.serverAuthUsername = "alice"
        auth.setPassword("secret")

        settings.protectPluginLaunchedServerWithAuth = false
        assertTrue(auth.serverLaunchEnvironmentVariables().isEmpty())

        settings.protectPluginLaunchedServerWithAuth = true
        assertEquals(
            mapOf(
                "OPENCODE_SERVER_USERNAME" to "alice",
                "OPENCODE_SERVER_PASSWORD" to "secret",
            ),
            auth.serverLaunchEnvironmentVariables(),
        )
    }

    fun testServerLaunchEnvironmentVariablesFailWhenProtectionEnabledAndPasswordMissing() {
        val settings = OpenCodeSettings.getInstance(project)
        val auth = OpenCodeServerAuth.getInstance(project)

        settings.serverAuthUsername = "alice"
        auth.setPassword("")
        settings.protectPluginLaunchedServerWithAuth = true

        assertFailsWith<OpenCodeServerAuth.MissingServerLaunchAuthCredentialsException> {
            auth.serverLaunchEnvironmentVariables()
        }
    }
}
