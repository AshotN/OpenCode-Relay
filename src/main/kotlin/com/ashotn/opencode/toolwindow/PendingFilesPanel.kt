package com.ashotn.opencode.toolwindow

import com.ashotn.opencode.diff.DiffHighlightKind
import com.ashotn.opencode.diff.DiffHighlightStyles
import com.ashotn.opencode.diff.DiffHunksChangedListener
import com.ashotn.opencode.diff.OpenCodeDiffService
import com.ashotn.opencode.diff.SessionStateChangedListener
import com.ashotn.opencode.ipc.OpenCodeEvent
import com.ashotn.opencode.ipc.PermissionChangedListener
import com.ashotn.opencode.ipc.PermissionReply
import com.ashotn.opencode.permission.OpenCodePermissionService
import com.intellij.diff.DiffManager
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.AnimatedIcon
import com.intellij.util.ui.EmptyIcon
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.GridLayout
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.ListSelectionModel
import javax.swing.border.MatteBorder

/**
 * Shows the session list and files with AI session diff highlights.
 * Double-clicking a file opens a before/after diff preview.
 */
class PendingFilesPanel(private val project: Project) : JPanel(BorderLayout()) {

    private data class PendingFileRow(
        val path: String,
        val name: String,
        val displayDir: String,
        val isDeleted: Boolean,
        val isAdded: Boolean,
    )

    private data class SessionRow(
        val sessionId: String,
        val title: String,
        val description: String,
        val isBusy: Boolean,
        val trackedFileCount: Int,
    )

    private val fileListModel = DefaultListModel<PendingFileRow>()
    private val fileList = JBList(fileListModel).apply {
        cellRenderer = FilePathCellRenderer()
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        visibleRowCount = 6
    }

    private val sessionListModel = DefaultListModel<SessionRow>()
    private val sessionList = JBList(sessionListModel).apply {
        cellRenderer = SessionRowCellRenderer()
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        visibleRowCount = 5
    }
    private val sessionScrollPane = JBScrollPane(sessionList)

    private val refreshScheduled = AtomicBoolean(false)
    private var didApplyInitialSessionSelection = false
    private val busySessionIcon = AnimatedIcon.Default.INSTANCE
    private val idleSessionIcon = EmptyIcon.create(busySessionIcon.iconWidth, busySessionIcon.iconHeight)

    private val permissionLabel = JBLabel("").apply {
        font = UIUtil.getLabelFont(UIUtil.FontSize.SMALL)
        border = JBUI.Borders.empty(4, 8, 2, 8)
    }

    private val permissionPanel = JPanel(BorderLayout()).apply {
        val allowButton = JButton("Allow").apply {
            background = JBColor(Color(0x2E7D32), Color(0x1B5E20))
            foreground = Color.WHITE
            isFocusPainted = false
            isOpaque = true
            addActionListener {
                OpenCodePermissionService.getInstance(project).replyToPermission(PermissionReply.ONCE)
            }
        }
        val allowAlwaysButton = JButton("Allow Always").apply {
            background = JBColor(Color(0x1565C0), Color(0x0D47A1))
            foreground = Color.WHITE
            isFocusPainted = false
            isOpaque = true
            addActionListener {
                OpenCodePermissionService.getInstance(project).replyToPermission(PermissionReply.ALWAYS)
            }
        }
        val rejectButton = JButton("Reject").apply {
            background = JBColor(Color(0xC62828), Color(0xB71C1C))
            foreground = Color.WHITE
            isFocusPainted = false
            isOpaque = true
            addActionListener {
                OpenCodePermissionService.getInstance(project).replyToPermission(PermissionReply.REJECT)
            }
        }
        val buttons = JPanel(GridLayout(1, 3)).apply {
            add(allowButton)
            add(allowAlwaysButton)
            add(rejectButton)
        }
        add(permissionLabel, BorderLayout.NORTH)
        add(buttons, BorderLayout.CENTER)
        isVisible = false
    }

