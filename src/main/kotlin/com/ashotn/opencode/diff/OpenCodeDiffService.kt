package com.ashotn.opencode.diff

import com.ashotn.opencode.ipc.FileDiff
import com.ashotn.opencode.ipc.OpenCodeEvent
import com.ashotn.opencode.ipc.SessionDiffStatus
import com.ashotn.opencode.ipc.SseClient
import com.ashotn.opencode.permission.OpenCodePermissionService
import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Project-level service that owns the SSE connection to the OpenCode server
 * and maintains AI diff hunks by session.
 */
@Service(Service.Level.PROJECT)
class OpenCodeDiffService(private val project: Project) : Disposable {

    private val log = logger<OpenCodeDiffService>()

    /** sessionId -> (absoluteFilePath -> hunks) */
    private val hunksBySessionAndFile = ConcurrentHashMap<String, Map<String, List<DiffHunk>>>()

    /** sessionId -> deleted absolute file paths */
    private val deletedBySession = ConcurrentHashMap<String, Set<String>>()

    /** sessionId -> newly added absolute file paths */
    private val addedBySession = ConcurrentHashMap<String, Set<String>>()

    /** sessionId -> baseline content (`before`) per absolute file path */
    private val baselineBeforeBySessionAndFile = ConcurrentHashMap<String, Map<String, String>>()

    /** latest session we should render in the panel */
    @Volatile private var latestSessionId: String? = null

    /** Monotonic counter used to ignore out-of-order async session.diff applications. */
    private val diffApplyRevision = AtomicLong(0)

    /** Guards map updates that must commit atomically. */
    private val stateLock = Any()

    private val permissionService = OpenCodePermissionService.getInstance(project)

    private var sseClient: SseClient? = null

    fun startListening(port: Int) {
        stopListening()
        permissionService.setPort(port)
        log.info("OpenCodeDiffService: starting SSE listener on port $port")
        EditorDiffRenderer.getInstance(project)
        sseClient = SseClient(port) { event -> handleEvent(event) }.also { it.start() }
    }

    fun stopListening() {
        sseClient?.stop()
        sseClient = null
    }

    private fun handleEvent(event: OpenCodeEvent) {
        when (event) {
            is OpenCodeEvent.SessionDiff -> handleSessionDiff(event)
            is OpenCodeEvent.SessionBusy -> {
                log.debug("OpenCodeDiffService: session busy ${event.sessionId}")
            }
            is OpenCodeEvent.SessionIdle -> {
                cleanupInactiveSession(event.sessionId)
            }
            is OpenCodeEvent.TurnPatch -> {
            }
            is OpenCodeEvent.PermissionAsked -> permissionService.handlePermissionAsked(event)
            is OpenCodeEvent.PermissionReplied -> permissionService.handlePermissionReplied(event)
            is OpenCodeEvent.FileEdited -> {
            }
            is OpenCodeEvent.FileDeleted -> {
            }
        }
    }

    private fun cleanupInactiveSession(sessionId: String) {
        if (sessionId == latestSessionId) return
        synchronized(stateLock) {
            hunksBySessionAndFile.remove(sessionId)
            deletedBySession.remove(sessionId)
            addedBySession.remove(sessionId)
            baselineBeforeBySessionAndFile.remove(sessionId)
        }
    }

