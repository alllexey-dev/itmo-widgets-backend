package dev.alllexey.itmowidgets.backend.services

import com.google.firebase.messaging.FirebaseMessagingException
import com.google.firebase.messaging.MessagingErrorCode
import dev.alllexey.itmowidgets.backend.exceptions.SafeDiagnostics
import dev.alllexey.itmowidgets.backend.model.Device
import dev.alllexey.itmowidgets.backend.dto.DeviceDeliveryTarget
import dev.alllexey.itmowidgets.backend.repositories.DeviceRepository
import dev.alllexey.itmowidgets.core.model.fcm.FcmPayload
import dev.alllexey.itmowidgets.core.model.fcm.FcmTypedWrapper
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DeviceService(
    private val deviceRepository: DeviceRepository,
    private val fcmService: FcmService,
    private val userService: UserService,
    private val deviceDeliveryStore: DeviceDeliveryStore,
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

    fun sendDataMessageToUser(userId: UUID, data: FcmPayload) {
        sendDataMessageToUser(userId, FcmTypedWrapper(data.getType(), data))
    }

    fun <T> sendDataMessageToUser(userId: UUID, data: FcmTypedWrapper<T?>?) {
        val targets = deviceDeliveryStore.targetsFor(userId)
        if (targets.isEmpty()) {
            logger.warn("User {} has no registered devices to send notification to", userId)
            return
        }

        val invalidTargets = mutableListOf<DeviceDeliveryTarget>()
        targets.forEach { target ->
            try {
                fcmService.sendDataMessage(target.fcmToken, data)
            } catch (error: Exception) {
                if (error is FirebaseMessagingException && error.messagingErrorCode == MessagingErrorCode.UNREGISTERED) {
                    invalidTargets.add(target)
                } else {
                    logger.warn("Failed to send notification to device {}: {}", target.deviceId, SafeDiagnostics.describe(error), error)
                }
            }
        }

        invalidTargets.forEach { target ->
            try {
                deviceDeliveryStore.removeIfTokenMatches(target)
            } catch (error: Exception) {
                logger.warn("Failed to remove invalid registration for device {}: {}", target.deviceId, SafeDiagnostics.describe(error), error)
            }
        }
    }
}
