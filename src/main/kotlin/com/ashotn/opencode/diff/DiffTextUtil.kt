package com.ashotn.opencode.diff

internal object DiffTextUtil {
    fun normalizeContent(content: String): String =
        content.replace("\r\n", "\n")

    fun toAbsolutePath(projectBase: String, path: String): String {
        val normalized = path.replace('\\', '/')
        return if (normalized.startsWith("/")) normalized else "$projectBase/$normalized"
    }

    fun hunkFingerprint(hunk: DiffHunk): String {
        val removed = hunk.removedLines.joinToString("\n")
        val added = hunk.addedLines.joinToString("\n")
        return "${hunk.filePath}\u0000${hunk.startLine}\u0000$removed\u0000$added"
    }
}
