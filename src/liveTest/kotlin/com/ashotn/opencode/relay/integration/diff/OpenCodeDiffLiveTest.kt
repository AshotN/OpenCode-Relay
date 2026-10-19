package com.ashotn.opencode.relay.integration.diff

import com.ashotn.opencode.relay.integration.OpenCodeTestEnvironment
import com.ashotn.opencode.relay.integration.OpenCodeTestEnvironmentFactory
import com.ashotn.opencode.relay.integration.OpenCodeTestEventCollector
import com.ashotn.opencode.relay.integration.OpenCodeTestServer
import com.ashotn.opencode.relay.integration.OpenCodeTestVersions
import com.ashotn.opencode.relay.api.session.Session
import com.ashotn.opencode.relay.api.session.SessionApiClient
import com.ashotn.opencode.relay.api.session.SessionDiffFile
import com.ashotn.opencode.relay.api.session.SessionDiffSnapshot
import com.ashotn.opencode.relay.api.transport.ApiResult
import com.ashotn.opencode.relay.api.transport.OpenCodeHttpTransport
import com.ashotn.opencode.relay.ipc.SessionDiffStatus
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class OpenCodeDiffLiveTest(
    private val version: String,
) {

    private data class ChildDiffs(
        val fileToChildSessionId: Map<String, String>,
        val diffSummaryRoleByFile: Map<String, String>,
    )

    companion object {
        private const val MAX_SUBAGENT_ATTEMPTS = 3

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun versions(): List<Array<String>> = OpenCodeTestVersions.all().map { arrayOf(it) }
    }

    @Test
    fun `creates and updates hello txt through real opencode server`() {
        withLiveSession(version) { environment, server, sessionClient, events, sessionId ->
            val helloFile = environment.repoRoot.resolve("hello.txt")
            submitPromptUntil(
                sessionClient = sessionClient,
                events = events,
                port = server.port,
                sessionId = sessionId,
                text = """
                    Create a file named hello.txt in the project root.
                    The final file must be exactly:
                    ```text
                    Hello World
                    ```
                    Keep the trailing newline.
                    Do not modify any other files.
                """.trimIndent(),
                satisfied = { helloFile.exists() && helloFile.readText().trim() == "Hello World" },
            )
            assertFileTextAllowingMissingTrailingNewline(helloFile, "Hello World\n")

            val createDiffFile = requireSessionDiffFile(sessionClient, server.port, sessionId, "hello.txt")
            assertEquals(SessionDiffStatus.ADDED, createDiffFile.status)
            submitPromptUntil(
                sessionClient = sessionClient,
                events = events,
                port = server.port,
                sessionId = sessionId,
                text = """
                    Edit only `hello.txt`.
                    The file currently contains the line `Hello World`.
                    Replace that line with `Goodbye World`.
                    The final file must be exactly:
                    ```text
                    Goodbye World
                    ```
                    Keep the trailing newline.
                    Do not modify any other files.
                """.trimIndent(),
                satisfied = { helloFile.exists() && helloFile.readText().trim() == "Goodbye World" },
            )
            assertFileTextAllowingMissingTrailingNewline(helloFile, "Goodbye World\n")

            val updateDiffFile = requireSessionDiffFile(sessionClient, server.port, sessionId, "hello.txt")
            assertEquals(SessionDiffStatus.ADDED, updateDiffFile.status)
        }
    }

    @Test
    fun `edits pre existing file and preserves diff semantics`() {
        withLiveSession(version) { environment, server, sessionClient, events, sessionId ->
            val noteFile = environment.repoRoot.resolve("note.txt")
            noteFile.writeText("Alpha\nBravo\nCharlie\n")

            submitPromptUntil(
                sessionClient = sessionClient,
                events = events,
                port = server.port,
                sessionId = sessionId,
                text = """
                    Edit only `note.txt`.
                    Make exactly one change: replace the second line `Bravo` with `Beta`.
                    The final file must be exactly:
                    ```text
                    Alpha
                    Beta
                    Charlie
                    ```
                    Keep the trailing newline.
                    Do not add line numbers, duplicate content, or modify any other files.
                """.trimIndent(),
                satisfied = { fileContentMatches(noteFile, "Alpha\nBeta\nCharlie\n") },
            )

            assertFileText(noteFile, "Alpha\nBeta\nCharlie\n")

            val diffFile = requireSessionDiffFile(sessionClient, server.port, sessionId, "note.txt")
            assertEquals(SessionDiffStatus.MODIFIED, diffFile.status)
            assertEquals("Alpha\nBravo\nCharlie\n", normalizeNewlinesOnly(diffFile.before))
            assertEquals("Alpha\nBeta\nCharlie\n", normalizeNewlinesOnly(diffFile.after))
        }
    }

    @Test
    fun `sub-agent edits are attributed to child session diffs`() {
        withLiveSession(version, allowTask = true) { environment, server, sessionClient, events, sessionId ->
            val expectedFiles = linkedMapOf(
                "live-subagents/alpha.txt" to "alpha from sub-agent\n",
                "live-subagents/bravo.txt" to "bravo from sub-agent\n",
                "live-subagents/charlie.txt" to "charlie from sub-agent\n",
            )

            // Small local models can drop a sub-agent task's file; resubmit the
            // prompt while any expected file is missing or wrong (bounded), so the
            // test exercises the server integration rather than model consistency.
            // Three sequential sub-agent turns on a local model can exceed two
            // minutes (observed ~85s best case); leave generous headroom.
            submitPromptUntil(
                sessionClient = sessionClient,
                events = events,
                port = server.port,
                sessionId = sessionId,
                turnTimeoutMs = 300_000,
                text = """
                    Use the built-in General subagent for three separate parallel tasks.
                    Task A: create only `live-subagents/alpha.txt` with exactly `alpha from sub-agent` and a trailing newline.
                    Task B: create only `live-subagents/bravo.txt` with exactly `bravo from sub-agent` and a trailing newline.
                    Task C: create only `live-subagents/charlie.txt` with exactly `charlie from sub-agent` and a trailing newline.
                    Do not edit README.md or any other files.
                    After all three subagents finish, reply with a short summary.
                """.trimIndent(),
                satisfied = {
                    expectedFiles.all { (relativePath, expectedContent) ->
                        fileContentMatches(environment.repoRoot.resolve(relativePath), expectedContent)
                    }
                },
            )

            expectedFiles.forEach { (relativePath, expectedContent) ->
                assertFileText(environment.repoRoot.resolve(relativePath), expectedContent)
            }

            val childDiffs = awaitSubAgentDiffs(
                sessionClient = sessionClient,
                port = server.port,
                rootSessionId = sessionId,
                repoRoot = environment.repoRoot,
                expectedRelativePaths = expectedFiles.keys,
            )
            assertEquals(
                expectedFiles.keys,
                childDiffs.fileToChildSessionId.keys,
                "expected files should be attributed to child-session diffs, not just root output",
            )
            assertTrue(
                childDiffs.fileToChildSessionId.values.toSet().isNotEmpty(),
                "expected at least one child session to own the edits, got ${childDiffs.fileToChildSessionId}",
            )
            assertEquals(
                expectedFiles.keys.associateWith { "assistant" },
                childDiffs.diffSummaryRoleByFile,
                "child diff summaries should be carried by assistant message snapshots, matching the parser filter",
            )
        }
    }

    private fun withLiveSession(
        version: String,
        allowTask: Boolean = false,
        block: (
            environment: OpenCodeTestEnvironment,
            server: OpenCodeTestServer,
            sessionClient: SessionApiClient,
            events: OpenCodeTestEventCollector,
            sessionId: String,
        ) -> Unit,
    ) {
        OpenCodeTestEnvironmentFactory.create(version, allowTask = allowTask).use { environment ->
            val server = environment.startServer()
            val sessionClient = SessionApiClient(
                OpenCodeHttpTransport(authorizationHeaderProvider = environment::authorizationHeader),
            )
            OpenCodeTestEventCollector(
                port = server.port,
                directory = environment.repoRoot.toString(),
                authorizationHeaderProvider = environment::authorizationHeader,
            ).use { events ->
                try {
                    events.awaitConnected()
                    val session = assertIs<ApiResult.Success<SessionApiClient.CreatedSession>>(
                        sessionClient.createSession(
                            port = server.port,
                            permissions = environment.sessionPermissions,
                            model = environment.testModel,
                        )
                    ).value
                    block(environment, server, sessionClient, events, session.sessionId)
                } catch (t: Throwable) {
                    t.addSuppressed(
                        IllegalStateException(
                            buildString {
                                appendLine("OpenCode diff live test failed for version $version")
                                appendLine(environment.diagnosticsSummary())
                                appendLine("recentEvents=${events.recentEventSummary()}")
                            },
                        ),
                    )
                    throw t
                }
            }
        }
    }

    private fun submitPromptAndAwaitTurn(
        sessionClient: SessionApiClient,
        events: OpenCodeTestEventCollector,
        port: Int,
        sessionId: String,
        // Local models can take well over 30s for a tool-using turn; the suite
        // should wait out slowness rather than time out on it.
        turnTimeoutMs: Long = 120_000,
        text: String,
    ) {
        val nextIdleCount = events.sessionIdleCount(sessionId) + 1
        val nextExecutionFinishedCount = events.executionFinishedCount(sessionId) + 1
        assertIs<ApiResult.Success<Unit>>(
            sessionClient.promptText(
                port = port,
                sessionId = sessionId,
                text = text,
            ),
        )
        events.awaitExecutionFinished(sessionId, nextExecutionFinishedCount, timeoutMs = turnTimeoutMs)
        events.awaitIdleStatus(sessionId, nextIdleCount, timeoutMs = turnTimeoutMs)
    }

    /**
     * LLM-driven turns on small local models can complete without performing
     * the requested work. Resubmit the prompt — with an explicit retry note so
     * the model does not treat the repeated text as already done — until
     * [satisfied] holds, bounded by [maxAttempts]. The latest-turn diff then
     * reflects the turn that actually did the work.
     */
    private fun submitPromptUntil(
        sessionClient: SessionApiClient,
        events: OpenCodeTestEventCollector,
        port: Int,
        sessionId: String,
        text: String,
        turnTimeoutMs: Long = 120_000,
        maxAttempts: Int = MAX_SUBAGENT_ATTEMPTS,
        satisfied: () -> Boolean,
    ) {
        var prompt = text
        var attempts = 0
        while (true) {
            attempts++
            submitPromptAndAwaitTurn(
                sessionClient = sessionClient,
                events = events,
                port = port,
                sessionId = sessionId,
                turnTimeoutMs = turnTimeoutMs,
                text = prompt,
            )
            if (satisfied() || attempts >= maxAttempts) return
            prompt = "$text\n\nIMPORTANT: The requested change was not made in your last reply. Perform the full task again now."
        }
    }

    private fun requireSessionDiffFile(
        sessionClient: SessionApiClient,
        port: Int,
        sessionId: String,
        relativePath: String,
    ): SessionDiffFile {
        val diff = when (val result = sessionClient.fetchSessionDiffSnapshot(port, sessionId)) {
            is ApiResult.Success -> result.value
            is ApiResult.Failure -> error("diff fetch for $sessionId failed: ${result.error}")
        }
        val diffFile = diff.files.firstOrNull { it.file == relativePath }
        assertTrue(diffFile != null, "session diff should include $relativePath")
        return diffFile
    }

    private fun awaitSubAgentDiffs(
        sessionClient: SessionApiClient,
        port: Int,
        rootSessionId: String,
        repoRoot: Path,
        expectedRelativePaths: Set<String>,
    ): ChildDiffs {
        val deadline = System.currentTimeMillis() + 30_000
        var lastSessions: List<Session> = emptyList()
        var lastDiffsBySessionId: Map<String, SessionDiffSnapshot> = emptyMap()

        while (System.currentTimeMillis() < deadline) {
            val sessions = when (val hierarchy = sessionClient.fetchSessionHierarchy(port)) {
                is ApiResult.Success -> hierarchy.value
                is ApiResult.Failure -> emptyList()
            }
            lastSessions = sessions
            val children = sessions.filter { it.parentID == rootSessionId }
            val diffsBySessionId = children.mapNotNull { child ->
                when (val diff = sessionClient.fetchSessionDiffSnapshot(port, child.id)) {
                    is ApiResult.Success -> child.id to diff.value
                    is ApiResult.Failure -> null
                }
            }.toMap()
            lastDiffsBySessionId = diffsBySessionId
            val diffSummaryRoleByFile = children
                .flatMap { child -> fetchDiffSummaryRolesByFile(sessionClient, port, child.id, repoRoot).entries }
                .filter { (file, _) -> file in expectedRelativePaths }
                .associate { (file, role) -> file to role }
            val fileToChildSessionId = diffsBySessionId
                .flatMap { (childSessionId, diff) ->
                    diff.files.map { normalizeDiffPath(repoRoot, it.file) to childSessionId }
                }
                .filter { (file, _) -> file in expectedRelativePaths }
                .associate { (file, childSessionId) -> file to childSessionId }

            val filesSeen = diffsBySessionId.values
                .flatMap { it.files }
                .map { normalizeDiffPath(repoRoot, it.file) }
                .filter { it in expectedRelativePaths }
                .toSet()
            if (
                children.size >= expectedRelativePaths.size &&
                filesSeen == expectedRelativePaths &&
                fileToChildSessionId.keys == expectedRelativePaths &&
                diffSummaryRoleByFile.keys == expectedRelativePaths
            ) {
                return ChildDiffs(
                    fileToChildSessionId = fileToChildSessionId,
                    diffSummaryRoleByFile = diffSummaryRoleByFile,
                )
            }

            Thread.sleep(250)
        }

        throw AssertionError(
            "Timed out waiting for sub-agent diffs. " +
                    "expected=$expectedRelativePaths " +
                    "sessions=${lastSessions.map { it.id to it.parentID }} " +
                    "diffFiles=${lastDiffsBySessionId.mapValues { (_, diff) -> diff.files.map { it.file } }}",
        )
    }

    private fun fetchDiffSummaryRolesByFile(
        sessionClient: SessionApiClient,
        port: Int,
        sessionId: String,
        repoRoot: Path,
    ): Map<String, String> {
        val summaries = when (val result = sessionClient.fetchSessionMessageDiffSummaries(port, sessionId)) {
            is ApiResult.Success -> result.value
            is ApiResult.Failure -> return emptyMap()
        }
        return summaries.flatMap { summary ->
            val role = summary.role ?: return@flatMap emptyList()
            summary.files.map { file -> normalizeDiffPath(repoRoot, file) to role }
        }.toMap()
    }

    private fun normalizeDiffPath(repoRoot: Path, file: String): String {
        val root = repoRoot.toAbsolutePath().normalize()
        return runCatching {
            val path = Path.of(file)
            val relative = if (path.isAbsolute) root.relativize(path.toAbsolutePath().normalize()).toString() else file
            relative.replace('\\', '/')
        }.getOrDefault(file.replace('\\', '/'))
    }

    private fun assertFileText(path: Path, expected: String) {
        val actual = if (path.exists()) normalizeNewlinesOnly(path.readText()) else "<missing file>"
        assertEquals(normalizeNewlinesOnly(expected), actual, "Unexpected file content at $path")
    }

    /**
     * Small local models are inconsistent about trailing newlines (observed on
     * qwen3.8:27b: the line is written but the final newline is dropped). Accept
     * the expected content with or without its final newline; the diff assertions
     * are what pin down the semantics under test.
     */
    private fun assertFileTextAllowingMissingTrailingNewline(path: Path, expected: String) {
        val actual = if (path.exists()) normalizeNewlinesOnly(path.readText()) else "<missing file>"
        val expectedNormalized = normalizeNewlinesOnly(expected)
        val accepted = actual == expectedNormalized || actual == expectedNormalized.trimEnd('\n')
        assertTrue(
            accepted,
            "Unexpected file content at $path: <$actual> (expected <$expectedNormalized>, trailing newline optional)",
        )
    }

    private fun fileContentMatches(path: Path, expected: String): Boolean =
        path.exists() && normalizeNewlinesOnly(path.readText()) == normalizeNewlinesOnly(expected)

    private fun normalizeNewlinesOnly(content: String): String = content.replace("\r\n", "\n")
}
