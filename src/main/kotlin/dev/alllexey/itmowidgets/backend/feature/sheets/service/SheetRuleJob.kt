package dev.alllexey.itmowidgets.backend.feature.sheets.service

import dev.alllexey.itmowidgets.backend.feature.links.model.ResourceUrlPolicy
import dev.alllexey.itmowidgets.backend.feature.links.service.SubjectLinkService
import dev.alllexey.itmowidgets.backend.platform.error.SafeDiagnostics
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationListener
import org.springframework.core.task.TaskExecutor
import org.springframework.core.task.TaskRejectedException
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant

/**
 * Works through queued sheets one at a time on [executor] under the `running_since` lease. For each sheet: is it
 * still shared with a flow or everybody, then `fingerprint`; an unchanged structure keeps the stored verdict, and only
 * a changed one or none at all spends an `analyze` of the day budget. A sheet is tried at most once per run; the
 * minute tick retries what is left. Logs carry counters and reason codes only.
 */
@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SheetRuleJob(
    private val store: SheetRuleStore,
    private val analyzer: SheetAnalyzerClient,
    private val links: SubjectLinkService,
    private val config: SheetRulesConfig,
    @Qualifier("sheetRuleExecutor") private val executor: TaskExecutor,
    private val clock: Clock,
    private val jsonMapper: JsonMapper,
) : ApplicationListener<ApplicationReadyEvent> {
    override fun onApplicationEvent(event: ApplicationReadyEvent) {
        store.recover(clock.instant())
    }

    /** Picks up requests a busy analyzer, a spent budget or a request during a run left waiting. */
    @Scheduled(fixedDelay = TICK_MILLIS, initialDelay = TICK_MILLIS)
    fun tick() {
        if (!config.enabled) return
        try {
            if (store.hasPending()) kick()
        } catch (error: Exception) {
            logger.error("Sheet rules tick failed: {}", SafeDiagnostics.describe(error), error)
        }
    }

    /** Starts a run unless one is going; called after a request is committed. */
    fun kick() {
        if (!config.enabled || !store.claim(clock.instant())) return
        try {
            executor.execute(::runClaimed)
        } catch (_: TaskRejectedException) {
            store.release()
        }
    }

    private fun runClaimed() {
        val started = System.nanoTime()
        val run = Run(clock.instant())
        try {
            var more = true
            while (more) more = process(run)
        } catch (error: Exception) {
            logger.error("Sheet rules run failed: {}", SafeDiagnostics.describe(error), error)
        } finally {
            try {
                store.release()
            } catch (releaseError: Exception) {
                logger.error("Sheet rules lease release failed: {}", SafeDiagnostics.describe(releaseError), releaseError)
            }
            logger.info(
                "Sheet rules run ready={} confirmed={} insufficient={} failed={} deferred={} analyses={} durationMs={}",
                run.ready,
                run.confirmed,
                run.insufficient,
                run.failed,
                run.deferred,
                run.analyses,
                (System.nanoTime() - started) / 1_000_000,
            )
        }
    }

    /** One sheet; false when the run stops. */
    private fun process(run: Run): Boolean {
        val now = clock.instant()
        if (!store.hasBudget(now)) {
            store.deferAllPending(BUDGET_EXHAUSTED, now)
            return false
        }
        val row = store.next(run.startedAt) ?: return false
        val id = row.sheetId
        store.renew(now)
        if (!store.markRunning(id, now)) return true
        if (!links.isShared(ResourceUrlPolicy.googleSheetUrl(id))) {
            store.recordFailed(id, NOT_SHARED, clock.instant())
            run.failed++
            return true
        }
        val fingerprint = when (val result = analyzer.call(AnalyzerOp.FINGERPRINT, id)) {
            is AnalyzerResult.Failure -> return failed(run, id, result.code, hasVerdict = row.fingerprint != null)
            is AnalyzerResult.Ok -> result.fingerprint
        }
        if (fingerprint == row.fingerprint) {
            store.confirm(id, clock.instant())
            run.confirmed++
            return true
        }
        if (row.fingerprint != null) store.clearVerdict(id, clock.instant())
        if (!store.takeBudget(clock.instant())) {
            store.defer(id, BUDGET_EXHAUSTED, clock.instant())
            run.deferred++
            return false
        }
        run.analyses++
        return when (val result = analyzer.call(AnalyzerOp.ANALYZE, id)) {
            is AnalyzerResult.Failure -> if (result.code == AnalyzerStatus.INSUFFICIENT) {
                store.recordInsufficient(id, fingerprint, clock.instant())
                run.insufficient++
                true
            } else {
                failed(run, id, result.code, hasVerdict = false)
            }

            is AnalyzerResult.Ok -> {
                val rule = requireNotNull(result.rule)
                val json = jsonMapper.writeValueAsString(rule)
                val rejection = SheetRuleShape.reject(rule, json.length)
                if (rejection == null) {
                    store.recordReady(id, json, result.fingerprint, clock.instant())
                    run.ready++
                } else {
                    logger.warn("Sheet analyzer rule rejected: {}", rejection)
                    store.recordAttempt(id, INVALID_RULE, clock.instant())
                    run.failed++
                }
                true
            }
        }
    }

    /**
     * Busy or unreachable: the sheet waits without an attempt and the run stops. A closed, missing or too large sheet
     * fails for now. Anything else costs an attempt, unless it was the check of a stored verdict, which then stays.
     */
    private fun failed(run: Run, id: String, code: String, hasVerdict: Boolean): Boolean {
        val now = clock.instant()
        when (code) {
            AnalyzerStatus.BUSY, AnalyzerStatus.UNAVAILABLE -> {
                store.defer(id, code, now)
                run.deferred++
                return false
            }

            AnalyzerStatus.CLOSED, AnalyzerStatus.NOT_FOUND, AnalyzerStatus.TOO_LARGE -> store.recordFailed(id, code, now)

            else -> if (hasVerdict) store.restore(id, code, now) else store.recordAttempt(id, code, now)
        }
        run.failed++
        return true
    }

    private class Run(val startedAt: Instant) {
        var ready = 0
        var confirmed = 0
        var insufficient = 0
        var failed = 0
        var deferred = 0
        var analyses = 0
    }

    companion object {
        const val TICK_MILLIS = 60_000L
        const val BUDGET_EXHAUSTED = "budget_exhausted"
        const val NOT_SHARED = "not_shared"
        const val INVALID_RULE = "invalid_rule"
        private val logger = LoggerFactory.getLogger(SheetRuleJob::class.java)
    }
}
