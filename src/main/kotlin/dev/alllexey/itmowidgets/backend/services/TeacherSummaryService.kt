package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.configs.AiSummaryConfig
import dev.alllexey.itmowidgets.backend.exceptions.BusinessRuleException
import dev.alllexey.itmowidgets.backend.exceptions.SafeDiagnostics
import dev.alllexey.itmowidgets.backend.model.ServiceCredential
import dev.alllexey.itmowidgets.backend.model.ServiceCredentialStatus
import dev.alllexey.itmowidgets.backend.model.SummaryRunOutcome
import dev.alllexey.itmowidgets.backend.model.SummaryRunTrigger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationListener
import org.springframework.core.task.TaskExecutor
import org.springframework.core.task.TaskRejectedException
import org.springframework.dao.DataAccessException
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.TransactionException
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Waits between two Gemini requests; tests replace it to count the pauses. */
fun interface SummaryPause {
    fun pause(duration: Duration)
}

private fun sleep(duration: Duration) {
    try {
        Thread.sleep(duration)
    } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        throw interrupted
    }
}

/**
 * Builds AI summaries nightly and on an admin's request. A run plans the input of every teacher, then asks Gemini
 * for teachers whose input changed, most reviewed first, within the day budget. A run holds the `running_since`
 * lease; the backend is a single instance, so a lease left by a crash is dropped at startup. Logs carry counters and
 * rejection codes only: never review texts, prompts, model answers or the key.
 */
