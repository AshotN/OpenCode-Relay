package com.ashotn.opencode.companion.diff

internal object DiffTextUtil {
    fun normalizeContent(content: String): String =
        content.replace("\r\n", "\n")

    fun toAbsolutePath(projectBase: String, path: String): String {
        val normalized = path.replace('\\', '/')
        return if (normalized.startsWith("/")) normalized else "$projectBase/$normalized"
    }

}
