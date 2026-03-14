package com.ashotn.opencode.relay

sealed interface OpenCodeExecutableResolutionState {
    data object Resolving : OpenCodeExecutableResolutionState
    data class Resolved(val info: OpenCodeInfo) : OpenCodeExecutableResolutionState
    data object NotFound : OpenCodeExecutableResolutionState
}
