package dev.alllexey.itmowidgets.backend.feature.moderation.service

import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationNoticeEntity
import dev.alllexey.itmowidgets.backend.feature.moderation.persistence.ModerationNoticeRepository
import dev.alllexey.itmowidgets.backend.feature.moderation.web.ModerationNotice
import dev.alllexey.itmowidgets.backend.feature.moderation.web.ModerationNoticeLink
import dev.alllexey.itmowidgets.backend.feature.moderation.web.ModerationNoticeReview
import org.springframework.data.domain.Limit
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/** A notice due for a push: [notice] without the teacher's name, which the caller adds after the transaction. */
data class DueNotice(val recipientId: UUID, val dueAt: Instant, val notice: ModerationNotice)

/** Short transactions over `moderation_notices`; never around FCM or name lookups. */
@Service
class ModerationNoticeStore(private val notices: ModerationNoticeRepository, private val targets: ModerationTargets) {
    /** The recipient's notices due within `(since, now]`, newest first, at most [limit]; deleted targets are left out. */
    @Transactional(readOnly = true)
    fun recent(recipientId: UUID, since: Instant, now: Instant, limit: Int): List<ModerationNotice> = views(
        notices.findAllByRecipientIdAndDueAtGreaterThanAndDueAtLessThanEqualOrderByDecidedAtDescDecisionIdDesc(
            recipientId,
            since,
            now,
            Limit.of(limit),
        ),
    )

    /**
     * Marks at most [limit] due notices sent and returns those to push: the ones whose target still exists and that
     * were not due before [staleBefore] (those are marked and skipped). A crash after the commit loses the pushes, like
     * any push of Backend; the bell still lists the notices.
     */
    @Transactional
    fun claimDue(now: Instant, staleBefore: Instant, limit: Int): Claimed {
        val due = notices.lockDue(now, limit)
        due.forEach { it.sentAt = now }
        val fresh = due.filter { !it.dueAt.isBefore(staleBefore) }
        val byId = views(fresh).associateBy { it.id }
        val pushes = fresh.mapNotNull { row -> byId[row.decisionId]?.let { DueNotice(row.recipientId, row.dueAt, it) } }
        return Claimed(due.size, pushes)
    }

    @Transactional
    fun purge(before: Instant): Int = notices.deleteDueBefore(before)

    private fun views(rows: List<ModerationNoticeEntity>): List<ModerationNotice> {
        val subjects = rows.groupBy { it.targetType }
            .flatMap { (type, group) -> targets.forType(type).noticeSubjects(group.map { it.targetId }).entries }
            .associate { it.toPair() }
        return rows.mapNotNull { row ->
            val subject = subjects[row.targetId] ?: return@mapNotNull null
            ModerationNotice(
                row.decisionId,
                row.outcome,
                row.note,
                row.decidedAt,
                subject.link?.let { ModerationNoticeLink(it.id, it.subjectId, it.subjectName, it.periodKey, it.title, it.host) },
                // Never stored: the caller names the teacher after its transaction.
                subject.review?.let { ModerationNoticeReview(it.id, it.teacherIsu, null, it.subjectTitle) },
            )
        }
    }

    /** [count] rows were claimed; [pushes] are the ones to send. */
    data class Claimed(val count: Int, val pushes: List<DueNotice>)
}
