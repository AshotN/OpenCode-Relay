package com.ashotn.opencode.relay.api.tui

import com.ashotn.opencode.relay.api.transport.ApiEndpoint
import com.ashotn.opencode.relay.api.transport.HttpMethod

internal object TuiEndpoints {
    fun appendPrompt(): ApiEndpoint = ApiEndpoint(method = HttpMethod.POST, path = "/tui/append-prompt")

    fun selectSession(): ApiEndpoint = ApiEndpoint(method = HttpMethod.POST, path = "/tui/select-session")
}
