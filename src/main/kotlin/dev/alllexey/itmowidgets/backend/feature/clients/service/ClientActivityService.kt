package dev.alllexey.itmowidgets.backend.feature.clients.service

import dev.alllexey.itmowidgets.backend.feature.clients.model.ClientHeader
import dev.alllexey.itmowidgets.backend.feature.clients.model.ClientInstanceEntity
import dev.alllexey.itmowidgets.backend.feature.clients.model.ClientKind
import dev.alllexey.itmowidgets.backend.feature.clients.persistence.ClientInstanceRepository
import dev.alllexey.itmowidgets.backend.platform.error.SafeDiagnostics
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The clients of signed-in users as their `X-Client` headers name them (ITMO.Widgets ADR 0034): one row per app
 * install or connection, a nightly anonymous total per build group and a 180-day retention.
 */
@Service
class ClientActivityService(private val instances: ClientInstanceRepository, private val clock: Clock) {
    /** The last header written per instance, so an unchanged client reaches the database at most once per [REFRESH]. */
    private val recent = ConcurrentHashMap<Pair<ClientKind, UUID>, Reported>()

    /** Not transactional: most calls end at the in-memory check without a database connection. */
    fun report(userId: UUID, kind: ClientKind, instanceId: UUID, header: ClientHeader) {
        val now = clock.instant()
        val key = kind to instanceId
        val last = recent[key]
        if (last != null && last.userId == userId && last.header == header && last.at > now.minus(REFRESH)) return
        instances.upsert(
            UUID.randomUUID(), userId, kind.name, instanceId, header.version, header.build, header.channel, header.source,
            header.commit, header.base, header.os, header.browser, header.custom, now,
        )
        // Only after the write, so a failed one is retried; dropping the map costs one write per active client.
        if (recent.size >= MAX_TRACKED) recent.clear()
        recent[key] = Reported(userId, header, now)
    }

    /** The user's app installs, most recently seen first. */
    @Transactional(readOnly = true)
    fun installs(userId: UUID): List<ClientInstanceEntity> = instances.findByUserIdOrderByLastSeenAtDesc(userId).filter { it.kind.isApp }

    /** `PUT /api/users/me/id-token` of an install that names itself in `X-Client`: its ITMO.ID session id. */
    fun recordItmoSession(userId: UUID, header: ClientHeader?, sid: String) {
        val install = header?.install?.takeIf { header.kind.isApp } ?: return
        instances.recordItmoSession(userId, header.kind, install, sid)
    }

    /** Yesterday's totals (Moscow) as of now, then rows unseen for [RETENTION] go. */
    @Scheduled(cron = "0 20 0 * * *", zone = "Europe/Moscow")
    fun nightly() {
        try {
            val now = clock.instant()
            instances.writeDaily(
                LocalDate.ofInstant(now, MOSCOW).minusDays(1),
                now.minus(Duration.ofDays(1)),
                now.minus(Duration.ofDays(7)),
                now.minus(Duration.ofDays(30)),
            )
            instances.deleteSeenBefore(now.minus(RETENTION))
        } catch (error: Exception) {
            // Spring's default scheduled-task logger includes unsafe exception messages and causes.
            logger.error("Client statistics job failed: {}", SafeDiagnostics.describe(error), error)
        }
    }

    private data class Reported(val userId: UUID, val header: ClientHeader, val at: Instant)

    companion object {
        val REFRESH: Duration = Duration.ofHours(1)
        val RETENTION: Duration = Duration.ofDays(180)
        private const val MAX_TRACKED = 20_000
        private val MOSCOW: ZoneId = ZoneId.of("Europe/Moscow")
        private val logger = LoggerFactory.getLogger(ClientActivityService::class.java)

        /** The one instance an app from before `install` stands for: one per user and platform. */
        fun legacyInstance(userId: UUID, kind: ClientKind): UUID = UUID.nameUUIDFromBytes("legacy:$userId:${kind.name}".toByteArray())
    }
}
