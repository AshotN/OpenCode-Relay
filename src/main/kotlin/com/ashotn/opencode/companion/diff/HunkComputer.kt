package com.ashotn.opencode.companion.diff

import com.ashotn.opencode.companion.api.session.FileDiff

internal fun interface HunkComputer {
    fun compute(fileDiff: FileDiff, sessionId: String): List<DiffHunk>
}
