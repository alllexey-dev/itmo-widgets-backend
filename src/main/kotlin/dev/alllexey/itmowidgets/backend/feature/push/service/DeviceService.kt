package dev.alllexey.itmowidgets.backend.feature.push.service

import com.google.firebase.messaging.FirebaseMessagingException
import com.google.firebase.messaging.MessagingErrorCode
import dev.alllexey.itmowidgets.backend.feature.app.model.AppPlatform
import dev.alllexey.itmowidgets.backend.feature.push.model.ClientVersion
import dev.alllexey.itmowidgets.backend.feature.push.model.Device
import dev.alllexey.itmowidgets.backend.feature.push.persistence.DeviceRepository
import dev.alllexey.itmowidgets.backend.feature.push.web.FcmPayload
import dev.alllexey.itmowidgets.backend.feature.push.web.FcmTypedWrapper
import dev.alllexey.itmowidgets.backend.feature.push.web.RegisterDeviceRequest
import dev.alllexey.itmowidgets.backend.feature.users.service.UserService
import dev.alllexey.itmowidgets.backend.platform.error.SafeDiagnostics
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DeviceService(
    private val deviceRepository: DeviceRepository,
    private val fcmService: FcmService,
    private val userService: UserService,
    private val deviceDeliveryStore: DeviceDeliveryStore,
    private val clock: Clock,
) {

    companion object {
        private val logger: Logger = LoggerFactory.getLogger(DeviceService::class.java)
    }

    /**
     * Applies every field of [request] to the device of its token, also when the token is known: an iOS build that
     * registered against a Backend without these fields left an `ANDROID` row, and its next registration heals it.
     * [clientVersion] is the request's `X-App-Version`, stored on this device; null keeps the device's last one.
     */
    @Transactional
    fun registerOrUpdateDevice(userId: UUID, request: RegisterDeviceRequest, clientVersion: ClientVersion? = null) {
        val user = userService.findUserById(userId)
        val fcmToken = request.fcmToken.trim()
        val now = clock.instant()

        val existingDevice = deviceRepository.findByFcmToken(fcmToken)
        val device = if (existingDevice != null) {
            logger.info("Updating existing device for user {}", user.isu)
            existingDevice.user = user
            existingDevice.deviceName = request.deviceName
            existingDevice.lastLogin = now
            existingDevice
        } else {
            logger.info("Registering new device for user {}", user.id)
            Device(user = user, fcmToken = fcmToken, deviceName = request.deviceName, lastLogin = now)
        }
        device.platform = request.platform ?: AppPlatform.ANDROID
        device.alertsAllowed = request.alertsAllowed ?: true
        // Absent keeps the stored name: a build reported through the header must never lose its version.
        request.appVersion
            ?.takeIf { it.isNotBlank() && it.length <= ClientVersion.MAX_VERSION_LENGTH }
            ?.let { device.appVersion = it }
        if (clientVersion != null) device.reportClientVersion(clientVersion, now)
        deviceRepository.save(device)
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

    fun sendDataMessageToUser(userId: UUID, data: FcmPayload, expiresAt: Instant, alert: PushAlert) {
        sendDataMessageToUser(userId, FcmTypedWrapper(data.getType(), data), expiresAt, alert)
    }

    /**
     * [expiresAt] is when the message stops being actionable; devices offline until then never get it. `IOS` devices
     * get [alert] with the same `data`; without an alert they get the Android data message.
     */
    fun <T> sendDataMessageToUser(userId: UUID, data: FcmTypedWrapper<T?>?, expiresAt: Instant, alert: PushAlert? = null) {
        val targets = deviceDeliveryStore.targetsFor(userId)
        if (targets.isEmpty()) {
            logger.warn("User {} has no registered devices to send notification to", userId)
            return
        }

        val invalidTargets = mutableListOf<DeviceDeliveryTarget>()
        targets.forEach { target ->
            try {
                if (alert != null && target.platform == AppPlatform.IOS) {
                    fcmService.sendAlertMessage(target.fcmToken, data, target.recipientIsu, expiresAt, alert)
                } else {
                    fcmService.sendDataMessage(target.fcmToken, data, target.recipientIsu, expiresAt)
                }
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
                logger.warn(
                    "Failed to remove invalid registration for device {}: {}",
                    target.deviceId,
                    SafeDiagnostics.describe(error),
                    error,
                )
            }
        }
    }
}
