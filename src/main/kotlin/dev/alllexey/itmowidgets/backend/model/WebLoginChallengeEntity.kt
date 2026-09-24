package dev.alllexey.itmowidgets.backend.model

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

enum class WebLoginStatus { PENDING, APPROVED, CLAIMED, EXPIRED }

/** Status transitions happen through conditional repository updates, so concurrent approvals and polls cannot race. */
@Entity
@Table(name = "web_login_challenges")
class WebLoginChallengeEntity(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(nullable = false, length = 8) val code: String,
    @Column(nullable = false, length = 64) @JdbcTypeCode(SqlTypes.CHAR) val pollSecretHash: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) val status: WebLoginStatus = WebLoginStatus.PENDING,
    @Column(length = 300) val userAgent: String?,
    @Column(nullable = false, length = 64) val clientIp: String,
    @Column(nullable = false) val createdAt: Instant,
    @Column(nullable = false) val expiresAt: Instant,
    val approvedBy: UUID? = null,
    val approvedAt: Instant? = null,
)
