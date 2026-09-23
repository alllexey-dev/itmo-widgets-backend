package dev.alllexey.itmowidgets.backend.model

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

enum class ModerationActor { MODERATOR, POLICY }

enum class ModerationAction { APPROVE, REJECT, HIDE, RESTORE, DISMISS, RESTRICT_USER, HIDE_ALL_BY_USER;

    companion object {
        @JvmStatic
        @com.fasterxml.jackson.annotation.JsonCreator(mode = com.fasterxml.jackson.annotation.JsonCreator.Mode.DELEGATING)
        fun fromJson(value: com.fasterxml.jackson.databind.JsonNode): ModerationAction {
            require(value.isTextual) { "Expected an enum name string" }
            return entries.firstOrNull { it.name == value.textValue() }
                ?: throw IllegalArgumentException("Unknown enum value")
        }
    }
}

/** Never updated or removed: the decision is the immutable source of the moderation audit. */
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
)
