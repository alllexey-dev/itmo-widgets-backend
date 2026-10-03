package dev.alllexey.itmowidgets.backend.feature.moderation.model

import dev.alllexey.itmowidgets.backend.feature.users.model.User
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

enum class ReportReason {
    BROKEN,
    WRONG_SUBJECT,
    SPAM,
    OTHER,
    OFFENSIVE,
    WRONG_TEACHER,
    ;

    companion object {
        /** The reasons a teacher review can be reported for. */
        val REVIEW_REASONS = setOf(OFFENSIVE, WRONG_TEACHER, SPAM, OTHER)

        @JvmStatic
        @com.fasterxml.jackson.annotation.JsonCreator(mode = com.fasterxml.jackson.annotation.JsonCreator.Mode.DELEGATING)
        fun fromJson(value: com.fasterxml.jackson.databind.JsonNode): ReportReason {
            require(value.isTextual) { "Expected an enum name string" }
            return entries.firstOrNull { it.name == value.textValue() }
                ?: throw IllegalArgumentException("Unknown enum value")
        }
    }
}

@Entity
@Table(name = "moderation_reports")
class ModerationReportEntity(
    @Id val id: UUID = UUID.randomUUID(),
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) val targetType: ModerationTargetType,
    @Column(nullable = false) val targetId: UUID,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reporter_id", nullable = false) val reporter: User,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) val reason: ReportReason,
    @Column(length = 500) val comment: String? = null,
    @Column(nullable = false) val createdAt: Instant,
    var dismissedAt: Instant? = null,
)
