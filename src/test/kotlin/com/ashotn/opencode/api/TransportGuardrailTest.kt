package com.ashotn.opencode.api

import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.test.assertTrue

class TransportGuardrailTest {

    @Test
    fun `forbids direct transport usage outside api package`() {
        val sourceRoot = Paths.get("src/main/kotlin/com/ashotn/opencode")
        val forbiddenPatterns = listOf(
            "HttpURLConnection",
            ".openConnection(",
            "requestMethod =",
            "java.net.http.HttpClient",
            "OkHttpClient",
            "io.ktor.client",
        )
        val violations = mutableListOf<String>()

        Files.walk(sourceRoot).use { pathStream ->
            pathStream
                .filter { Files.isRegularFile(it) }
                .filter { it.extension == "kt" }
                .forEach { file ->
                    val relativePath = sourceRoot.relativize(file).invariantSeparatorsPathString
                    if (relativePath.startsWith("api/")) {
                        return@forEach
                    }

                    val lines = Files.readAllLines(file)
                    lines.forEachIndexed { index, line ->
                        forbiddenPatterns.forEach { pattern ->
                            if (line.contains(pattern)) {
                                violations.add("$relativePath:${index + 1} contains '$pattern'")
                            }
                        }
                    }
                }
        }

        assertTrue(
            violations.isEmpty(),
            "Transport usage is only allowed under com.ashotn.opencode.api. Violations:\n${violations.joinToString("\n")}",
        )
    }
}
