package com.ashotn.opencode.relay.ipc

import com.google.gson.JsonParser
import org.junit.Test
import kotlin.test.assertEquals

class SnapshotDiffTextParserTest {

    @Test
    fun `parses legacy before after snapshot diff`() {
        val obj = JsonParser.parseString(
            """
            {
              "file": "a.txt",
              "before": "old",
              "after": "new"
            }
            """.trimIndent()
        ).asJsonObject

        val diffText = SnapshotDiffTextParser.parse(obj)

        assertEquals("old", diffText.before)
        assertEquals("new", diffText.after)
    }

    @Test
    fun `reconstructs snapshot text from patch only diff`() {
        val obj = JsonParser.parseString(
            """
            {
              "file": "a.txt",
              "patch": "Index: a.txt\n===================================================================\n--- a.txt\t\n+++ a.txt\t\n@@ -1 +1 @@\n-old\n+new\n"
            }
            """.trimIndent()
        ).asJsonObject

        val diffText = SnapshotDiffTextParser.parse(obj)

        assertEquals("old\n", diffText.before)
        assertEquals("new\n", diffText.after)
    }

    @Test
    fun `preserves no newline at end of file marker`() {
        val obj = JsonParser.parseString(
            """
            {
              "file": "a.txt",
              "patch": "Index: a.txt\n===================================================================\n--- a.txt\t\n+++ a.txt\t\n@@ -1 +1 @@\n-old\n\\ No newline at end of file\n+new\n\\ No newline at end of file\n"
            }
            """.trimIndent()
        ).asJsonObject

        val diffText = SnapshotDiffTextParser.parse(obj)

        assertEquals("old", diffText.before)
        assertEquals("new", diffText.after)
    }
}
