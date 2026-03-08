package com.ashotn.opencode.diff

import com.ashotn.opencode.ipc.OpenCodeEvent
import com.ashotn.opencode.settings.OpenCodeSettings
import com.ashotn.opencode.ipc.SseClient
import com.ashotn.opencode.permission.OpenCodePermissionService
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Project-level service that owns the SSE connection to the OpenCode server
 * and maintains AI diff hunks by session.
 */
@Service(Service.Level.PROJECT)
class OpenCodeDiffService(private val project: Project) : Disposable {

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
    private val tracer: DiffTracer = run {
        val settings = OpenCodeSettings.getInstance(project)
        DiffTracer.fromSettings(
            project = project,
            logger = log,
            traceEnabled = settings.diffTraceEnabled,
            includeHistory = settings.diffTraceHistoryEnabled,
        )
    }

    /** childSessionId -> parentSessionId */
    private val parentBySessionId = ConcurrentHashMap<String, String>()

    /** Session IDs discovered from /session list, including root sessions. */
    private val hierarchySessionIds = ConcurrentHashMap.newKeySet<String>()


    /** sessionId -> title/summary metadata from /session. */
    private val sessionTitleById = ConcurrentHashMap<String, String>()
    private val sessionDescriptionById = ConcurrentHashMap<String, String>()

    /** Guards map updates that must commit atomically. */
    private val stateLock = Any()

    private val permissionService = OpenCodePermissionService.getInstance(project)
    private val documentSyncService = DocumentSyncService(project)
    private val sessionApiClient = SessionApiClient()
    private val hunkComputer = DiffHunkComputer(log)
    private val sessionDiffApplyComputer = SessionDiffApplyComputer(
        contentReader = documentSyncService::readCurrentContent,
        hunkComputer = hunkComputer::compute,
        onFileProcessing = { absPath, status ->
            documentSyncService.queueVfsRefresh(absPath, status)
            documentSyncService.reloadOpenDocument(absPath)
        },
        log = log,
        tracer = tracer,
    )
    private val publishService = DiffPublishService(project)
    private val scopeResolver = SessionScopeResolver()
    private val queryService = DiffQueryService()
    private val eventReducer = DiffEventReducer()
    private val stateStore = DiffStateStore()
    private val hierarchyRefreshInFlight = AtomicBoolean(false)
    private val historicalDiffLoadInFlight = ConcurrentHashMap.newKeySet<String>()
    private val historicalDiffLoadedSessions = ConcurrentHashMap.newKeySet<String>()
    private val lifecycleGeneration = AtomicLong(0L)
    private val reconcileScheduleTokenBySession = ConcurrentHashMap<String, Long>()
    private val reconcileScheduleSequence = AtomicLong(0L)

    @Volatile
    private var port: Int = 0

    private var sseClient: SseClient? = null

    fun startListening(port: Int) {
        stopListening()
        val generation = advanceLifecycleGeneration()

        this.port = port
        permissionService.setPort(port)
        log.info("OpenCodeDiffService: starting SSE listener on port $port")
        EditorDiffRenderer.getInstance(project)
        sseClient = SseClient(
            port = port,
            onEvent = { event -> handleEvent(event, generation) },
        ).also { it.start() }
        trace("listener.started") {
            val traceFilePath = (tracer as? JsonlDiffTracer)?.traceFilePath()
            mapOf(
                "port" to port,
                "generation" to generation,
                "traceFile" to traceFilePath,
            )
        }
        refreshSessionHierarchyAsync(generation)
    }

    fun stopListening() {
        val generation = advanceLifecycleGeneration()
        trace("listener.stopping") {
            mapOf(
                "generation" to generation,
            )
        }
        sseClient?.stop()
        sseClient = null
        port = 0
        reconcileScheduleTokenBySession.clear()
        clearRuntimeStateAndPublish()
    }

