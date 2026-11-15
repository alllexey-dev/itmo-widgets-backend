package dev.alllexey.itmowidgets.backend.feature.activity.service

import dev.alllexey.itmowidgets.backend.feature.clients.service.ClientActivityService
import dev.alllexey.itmowidgets.backend.feature.connections.service.ConnectionService
import dev.alllexey.itmowidgets.backend.feature.push.service.DeviceService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * The one definition of a user's last activity: the latest of the last request of any of their clients (hour
 * precision), the last registration of any of their devices and the last use of any of their connections. A user of
 * Android 2.2 or older, which sends neither `X-Client` nor `X-App-Version`, is seen only when its device registers.
 * Each part belongs to the feature that records it (docs/contracts/clients.md#people).
 */
@Service
class UserLastSeen(
    private val clients: ClientActivityService,
    private val devices: DeviceService,
    private val connections: ConnectionService,
) {
    /** The user's last activity; null when nothing was ever recorded. */
    @Transactional(readOnly = true)
    fun of(userId: UUID): Instant? =
        listOfNotNull(clients.lastSeen(userId), devices.lastLogin(userId), connections.lastUsed(userId)).maxOrNull()

    /** The last activity of every user with one, in three queries. */
    @Transactional(readOnly = true)
    fun all(): Map<UUID, Instant> {
        val latest = HashMap(clients.lastSeenByUser())
        for (part in listOf(devices.lastLoginByUser(), connections.lastUsedByUser())) {
            part.forEach { (userId, at) -> latest.merge(userId, at, ::maxOf) }
        }
        return latest
    }
}
