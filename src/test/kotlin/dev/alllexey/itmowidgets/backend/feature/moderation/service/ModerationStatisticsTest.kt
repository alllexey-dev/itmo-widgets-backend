package dev.alllexey.itmowidgets.backend.feature.moderation.service

import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationAction
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationActor
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationCaseReason
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationCaseStats
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationCaseStatus
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationCaseStatus.OPEN
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationCaseStatus.RESOLVED
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationCaseStatus.WITHDRAWN
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationDecisionCount
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationReportStats
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationStats
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationTargetType
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationTargetType.SUBJECT_RESOURCE
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationTargetType.TEACHER_REVIEW
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ReportReason
import dev.alllexey.itmowidgets.backend.feature.moderation.persistence.ModerationStatisticsRepository
import dev.alllexey.itmowidgets.backend.platform.PostgreSqlRepositoryTest
import dev.alllexey.itmowidgets.backend.platform.stats.FeatureUsage
import dev.alllexey.itmowidgets.backend.platform.stats.StatsPeriod
import dev.alllexey.itmowidgets.backend.testing.insertUser
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The moderation aggregates on real PostgreSQL with synthetic cases in 2032, a year no other test writes to. The
 * backlog also counts cases other tests left open in earlier years, so it is compared as a delta. Moscow is UTC+3:
 * its day starts at 21:00 UTC.
 */
