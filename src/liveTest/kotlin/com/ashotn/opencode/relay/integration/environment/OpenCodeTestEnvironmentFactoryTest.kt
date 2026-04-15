package com.ashotn.opencode.relay.integration.environment

import com.ashotn.opencode.relay.integration.OpenCodeTestEnvironmentFactory
import com.ashotn.opencode.relay.integration.OpenCodeTestVersions
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.io.path.exists
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class OpenCodeTestEnvironmentFactoryTest(
    private val version: String,
) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun versions(): List<Array<String>> = OpenCodeTestVersions.all().map { arrayOf(it) }
    }

    @Test
    fun `creates isolated scenario environment for cached version`() {
        val environment = OpenCodeTestEnvironmentFactory.create(version)

        try {
            assertEquals(version, environment.version)
            assertTrue(environment.opencodeBin.exists())
            assertTrue(environment.scenarioRoot.exists())
            assertTrue(environment.repoRoot.exists())
            assertTrue(environment.artifactsRoot.exists())
            assertTrue(environment.authPath.exists())
            assertTrue(environment.repoRoot.resolve(".git").exists())
            assertTrue(environment.repoRoot.resolve("README.md").exists())

            assertEquals(environment.scenarioRoot.resolve("home").toString(), environment.env.getValue("HOME"))
            assertEquals(
                environment.scenarioRoot.resolve("xdg-config").toString(),
                environment.env.getValue("XDG_CONFIG_HOME")
            )
            assertEquals(
                environment.scenarioRoot.resolve("xdg-data").toString(),
                environment.env.getValue("XDG_DATA_HOME")
            )
            assertEquals(
                environment.scenarioRoot.resolve("xdg-cache").toString(),
                environment.env.getValue("XDG_CACHE_HOME")
            )
            assertEquals(
                environment.scenarioRoot.resolve("xdg-state").toString(),
                environment.env.getValue("XDG_STATE_HOME")
            )
            assertEquals(
                environment.scenarioRoot.resolve("home").toString(),
                environment.env.getValue("OPENCODE_TEST_HOME")
            )
            assertEquals("true", environment.env.getValue("OPENCODE_DISABLE_AUTOUPDATE"))
            assertEquals("true", environment.env.getValue("OPENCODE_DISABLE_LSP_DOWNLOAD"))
        } finally {
            val scenarioRoot = environment.scenarioRoot
            environment.close()
            assertFalse(scenarioRoot.exists())
        }
    }

    @Test
    fun `starts and stops real opencode server for isolated scenario`() {
        val environment = OpenCodeTestEnvironmentFactory.create(version)

        try {
            val server = environment.startServer()

            assertTrue(server.process.isAlive)
            assertTrue(server.port > 0)
            assertTrue(server.stdoutLog.exists())
            assertTrue(server.stderrLog.exists())

            server.close()

            assertFalse(server.process.isAlive)
        } finally {
            environment.close()
        }
    }
}
