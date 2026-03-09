package com.ashotn.opencode.companion.diff

import com.intellij.openapi.project.Project

internal class DiffPublishService(private val project: Project) {
    fun publishChanged(filePath: String) {
        project.messageBus
            .syncPublisher(DiffHunksChangedListener.TOPIC)
            .onHunksChanged(filePath)
    }

    fun publishSessionStateChanged() {
        project.messageBus
            .syncPublisher(SessionStateChangedListener.TOPIC)
            .onSessionStateChanged()
    }
}
