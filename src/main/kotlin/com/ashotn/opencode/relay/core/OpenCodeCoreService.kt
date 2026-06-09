package com.ashotn.opencode.relay.core

import com.ashotn.opencode.relay.OpenCodePlugin
import com.ashotn.opencode.relay.api.session.Session
import com.ashotn.opencode.relay.api.session.SessionApiClient
import com.ashotn.opencode.relay.api.session.SessionDiffSnapshot
import com.ashotn.opencode.relay.api.transport.ApiResult
import com.ashotn.opencode.relay.api.transport.OpenCodeHttpTransport
import com.ashotn.opencode.relay.core.session.PendingSessionSelection
import com.ashotn.opencode.relay.core.session.SessionInfo
import com.ashotn.opencode.relay.core.session.SessionScopeResolver
import com.ashotn.opencode.relay.ipc.McpChangedListener
import com.ashotn.opencode.relay.ipc.OpenCodeEvent
import com.ashotn.opencode.relay.ipc.SseClient
import com.ashotn.opencode.relay.permission.OpenCodePermissionService
import com.ashotn.opencode.relay.settings.OpenCodeServerAuth
import com.ashotn.opencode.relay.settings.OpenCodeSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

internal fun selectMessageSummaryFileCountLoadBatch(
    sessions: List<Session>,
    maxBatchSize: Int,
    shouldLoad: (Session) -> Boolean,
): List<Session> = sessions.asSequence()
    .filter { it.summary != null }
    .filter(shouldLoad)
    .sortedByDescending { it.time.updated }
    .take(maxBatchSize)
    .toList()

/**
 * Project-level service that owns the SSE connection to the OpenCode server
 * and maintains AI diff hunks by session.
 */
@Service(Service.Level.PROJECT)
class OpenCodeCoreService(private val project: Project) : Disposable {

    data class FileDiffPreview(
        val filePath: String,
        val sessionId: String,
        /** Content of the file before the AI session touched it (session baseline). */
        val before: String,
        /** The AI's intended result as reported by the server. */
        val aiAfter: String,
    )

    private val log = logger<OpenCodeCoreService>()
    private val tracer: DiffTracer = run {
        val settings = OpenCodeSettings.getInstance(project)
        DiffTracer.fromSettings(
            project = project,
            logger = log,
            traceEnabled = settings.diffTraceEnabled,
            includeHistory = settings.diffTraceHistoryEnabled,
        )
    }

    /** Session metadata indexed by session ID, populated from the /session list response. */
    private val sessionById = ConcurrentHashMap<String, Session>()

    /** Guards map updates that must commit atomically. */
    private val stateLock = Any()
    private val pendingSessionSelection = PendingSessionSelection()

    private val permissionService = OpenCodePermissionService.getInstance(project)
    private val documentSyncService = DocumentSyncService(project)
    private val serverAuth = OpenCodeServerAuth.getInstance(project)
    private val sessionApiClient = SessionApiClient(
        OpenCodeHttpTransport(authorizationHeaderProvider = serverAuth::connectionAuthorizationHeader),
    )
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
    private val publishService = PublishService(project)
    private val scopeResolver = SessionScopeResolver()
    private val queryService = QueryService()
    private val eventReducer = EventReducer()
    private val stateStore = StateStore()
    private val hierarchyRefreshInFlight = AtomicBoolean(false)
    private val historicalDiffLoadInFlight = ConcurrentHashMap.newKeySet<String>()
    private val historicalDiffLoadedSessions = ConcurrentHashMap.newKeySet<String>()
    private val messageDiffFetchCoalescer = MessageDiffFetchCoalescer()
    private val liveSessionDiffApplyInFlight = ConcurrentHashMap<String, AtomicInteger>()
    private val messageSummaryFileCountLoadInFlight = ConcurrentHashMap.newKeySet<String>()
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
        log.info("OpenCodeCoreService: starting SSE listener on port $port")
        EditorDiffRenderer.getInstance(project)
        sseClient = SseClient(
            port = port,
            onEvent = { event -> handleEvent(event, generation) },
            directory = project.basePath,
            authorizationHeaderProvider = serverAuth::connectionAuthorizationHeader,
            onAuthenticationFailure = {
                OpenCodePlugin.getInstance(project).reportAuthenticationRequired()
            },
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
        messageDiffFetchCoalescer.clear()
        liveSessionDiffApplyInFlight.clear()
        messageSummaryFileCountLoadInFlight.clear()
        clearRuntimeStateAndPublish()
    }

