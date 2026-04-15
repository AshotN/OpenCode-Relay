package com.ashotn.opencode.relay.integration.session

import com.ashotn.opencode.relay.api.session.SessionApiClient
import com.ashotn.opencode.relay.integration.OpenCodeTestEnvironmentFactory
import com.ashotn.opencode.relay.integration.OpenCodeTestVersions
import com.ashotn.opencode.relay.api.transport.ApiResult
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.assertIs
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class OpenCodeConnectionAndSessionLiveTest(
    private val version: String,
) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun versions(): List<Array<String>> = OpenCodeTestVersions.all().map { arrayOf(it) }
    }

    @Test
    fun `creates real session against isolated server`() {
        val environment = OpenCodeTestEnvironmentFactory.create(version)

        try {
            val server = environment.startServer()
            val client = SessionApiClient()

            val result = client.createSession(server.port)

            val success = assertIs<ApiResult.Success<SessionApiClient.CreatedSession>>(result)
            assertTrue(success.value.sessionId.isNotBlank())
        } finally {
            environment.close()
        }
    }
}
