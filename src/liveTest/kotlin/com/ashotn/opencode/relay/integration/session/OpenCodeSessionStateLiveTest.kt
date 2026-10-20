package com.ashotn.opencode.relay.integration.session

import com.ashotn.opencode.relay.api.session.SessionApiClient
import com.ashotn.opencode.relay.api.transport.ApiResult
import com.ashotn.opencode.relay.api.transport.OpenCodeHttpTransport
import com.ashotn.opencode.relay.integration.OpenCodeTestEnvironmentFactory
import com.ashotn.opencode.relay.integration.OpenCodeTestEventCollector
import com.ashotn.opencode.relay.integration.OpenCodeTestVersions
import com.ashotn.opencode.relay.ipc.OpenCodeEvent
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.assertIs

@RunWith(Parameterized::class)
class OpenCodeSessionStateLiveTest(
    private val version: String,
) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun versions(): List<Array<String>> = OpenCodeTestVersions.all().map { arrayOf(it) }
    }

    @Test
    fun `real server emits session lifecycle and status events`() {
        OpenCodeTestEnvironmentFactory.create(version).use { environment ->
            val server = environment.startServer()
            val sessionClient = SessionApiClient(
                OpenCodeHttpTransport(authorizationHeaderProvider = environment::authorizationHeader),
            )

            OpenCodeTestEventCollector(
                port = server.port,
                directory = environment.repoRoot.toString(),
                authorizationHeaderProvider = environment::authorizationHeader,
            ).use { events ->
                try {
                    events.awaitConnected()

                    val sessionId = assertIs<ApiResult.Success<SessionApiClient.CreatedSession>>(
                        sessionClient.createSession(
                            port = server.port,
                            permissions = environment.sessionPermissions,
                            model = environment.testModel,
                        ),
                    ).value.sessionId

                    events.awaitSessionLifecycle(sessionId, atLeastCount = 1)

                    val nextBusyCount = events.sessionStatusCount(
                        sessionId,
                        OpenCodeEvent.SessionStatusType.BUSY,
                    ) + 1
                    val nextIdleCount = events.sessionStatusCount(
                        sessionId,
                        OpenCodeEvent.SessionStatusType.IDLE,
                    ) + 1

                    assertIs<ApiResult.Success<Unit>>(
                        sessionClient.promptText(
                            port = server.port,
                            sessionId = sessionId,
                            text = "Reply with exactly: pong\nDo not edit files or use tools.",
                        ),
                    )

                    events.awaitSessionStatus(
                        sessionId = sessionId,
                        status = OpenCodeEvent.SessionStatusType.BUSY,
                        atLeastCount = nextBusyCount,
                        timeoutMs = 30_000,
                    )

                    events.awaitSessionStatus(
                        sessionId = sessionId,
                        status = OpenCodeEvent.SessionStatusType.IDLE,
                        atLeastCount = nextIdleCount,
                        timeoutMs = 30_000,
                    )
                } catch (t: Throwable) {
                    t.addSuppressed(
                        IllegalStateException(
                            buildString {
                                appendLine("OpenCode session state live test failed for version $version")
                                appendLine(environment.diagnosticsSummary())
                                appendLine("recentEvents=${events.recentEventSummary()}")
                            },
                        ),
                    )
                    throw t
                }
            }
        }
    }
}
