package com.ashotn.opencode.util

/**
 * Strips [projectBase] from the start of this absolute path and returns a
 * project-relative path (no leading slash).
 *
 * If this path does not start with [projectBase], the original string is returned unchanged.
 */
fun String.toProjectRelativePath(projectBase: String): String =
    if (startsWith(projectBase)) removePrefix(projectBase).trimStart('/') else this
