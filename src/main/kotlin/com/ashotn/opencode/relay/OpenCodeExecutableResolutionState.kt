package com.ashotn.opencode.relay

sealed interface OpenCodeExecutableResolutionState {
    data object Resolving : OpenCodeExecutableResolutionState
    data class Resolved(val info: OpenCodeInfo) : OpenCodeExecutableResolutionState
    data object NotFound : OpenCodeExecutableResolutionState

    /**
     * An executable was found, but it is an unsupported major version
     * (OpenCode 1.x) — the plugin requires OpenCode 2.x.
     */
    data class IncompatibleVersion(val info: OpenCodeInfo) : OpenCodeExecutableResolutionState
}
