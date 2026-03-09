package com.ashotn.opencode.companion.diff

internal fun interface ContentReader {
    fun readCurrentContent(absPath: String): String
}
