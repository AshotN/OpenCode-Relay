package com.ashotn.opencode.relay.util

import com.intellij.util.containers.CollectionFactory

/**
 * Strips [projectBase] from the start of this absolute path and returns a
 * project-relative path (no leading slash).
 *
 * If this path does not start with [projectBase], the original string is returned unchanged.
 */
fun String.toProjectRelativePath(projectBase: String): String =
    normalizeProjectPath(this).let { path ->
        val base = normalizeProjectPath(projectBase).trimEnd('/')
        val baseWithSlash = "$base/"
        when {
            samePath(path, base) -> ""
            startsWithPath(path, baseWithSlash) -> path.substring(baseWithSlash.length)
            else -> this
        }
    }

fun toAbsolutePath(projectBase: String, path: String): String {
    val normalized = normalizeProjectPath(path)
    if (isAbsolutePath(normalized)) {
        return normalizeProjectAbsolutePath(projectBase, normalized)
    }

    return "${normalizeProjectPath(projectBase).trimEnd('/')}/$normalized"
}

internal fun createPathIdentitySet(projectBase: String): MutableSet<String> =
    if (isWindowsAbsolutePath(normalizeProjectPath(projectBase))) {
        CollectionFactory.createFilePathSet(0, false)
    } else {
        CollectionFactory.createFilePathSet()
    }

internal fun createPathIdentitySet(projectBase: String, paths: Collection<String>): MutableSet<String> =
    if (isWindowsAbsolutePath(normalizeProjectPath(projectBase))) {
        CollectionFactory.createFilePathSet(paths, false)
    } else {
        CollectionFactory.createFilePathSet(paths)
    }

internal fun <V> createPathIdentityMap(projectBase: String): MutableMap<String, V> =
    if (isWindowsAbsolutePath(normalizeProjectPath(projectBase))) {
        CollectionFactory.createFilePathMap(0, false)
    } else {
        CollectionFactory.createFilePathMap()
    }

internal fun pathsEqual(projectBase: String, path1: String, path2: String): Boolean =
    path2 in createPathIdentitySet(projectBase, listOf(path1))

private val WINDOWS_ABSOLUTE_PATH = Regex("^[A-Za-z]:/.*")

private fun normalizeProjectPath(path: String): String = path.replace('\\', '/')

private fun normalizeProjectAbsolutePath(projectBase: String, path: String): String {
    val base = normalizeProjectPath(projectBase).trimEnd('/')
    if (!isWindowsAbsolutePath(path) || !isWindowsAbsolutePath(base)) return path

    val baseWithSlash = "$base/"
    return when {
        samePath(path, base) -> base
        startsWithPath(path, baseWithSlash) -> "$base/${path.substring(baseWithSlash.length)}"
        else -> path
    }
}

private fun isAbsolutePath(path: String): Boolean =
    path.startsWith("/") || WINDOWS_ABSOLUTE_PATH.matches(path)

private fun isWindowsAbsolutePath(path: String): Boolean = WINDOWS_ABSOLUTE_PATH.matches(path)

private fun samePath(path: String, base: String): Boolean =
    if (isWindowsAbsolutePath(path) && isWindowsAbsolutePath(base)) path.equals(base, ignoreCase = true)
    else path == base

private fun startsWithPath(path: String, baseWithSlash: String): Boolean =
    if (isWindowsAbsolutePath(path) && isWindowsAbsolutePath(baseWithSlash)) {
        path.startsWith(baseWithSlash, ignoreCase = true)
    } else {
        path.startsWith(baseWithSlash)
    }
