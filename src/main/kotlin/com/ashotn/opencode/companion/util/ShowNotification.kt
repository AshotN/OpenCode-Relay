package com.ashotn.opencode.companion.util

import com.ashotn.opencode.companion.OpenCodeConstants
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

fun Project.showNotification(title: String, content: String, type: NotificationType = NotificationType.INFORMATION) {
    NotificationGroupManager.getInstance()
        .getNotificationGroup(OpenCodeConstants.NOTIFICATION_GROUP_ID)
        .createNotification(title, content, type)
        .notify(this)
}
