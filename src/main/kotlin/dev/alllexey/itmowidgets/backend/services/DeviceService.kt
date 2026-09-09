package dev.alllexey.itmowidgets.backend.services

import com.google.firebase.messaging.FirebaseMessagingException
import com.google.firebase.messaging.MessagingErrorCode
import dev.alllexey.itmowidgets.backend.exceptions.SafeDiagnostics
import dev.alllexey.itmowidgets.backend.model.Device
import dev.alllexey.itmowidgets.backend.model.User
import dev.alllexey.itmowidgets.backend.repositories.DeviceRepository
import dev.alllexey.itmowidgets.core.model.fcm.FcmPayload
import dev.alllexey.itmowidgets.core.model.fcm.FcmTypedWrapper
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class DeviceService(
    private val deviceRepository: DeviceRepository,
    private val fcmService: FcmService,
    private val userService: UserService
) {

    companion object {
        private val logger: Logger = LoggerFactory.getLogger(DeviceService::class.java)
    }

    @Transactional
    fun registerOrUpdateDevice(userId: UUID, fcmToken: String, deviceName: String) {
        val user = userService.findUserById(userId)

        val existingDevice = deviceRepository.findByFcmToken(fcmToken)
        if (existingDevice != null) {
            logger.info("Updating existing device for user {}", user.isu)
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

    /**
     * Removes only a device owned by the authenticated user.
     *
     * Missing and foreign tokens are treated identically so the endpoint cannot
     * be used to discover another user's FCM registrations.
     */
    @Transactional
    fun unregisterDevice(userId: UUID, fcmToken: String) {
        val normalizedToken = fcmToken.trim()
        if (normalizedToken.isEmpty()) return

        val device = deviceRepository.findByFcmToken(normalizedToken) ?: return
        if (device.user.id != userId) return

        deviceRepository.delete(device)
    }

    @Transactional
    fun sendDataMessageToUser(user: User, data: FcmPayload) {
        sendDataMessageToUser(user, FcmTypedWrapper(data.getType(), data))
    }

    @Transactional
    fun <T> sendDataMessageToUser(user: User, data: FcmTypedWrapper<T?>?) {
        val devices = deviceRepository.findByUserId(user.id)
        if (devices.isEmpty()) {
            logger.warn("User ${user.id} has no registered devices to send notification to.")
            return
        }

        val invalidTokens = mutableListOf<String>()
        devices.forEach {
            val token = it.fcmToken
            try {
                fcmService.sendDataMessage(token, data)
            } catch (e: Exception) {
                if (e is FirebaseMessagingException && e.messagingErrorCode == MessagingErrorCode.UNREGISTERED) {
                    invalidTokens.add(token)
                } else {
                    logger.warn("Failed to send notification to device {}: {}", it.id, SafeDiagnostics.describe(e))
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
