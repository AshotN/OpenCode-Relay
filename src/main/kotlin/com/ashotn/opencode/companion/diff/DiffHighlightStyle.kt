package com.ashotn.opencode.companion.diff

import com.intellij.ui.JBColor
import java.awt.Color

enum class DiffHighlightKind {
    ADDED,
    REMOVED,
}

data class DiffHighlightStyle(
    val bg: Color? = null,
    val fg: Color? = null,
    val border: Color? = null,
)

object DiffHighlightStyles {
    fun style(kind: DiffHighlightKind): DiffHighlightStyle = when (kind) {
        DiffHighlightKind.ADDED -> DiffHighlightStyle(
            bg = JBColor(Color(50, 170, 50, 60), Color(63, 163, 102, 80)),
            fg = JBColor(Color(46, 125, 50), Color(129, 199, 132)),
        )

        DiffHighlightKind.REMOVED -> DiffHighlightStyle(
            bg = JBColor(Color(255, 80, 80, 60), Color(200, 70, 70, 80)),
            fg = JBColor(Color(210, 80, 80), Color(239, 154, 154)),
            border = JBColor(Color(188, 63, 63), Color(229, 115, 115)),
        )

    }
}