    private fun handleEvent(event: OpenCodeEvent, generation: Long) {
        if (generation != lifecycleGeneration.get()) return
        when (event) {
            is OpenCodeEvent.SessionDiff -> handleSessionDiff(event, fromHistory = false, generation = generation)
            is OpenCodeEvent.SessionBusy -> {
                markSessionBusy(event.sessionId, true, generation)
            }

            is OpenCodeEvent.SessionIdle -> {
                markSessionBusy(event.sessionId, false, generation)
            }

            is OpenCodeEvent.TurnPatch -> {
                val projectBase = project.basePath
                if (projectBase == null) {
                    log.debug("OpenCodeDiffService: skip turn.patch reason=missingProjectBase generation=$generation")
                    return
                }
                val touchedPaths = eventReducer.reduceTurnPatchTouchedPaths(projectBase, event.files)
                if (generation != lifecycleGeneration.get()) return
                val committed = eventReducer.commitTurnPatch(
                    stateStore = stateStore,
                    stateLock = stateLock,
                    sessionId = event.sessionId,
                    touchedPaths = touchedPaths,
                    generation = generation,
                    currentGeneration = { lifecycleGeneration.get() },
                )
                if (!committed) return
                trace("turn.patch.committed") {
                    mapOf(
                        "sessionId" to event.sessionId,
                        "touchedPaths" to touchedPaths.toList(),
                        "generation" to generation,
                    )
                }
                log.debug("OpenCodeDiffService: turn.patch recorded session=${event.sessionId} touchedFileCount=${touchedPaths.size} generation=$generation")
            }

            is OpenCodeEvent.PermissionAsked -> permissionService.handlePermissionAsked(event)
            is OpenCodeEvent.PermissionReplied -> permissionService.handlePermissionReplied(event)
        }
    }

    private fun markSessionBusy(sessionId: String, isBusy: Boolean, generation: Long) {
        if (generation != lifecycleGeneration.get()) return

        val previousLiveVisibleFiles = synchronized(stateLock) {
            if (generation != lifecycleGeneration.get()) {
                return@synchronized emptySet()
            }
            liveVisibleFilesLocked()
        }

        val committed = eventReducer.commitSessionBusy(
            stateStore = stateStore,
            stateLock = stateLock,
            sessionId = sessionId,
            isBusy = isBusy,
            nowMillis = System.currentTimeMillis(),
            generation = generation,
            currentGeneration = { lifecycleGeneration.get() },
        )
        if (!committed) return

        val nextLiveVisibleFiles = synchronized(stateLock) {
            if (generation != lifecycleGeneration.get()) {
                return@synchronized emptySet()
            }
            liveVisibleFilesLocked()
        }

        if (!isBusy) {
            documentSyncService.flushQueuedVfsRefreshes()
        }
        if (generation != lifecycleGeneration.get()) return

        val filesToRefresh = previousLiveVisibleFiles + nextLiveVisibleFiles
        filesToRefresh.forEach { publishService.publishChanged(it) }
        publishService.publishSessionStateChanged()
        refreshSessionHierarchyAsync(generation)
    }

