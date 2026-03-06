package com.ashotn.opencode.diff

/**
 * A single contiguous block of changed lines within a file.
 *
 * [removedLines] are the "before" lines — rendered as a block inlay above the
 * changed region. They never exist in the actual document.
 *
 * [addedLines] are the "after" lines — already present in the document on disk.
 * They are highlighted with a green background via [DocumentMarkupModel].
 *
 * [startLine] is the 0-based line number in the **current** (after) document
 * where the added lines begin.
 */
data class DiffHunk(
    val filePath: String,
    val startLine: Int,
    val removedLines: List<String>,
    val addedLines: List<String>,
    val sessionId: String,
    val messageId: String,
    val state: HunkState = HunkState.PENDING,
)