@Import(ModerationStatistics::class, ModerationStatisticsRepository::class, ReportUsage::class)
class ModerationStatisticsTest @Autowired constructor(
    private val statistics: ModerationStatistics,
    private val reportUsage: ReportUsage,
    private val jdbc: JdbcTemplate,
) : PostgreSqlRepositoryTest() {
    private val period = StatsPeriod(LocalDate.parse("2032-03-01"), LocalDate.parse("2032-03-05"))
    private var nextIsu = 984_100

    @Test
    fun `an empty period has every day with a flat backlog and no cases, decisions or reports`() {
        val empty = StatsPeriod(LocalDate.parse("2032-07-01"), LocalDate.parse("2032-07-03"))
        val stats = statistics.stats(empty)

        assertEquals(empty.days, stats.days.map { it.day })
        assertTrue(stats.days.all { it.casesOpened == 0L && it.casesResolved == 0L && it.casesWithdrawn == 0L && it.reports == 0L })
        assertEquals(1, stats.days.map { it.backlog }.distinct().size)
        assertEquals(emptyList(), stats.cases)
        assertEquals(emptyList(), stats.decisions)
        assertEquals(emptyList(), stats.reports)
        assertEquals(FeatureUsage(0, emptySet()), reportUsage.usage(empty))
    }

    @Test
    fun `a one-day period has one day`() {
        val day = StatsPeriod(LocalDate.parse("2032-03-03"), LocalDate.parse("2032-03-03"))

        assertEquals(listOf(day.from), statistics.stats(day).days.map { it.day })
    }

    @Test
    fun `cases count by Moscow day with the backlog at each day's end, and by reason with their decision times`() {
        val before = statistics.stats(period)
        val moderator = user()
        // Open before the period: two still open at its start, one resolved in it.
        case(SUBJECT_RESOURCE, ModerationCaseReason.REPORTS, "2032-02-20T10:00:00Z", OPEN)
        case(TEACHER_REVIEW, ModerationCaseReason.SUBMISSION, "2032-02-29T20:59:59Z", OPEN)
        val earlier = case(SUBJECT_RESOURCE, ModerationCaseReason.VOTES, "2032-02-25T10:00:00Z", RESOLVED, "2032-03-02T10:00:00Z")
        // Opened in the period.
        val midnight = case(SUBJECT_RESOURCE, ModerationCaseReason.SUBMISSION, "2032-02-29T21:00:00Z", RESOLVED, "2032-03-01T21:01:40Z")
        val quick = case(SUBJECT_RESOURCE, ModerationCaseReason.SUBMISSION, "2032-03-01T10:00:00Z", RESOLVED, "2032-03-01T10:10:00Z")
        case(TEACHER_REVIEW, ModerationCaseReason.REPORTS, "2032-03-03T12:00:00Z", WITHDRAWN, "2032-03-04T12:00:00Z")
        val last = case(TEACHER_REVIEW, ModerationCaseReason.REPORTS, "2032-03-05T20:59:59Z", OPEN)
        val after = case(TEACHER_REVIEW, ModerationCaseReason.REPORTS, "2032-03-05T21:00:00Z", OPEN)
        decision(quick, ModerationAction.APPROVE, "2032-03-01T10:10:00Z", moderator)
        decision(midnight, ModerationAction.APPROVE, "2032-03-01T21:01:40Z", moderator = null)
        decision(earlier, ModerationAction.REJECT, "2032-03-02T10:00:00Z", moderator)
        decision(earlier, ModerationAction.HIDE, "2032-02-28T10:00:00Z", moderator)
        decision(last, ModerationAction.HIDE, "2032-03-05T21:00:00Z", moderator)
        decision(after, ModerationAction.DISMISS, "2032-03-06T10:00:00Z", moderator)

        val stats = statistics.stats(period)

        assertEquals(listOf(2L, 0L, 1L, 0L, 1L), stats.days.map { it.casesOpened })
        assertEquals(listOf(1L, 2L, 0L, 0L, 0L), stats.days.map { it.casesResolved })
        assertEquals(listOf(0L, 0L, 0L, 1L, 0L), stats.days.map { it.casesWithdrawn })
        // Three open at the start; the end of the last day leaves the two earlier open cases and the last one.
        assertEquals(listOf(4L, 2L, 3L, 2L, 3L), stats.days.mapIndexed { index, day -> day.backlog - before.days[index].backlog })
        assertEquals(
            listOf(
                // 86500 s and 600 s.
                ModerationCaseStats(SUBJECT_RESOURCE, ModerationCaseReason.SUBMISSION, 2, 2, 0, 43550, 86500),
                ModerationCaseStats(TEACHER_REVIEW, ModerationCaseReason.REPORTS, 2, 0, 1, null, null),
            ),
            stats.cases,
        )
        assertEquals(
            listOf(
                ModerationDecisionCount(SUBJECT_RESOURCE, ModerationAction.APPROVE, ModerationActor.MODERATOR, 1),
                ModerationDecisionCount(SUBJECT_RESOURCE, ModerationAction.APPROVE, ModerationActor.POLICY, 1),
                ModerationDecisionCount(SUBJECT_RESOURCE, ModerationAction.REJECT, ModerationActor.MODERATOR, 1),
            ),
            stats.decisions,
        )
    }

    @Test
    fun `a reverted decision is no outcome of moderation, the decision taken after its revert is`() {
        val moderator = user()
        val case = case(SUBJECT_RESOURCE, ModerationCaseReason.SUBMISSION, "2032-03-02T10:00:00Z", RESOLVED, "2032-03-02T10:20:00Z")
        val undone = decision(case, ModerationAction.REJECT, "2032-03-02T10:05:00Z", moderator)
        revert(undone, moderator, "2032-03-02T10:08:00Z")
        decision(case, ModerationAction.APPROVE, "2032-03-02T10:20:00Z", moderator)

        assertEquals(
            listOf(ModerationDecisionCount(SUBJECT_RESOURCE, ModerationAction.APPROVE, ModerationActor.MODERATOR, 1)),
            statistics.stats(period).decisions,
        )
    }

    @Test
    fun `reports count by Moscow creation day, reason and dismissal, and their reporters use the feature`() {
        val (first, second, third) = List(3) { user() }
        val link = UUID.randomUUID()
        report(SUBJECT_RESOURCE, link, first, ReportReason.BROKEN, "2032-03-01T10:00:00Z", dismissed = true)
        report(SUBJECT_RESOURCE, link, second, ReportReason.BROKEN, "2032-03-01T12:00:00Z")
        report(TEACHER_REVIEW, UUID.randomUUID(), first, ReportReason.OFFENSIVE, "2032-03-04T21:00:00Z")
        report(SUBJECT_RESOURCE, UUID.randomUUID(), third, ReportReason.SPAM, "2032-02-29T20:59:59Z")
        report(SUBJECT_RESOURCE, UUID.randomUUID(), third, ReportReason.SPAM, "2032-03-05T21:00:00Z")

        val stats: ModerationStats = statistics.stats(period)

        assertEquals(listOf(2L, 0L, 0L, 0L, 1L), stats.days.map { it.reports })
        assertEquals(
            listOf(
                ModerationReportStats(SUBJECT_RESOURCE, ReportReason.BROKEN, total = 2, dismissed = 1),
                ModerationReportStats(TEACHER_REVIEW, ReportReason.OFFENSIVE, total = 1, dismissed = 0),
            ),
            stats.reports,
        )
        assertEquals(FeatureUsage(3, setOf(first, second)), reportUsage.usage(period))
        assertEquals("REPORTS", reportUsage.feature)
        assertTrue(reportUsage.ownAction)
    }

    private fun user(): UUID = jdbc.insertUser(nextIsu++, createdAt = Instant.parse("2032-01-01T00:00:00Z"))

    private fun case(
        type: ModerationTargetType,
        reason: ModerationCaseReason,
        opened: String,
        status: ModerationCaseStatus,
        closed: String? = null,
    ): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO moderation_cases (id, target_type, target_id, status, reason, opened_at, resolved_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
            id,
            type.name,
            UUID.randomUUID(),
            status.name,
            reason.name,
            at(opened),
            closed?.let(::at),
        )
        return id
    }

    /** A policy decision without [moderator]; the policy only approves. */
    private fun decision(case: UUID, action: ModerationAction, created: String, moderator: UUID?): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO moderation_decisions (id, case_id, moderator_id, actor, action, created_at) VALUES (?, ?, ?, ?, ?, ?)",
            id,
            case,
            moderator,
            if (moderator == null) ModerationActor.POLICY.name else ModerationActor.MODERATOR.name,
            action.name,
            at(created),
        )
        return id
    }

    private fun revert(decision: UUID, moderator: UUID, reverted: String) {
        jdbc.update(
            "INSERT INTO moderation_decision_reverts (decision_id, reverted_by, reverted_at) VALUES (?, ?, ?)",
            decision,
            moderator,
            at(reverted),
        )
    }

    private fun report(
        type: ModerationTargetType,
        target: UUID,
        reporter: UUID,
        reason: ReportReason,
        created: String,
        dismissed: Boolean = false,
    ) {
        jdbc.update(
            """
            INSERT INTO moderation_reports (id, target_type, target_id, reporter_id, reason, created_at, dismissed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """,
            UUID.randomUUID(),
            type.name,
            target,
            reporter,
            reason.name,
            at(created),
            if (dismissed) at("2032-03-06T00:00:00Z") else null,
        )
    }

    private fun at(text: String): Timestamp = Timestamp.from(Instant.parse(text))
}
