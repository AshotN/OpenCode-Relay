package com.ashotn.opencode.companion.api.session

data class Session(
    val id: String,
    val projectID: String?,
    val directory: String?,
    val parentID: String?,
    val title: String?,
    val version: String?,
    val time: SessionTime,
    val summary: SessionSummary?,
    val share: SessionShare?,
)

data class SessionTime(
    val created: Long,
    val updated: Long,
    val compacting: Long?,
)

data class SessionSummary(
    val additions: Int,
    val deletions: Int,
    val files: Int,
    val diffs: List<FileDiff>?,
)

data class SessionShare(
    val url: String?,
)

data class FileDiff(
    val file: String,
    val before: String,
    val after: String,
    val additions: Int,
    val deletions: Int,
)
