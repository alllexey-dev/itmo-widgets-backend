package dev.alllexey.itmowidgets.backend.feature.push.service

import dev.alllexey.itmowidgets.backend.feature.push.model.ClientVersion
import dev.alllexey.itmowidgets.backend.feature.push.persistence.DeviceRepository
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps the last app build each device reported. Requests carry no device identity, only the user's bearer token,
 * so a request's build goes to the user's one device it can belong to ([DeviceRepository.recordClientVersion]);
 * device registration names the device exactly ([DeviceService.registerOrUpdateDevice]).
 */
@Service
class ClientVersionService(private val devices: DeviceRepository, private val clock: Clock, private val config: ClientVersionConfig) {
    /** The last build written per user, so an unchanged build reaches the database at most once per refresh. */
    private val recent = ConcurrentHashMap<UUID, Reported>()

    /** Not transactional: most calls end at the in-memory check without a database connection. */
    fun report(userId: UUID, version: ClientVersion) {
        val now = clock.instant()
        val last = recent[userId]
        if (last != null && last.version == version && last.at > now.minus(config.refresh)) return
        devices.recordClientVersion(
            userId,
            version.version,
            version.build,
            version.platform.name,
            version.distribution,
            now,
            now.minus(config.refresh),
        )
        // Only after the write, so a failed one is retried. The map only saves writes; dropping it costs one write
        // per active user.
        if (recent.size >= MAX_TRACKED_USERS) recent.clear()
        recent[userId] = Reported(version, now)
    }

    private data class Reported(val version: ClientVersion, val at: Instant)

    private companion object {
        const val MAX_TRACKED_USERS = 10_000
    }
}