    init {
        border = MatteBorder(1, 0, 0, 0, JBColor.border())

        val header = JBLabel("Session changes").apply {
            font = UIUtil.getLabelFont(UIUtil.FontSize.SMALL)
            foreground = JBUI.CurrentTheme.Label.disabledForeground()
            border = JBUI.Borders.empty(6, 8, 4, 8)
        }

        val sessionsHeader = JBLabel("Sessions (family root)").apply {
            font = UIUtil.getLabelFont(UIUtil.FontSize.SMALL)
            foreground = JBUI.CurrentTheme.Label.disabledForeground()
            border = JBUI.Borders.empty(0, 8, 2, 8)
        }

        sessionList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount >= 1) {
                    val row = sessionList.selectedValue ?: return
                    OpenCodeDiffService.getInstance(project).selectSession(row.sessionId)
                    refresh()
                }
            }
        })

        val sessionSection = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyBottom(4)
            add(sessionsHeader, BorderLayout.NORTH)
            add(sessionScrollPane, BorderLayout.CENTER)
            minimumSize = JBUI.size(120, 90)
        }

        val filesHeader = JBLabel("Files").apply {
            font = UIUtil.getLabelFont(UIUtil.FontSize.SMALL)
            foreground = JBUI.CurrentTheme.Label.disabledForeground()
            border = JBUI.Borders.empty(0, 8, 2, 8)
        }

        fileList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount < 2) return
                val row = fileList.selectedValue ?: return
                openBuiltInDiff(row)
            }
        })

        val filesSection = JPanel(BorderLayout()).apply {
            add(filesHeader, BorderLayout.NORTH)
            add(JBScrollPane(fileList), BorderLayout.CENTER)
            minimumSize = JBUI.size(120, 140)
        }

        val splitPane = JSplitPane(JSplitPane.VERTICAL_SPLIT, sessionSection, filesSection).apply {
            resizeWeight = 0.35
            setContinuousLayout(true)
            setOneTouchExpandable(true)
            border = JBUI.Borders.empty()
        }

        val contentCenter = JPanel(BorderLayout()).apply {
            add(splitPane, BorderLayout.CENTER)
        }

        val body = JPanel(BorderLayout()).apply {
            add(contentCenter, BorderLayout.CENTER)
        }

        add(header, BorderLayout.NORTH)
        add(body, BorderLayout.CENTER)
        add(permissionPanel, BorderLayout.SOUTH)

        project.messageBus.connect().subscribe(
            DiffHunksChangedListener.TOPIC,
            DiffHunksChangedListener { _ -> requestRefresh() },
        )

        project.messageBus.connect().subscribe(
            SessionStateChangedListener.TOPIC,
            SessionStateChangedListener { requestRefresh() },
        )

        project.messageBus.connect().subscribe(
            PermissionChangedListener.TOPIC,
            PermissionChangedListener { event ->
                ApplicationManager.getApplication().invokeLater { onPermissionChanged(event) }
            },
        )

        val permissionService = OpenCodePermissionService.getInstance(project)
        onPermissionChanged(permissionService.currentPermission())
    }

    private fun requestRefresh() {
        if (!refreshScheduled.compareAndSet(false, true)) return
        ApplicationManager.getApplication().invokeLater {
            refreshScheduled.set(false)
            refresh()
        }
    }

    private fun onPermissionChanged(event: OpenCodeEvent.PermissionAsked?) {
        if (event != null) {
            val detail = event.metadata.entries.firstOrNull()
                ?.let { (k, v) -> "$k: $v" }
                ?: event.patterns.firstOrNull()
                ?: event.permission
            permissionLabel.text = "<html><b>${event.permission}</b>" +
                    if (detail != event.permission) "&nbsp;&nbsp;$detail" else "" +
                            "</html>"
            permissionPanel.isVisible = true
        } else {
            permissionPanel.isVisible = false
        }
        refresh()
    }

    private fun refresh() {
        val diffService = OpenCodeDiffService.getInstance(project)
        val permissionService = OpenCodePermissionService.getInstance(project)

        val sessions = diffService.listSessions()
        val selectedSessionId = diffService.selectedSessionId()
        updateSessionList(sessions, selectedSessionId)

        val rows = diffService.allTrackedFiles()
            .sorted()
            .map { path ->
                val name = path.substringAfterLast('/')
                val fullDir = path.substringBeforeLast('/', "")
                val projectBase = project.basePath ?: ""
                val relativeDir = if (projectBase.isNotBlank() && fullDir.startsWith(projectBase)) {
                    fullDir.removePrefix(projectBase).trimStart('/')
                } else {
                    fullDir
                }
                PendingFileRow(
                    path = path,
                    name = name,
                    displayDir = relativeDir.ifEmpty { "/" },
                    isDeleted = diffService.isDeleted(path),
                    isAdded = diffService.isAdded(path),
                )
            }

        fileListModel.clear()
        rows.forEach { fileListModel.addElement(it) }

        val hasPermission = permissionService.currentPermission() != null
        isVisible = rows.isNotEmpty() || hasPermission || sessions.isNotEmpty()
        revalidate()
        repaint()
    }

    private fun updateSessionList(
        sessions: List<OpenCodeDiffService.SessionInfo>,
        selectedSessionId: String?,
    ) {
        val rows = sessions
            .filter { it.parentSessionId == null }
            .map { session ->
                SessionRow(
                    sessionId = session.sessionId,
                    title = session.title ?: session.sessionId.take(12),
                    description = session.description ?: session.sessionId,
                    isBusy = session.isBusy,
                    trackedFileCount = session.trackedFileCount,
                )
            }

        sessionListModel.clear()
        rows.forEach { sessionListModel.addElement(it) }

        val selectedIndex = selectedSessionId
            ?.let { sessionId -> rows.indexOfFirst { it.sessionId == sessionId } }
            ?.takeIf { it >= 0 }

        if (selectedIndex != null) {
            sessionList.selectedIndex = selectedIndex
        } else {
            sessionList.clearSelection()
        }

        if (!didApplyInitialSessionSelection && rows.isNotEmpty()) {
            didApplyInitialSessionSelection = true
            ApplicationManager.getApplication().invokeLater {
                if (sessionListModel.isEmpty) return@invokeLater
                sessionScrollPane.viewport.viewPosition = Point(0, 0)
                sessionList.ensureIndexIsVisible(0)
            }
        }
    }

    private fun openBuiltInDiff(row: PendingFileRow) {
        val diffService = OpenCodeDiffService.getInstance(project)
        val preview = diffService.getFileDiffPreview(row.path) ?: return

        val title = "OpenCode Diff: ${row.name}"
        val request = SimpleDiffRequest(
            title,
            DiffContentFactory.getInstance().create(project, preview.before),
            DiffContentFactory.getInstance().create(project, preview.after),
            "Session baseline",
            "Current file",
        )

        ApplicationManager.getApplication().invokeLater {
            DiffManager.getInstance().showDiff(project, request)
        }
    }

    private inner class SessionRowCellRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>, value: Any?, index: Int,
            isSelected: Boolean, cellHasFocus: Boolean,
        ): Component {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            val row = value as? SessionRow ?: return this

            icon = if (row.isBusy) busySessionIcon else idleSessionIcon
            iconTextGap = JBUI.scale(6)
            val dimColor = if (isSelected) foreground else JBUI.CurrentTheme.Label.disabledForeground()
            val dimHex = String.format("%06x", dimColor.rgb and 0xFFFFFF)
            val titleColor = when {
                isSelected -> foreground
                row.isBusy -> JBColor(Color(0x1565C0), Color(0x90CAF9))
                else -> foreground
            }
            val titleHex = String.format("%06x", titleColor.rgb and 0xFFFFFF)
            val statusText = if (row.isBusy) "&nbsp;<font color='#$dimHex'>running...</font>" else ""
            val safeDescription = row.description.ifBlank { row.sessionId }
            text =
                "<html><b><font color='#$titleHex'>${row.title}</font></b>$statusText&nbsp;<font color='#$dimHex'>(${row.trackedFileCount})</font><br/><font color='#$dimHex'>$safeDescription</font></html>"
            border = JBUI.Borders.empty(4, 8)
            return this
        }
    }

    private inner class FilePathCellRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>, value: Any?, index: Int,
            isSelected: Boolean, cellHasFocus: Boolean,
        ): Component {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)

            val row = value as? PendingFileRow ?: return this

            if (row.isDeleted) {
                val removedStyle = DiffHighlightStyles.style(DiffHighlightKind.REMOVED)
                val removedColor = if (isSelected) foreground else (removedStyle.fg ?: JBColor.RED)
                val removedHex = String.format("%06x", removedColor.rgb and 0xFFFFFF)
                val dimColor = if (isSelected) foreground else JBUI.CurrentTheme.Label.disabledForeground()
                val dimHex = String.format("%06x", dimColor.rgb and 0xFFFFFF)
                text =
                    "<html><font color='#$removedHex'><s><b>${row.name}</b></s></font>&nbsp;<font color='#$dimHex'>${row.displayDir}</font></html>"
            } else if (row.isAdded) {
                val addedStyle = DiffHighlightStyles.style(DiffHighlightKind.ADDED)
                val addedColor =
                    if (isSelected) foreground else (addedStyle.fg ?: JBColor(Color(0x2E7D32), Color(0x66BB6A)))
                val addedHex = String.format("%06x", addedColor.rgb and 0xFFFFFF)
                val dimColor = if (isSelected) foreground else JBUI.CurrentTheme.Label.disabledForeground()
                val dimHex = String.format("%06x", dimColor.rgb and 0xFFFFFF)
                text =
                    "<html><font color='#$addedHex'><b>${row.name}</b></font>&nbsp;<font color='#$dimHex'>${row.displayDir}</font></html>"
            } else {
                val dimColor = if (isSelected) foreground else JBUI.CurrentTheme.Label.disabledForeground()
                val hex = String.format("%06x", dimColor.rgb and 0xFFFFFF)
                text = "<html><b>${row.name}</b>&nbsp;<font color='#$hex'>${row.displayDir}</font></html>"
            }

            border = JBUI.Borders.empty(2, 8)
            return this
        }
    }
}
