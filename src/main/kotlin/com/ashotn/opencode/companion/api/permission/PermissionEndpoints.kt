package com.ashotn.opencode.companion.api.permission

import com.ashotn.opencode.companion.api.transport.ApiEndpoint
import com.ashotn.opencode.companion.api.transport.HttpMethod

internal object PermissionEndpoints {
    fun reply(sessionId: String, permissionId: String): ApiEndpoint =
        ApiEndpoint(method = HttpMethod.POST, path = "/session/$sessionId/permissions/$permissionId")
}
