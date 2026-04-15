package com.ashotn.opencode.relay.integration

object OpenCodeTestVersions {

    private const val MULTI_VERSION_ENV = "OPENCODE_TEST_VERSIONS"

    fun all(): List<String> {
        val multi = System.getenv(MULTI_VERSION_ENV)
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        require(multi.isNotEmpty()) {
            "Missing required $MULTI_VERSION_ENV environment variable for OpenCode test versions."
        }
        return multi.distinct()
    }
}
