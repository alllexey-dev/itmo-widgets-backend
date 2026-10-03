package dev.alllexey.itmowidgets.backend.feature.reviews.persistence

import dev.alllexey.itmowidgets.backend.feature.reviews.model.SummaryRunOutcome
import dev.alllexey.itmowidgets.backend.feature.reviews.model.SummaryRunTrigger
import dev.alllexey.itmowidgets.backend.feature.reviews.model.TeacherSummaryStateEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.time.LocalDate

interface TeacherSummaryStateRepository : JpaRepository<TeacherSummaryStateEntity, Short> {
    /** Exactly one caller takes a free or stale lease; the taker is recorded as the last start. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        """
        UPDATE TeacherSummaryStateEntity s SET s.runningSince = :now, s.lastStartedAt = :now, s.lastTrigger = :trigger
        WHERE s.id = 1 AND (s.runningSince IS NULL OR s.runningSince < :staleBefore)
    """,
    )
    fun claim(now: Instant, staleBefore: Instant, trigger: SummaryRunTrigger): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE TeacherSummaryStateEntity s SET s.runningSince = NULL WHERE s.id = 1 AND s.runningSince IS NOT NULL")
    fun release(): Int

    /** Takes one request of the day's budget; a new day starts over at one. Zero when the budget is spent. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        value = """
        UPDATE teacher_summary_state
        SET budget_used = CASE WHEN budget_day = :day THEN budget_used + 1 ELSE 1 END, budget_day = :day
        WHERE id = 1 AND (budget_day IS DISTINCT FROM :day OR budget_used < :limit)
        """,
        nativeQuery = true,
    )
    fun takeBudget(day: LocalDate, limit: Int): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        """
        UPDATE TeacherSummaryStateEntity s
        SET s.lastFinishedAt = :now, s.lastOutcome = :outcome, s.lastError = :error, s.lastGenerated = :generated,
            s.lastFailed = :failed, s.lastRequests = :requests
        WHERE s.id = 1
    """,
    )
    fun finish(outcome: SummaryRunOutcome, error: String?, generated: Int, failed: Int, requests: Int, now: Instant): Int
}
