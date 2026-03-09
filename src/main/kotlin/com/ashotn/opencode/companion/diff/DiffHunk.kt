package com.ashotn.opencode.companion.diff

/**
 * A single contiguous block of changed lines within a file.
 *
 * [removedLines] are the "before" lines rendered as a block inlay above the
 * changed region. They do not exist in the document.
 *
 * [addedLines] are the "after" lines already present in the current document.
 *
 * [startLine] is the 0-based line number in the current (after) document
 * where the added lines begin.
 */
data class DiffHunk(
    val filePath: String,
    val startLine: Int,
    val removedLines: List<String>,
    val addedLines: List<String>,
    val sessionId: String,
)
