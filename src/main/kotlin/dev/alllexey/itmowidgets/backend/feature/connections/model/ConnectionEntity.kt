package dev.alllexey.itmowidgets.backend.feature.connections.model

import jakarta.persistence.*
import org.hibernate.annotations.DynamicUpdate
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/**
 * A connected client of a user. Only the SHA-256 of its token is stored, [tokenHash] is null until the client claims
 * its approval. [itmoSid] and [itmoExpiresAt] are the client's own ITMO.ID session as the phone reported it; a revoked
 * row keeps them until the phone ends the session ([itmoEndedAt]) or it expires. The per-request fields
 * ([lastUsedAt], [lastPollAt]) change through repository updates; [DynamicUpdate] keeps an entity flush off them.
 */
@Entity
@DynamicUpdate
@Table(name = "connections")
class ConnectionEntity(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(nullable = false) val userId: UUID,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 8) val client: ClientType,
    @Column(nullable = false, length = 60) val name: String,
    @Column(length = 40) var version: String?,
    @Column(length = 16) val platform: String?,
    @Column(nullable = false, length = 100) var scopes: String,
    @Column(unique = true, length = 64) @JdbcTypeCode(SqlTypes.CHAR) var tokenHash: String? = null,
    @Column(nullable = false) val createdAt: Instant,
    @Column(nullable = false) var approvedAt: Instant,
    val lastUsedAt: Instant? = null,
    val lastPollAt: Instant? = null,
    var revokedAt: Instant? = null,
    @Column(length = 64) var itmoSid: String? = null,
    var itmoExpiresAt: Instant? = null,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 8) var itmoStatus: ItmoStatus = ItmoStatus.NONE,
    var itmoEndedAt: Instant? = null,
) {
    val scopeSet: Set<ConnectionScope> get() = ConnectionScope.parse(scopes)
}
