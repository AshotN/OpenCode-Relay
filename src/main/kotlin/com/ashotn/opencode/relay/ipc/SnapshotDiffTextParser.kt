package com.ashotn.opencode.relay.ipc

import com.ashotn.opencode.relay.util.getStringOrNull
import com.google.gson.JsonObject

internal data class SnapshotDiffText(
    val before: String,
    val after: String,
)

internal object SnapshotDiffTextParser {
    fun parse(obj: JsonObject): SnapshotDiffText {
        // OpenCode before 1.4.0 sent full snapshot text in `before`/`after`.
        val before = obj.getStringOrNull("before")
        val after = obj.getStringOrNull("after")
        if (before != null || after != null) {
            return SnapshotDiffText(before = before ?: "", after = after ?: "")
        }

        // OpenCode 1.4.0+ sends only a unified diff in `patch`, so rebuild both sides.
        val patch = obj.getStringOrNull("patch") ?: return SnapshotDiffText(before = "", after = "")
        return parseUnifiedPatch(patch)
    }

    private fun parseUnifiedPatch(patch: String): SnapshotDiffText {
        // OpenCode emits full-file unified diffs here, not minimal-context patches.
        if (patch.isEmpty()) return SnapshotDiffText(before = "", after = "")

        val before = StringBuilder()
        val after = StringBuilder()
        var inHunk = false
        var lastPrefix: Char? = null

        for (rawLine in patch.split('\n')) {
            val line = rawLine.removeSuffix("\r")
            when {
                line.startsWith("@@ ") -> {
                    inHunk = true
                    lastPrefix = null
                }

                !inHunk -> Unit

                line == "\\ No newline at end of file" -> {
                    when (lastPrefix) {
                        ' ' -> {
                            trimTrailingNewline(before)
                            trimTrailingNewline(after)
                        }

                        '-' -> trimTrailingNewline(before)
                        '+' -> trimTrailingNewline(after)
                    }
                }

                line.isEmpty() -> Unit

                else -> {
                    val prefix = line.first()
                    val content = line.drop(1)
                    when (prefix) {
                        ' ' -> appendPatchLine(before, after, content)
                        '-' -> before.append(content).append('\n')
                        '+' -> after.append(content).append('\n')
                    }
                    if (prefix == ' ' || prefix == '-' || prefix == '+') {
                        lastPrefix = prefix
                    }
                }
            }
        }

        return SnapshotDiffText(before = before.toString(), after = after.toString())
    }

    private fun appendPatchLine(before: StringBuilder, after: StringBuilder, content: String) {
        before.append(content).append('\n')
        after.append(content).append('\n')
    }

    private fun trimTrailingNewline(text: StringBuilder) {
        if (text.isNotEmpty() && text.last() == '\n') {
            text.setLength(text.length - 1)
        }
    }
}
