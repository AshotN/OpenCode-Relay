package com.ashotn.opencode.companion.api

import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.name
import kotlin.test.assertTrue

class EndpointGuardrailTest {

    @Test
    fun `forbids domain endpoint factories under transport package`() {
        val transportRoot = Paths.get("src/main/kotlin/com/ashotn/opencode/companion/api/transport")
        val violations = mutableListOf<String>()

        Files.walk(transportRoot).use { pathStream ->
            pathStream
                .filter { Files.isRegularFile(it) }
                .filter { it.extension == "kt" }
                .forEach { file ->
                    if (file.name.endsWith("Endpoints.kt")) {
                        violations.add(transportRoot.relativize(file).invariantSeparatorsPathString)
                    }
                }
        }

        assertTrue(
            violations.isEmpty(),
            "Domain endpoint factories must live next to their API clients, not in api.transport. Violations:\n${violations.joinToString("\n")}",
        )
    }
}
