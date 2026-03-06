package com.ashotn.opencode.diff

import com.ashotn.opencode.ipc.FileDiff
import com.ashotn.opencode.ipc.OpenCodeEvent
import com.ashotn.opencode.ipc.SessionDiffStatus
import com.ashotn.opencode.ipc.SseClient
import com.ashotn.opencode.permission.OpenCodePermissionService
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Project-level service that owns the SSE connection to the OpenCode server
 * and maintains AI diff hunks by session.
 */
@Service(Service.Level.PROJECT)
class OpenCodeDiffService(private val project: Project) : Disposable {

    enum class SessionScope {
        SELECTED,
        FAMILY,
        ALL,
    }

    enum class ViewMode {
        SELECTED_SESSION,
        UNIFIED_FAMILY,
    }

    data class SessionInfo(
        val sessionId: String,
        val parentSessionId: String?,
        val title: String?,
        val description: String?,
        val isBusy: Boolean,
        val trackedFileCount: Int,
        val updatedAtMillis: Long,
    )

    data class FileDiffPreview(
        val filePath: String,
        val sessionId: String,
        val before: String,
        val after: String,
    )

    private val log = logger<OpenCodeDiffService>()

    /** sessionId -> (absoluteFilePath -> hunks), includes historical and live views. */
    private val hunksBySessionAndFile = ConcurrentHashMap<String, Map<String, List<DiffHunk>>>()

    /** sessionId -> live last-turn hunks used for inline editor highlighting. */
    private val liveHunksBySessionAndFile = ConcurrentHashMap<String, Map<String, List<DiffHunk>>>()

    /** sessionId -> deleted absolute file paths */
    private val deletedBySession = ConcurrentHashMap<String, Set<String>>()

    /** sessionId -> newly added absolute file paths */
    private val addedBySession = ConcurrentHashMap<String, Set<String>>()

    /** sessionId -> baseline content (`before`) per absolute file path */
    private val baselineBeforeBySessionAndFile = ConcurrentHashMap<String, Map<String, String>>()

    /** sessionId -> latest known `after` by absolute file path (for turn deltas). */
    private val lastAfterBySessionAndFile = ConcurrentHashMap<String, MutableMap<String, String>>()

    /** sessionId -> files touched by latest turn patch (absolute paths). */
    private val pendingTurnFilesBySession = ConcurrentHashMap<String, Set<String>>()

    /** childSessionId -> parentSessionId */
    private val parentBySessionId = ConcurrentHashMap<String, String>()

    /** Session IDs discovered from /session list, including root sessions. */
    private val hierarchySessionIds = ConcurrentHashMap.newKeySet<String>()

    /** Explicitly selected session for compatibility APIs. */
    @Volatile private var selectedSessionId: String? = null

    /** Current view mode for compatibility APIs. */
    @Volatile private var viewMode: ViewMode = ViewMode.UNIFIED_FAMILY

    /** Last session observed in activity events (busy/diff/idle). */
    @Volatile private var latestActiveSessionId: String? = null

    /** Per-session monotonic revisions to ignore stale async applies. */
    private val diffApplyRevisionBySession = ConcurrentHashMap<String, Long>()

    /** sessionId -> isBusy */
    private val busyBySession = ConcurrentHashMap<String, Boolean>()

    /** sessionId -> last touched time (for listing) */
    private val updatedAtBySession = ConcurrentHashMap<String, Long>()

    /** sessionId -> title/summary metadata from /session. */
    private val sessionTitleById = ConcurrentHashMap<String, String>()
    private val sessionDescriptionById = ConcurrentHashMap<String, String>()

    /** Guards map updates that must commit atomically. */
    private val stateLock = Any()

