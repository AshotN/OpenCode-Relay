package com.ashotn.opencode.api.event

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI

class EventStreamClient(
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 0,
) {
    @Volatile
    private var activeSubscription: EventSubscription? = null

    fun consume(port: Int, onLine: (String) -> Unit) {
        val subscription = subscribe(port)
        activeSubscription = subscription

        try {
            subscription.reader.use { reader ->
                reader.forEachLine { line ->
                    onLine(line)
                }
            }
        } finally {
            subscription.close()
            activeSubscription = null
        }
    }

    fun disconnect() {
        activeSubscription?.close()
        activeSubscription = null
    }

    private interface EventSubscription : AutoCloseable {
        val reader: BufferedReader
    }

    private fun subscribe(port: Int): EventSubscription {
        val connection = URI("http://localhost:$port/event").toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "text/event-stream")
        connection.setRequestProperty("Cache-Control", "no-cache")
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.connect()

        val statusCode = connection.responseCode
        if (statusCode !in 200..299) {
            val errorBody = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            connection.disconnect()
            throw IOException("SSE stream failed HTTP $statusCode body=${errorBody?.take(200)}")
        }

        val contentType = connection.contentType?.lowercase() ?: ""
        if (!contentType.startsWith("text/event-stream")) {
            connection.disconnect()
            throw IOException("SSE stream has unexpected content type: $contentType")
        }

        val reader = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8))

        return object : EventSubscription {
            override val reader: BufferedReader = reader

            override fun close() {
                runCatching { reader.close() }
                connection.disconnect()
            }
        }
    }
}