    private fun handleSessionDiff(event: OpenCodeEvent.SessionDiff, fromHistory: Boolean, generation: Long) {
        if (generation != lifecycleGeneration.get()) return
        val projectBase = project.basePath
        if (projectBase == null) {
            log.debug("OpenCodeDiffService: skip session.diff reason=missingProjectBase session=${event.sessionId} generation=$generation")
            return
        }

        val applyDecision = eventReducer.beginSessionDiffApply(
            stateStore = stateStore,
            stateLock = stateLock,
            sessionId = event.sessionId,
            fromHistory = fromHistory,
            generation = generation,
            currentGeneration = { lifecycleGeneration.get() },
        )
        trace("session.diff.decision", fromHistory = fromHistory) {
            mapOf(
                "sessionId" to event.sessionId,
                "fromHistory" to fromHistory,
                "fileCount" to event.files.size,
                "shouldApply" to applyDecision.shouldApply,
                "skipReason" to applyDecision.skipReason?.name,
                "revision" to applyDecision.revision,
                "turnScope" to applyDecision.turnScope?.toList(),
                "generation" to generation,
            )
        }
        if (!applyDecision.shouldApply) {
            when (applyDecision.skipReason) {
                DiffEventReducer.SessionDiffSkipReason.UNSCOPED_LIVE -> {
                    log.debug("OpenCodeDiffService: skip session.diff reason=unscopedLive session=${event.sessionId} generation=$generation")
                }

                DiffEventReducer.SessionDiffSkipReason.STALE_OR_ALREADY_LOADED -> {
                    log.debug("OpenCodeDiffService: skip session.diff reason=historyAlreadyLoaded session=${event.sessionId} generation=$generation")
                }

                null -> {
                }
            }
            return
        }
        val turnScope = applyDecision.turnScope
        val revision = applyDecision.revision ?: return

        ApplicationManager.getApplication().executeOnPooledThread {
            if (generation != lifecycleGeneration.get()) return@executeOnPooledThread

            log.debug(
                "OpenCodeDiffService: apply session.diff session=${event.sessionId} revision=$revision fileCount=${event.files.size} fromHistory=$fromHistory generation=$generation",
            )

            val prepareSnapshot = stateStore.snapshotSessionDiffPrepareState(
                stateLock = stateLock,
                sessionId = event.sessionId,
                expectedGeneration = generation,
                currentGeneration = { lifecycleGeneration.get() },
            ) ?: return@executeOnPooledThread

            val computedState = sessionDiffApplyComputer.compute(
                projectBase = projectBase,
                event = event,
                fromHistory = fromHistory,
                turnScope = turnScope,
                previousAfterByFile = prepareSnapshot.previousAfterByFile,
            )

            if (generation != lifecycleGeneration.get()) return@executeOnPooledThread

            val previousLiveVisibleFiles = synchronized(stateLock) {
                if (generation != lifecycleGeneration.get()) {
                    return@synchronized emptySet()
                }
                liveVisibleFilesLocked()
            }

            val commitResult = stateStore.commitSessionDiffApply(
                stateLock = stateLock,
                sessionId = event.sessionId,
                revision = revision,
                fromHistory = fromHistory,
                computedState = computedState,
                nowMillis = System.currentTimeMillis(),
                expectedGeneration = generation,
                currentGeneration = { lifecycleGeneration.get() },
            )
            if (commitResult == null) {
                log.debug("OpenCodeDiffService: skip session.diff reason=staleApply session=${event.sessionId} revision=$revision generation=$generation")
                trace("session.diff.commitSkipped", fromHistory = fromHistory) {
                    mapOf(
                        "sessionId" to event.sessionId,
                        "revision" to revision,
                        "generation" to generation,
                    )
                }
                return@executeOnPooledThread
            }

            val changedFiles = commitResult.changedFiles
            traceSessionDiffCommitted(
                sessionId = event.sessionId,
                revision = revision,
                generation = generation,
                fromHistory = fromHistory,
                changedFiles = changedFiles,
            )
            val nextLiveVisibleFiles = synchronized(stateLock) {
                if (generation != lifecycleGeneration.get()) {
                    return@synchronized emptySet()
                }
                liveVisibleFilesLocked()
            }

            val filesToRefresh = changedFiles + previousLiveVisibleFiles + nextLiveVisibleFiles

            log.debug(
                "OpenCodeDiffService: applied session.diff session=${event.sessionId} revision=$revision trackedFileCount=${computedState.newHunksByFile.size} deletedFileCount=${computedState.newDeleted.size} addedFileCount=${computedState.newAdded.size} changedFileCount=${changedFiles.size} refreshedFileCount=${filesToRefresh.size} generation=$generation",
            )

            if (generation != lifecycleGeneration.get()) return@executeOnPooledThread
            if (fromHistory) {
                historicalDiffLoadedSessions.add(event.sessionId)
            }

            filesToRefresh.forEach { publishService.publishChanged(it) }
            publishService.publishSessionStateChanged()
            if (!fromHistory) {
                refreshSessionHierarchyAsync(generation)
            }

            scheduleReconcile(event.sessionId, revision, generation)
        }
    }

