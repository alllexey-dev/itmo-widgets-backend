package dev.alllexey.itmowidgets.backend.feature.weblogin.service

import dev.alllexey.itmowidgets.backend.feature.push.model.PushCategory
import dev.alllexey.itmowidgets.backend.feature.push.service.DeviceService
import dev.alllexey.itmowidgets.backend.feature.push.service.PushAlert
import dev.alllexey.itmowidgets.backend.feature.push.service.PushLocKeys
import dev.alllexey.itmowidgets.backend.feature.weblogin.model.PairingKind
import dev.alllexey.itmowidgets.backend.feature.weblogin.web.DeviceConnectedPayload
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.util.UUID

/** «Подключено новое устройство» on every device of the owner, after a claim commits; a check against forwarded links. */
@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ConnectionNotificationService(private val devices: DeviceService, private val clock: Clock) {
    fun notifyConnected(userId: UUID, connectionId: UUID, kind: PairingKind, name: String?, userAgent: String?) {
        try {
            val now = clock.instant()
            val payload = DeviceConnectedPayload(connectionId, kind.name, name, userAgent, now)
            devices.sendTypedMessageToUser(
                userId,
                DeviceConnectedPayload.TYPE,
                payload,
                now.plus(LIFETIME),
                alert(connectionId, name),
                PushCategory.CONNECTIONS,
            )
        } catch (error: Exception) {
            logger.warn("Connection notification failed: {}", error.javaClass.simpleName)
        }
    }

    private fun alert(connectionId: UUID, name: String?) = PushAlert(
        titleLocKey = PushLocKeys.DEVICE_CONNECTED_TITLE,
        locKey = if (name == null) PushLocKeys.DEVICE_CONNECTED_WEB else PushLocKeys.DEVICE_CONNECTED_AGENT,
        locArgs = listOfNotNull(name),
        collapseId = "connection-$connectionId",
        threadId = "connections",
        interruptionLevel = PushAlert.InterruptionLevel.ACTIVE,
    )

    companion object {
        val LIFETIME: Duration = Duration.ofHours(12)
        private val logger = LoggerFactory.getLogger(ConnectionNotificationService::class.java)
    }
}
