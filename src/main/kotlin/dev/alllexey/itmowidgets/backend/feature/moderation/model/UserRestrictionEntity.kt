package dev.alllexey.itmowidgets.backend.feature.moderation.model

import dev.alllexey.itmowidgets.backend.feature.users.model.User
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

enum class RestrictionCapability {
    SUBMIT_RESOURCES,
    VOTE,
    REPORT,
    WRITE_REVIEWS,
    ALL,
    ;

    companion object {
        @JvmStatic
        @com.fasterxml.jackson.annotation.JsonCreator(mode = com.fasterxml.jackson.annotation.JsonCreator.Mode.DELEGATING)
        fun fromJson(value: tools.jackson.databind.JsonNode): RestrictionCapability {
            require(value.isString) { "Restriction capability must be an enum name string" }
            return entries.firstOrNull { it.name == value.stringValue() }
                ?: throw IllegalArgumentException("Unknown restriction capability")
        }
    }
}

@Entity
@Table(name = "user_restrictions")
class UserRestrictionEntity(
    @Id val id: UUID = UUID.randomUUID(),
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false) val user: User,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) val capability: RestrictionCapability,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "decision_id", nullable = false) val decision: ModerationDecisionEntity,
    @Column(nullable = false, length = 500) val reason: String,
    @Column(nullable = false) val startsAt: Instant,
    val expiresAt: Instant? = null,
    var revokedAt: Instant? = null,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "revoked_by") var revokedBy: User? = null,
)
