package dev.alllexey.itmowidgets.backend.feature.sheets.persistence

import dev.alllexey.itmowidgets.backend.feature.sheets.model.SheetRuleEntity
import dev.alllexey.itmowidgets.backend.feature.sheets.model.SheetRuleStateEntity
import org.springframework.data.domain.Limit
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.time.LocalDate

/**
 * Every write touches only its own columns and checks the status it expects, so a request and the job never
 * overwrite each other. A verdict is the rule with its fingerprint, model and `analyzed_at`, or the fingerprint
 * alone for `INSUFFICIENT`.
 */
interface SheetRuleRepository : JpaRepository<SheetRuleEntity, String> {
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        value = """
        INSERT INTO sheet_rules (sheet_id, status, requested_at, updated_at) VALUES (:sheetId, 'PENDING', :now, :now)
        ON CONFLICT (sheet_id) DO NOTHING
        """,
        nativeQuery = true,
    )
    fun insertPending(sheetId: String, now: Instant): Int

    /** A verdict checked before [staleBefore] or a failure older than it is asked again; a failure with fresh attempts. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        value = """
        UPDATE sheet_rules
        SET status = 'PENDING', requested_at = :now, last_error = NULL, updated_at = :now,
            attempts = CASE WHEN status = 'FAILED' THEN 0 ELSE attempts END
        WHERE sheet_id = :sheetId
          AND ((status IN ('READY', 'INSUFFICIENT') AND checked_at < :staleBefore)
              OR (status = 'FAILED' AND updated_at < :staleBefore))
        """,
        nativeQuery = true,
    )
    fun requeue(sheetId: String, staleBefore: Instant, now: Instant): Int

    /** The oldest request the run has not tried yet: an attempt sets `checked_at` to its time. */
    @Query(
        """
        SELECT r FROM SheetRuleEntity r WHERE r.status = dev.alllexey.itmowidgets.backend.feature.sheets.model.SheetRuleStatus.PENDING
          AND (r.checkedAt IS NULL OR r.checkedAt < :runStartedAt)
        ORDER BY r.requestedAt, r.sheetId
        """,
    )
    fun findPending(runStartedAt: Instant, limit: Limit): List<SheetRuleEntity>

    @Query(value = "SELECT EXISTS (SELECT 1 FROM sheet_rules WHERE status = 'PENDING')", nativeQuery = true)
    fun existsPending(): Boolean

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        value = "UPDATE sheet_rules SET status = 'RUNNING', updated_at = :now WHERE sheet_id = :sheetId AND status = 'PENDING'",
        nativeQuery = true,
    )
    fun markRunning(sheetId: String, now: Instant): Int

    /** A crashed run leaves rows RUNNING; they wait again. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE sheet_rules SET status = 'PENDING', updated_at = :now WHERE status = 'RUNNING'", nativeQuery = true)
    fun releaseRunning(now: Instant): Int

    /** The structure did not change: the stored verdict stands and counts as checked now. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        value = """
        UPDATE sheet_rules
        SET status = CASE WHEN rule IS NULL THEN 'INSUFFICIENT' ELSE 'READY' END,
            last_error = CASE WHEN rule IS NULL THEN 'insufficient' END,
            checked_at = :now, attempts = 0, updated_at = :now
        WHERE sheet_id = :sheetId AND status = 'RUNNING' AND fingerprint IS NOT NULL
        """,
        nativeQuery = true,
    )
    fun confirm(sheetId: String, now: Instant): Int

    /** A check of a stored verdict failed for a passing reason: the verdict stays, still stale; a rule notes the reason. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        value = """
        UPDATE sheet_rules
        SET status = CASE WHEN rule IS NULL THEN 'INSUFFICIENT' ELSE 'READY' END,
            last_error = CASE WHEN rule IS NULL THEN 'insufficient' ELSE :code END, updated_at = :now
        WHERE sheet_id = :sheetId AND status = 'RUNNING' AND fingerprint IS NOT NULL
        """,
        nativeQuery = true,
    )
    fun restore(sheetId: String, code: String, now: Instant): Int

    /** The structure changed: the old verdict no longer applies, even if the new analysis fails. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        value = """
        UPDATE sheet_rules SET rule = NULL, fingerprint = NULL, model = NULL, analyzed_at = NULL, updated_at = :now
        WHERE sheet_id = :sheetId AND status = 'RUNNING'
        """,
        nativeQuery = true,
    )
    fun clearVerdict(sheetId: String, now: Instant): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        value = """
        UPDATE sheet_rules
        SET status = 'READY', rule = CAST(:rule AS jsonb), fingerprint = :fingerprint, model = :model, analyzed_at = :now,
            checked_at = :now, attempts = 0, last_error = NULL, updated_at = :now
        WHERE sheet_id = :sheetId AND status = 'RUNNING'
        """,
        nativeQuery = true,
    )
    fun recordReady(sheetId: String, rule: String, fingerprint: String, model: String, now: Instant): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        value = """
        UPDATE sheet_rules
        SET status = 'INSUFFICIENT', rule = NULL, fingerprint = :fingerprint, model = :model, analyzed_at = :now,
            checked_at = :now, attempts = 0, last_error = 'insufficient', updated_at = :now
        WHERE sheet_id = :sheetId AND status = 'RUNNING'
        """,
        nativeQuery = true,
    )
    fun recordInsufficient(sheetId: String, fingerprint: String, model: String, now: Instant): Int

    /** A failed attempt: the row waits for another one, or fails for good at [maxAttempts]; it has no verdict. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        value = """
        UPDATE sheet_rules
        SET status = CASE WHEN attempts + 1 >= :maxAttempts THEN 'FAILED' ELSE 'PENDING' END,
            attempts = attempts + 1, last_error = :code, rule = NULL, fingerprint = NULL, model = NULL,
            analyzed_at = NULL, checked_at = :now, updated_at = :now
        WHERE sheet_id = :sheetId AND status = 'RUNNING'
        """,
        nativeQuery = true,
    )
    fun recordAttempt(sheetId: String, code: String, maxAttempts: Int, now: Instant): Int

    /** A failure no retry fixes now (the sheet is closed, gone or too large); the verdict goes too. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        value = """
        UPDATE sheet_rules
        SET status = 'FAILED', attempts = attempts + 1, last_error = :code, rule = NULL, fingerprint = NULL, model = NULL,
            analyzed_at = NULL, checked_at = :now, updated_at = :now
        WHERE sheet_id = :sheetId AND status = 'RUNNING'
        """,
        nativeQuery = true,
    )
    fun recordFailed(sheetId: String, code: String, now: Instant): Int

    /** The analyzer could not take the row now (busy, unavailable, no budget): it waits without spending an attempt. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        value = """
        UPDATE sheet_rules SET status = 'PENDING', last_error = :code, updated_at = :now
        WHERE sheet_id = :sheetId AND status = 'RUNNING'
        """,
        nativeQuery = true,
    )
    fun defer(sheetId: String, code: String, now: Instant): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        value = """
        UPDATE sheet_rules SET last_error = :code, updated_at = :now
        WHERE status = 'PENDING' AND last_error IS DISTINCT FROM :code
        """,
        nativeQuery = true,
    )
    fun deferAllPending(code: String, now: Instant): Int
}

interface SheetRuleStateRepository : JpaRepository<SheetRuleStateEntity, Short> {
    /** Exactly one caller takes a free or stale lease. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        "UPDATE SheetRuleStateEntity s SET s.runningSince = :now WHERE s.id = 1 AND (s.runningSince IS NULL OR s.runningSince < :staleBefore)",
    )
    fun claim(now: Instant, staleBefore: Instant): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE SheetRuleStateEntity s SET s.runningSince = NULL WHERE s.id = 1 AND s.runningSince IS NOT NULL")
    fun release(): Int

    /** A run renews its lease before each sheet, so only a run that stopped moving looks stale. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE SheetRuleStateEntity s SET s.runningSince = :now WHERE s.id = 1 AND s.runningSince IS NOT NULL")
    fun renew(now: Instant): Int

    @Query(
        value = "SELECT budget_day IS DISTINCT FROM :day OR budget_used < :limit FROM sheet_rule_state WHERE id = 1",
        nativeQuery = true,
    )
    fun hasBudget(day: LocalDate, limit: Int): Boolean

    /** Takes one analysis of the day's budget; a new day starts over at one. Zero when the budget is spent. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        value = """
        UPDATE sheet_rule_state
        SET budget_used = CASE WHEN budget_day = :day THEN budget_used + 1 ELSE 1 END, budget_day = :day
        WHERE id = 1 AND (budget_day IS DISTINCT FROM :day OR budget_used < :limit)
        """,
        nativeQuery = true,
    )
    fun takeBudget(day: LocalDate, limit: Int): Int
}
