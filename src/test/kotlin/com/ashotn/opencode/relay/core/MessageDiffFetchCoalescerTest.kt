package com.ashotn.opencode.relay.core

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessageDiffFetchCoalescerTest {

    @Test
    fun `duplicate in-flight fetch is coalesced and reported as pending`() {
        val coalescer = MessageDiffFetchCoalescer()
        val key = MessageDiffFetchCoalescer.liveTurnKey("ses_1")

        assertTrue(coalescer.tryStart(key))
        assertFalse(coalescer.tryStart(key))

        assertTrue(coalescer.finish(key, allowPending = true))
        assertFalse(coalescer.finish(key, allowPending = true))
        assertTrue(coalescer.tryStart(key))
    }

    @Test
    fun `pending fetch is discarded when generation is stale`() {
        val coalescer = MessageDiffFetchCoalescer()
        val key = MessageDiffFetchCoalescer.liveTurnKey("ses_1")

        assertTrue(coalescer.tryStart(key))
        assertFalse(coalescer.tryStart(key))
        assertFalse(coalescer.finish(key, allowPending = false))
        assertFalse(coalescer.finish(key, allowPending = true))
        assertTrue(coalescer.tryStart(key))
    }

    @Test
    fun `distinct keys start independently`() {
        val coalescer = MessageDiffFetchCoalescer()

        assertTrue(coalescer.tryStart(MessageDiffFetchCoalescer.liveTurnKey("ses_1")))
        assertTrue(coalescer.tryStart(MessageDiffFetchCoalescer.liveTurnKey("ses_2")))
        assertTrue(coalescer.tryStart(MessageDiffFetchCoalescer.key("ses_1", "msg_1")))
    }
}
