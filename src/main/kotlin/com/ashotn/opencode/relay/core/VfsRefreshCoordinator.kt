package com.ashotn.opencode.relay.core

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.util.concurrency.AppExecutorUtil
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal class VfsRefreshCoordinator(
    private val debounceMillis: Long = 150L,
) : Disposable {
    private val log = logger<VfsRefreshCoordinator>()
    private val pendingByPath = ConcurrentHashMap<String, Boolean>()
    private val scheduleLock = Any()
    private val disposed = AtomicBoolean(false)
    private val batchesExecuted = AtomicLong(0)
    private val skippedRedundantRequests = AtomicLong(0)

    @Volatile
    private var scheduledFlush: ScheduledFuture<*>? = null

    fun enqueue(path: String, recursive: Boolean) {
        if (path.isBlank() || disposed.get()) return

        val normalizedPath = File(path).absolutePath
        val previous = pendingByPath.putIfAbsent(normalizedPath, recursive)
        if (previous != null) {
            if (recursive && !previous) {
                pendingByPath.replace(normalizedPath, false, true)
            } else {
                skippedRedundantRequests.incrementAndGet()
            }
        }

        ensureScheduled()
    }

    fun flushNow(trigger: String) {
        flushInternal(trigger)
    }

    fun clearPending() {
        synchronized(scheduleLock) {
            scheduledFlush?.cancel(false)
            scheduledFlush = null
        }
        pendingByPath.clear()
    }

    override fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        clearPending()
    }

    private fun ensureScheduled() {
        synchronized(scheduleLock) {
            if (disposed.get()) return
            val active = scheduledFlush?.takeUnless { it.isDone || it.isCancelled }
            if (active != null) return

            scheduledFlush = AppExecutorUtil.getAppScheduledExecutorService().schedule(
                { flushInternal("debounced") },
                debounceMillis,
                TimeUnit.MILLISECONDS,
            )
        }
    }

    private fun flushInternal(trigger: String) {
        if (disposed.get()) return

        synchronized(scheduleLock) {
            scheduledFlush?.cancel(false)
            scheduledFlush = null
        }

        if (pendingByPath.isEmpty()) return

        val snapshot = LinkedHashMap(pendingByPath)
        snapshot.forEach { (path, recursive) ->
            pendingByPath.remove(path, recursive)
        }
        if (snapshot.isEmpty()) return

        val recursiveIoFiles = snapshot.entries
            .asSequence()
            .filter { it.value }
            .map { File(it.key) }
            .toList()
        val nonRecursiveIoFiles = snapshot.entries
            .asSequence()
            .filterNot { it.value }
            .map { File(it.key) }
            .toList()

        val requestedCount = recursiveIoFiles.size + nonRecursiveIoFiles.size
        val skippedCount = skippedRedundantRequests.get()

        if (recursiveIoFiles.isNotEmpty()) {
            refreshBatch(
                ioFiles = recursiveIoFiles,
                recursive = true,
                requestedCount = requestedCount,
                skippedCount = skippedCount,
                trigger = trigger,
            )
        }

        if (nonRecursiveIoFiles.isNotEmpty()) {
            refreshBatch(
                ioFiles = nonRecursiveIoFiles,
                recursive = false,
                requestedCount = requestedCount,
                skippedCount = skippedCount,
                trigger = trigger,
            )
        }
    }

    private fun refreshBatch(
        ioFiles: Collection<File>,
        recursive: Boolean,
        requestedCount: Int,
        skippedCount: Long,
        trigger: String,
    ) {
        if (ioFiles.isEmpty() || disposed.get()) return

        val refreshableFiles = ioFiles.filter { file ->
            file.exists() || LocalFileSystem.getInstance().findFileByIoFile(file) != null
        }
        if (refreshableFiles.isEmpty()) {
            log.debug(
                "VfsRefreshCoordinator: batchSkippedNoRefreshableFiles trigger=$trigger recursive=$recursive requestedPathCount=$requestedCount dedupedPathCount=${ioFiles.size} skippedRedundantRequests=$skippedCount",
            )
            return
        }

        val startedAtNanos = System.nanoTime()
        val batchIndex = batchesExecuted.incrementAndGet()
        LocalFileSystem.getInstance().refreshIoFiles(
            refreshableFiles,
            true,
            recursive,
        ) {
            val durationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos)
            log.debug(
                "VfsRefreshCoordinator: batchCompleted index=$batchIndex trigger=$trigger recursive=$recursive requestedPathCount=$requestedCount dedupedPathCount=${ioFiles.size} refreshablePathCount=${refreshableFiles.size} skippedRedundantRequests=$skippedCount durationMs=$durationMillis",
            )
        }
    }
}