    private fun handleSessionDiff(event: OpenCodeEvent.SessionDiff) {
        val projectBase = project.basePath ?: return
        val revision = diffApplyRevision.incrementAndGet()

        ApplicationManager.getApplication().executeOnPooledThread {
            val newHunksByFile = HashMap<String, List<DiffHunk>>()
            val newDeleted = HashSet<String>()
            val newAdded = HashSet<String>()
            val newBaselineByFile = HashMap<String, String>()

            log.debug(
                "OpenCodeDiffService: apply session.diff session=${event.sessionId} revision=$revision files=${event.files.size}",
            )

            for (diffFile in event.files) {
                val absPath = "$projectBase/${diffFile.file}"
                refreshVfs(absPath, wasDeleted = diffFile.status == SessionDiffStatus.DELETED)
                reloadOpenDocument(absPath)

                val actualAfter = readCurrentContent(absPath)
                val hasContentChange = normalizeContent(diffFile.before) != normalizeContent(actualAfter)

                log.debug(
                    "OpenCodeDiffService: file=${diffFile.file} status=${diffFile.status} beforeEqDisk=${!hasContentChange} additions=${diffFile.additions} deletions=${diffFile.deletions}",
                )

                if (!hasContentChange) {
                    log.debug("OpenCodeDiffService: skip baseline-matching file=${diffFile.file}")
                    continue
                }

                val fileDiff = FileDiff(
                    file = absPath,
                    before = diffFile.before,
                    after = actualAfter,
                    additions = diffFile.additions,
                    deletions = diffFile.deletions,
                )
                val hunks = computeHunks(fileDiff, event.sessionId)
                log.debug("OpenCodeDiffService: file=${diffFile.file} hunks=${hunks.size}")
                if (hunks.isEmpty()) {
                    log.debug("OpenCodeDiffService: skip zero-hunk file=${diffFile.file}")
                    continue
                }

                newHunksByFile[absPath] = hunks
                newBaselineByFile[absPath] = diffFile.before
                if (actualAfter.isEmpty()) newDeleted.add(absPath)
                if (diffFile.status == SessionDiffStatus.ADDED && actualAfter.isNotEmpty()) newAdded.add(absPath)
            }

            val removedFiles: Set<String>
            val changedFiles: Set<String>

            synchronized(stateLock) {
                if (revision != diffApplyRevision.get()) {
                    log.debug("OpenCodeDiffService: skipping stale session.diff apply revision=$revision")
                    return@executeOnPooledThread
                }

                val previousSessionId = latestSessionId
                val previousVisibleFiles = previousSessionId
                    ?.let { hunksBySessionAndFile[it]?.keys?.toSet() }
                    ?: emptySet()

                latestSessionId = event.sessionId
                evictOtherSessionsLocked(event.sessionId)

                hunksBySessionAndFile[event.sessionId] = newHunksByFile
                deletedBySession[event.sessionId] = newDeleted
                addedBySession[event.sessionId] = newAdded
                baselineBeforeBySessionAndFile[event.sessionId] = newBaselineByFile

                val newVisibleFiles = newHunksByFile.keys.toSet()
                removedFiles = previousVisibleFiles - newVisibleFiles
                changedFiles = previousVisibleFiles + newVisibleFiles
            }

            log.debug(
                "OpenCodeDiffService: applied session=${event.sessionId} trackedFiles=${newHunksByFile.size} deletedFiles=${newDeleted.size} addedFiles=${newAdded.size}",
            )

            removedFiles.forEach {
                refreshVfs(it, wasDeleted = false)
                reloadOpenDocument(it)
            }
            changedFiles.forEach { publishChanged(it) }

            scheduleReconcile(event.sessionId, revision)
        }
    }

    private fun evictOtherSessionsLocked(activeSessionId: String) {
        val staleSessionIds = hunksBySessionAndFile.keys.filter { it != activeSessionId }
        staleSessionIds.forEach { sessionId ->
            hunksBySessionAndFile.remove(sessionId)
            deletedBySession.remove(sessionId)
            addedBySession.remove(sessionId)
            baselineBeforeBySessionAndFile.remove(sessionId)
        }
    }

