package com.ashotn.opencode.relay.settings

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.Base64
import kotlin.test.assertTrue

class OpenCodeServerAuthTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            OpenCodeServerAuth.getInstance(project).setPassword("")
        } finally {
            super.tearDown()
        }
    }

    fun testConnectionAuthorizationHeaderUsesFixedOpenCodeUsername() {
        val auth = OpenCodeServerAuth.getInstance(project)

        // OpenCode 2.x always authenticates as the fixed "opencode" user.
        auth.setPassword("secret")

        val expectedToken = Base64.getEncoder()
            .encodeToString("opencode:secret".toByteArray())
        assertEquals("Basic $expectedToken", auth.connectionAuthorizationHeader())
        assertEquals(
            mapOf("OPENCODE_PASSWORD" to "secret"),
            auth.connectionEnvironmentVariables(),
        )
    }

    fun testConnectionAuthorizationHeaderIsNullWithoutPassword() {
        val auth = OpenCodeServerAuth.getInstance(project)
        auth.setPassword("")

        assertNull(auth.connectionAuthorizationHeader())
        assertTrue(auth.connectionEnvironmentVariables().isEmpty())
    }

    fun testLaunchEnvironmentVariablesReuseStoredPassword() {
        val auth = OpenCodeServerAuth.getInstance(project)
        auth.setPassword("stored")

        assertEquals(mapOf("OPENCODE_PASSWORD" to "stored"), auth.launchEnvironmentVariables())
        assertEquals("stored", auth.password())
    }

    fun testLaunchEnvironmentVariablesGeneratePasswordWhenNoneStored() {
        val auth = OpenCodeServerAuth.getInstance(project)
        auth.setPassword("")

        val environmentVariables = auth.launchEnvironmentVariables()

        val generated = environmentVariables.getValue("OPENCODE_PASSWORD")
        assertTrue(generated.length >= 32, "generated password should be reasonably long: $generated")
        assertEquals(generated, auth.password())
    }

    fun testSetPasswordTransientlyUpdatesCache() {
        val auth = OpenCodeServerAuth.getInstance(project)
        auth.setPassword("")

        auth.setPasswordTransiently("transient")

        assertEquals("transient", auth.password())
        assertEquals(
            "Basic " + Base64.getEncoder().encodeToString("opencode:transient".toByteArray()),
            auth.connectionAuthorizationHeader(),
        )
    }
}
