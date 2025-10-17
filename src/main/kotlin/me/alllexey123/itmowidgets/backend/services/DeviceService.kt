package me.alllexey123.itmowidgets.backend.services

import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.Notification
import me.alllexey123.itmowidgets.backend.model.Device
import me.alllexey123.itmowidgets.backend.repositories.DeviceRepository
import me.alllexey123.itmowidgets.backend.repositories.UserRepository
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class DeviceService(
    private val userRepository: UserRepository,
    private val deviceRepository: DeviceRepository,
    private val firebaseMessaging: FirebaseMessaging
) {

    companion object {
        private val logger: Logger = LoggerFactory.getLogger(DeviceService::class.java)
    }

    @Transactional
    fun registerOrUpdateDevice(userId: UUID, fcmToken: String, deviceName: String) {
        val user = userRepository.findById(userId)
            .orElseThrow { RuntimeException("User not found with ID: $userId") }

        val existingDevice = deviceRepository.findByFcmToken(fcmToken)
        if (existingDevice != null) {
            logger.info("Updating existing device for user {}", user.id)
            existingDevice.user = user
            existingDevice.deviceName = deviceName
            existingDevice.lastLogin = Instant.now()
            deviceRepository.save(existingDevice)
        } else {
            logger.info("Registering new device for user {}", user.id)
            val newDevice = Device(
                user = user,
                fcmToken = fcmToken,
                deviceName = deviceName
            )
            deviceRepository.save(newDevice)
        }
    }

    @Transactional
    fun sendNotificationToUser(userId: UUID, title: String, body: String) {
        val user = userRepository.findById(userId)
            .orElseThrow { RuntimeException("User not found with ID: $userId") }

        val devices = user.devices
        if (devices.isEmpty()) {
            logger.warn("User {} has no registered devices to send notification to.", userId)
            return
        }

        val invalidTokens = mutableListOf<String>()

        devices.forEach { device ->
            val message = Message.builder()
                .setNotification(Notification.builder().setTitle(title).setBody(body).build())
                .setToken(device.fcmToken)
                .build()

            try {
                firebaseMessaging.send(message)
            } catch (e: Exception) {
                if (e.message?.contains("not found", ignoreCase = true) ?: false) {
                    invalidTokens.add(device.fcmToken)
                } else {
                    logger.warn("Failed to send notification to token {}", device.fcmToken, e)
                }
            }
        }
        if (invalidTokens.isNotEmpty()) cleanupInvalidTokens(invalidTokens)
    }

    @Transactional
    fun cleanupInvalidTokens(tokens: List<String>) {
        logger.info("Cleaning up {} invalid FCM tokens.", tokens.size)
        tokens.forEach { token ->
            deviceRepository.findByFcmToken(token)?.let { deviceRepository.delete(it) }
        }
    }
}