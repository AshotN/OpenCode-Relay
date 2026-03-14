package com.ashotn.opencode.relay.core

import com.ashotn.opencode.relay.core.session.SessionStateChangedListener
import com.intellij.openapi.project.Project

internal class PublishService(private val project: Project) {
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