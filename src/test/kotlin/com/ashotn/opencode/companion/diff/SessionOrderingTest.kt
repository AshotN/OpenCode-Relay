package com.ashotn.opencode.companion.diff

import com.ashotn.opencode.companion.api.session.Session
import com.ashotn.opencode.companion.api.session.SessionTime
import com.ashotn.opencode.companion.ipc.OpenCodeEvent
import com.ashotn.opencode.companion.ipc.SessionDiffStatus
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for session list ordering.
 *
 * The session list must always reflect the server's `time.updated` order (newest first),
 * regardless of the order in which background operations complete at plugin startup.
 *
 * Regression: on connect the plugin loads historical diffs for all sessions and then
 * runs commitReconcile for each. Because reconcile finished in an arbitrary order,
 * whichever session completed last would get the highest `System.currentTimeMillis()`
 * stamp and appear first in the list — overriding the server-reported timestamps.
 */
class SessionOrderingTest {

    // -------------------------------------------------------------------------
    // Session ordering must match server-reported timestamps after reconcile
    //
    // REGRESSION: commitReconcile was writing System.currentTimeMillis() into
    // updatedAtBySession, overwriting the timestamps seeded from the server's
    // /session response (time.updated). Whichever session's reconcile finished
    // last ended up sorted first — unrelated to actual recency.
    //
    // MANUAL VERIFICATION:
    //   1. Create several sessions in the TUI, newest being "Note2.md: add joke".
    //   2. Open the plugin tool window — session list must show "Note2.md: add joke"
    //      at the top, matching the TUI order.
    //   3. Before the fix, an older session ("Add number 1 to notes.md") appeared
    //      first because its historical diff reconcile happened to finish last.
    // -------------------------------------------------------------------------
    @Test
    fun `session order is determined by server timestamps, not by reconcile completion order`() {
        // Use a single shared store so commitReconcile mutations are visible to listSessions.
        val store = DiffStateStore()
        val stateLock = Any()
        val generation = 1L
        val eventReducer = DiffEventReducer()
        val projectBase = "/project"

        // Three sessions. The server reports them newest → oldest:
        //   sessionNew  updated at T=3000  (most recent — should be first)
        //   sessionMid  updated at T=2000
        //   sessionOld  updated at T=1000  (oldest — should be last)
        val sessionNew = "ses_new"
        val sessionMid = "ses_mid"
        val sessionOld = "ses_old"

        val tNew = 3000L
        val tMid = 2000L
        val tOld = 1000L

        // Simulate System.currentTimeMillis() values used during the diff apply —
        // always larger than the server-reported timestamps.
        // The OLDEST session is processed last (highest nowMillis = 30_000).
        // Without the fix, reconcile would write these nowMillis values into
        // updatedAtBySession, making sessionOld (30_000) sort before sessionNew
        // (10_000) — the exact opposite of the correct server-reported order.
        val nowBySession = mapOf(
            sessionNew to 10_000L,  // processed first → lowest nowMillis
            sessionMid to 20_000L,
            sessionOld to 30_000L,  // processed last → highest nowMillis
        )

        // ── Step 1: seed timestamps from the server /session response ──────────
        store.updatedAtBySession[sessionNew] = tNew
        store.updatedAtBySession[sessionMid] = tMid
        store.updatedAtBySession[sessionOld] = tOld

        val disk = mutableMapOf<String, String>()
        val computer = SessionDiffApplyComputer(
            contentReader = { absPath -> disk[absPath] ?: "" },
            hunkComputer = { fileDiff, sid ->
                if (fileDiff.before == fileDiff.after) emptyList()
                else listOf(DiffHunk(fileDiff.file, 0,
                    listOf(fileDiff.before), listOf(fileDiff.after), sid))
            },
            log = NoOpLogger,
            tracer = NoOpDiffTracer,
        )

        // ── Step 2 & 3: for each session, run the full historical diff + reconcile ──
        // Processed in the order that maximises the bug: newest first, oldest last.
        // sessionOld finishes last and gets nowMillis=30_000 > tNew=3000, so without
        // the fix it sorts to the top.
        for ((sid, relPath, content) in listOf(
            Triple(sessionNew, "new.md", "new content"),
            Triple(sessionMid, "mid.md", "mid content"),
            Triple(sessionOld, "old.md", "old content"),  // finishes last
        )) {
            val absPath = "$projectBase/$relPath"
            disk[absPath] = content

            // Reserve revision
            val revision = store.reserveRevisionForSessionDiffApply(
                stateLock = stateLock,
                sessionId = sid,
                fromHistory = true,
                expectedGeneration = generation,
                currentGeneration = { generation },
            )!!

            val prepareSnapshot = store.snapshotSessionDiffPrepareState(
                stateLock = stateLock,
                sessionId = sid,
                expectedGeneration = generation,
                currentGeneration = { generation },
            )!!

            val event = OpenCodeEvent.SessionDiff(
                sessionId = sid,
                files = listOf(OpenCodeEvent.SessionDiffFile(
                    file = absPath,
                    before = "",
                    after = "ai content",
                    additions = 1,
                    deletions = 0,
                    status = SessionDiffStatus.MODIFIED,
                )),
            )

            val computedState = computer.compute(
                projectBase = projectBase,
                event = event,
                fromHistory = true,
                turnScope = null,
                previousAfterByFile = prepareSnapshot.previousAfterByFile,
            )

            store.commitSessionDiffApply(
                stateLock = stateLock,
                sessionId = sid,
                revision = revision,
                fromHistory = true,
                computedState = computedState,
                nowMillis = nowBySession[sid]!!,
                expectedGeneration = generation,
                currentGeneration = { generation },
            )

            // Simulate the file already matching baseline by the time reconcile runs.
            // This guarantees reduceReconcile returns a decision and commitReconcile executes.
            disk[absPath] = ""

            // ── Step 3: commitReconcile — the previously-buggy step ───────────
            val snapshot = assertNotNull(
                store.snapshotSessionReconcileState(
                    stateLock = stateLock,
                    sessionId = sid,
                    expectedGeneration = generation,
                    currentGeneration = { generation },
                ),
                "expected reconcile snapshot for $sid",
            )

            val decision = assertNotNull(
                eventReducer.reduceReconcile(
                    currentHunks = snapshot.currentHunks,
                    currentDeleted = snapshot.currentDeleted,
                    currentAdded = snapshot.currentAdded,
                    currentBaselines = snapshot.currentBaselines,
                    readCurrentContent = { absPath2 -> disk[absPath2] ?: "" },
                ),
                "expected reconcile decision for $sid",
            )

            val committed = store.commitReconcile(
                stateLock = stateLock,
                sessionId = sid,
                revision = store.currentSessionRevision(stateLock, sid),
                updatedHunks = decision.updatedHunks,
                updatedDeleted = decision.updatedDeleted,
                updatedAdded = decision.updatedAdded,
                currentBaselines = snapshot.currentBaselines,
                expectedGeneration = generation,
                currentGeneration = { generation },
            )
            assertTrue(committed, "expected reconcile commit to succeed for $sid")
        }

        // ── Step 4: assert ordering ────────────────────────────────────────────
        // sessionOld's reconcile finished last with nowMillis=30_000, which is larger
        // than tNew=3000. Without the fix, sessionOld sorts first. With the fix,
        // the server-seeded order is preserved: sessionNew > sessionMid > sessionOld.
        assertEquals(
            mapOf(sessionNew to tNew, sessionMid to tMid, sessionOld to tOld),
            mapOf(
                sessionNew to store.updatedAtBySession[sessionNew],
                sessionMid to store.updatedAtBySession[sessionMid],
                sessionOld to store.updatedAtBySession[sessionOld],
            ),
            "reconcile must not overwrite server-seeded timestamps",
        )

        val sessions = DiffQueryService().listSessions(
            knownSessionIds = setOf(sessionNew, sessionMid, sessionOld),
            sessions = mapOf(
                sessionNew to Session(id = sessionNew, projectID = null, directory = null, parentID = null, title = "Newest session", version = null, time = SessionTime(0L, tNew, null), summary = null, share = null),
                sessionMid to Session(id = sessionMid, projectID = null, directory = null, parentID = null, title = "Middle session", version = null, time = SessionTime(0L, tMid, null), summary = null, share = null),
                sessionOld to Session(id = sessionOld, projectID = null, directory = null, parentID = null, title = "Oldest session", version = null, time = SessionTime(0L, tOld, null), summary = null, share = null),
            ),
            busyBySession = emptyMap(),
            hunksBySessionAndFile = emptyMap(),
            addedBySession = emptyMap(),
            deletedBySession = emptyMap(),
            updatedAtBySession = store.updatedAtBySession,
        )

        assertEquals(3, sessions.size)
        assertEquals(sessionNew, sessions[0].sessionId,
            "newest session (tNew=$tNew) must be first, got: ${sessions.map { "${it.sessionId}(updatedAt=${store.updatedAtBySession[it.sessionId]})" }}")
        assertEquals(sessionMid, sessions[1].sessionId,
            "middle session (tMid=$tMid) must be second, got: ${sessions.map { it.sessionId }}")
        assertEquals(sessionOld, sessions[2].sessionId,
            "oldest session (tOld=$tOld) must be last, got: ${sessions.map { it.sessionId }}")
    }

