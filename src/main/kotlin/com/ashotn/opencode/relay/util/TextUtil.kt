package com.ashotn.opencode.relay.util

internal object TextUtil {
    fun normalizeContent(content: String): String =
        content.replace("\r\n", "\n")
}