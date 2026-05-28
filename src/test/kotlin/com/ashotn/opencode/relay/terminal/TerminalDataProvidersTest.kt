package com.ashotn.opencode.relay.terminal

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.DataProvider
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.openapi.wm.ToolWindow
import com.intellij.terminal.JBTerminalPanel
import com.intellij.terminal.JBTerminalSystemSettingsProviderBase
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jediterm.terminal.model.StyleState
import com.jediterm.terminal.model.TerminalTextBuffer
import java.awt.BorderLayout
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.io.File
import java.lang.reflect.Proxy
import java.util.function.Consumer
import javax.swing.JPanel

class TerminalDataProvidersTest : BasePlatformTestCase() {

    fun `test terminal panel override hides ancestor tool window from data context`() {
        val container = JPanel(BorderLayout())
        val terminalPanel = JPanel(BorderLayout())
        val toolWindow = toolWindowStub()

        container.putClientProperty("DataProvider", DataProvider { dataId ->
            when {
                PlatformDataKeys.TOOL_WINDOW.`is`(dataId) -> toolWindow
                else -> null
            }
        })
        container.add(terminalPanel, BorderLayout.CENTER)

        installTerminalToolWindowOverride(terminalPanel)
        try {
            ApplicationManager.getApplication().invokeAndWait {
                val dataContext = DataManager.getInstance().getDataContext(terminalPanel)
                assertNull(dataContext.getData(PlatformDataKeys.TOOL_WINDOW))
            }
        } finally {
            uninstallTerminalToolWindowOverride(terminalPanel)
        }
    }

    fun `test embedded terminal data provider installs ctrl z key override without intercepting escape`() {
        val terminalPanel = createTerminalPanel()
        val existingHandlers = preKeyEventHandlers(terminalPanel)

        try {
            installEmbeddedTerminalDataProvider(project, terminalPanel)

            val handlers = preKeyEventHandlers(terminalPanel)
            val addedHandlers = handlers.drop(existingHandlers.size)
            assertEquals(1, addedHandlers.size)

            val ctrlZ = KeyEvent(
                terminalPanel,
                KeyEvent.KEY_PRESSED,
                System.currentTimeMillis(),
                InputEvent.CTRL_DOWN_MASK,
                KeyEvent.VK_Z,
                'Z',
            )
            addedHandlers.forEach { it.accept(ctrlZ) }
            assertTrue(ctrlZ.isConsumed)

            val escape = KeyEvent(
                terminalPanel,
                KeyEvent.KEY_PRESSED,
                System.currentTimeMillis(),
                0,
                KeyEvent.VK_ESCAPE,
                KeyEvent.CHAR_UNDEFINED,
            )
            addedHandlers.forEach { it.accept(escape) }
            assertFalse(escape.isConsumed)
        } finally {
            ensureTerminalPanelCanBeDisposed(terminalPanel)
            Disposer.dispose(terminalPanel)
        }
    }

    fun `test markdown terminal hyperlink filter resolves project relative file link`() {
        val file = File(project.basePath, "src/main/kotlin/SendFileAction.kt").apply {
            parentFile.mkdirs()
            writeText("class SendFileAction")
        }
        com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)

        val line = "Open [SendFileAction](src/main/kotlin/SendFileAction.kt)"
        val result = MarkdownTerminalHyperlinkFilter(project).apply(line)

