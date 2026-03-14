package com.ashotn.opencode.relay.api.permission

import com.ashotn.opencode.relay.api.transport.ApiEndpoint
import com.ashotn.opencode.relay.api.transport.HttpMethod

internal object PermissionEndpoints {
    fun reply(sessionId: String, permissionId: String): ApiEndpoint =
        ApiEndpoint(method = HttpMethod.POST, path = "/session/$sessionId/permissions/$permissionId")
}
