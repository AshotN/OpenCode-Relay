package com.ashotn.opencode.companion.diff

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.markup.TextAttributes
import java.awt.Font
import java.awt.Graphics
import java.awt.Rectangle

/**
 * Renders removed lines ("before" content) as a block inlay above the changed
 * region. The document is never modified — this is a purely visual overlay.
 *
 * Painted to match the editor's current font and line height so the block
 * integrates naturally with the surrounding code.
 */
class RemovedLinesRenderer(
    private val lines: List<String>,
    private val editor: Editor,
) : EditorCustomElementRenderer {

    companion object {
        private const val LEFT_BORDER_WIDTH = 3
        private const val H_PADDING = 6
    }

    override fun calcWidthInPixels(inlay: Inlay<*>): Int =
        editor.contentComponent.width

    override fun calcHeightInPixels(inlay: Inlay<*>): Int =
        editor.lineHeight * lines.size

    override fun paint(
        inlay: Inlay<*>,
        g: Graphics,
        targetRegion: Rectangle,
        textAttributes: TextAttributes,
    ) {
        val lineHeight = editor.lineHeight
        val font: Font = editor.colorsScheme.getFont(com.intellij.openapi.editor.colors.EditorFontType.PLAIN)
        val fm = g.getFontMetrics(font)
        val ascent = fm.ascent
        val removedStyle = DiffHighlightStyles.style(DiffHighlightKind.REMOVED)

        lines.forEachIndexed { i, line ->
            val y = targetRegion.y + i * lineHeight

            // Background fill
            g.color = removedStyle.bg ?: textAttributes.backgroundColor
            g.fillRect(targetRegion.x, y, targetRegion.width, lineHeight)

            // Left border bar
            g.color = removedStyle.border ?: textAttributes.foregroundColor
            g.fillRect(targetRegion.x, y, LEFT_BORDER_WIDTH, lineHeight)

            // Line text
            g.font = font
            g.color = removedStyle.fg ?: textAttributes.foregroundColor
            g.drawString(line, targetRegion.x + LEFT_BORDER_WIDTH + H_PADDING, y + ascent)
        }
    }
}