    // -------------------------------------------------------------------------
    // A live SSE event (session.busy / session.idle) for a session must update
    // its position in the list — it represents real current activity and should
    // override the server-seeded timestamp.
    // -------------------------------------------------------------------------
    @Test
    fun `live SSE busy event advances session to top of list`() {
        val store = DiffStateStore()
        val stateLock = Any()
        val generation = 1L

        val sessionA = "ses_aaa"
        val sessionB = "ses_bbb"

        // Start with sessionA newer than sessionB
        store.updatedAtBySession[sessionA] = 2000L
        store.updatedAtBySession[sessionB] = 1000L

        // sessionB receives a live busy event with a more recent timestamp
        val eventReducer = DiffEventReducer()
        eventReducer.commitSessionBusy(
            stateStore = store,
            stateLock = stateLock,
            sessionId = sessionB,
            isBusy = true,
            nowMillis = 9000L,   // later than sessionA's 2000
            generation = generation,
            currentGeneration = { generation },
        )

        val sessions = DiffQueryService().listSessions(
            knownSessionIds = setOf(sessionA, sessionB),
            sessions = mapOf(
                sessionA to Session(id = sessionA, projectID = null, directory = null, parentID = null, title = "Session A", version = null, time = SessionTime(0L, 2000L, null), summary = null, share = null),
                sessionB to Session(id = sessionB, projectID = null, directory = null, parentID = null, title = "Session B", version = null, time = SessionTime(0L, 1000L, null), summary = null, share = null),
            ),
            busyBySession = store.busyBySession,
            hunksBySessionAndFile = emptyMap(),
            addedBySession = emptyMap(),
            deletedBySession = emptyMap(),
            updatedAtBySession = store.updatedAtBySession,
        )

        // sessionB is busy → sorts first regardless of timestamp (busy-first rule)
        assertEquals(sessionB, sessions[0].sessionId,
            "busy session must sort first, got ${sessions.map { it.sessionId }}")
        assertEquals(sessionA, sessions[1].sessionId)
    }
}
