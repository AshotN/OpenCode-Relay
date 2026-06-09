package com.ashotn.opencode.relay.integration.diff

import com.ashotn.opencode.relay.integration.OpenCodeTestEnvironment
import com.ashotn.opencode.relay.integration.OpenCodeTestEnvironmentFactory
import com.ashotn.opencode.relay.integration.OpenCodeTestEventCollector
import com.ashotn.opencode.relay.integration.OpenCodeTestServer
import com.ashotn.opencode.relay.integration.OpenCodeTestVersions
import com.ashotn.opencode.relay.api.session.Session
import com.ashotn.opencode.relay.api.session.SessionApiClient
import com.ashotn.opencode.relay.api.transport.ApiResult
import com.ashotn.opencode.relay.core.CoreDiffStateHarness
import com.ashotn.opencode.relay.core.DiffPipelineHarness
import com.ashotn.opencode.relay.core.DiffHunk
import com.ashotn.opencode.relay.ipc.OpenCodeEvent
import com.ashotn.opencode.relay.ipc.SessionDiffStatus
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.createDirectories
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
        val sessions: List<Session>,
        val diffsBySessionId: Map<String, OpenCodeEvent.SessionDiff>,
        val fileToChildSessionId: Map<String, String>,
        val diffSummaryRoleByFile: Map<String, String>,
    )

    private data class MessageDiffProbeContext(
        val sessionClient: SessionApiClient,
        val port: Int,
        val sessionId: String,
        val repoRoot: Path,
    )

    private data class MessageDiffProbeSnapshot(
        val messageId: String,
        val eventFiles: List<String>,
        val fetchedFiles: List<String>,
    )

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
                    Edit only `hello.txt`.
                    The file currently contains exactly `Hello World` and a trailing newline.
                    Replace the line `Hello World` with `Goodbye World`.
                    The final file must be exactly:
                    ```text
                    Goodbye World
                    ```
                    Keep the trailing newline.
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

    @Test
    fun `sub-agent edits are visible under root session diff state`() {
        withLiveSession(version, allowTask = true) { environment, server, sessionClient, events, sessionId ->
            val expectedFiles = linkedMapOf(
                "live-subagents/alpha.txt" to "alpha from sub-agent\n",
                "live-subagents/bravo.txt" to "bravo from sub-agent\n",
                "live-subagents/charlie.txt" to "charlie from sub-agent\n",
            )

            submitPromptAndAwaitTurn(
                sessionClient = sessionClient,
                events = events,
                port = server.port,
                sessionId = sessionId,
                turnTimeoutMs = 120_000,
                text = """
                    Use the built-in General subagent for three separate parallel tasks.
                    Task A: create only `live-subagents/alpha.txt` with exactly `alpha from sub-agent` and a trailing newline.
                    Task B: create only `live-subagents/bravo.txt` with exactly `bravo from sub-agent` and a trailing newline.
                    Task C: create only `live-subagents/charlie.txt` with exactly `charlie from sub-agent` and a trailing newline.
                    Do not edit README.md or any other files.
                    After all three subagents finish, reply with a short summary.
                """.trimIndent(),
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
                timeoutMs = 30_000,
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
                expectedFiles.keys.associateWith { "user" },
                childDiffs.diffSummaryRoleByFile,
                "child diff summaries should be carried by user messages, matching the parser filter",
            )

            val coreVisible = applyRealDiffsToCoreState(
                projectBase = environment.repoRoot.toString(),
                rootSessionId = sessionId,
                sessions = childDiffs.sessions,
                diffsBySessionId = childDiffs.diffsBySessionId,
            )
            val expectedAbsFiles = expectedFiles.keys.map { environment.repoRoot.resolve(it).toString() }.toSet()

            assertEquals(
                expectedAbsFiles,
                coreVisible.visibleFiles.intersect(expectedAbsFiles),
                "root session file list should include all sub-agent edits",
            )
            assertEquals(
                expectedAbsFiles,
                coreVisible.liveVisibleFiles.intersect(expectedAbsFiles),
                "root session live diff state should include all sub-agent edits simultaneously",
            )
        }
    }

    @Test
    fun `python multi-file turn does not lose later message diff updates`() {
        if (!version.startsWith("1.16.")) return

        val probeContext = AtomicReference<MessageDiffProbeContext?>()
        val snapshots = ConcurrentLinkedQueue<MessageDiffProbeSnapshot>()

        withLiveSession(
            version = version,
            onEvent = { event ->
                val context = probeContext.get() ?: return@withLiveSession
                if (event !is OpenCodeEvent.MessageDiffAvailable || event.sessionId != context.sessionId) return@withLiveSession

                val fetchedFiles = when (val result = context.sessionClient.fetchSessionDiffSnapshot(
                    port = context.port,
                    sessionId = event.sessionId,
                    messageId = event.messageId,
                )) {
                    is ApiResult.Success -> result.value.files.map { normalizeDiffPath(context.repoRoot, it.file) }
                    is ApiResult.Failure -> emptyList()
                }
                snapshots.add(
                    MessageDiffProbeSnapshot(
                        messageId = event.messageId,
                        eventFiles = event.files.map { it.replace('\\', '/') }.sorted(),
                        fetchedFiles = fetchedFiles.sorted(),
                    )
                )
            },
        ) { environment, server, sessionClient, events, sessionId ->
            probeContext.set(MessageDiffProbeContext(sessionClient, server.port, sessionId, environment.repoRoot))

            val files = linkedMapOf(
                "pkg/alpha.py" to "def value():\n    return \"alpha-old\"\n",
                "pkg/bravo.py" to "def value():\n    return \"bravo-old\"\n",
                "pkg/charlie.py" to "def value():\n    return \"charlie-old\"\n",
            )
            files.forEach { (relativePath, content) ->
                val path = environment.repoRoot.resolve(relativePath)
                path.parent.createDirectories()
                path.writeText(content)
            }

            submitPromptAndAwaitTurn(
                sessionClient = sessionClient,
                events = events,
                port = server.port,
                sessionId = sessionId,
                turnTimeoutMs = 60_000,
                text = """
                    Edit only these Python files: `pkg/alpha.py`, `pkg/bravo.py`, and `pkg/charlie.py`.
                    Make exactly these replacements:
                    - In `pkg/alpha.py`, replace `alpha-old` with `alpha-new`.
                    - In `pkg/bravo.py`, replace `bravo-old` with `bravo-new`.
                    - In `pkg/charlie.py`, replace `charlie-old` with `charlie-new`.
                    Do not modify any other files.
                """.trimIndent(),
            )

            val expectedFiles = files.keys.toSet()
            assertFileText(environment.repoRoot.resolve("pkg/alpha.py"), "def value():\n    return \"alpha-new\"\n")
            assertFileText(environment.repoRoot.resolve("pkg/bravo.py"), "def value():\n    return \"bravo-new\"\n")
            assertFileText(environment.repoRoot.resolve("pkg/charlie.py"), "def value():\n    return \"charlie-new\"\n")

            val finalDiff = assertIs<ApiResult.Success<OpenCodeEvent.SessionDiff>>(
                sessionClient.fetchSessionDiffSnapshot(server.port, sessionId),
            ).value
            val finalFiles = finalDiff.files.map { normalizeDiffPath(environment.repoRoot, it.file) }.toSet()
            assertEquals(expectedFiles, finalFiles.intersect(expectedFiles), "final server diff should include all Python files")

            val latestLoadedFiles = snapshots
                .groupBy { it.messageId }
                .values
                .flatMap { it.last().fetchedFiles }
                .map { normalizeDiffPath(environment.repoRoot, it) }
                .filter { it in expectedFiles }
                .toSet()

            assertEquals(
                expectedFiles,
                latestLoadedFiles,
                buildString {
                    appendLine("latest message-diff fetches should include every Python file")
                    appendLine("messageDiffEvents=${events.messageDiffEvents(sessionId)}")
                    appendLine("snapshots=${snapshots.toList()}")
                    appendLine("finalFiles=$finalFiles")
                },
            )
        }
    }

    @Test
    fun `reverted AI changes are absent from restored file list`() {
        if (!version.startsWith("1.16.")) return

        withLiveSession(version) { environment, server, sessionClient, events, sessionId ->
            val relativePath = "revert-me.txt"
            val original = "Original content\n"
            val aiContent = "AI content\n"
            val file = environment.repoRoot.resolve(relativePath)
            file.writeText(original)

            submitPromptAndAwaitTurn(
                sessionClient = sessionClient,
                events = events,
                port = server.port,
                sessionId = sessionId,
                text = """
                    Edit only `$relativePath`.
                    Replace the entire file content with exactly:
                    ```text
                    AI content
                    ```
                    Keep the trailing newline.
                    Do not modify any other files.
                """.trimIndent(),
            )
            assertFileText(file, aiContent)

            val serverDiff = assertIs<ApiResult.Success<OpenCodeEvent.SessionDiff>>(
                sessionClient.fetchSessionDiffSnapshot(server.port, sessionId),
            ).value
            assertTrue(
                serverDiff.files.any { normalizeDiffPath(environment.repoRoot, it.file) == relativePath },
                "server message history should still report $relativePath after the AI edit",
            )

            file.writeText(original)
            assertFileText(file, original)

            val harness = DiffPipelineHarness(
                projectBase = environment.repoRoot.toString(),
                sessionId = sessionId,
            )
            harness.disk[harness.abs(relativePath)] = original
            harness.applyHistoricalSessionDiffFiles(serverDiff.files)

            assertEquals(
                0,
                harness.trackedFileCount(),
                "reverted AI changes should not remain visible in the restored session file list",
            )
        }
    }

    private fun withLiveSession(
        version: String,
        allowTask: Boolean = false,
        onEvent: (OpenCodeEvent) -> Unit = {},
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
            val sessionClient = SessionApiClient()
            OpenCodeTestEventCollector(server.port, environment.repoRoot.toString(), onEvent).use { events ->
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
        val nextDiffSignalCount = events.diffSignalCount(sessionId) + 1
        assertIs<ApiResult.Success<Unit>>(
            sessionClient.promptTextAsync(
                port = port,
                sessionId = sessionId,
                providerId = providerId,
                modelId = modelId,
                text = text,
            ),
        )
        events.awaitDiffSignal(sessionId, nextDiffSignalCount, timeoutMs = turnTimeoutMs)
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

    private fun awaitSubAgentDiffs(
        sessionClient: SessionApiClient,
        port: Int,
        rootSessionId: String,
        repoRoot: Path,
        expectedRelativePaths: Set<String>,
        timeoutMs: Long,
    ): ChildDiffs {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastSessions: List<Session> = emptyList()
        var lastDiffsBySessionId: Map<String, OpenCodeEvent.SessionDiff> = emptyMap()

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
                    sessions = sessions,
                    diffsBySessionId = diffsBySessionId.filterValues { diff -> diff.files.isNotEmpty() },
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

    private fun applyRealDiffsToCoreState(
        projectBase: String,
        rootSessionId: String,
        sessions: List<Session>,
        diffsBySessionId: Map<String, OpenCodeEvent.SessionDiff>,
    ): CoreDiffStateHarness.VisibleState {
        val harness = CoreDiffStateHarness(projectBase)
        diffsBySessionId.forEach { (diffSessionId, diff) ->
            harness.applyLiveMessageDiff(
                sessionId = diffSessionId,
                diff = diff,
                readContent = { absPath -> Path.of(absPath).takeIf { it.exists() }?.readText() ?: "" },
            )
        }

        return harness.selectRootAndVisibleState(rootSessionId, sessions)
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

    private fun normalizeNewlinesOnly(content: String): String = content.replace("\r\n", "\n")
}