    private val permissionService = OpenCodePermissionService.getInstance(project)
    private val documentSyncService = DocumentSyncService()
    private val sessionApiClient = SessionApiClient()
    private val hunkComputer = DiffHunkComputer(log)
    private val publishService = DiffPublishService(project)
    private val scopeResolver = SessionScopeResolver()
    private val hierarchyRefreshInFlight = AtomicBoolean(false)
    private val historicalDiffLoadInFlight = ConcurrentHashMap.newKeySet<String>()
    private val historicalDiffLoadedSessions = ConcurrentHashMap.newKeySet<String>()
    private val lifecycleGeneration = AtomicLong(0L)

    @Volatile private var port: Int = 0

    private var sseClient: SseClient? = null

    fun startListening(port: Int) {
        stopListening()
        val generation = lifecycleGeneration.incrementAndGet()
        this.port = port
        permissionService.setPort(port)
        log.info("OpenCodeDiffService: starting SSE listener on port $port")
        EditorDiffRenderer.getInstance(project)
        sseClient = SseClient(port) { event -> handleEvent(event, generation) }.also { it.start() }
        refreshSessionHierarchyAsync(generation)
    }

    fun stopListening() {
        sseClient?.stop()
        sseClient = null
    }

    private fun handleEvent(event: OpenCodeEvent, generation: Long) {
        if (generation != lifecycleGeneration.get()) return
        when (event) {
            is OpenCodeEvent.SessionDiff -> handleSessionDiff(event, fromHistory = false, generation = generation)
            is OpenCodeEvent.SessionBusy -> {
                markSessionBusy(event.sessionId, true, generation)
                log.debug("OpenCodeDiffService: session busy ${event.sessionId}")
            }
            is OpenCodeEvent.SessionIdle -> {
                markSessionBusy(event.sessionId, false, generation)
                log.debug("OpenCodeDiffService: session idle ${event.sessionId}")
            }
            is OpenCodeEvent.TurnPatch -> {
                val projectBase = project.basePath ?: return
                val touchedPaths = event.files
                    .map { path -> DiffTextUtil.toAbsolutePath(projectBase, path) }
                    .toSet()
                pendingTurnFilesBySession[event.sessionId] = touchedPaths
                log.debug("OpenCodeDiffService: stashed turn patch session=${event.sessionId} files=${touchedPaths.size}")
            }
            is OpenCodeEvent.PermissionAsked -> permissionService.handlePermissionAsked(event)
            is OpenCodeEvent.PermissionReplied -> permissionService.handlePermissionReplied(event)
            is OpenCodeEvent.FileEdited -> {
            }
            is OpenCodeEvent.FileDeleted -> {
            }
        }
    }

    private fun markSessionBusy(sessionId: String, isBusy: Boolean, generation: Long) {
        synchronized(stateLock) {
            busyBySession[sessionId] = isBusy
            updatedAtBySession[sessionId] = System.currentTimeMillis()
            latestActiveSessionId = sessionId
            if (selectedSessionId == null && sessionExistsLocked(sessionId)) {
                selectedSessionId = sessionId
            }
        }
        if (generation != lifecycleGeneration.get()) return
        publishService.publishSessionStateChanged()
        refreshSessionHierarchyAsync(generation)
    }

