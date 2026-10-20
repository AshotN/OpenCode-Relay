package com.ashotn.opencode.relay.api.health

/** Response of `GET /api/info` — the v2 readiness/health endpoint. */
data class ServerInfo(
    val version: String,
    val pid: Int,
    val urls: List<String>,
)
