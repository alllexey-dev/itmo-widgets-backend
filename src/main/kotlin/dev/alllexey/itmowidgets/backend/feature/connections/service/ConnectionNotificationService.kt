package dev.alllexey.itmowidgets.backend.feature.connections.service

import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientType
import dev.alllexey.itmowidgets.backend.feature.connections.web.DeviceConnectedPayload
import dev.alllexey.itmowidgets.backend.feature.push.service.DeviceService
import dev.alllexey.itmowidgets.backend.feature.push.service.PushAlert
import dev.alllexey.itmowidgets.backend.feature.push.service.PushLocKeys
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
    fun notifyConnected(userId: UUID, connectionId: UUID, client: ClientType, name: String) {
        try {
            val now = clock.instant()
            val payload = DeviceConnectedPayload(connectionId, client.wire, name, now)
            devices.sendTypedMessageToUser(
                userId,
                DeviceConnectedPayload.TYPE,
                payload,
                now.plus(LIFETIME),
                alert(connectionId, client, name),
            )
        } catch (error: Exception) {
            logger.warn("Connection notification failed: {}", error.javaClass.simpleName)
        }
    }

    private fun alert(connectionId: UUID, client: ClientType, name: String) = PushAlert(
        titleLocKey = PushLocKeys.DEVICE_CONNECTED_TITLE,
        locKey = if (client == ClientType.WEB) PushLocKeys.DEVICE_CONNECTED_WEB else PushLocKeys.DEVICE_CONNECTED_AGENT,
        locArgs = if (client == ClientType.WEB) emptyList() else listOf(name),
        collapseId = "connection-$connectionId",
        threadId = "connections",
        interruptionLevel = PushAlert.InterruptionLevel.ACTIVE,
    )

    companion object {
        val LIFETIME: Duration = Duration.ofHours(12)
        private val logger = LoggerFactory.getLogger(ConnectionNotificationService::class.java)
    }
}