    private fun handleSessionDiff(event: OpenCodeEvent.SessionDiff, fromHistory: Boolean, generation: Long) {
        val projectBase = project.basePath ?: return

        val turnScope: Set<String>? = if (!fromHistory) {
            pendingTurnFilesBySession.remove(event.sessionId)
        } else {
            null
        }

        if (!fromHistory && turnScope == null) {
            log.debug("OpenCodeDiffService: ignoring unscoped live session.diff for ${event.sessionId}")
            return
        }

        val revision = nextSessionRevision(event.sessionId)

        ApplicationManager.getApplication().executeOnPooledThread {
            if (generation != lifecycleGeneration.get()) return@executeOnPooledThread

            val newHunksByFile = HashMap<String, List<DiffHunk>>()
            val newDeleted = HashSet<String>()
            val newAdded = HashSet<String>()
            val newBaselineByFile = HashMap<String, String>()

            log.debug(
                "OpenCodeDiffService: apply session.diff session=${event.sessionId} revision=$revision files=${event.files.size} fromHistory=$fromHistory",
            )

            val previousAfterByFile = lastAfterBySessionAndFile[event.sessionId]?.toMap() ?: emptyMap()
            val nextAfterByFile = previousAfterByFile.toMutableMap()

            for (diffFile in event.files) {
                val absPath = DiffTextUtil.toAbsolutePath(projectBase, diffFile.file)

                if (!fromHistory && turnScope != null && absPath !in turnScope) {
                    continue
                }

                documentSyncService.refreshVfs(absPath, wasDeleted = diffFile.status == SessionDiffStatus.DELETED)
                documentSyncService.reloadOpenDocument(absPath)

                val actualAfter = documentSyncService.readCurrentContent(absPath)
                val effectiveBefore = if (fromHistory) {
                    diffFile.before
                } else {
                    previousAfterByFile[absPath] ?: diffFile.before
                }

                val hasContentChange =
                    DiffTextUtil.normalizeContent(effectiveBefore) != DiffTextUtil.normalizeContent(actualAfter)

                log.debug(
                    "OpenCodeDiffService: file=${diffFile.file} status=${diffFile.status} beforeEqDisk=${!hasContentChange} additions=${diffFile.additions} deletions=${diffFile.deletions}",
                )

                nextAfterByFile[absPath] = actualAfter

                if (!hasContentChange) {
                    log.debug("OpenCodeDiffService: skip baseline-matching file=${diffFile.file}")
                    continue
                }

                val fileDiff = FileDiff(
                    file = absPath,
                    before = effectiveBefore,
                    after = actualAfter,
                    additions = diffFile.additions,
                    deletions = diffFile.deletions,
                )
                val hunks = hunkComputer.compute(fileDiff, event.sessionId)
                log.debug("OpenCodeDiffService: file=${diffFile.file} hunks=${hunks.size}")
                if (hunks.isEmpty()) {
                    log.debug("OpenCodeDiffService: skip zero-hunk file=${diffFile.file}")
                    continue
                }

                newHunksByFile[absPath] = hunks
                newBaselineByFile[absPath] = effectiveBefore
                if (actualAfter.isEmpty()) newDeleted.add(absPath)
                if (diffFile.status == SessionDiffStatus.ADDED && actualAfter.isNotEmpty()) newAdded.add(absPath)
            }

            val removedFiles: Set<String>
            val changedFiles: Set<String>

            synchronized(stateLock) {
                if (generation != lifecycleGeneration.get()) return@executeOnPooledThread
                if (revision != currentSessionRevision(event.sessionId)) {
                    log.debug("OpenCodeDiffService: skipping stale session.diff apply revision=$revision")
                    return@executeOnPooledThread
                }

                lastAfterBySessionAndFile[event.sessionId] = nextAfterByFile

                val previousSessionFiles = hunksBySessionAndFile[event.sessionId]?.keys?.toSet() ?: emptySet()

                if (!fromHistory) {
                    latestActiveSessionId = event.sessionId
                    busyBySession[event.sessionId] = true
                    updatedAtBySession[event.sessionId] = System.currentTimeMillis()
                    if (selectedSessionId == null) {
                        selectedSessionId = event.sessionId
                    }
                }

                hunksBySessionAndFile[event.sessionId] = newHunksByFile
                if (fromHistory) {
                    liveHunksBySessionAndFile[event.sessionId] = emptyMap()
                } else {
                    liveHunksBySessionAndFile[event.sessionId] = newHunksByFile
                }
                deletedBySession[event.sessionId] = newDeleted
                addedBySession[event.sessionId] = newAdded
                baselineBeforeBySessionAndFile[event.sessionId] = newBaselineByFile
                historicalDiffLoadedSessions.add(event.sessionId)

                val newSessionFiles = newHunksByFile.keys.toSet()
                removedFiles = previousSessionFiles - newSessionFiles
                changedFiles = previousSessionFiles + newSessionFiles
            }

            log.debug(
                "OpenCodeDiffService: applied session=${event.sessionId} trackedFiles=${newHunksByFile.size} deletedFiles=${newDeleted.size} addedFiles=${newAdded.size}",
            )

            if (generation != lifecycleGeneration.get()) return@executeOnPooledThread

            removedFiles.forEach {
                documentSyncService.refreshVfs(it, wasDeleted = false)
                documentSyncService.reloadOpenDocument(it)
            }
            changedFiles.forEach { publishService.publishChanged(it) }
            publishService.publishSessionStateChanged()
            if (!fromHistory) {
                refreshSessionHierarchyAsync(generation)
            }

            scheduleReconcile(event.sessionId, revision, generation)
        }
    }

