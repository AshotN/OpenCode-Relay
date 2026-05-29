package com.ashotn.opencode.relay.actions

import com.ashotn.opencode.relay.util.showNotification
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

private const val SEND_REFERENCE_SUCCESS_DO_NOT_ASK_ID = "opencode.relay.sendReferenceSuccess"
private const val SEND_REFERENCE_SUCCESS_DO_NOT_ASK_DISPLAY_NAME = "OpenCode: File or folder references sent"

internal fun Project.showSendReferenceSuccessNotification(title: String, content: String) {
    showNotification(title, content, NotificationType.INFORMATION) {
        configureDoNotAskOption(
            SEND_REFERENCE_SUCCESS_DO_NOT_ASK_ID,
            SEND_REFERENCE_SUCCESS_DO_NOT_ASK_DISPLAY_NAME,
        )
    }
}