    private fun handleEvent(event: OpenCodeEvent, generation: Long) {
        if (generation != lifecycleGeneration.get()) return
        when (event) {
            is OpenCodeEvent.ServerConnected -> Unit
            is OpenCodeEvent.SessionStatus -> {
                applySessionStatus(event.sessionId, event.status, generation)
            }

            is OpenCodeEvent.SessionLifecycleChanged -> {
                refreshSessionHierarchyAsync(generation)
            }

            is OpenCodeEvent.MessageDiffAvailable -> {
                handleMessageDiffAvailable(event, generation)
            }

            is OpenCodeEvent.McpToolsChanged -> {
                project.messageBus.syncPublisher(McpChangedListener.TOPIC).onMcpChanged()
            }

            is OpenCodeEvent.PermissionAsked -> permissionService.handlePermissionAsked(event)
            is OpenCodeEvent.PermissionReplied -> permissionService.handlePermissionReplied(event)
        }
    }

    private fun handleMessageDiffAvailable(event: OpenCodeEvent.MessageDiffAvailable, generation: Long) {
        if (generation != lifecycleGeneration.get()) return
        if (!messageDiffFetchCoalescer.tryStart(event)) {
            log.debug("OpenCodeCoreService: defer message diff reason=alreadyInFlight session=${event.sessionId} message=${event.messageId} generation=$generation")
            return
        }

        scheduleMessageDiffFetch(event, messageDiffFetchCoalescer.key(event), generation)
    }

