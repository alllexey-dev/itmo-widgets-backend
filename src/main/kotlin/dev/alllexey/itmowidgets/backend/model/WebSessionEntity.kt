package dev.alllexey.itmowidgets.backend.model

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/** A browser session; only the SHA-256 of the cookie token is stored. */
@Entity
@Table(name = "web_sessions")
class WebSessionEntity(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(nullable = false) val userId: UUID,
    @Column(nullable = false, unique = true, length = 64) @JdbcTypeCode(SqlTypes.CHAR) val tokenHash: String,
    @Column(length = 300) val userAgent: String?,
    @Column(nullable = false) val createdAt: Instant,
    @Column(nullable = false) var lastSeenAt: Instant,
    @Column(nullable = false) val expiresAt: Instant,
    var revokedAt: Instant? = null,
)
