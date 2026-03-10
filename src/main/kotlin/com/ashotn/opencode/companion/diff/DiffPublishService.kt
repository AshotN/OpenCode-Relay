package com.ashotn.opencode.companion.diff

import com.intellij.openapi.project.Project

internal class DiffPublishService(private val project: Project) {
    fun publishChanged(filePath: String) {
        if (project.isDisposed) return
        project.messageBus
            .syncPublisher(DiffHunksChangedListener.TOPIC)
            .onHunksChanged(filePath)
    }

    fun publishSessionStateChanged() {
        if (project.isDisposed) return
        project.messageBus
            .syncPublisher(SessionStateChangedListener.TOPIC)
            .onSessionStateChanged()
    }
}