@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TeacherSummaryService(
    private val inputs: SummaryInputSource,
    private val prompt: SummaryPrompt,
    private val validator: SummaryValidator,
    private val client: GeminiClient,
    private val store: TeacherSummaryStore,
    private val credentials: ServiceCredentialStore,
    private val config: AiSummaryConfig,
    @Qualifier("aiSummaryExecutor") private val executor: TaskExecutor,
    private val clock: Clock,
    private val pause: SummaryPause = SummaryPause(::sleep),
) : ApplicationListener<ApplicationReadyEvent> {
    override fun onApplicationEvent(event: ApplicationReadyEvent) {
        store.release()
        credentials.initializeFromBootstrap(KEY, config.apiKey)
    }

    @Scheduled(cron = "0 30 5 * * *", zone = "Europe/Moscow")
    fun scheduledRun() {
        if (!config.enabled) return
        try {
            if (!store.claim(SummaryRunTrigger.SCHEDULE, clock.instant())) {
                logger.info("AI summaries skipped: already running")
                return
            }
            execute(SummaryRunTrigger.SCHEDULE)
        } catch (error: Exception) {
            // Spring's default scheduled-task logger includes unsafe exception messages and causes.
            logger.error("AI summaries schedule failed: {}", SafeDiagnostics.describe(error), error)
        }
    }

    /** «Пересчитать всё»: failed teachers get fresh attempts. */
    fun startManual(adminId: UUID) {
        if (!config.enabled) throw BusinessRuleException(DISABLED)
        val now = clock.instant()
        if (!store.claimManual(adminId, now)) throw BusinessRuleException(RUNNING)
        store.resetFailedAttempts(now)
        execute(SummaryRunTrigger.ADMIN)
    }

    /** Called after an admin's request for [isu] is committed; a running run picks the teacher up first. */
    fun requestTeacher(isu: Int) {
        if (!config.enabled) return
        if (!store.claim(SummaryRunTrigger.ADMIN, clock.instant())) {
            logger.info("AI summary of teacher {} requested during a run", isu)
            return
        }
        try {
            execute(SummaryRunTrigger.ADMIN)
        } catch (_: BusinessRuleException) {
            // The executor is busy with a run that picks the teacher up.
        }
    }

    private fun execute(trigger: SummaryRunTrigger) {
        try {
            executor.execute { runClaimed(trigger) }
        } catch (_: TaskRejectedException) {
            store.release()
            throw BusinessRuleException(RUNNING)
        }
    }

    private fun runClaimed(trigger: SummaryRunTrigger) {
        val startedAtNanos = System.nanoTime()
        val run = Run(clock.instant())
        try {
            store.plan(inputs.all(), run.startedAt)
            val key = credentials.value(KEY)
            if (key == null) run.outcome = SummaryRunOutcome.NO_KEY else generate(key, run)
        } catch (error: Exception) {
            run.outcome = SummaryRunOutcome.FAILED
            run.error = category(error)
            logger.error("AI summaries run failed: {}", SafeDiagnostics.describe(error), error)
        } finally {
            try {
                store.finish(run.outcome, run.error, run.generated, run.failed, run.requests, clock.instant())
                val state = store.state()
                logger.info("AI summaries {} trigger={} generated={} failed={} requests={} budget={}/{} durationMs={}",
                    run.outcome, trigger, run.generated, run.failed, run.requests, state.budgetUsed, config.dailyRequestBudget,
                    (System.nanoTime() - startedAtNanos) / 1_000_000)
            } catch (recordError: Exception) {
                logger.error("AI summaries result record unavailable: {}", SafeDiagnostics.describe(recordError), recordError)
            }
            try {
                store.release()
            } catch (releaseError: Exception) {
                logger.error("AI summaries lease release failed: {}", SafeDiagnostics.describe(releaseError), releaseError)
            }
        }
    }

    /** Sets the outcome; a Gemini failure stops the run without counting an attempt for the teacher. */
    private fun generate(key: String, run: Run) {
        while (true) {
            val row = store.next(run.startedAt) ?: break
            val input = inputs.forTeacher(row.teacherIsu)
            if (input == null || input.hash != row.inputHash || input.count != row.inputCount) {
                store.planTeacher(row.teacherIsu, input, clock.instant())
                continue
            }
            if (!store.takeBudget(clock.instant())) {
                run.outcome = SummaryRunOutcome.BUDGET_EXHAUSTED
                return
            }
            if (run.requests > 0) pause.pause(config.requestDelay)
            store.markAttempt(row.teacherIsu, clock.instant())
            run.requests++
            val response = try {
                client.generate(key, prompt.request(input))
            } catch (failure: GeminiFailure) {
                stop(failure, run)
                return
            }
            when (val verdict = validator.validate(response, input.count)) {
                is SummaryVerdict.Valid -> {
                    if (store.recordSuccess(row.teacherIsu, verdict, input, config.model, clock.instant())) run.generated++
                    if (!run.keyUsed) {
                        credentials.recordUse(KEY)
                        run.keyUsed = true
                    }
                }
                is SummaryVerdict.Rejected -> {
                    store.recordRejected(row.teacherIsu, verdict.code, clock.instant())
                    run.failed++
                    logger.warn("AI summary of teacher {} rejected: {}", row.teacherIsu, verdict.code)
                }
            }
        }
        run.outcome = SummaryRunOutcome.COMPLETED
    }

    private fun stop(failure: GeminiFailure, run: Run) {
        run.outcome = when (failure.category) {
            GeminiErrorCategory.RATE_LIMITED -> SummaryRunOutcome.RATE_LIMITED
            GeminiErrorCategory.AUTH -> SummaryRunOutcome.AUTH_FAILED
            else -> SummaryRunOutcome.FAILED
        }
        run.error = failure.summary()
        if (failure.category == GeminiErrorCategory.AUTH) {
            credentials.recordFailure(KEY, ServiceCredentialStatus.FAILED, failure.summary())
        }
        logger.warn("Gemini {} {}", run.outcome, failure.summary())
    }

    private fun category(error: Exception): String =
        if (generateSequence<Throwable>(error) { it.cause }.take(10).any { it is DataAccessException || it is TransactionException }) {
            "PERSISTENCE"
        } else "INTERNAL"

    private class Run(val startedAt: Instant) {
        var outcome = SummaryRunOutcome.FAILED
        var error: String? = null
        var generated = 0
        var failed = 0
        var requests = 0
        var keyUsed = false
    }

    companion object {
        private val KEY = ServiceCredential.GEMINI_API_KEY
        const val DISABLED = "AI summaries are disabled"
        const val RUNNING = "AI summaries are already running"
        private val logger = LoggerFactory.getLogger(TeacherSummaryService::class.java)
    }
}
