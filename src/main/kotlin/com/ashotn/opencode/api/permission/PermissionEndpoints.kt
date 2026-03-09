package com.ashotn.opencode.api.permission

import com.ashotn.opencode.api.transport.ApiEndpoint
import com.ashotn.opencode.api.transport.HttpMethod

internal object PermissionEndpoints {
    fun reply(sessionId: String, permissionId: String): ApiEndpoint =
        ApiEndpoint(method = HttpMethod.POST, path = "/session/$sessionId/permissions/$permissionId")
}
