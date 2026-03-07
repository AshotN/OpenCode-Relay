package com.ashotn.opencode.diff

import com.intellij.util.messages.Topic

/**
 * Published on the project message bus whenever the set of [DiffHunk]s for a
 * file changes (new diffs arrived, or hunks were cleared).
 *
 * Subscribers ([EditorDiffRenderer]) refresh their markup for the given file.
 */
fun interface DiffHunksChangedListener {

    fun onHunksChanged(filePath: String)

    companion object {
        @JvmField
        val TOPIC: Topic<DiffHunksChangedListener> =
            Topic.create("OpenCode Diff Hunks Changed", DiffHunksChangedListener::class.java)
    }
}
