package dev.alllexey.itmowidgets.backend.feature.weblogin.model

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

enum class WebLoginStatus { PENDING, APPROVED, CLAIMED, EXPIRED, REJECTED }

/** What a challenge connects (docs/contracts/pairing.md#kinds). Every kind but [WEB_SESSION] carries a key commitment. */
enum class PairingKind {
    WEB_SESSION,
    AGENT,
    AGENT_SESSION,
    WEB_ITMO,
    ;

    val keyed: Boolean get() = this != WEB_SESSION
}

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
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) val kind: PairingKind = PairingKind.WEB_SESSION,
    /** SHA-256 over the target's public key and pairing secret; never the key itself. */
    val keyCommitment: ByteArray? = null,
    @Column(length = 60) val targetName: String? = null,
    @Column(length = 40) val requestedByVersion: String? = null,
    val agentTokenId: UUID? = null,
    val openedBy: UUID? = null,
    val openedAt: Instant? = null,
    @Column(length = 100) val scopes: String? = null,
    /** The HPKE ciphertext for the target; nulled at the claim and after expiry. */
    val sealedBundle: ByteArray? = null,
)
