package dev.alllexey.itmowidgets.backend.feature.sheets.service

import dev.alllexey.itmowidgets.backend.feature.sheets.model.SheetRuleEntity
import dev.alllexey.itmowidgets.backend.feature.sheets.persistence.SheetRuleRepository
import dev.alllexey.itmowidgets.backend.feature.sheets.persistence.SheetRuleStateRepository
import org.springframework.data.domain.Limit
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Each call commits on its own; the job calls the analyzer outside any transaction. */
@Service
@Transactional(propagation = Propagation.REQUIRES_NEW)
class SheetRuleStore(
    private val rules: SheetRuleRepository,
    private val states: SheetRuleStateRepository,
    private val config: SheetRulesConfig,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    fun find(sheetId: String): SheetRuleEntity? = rules.findById(sheetId).orElse(null)

    /** Queues [sheetId] when it has no row or its row is due ([SheetRuleEntity.isDue]); true when it was queued. */
    fun request(sheetId: String, now: Instant): Boolean =
        rules.insertPending(sheetId, now) == 1 || rules.requeue(sheetId, now.minus(config.staleAfter), now) == 1

    fun claim(now: Instant): Boolean = states.claim(now, now.minus(STALE_LEASE)) == 1

    fun renew(now: Instant) {
        states.renew(now)
    }

    fun release() {
        states.release()
    }

    /** At startup: a lease and running rows left by a crash; the backend is a single instance. */
    fun recover(now: Instant) {
        states.release()
        rules.releaseRunning(now)
    }

    fun hasPending(): Boolean = rules.existsPending()

    fun next(runStartedAt: Instant): SheetRuleEntity? = rules.findPending(runStartedAt, Limit.of(1)).firstOrNull()

    fun markRunning(sheetId: String, now: Instant): Boolean = rules.markRunning(sheetId, now) == 1

    fun hasBudget(now: Instant): Boolean = states.hasBudget(day(now), config.dailyBudget)

    fun takeBudget(now: Instant): Boolean = states.takeBudget(day(now), config.dailyBudget) == 1

    fun confirm(sheetId: String, now: Instant) {
        rules.confirm(sheetId, now)
    }

    fun restore(sheetId: String, code: String, now: Instant) {
        rules.restore(sheetId, code, now)
    }

    fun clearVerdict(sheetId: String, now: Instant) {
        rules.clearVerdict(sheetId, now)
    }

    fun recordReady(sheetId: String, rule: String, fingerprint: String, now: Instant) {
        rules.recordReady(sheetId, rule, fingerprint, config.model, now)
    }

    fun recordInsufficient(sheetId: String, fingerprint: String, now: Instant) {
        rules.recordInsufficient(sheetId, fingerprint, config.model, now)
    }

    fun recordAttempt(sheetId: String, code: String, now: Instant) {
        rules.recordAttempt(sheetId, code, config.maxAttempts, now)
    }

    fun recordFailed(sheetId: String, code: String, now: Instant) {
        rules.recordFailed(sheetId, code, now)
    }

    fun defer(sheetId: String, code: String, now: Instant) {
        rules.defer(sheetId, code, now)
    }

    fun deferAllPending(code: String, now: Instant) {
        rules.deferAllPending(code, now)
    }

    private fun day(now: Instant): LocalDate = LocalDate.ofInstant(now, BUDGET_ZONE)

    companion object {
        /** Longer than one sheet's fingerprint and analysis together; a run renews it before each sheet. */
        val STALE_LEASE: Duration = Duration.ofMinutes(30)
        val BUDGET_ZONE: ZoneId = ZoneId.of("Europe/Moscow")
    }
}
