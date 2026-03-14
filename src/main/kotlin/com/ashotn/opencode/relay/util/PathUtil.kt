package com.ashotn.opencode.relay.util

/**
 * Strips [projectBase] from the start of this absolute path and returns a
 * project-relative path (no leading slash).
 *
 * If this path does not start with [projectBase], the original string is returned unchanged.
 */
fun String.toProjectRelativePath(projectBase: String): String =
    if (startsWith(projectBase)) removePrefix(projectBase).trimStart('/') else this

fun toAbsolutePath(projectBase: String, path: String): String {
    val normalized = path.replace('\\', '/')
    return if (normalized.startsWith("/")) normalized else "$projectBase/$normalized"
}