    private fun scheduleReconcile(sessionId: String, revision: Long, generation: Long) {
        val token = synchronized(stateLock) {
            if (generation != lifecycleGeneration.get()) return
            val nextToken = reconcileScheduleSequence.incrementAndGet()
            reconcileScheduleTokenBySession[sessionId] = nextToken
            nextToken
        }

        AppExecutorUtil.getAppScheduledExecutorService().schedule(
            Runnable {
                try {
                    if (generation != lifecycleGeneration.get()) return@Runnable
                    if (reconcileScheduleTokenBySession[sessionId] != token) return@Runnable
                    if (revision != currentSessionRevision(sessionId)) return@Runnable
                    reconcileSessionState(sessionId, revision, generation)
                } finally {
                    reconcileScheduleTokenBySession.remove(sessionId, token)
                }
            },
            250,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun reconcileSessionState(sessionId: String, revision: Long, generation: Long) {
        if (generation != lifecycleGeneration.get()) return

        val snapshot = stateStore.snapshotSessionReconcileState(
            stateLock = stateLock,
            sessionId = sessionId,
            expectedGeneration = generation,
            currentGeneration = { lifecycleGeneration.get() },
        ) ?: return

        val reconcileDecision = eventReducer.reduceReconcile(
            currentHunks = snapshot.currentHunks,
            currentDeleted = snapshot.currentDeleted,
            currentAdded = snapshot.currentAdded,
            currentBaselines = snapshot.currentBaselines,
            readCurrentContent = { absPath -> documentSyncService.readCurrentContent(absPath) },
        ) ?: return

        if (reconcileDecision.removedPaths.isNotEmpty()) {
            log.debug(
                "OpenCodeDiffService: reconcile session=$sessionId revision=$revision removedBaselineMatchCount=${reconcileDecision.removedPaths.size} generation=$generation",
            )
        }

        val committed = stateStore.commitReconcile(
            stateLock = stateLock,
            sessionId = sessionId,
            revision = revision,
            updatedHunks = reconcileDecision.updatedHunks,
            updatedDeleted = reconcileDecision.updatedDeleted,
            updatedAdded = reconcileDecision.updatedAdded,
            currentBaselines = snapshot.currentBaselines,
            nowMillis = System.currentTimeMillis(),
            expectedGeneration = generation,
            currentGeneration = { lifecycleGeneration.get() },
        )
        if (!committed) return

        if (generation != lifecycleGeneration.get()) return
        reconcileDecision.removedPaths.forEach { publishService.publishChanged(it) }
        publishService.publishSessionStateChanged()
    }

    private inline fun trace(kind: String, fromHistory: Boolean = false, fields: () -> Map<String, Any?>) {
        if (!tracer.enabled) return
        if (fromHistory && !tracer.includeHistory) return
        tracer.record(kind, fields())
    }

    private fun traceSessionDiffCommitted(
        sessionId: String,
        revision: Long,
        generation: Long,
        fromHistory: Boolean,
        changedFiles: Set<String>,
    ) {
        trace("session.diff.committed", fromHistory = fromHistory) {
            // Only record file names and byte-length summaries — never store full file content
            // in a trace field, as that causes unbounded memory growth during active sessions.
            val stateSnapshot = synchronized(stateLock) {
                val baselineSizes = stateStore.baselineBeforeBySessionAndFile[sessionId]
                    ?.mapValues { (_, v) -> v.length }
                    ?: emptyMap<String, Int>()
                val lastAfterSizes = stateStore.lastAfterBySessionAndFile[sessionId]
                    ?.mapValues { (_, v) -> v.length }
                    ?: emptyMap<String, Int>()
                mapOf(
                    "hunkFiles" to (stateStore.hunksBySessionAndFile[sessionId]?.keys?.sorted() ?: emptyList<String>()),
                    "liveHunkFiles" to (stateStore.liveHunksBySessionAndFile[sessionId]?.keys?.sorted()
                        ?: emptyList<String>()),
                    "deletedFiles" to (stateStore.deletedBySession[sessionId]?.sorted() ?: emptyList<String>()),
                    "addedFiles" to (stateStore.addedBySession[sessionId]?.sorted() ?: emptyList<String>()),
                    "baselineBeforeSizeByFile" to baselineSizes,
                    "lastAfterSizeByFile" to lastAfterSizes,
                )
            }
            mapOf(
                "sessionId" to sessionId,
                "revision" to revision,
                "generation" to generation,
                "changedFiles" to changedFiles.sorted(),
                "state" to stateSnapshot,
            )
        }
    }

    private fun currentSessionRevision(sessionId: String): Long =
        stateStore.currentSessionRevision(stateLock, sessionId)

    private fun hasAppliedDiffState(sessionId: String): Boolean =
        currentSessionRevision(sessionId) > 0L

    private fun knownSessionIdsLocked(): Set<String> =
        scopeResolver.knownSessionIds(
            hunksBySessionAndFile = stateStore.hunksBySessionAndFile,
            busyBySession = stateStore.busyBySession,
            updatedAtBySession = stateStore.updatedAtBySession,
            hierarchySessionIds = hierarchySessionIds,
            parentBySessionId = parentBySessionId,
        )

    private fun sessionExistsLocked(sessionId: String): Boolean =
        knownSessionIdsLocked().contains(sessionId)

    private fun resolveSelectedSessionIdLocked(): String? = stateStore.resolveSelectedSessionId(
        stateLock = stateLock,
    ) { selectedSessionId ->
        scopeResolver.resolveSelectedSessionId(
            selectedSessionId = selectedSessionId,
            knownSessionIds = knownSessionIdsLocked(),
        )
    }

    private fun knownFamilySessionIdsForRootLocked(rootSessionId: String): Set<String> =
        scopeResolver.knownFamilySessionIdsForRoot(rootSessionId, parentBySessionId)

    private fun familySessionIdsLocked(): Set<String> {
        val selected = resolveSelectedSessionIdLocked() ?: return emptySet()
        return scopeResolver.familySessionIds(
            selectedSessionId = selected,
            parentBySessionId = parentBySessionId,
            knownSessionIds = knownSessionIdsLocked(),
            busyBySession = stateStore.busyBySession,
            updatedAtBySession = stateStore.updatedAtBySession,
            hunksBySessionAndFile = stateStore.hunksBySessionAndFile,
            nowMillis = System.currentTimeMillis(),
        )
    }

    private fun visibleFilesLocked(): Set<String> = queryService.visibleFiles(
        familySessionIds = { familySessionIdsLocked() },
        hunksBySessionAndFile = stateStore.hunksBySessionAndFile,
        addedBySession = stateStore.addedBySession,
        deletedBySession = stateStore.deletedBySession,
    )

    private fun liveVisibleFilesLocked(): Set<String> = queryService.liveVisibleFiles(
        familySessionIds = { familySessionIdsLocked() },
        liveHunksBySessionAndFile = stateStore.liveHunksBySessionAndFile,
        addedBySession = stateStore.addedBySession,
        deletedBySession = stateStore.deletedBySession,
    )

    /**
     * Inline hunks for editor rendering, sourced from live (latest-turn-only) state.
     *
     * Per docs/INLINE_DIFF_POLICY.md:
     *   - Only the selected session contributes (Rule 1).
     *   - Only the most recent turn is represented (Rule 2).
     */
    private fun inlineLiveHunks(filePath: String): List<DiffHunk> = queryService.liveHunks(
        filePath = filePath,
        selectedSessionId = { resolveSelectedSessionIdLocked() },
        liveHunksBySessionAndFile = stateStore.liveHunksBySessionAndFile,
    )

    fun listSessions(): List<SessionInfo> = synchronized(stateLock) {
        queryService.listSessions(
            knownSessionIds = knownSessionIdsLocked(),
            parentBySessionId = parentBySessionId,
            titleBySessionId = sessionTitleById,
            descriptionBySessionId = sessionDescriptionById,
            busyBySession = stateStore.busyBySession,
            hunksBySessionAndFile = stateStore.hunksBySessionAndFile,
            addedBySession = stateStore.addedBySession,
            deletedBySession = stateStore.deletedBySession,
            updatedAtBySession = stateStore.updatedAtBySession,
        )
    }

    fun selectedSessionId(): String? = synchronized(stateLock) {
        resolveSelectedSessionIdLocked()
    }

    fun selectSession(sessionId: String?) {
        val previousFiles = synchronized(stateLock) { liveVisibleFilesLocked() }
        val selectedAfter = stateStore.commitSelectedSession(
            stateLock = stateLock,
            requestedSessionId = sessionId,
            sessionExists = { candidate -> sessionExistsLocked(candidate) },
        )
        val newFiles = synchronized(stateLock) { liveVisibleFilesLocked() }
        val changedFiles = if (previousFiles == newFiles) newFiles else previousFiles + newFiles
        changedFiles.forEach { publishService.publishChanged(it) }
        publishService.publishSessionStateChanged()

        if (selectedAfter != null) {
            ensureHistoricalFamilyLoadedAsync(selectedAfter)
        }
    }

    fun getHunks(filePath: String): List<DiffHunk> {
        return synchronized(stateLock) { inlineLiveHunks(filePath) }
    }

    fun hasPendingHunks(filePath: String): Boolean {
        return synchronized(stateLock) {
            inlineLiveHunks(filePath).isNotEmpty()
        }
    }

    fun isDeleted(filePath: String): Boolean = synchronized(stateLock) {
        queryService.containsFile(
            filePath = filePath,
            familySessionIds = { familySessionIdsLocked() },
            filesBySession = stateStore.deletedBySession,
        )
    }

    fun isAdded(filePath: String): Boolean = synchronized(stateLock) {
        queryService.containsFile(
            filePath = filePath,
            familySessionIds = { familySessionIdsLocked() },
            filesBySession = stateStore.addedBySession,
        )
    }

    fun allTrackedFiles(): Set<String> = synchronized(stateLock) { visibleFilesLocked() }

    fun getFileDiffPreview(filePath: String, onResult: (FileDiffPreview?) -> Unit) {
        val (sessionId, currentPort) = synchronized(stateLock) {
            val candidateSessionIds = queryService.previewCandidateSessionIds(
                familySessionIds = { familySessionIdsLocked() },
                updatedAtBySession = stateStore.updatedAtBySession,
            )
            val sid = candidateSessionIds.firstOrNull { sessionId ->
                stateStore.baselineBeforeBySessionAndFile[sessionId]?.containsKey(filePath) == true
            } ?: return onResult(null)
            sid to port
        }

        if (currentPort <= 0) return onResult(null)

        val projectBase = project.basePath ?: return onResult(null)

        ApplicationManager.getApplication().executeOnPooledThread {
            val result = sessionApiClient.fetchFileDiffPreview(currentPort, sessionId, projectBase, filePath)
            if (!result.success || result.before == null) {
                onResult(null)
                return@executeOnPooledThread
            }
            onResult(
                FileDiffPreview(
                    filePath = filePath,
                    sessionId = sessionId,
                    before = result.before,
                    after = result.after ?: documentSyncService.readCurrentContent(filePath),
                )
            )
        }
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
        if (hasAppliedDiffState(sessionId)) {
            historicalDiffLoadedSessions.add(sessionId)
            return
        }
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
                if (!result.success) {
                    log.debug(
                        "OpenCodeDiffService: skip historical snapshot reason=requestFailed session=$sessionId port=$currentPort generation=$generation",
                    )
                    return@executeOnPooledThread
                }
                if (generation != lifecycleGeneration.get()) return@executeOnPooledThread

                val event = result.event ?: return@executeOnPooledThread
                handleSessionDiff(event, fromHistory = true, generation = generation)
            } catch (e: Exception) {
                log.debug(
                    "OpenCodeDiffService: failed historical snapshot load session=$sessionId port=$currentPort generation=$generation",
                    e
                )
            } finally {
                historicalDiffLoadInFlight.remove(sessionId)
            }
        }
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
                if (snapshot.sessionIds.isEmpty()) {
                    log.debug("OpenCodeDiffService: skip hierarchy refresh reason=emptySnapshot port=$currentPort generation=$generation")
                    return@executeOnPooledThread
                }

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
                log.debug("OpenCodeDiffService: failed hierarchy refresh port=$currentPort generation=$generation", e)
            } finally {
                hierarchyRefreshInFlight.set(false)
            }
        }
    }

    private fun clearRuntimeStateAndPublish() {
        val previousInlineFiles = synchronized(stateLock) {
            stateStore.hunksBySessionAndFile.values.flatMap { it.keys }.toSet()
        }
        synchronized(stateLock) {
            resetStateLocked()
        }
        permissionService.clearPermissions()
        hierarchyRefreshInFlight.set(false)
        previousInlineFiles.forEach { publishService.publishChanged(it) }
        publishService.publishSessionStateChanged()
    }

    private fun resetStateLocked() {
        sessionTitleById.clear()
        sessionDescriptionById.clear()
        documentSyncService.reset()
        parentBySessionId.clear()
        hierarchySessionIds.clear()
        historicalDiffLoadInFlight.clear()
        historicalDiffLoadedSessions.clear()
        reconcileScheduleTokenBySession.clear()
        stateStore.resetState()
    }

    private fun advanceLifecycleGeneration(): Long = synchronized(stateLock) {
        lifecycleGeneration.incrementAndGet()
    }

    override fun dispose() {
        stopListening()
        synchronized(stateLock) {
            resetStateLocked()
        }
        documentSyncService.dispose()
        hierarchyRefreshInFlight.set(false)
        tracer.close()
    }

    companion object {
        fun getInstance(project: Project): OpenCodeDiffService =
            project.getService(OpenCodeDiffService::class.java)
    }
}