    private fun scheduleReconcile(sessionId: String, revision: Long) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                Thread.sleep(250)
            } catch (_: InterruptedException) {
                return@executeOnPooledThread
            }

            if (revision != diffApplyRevision.get()) return@executeOnPooledThread
            reconcileSessionState(sessionId, revision)
        }
    }

    private fun reconcileSessionState(sessionId: String, revision: Long) {
        val currentHunks = hunksBySessionAndFile[sessionId] ?: return
        val currentDeleted = deletedBySession[sessionId] ?: emptySet()
        val currentAdded = addedBySession[sessionId] ?: emptySet()
        val currentBaselines = baselineBeforeBySessionAndFile[sessionId] ?: emptyMap()

        if (currentHunks.isEmpty()) return

        val updatedHunks = currentHunks.toMutableMap()
        val updatedDeleted = currentDeleted.toMutableSet()
        val updatedAdded = currentAdded.toMutableSet()
        val removedPaths = mutableListOf<String>()

        for ((absPath, _) in currentHunks) {
            val baseline = currentBaselines[absPath] ?: continue
            val diskContent = readCurrentContent(absPath)
            val matchesBaseline = normalizeContent(diskContent) == normalizeContent(baseline)
            if (matchesBaseline) {
                updatedHunks.remove(absPath)
                updatedDeleted.remove(absPath)
                updatedAdded.remove(absPath)
                removedPaths.add(absPath)
                log.debug("OpenCodeDiffService: reconcile removed baseline-matching file=$absPath")
            }
        }

        if (removedPaths.isEmpty()) return

        synchronized(stateLock) {
            if (revision != diffApplyRevision.get()) return
            if (latestSessionId != sessionId) return

            hunksBySessionAndFile[sessionId] = updatedHunks
            deletedBySession[sessionId] = updatedDeleted
            addedBySession[sessionId] = updatedAdded
            baselineBeforeBySessionAndFile[sessionId] = currentBaselines.filterKeys { it in updatedHunks.keys }
        }

        removedPaths.forEach { publishChanged(it) }
    }

    private fun refreshVfs(absPath: String, wasDeleted: Boolean) {
        val lfs = LocalFileSystem.getInstance()
        val fileOnDisk = java.io.File(absPath)

        if (wasDeleted) {
            val parentPath = fileOnDisk.parent ?: return
            val parentVFile = lfs.refreshAndFindFileByPath(parentPath) ?: lfs.findFileByPath(parentPath) ?: return
            parentVFile.refresh(false, true)
            return
        }

        val vFile = lfs.refreshAndFindFileByPath(absPath) ?: lfs.findFileByPath(absPath)
        if (vFile != null) {
            vFile.refresh(false, false)
            return
        }

        var ancestor = fileOnDisk.parentFile
        var ancestorVFile = null as com.intellij.openapi.vfs.VirtualFile?
        while (ancestor != null) {
            ancestorVFile = lfs.refreshAndFindFileByPath(ancestor.path) ?: lfs.findFileByPath(ancestor.path)
            if (ancestorVFile != null) break
            ancestor = ancestor.parentFile
        }
        ancestorVFile?.refresh(false, true)
    }

    private fun reloadOpenDocument(absPath: String) {
        val vFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(absPath)
            ?: LocalFileSystem.getInstance().findFileByPath(absPath)
            ?: return

        val fileDocumentManager = FileDocumentManager.getInstance()
        val document = fileDocumentManager.getCachedDocument(vFile) ?: return

        val reloadAction = {
            if (!fileDocumentManager.isDocumentUnsaved(document)) {
                fileDocumentManager.reloadFromDisk(document)
            }
        }

        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) {
            reloadAction()
        } else {
            app.invokeAndWait(reloadAction)
        }
    }

    private fun readCurrentContent(absPath: String): String {
        val file = java.io.File(absPath)
        if (!file.exists()) return ""
        return try {
            file.readText()
        } catch (_: Exception) {
            ""
        }
    }

    private fun normalizeContent(content: String): String =
        content.replace("\r\n", "\n")

    private fun computeHunks(fileDiff: FileDiff, sessionId: String): List<DiffHunk> {
        val beforeLines = fileDiff.before.lines()
        val afterLines = fileDiff.after.lines()
        return try {
            val changes = ComparisonManager.getInstance()
                .compareLines(fileDiff.before, fileDiff.after, ComparisonPolicy.DEFAULT, EmptyProgressIndicator())
            changes.map { change ->
                DiffHunk(
                    filePath = fileDiff.file,
                    startLine = change.startLine2,
                    removedLines = beforeLines.subList(change.startLine1, change.endLine1.coerceAtMost(beforeLines.size)),
                    addedLines = afterLines.subList(change.startLine2, change.endLine2.coerceAtMost(afterLines.size)),
                    sessionId = sessionId,
                    messageId = sessionId,
                )
            }
        } catch (e: Exception) {
            log.warn("OpenCodeDiffService: failed to compute hunks for ${fileDiff.file}", e)
            emptyList()
        }
    }

    fun getHunks(filePath: String): List<DiffHunk> {
        val sessionId = latestSessionId ?: return emptyList()
        return hunksBySessionAndFile[sessionId]?.get(filePath) ?: emptyList()
    }

    fun hasPendingHunks(filePath: String): Boolean =
        getHunks(filePath).any { it.state == HunkState.PENDING }

    fun isDeleted(filePath: String): Boolean {
        val sessionId = latestSessionId ?: return false
        return deletedBySession[sessionId]?.contains(filePath) == true
    }

    fun isAdded(filePath: String): Boolean {
        val sessionId = latestSessionId ?: return false
        return addedBySession[sessionId]?.contains(filePath) == true
    }

    fun allTrackedFiles(): Set<String> {
        val sessionId = latestSessionId ?: return emptySet()
        return hunksBySessionAndFile[sessionId]?.keys?.toSet() ?: emptySet()
    }

    fun clearAll() {
        diffApplyRevision.incrementAndGet()
        val files = hunksBySessionAndFile.values.flatMap { it.keys }.toSet()
        synchronized(stateLock) {
            hunksBySessionAndFile.clear()
            deletedBySession.clear()
            addedBySession.clear()
            baselineBeforeBySessionAndFile.clear()
            latestSessionId = null
        }
        files.forEach { publishChanged(it) }
    }

    private fun publishChanged(filePath: String) {
        project.messageBus
            .syncPublisher(DiffHunksChangedListener.TOPIC)
            .onHunksChanged(filePath)
    }

    override fun dispose() {
        stopListening()
        diffApplyRevision.incrementAndGet()
        synchronized(stateLock) {
            hunksBySessionAndFile.clear()
            deletedBySession.clear()
            addedBySession.clear()
            baselineBeforeBySessionAndFile.clear()
            latestSessionId = null
        }
    }

    companion object {
        fun getInstance(project: Project): OpenCodeDiffService =
            project.getService(OpenCodeDiffService::class.java)
    }
}
