package com.ashotn.opencode.companion.toolwindow

import com.ashotn.opencode.companion.actions.ClearSelectedSessionAction
import com.ashotn.opencode.companion.actions.NewSessionAction
import com.ashotn.opencode.companion.diff.DiffHighlightKind
import com.ashotn.opencode.companion.diff.DiffHighlightStyles
import com.ashotn.opencode.companion.diff.DiffHunksChangedListener
import com.ashotn.opencode.companion.diff.OpenCodeDiffService
import com.ashotn.opencode.companion.tui.OpenCodeTuiClient
import com.ashotn.opencode.companion.diff.SessionStateChangedListener
import com.ashotn.opencode.companion.util.toProjectRelativePath
import com.ashotn.opencode.companion.ipc.OpenCodeEvent
import com.ashotn.opencode.companion.ipc.PermissionChangedListener
import com.ashotn.opencode.companion.ipc.PermissionReply
import com.ashotn.opencode.companion.permission.OpenCodePermissionService
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.editor.DiffEditorTabFilesManager
import com.intellij.diff.editor.SimpleDiffVirtualFile
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
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
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Point
import java.awt.RenderingHints
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.KeyStroke
import javax.swing.plaf.basic.BasicSplitPaneDivider
import javax.swing.plaf.basic.BasicSplitPaneUI
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.ListSelectionModel
import javax.swing.Timer
import javax.swing.border.MatteBorder
import javax.swing.event.ListSelectionEvent
import javax.swing.event.ListSelectionListener

/**
 * Shows the session list and files with AI session diff highlights.
 * Double-clicking a file opens a before/after diff preview.
 */