    private fun scheduleMessageDiffFetch(
        event: OpenCodeEvent.MessageDiffAvailable,
        key: String,
        generation: Long,
    ) {
        val currentPort = port
        if (currentPort <= 0) {
            log.debug("OpenCodeCoreService: skip message diff reason=missingPort session=${event.sessionId} message=${event.messageId} generation=$generation")
            messageDiffFetchCoalescer.cancel(key)
            return
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                if (generation != lifecycleGeneration.get()) return@executeOnPooledThread
                val eventDiff = when (val result = sessionApiClient.fetchSessionDiffSnapshot(
                    port = currentPort,
                    sessionId = event.sessionId,
                    messageId = event.messageId,
                )) {
                    is ApiResult.Failure -> {
                        log.debug(
                            "OpenCodeCoreService: skip message diff reason=requestFailed session=${event.sessionId} message=${event.messageId} port=$currentPort generation=$generation error=${result.error}",
                        )
                        return@executeOnPooledThread
                    }

                    is ApiResult.Success -> result.value
                }
                if (generation != lifecycleGeneration.get()) return@executeOnPooledThread
                if (eventDiff.files.isEmpty()) {
                    log.debug("OpenCodeCoreService: skip message diff reason=emptySnapshot session=${event.sessionId} message=${event.messageId} generation=$generation")
                    return@executeOnPooledThread
                }

                log.debug(
                    "OpenCodeCoreService: apply message diff session=${event.sessionId} message=${event.messageId} eventFiles=${event.files} fileCount=${eventDiff.files.size} files=${eventDiff.files.map { it.file }} generation=$generation",
                )
                handleSessionDiff(eventDiff, fromHistory = false, generation = generation)
            } catch (e: Exception) {
                log.debug(
                    "OpenCodeCoreService: failed message diff load session=${event.sessionId} message=${event.messageId} port=$currentPort generation=$generation",
                    e,
                )
            } finally {
                val pending = messageDiffFetchCoalescer.finish(
                    key = key,
                    allowPending = generation == lifecycleGeneration.get(),
                )
                if (pending != null) {
                    log.debug("OpenCodeCoreService: refetch pending message diff session=${pending.sessionId} message=${pending.messageId} generation=$generation")
                    scheduleMessageDiffFetch(pending, key, generation)
                }
            }
        }
    }

    private fun applySessionStatus(
        sessionId: String,
        status: OpenCodeEvent.SessionStatusType,
        generation: Long,
    ) {
        val isBusy = status != OpenCodeEvent.SessionStatusType.IDLE
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

    private fun handleSessionDiff(event: SessionDiffSnapshot, fromHistory: Boolean, generation: Long) {
        if (generation != lifecycleGeneration.get()) return
        val projectBase = project.basePath
        if (projectBase == null) {
            log.debug("OpenCodeCoreService: skip diff.apply reason=missingProjectBase session=${event.sessionId} generation=$generation")
            return
        }

        val trackLiveApply = !fromHistory
        if (trackLiveApply) beginLiveSessionDiffApply(event.sessionId)

        val revision = stateStore.reserveRevisionForSessionDiffApply(
            stateLock = stateLock,
            sessionId = event.sessionId,
            expectedGeneration = generation,
            currentGeneration = { lifecycleGeneration.get() },
        )
        trace("diff.apply.decision", fromHistory = fromHistory) {
            mapOf(
                "sessionId" to event.sessionId,
                "fromHistory" to fromHistory,
                "fileCount" to event.files.size,
                "shouldApply" to (revision != null),
                "skipReason" to if (revision == null) "staleOrAlreadyLoaded" else null,
                "revision" to revision,
                "generation" to generation,
            )
        }
        if (revision == null) {
            if (trackLiveApply) finishLiveSessionDiffApply(event.sessionId)
            log.debug("OpenCodeCoreService: skip diff.apply reason=staleOrAlreadyLoaded session=${event.sessionId} generation=$generation")
            return
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                if (generation != lifecycleGeneration.get()) return@executeOnPooledThread

                log.debug(
                    "OpenCodeCoreService: apply diff.apply session=${event.sessionId} revision=$revision fileCount=${event.files.size} fromHistory=$fromHistory generation=$generation",
                )

                val computedState = sessionDiffApplyComputer.compute(
                    projectBase = projectBase,
                    event = event,
                    fromHistory = fromHistory,
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
                    log.debug("OpenCodeCoreService: skip diff.apply reason=staleApply session=${event.sessionId} revision=$revision generation=$generation")
                    trace("diff.apply.commitSkipped", fromHistory = fromHistory) {
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
                    "OpenCodeCoreService: applied diff.apply session=${event.sessionId} revision=$revision trackedFileCount=${computedState.newHunksByFile.size} deletedFileCount=${computedState.newDeleted.size} addedFileCount=${computedState.newAdded.size} changedFileCount=${changedFiles.size} refreshedFileCount=${filesToRefresh.size} generation=$generation",
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
            } finally {
                if (trackLiveApply) finishLiveSessionDiffApply(event.sessionId)
            }
        }
    }

    private fun beginLiveSessionDiffApply(sessionId: String) {
        liveSessionDiffApplyInFlight.compute(sessionId) { _, count ->
            (count ?: AtomicInteger(0)).also { it.incrementAndGet() }
        }
    }

    private fun finishLiveSessionDiffApply(sessionId: String) {
        liveSessionDiffApplyInFlight.computeIfPresent(sessionId) { _, count ->
            if (count.decrementAndGet() <= 0) null else count
        }
    }

    private fun hasLiveSessionDiffApplyInFlight(sessionId: String): Boolean =
        (liveSessionDiffApplyInFlight[sessionId]?.get() ?: 0) > 0

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
                "OpenCodeCoreService: reconcile session=$sessionId revision=$revision removedBaselineMatchCount=${reconcileDecision.removedPaths.size} generation=$generation",
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
        trace("diff.apply.committed", fromHistory = fromHistory) {
            // Only record file names and byte-length summaries — never store full file content
            // in a trace field, as that causes unbounded memory growth during active sessions.
            val stateSnapshot = synchronized(stateLock) {
                val baselineSizes = stateStore.baselineBeforeBySessionAndFile[sessionId]
                    ?.mapValues { (_, v) -> v.length }
                    ?: emptyMap()
                mapOf(
                    "hunkFiles" to (stateStore.hunksBySessionAndFile[sessionId]?.keys?.sorted() ?: emptyList()),
                    "liveHunkFiles" to (stateStore.liveHunksBySessionAndFile[sessionId]?.keys?.sorted()
                        ?: emptyList()),
                    "deletedFiles" to (stateStore.deletedBySession[sessionId]?.sorted() ?: emptyList()),
                    "addedFiles" to (stateStore.addedBySession[sessionId]?.sorted() ?: emptyList()),
                    "baselineBeforeSizeByFile" to baselineSizes,
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

    private fun knownSessionIdsLocked(): Set<String> =
        scopeResolver.knownSessionIds(
            hunksBySessionAndFile = stateStore.hunksBySessionAndFile,
            busyBySession = stateStore.busyBySession,
            updatedAtBySession = stateStore.updatedAtBySession,
            sessions = sessionById,
        )

    private fun hasLocalDiffStateLocked(sessionId: String): Boolean =
        stateStore.hunksBySessionAndFile.containsKey(sessionId) ||
                stateStore.addedBySession.containsKey(sessionId) ||
                stateStore.deletedBySession.containsKey(sessionId)

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

    private fun knownFamilySessionIdsForRootLocked(rootSessionId: String): Set<String> {
        val parentBySessionId = sessionById.values.mapNotNull { s -> s.parentID?.let { s.id to it } }.toMap()
        return scopeResolver.knownFamilySessionIdsForRoot(rootSessionId, parentBySessionId)
    }

    private fun familySessionIdsLocked(): Set<String> {
        val selected = resolveSelectedSessionIdLocked() ?: return emptySet()
        return scopeResolver.familySessionIds(
            selectedSessionId = selected,
            sessions = sessionById,
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
     *   - Only the selected session family contributes (Rule 1).
     *   - Only the most recent turn is represented (Rule 2).
     */
    private fun inlineLiveHunks(filePath: String): List<DiffHunk> {
        val selected = resolveSelectedSessionIdLocked()
        val family = selected?.let { selectedSessionId ->
            scopeResolver.familySessionIds(
                selectedSessionId = selectedSessionId,
                sessions = sessionById,
                knownSessionIds = knownSessionIdsLocked(),
                busyBySession = stateStore.busyBySession,
                updatedAtBySession = stateStore.updatedAtBySession,
                hunksBySessionAndFile = stateStore.hunksBySessionAndFile,
                nowMillis = System.currentTimeMillis(),
            )
        } ?: emptySet()
        val hunks = queryService.liveHunks(
            filePath = filePath,
            familySessionIds = { family },
            liveHunksBySessionAndFile = stateStore.liveHunksBySessionAndFile,
        )
        if (log.isDebugEnabled) {
            log.debug(
                "OpenCodeCoreService: inline hunks lookup file=$filePath selected=$selected family=$family returnedHunkCount=${hunks.size} liveHunkFilesBySession=${stateStore.liveHunksBySessionAndFile.mapValues { (_, files) -> files.keys.toList() }}",
            )
        }
        return hunks
    }

    /**
     * Updates the stored metadata for a single session and publishes a state change.
     *
     * Used for optimistic UI updates — e.g. after a rename completes via the API, before
     * the next SSE-triggered hierarchy refresh arrives.
     */
    fun updateSessionState(session: Session) {
        synchronized(stateLock) {
            sessionById[session.id] = session
            // Bump the local timestamp so the session sorts to the top immediately,
            // before the next hierarchy refresh arrives with the server's timestamp.
            stateStore.updatedAtBySession[session.id] = System.currentTimeMillis()
        }
        publishService.publishSessionStateChanged()
    }

    /**
     * Removes a session from all local state (metadata, diff state, busy flags, etc.)
     * and publishes a state change so the UI list updates immediately.
     *
     * If the deleted session was selected, clears the selection.
     */
    fun removeSession(sessionId: String) {
        val previousVisibleFiles = synchronized(stateLock) {
            val visible = visibleFilesLocked()

            sessionById.remove(sessionId)
            stateStore.busyBySession.remove(sessionId)
            stateStore.updatedAtBySession.remove(sessionId)
            stateStore.hunksBySessionAndFile.remove(sessionId)
            stateStore.liveHunksBySessionAndFile.remove(sessionId)
            stateStore.deletedBySession.remove(sessionId)
            stateStore.addedBySession.remove(sessionId)
            stateStore.baselineBeforeBySessionAndFile.remove(sessionId)
            stateStore.messageSummaryFileCountBySession.remove(sessionId)
            stateStore.messageSummaryFileCountUpdatedAtBySession.remove(sessionId)
            historicalDiffLoadedSessions.remove(sessionId)
            messageSummaryFileCountLoadInFlight.remove(sessionId)

            if (stateStore.selectedSessionId == sessionId) {
                stateStore.selectedSessionId = null
            }

            visible
        }

        previousVisibleFiles.forEach { publishService.publishChanged(it) }
        publishService.publishSessionStateChanged()
    }

    fun refreshSessionHierarchy() = refreshSessionHierarchyAsync()

    fun listSessions(): List<SessionInfo> = synchronized(stateLock) {
        queryService.listSessions(
            knownSessionIds = knownSessionIdsLocked(),
            sessions = sessionById,
            busyBySession = stateStore.busyBySession,
            hunksBySessionAndFile = stateStore.hunksBySessionAndFile,
            addedBySession = stateStore.addedBySession,
            deletedBySession = stateStore.deletedBySession,
            messageSummaryFileCountBySession = stateStore.messageSummaryFileCountBySession,
            updatedAtBySession = stateStore.updatedAtBySession,
        )
    }

    fun selectedSessionId(): String? = synchronized(stateLock) {
        resolveSelectedSessionIdLocked()
    }

    fun selectSession(sessionId: String?) {
        val shouldDeferSelection = synchronized(stateLock) {
            pendingSessionSelection.shouldDeferSelection(
                requestedSessionId = sessionId,
                sessionExists = { candidate -> sessionExistsLocked(candidate) },
            )
        }

        if (shouldDeferSelection) {
            refreshSessionHierarchyAsync()
            return
        }

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
            when (val result = sessionApiClient.fetchFileDiffPreview(currentPort, sessionId, projectBase, filePath)) {
                is ApiResult.Failure -> {
                    log.debug(
                        "OpenCodeCoreService: failed diff preview fetch session=$sessionId file=$filePath port=$currentPort reason=${result.error}",
                    )
                    onResult(null)
                }

                is ApiResult.Success -> {
                    val preview = result.value
                    if (preview == null) {
                        onResult(null)
                        return@executeOnPooledThread
                    }
                    onResult(
                        FileDiffPreview(
                            filePath = filePath,
                            sessionId = sessionId,
                            before = preview.before,
                            aiAfter = preview.after,
                        )
                    )
                }
            }
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

    private fun ensureMessageSummaryFileCountsLoadedAsync(sessions: List<Session>, generation: Long) {
        if (generation != lifecycleGeneration.get()) return
        val currentPort = port
        if (currentPort <= 0) return

        val sessionsToLoad = synchronized(stateLock) {
            if (generation != lifecycleGeneration.get()) return@synchronized emptyList()
            selectMessageSummaryFileCountLoadBatch(
                sessions = sessions,
                maxBatchSize = MESSAGE_SUMMARY_FILE_COUNT_LOAD_BATCH_SIZE,
            ) { session ->
                if (hasLocalDiffStateLocked(session.id)) return@selectMessageSummaryFileCountLoadBatch false
                val loadedAt = stateStore.messageSummaryFileCountUpdatedAtBySession[session.id]
                loadedAt == null || loadedAt < session.time.updated
            }
        }

        for (session in sessionsToLoad) {
            if (messageSummaryFileCountLoadInFlight.size >= MESSAGE_SUMMARY_FILE_COUNT_LOAD_BATCH_SIZE) break
            if (!messageSummaryFileCountLoadInFlight.add(session.id)) continue
            if (messageSummaryFileCountLoadInFlight.size > MESSAGE_SUMMARY_FILE_COUNT_LOAD_BATCH_SIZE) {
                messageSummaryFileCountLoadInFlight.remove(session.id)
                break
            }

            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    if (generation != lifecycleGeneration.get()) return@executeOnPooledThread
                    val summaries =
                        when (val result = sessionApiClient.fetchSessionMessageDiffSummaries(currentPort, session.id)) {
                            is ApiResult.Failure -> {
                                log.debug(
                                    "OpenCodeCoreService: skip message summary file count reason=requestFailed session=${session.id} port=$currentPort generation=$generation error=${result.error}",
                                )
                                return@executeOnPooledThread
                            }

                            is ApiResult.Success -> result.value
                        }
                    val fileCount = summaries
                        .filter { it.role == "user" }
                        .flatMap { it.files }
                        .toSet()
                        .size
                    val changed = synchronized(stateLock) {
                        if (generation != lifecycleGeneration.get()) return@synchronized false
                        if (hasLocalDiffStateLocked(session.id)) return@synchronized false
                        val previous = stateStore.messageSummaryFileCountBySession[session.id]
                        stateStore.messageSummaryFileCountBySession[session.id] = fileCount
                        stateStore.messageSummaryFileCountUpdatedAtBySession[session.id] = session.time.updated
                        previous != fileCount
                    }
                    if (changed && generation == lifecycleGeneration.get()) {
                        publishService.publishSessionStateChanged()
                    }
                } catch (e: Exception) {
                    log.debug(
                        "OpenCodeCoreService: failed message summary file count load session=${session.id} port=$currentPort generation=$generation",
                        e,
                    )
                } finally {
                    messageSummaryFileCountLoadInFlight.remove(session.id)
                }
            }
        }
    }

    private fun ensureHistoricalDiffLoadedAsync(sessionId: String, generation: Long) {
        if (generation != lifecycleGeneration.get()) return
        if (sessionId.isBlank()) return
        if (historicalDiffLoadedSessions.contains(sessionId)) return
        if (hasLiveSessionDiffApplyInFlight(sessionId)) return
        if (!historicalDiffLoadInFlight.add(sessionId)) return

        val currentPort = port
        if (currentPort <= 0) {
            historicalDiffLoadInFlight.remove(sessionId)
            return
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                if (generation != lifecycleGeneration.get()) return@executeOnPooledThread
                if (hasLiveSessionDiffApplyInFlight(sessionId)) return@executeOnPooledThread

                val event = when (val result = sessionApiClient.fetchSessionDiffSnapshot(currentPort, sessionId)) {
                    is ApiResult.Failure -> {
                        log.debug(
                            "OpenCodeCoreService: skip historical snapshot reason=requestFailed session=$sessionId port=$currentPort generation=$generation error=${result.error}",
                        )
                        return@executeOnPooledThread
                    }

                    is ApiResult.Success -> result.value
                }
                if (generation != lifecycleGeneration.get()) return@executeOnPooledThread
                if (hasLiveSessionDiffApplyInFlight(sessionId)) return@executeOnPooledThread

                handleSessionDiff(event, fromHistory = true, generation = generation)
            } catch (e: Exception) {
                log.debug(
                    "OpenCodeCoreService: failed historical snapshot load session=$sessionId port=$currentPort generation=$generation",
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

                val sessions: List<Session> = when (val result = sessionApiClient.fetchSessionHierarchy(currentPort)) {
                    is ApiResult.Success -> result.value
                    is ApiResult.Failure -> {
                        log.debug(
                            "OpenCodeCoreService: skip hierarchy refresh reason=requestFailed port=$currentPort generation=$generation",
                        )
                        return@executeOnPooledThread
                    }
                }
                if (sessions.isEmpty()) {
                    log.debug("OpenCodeCoreService: skip hierarchy refresh reason=emptySnapshot port=$currentPort generation=$generation")
                    return@executeOnPooledThread
                }

                val newSessionIds = sessions.map { it.id }.toSet()

                val (changed, hierarchyLiveFilesToRefresh) = synchronized(stateLock) {
                    if (generation != lifecycleGeneration.get()) return@synchronized false to emptySet<String>()
                    val idsChanged = sessionById.keys != newSessionIds
                    val sessionsChanged = idsChanged || sessions.any { sessionById[it.id] != it }
                    val previousLiveVisibleFiles = liveVisibleFilesLocked()

                    // Seed updatedAtBySession from the server-reported timestamps for sessions
                    // that have no locally-observed timestamp yet (or whose local timestamp is older).
                    // This ensures the session list is ordered correctly (newest first) even before
                    // any SSE events have been received for a session.
                    var timestampsChanged = false
                    for (session in sessions) {
                        val serverUpdatedAt = session.time.updated
                        val localUpdatedAt = stateStore.updatedAtBySession[session.id] ?: 0L
                        if (serverUpdatedAt > localUpdatedAt) {
                            stateStore.updatedAtBySession[session.id] = serverUpdatedAt
                            timestampsChanged = true
                        }
                    }

                    if (!sessionsChanged && !timestampsChanged) {
                        false to emptySet()
                    } else {
                        sessionById.clear()
                        sessions.forEach { sessionById[it.id] = it }
                        val nextLiveVisibleFiles = liveVisibleFilesLocked()
                        true to (previousLiveVisibleFiles + nextLiveVisibleFiles)
                    }
                }

                if (generation != lifecycleGeneration.get()) return@executeOnPooledThread

                val deferredSelection = synchronized(stateLock) {
                    if (generation != lifecycleGeneration.get()) return@synchronized null
                    pendingSessionSelection.consumeIfResolved(newSessionIds)
                }

                if (changed) {
                    hierarchyLiveFilesToRefresh.forEach { publishService.publishChanged(it) }
                    publishService.publishSessionStateChanged()
                }
                ensureMessageSummaryFileCountsLoadedAsync(sessions, generation)

                if (!deferredSelection.isNullOrBlank()) {
                    selectSession(deferredSelection)
                }
                val selected = synchronized(stateLock) {
                    if (generation != lifecycleGeneration.get()) return@synchronized null
                    resolveSelectedSessionIdLocked()
                }
                if (selected != null) {
                    ensureHistoricalFamilyLoadedAsync(selected, generation)
                }
            } catch (e: Exception) {
                log.debug("OpenCodeCoreService: failed hierarchy refresh port=$currentPort generation=$generation", e)
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
        sessionById.clear()
        documentSyncService.reset()
        messageDiffFetchCoalescer.clear()
        historicalDiffLoadInFlight.clear()
        historicalDiffLoadedSessions.clear()
        liveSessionDiffApplyInFlight.clear()
        messageSummaryFileCountLoadInFlight.clear()
        reconcileScheduleTokenBySession.clear()
        pendingSessionSelection.clear()
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
        private const val MESSAGE_SUMMARY_FILE_COUNT_LOAD_BATCH_SIZE = 4

        fun getInstance(project: Project): OpenCodeCoreService =
            project.getService(OpenCodeCoreService::class.java)
    }
}
