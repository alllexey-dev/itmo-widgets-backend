package dev.alllexey.itmowidgets.backend.feature.moderation.persistence

import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationNoticeEntity
import org.springframework.data.domain.Limit
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

interface ModerationNoticeRepository : JpaRepository<ModerationNoticeEntity, UUID> {
    /** Unsent notices that are due, oldest first, locked for the caller; a concurrent caller skips them. */
    @Query(
        value = """
            SELECT * FROM moderation_notices
            WHERE sent_at IS NULL AND due_at <= :now
            ORDER BY due_at, decision_id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
        """,
        nativeQuery = true,
    )
    fun lockDue(now: Instant, limit: Int): List<ModerationNoticeEntity>

    /** The recipient's notices that are due within `(since, now]`, newest decision first. */
    fun findAllByRecipientIdAndDueAtGreaterThanAndDueAtLessThanEqualOrderByDecidedAtDescDecisionIdDesc(
        recipientId: UUID,
        since: Instant,
        now: Instant,
        limit: Limit,
    ): List<ModerationNoticeEntity>

    @Modifying
    @Query("DELETE FROM ModerationNoticeEntity n WHERE n.decisionId = :decisionId")
    fun deleteByDecision(decisionId: UUID): Int

    @Modifying
    @Query("DELETE FROM ModerationNoticeEntity n WHERE n.dueAt < :before")
    fun deleteDueBefore(before: Instant): Int
}
