package dev.alllexey.itmowidgets.backend.feature.moderation.model

import dev.alllexey.itmowidgets.backend.feature.users.model.User
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/** Never updated or removed: the audit of an undone decision, which itself stays in `moderation_decisions`. */
@Entity
@org.hibernate.annotations.Immutable
@Table(name = "moderation_decision_reverts")
class ModerationDecisionRevertEntity(
    @Id val decisionId: UUID,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reverted_by", nullable = false) val revertedBy: User,
    @Column(nullable = false) val revertedAt: Instant,
)
