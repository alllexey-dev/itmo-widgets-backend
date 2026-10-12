package dev.alllexey.itmowidgets.backend.feature.agent.model

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/** A personal agent token; only the SHA-256 of the token is stored. Status fields change through repository updates. */
@Entity
@Table(name = "agent_tokens")
class AgentTokenEntity(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(nullable = false) val userId: UUID,
    @Column(nullable = false, length = 60) val name: String,
    @Column(nullable = false, unique = true, length = 64) @JdbcTypeCode(SqlTypes.CHAR) val tokenHash: String,
    @Column(nullable = false, length = 12) val tokenHint: String,
    @Column(nullable = false, length = 100) val scopes: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 8) val createdVia: AgentTokenOrigin,
    @Column(nullable = false) val createdAt: Instant,
    val lastUsedAt: Instant? = null,
    val lastPollAt: Instant? = null,
    @Column(nullable = false) val itmoSessionOk: Boolean = false,
    val ackedSeq: Long? = null,
    val revokedAt: Instant? = null,
) {
    val scopeSet: Set<AgentScope> get() = AgentScope.parse(scopes)
}
