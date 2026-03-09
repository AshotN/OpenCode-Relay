package com.ashotn.opencode.companion.ipc

enum class SessionDiffStatus(val wireValue: String) {
    ADDED("added"),
    MODIFIED("modified"),
    DELETED("deleted"),
    UNKNOWN("unknown");

    companion object {
        fun fromWire(value: String): SessionDiffStatus =
            entries.firstOrNull { it.wireValue == value } ?: UNKNOWN
    }
}
