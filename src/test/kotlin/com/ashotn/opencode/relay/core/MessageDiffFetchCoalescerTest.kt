package com.ashotn.opencode.relay.core

import com.ashotn.opencode.relay.ipc.OpenCodeEvent
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MessageDiffFetchCoalescerTest {
    @Test
    fun `duplicate in-flight event is returned once as latest pending event`() {
        val coalescer = MessageDiffFetchCoalescer()
        val first = event(files = listOf("first.kt"))
        val pendingOld = event(files = listOf("old.kt"))
        val pendingLatest = event(files = listOf("latest.kt"))
        val key = coalescer.key(first)

        assertTrue(coalescer.tryStart(first))
        assertFalse(coalescer.tryStart(pendingOld))
        assertFalse(coalescer.tryStart(pendingLatest))

        assertEquals(pendingLatest, coalescer.finish(key, allowPending = true))
        assertNull(coalescer.finish(key, allowPending = true))
        assertTrue(coalescer.tryStart(first))
    }

    @Test
    fun `pending duplicate can continue until no pending updates remain`() {
        val coalescer = MessageDiffFetchCoalescer()
        val first = event(messageId = "msg", files = listOf("first.kt"))
        val second = event(messageId = "msg", files = listOf("second.kt"))
        val third = event(messageId = "msg", files = listOf("third.kt"))
        val key = coalescer.key(first)

        assertTrue(coalescer.tryStart(first))
        assertFalse(coalescer.tryStart(second))
        assertEquals(second, coalescer.finish(key, allowPending = true))

        assertFalse(coalescer.tryStart(third))
        assertEquals(third, coalescer.finish(key, allowPending = true))
        assertNull(coalescer.finish(key, allowPending = true))
    }

    @Test
    fun `non-duplicate events start independently`() {
        val coalescer = MessageDiffFetchCoalescer()

        assertTrue(coalescer.tryStart(event(sessionId = "ses_1", messageId = "msg_1")))
        assertTrue(coalescer.tryStart(event(sessionId = "ses_1", messageId = "msg_2")))
        assertTrue(coalescer.tryStart(event(sessionId = "ses_2", messageId = "msg_1")))
    }

    private fun event(
        sessionId: String = "ses",
        messageId: String = "msg",
        files: List<String> = emptyList(),
    ) = OpenCodeEvent.MessageDiffAvailable(
        sessionId = sessionId,
        messageId = messageId,
        files = files,
    )
}
