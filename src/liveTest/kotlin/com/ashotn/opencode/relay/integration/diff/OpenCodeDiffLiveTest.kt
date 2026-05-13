package com.ashotn.opencode.relay.integration.diff

import com.ashotn.opencode.relay.integration.OpenCodeTestEnvironment
import com.ashotn.opencode.relay.integration.OpenCodeTestEnvironmentFactory
import com.ashotn.opencode.relay.integration.OpenCodeTestEventCollector
import com.ashotn.opencode.relay.integration.OpenCodeTestServer
import com.ashotn.opencode.relay.integration.OpenCodeTestVersions
import com.ashotn.opencode.relay.api.session.SessionApiClient
import com.ashotn.opencode.relay.api.transport.ApiResult
import com.ashotn.opencode.relay.core.DiffPipelineHarness
import com.ashotn.opencode.relay.core.normalizeTestContent
import com.ashotn.opencode.relay.core.DiffHunk
import com.ashotn.opencode.relay.ipc.OpenCodeEvent
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

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun versions(): List<Array<String>> = OpenCodeTestVersions.all().map { arrayOf(it) }
    }

    private val providerId = "github-copilot"
    private val modelId = "gpt-5-mini"

    @Test
    fun `creates and updates hello txt through real opencode server`() {
        withLiveSession(version) { environment, server, sessionClient, events, sessionId ->
            submitPromptAndAwaitTurn(
                sessionClient = sessionClient,
                events = events,
                port = server.port,
                sessionId = sessionId,
                text = """
                    Create a file named hello.txt in the project root containing exactly:
                    Hello World
                    Do not modify any other files.
                """.trimIndent(),
            )
            val helloFile = environment.repoRoot.resolve("hello.txt")
            assertFileText(helloFile, "Hello World\n")

            val createDiffFile = requireSessionDiffFile(sessionClient, server.port, sessionId, "hello.txt")
            assertEquals(SessionDiffStatus.ADDED, createDiffFile.status)
            val createPreview = awaitFileDiffPreview(
                sessionClient = sessionClient,
                port = server.port,
                sessionId = sessionId,
                projectBase = environment.repoRoot.toString(),
                absFilePath = helloFile.toString(),
            )
            assertInlineDiffFromServerPayload(
                repoRoot = environment.repoRoot.toString(),
                sessionId = sessionId,
                diffFile = createDiffFile,
                expectedRemoved = "",
                expectedAdded = "Hello World\n",
            )
            assertPreviewMatchesServerDiff(createPreview, createDiffFile)

            submitPromptAndAwaitTurn(
                sessionClient = sessionClient,
                events = events,
                port = server.port,
                sessionId = sessionId,
                text = """
                    Change hello.txt so it contains exactly:
                    Goodbye World
                    Do not modify any other files.
                """.trimIndent(),
            )
            assertFileText(helloFile, "Goodbye World\n")

            val updateDiffFile = requireSessionDiffFile(sessionClient, server.port, sessionId, "hello.txt")
            assertEquals(SessionDiffStatus.ADDED, updateDiffFile.status)
            val updatePreview = awaitFileDiffPreview(
                sessionClient = sessionClient,
                port = server.port,
                sessionId = sessionId,
                projectBase = environment.repoRoot.toString(),
                absFilePath = helloFile.toString(),
            )
            assertInlineDiffFromServerPayload(
                repoRoot = environment.repoRoot.toString(),
                sessionId = sessionId,
                diffFile = updateDiffFile,
                expectedRemoved = "",
                expectedAdded = "Goodbye World\n",
            )
            assertPreviewMatchesServerDiff(updatePreview, updateDiffFile)
        }
    }

    @Test
    fun `edits pre existing file and preserves diff semantics`() {
        withLiveSession(version) { environment, server, sessionClient, events, sessionId ->
            val noteFile = environment.repoRoot.resolve("note.txt")
            noteFile.writeText("Alpha\nBravo\nCharlie\n")

            submitPromptAndAwaitTurn(
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
            )

            assertFileText(noteFile, "Alpha\nBeta\nCharlie\n")

            val diffFile = requireSessionDiffFile(sessionClient, server.port, sessionId, "note.txt")
            assertEquals(SessionDiffStatus.MODIFIED, diffFile.status)
            assertEquals("Alpha\nBravo\nCharlie\n", normalizeNewlinesOnly(diffFile.before))
            assertEquals("Alpha\nBeta\nCharlie\n", normalizeNewlinesOnly(diffFile.after))

            val preview = awaitFileDiffPreview(
                sessionClient = sessionClient,
                port = server.port,
                sessionId = sessionId,
                projectBase = environment.repoRoot.toString(),
                absFilePath = noteFile.toString(),
            )
            assertPreviewMatchesServerDiff(preview, diffFile)
            assertInlineDiffFromServerPayload(
                repoRoot = environment.repoRoot.toString(),
                sessionId = sessionId,
                diffFile = diffFile,
                expectedRemoved = "Bravo",
                expectedAdded = "Beta",
            )
        }
    }

    @Test
    fun `removes middle chunk from longer file and preserves diff semantics`() {
        withLiveSession(version) { environment, server, sessionClient, events, sessionId ->
            val longFile = environment.repoRoot.resolve("numbers.txt")
            val original = lines(1..100)
            val removedBlock = lines(41..60)
            val expected = lines((1..100).filter { it !in 41..60 })
            val searchBlock = "40\n${removedBlock}61\n"
            val replacementBlock = "40\n61\n"
            val prompt = """
                Edit only `numbers.txt`.
                Perform one exact text replacement in the file.
                Replace this exact text:
                ```text
                $searchBlock
                ```
                with this exact text:
                ```text
                $replacementBlock
                ```
                Do not rewrite the whole file.
                Leave all remaining content byte-for-byte unchanged.
                After the edit, line `40` must be followed immediately by line `61`.
                The file must still start with `1`, end with `100`, contain one plain number per line,
                and keep the trailing newline.
                Do not add line numbers, duplicate content, renumber anything, or modify any other files.
            """.trimIndent()
            longFile.writeText(original)

            submitPromptAndAwaitTurn(
                sessionClient = sessionClient,
                events = events,
                port = server.port,
                sessionId = sessionId,
                turnTimeoutMs = 60_000,
                text = prompt,
            )

            assertFileText(longFile, expected)

            val diffFile = requireSessionDiffFile(sessionClient, server.port, sessionId, "numbers.txt")
            assertEquals(SessionDiffStatus.MODIFIED, diffFile.status)
            assertEquals(normalizeNewlinesOnly(original), normalizeNewlinesOnly(diffFile.before))
            assertEquals(normalizeNewlinesOnly(expected), normalizeNewlinesOnly(diffFile.after))

            val preview = awaitFileDiffPreview(
                sessionClient = sessionClient,
                port = server.port,
                sessionId = sessionId,
                projectBase = environment.repoRoot.toString(),
                absFilePath = longFile.toString(),
                timeoutMs = 15_000,
            )
            assertPreviewMatchesServerDiff(preview, diffFile)
            assertInlineDiffFromServerPayload(
                repoRoot = environment.repoRoot.toString(),
                sessionId = sessionId,
                diffFile = diffFile,
                expectedRemoved = removedBlock.removeSuffix("\n"),
                expectedAdded = "",
            )
        }
    }

    private fun withLiveSession(
        version: String,
        block: (
            environment: OpenCodeTestEnvironment,
            server: OpenCodeTestServer,
            sessionClient: SessionApiClient,
            events: OpenCodeTestEventCollector,
            sessionId: String,
        ) -> Unit,
    ) {
        OpenCodeTestEnvironmentFactory.create(version).use { environment ->
            val server = environment.startServer()
            val sessionClient = SessionApiClient()
            OpenCodeTestEventCollector(server.port, environment.repoRoot.toString()).use { events ->
                try {
                    events.awaitConnected()
                    val session = assertIs<ApiResult.Success<SessionApiClient.CreatedSession>>(
                        sessionClient.createSession(server.port)
                    ).value
                    block(environment, server, sessionClient, events, session.sessionId)
                } catch (t: Throwable) {
                    environment.preserveForDiagnostics()
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
        turnTimeoutMs: Long = 30_000,
        text: String,
    ) {
        val nextIdleCount = events.sessionIdleCount(sessionId) + 1
        val nextDiffCount = events.sessionDiffCount(sessionId) + 1
        assertIs<ApiResult.Success<Unit>>(
            sessionClient.promptTextAsync(
                port = port,
                sessionId = sessionId,
                providerId = providerId,
                modelId = modelId,
                text = text,
            ),
        )
        events.awaitSessionDiff(sessionId, nextDiffCount, timeoutMs = turnTimeoutMs)
        events.awaitIdleStatus(sessionId, nextIdleCount, timeoutMs = turnTimeoutMs)
    }

    private fun requireSessionDiffFile(
        sessionClient: SessionApiClient,
        port: Int,
        sessionId: String,
        relativePath: String,
    ): OpenCodeEvent.SessionDiffFile {
        val diff = assertIs<ApiResult.Success<OpenCodeEvent.SessionDiff>>(
            sessionClient.fetchSessionDiffSnapshot(port, sessionId),
        ).value
        val diffFile = diff.files.firstOrNull { it.file == relativePath }
        assertTrue(diffFile != null, "session diff should include $relativePath")
        return diffFile
    }

    private fun assertFileText(path: Path, expected: String) {
        val actual = if (path.exists()) normalizeNewlinesOnly(path.readText()) else "<missing file>"
        assertEquals(normalizeNewlinesOnly(expected), actual, "Unexpected file content at $path")
    }

    private fun awaitFileDiffPreview(
        sessionClient: SessionApiClient,
        port: Int,
        sessionId: String,
        projectBase: String,
        absFilePath: String,
        timeoutMs: Long = 20_000,
    ): SessionApiClient.FileDiffPreview {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val result = sessionClient.fetchFileDiffPreview(port, sessionId, projectBase, absFilePath)
            val preview = (result as? ApiResult.Success<SessionApiClient.FileDiffPreview?>)?.value
            if (preview != null) {
                return preview
            }
            Thread.sleep(100)
        }

        throw AssertionError(
            "Timed out waiting for 3-panel preview for $absFilePath",
        )
    }

    private fun assertInlineDiffFromServerPayload(
        repoRoot: String,
        sessionId: String,
        diffFile: OpenCodeEvent.SessionDiffFile,
        expectedRemoved: String,
        expectedAdded: String,
    ) {
        val relativeFile = diffFile.file
        val harness = DiffPipelineHarness(
            projectBase = repoRoot,
            sessionId = sessionId,
            hunkComputer = { fileDiff, sid ->
                if (fileDiff.before == fileDiff.after) emptyList()
                else listOf(
                    DiffHunk(
                        filePath = fileDiff.file,
                        startLine = sharedPrefixLineCount(fileDiff.before, fileDiff.after),
                        removedLines = contentLines(fileDiff.before).drop(
                            sharedPrefixLineCount(fileDiff.before, fileDiff.after)
                        ).dropLast(sharedSuffixLineCount(fileDiff.before, fileDiff.after)),
                        addedLines = contentLines(fileDiff.after).drop(
                            sharedPrefixLineCount(fileDiff.before, fileDiff.after)
                        ).dropLast(sharedSuffixLineCount(fileDiff.before, fileDiff.after)),
                        sessionId = sid,
                    )
                )
            },
        )
        harness.disk[harness.abs(relativeFile)] = diffFile.before
        harness.commitTurnPatch(listOf(relativeFile))
        harness.disk[harness.abs(relativeFile)] = diffFile.after
        harness.applySessionDiffFiles(listOf(diffFile))

        val hunks = harness.hunksFor(relativeFile)
        assertTrue(hunks.isNotEmpty(), "inline diff should produce hunks for $relativeFile")
        val removedText = normalizeNewlinesOnly(hunks.flatMap { it.removedLines }.joinToString("\n"))
        val addedText = normalizeNewlinesOnly(hunks.flatMap { it.addedLines }.joinToString("\n"))
        assertEquals(normalizeNewlinesOnly(expectedRemoved), removedText)
        assertEquals(normalizeNewlinesOnly(expectedAdded), addedText)
    }

    private fun assertPreviewMatchesServerDiff(
        preview: SessionApiClient.FileDiffPreview,
        diffFile: OpenCodeEvent.SessionDiffFile,
    ) {
        assertEquals(normalizeNewlinesOnly(diffFile.before), normalizeNewlinesOnly(preview.before))
        assertEquals(normalizeNewlinesOnly(diffFile.after), normalizeNewlinesOnly(preview.after))
    }

    private fun lines(values: Iterable<Int>): String = values.joinToString(separator = "\n") + "\n"

    private fun sharedPrefixLineCount(before: String, after: String): Int {
        val beforeLines = contentLines(before)
        val afterLines = contentLines(after)
        var prefix = 0
        while (prefix < beforeLines.size && prefix < afterLines.size && beforeLines[prefix] == afterLines[prefix]) {
            prefix += 1
        }
        return prefix
    }

    private fun sharedSuffixLineCount(before: String, after: String): Int {
        val beforeLines = contentLines(before)
        val afterLines = contentLines(after)
        val prefix = sharedPrefixLineCount(before, after)
        var beforeEndExclusive = beforeLines.size
        var afterEndExclusive = afterLines.size
        var suffix = 0
        while (
            beforeEndExclusive > prefix &&
            afterEndExclusive > prefix &&
            beforeLines[beforeEndExclusive - 1] == afterLines[afterEndExclusive - 1]
        ) {
            beforeEndExclusive -= 1
            afterEndExclusive -= 1
            suffix += 1
        }
        return suffix
    }

    private fun contentLines(content: String): List<String> = if (content.isEmpty()) emptyList() else content.lines()

    private fun normalizeNewlinesOnly(content: String): String = normalizeTestContent(content)
}