    private fun scheduleReconcile(sessionId: String, revision: Long, generation: Long) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                Thread.sleep(250)
            } catch (_: InterruptedException) {
                return@executeOnPooledThread
            }

            if (generation != lifecycleGeneration.get()) return@executeOnPooledThread
            if (revision != currentSessionRevision(sessionId)) return@executeOnPooledThread
            reconcileSessionState(sessionId, revision, generation)
        }
    }

    private fun reconcileSessionState(sessionId: String, revision: Long, generation: Long) {
        if (generation != lifecycleGeneration.get()) return

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
            val diskContent = documentSyncService.readCurrentContent(absPath)
            val matchesBaseline =
                DiffTextUtil.normalizeContent(diskContent) == DiffTextUtil.normalizeContent(baseline)
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
            if (generation != lifecycleGeneration.get()) return
            if (revision != currentSessionRevision(sessionId)) return

            hunksBySessionAndFile[sessionId] = updatedHunks
            liveHunksBySessionAndFile[sessionId] =
                (liveHunksBySessionAndFile[sessionId] ?: emptyMap())
                    .filterKeys { it in updatedHunks.keys }
            deletedBySession[sessionId] = updatedDeleted
            addedBySession[sessionId] = updatedAdded
            baselineBeforeBySessionAndFile[sessionId] = currentBaselines.filterKeys { it in updatedHunks.keys }
            updatedAtBySession[sessionId] = System.currentTimeMillis()
        }

        if (generation != lifecycleGeneration.get()) return
        removedPaths.forEach { publishService.publishChanged(it) }
        publishService.publishSessionStateChanged()
    }

    private fun nextSessionRevision(sessionId: String): Long = synchronized(stateLock) {
        val nextRevision = (diffApplyRevisionBySession[sessionId] ?: 0L) + 1
        diffApplyRevisionBySession[sessionId] = nextRevision
        nextRevision
    }

    private fun currentSessionRevision(sessionId: String): Long = synchronized(stateLock) {
        diffApplyRevisionBySession[sessionId] ?: 0L
    }

    private fun knownSessionIdsLocked(): Set<String> =
        scopeResolver.knownSessionIds(
            hunksBySessionAndFile = hunksBySessionAndFile,
            busyBySession = busyBySession,
            updatedAtBySession = updatedAtBySession,
            hierarchySessionIds = hierarchySessionIds,
            parentBySessionId = parentBySessionId,
        )

    private fun sessionExistsLocked(sessionId: String): Boolean =
        knownSessionIdsLocked().contains(sessionId)

    private fun resolveSelectedSessionIdLocked(): String? {
        val resolved = scopeResolver.resolveSelectedSessionId(
            selectedSessionId = selectedSessionId,
            latestActiveSessionId = latestActiveSessionId,
            knownSessionIds = knownSessionIdsLocked(),
            updatedAtBySession = updatedAtBySession,
        )
        selectedSessionId = resolved
        return resolved
    }

    private fun knownFamilySessionIdsForRootLocked(rootSessionId: String): Set<String> =
        scopeResolver.knownFamilySessionIdsForRoot(rootSessionId, parentBySessionId)

    private fun familySessionIdsLocked(): Set<String> {
        val selected = resolveSelectedSessionIdLocked() ?: return emptySet()
        return scopeResolver.familySessionIds(
            selectedSessionId = selected,
            parentBySessionId = parentBySessionId,
            knownSessionIds = knownSessionIdsLocked(),
            busyBySession = busyBySession,
            updatedAtBySession = updatedAtBySession,
            hunksBySessionAndFile = hunksBySessionAndFile,
            nowMillis = System.currentTimeMillis(),
        )
    }

    private fun visibleFilesLocked(scope: SessionScope): Set<String> = when (scope) {
        SessionScope.SELECTED -> {
            val sessionId = resolveSelectedSessionIdLocked() ?: return emptySet()
            hunksBySessionAndFile[sessionId]?.keys?.toSet() ?: emptySet()
        }
        SessionScope.FAMILY -> {
            familySessionIdsLocked()
                .flatMap { sessionId -> hunksBySessionAndFile[sessionId]?.keys ?: emptySet() }
                .toSet()
        }
        SessionScope.ALL -> hunksBySessionAndFile.values.flatMap { it.keys }.toSet()
    }

    private fun visibleFilesForCurrentViewLocked(): Set<String> =
        visibleFilesLocked(currentScopeLocked())

    private fun liveVisibleFilesLocked(scope: SessionScope): Set<String> = when (scope) {
        SessionScope.SELECTED -> {
            val sessionId = resolveSelectedSessionIdLocked() ?: return emptySet()
            liveHunksBySessionAndFile[sessionId]?.keys?.toSet() ?: emptySet()
        }
        SessionScope.FAMILY -> {
            familySessionIdsLocked()
                .flatMap { sessionId -> liveHunksBySessionAndFile[sessionId]?.keys ?: emptySet() }
                .toSet()
        }
        SessionScope.ALL -> liveHunksBySessionAndFile.values.flatMap { it.keys }.toSet()
    }

    private fun inlineHunks(filePath: String, scope: SessionScope): List<DiffHunk> = when (scope) {
        SessionScope.SELECTED -> {
            val sessionId = resolveSelectedSessionIdLocked() ?: return emptyList()
            liveHunksBySessionAndFile[sessionId]?.get(filePath) ?: emptyList()
        }
        SessionScope.FAMILY -> {
            val orderedSessionIds = familySessionIdsLocked().sortedBy { sessionId ->
                -(updatedAtBySession[sessionId] ?: 0L)
            }
            SessionScopeMergeUtil.mergeByOrderedSessions(filePath, orderedSessionIds, liveHunksBySessionAndFile)
        }
        SessionScope.ALL -> liveHunksBySessionAndFile.values.flatMap { it[filePath].orEmpty() }
    }

    private fun currentScopeLocked(): SessionScope = when (viewMode) {
        ViewMode.SELECTED_SESSION -> SessionScope.SELECTED
        ViewMode.UNIFIED_FAMILY -> SessionScope.FAMILY
    }

    fun currentViewMode(): ViewMode = viewMode

    fun setViewMode(mode: ViewMode) {
        if (viewMode == mode) return
        val previousFiles = synchronized(stateLock) { liveVisibleFilesLocked(currentScopeLocked()) }
        viewMode = mode
        val newFiles = synchronized(stateLock) { liveVisibleFilesLocked(currentScopeLocked()) }
        val changedFiles = if (previousFiles == newFiles) newFiles else previousFiles + newFiles
        changedFiles.forEach { publishService.publishChanged(it) }
        publishService.publishSessionStateChanged()
    }

    fun listSessions(): List<SessionInfo> = synchronized(stateLock) {
        knownSessionIdsLocked()
            .map { sessionId ->
                SessionInfo(
                    sessionId = sessionId,
                    parentSessionId = parentBySessionId[sessionId],
                    title = sessionTitleById[sessionId],
                    description = sessionDescriptionById[sessionId],
                    isBusy = busyBySession[sessionId] == true,
                    trackedFileCount = hunksBySessionAndFile[sessionId]?.size ?: 0,
                    updatedAtMillis = updatedAtBySession[sessionId] ?: 0L,
                )
            }
            .sortedWith(
                compareByDescending<SessionInfo> { it.isBusy }
                    .thenByDescending { it.updatedAtMillis }
                    .thenBy { it.sessionId },
            )
    }

    fun selectedSessionId(): String? = synchronized(stateLock) {
        resolveSelectedSessionIdLocked()
    }

    fun selectSession(sessionId: String?) {
        val previousFiles = synchronized(stateLock) { liveVisibleFilesLocked(currentScopeLocked()) }
        val selectedAfter = synchronized(stateLock) {
            selectedSessionId = when {
                sessionId == null -> null
                sessionExistsLocked(sessionId) -> sessionId
                else -> selectedSessionId
            }
            selectedSessionId
        }
        val newFiles = synchronized(stateLock) { liveVisibleFilesLocked(currentScopeLocked()) }
        val changedFiles = if (previousFiles == newFiles) newFiles else previousFiles + newFiles
        changedFiles.forEach { publishService.publishChanged(it) }
        publishService.publishSessionStateChanged()

        if (selectedAfter != null) {
            ensureHistoricalFamilyLoadedAsync(selectedAfter)
        }
    }

    fun getHunks(filePath: String, sessionId: String): List<DiffHunk> {
        return hunksBySessionAndFile[sessionId]?.get(filePath) ?: emptyList()
    }

    fun getHunks(filePath: String, scope: SessionScope): List<DiffHunk> = synchronized(stateLock) {
        when (scope) {
            SessionScope.SELECTED -> {
                val sessionId = resolveSelectedSessionIdLocked() ?: return@synchronized emptyList()
                hunksBySessionAndFile[sessionId]?.get(filePath) ?: emptyList()
            }
            SessionScope.FAMILY -> {
                val orderedSessionIds = familySessionIdsLocked().sortedBy { sessionId ->
                    -(updatedAtBySession[sessionId] ?: 0L)
                }
                SessionScopeMergeUtil.mergeByOrderedSessions(filePath, orderedSessionIds, hunksBySessionAndFile)
            }
            SessionScope.ALL -> hunksBySessionAndFile.values.flatMap { it[filePath].orEmpty() }
        }
    }

    fun getHunks(filePath: String): List<DiffHunk> {
        val currentScope = synchronized(stateLock) { currentScopeLocked() }
        return synchronized(stateLock) { inlineHunks(filePath, currentScope) }
    }

    fun hasPendingHunks(filePath: String, scope: SessionScope): Boolean =
        getHunks(filePath, scope).any { it.state == HunkState.PENDING }

    fun hasPendingHunks(filePath: String): Boolean {
        val currentScope = synchronized(stateLock) { currentScopeLocked() }
        return synchronized(stateLock) {
            inlineHunks(filePath, currentScope).any { it.state == HunkState.PENDING }
        }
    }

    fun isDeleted(filePath: String, sessionId: String): Boolean {
        return deletedBySession[sessionId]?.contains(filePath) == true
    }

    fun isDeleted(filePath: String, scope: SessionScope): Boolean = synchronized(stateLock) {
        when (scope) {
            SessionScope.SELECTED -> {
                val sessionId = resolveSelectedSessionIdLocked() ?: return@synchronized false
                deletedBySession[sessionId]?.contains(filePath) == true
            }
            SessionScope.FAMILY -> familySessionIdsLocked().any { sessionId ->
                deletedBySession[sessionId]?.contains(filePath) == true
            }
            SessionScope.ALL -> deletedBySession.values.any { it.contains(filePath) }
        }
    }

    fun isDeleted(filePath: String): Boolean {
        val currentScope = synchronized(stateLock) { currentScopeLocked() }
        return isDeleted(filePath, currentScope)
    }

    fun isAdded(filePath: String, sessionId: String): Boolean {
        return addedBySession[sessionId]?.contains(filePath) == true
    }

    fun isAdded(filePath: String, scope: SessionScope): Boolean = synchronized(stateLock) {
        when (scope) {
            SessionScope.SELECTED -> {
                val sessionId = resolveSelectedSessionIdLocked() ?: return@synchronized false
                addedBySession[sessionId]?.contains(filePath) == true
            }
            SessionScope.FAMILY -> familySessionIdsLocked().any { sessionId ->
                addedBySession[sessionId]?.contains(filePath) == true
            }
            SessionScope.ALL -> addedBySession.values.any { it.contains(filePath) }
        }
    }

    fun isAdded(filePath: String): Boolean {
        val currentScope = synchronized(stateLock) { currentScopeLocked() }
        return isAdded(filePath, currentScope)
    }

    fun trackedFiles(scope: SessionScope): Set<String> = synchronized(stateLock) {
        visibleFilesLocked(scope)
    }

    fun allTrackedFiles(): Set<String> {
        return synchronized(stateLock) { visibleFilesForCurrentViewLocked() }
    }

    fun getFileDiffPreview(filePath: String): FileDiffPreview? {
        val source = synchronized(stateLock) {
            val candidateSessionIds = when (currentScopeLocked()) {
                SessionScope.SELECTED -> listOfNotNull(resolveSelectedSessionIdLocked())
                SessionScope.FAMILY -> familySessionIdsLocked().sortedByDescending { updatedAtBySession[it] ?: 0L }
                SessionScope.ALL -> knownSessionIdsLocked().sortedByDescending { updatedAtBySession[it] ?: 0L }
            }

            candidateSessionIds.asSequence()
                .mapNotNull { sessionId ->
                    val before = baselineBeforeBySessionAndFile[sessionId]?.get(filePath) ?: return@mapNotNull null
                    sessionId to before
                }
                .firstOrNull()
        } ?: return null

        return FileDiffPreview(
            filePath = filePath,
            sessionId = source.first,
            before = source.second,
            after = documentSyncService.readCurrentContent(filePath),
        )
    }

    private fun ensureHistoricalFamilyLoadedAsync(rootSessionId: String, generation: Long = lifecycleGeneration.get()) {
        if (generation != lifecycleGeneration.get()) return

        val familyIds = synchronized(stateLock) {
            if (generation != lifecycleGeneration.get()) return@synchronized emptySet()
            knownFamilySessionIdsForRootLocked(rootSessionId)
        }
        familyIds.forEach { sessionId -> ensureHistoricalDiffLoadedAsync(sessionId, generation) }
        ensureHistoricalDiffLoadedAsync(rootSessionId, generation)
    }

    private fun ensureHistoricalRootsLoadedAsync(
        sessionIds: Set<String>,
        parentBySessionId: Map<String, String>,
        generation: Long,
    ) {
        if (generation != lifecycleGeneration.get()) return

        val rootSessionIds = sessionIds.filter { it !in parentBySessionId.keys }
        rootSessionIds.forEach { sessionId -> ensureHistoricalDiffLoadedAsync(sessionId, generation) }
    }

    private fun ensureHistoricalDiffLoadedAsync(sessionId: String, generation: Long) {
        if (generation != lifecycleGeneration.get()) return
        if (sessionId.isBlank()) return
        if (historicalDiffLoadedSessions.contains(sessionId)) return
        if (!historicalDiffLoadInFlight.add(sessionId)) return

        val currentPort = port
        if (currentPort <= 0) {
            historicalDiffLoadInFlight.remove(sessionId)
            return
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                if (generation != lifecycleGeneration.get()) return@executeOnPooledThread

                val result = sessionApiClient.fetchSessionDiffSnapshot(currentPort, sessionId)
                if (!result.success) return@executeOnPooledThread
                if (generation != lifecycleGeneration.get()) return@executeOnPooledThread

                historicalDiffLoadedSessions.add(sessionId)
                val event = result.event ?: return@executeOnPooledThread
                handleSessionDiff(event, fromHistory = true, generation = generation)
            } catch (e: Exception) {
                log.debug("OpenCodeDiffService: failed historical diff load for $sessionId", e)
            } finally {
                historicalDiffLoadInFlight.remove(sessionId)
            }
        }
    }

    fun clearAll() {
        val files = synchronized(stateLock) {
            visibleFilesLocked(SessionScope.ALL)
        }
        lifecycleGeneration.incrementAndGet()
        synchronized(stateLock) {
            resetStateLocked()
        }
        files.forEach { publishService.publishChanged(it) }
        publishService.publishSessionStateChanged()
    }

    private fun refreshSessionHierarchyAsync(generation: Long = lifecycleGeneration.get()) {
        if (generation != lifecycleGeneration.get()) return

        val currentPort = port
        if (currentPort <= 0) return
        if (!hierarchyRefreshInFlight.compareAndSet(false, true)) return

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                if (generation != lifecycleGeneration.get()) return@executeOnPooledThread

                val snapshot = sessionApiClient.fetchSessionHierarchy(currentPort)
                if (snapshot.sessionIds.isEmpty()) return@executeOnPooledThread

                val changed = synchronized(stateLock) {
                    if (generation != lifecycleGeneration.get()) return@synchronized false
                    val idsChanged = hierarchySessionIds != snapshot.sessionIds
                    val parentsChanged = parentBySessionId != snapshot.parentBySessionId
                    val titlesChanged = sessionTitleById != snapshot.titleBySessionId
                    val descriptionsChanged = sessionDescriptionById != snapshot.descriptionBySessionId
                    if (!idsChanged && !parentsChanged && !titlesChanged && !descriptionsChanged) {
                        false
                    } else {
                        hierarchySessionIds.clear()
                        hierarchySessionIds.addAll(snapshot.sessionIds)

                        parentBySessionId.clear()
                        parentBySessionId.putAll(snapshot.parentBySessionId)

                        sessionTitleById.clear()
                        sessionTitleById.putAll(snapshot.titleBySessionId)

                        sessionDescriptionById.clear()
                        sessionDescriptionById.putAll(snapshot.descriptionBySessionId)
                        true
                    }
                }

                if (generation != lifecycleGeneration.get()) return@executeOnPooledThread

                if (changed) {
                    publishService.publishSessionStateChanged()
                }

                ensureHistoricalRootsLoadedAsync(snapshot.sessionIds, snapshot.parentBySessionId, generation)

                val selected = synchronized(stateLock) {
                    if (generation != lifecycleGeneration.get()) return@synchronized null
                    resolveSelectedSessionIdLocked()
                }
                if (selected != null) {
                    ensureHistoricalFamilyLoadedAsync(selected, generation)
                }
            } catch (e: Exception) {
                log.debug("OpenCodeDiffService: failed to refresh session hierarchy", e)
            } finally {
                hierarchyRefreshInFlight.set(false)
            }
        }
    }

    private fun resetStateLocked() {
        hunksBySessionAndFile.clear()
        liveHunksBySessionAndFile.clear()
        deletedBySession.clear()
        addedBySession.clear()
        baselineBeforeBySessionAndFile.clear()
        lastAfterBySessionAndFile.clear()
        pendingTurnFilesBySession.clear()
        busyBySession.clear()
        updatedAtBySession.clear()
        sessionTitleById.clear()
        sessionDescriptionById.clear()
        documentSyncService.clearReloadHistory()
        parentBySessionId.clear()
        hierarchySessionIds.clear()
        diffApplyRevisionBySession.clear()
        historicalDiffLoadInFlight.clear()
        historicalDiffLoadedSessions.clear()
        selectedSessionId = null
        latestActiveSessionId = null
        viewMode = ViewMode.UNIFIED_FAMILY
    }

    override fun dispose() {
        stopListening()
        lifecycleGeneration.incrementAndGet()
        synchronized(stateLock) {
            resetStateLocked()
        }
    }

    companion object {
        fun getInstance(project: Project): OpenCodeDiffService =
            project.getService(OpenCodeDiffService::class.java)
    }
}
