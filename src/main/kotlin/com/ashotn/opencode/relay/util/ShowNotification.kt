package com.ashotn.opencode.relay.util

import com.ashotn.opencode.relay.OpenCodeConstants
import com.intellij.notification.Notification
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

fun Project.showNotification(
    title: String,
    content: String,
    type: NotificationType = NotificationType.INFORMATION,
    configure: Notification.() -> Unit = {},
) {
    val notification = NotificationGroupManager.getInstance()
        .getNotificationGroup(OpenCodeConstants.NOTIFICATION_GROUP_ID)
        .createNotification(title, content, type)
    notification.configure()
    notification.notify(this)
}
