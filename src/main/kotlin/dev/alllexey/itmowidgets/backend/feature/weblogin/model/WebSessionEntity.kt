package dev.alllexey.itmowidgets.backend.feature.weblogin.model

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/**
 * A browser session; only SHA-256 hashes of its tokens are stored. [tokenHash] is the access token, valid until
 * [accessExpiresAt] (always, for sessions issued before refresh tokens); [refreshHash] rotates both, keeping the
 * [previousRefreshHash] rotated at [rotatedAt].
 */
@Entity
@Table(name = "web_sessions")
class WebSessionEntity(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(nullable = false) val userId: UUID,
    @Column(nullable = false, unique = true, length = 64) @JdbcTypeCode(SqlTypes.CHAR) var tokenHash: String,
    @Column(length = 300) val userAgent: String?,
    @Column(nullable = false) val createdAt: Instant,
    @Column(nullable = false) var lastSeenAt: Instant,
    @Column(nullable = false) val expiresAt: Instant,
    var revokedAt: Instant? = null,
    var accessExpiresAt: Instant? = null,
    @Column(unique = true, length = 64) @JdbcTypeCode(SqlTypes.CHAR) var refreshHash: String? = null,
    @Column(unique = true, length = 64) @JdbcTypeCode(SqlTypes.CHAR) var previousRefreshHash: String? = null,
    var rotatedAt: Instant? = null,
)
