package com.ashotn.opencode.diff

internal fun interface ContentReader {
    fun readCurrentContent(absPath: String): String
}
