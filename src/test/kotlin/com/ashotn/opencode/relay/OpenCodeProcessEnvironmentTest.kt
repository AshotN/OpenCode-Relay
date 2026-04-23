package com.ashotn.opencode.relay

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenCodeProcessEnvironmentTest {

    @Test
    fun `configure applies custom environment variables`() {
        val processBuilder = ProcessBuilder("opencode")

        OpenCodeProcessEnvironment.configure(
            processBuilder,
            "/tmp/opencode",
            mapOf(
                "FOO" to "bar",
                "BAR" to "  spaced value  ",
            ),
        )

        assertEquals("bar", processBuilder.environment()["FOO"])
        assertEquals("  spaced value  ", processBuilder.environment()["BAR"])
    }

    @Test
    fun `configure ignores blank names and still prepends nvm path`() {
        val processBuilder = ProcessBuilder("opencode")
        processBuilder.environment()["PATH"] = "/usr/bin"

        OpenCodeProcessEnvironment.configure(
            processBuilder,
            "/Users/test/.nvm/versions/node/v20.0.0/bin/opencode",
            mapOf(
                " PATH " to "/custom/bin",
                " " to "ignored",
                "FOO" to "bar",
            ),
        )

        val path = processBuilder.environment()["PATH"] ?: error("PATH was not set")
        val nvmBinDirectory = "/Users/test/.nvm/versions/node/v20.0.0/bin"

        assertTrue(path.startsWith(nvmBinDirectory + File.pathSeparator))
        assertTrue(path.contains("/custom/bin"))
        assertEquals("bar", processBuilder.environment()["FOO"])
        assertFalse(processBuilder.environment().containsKey(" "))
    }

    @Test
    fun `terminalCommand applies custom environment variables`() {
        val command = OpenCodeProcessEnvironment.terminalCommand(
            listOf("/tmp/opencode", "attach", "http://127.0.0.1:4096"),
            mapOf(
                "FOO" to "bar",
                "BAR" to "  spaced value  ",
            ),
        )

        assertEquals("/usr/bin/env", command.first())
        assertTrue(command.contains("FOO=bar"))
        assertTrue(command.contains("BAR=  spaced value  "))
        assertEquals(listOf("/tmp/opencode", "attach", "http://127.0.0.1:4096"), command.takeLast(3))
    }

    @Test
    fun `terminalCommand merges environment variables with nvm path`() {
        val command = OpenCodeProcessEnvironment.terminalCommand(
            listOf("/Users/test/.nvm/versions/node/v20.0.0/bin/opencode", "attach", "http://127.0.0.1:4096"),
            mapOf("PATH" to "/custom/bin"),
        )

        val pathEntry = command.firstOrNull { it.startsWith("PATH=") } ?: error("PATH entry not found")
        assertTrue(pathEntry.startsWith("PATH=/Users/test/.nvm/versions/node/v20.0.0/bin${File.pathSeparator}"))
        assertTrue(pathEntry.contains("/custom/bin"))
    }
}
