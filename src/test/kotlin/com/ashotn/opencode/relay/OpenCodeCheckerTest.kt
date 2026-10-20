package com.ashotn.opencode.relay

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenCodeCheckerTest {

    @Test
    fun `parses v2 version output with opencode prefix`() {
        val version = parseOpenCodeVersion("opencode v2.0.32")

        assertEquals(2, version?.major)
        assertEquals("2.0.32", version?.semVer)
    }

    @Test
    fun `parses bare semver version output`() {
        val version = parseOpenCodeVersion("2.1.0")

        assertEquals(2, version?.major)
        assertEquals("2.1.0", version?.semVer)
    }

    @Test
    fun `parses v1 version output`() {
        val version = parseOpenCodeVersion("1.4.0")

        assertEquals(1, version?.major)
        assertEquals("1.4.0", version?.semVer)
    }

    @Test
    fun `parses version with pre-release suffix`() {
        val version = parseOpenCodeVersion("opencode v2.1.0-beta.1")

        assertEquals(2, version?.major)
        assertEquals("2.1.0-beta.1", version?.semVer)
    }

    @Test
    fun `returns null for unparsable version output`() {
        assertNull(parseOpenCodeVersion("opencode"))
        assertNull(parseOpenCodeVersion("unknown"))
        assertNull(parseOpenCodeVersion(""))
    }

    @Test
    fun `help output with bare serve subcommand passes the gate`() {
        val help = """
            SUBCOMMANDS
              run                Run OpenCode with a message
              session            Manage sessions
              service            Manage the background server
              serve              Start the v2 API and web server
        """.trimIndent()

        assertTrue(hasRequiredHelpCommands(help))
    }

    @Test
    fun `help output with v1 style command listing passes the gate`() {
        val help = """
            COMMANDS
              opencode serve       Start the server
              opencode attach      Attach to a running server
        """.trimIndent()

        assertTrue(hasRequiredHelpCommands(help))
    }

    @Test
    fun `help output without serve fails the gate`() {
        val help = """
            SUBCOMMANDS
              run                Run OpenCode with a message
              service            Manage the background server
        """.trimIndent()

        // "service" contains "serve" but not as a standalone word.
        assertFalse(hasRequiredHelpCommands(help))
    }
}
