package dev.alllexey.itmowidgets.backend.model

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

enum class ModerationTargetType { SUBJECT_RESOURCE }
enum class ModerationCaseStatus { OPEN, RESOLVED, WITHDRAWN }
enum class ModerationCaseReason { SUBMISSION, REPORTS, VOTES }

@Entity
@Table(name = "moderation_cases")
class ModerationCaseEntity(
    @Id val id: UUID = UUID.randomUUID(),
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) val targetType: ModerationTargetType,
    @Column(nullable = false) val targetId: UUID,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) var status: ModerationCaseStatus = ModerationCaseStatus.OPEN,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) val reason: ModerationCaseReason,
    @Column(nullable = false) val openedAt: Instant,
    var resolvedAt: Instant? = null,
)
