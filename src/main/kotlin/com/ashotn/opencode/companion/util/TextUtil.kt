package com.ashotn.opencode.companion.util

internal object TextUtil {
    fun normalizeContent(content: String): String =
        content.replace("\r\n", "\n")
}