package com.ashotn.opencode.diff

import com.ashotn.opencode.ipc.FileDiff

internal fun interface HunkComputer {
    fun compute(fileDiff: FileDiff, sessionId: String): List<DiffHunk>
}