class PendingFilesPanel(private val project: Project, parentDisposable: Disposable) : JPanel(BorderLayout()), Disposable {

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
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        visibleRowCount = 6
    }

    private val sessionListModel = DefaultListModel<SessionRow>()
    private val sessionList = JBList(sessionListModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        visibleRowCount = 5
    }
    private val sessionScrollPane = JBScrollPane(sessionList)

    // Tracks open diff editor tabs by (filePath, sessionId) so reuse is scoped to the same session.
    private val openDiffFiles = HashMap<Pair<String, String>, SimpleDiffVirtualFile>()

    private val refreshScheduled = AtomicBoolean(false)
    private var didApplyInitialSessionSelection = false
    private var isUpdatingSessionSelection = false
    private val busySessionIcon = AnimatedIcon(50, *AnimatedIcon.Default.ICONS.toTypedArray())
    private val idleSessionIcon = EmptyIcon.create(busySessionIcon.iconWidth, busySessionIcon.iconHeight)

    // Repaints the session list at the spinner frame rate so AnimatedIcon advances smoothly.
    // Only runs while at least one session is busy.
    private val spinnerTimer = Timer(50) { sessionList.repaint() }

    private val permissionLabel = JBLabel("").apply {
        font = UIUtil.getLabelFont(UIUtil.FontSize.SMALL)
        border = JBUI.Borders.empty(4, 8, 2, 8)
    }

    private val permissionPanel = JPanel(BorderLayout()).apply {
        val allowButton = permissionButton(
            label = "Allow",
            bg = JBColor(Color(0x2E7D32), Color(0x388E3C)),
            hover = JBColor(Color(0x388E3C), Color(0x43A047)),
        ) {
            OpenCodePermissionService.getInstance(project).replyToPermission(PermissionReply.ONCE)
        }
        val allowAlwaysButton = permissionButton(
            label = "Allow Always",
            bg = JBColor(Color(0x1565C0), Color(0x1976D2)),
            hover = JBColor(Color(0x1976D2), Color(0x1E88E5)),
        ) {
            OpenCodePermissionService.getInstance(project).replyToPermission(PermissionReply.ALWAYS)
        }
        val rejectButton = permissionButton(
            label = "Reject",
            bg = JBColor(Color(0xC62828), Color(0xD32F2F)),
            hover = JBColor(Color(0xD32F2F), Color(0xE53935)),
        ) {
            OpenCodePermissionService.getInstance(project).replyToPermission(PermissionReply.REJECT)
        }
        val buttons = JPanel(FlowLayout(FlowLayout.CENTER, JBUI.scale(6), 0)).apply {
            isOpaque = false
            add(allowButton)
            add(allowAlwaysButton)
            add(rejectButton)
        }
        val buttonsWrapper = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 8, 8, 8)
            add(buttons, BorderLayout.CENTER)
        }
        add(permissionLabel, BorderLayout.NORTH)
        add(buttonsWrapper, BorderLayout.CENTER)
        isVisible = false
    }

    init {
        fileList.cellRenderer = FilePathCellRenderer()
        sessionList.cellRenderer = SessionRowCellRenderer()

        border = MatteBorder(1, 0, 0, 0, JBColor.border())

        val sessionActionsGroup = DefaultActionGroup().apply {
            add(NewSessionAction(project))
            add(ClearSelectedSessionAction(project))
        }
        val sessionActionsToolbar = ActionManager.getInstance()
            .createActionToolbar("OpenCode.SessionListHeader", sessionActionsGroup, true).apply {
                targetComponent = this@PendingFilesPanel
            }

        val sessionHeaderLabel = JBLabel("Sessions").apply {
            font = UIUtil.getLabelFont(UIUtil.FontSize.SMALL)
            foreground = JBUI.CurrentTheme.Label.disabledForeground()
            border = JBUI.Borders.empty(4, 8, 2, 4)
        }

        val sessionHeader = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(sessionHeaderLabel, BorderLayout.WEST)
            add(sessionActionsToolbar.component, BorderLayout.EAST)
        }

        sessionList.addListSelectionListener(object : ListSelectionListener {
            override fun valueChanged(e: ListSelectionEvent) {
                if (e.valueIsAdjusting) return
                if (isUpdatingSessionSelection) return
                val row = sessionList.selectedValue ?: return
                val diffService = OpenCodeDiffService.getInstance(project)
                diffService.selectSession(row.sessionId)
                OpenCodeTuiClient.getInstance(project).selectTuiSession(row.sessionId)
                refresh()
            }
        })

        val sessionSection = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyRight(4)
            add(sessionHeader, BorderLayout.NORTH)
            add(sessionScrollPane, BorderLayout.CENTER)
            minimumSize = JBUI.size(200, 60)
        }

        val filesHeader = JBLabel("Files").apply {
            font = UIUtil.getLabelFont(UIUtil.FontSize.SMALL)
            foreground = JBUI.CurrentTheme.Label.disabledForeground()
            border = JBUI.Borders.empty(4, 8, 2, 8)
        }

        fileList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount < 2) return
                val row = fileList.selectedValue ?: return
                if (row.isDeleted) return
                openBuiltInDiff(row)
            }

            //For right click
            override fun mousePressed(e: MouseEvent) = maybeShowPopup(e)
            override fun mouseReleased(e: MouseEvent) = maybeShowPopup(e)

            private fun maybeShowPopup(e: MouseEvent) {
                if (!e.isPopupTrigger) return

                // Select the row under the cursor so menu actions operate on the right item.
                val index = fileList.locationToIndex(e.point)
                if (index >= 0) fileList.selectedIndex = index

                val row = fileList.selectedValue ?: return

                val popup = JPopupMenu()

                val jumpItem = JMenuItem("Jump to Source")
                jumpItem.isEnabled = !row.isDeleted
                jumpItem.addActionListener {
                    val vFile = LocalFileSystem.getInstance().findFileByPath(row.path) ?: return@addActionListener
                    OpenFileDescriptor(project, vFile).navigate(true)
                }
                popup.add(jumpItem)

                val diffItem = JMenuItem("Open Diff")
                diffItem.isEnabled = !row.isDeleted
                diffItem.addActionListener {
                    openBuiltInDiff(row)
                }
                popup.add(diffItem)

                popup.show(fileList, e.x, e.y)
            }
        })

        val filesSection = JPanel(BorderLayout()).apply {
            add(filesHeader, BorderLayout.NORTH)
            add(JBScrollPane(fileList), BorderLayout.CENTER)
            minimumSize = JBUI.size(60, 60)
        }

        val splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sessionSection, filesSection).apply {
            resizeWeight = 0.85
            isContinuousLayout = true
            dividerSize = JBUI.scale(2)
            OpenCodeToolWindowPanel.applyThemedDivider(this)
        }

        add(splitPane, BorderLayout.CENTER)
        add(permissionPanel, BorderLayout.SOUTH)

        // Esc clears the selected session when the panel (or any child) has focus.
        val escAction = object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent) {
                OpenCodeDiffService.getInstance(project).selectSession(null)
            }
        }
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "clearSession")
        actionMap.put("clearSession", escAction)

        // "Jump to Source" — registered as an IDE AnAction so it participates in the IDE's
        // action dispatch and respects user keymap remappings (CommonShortcuts.getEditSource()
        // is the live shortcut set for EditSourceAction, typically F4).
        object : AnAction() {
            override fun actionPerformed(e: AnActionEvent) {
                val row = fileList.selectedValue ?: return
                if (row.isDeleted) return
                val vFile = LocalFileSystem.getInstance().findFileByPath(row.path) ?: return
                OpenFileDescriptor(project, vFile).navigate(true)
            }
        }.registerCustomShortcutSet(CommonShortcuts.getEditSource(), fileList)

        Disposer.register(parentDisposable, this)

        project.messageBus.connect(this).subscribe(
            DiffHunksChangedListener.TOPIC,
            DiffHunksChangedListener { _ -> requestRefresh() },
        )

        project.messageBus.connect(this).subscribe(
            SessionStateChangedListener.TOPIC,
            SessionStateChangedListener { requestRefresh() },
        )

        project.messageBus.connect(this).subscribe(
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
                    (if (detail != event.permission) "&nbsp;&nbsp;$detail" else "") +
                    "</html>"
            permissionPanel.isVisible = true
        } else {
            permissionPanel.isVisible = false
        }
        refresh()
    }

    private fun refresh() {
        val diffService = OpenCodeDiffService.getInstance(project)

        val sessions = diffService.listSessions()
        val selectedSessionId = diffService.selectedSessionId()
        updateSessionList(sessions, selectedSessionId)

        val rows = diffService.allTrackedFiles()
            .sorted()
            .map { path ->
                val name = path.substringAfterLast('/')
                val fullDir = path.substringBeforeLast('/', "")
                val projectBase = project.basePath ?: ""
                val relativeDir = if (projectBase.isNotBlank()) fullDir.toProjectRelativePath(projectBase) else fullDir
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

        val selectedIndex = selectedSessionId
            ?.let { sessionId -> rows.indexOfFirst { it.sessionId == sessionId } }
            ?.takeIf { it >= 0 }

        isUpdatingSessionSelection = true
        try {
            sessionListModel.clear()
            rows.forEach { sessionListModel.addElement(it) }

            if (selectedIndex != null) {
                sessionList.selectedIndex = selectedIndex
            } else {
                sessionList.clearSelection()
            }
        } finally {
            isUpdatingSessionSelection = false
        }

        val anyBusy = rows.any { it.isBusy }
        if (anyBusy && !spinnerTimer.isRunning) spinnerTimer.start()
        else if (!anyBusy && spinnerTimer.isRunning) spinnerTimer.stop()

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
        diffService.getFileDiffPreview(row.path) { preview ->
            if (preview == null) return@getFileDiffPreview
            ApplicationManager.getApplication().invokeLater {
                val key = row.path to preview.sessionId

                // If a diff tab for this file+session is already open, focus it.
                val existing = openDiffFiles[key]
                if (existing != null && FileEditorManager.getInstance(project).isFileOpen(existing)) {
                    FileEditorManager.getInstance(project).openFile(existing, true)
                    return@invokeLater
                }

                val contentFactory = DiffContentFactory.getInstance()
                val vFile = LocalFileSystem.getInstance().findFileByPath(row.path) ?: return@invokeLater
                val title = "OpenCode Diff: ${row.name}"

                // 3-panel diff: Before AI | Current file (live) | After AI.
                // "Current file" is the live VirtualFile so it reflects unsaved edits too.
                // "After AI" is the server's intended result — useful to see what the AI
                // originally wrote, even if the user has since modified the file on top.
                val beforeContent = contentFactory.create(project, preview.before, vFile)
                val currentContent = contentFactory.create(project, vFile)
                val aiAfterContent = contentFactory.create(project, preview.aiAfter, vFile)
                val request = SimpleDiffRequest(
                    title,
                    beforeContent,
                    currentContent,
                    aiAfterContent,
                    "Before AI",
                    "Current file",
                    "After AI",
                )

                val diffVirtualFile = SimpleDiffVirtualFile(request)
                openDiffFiles[key] = diffVirtualFile
                DiffEditorTabFilesManager.getInstance(project).showDiffFile(diffVirtualFile, true)
            }
        }
    }

    private fun permissionButton(
        label: String,
        bg: JBColor,
        hover: JBColor,
        onClick: () -> Unit,
    ): JButton {
        var hovered = false
        return object : JButton(label) {
            init {
                // Force BasicButtonUI so Darcula/IntelliJ L&F delegates cannot override
                // isContentAreaFilled=false and paint their own opaque chrome.
                setUI(javax.swing.plaf.basic.BasicButtonUI())
                foreground = JBColor.WHITE
                isContentAreaFilled = false
                isBorderPainted = false
                isFocusPainted = false
                isOpaque = false
                font = font.deriveFont(Font.BOLD).deriveFont(JBUI.scaleFontSize(11f))
                border = BorderFactory.createEmptyBorder(
                    JBUI.scale(5), JBUI.scale(12), JBUI.scale(5), JBUI.scale(12)
                )
                cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                addActionListener { onClick() }
                addMouseListener(object : MouseAdapter() {
                    override fun mouseEntered(e: MouseEvent) { hovered = true; repaint() }
                    override fun mouseExited(e: MouseEvent) { hovered = false; repaint() }
                })
            }

            override fun paintComponent(g: java.awt.Graphics) {
                val g2 = g.create() as java.awt.Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val arc = JBUI.scale(6)
                g2.color = if (hovered) hover else bg
                // width-1/height-1: fillRoundRect excludes the last pixel row/column
                g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
                if (isFocusOwner) {
                    g2.color = JBColor(Color(0, 0, 0, 180), Color(255, 255, 255, 180))
                    g2.stroke = java.awt.BasicStroke(JBUI.scale(2).toFloat())
                    val inset = JBUI.scale(2)
                    g2.drawRoundRect(inset, inset, width - 1 - inset * 2, height - 1 - inset * 2, arc, arc)
                }
                g2.dispose()
                super.paintComponent(g)
            }
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

    override fun dispose() {
        spinnerTimer.stop()
        // Message bus connections are closed automatically by Disposer when this is disposed.
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