        assertNotNull(result)
        val item = result!!.items.single()
        assertEquals(line.indexOf('['), item.startOffset)
        assertEquals(line.length, item.endOffset)
    }

    fun `test markdown terminal hyperlink filter resolves markdown file link with line anchor`() {
        val file = File(project.basePath, "note.md").apply {
            parentFile?.mkdirs()
            writeText("one\ntwo\n")
        }
        com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)

        var navigatedLineNumber: Int? = null
        val line = "Open [note.md:2](./note.md#L2)"
        val result = MarkdownTerminalHyperlinkFilter(project) { virtualFile, lineNumber ->
            assertEquals(file.path, virtualFile.path)
            navigatedLineNumber = lineNumber
        }.apply(line)

        assertNotNull(result)
        val item = result!!.items.single()
        assertEquals(line.indexOf('['), item.startOffset)
        assertEquals(line.length, item.endOffset)
        item.linkInfo.navigate()
        assertEquals(2, navigatedLineNumber)
    }

    fun `test markdown terminal hyperlink filter resolves prompt example markdown link`() {
        val file = File(project.basePath, "src/main/resources/opencode-relay/plugins/opencode-relay-prompt.js").apply {
            parentFile.mkdirs()
            writeText("const IDE_GUIDANCE = 'test'\n")
        }
        com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)

        val line = "[./src/main/resources/opencode-relay/plugins/opencode-relay-prompt.js](./src/main/resources/opencode-relay/plugins/opencode-relay-prompt.js)"
        val result = MarkdownTerminalHyperlinkFilter(project).apply(line)

        assertNotNull(result)
        val item = result!!.items.single()
        assertEquals(0, item.startOffset)
        assertEquals(line.length, item.endOffset)
    }

    fun `test markdown terminal hyperlink filter resolves wrapped markdown label path`() {
        val file = File(project.basePath, "src/main/resources/opencode-relay/plugins/opencode-relay-prompt.js").apply {
            parentFile.mkdirs()
            writeText("const IDE_GUIDANCE = 'test'\n")
        }
        com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)

        val line = "[./src/main/resources/opencode-relay/plugins/opencode-relay-prompt.js"
        val result = MarkdownTerminalHyperlinkFilter(project).apply(line)

        assertNotNull(result)
        val item = result!!.items.single()
        assertEquals(1, item.startOffset)
        assertEquals(line.length, item.endOffset)
    }

    fun `test markdown terminal hyperlink filter resolves advertised bare file formats`() {
        data class Case(
            val name: String,
            val target: String,
            val file: File,
            val lineNumber: Int? = null,
        )

        val absoluteFile = File(project.basePath, "absolute/AbsoluteFile.kt")
        val absoluteLineSuffixFile = File(project.basePath, "absolute/AbsoluteLineSuffixFile.kt")
        val cases = listOf(
            Case(
                "dot slash relative path",
                "./src/main/resources/opencode-relay/plugins/opencode-relay-prompt.js",
                File(project.basePath, "src/main/resources/opencode-relay/plugins/opencode-relay-prompt.js"),
            ),
            Case(
                "project relative path",
                "src/main/kotlin/com/ashotn/opencode/relay/actions/SendFileAction.kt",
                File(project.basePath, "src/main/kotlin/com/ashotn/opencode/relay/actions/SendFileAction.kt"),
            ),
            Case(
                "parent relative path",
                "../src/ParentRelative.kt",
                File(project.basePath, "../src/ParentRelative.kt"),
            ),
            Case(
                "absolute path",
                absoluteFile.path,
                absoluteFile,
            ),
            Case(
                "line anchor without dot slash",
                "note.md#L2",
                File(project.basePath, "note.md"),
                lineNumber = 2,
            ),
            Case(
                "dot slash line anchor",
                "./note.md#L2",
                File(project.basePath, "note.md"),
                lineNumber = 2,
            ),
            Case(
                "line anchor range",
                "./note.md#L2-L6",
                File(project.basePath, "note.md"),
                lineNumber = 2,
            ),
            Case(
                "dot slash line suffix",
                "./src/FileWithLineSuffix.kt:42",
                File(project.basePath, "src/FileWithLineSuffix.kt"),
                lineNumber = 42,
            ),
            Case(
                "project relative line suffix range",
                "src/main/FileWithLineSuffixRange.kt:42-48",
                File(project.basePath, "src/main/FileWithLineSuffixRange.kt"),
                lineNumber = 42,
            ),
            Case(
                "absolute line suffix",
                "${absoluteLineSuffixFile.path}:42",
                absoluteLineSuffixFile,
                lineNumber = 42,
            ),
        )

        cases.forEach { case ->
            case.file.apply {
                parentFile?.mkdirs()
                writeText((1..60).joinToString("\n") { "line $it" } + "\n")
            }
            VfsRootAccess.allowRootAccess(testRootDisposable, case.file.parentFile.canonicalPath)
            com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByIoFile(case.file)

            var navigatedPath: String? = null
            var navigatedLineNumber: Int? = null
            val line = "See ${case.target}"
            val result = MarkdownTerminalHyperlinkFilter(project) { virtualFile, lineNumber ->
                navigatedPath = virtualFile.path
                navigatedLineNumber = lineNumber
            }.apply(line)

            assertNotNull(case.name, result)
            val item = result!!.items.single()
            val targetStart = line.indexOf(case.target)
            assertEquals(case.name, targetStart, item.startOffset)
            assertEquals(case.name, targetStart + case.target.length, item.endOffset)

            item.linkInfo.navigate()
            assertEquals(case.name, case.file.canonicalPath, File(navigatedPath!!).canonicalPath)
            assertEquals(case.name, case.lineNumber, navigatedLineNumber)
        }
    }

    fun `test markdown terminal hyperlink filter excludes trailing punctuation from bare file links`() {
        data class Case(
            val name: String,
            val text: String,
            val target: String,
            val file: File,
            val lineNumber: Int? = null,
        )

        val cases = listOf(
            Case(
                "trailing period",
                "src/main/FileTrailingPeriod.kt.",
                "src/main/FileTrailingPeriod.kt",
                File(project.basePath, "src/main/FileTrailingPeriod.kt"),
            ),
            Case(
                "trailing comma",
                "src/main/FileTrailingComma.kt,",
                "src/main/FileTrailingComma.kt",
                File(project.basePath, "src/main/FileTrailingComma.kt"),
            ),
            Case(
                "trailing colon",
                "src/main/FileTrailingColon.kt:",
                "src/main/FileTrailingColon.kt",
                File(project.basePath, "src/main/FileTrailingColon.kt"),
            ),
            Case(
                "trailing semicolon",
                "src/main/FileTrailingSemicolon.kt;",
                "src/main/FileTrailingSemicolon.kt",
                File(project.basePath, "src/main/FileTrailingSemicolon.kt"),
            ),
            Case(
                "line suffix trailing period",
                "src/main/FileLineSuffixTrailingPeriod.kt:42.",
                "src/main/FileLineSuffixTrailingPeriod.kt:42",
                File(project.basePath, "src/main/FileLineSuffixTrailingPeriod.kt"),
                lineNumber = 42,
            ),
            Case(
                "line anchor trailing period",
                "note.md#L2.",
                "note.md#L2",
                File(project.basePath, "note.md"),
                lineNumber = 2,
            ),
        )

        cases.forEach { case ->
            case.file.apply {
                parentFile?.mkdirs()
                writeText((1..60).joinToString("\n") { "line $it" } + "\n")
            }
            VfsRootAccess.allowRootAccess(testRootDisposable, case.file.parentFile.canonicalPath)
            com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByIoFile(case.file)

            var navigatedPath: String? = null
            var navigatedLineNumber: Int? = null
            val line = "See ${case.text}"
            val result = MarkdownTerminalHyperlinkFilter(project) { virtualFile, lineNumber ->
                navigatedPath = virtualFile.path
                navigatedLineNumber = lineNumber
            }.apply(line)

            assertNotNull(case.name, result)
            val item = result!!.items.single()
            val targetStart = line.indexOf(case.target)
            assertEquals(case.name, targetStart, item.startOffset)
            assertEquals(case.name, targetStart + case.target.length, item.endOffset)

            item.linkInfo.navigate()
            assertEquals(case.name, case.file.canonicalPath, File(navigatedPath!!).canonicalPath)
            assertEquals(case.name, case.lineNumber, navigatedLineNumber)
        }
    }

    fun `test markdown terminal hyperlink filter resolves wrapped markdown label path with line suffix`() {
        val file = File(project.basePath, "src/main/resources/opencode-relay/plugins/opencode-relay-prompt.js").apply {
            parentFile.mkdirs()
            writeText("one\ntwo\n")
        }
        com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)

        var navigatedLineNumber: Int? = null
        val line = "[./src/main/resources/opencode-relay/plugins/opencode-relay-prompt.js:2"
        val result = MarkdownTerminalHyperlinkFilter(project) { virtualFile, lineNumber ->
            assertEquals(file.path, virtualFile.path)
            navigatedLineNumber = lineNumber
        }.apply(line)

        assertNotNull(result)
        val item = result!!.items.single()
        assertEquals(1, item.startOffset)
        assertEquals(line.length, item.endOffset)
        item.linkInfo.navigate()
        assertEquals(2, navigatedLineNumber)
    }

    fun `test markdown terminal hyperlink filter resolves markdown line range to first line`() {
        val file = File(project.basePath, "note.md").apply {
            parentFile?.mkdirs()
            writeText((1..6).joinToString("\n") { "line $it" } + "\n")
        }
        com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)

        var navigatedLineNumber: Int? = null
        val line = "[`note.md` lines 2-6](./note.md#L2-L6)"
        val result = MarkdownTerminalHyperlinkFilter(project) { virtualFile, lineNumber ->
            assertEquals(file.path, virtualFile.path)
            navigatedLineNumber = lineNumber
        }.apply(line)

        assertNotNull(result)
        val item = result!!.items.single()
        assertEquals(line.indexOf('['), item.startOffset)
        assertEquals(line.length, item.endOffset)
        item.linkInfo.navigate()
        assertEquals(2, navigatedLineNumber)
    }

    fun `test markdown terminal hyperlink filter resolves bare line anchor inside html code tag`() {
        val file = File(project.basePath, "note.md").apply {
            parentFile?.mkdirs()
            writeText((1..12).joinToString("\n") { "line $it" } + "\n")
        }
        com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)

        var navigatedLineNumber: Int? = null
        val line = "<td><code>./note.md#L12</code></td>"
        val result = MarkdownTerminalHyperlinkFilter(project) { virtualFile, lineNumber ->
            assertEquals(file.path, virtualFile.path)
            navigatedLineNumber = lineNumber
        }.apply(line)

        assertNotNull(result)
        val item = result!!.items.single()
        val targetStart = line.indexOf("./note.md#L12")
        assertEquals(targetStart, item.startOffset)
        assertEquals(targetStart + "./note.md#L12".length, item.endOffset)
        item.linkInfo.navigate()
        assertEquals(12, navigatedLineNumber)
    }

    fun `test markdown terminal hyperlink filter ignores missing project relative file link`() {
        val result = MarkdownTerminalHyperlinkFilter(project)
            .apply("Open [Missing](src/main/kotlin/Missing.kt)")

        assertNull(result)
    }

    fun `test markdown terminal hyperlink filter ignores uri-like line anchor without crashing`() {
        val result = MarkdownTerminalHyperlinkFilter(project)
            .apply("mailto:test@example.com#L1")

        assertNull(result)
    }

    private fun toolWindowStub(): ToolWindow =
        Proxy.newProxyInstance(
            ToolWindow::class.java.classLoader,
            arrayOf(ToolWindow::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "toString" -> "ToolWindowStub"
                else -> if (method.returnType == Boolean::class.javaPrimitiveType) false else null
            }
        } as ToolWindow

    private fun createTerminalPanel(): JBTerminalPanel {
        lateinit var terminalPanel: JBTerminalPanel
        ApplicationManager.getApplication().invokeAndWait {
            val styleState = StyleState()
            terminalPanel = JBTerminalPanel(
                JBTerminalSystemSettingsProviderBase(),
                TerminalTextBuffer(80, 24, styleState),
                styleState,
            )
        }
        return terminalPanel
    }

    @Suppress("UNCHECKED_CAST")
    private fun preKeyEventHandlers(terminalPanel: JBTerminalPanel): List<Consumer<KeyEvent>> {
        val field = JBTerminalPanel::class.java.getDeclaredField("myPreKeyEventConsumers")
        field.isAccessible = true
        return (field.get(terminalPanel) as List<Consumer<KeyEvent>>).toList()
    }

    private fun ensureTerminalPanelCanBeDisposed(terminalPanel: JBTerminalPanel) {
        val field = terminalPanel.javaClass.superclass.getDeclaredField("myRepaintTimer")
        field.isAccessible = true
        if (field.get(terminalPanel) == null) {
            field.set(terminalPanel, javax.swing.Timer(0) { })
        }
    }
}
