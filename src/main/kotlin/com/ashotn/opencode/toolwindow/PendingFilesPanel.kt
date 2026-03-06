package com.ashotn.opencode.toolwindow

import com.ashotn.opencode.diff.DiffHighlightKind
import com.ashotn.opencode.diff.DiffHighlightStyles
import com.ashotn.opencode.diff.DiffHunksChangedListener
import com.ashotn.opencode.diff.OpenCodeDiffService
import com.ashotn.opencode.ipc.OpenCodeEvent
import com.ashotn.opencode.ipc.PermissionChangedListener
import com.ashotn.opencode.permission.OpenCodePermissionService
import com.ashotn.opencode.ipc.PermissionReply
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.GridLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.border.MatteBorder

/**
 * Shows the list of files with AI session diff highlights.
 * Clicking a file opens it in the editor and scrolls to the first hunk.
 */
class PendingFilesPanel(private val project: Project) : JPanel(BorderLayout()) {

    private data class PendingFileRow(
        val path: String,
        val name: String,
        val displayDir: String,
        val isDeleted: Boolean,
        val isAdded: Boolean,
    )

    private val listModel = DefaultListModel<PendingFileRow>()
    private val fileList = JBList(listModel).apply {
        cellRenderer = FilePathCellRenderer()
        selectionMode = javax.swing.ListSelectionModel.SINGLE_SELECTION
        visibleRowCount = 5
    }

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

        val header = JBLabel("Session Changes").apply {
            font = UIUtil.getLabelFont(UIUtil.FontSize.SMALL)
            foreground = JBUI.CurrentTheme.Label.disabledForeground()
            border = JBUI.Borders.empty(6, 8, 4, 8)
        }

        add(header, BorderLayout.NORTH)
        add(JBScrollPane(fileList), BorderLayout.CENTER)
        add(permissionPanel, BorderLayout.SOUTH)

        fileList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount >= 1) {
                    val row = fileList.selectedValue ?: return
                    openFile(row)
                }
            }
        })

        project.messageBus.connect().subscribe(
            DiffHunksChangedListener.TOPIC,
            DiffHunksChangedListener { _ ->
                ApplicationManager.getApplication().invokeLater { refresh() }
            },
        )

        project.messageBus.connect().subscribe(
            PermissionChangedListener.TOPIC,
            PermissionChangedListener { event ->
                ApplicationManager.getApplication().invokeLater { onPermissionChanged(event) }
            },
        )

        val permissionService = OpenCodePermissionService.getInstance(project)
        onPermissionChanged(permissionService.currentPermission())
        refresh()
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

        listModel.clear()
        rows.forEach { listModel.addElement(it) }

        val hasPermission = permissionService.currentPermission() != null
        isVisible = rows.isNotEmpty() || hasPermission
        revalidate()
        repaint()
    }

    private fun openFile(row: PendingFileRow) {
        if (row.isDeleted) return

        val diffService = OpenCodeDiffService.getInstance(project)
        val vFile = LocalFileSystem.getInstance().findFileByPath(row.path) ?: return
        val firstHunkLine = diffService.getHunks(row.path).firstOrNull()?.startLine ?: 0
        val descriptor = OpenFileDescriptor(project, vFile, firstHunkLine, 0)
        ApplicationManager.getApplication().invokeLater {
            FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
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
                text = "<html><font color='#$removedHex'><s><b>${row.name}</b></s></font>&nbsp;<font color='#$dimHex'>${row.displayDir}</font></html>"
            } else if (row.isAdded) {
                val addedStyle = DiffHighlightStyles.style(DiffHighlightKind.ADDED)
                val addedColor = if (isSelected) foreground else (addedStyle.fg ?: JBColor(Color(0x2E7D32), Color(0x66BB6A)))
                val addedHex = String.format("%06x", addedColor.rgb and 0xFFFFFF)
                val dimColor = if (isSelected) foreground else JBUI.CurrentTheme.Label.disabledForeground()
                val dimHex = String.format("%06x", dimColor.rgb and 0xFFFFFF)
                text = "<html><font color='#$addedHex'><b>${row.name}</b></font>&nbsp;<font color='#$dimHex'>${row.displayDir}</font></html>"
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
