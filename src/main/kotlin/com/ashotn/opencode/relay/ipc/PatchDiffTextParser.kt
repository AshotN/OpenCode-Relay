package com.ashotn.opencode.relay.ipc

import com.ashotn.opencode.relay.util.getStringOrNull
import com.google.gson.JsonObject

internal data class DiffText(
    val before: String,
    val after: String,
)

internal object PatchDiffTextParser {
    fun parse(obj: JsonObject): DiffText {
        val patch = obj.getStringOrNull("patch") ?: return DiffText(before = "", after = "")
        return parseUnifiedPatch(patch)
    }

    private fun parseUnifiedPatch(patch: String): DiffText {
        // The session diff payload currently uses full-file unified diffs.
        if (patch.isEmpty()) return DiffText(before = "", after = "")

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

        return DiffText(before = before.toString(), after = after.toString())
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
