package dev.alllexey.itmowidgets.backend.feature.admin.service

import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminCaseStats
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminDecisionStats
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminModerationStats
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminModerationStatsDay
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminReportStats
import dev.alllexey.itmowidgets.backend.feature.links.service.LinkStatistics
import dev.alllexey.itmowidgets.backend.feature.moderation.service.ModerationStatistics
import dev.alllexey.itmowidgets.backend.feature.reviews.service.ReviewStatistics
import dev.alllexey.itmowidgets.backend.platform.stats.StatsPeriod
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * The reviews and moderation tab of the statistics page: authorizes, resolves the period and composes the figures of
 * [ReviewStatistics], [LinkStatistics] and [ModerationStatistics] into one answer.
 */
@Service
class AdminModerationStatsService(
    private val access: AdminAccess,
    private val reviews: ReviewStatistics,
    private val links: LinkStatistics,
    private val moderation: ModerationStatistics,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun moderation(adminId: UUID, from: LocalDate?, to: LocalDate?): AdminModerationStats {
        access.requireAdmin(adminId)
        val period = StatsPeriod.requested(from, to, clock)
        val reviewStats = reviews.stats(period)
        val linkVotes = links.votesPerDay(period)
        val moderationStats = moderation.stats(period)
        // Each series has one row per day of the period, so the same index is the same day.
        val days = period.days.indices.map { index ->
            val review = reviewStats.days[index]
            val cases = moderationStats.days[index]
            AdminModerationStatsDay(
                date = review.day,
                newReviews = review.newReviews,
                reviewVotes = review.reviewVotes,
                externalReviewVotes = review.externalReviewVotes,
                linkVotes = linkVotes[index].count,
                casesOpened = cases.casesOpened,
                casesResolved = cases.casesResolved,
                casesWithdrawn = cases.casesWithdrawn,
                backlog = cases.backlog,
                reports = cases.reports,
            )
        }
        return AdminModerationStats(
            from = period.from,
            to = period.to,
            days = days,
            ownReviews = reviewStats.ownReviews,
            cases = moderationStats.cases.map {
                AdminCaseStats(
                    it.targetType,
                    it.reason,
                    it.opened,
                    it.resolved,
                    it.withdrawn,
                    it.medianSecondsToDecision,
                    it.maxSecondsToDecision,
                )
            },
            decisions = moderationStats.decisions.map { AdminDecisionStats(it.targetType, it.action, it.actor, it.count) },
            reports = moderationStats.reports.map { AdminReportStats(it.targetType, it.reason, it.total, it.dismissed) },
        )
    }
}
