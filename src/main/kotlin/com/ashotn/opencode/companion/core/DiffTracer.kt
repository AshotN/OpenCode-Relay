package com.ashotn.opencode.companion.core

import com.google.gson.GsonBuilder
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.BufferedWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

internal interface DiffTracer {
    val enabled: Boolean
    val includeHistory: Boolean

    fun record(kind: String, fields: Map<String, Any?> = emptyMap())

    fun close() {}

    companion object {
        fun fromSettings(
            project: Project,
            logger: Logger,
            traceEnabled: Boolean,
            includeHistory: Boolean,
        ): DiffTracer {
            if (!traceEnabled) return NoOpDiffTracer
            return JsonlDiffTracer(project, logger, includeHistory)
        }


    }
}

internal object NoOpDiffTracer : DiffTracer {
    override val enabled: Boolean = false
    override val includeHistory: Boolean = false

    override fun record(kind: String, fields: Map<String, Any?>) = Unit
}

internal class JsonlDiffTracer(
    private val project: Project,
    private val logger: Logger,
    override val includeHistory: Boolean = false,
) : DiffTracer {
    override val enabled: Boolean = true

    private val gson = GsonBuilder().disableHtmlEscaping().create()
    private val seq = AtomicLong(0L)
    private val writeLock = Any()

    @Volatile
    private var writer: BufferedWriter? = null

    @Volatile
    private var tracePath: Path? = null

    override fun record(kind: String, fields: Map<String, Any?>) {
        try {
            val currentWriter = ensureWriter() ?: return
            val payload = linkedMapOf<String, Any?>(
                "seq" to seq.incrementAndGet(),
                "ts" to Instant.now().toString(),
                "kind" to kind,
                "fields" to fields,
            )
            val json = gson.toJson(payload)
            synchronized(writeLock) {
                currentWriter.write(json)
                currentWriter.newLine()
                currentWriter.flush()
            }
        } catch (t: Throwable) {
            logger.debug("JsonlDiffTracer: failed to write trace event", t)
        }
    }

    override fun close() {
        synchronized(writeLock) {
            runCatching { writer?.close() }
            writer = null
        }
    }

    fun traceFilePath(): String? = tracePath?.toString()

    private fun ensureWriter(): BufferedWriter? {
        writer?.let { return it }

        synchronized(writeLock) {
            writer?.let { return it }

            val traceDir = Path.of(System.getProperty("java.io.tmpdir"), "opencode-diff-traces")
            runCatching { Files.createDirectories(traceDir) }
                .onFailure {
                    logger.warn("JsonlDiffTracer: failed to create trace directory: $traceDir", it)
                    return null
                }

            val projectName = project.name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val filePath = traceDir.resolve("diff-trace-$projectName-${System.currentTimeMillis()}.jsonl")
            val createdWriter = runCatching {
                Files.newBufferedWriter(
                    filePath,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                    StandardOpenOption.WRITE,
                )
            }.getOrElse {
                logger.warn("JsonlDiffTracer: failed to open trace file: $filePath", it)
                return null
            }

            writer = createdWriter
            tracePath = filePath
            logger.info("JsonlDiffTracer: tracing enabled file=$filePath")
            return createdWriter
        }
    }
}