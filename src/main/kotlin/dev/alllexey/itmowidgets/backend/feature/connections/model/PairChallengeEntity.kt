package dev.alllexey.itmowidgets.backend.feature.connections.model

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/** A new client ([CONNECT]) or a new approval for an existing connection ([RENEW]). */
enum class PairKind { CONNECT, RENEW }

enum class PairStatus { PENDING, APPROVED, CLAIMED, EXPIRED }

/**
 * A pairing request (docs/contracts/connections.md#pairing). [keyCommitment] is SHA-256 over the client's key and
 * pairing secret, never the key itself; [sealedBundle] the HPKE ciphertext for the client until its claim. Status
 * transitions happen through conditional repository updates, so concurrent approvals and claims cannot race.
 */
@Entity
@Table(name = "pair_challenges")
class PairChallengeEntity(
    @Id val id: UUID = UUID.randomUUID(),
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 8) val kind: PairKind,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 8) val client: ClientType,
    @Column(nullable = false, length = 8) val code: String,
    @Column(nullable = false, length = 64) @JdbcTypeCode(SqlTypes.CHAR) val claimSecretHash: String,
    @Column(nullable = false) val keyCommitment: ByteArray,
    @Column(nullable = false, length = 60) val name: String,
    @Column(length = 40) val version: String?,
    @Column(length = 16) val platform: String?,
    @Column(nullable = false, length = 100) val scopes: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) val status: PairStatus = PairStatus.PENDING,
    @Column(nullable = false, length = 64) val clientIp: String,
    @Column(nullable = false) val createdAt: Instant,
    @Column(nullable = false) val expiresAt: Instant,
    val openedBy: UUID? = null,
    val openedAt: Instant? = null,
    val approvedAt: Instant? = null,
    val connectionId: UUID? = null,
    val sealedBundle: ByteArray? = null,
) {
    val scopeSet: Set<ConnectionScope> get() = ConnectionScope.parse(scopes)
}
