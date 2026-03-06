package com.ashotn.opencode.diff

enum class HunkState {
    /** Diff is visible in the editor; user has not yet acted on it. */
    PENDING,

    /** User accepted the change — highlights cleared, file kept as-is. */
    ACCEPTED,

    /** User rejected the change — revert was requested from OpenCode. */
    REJECTED,
}
