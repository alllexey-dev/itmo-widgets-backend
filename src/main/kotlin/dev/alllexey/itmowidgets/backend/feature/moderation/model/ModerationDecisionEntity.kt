package dev.alllexey.itmowidgets.backend.feature.moderation.model

import dev.alllexey.itmowidgets.backend.feature.users.model.User
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

enum class ModerationActor { MODERATOR, POLICY }

enum class ModerationAction {
    APPROVE,
    REJECT,
    HIDE,
    RESTORE,
    DISMISS,
    RESTRICT_USER,
    HIDE_ALL_BY_USER,
    ;

    /** Whether a decision resolves its case; RESTRICT_USER and HIDE_ALL_BY_USER leave it open for the next decision. */
    val resolvesCase: Boolean get() = this != RESTRICT_USER && this != HIDE_ALL_BY_USER

    /**
     * Whether a moderator's decision may be reverted: the case-resolving actions, each of which changes one target.
     * RESTRICT_USER has its own revoke; HIDE_ALL_BY_USER changes many targets and withdraws other cases.
     */
    val revertible: Boolean get() = resolvesCase

    companion object {
        @JvmStatic
        @com.fasterxml.jackson.annotation.JsonCreator(mode = com.fasterxml.jackson.annotation.JsonCreator.Mode.DELEGATING)
        fun fromJson(value: tools.jackson.databind.JsonNode): ModerationAction {
            require(value.isString) { "Expected an enum name string" }
            return entries.firstOrNull { it.name == value.stringValue() }
                ?: throw IllegalArgumentException("Unknown enum value")
        }
    }
}

/**
 * Never updated or removed: the decision is the immutable source of the moderation audit. A revertible decision by a
 * moderator records in [prior] what it replaced; a revert is a [ModerationDecisionRevertEntity] of its own.
 */
@Entity
@org.hibernate.annotations.Immutable
@Table(name = "moderation_decisions")
class ModerationDecisionEntity(
    @Id val id: UUID = UUID.randomUUID(),
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "case_id", nullable = false) val case: ModerationCaseEntity,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "moderator_id") val moderator: User?,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) val action: ModerationAction,
    @Column(length = 500) val note: String? = null,
    @Enumerated(EnumType.STRING) @Column(length = 32) val restrictionCapability: RestrictionCapability? = null,
    val restrictionDays: Int? = null,
    @Column(nullable = false) val createdAt: Instant,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) val actor: ModerationActor = ModerationActor.MODERATOR,
    @Embedded val prior: DecisionPrior? = null,
)
