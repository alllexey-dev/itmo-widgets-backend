package dev.alllexey.itmowidgets.backend.feature.agent.persistence

import dev.alllexey.itmowidgets.backend.feature.agent.model.AgentEventRow
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * `agent_users` and `agent_events` over JDBC: the payload is jsonb and the sequence is a row lock, neither of which JPA
 * adds anything to. Callers hold the transaction.
 */
@Repository
class AgentEventStore(private val jdbc: NamedParameterJdbcTemplate) {
    /** Locks the user's sequence row, creating it, and returns the newest seq handed out. */
    fun lockSequence(userId: UUID): Long {
        val params = mapOf("userId" to userId)
        jdbc.update("INSERT INTO agent_users (user_id) VALUES (:userId) ON CONFLICT (user_id) DO NOTHING", params)
        return jdbc.queryForObject("SELECT last_event_seq FROM agent_users WHERE user_id = :userId FOR UPDATE", params, Long::class.java)!!
    }

    fun lastSeq(userId: UUID): Long =
        jdbc.queryForList("SELECT last_event_seq FROM agent_users WHERE user_id = :userId", mapOf("userId" to userId), Long::class.java)
            .firstOrNull() ?: 0

    fun oldestSeq(userId: UUID): Long? =
        jdbc.queryForObject("SELECT min(seq) FROM agent_events WHERE user_id = :userId", mapOf("userId" to userId), Long::class.java)

    fun exists(userId: UUID, dedupKey: String): Boolean = jdbc.queryForObject(
        "SELECT EXISTS (SELECT 1 FROM agent_events WHERE user_id = :userId AND dedup_key = :dedupKey)",
        mapOf("userId" to userId, "dedupKey" to dedupKey),
        Boolean::class.java,
    )!!

    /** Appends [seq] and moves the user's sequence to it; the caller holds [lockSequence]. */
    fun insert(userId: UUID, seq: Long, dedupKey: String, type: String, payload: String, createdAt: Instant, expiresAt: Instant) {
        jdbc.update(
            """
            INSERT INTO agent_events (user_id, seq, dedup_key, type, payload, created_at, expires_at)
            VALUES (:userId, :seq, :dedupKey, :type, CAST(:payload AS jsonb), :createdAt, :expiresAt)
            """,
            mapOf(
                "userId" to userId,
                "seq" to seq,
                "dedupKey" to dedupKey,
                "type" to type,
                "payload" to payload,
                "createdAt" to createdAt.utc(),
                "expiresAt" to expiresAt.utc(),
            ),
        )
        jdbc.update("UPDATE agent_users SET last_event_seq = :seq WHERE user_id = :userId", mapOf("userId" to userId, "seq" to seq))
    }

    /** Events in `(after, through]` of [types], oldest first. */
    fun events(userId: UUID, after: Long, through: Long, types: Set<String>, limit: Int): List<AgentEventRow> {
        if (types.isEmpty()) return emptyList()
        return jdbc.query(
            """
            SELECT seq, dedup_key, type, CAST(payload AS text) AS payload, created_at, expires_at FROM agent_events
            WHERE user_id = :userId AND seq > :after AND seq <= :through AND type IN (:types)
            ORDER BY seq LIMIT :limit
            """,
            mapOf("userId" to userId, "after" to after, "through" to through, "types" to types, "limit" to limit),
        ) { rs, _ ->
            AgentEventRow(
                rs.getLong("seq"),
                rs.getString("dedup_key"),
                rs.getString("type"),
                rs.getString("payload"),
                rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
                rs.getObject("expires_at", OffsetDateTime::class.java).toInstant(),
            )
        }
    }

    /** Drops expired events and everything but each user's newest [keep]; seq stays monotonic in `agent_users`. */
    fun deleteExpired(now: Instant, keep: Int): Int =
        jdbc.update("DELETE FROM agent_events WHERE expires_at <= :now", mapOf("now" to now.utc())) +
            jdbc.update(
                """
                DELETE FROM agent_events e USING agent_users u
                WHERE u.user_id = e.user_id AND e.seq <= u.last_event_seq - :keep
                """,
                mapOf("keep" to keep),
            )

    fun hasAccess(userId: UUID): Boolean = jdbc.queryForObject(
        "SELECT EXISTS (SELECT 1 FROM agent_users WHERE user_id = :userId AND access_granted_at IS NOT NULL)",
        mapOf("userId" to userId),
        Boolean::class.java,
    )!!

    /** Returns true when access changed. */
    fun grantAccess(userId: UUID, now: Instant): Boolean = jdbc.update(
        """
        INSERT INTO agent_users (user_id, access_granted_at) VALUES (:userId, :now)
        ON CONFLICT (user_id) DO UPDATE SET access_granted_at = :now WHERE agent_users.access_granted_at IS NULL
        """,
        mapOf("userId" to userId, "now" to now.utc()),
    ) > 0

    fun revokeAccess(userId: UUID): Boolean = jdbc.update(
        "UPDATE agent_users SET access_granted_at = NULL WHERE user_id = :userId AND access_granted_at IS NOT NULL",
        mapOf("userId" to userId),
    ) > 0

    private fun Instant.utc(): OffsetDateTime = atOffset(ZoneOffset.UTC)
}
