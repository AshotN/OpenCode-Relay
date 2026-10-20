package com.ashotn.opencode.relay.api.transport

import com.google.gson.JsonElement

/**
 * Unwraps the OpenCode v2 response envelope.
 *
 * Most v2 routes wrap their payload in a `{"data": ...}` object, but a few
 * (e.g. `GET /api/info`) return the payload directly. This returns the `data`
 * member when present and the receiver itself otherwise.
 *
 * Composite envelopes that carry sibling fields alongside `data`
 * (e.g. `GET /api/session` returns `{data, cursor}`) should not be unwrapped
 * blindly — read the siblings explicitly via [OpenCodeHttpTransport.parseJsonEnvelope].
 */
fun JsonElement.unwrapData(): JsonElement {
    if (!isJsonObject) return this
    return asJsonObject.get("data") ?: this
}
