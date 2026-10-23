package dev.alllexey.itmowidgets.backend.feature.clients.persistence

import dev.alllexey.itmowidgets.backend.feature.clients.model.ClientInstanceEntity
import dev.alllexey.itmowidgets.backend.feature.clients.model.ClientKind
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

interface ClientInstanceRepository : JpaRepository<ClientInstanceEntity, UUID> {
    /**
     * Creates or updates the instance with what its header said at [seenAt]. An instance id another user already holds
     * stays theirs: the write is skipped, so a client cannot move someone else's row. Returns the rows written, 0 or 1.
     */
    @Transactional
    @Modifying
    @Query(
        nativeQuery = true,
        value = """
        INSERT INTO client_instances (id, user_id, kind, instance_id, version, build, channel, source, commit_sha, base,
            os, browser, custom, first_seen_at, last_seen_at)
        VALUES (:id, :userId, :kind, :instanceId, :version, :build, :channel, :source, :commit, :base, :os, :browser,
            :custom, :seenAt, :seenAt)
        ON CONFLICT (kind, instance_id) DO UPDATE SET version = EXCLUDED.version, build = EXCLUDED.build,
            channel = EXCLUDED.channel, source = EXCLUDED.source, commit_sha = EXCLUDED.commit_sha, base = EXCLUDED.base,
            os = EXCLUDED.os, browser = EXCLUDED.browser, custom = EXCLUDED.custom, last_seen_at = EXCLUDED.last_seen_at
        WHERE client_instances.user_id = EXCLUDED.user_id
        """,
    )
    fun upsert(
        id: UUID,
        userId: UUID,
        kind: String,
        instanceId: UUID,
        version: String,
        build: String?,
        channel: String?,
        source: String?,
        commit: String?,
        base: String?,
        os: String?,
        browser: String?,
        custom: Boolean,
        seenAt: Instant,
    ): Int

    fun findByUserIdOrderByLastSeenAtDesc(userId: UUID): List<ClientInstanceEntity>

    /** The ITMO.ID session of the user's own app install, from its ID-token sync; a foreign instance is left alone. */
    @Transactional
    @Modifying
    @Query("UPDATE ClientInstanceEntity c SET c.itmoSid = :sid WHERE c.userId = :userId AND c.kind = :kind AND c.instanceId = :instanceId")
    fun recordItmoSession(userId: UUID, kind: ClientKind, instanceId: UUID, sid: String): Int

    @Query("SELECT MAX(c.lastSeenAt) FROM ClientInstanceEntity c WHERE c.userId = :userId")
    fun findLastSeen(userId: UUID): Instant?

    /** Instances per kind seen since each of the three instants, kinds without any in the last window left out. */
    @Query(
        nativeQuery = true,
        value = """
        SELECT kind,
            COUNT(*) FILTER (WHERE last_seen_at >= :day) AS active1d,
            COUNT(*) FILTER (WHERE last_seen_at >= :week) AS active7d,
            COUNT(*) AS active30d
        FROM client_instances WHERE last_seen_at >= :month GROUP BY kind
        """,
    )
    fun countActiveByKind(day: Instant, week: Instant, month: Instant): List<ClientKindCount>

    /** Instances seen since [month] per build group: official or custom, group version, channel and source. */
    @Query(
        nativeQuery = true,
        value = """
        SELECT kind, custom, CASE WHEN custom THEN COALESCE(base, version) ELSE version END AS version, channel, source,
            COUNT(*) FILTER (WHERE last_seen_at >= :week) AS active7d, COUNT(*) AS active30d
        FROM client_instances WHERE last_seen_at >= :month
        GROUP BY 1, 2, 3, 4, 5
        """,
    )
    fun countActiveByBuildGroup(week: Instant, month: Instant): List<ClientBuildGroupCount>

    /** Instances seen since [month] per OS version (apps, agents) or browser (web). */
    @Query(
        nativeQuery = true,
        value = """
        SELECT kind, COALESCE(browser, os) AS name, COUNT(*) AS active30d
        FROM client_instances WHERE last_seen_at >= :month
        GROUP BY 1, 2
        """,
    )
    fun countActiveByPlatform(month: Instant): List<ClientPlatformCount>

    /** Today's totals per build group as of [now]; a second run on the same [day] replaces them. */
    @Transactional
    @Modifying
    @Query(
        nativeQuery = true,
        value = """
        INSERT INTO client_daily (day, kind, custom, version, channel, source, active_1d, active_7d, active_30d)
        SELECT CAST(:day AS date), kind, custom, CASE WHEN custom THEN COALESCE(base, version) ELSE version END,
            COALESCE(channel, ''), COALESCE(source, ''),
            COUNT(*) FILTER (WHERE last_seen_at >= :dayAgo), COUNT(*) FILTER (WHERE last_seen_at >= :weekAgo), COUNT(*)
        FROM client_instances WHERE last_seen_at >= :monthAgo
        GROUP BY 1, 2, 3, 4, 5, 6
        ON CONFLICT (day, kind, custom, version, channel, source) DO UPDATE SET active_1d = EXCLUDED.active_1d,
            active_7d = EXCLUDED.active_7d, active_30d = EXCLUDED.active_30d
        """,
    )
    fun writeDaily(day: LocalDate, dayAgo: Instant, weekAgo: Instant, monthAgo: Instant): Int

    /** The daily totals since [from], per kind, official or custom and group version (channels and sources summed). */
    @Query(
        nativeQuery = true,
        value = """
        SELECT day, kind, custom, version, SUM(active_1d) AS active1d, SUM(active_7d) AS active7d,
            SUM(active_30d) AS active30d
        FROM client_daily WHERE day >= :from
        GROUP BY 1, 2, 3, 4 ORDER BY 1, 2, 3, 4
        """,
    )
    fun findDaily(from: LocalDate): List<ClientDayCount>

    @Transactional
    @Modifying
    @Query(nativeQuery = true, value = "DELETE FROM client_instances WHERE last_seen_at < :cutoff")
    fun deleteSeenBefore(cutoff: Instant): Int
}

interface ClientKindCount {
    val kind: String
    val active1d: Long
    val active7d: Long
    val active30d: Long
}

interface ClientBuildGroupCount {
    val kind: String
    val custom: Boolean
    val version: String
    val channel: String?
    val source: String?
    val active7d: Long
    val active30d: Long
}

interface ClientPlatformCount {
    val kind: String
    val name: String?
    val active30d: Long
}

interface ClientDayCount {
    val day: LocalDate
    val kind: String
    val custom: Boolean
    val version: String
    val active1d: Long
    val active7d: Long
    val active30d: Long
}
