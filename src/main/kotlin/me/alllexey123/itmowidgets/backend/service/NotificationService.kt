package me.alllexey123.itmowidgets.backend.service

import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.Notification
import org.springframework.stereotype.Service

@Service
class NotificationService {

    fun sendNotification(token: String, title: String, body: String) {
        val notification = Notification.builder()
            .setTitle(title)
            .setBody(body)
            .build()

        val message = Message.builder()
            .setToken(token)
            .setNotification(notification)
            .build()

        try {
            FirebaseMessaging.getInstance().send(message)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}