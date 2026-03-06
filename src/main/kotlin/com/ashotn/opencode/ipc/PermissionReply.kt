package com.ashotn.opencode.ipc

enum class PermissionReply(val wireValue: String) {
    ONCE("once"),
    ALWAYS("always"),
    REJECT("reject");

    companion object {
        fun fromWire(value: String): PermissionReply? =
            entries.firstOrNull { it.wireValue == value }
    }
}
