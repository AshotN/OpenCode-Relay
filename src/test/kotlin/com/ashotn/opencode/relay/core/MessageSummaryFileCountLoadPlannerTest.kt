package com.ashotn.opencode.relay.core

import com.ashotn.opencode.relay.api.session.Session
import com.ashotn.opencode.relay.api.session.SessionTime
import org.junit.Test
import kotlin.test.assertEquals

class MessageSummaryFileCountLoadPlannerTest {

    @Test
    fun `message summary file count loads only newest eligible sessions up to batch size`() {
        // In OpenCode 2.x sessions carry no server-side summary; eligibility is
        // decided purely by the caller's predicate.
        val sessions = listOf(
            session("newest", updated = 50),
            session("plain", updated = 100),
            session("loaded", updated = 60),
            session("middle", updated = 30),
            session("newer", updated = 45),
        )

        val selected = selectMessageSummaryFileCountLoadBatch(
            sessions = sessions,
            maxBatchSize = 2,
        ) { session -> session.id != "loaded" }

        assertEquals(listOf("plain", "newest"), selected.map { it.id })
    }

    private fun session(id: String, updated: Long): Session = Session(
        id = id,
        projectID = null,
        directory = null,
        parentID = null,
        title = id,
        time = SessionTime(created = 0, updated = updated, compacting = null),
    )
}
