package dev.alllexey.itmowidgets.backend.feature.reviews.service

import dev.alllexey.itmowidgets.backend.feature.reviews.model.ReviewSyncOutcome
import dev.alllexey.itmowidgets.backend.platform.error.BusinessRuleException
import dev.alllexey.itmowidgets.backend.platform.error.SafeDiagnostics
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
import java.util.UUID

/**
 * Copies the Reviews project into the database, daily and on an admin's request. A run holds the
 * `running_since` lease; the backend is a single instance, so a lease left by a crash is dropped at startup.
 */
@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ReviewsSyncService(
    private val client: ReviewsApiClient,
    private val store: ReviewsSyncStore,
    private val config: ReviewsSyncConfig,
    @Qualifier("reviewsSyncExecutor") private val executor: TaskExecutor,
    private val clock: Clock,
) : ApplicationListener<ApplicationReadyEvent> {
    override fun onApplicationEvent(event: ApplicationReadyEvent) {
        store.release()
    }

    @Scheduled(cron = "0 0 5 * * *", zone = "Europe/Moscow")
    fun scheduledRun() {
        if (!config.enabled) return
        try {
            if (!store.claim(clock.instant())) {
                logger.info("Reviews sync skipped: already running")
                return
            }
            execute()
        } catch (error: Exception) {
            // Spring's default scheduled-task logger includes unsafe exception messages and causes.
            logger.error("Reviews sync schedule failed: {}", SafeDiagnostics.describe(error), error)
        }
    }

    fun startManual(adminId: UUID) {
        if (!config.enabled) throw BusinessRuleException("Reviews sync is disabled")
        if (!store.claimManual(adminId, clock.instant())) throw BusinessRuleException("Reviews sync is already running")
        execute()
    }

    private fun execute() {
        try {
            executor.execute(::runClaimed)
        } catch (_: TaskRejectedException) {
            store.release()
            throw BusinessRuleException("Reviews sync is already running")
        }
    }

    private fun runClaimed() {
        val startedAtNanos = System.nanoTime()
        try {
            when (val registry = client.registry(store.state().etag)) {
                RegistryResult.NotModified -> {
                    store.recordUnchanged(clock.instant())
                    val state = store.state()
                    logOutcome(
                        ReviewSyncOutcome.UNCHANGED,
                        ReviewsSyncResult(0, 0, 0, state.teachersTotal, state.reviewsTotal),
                        startedAtNanos,
                    )
                }

                is RegistryResult.Changed -> {
                    val teachers = registry.teacherIds.mapIndexedNotNull { index, id ->
                        if (index > 0) Thread.sleep(config.requestDelay)
                        client.teacher(id)
                    }
                    val result = store.applySnapshot(ReviewsSnapshot(registry.etag, teachers), clock.instant())
                    logOutcome(ReviewSyncOutcome.UPDATED, result, startedAtNanos)
                }
            }
        } catch (error: Exception) {
            val failure = error as? ReviewsSyncFailure ?: ReviewsSyncFailure(category(error), "")
            logger.error(
                "Reviews sync FAILED {} durationMs={}: {}",
                failure.summary(),
                elapsedMillis(startedAtNanos),
                SafeDiagnostics.describe(error),
                error,
            )
            try {
                store.recordFailure(clock.instant(), failure.summary())
            } catch (recordError: Exception) {
                logger.error("Reviews sync failure record unavailable: {}", SafeDiagnostics.describe(recordError), recordError)
            }
        } finally {
            try {
                store.release()
            } catch (releaseError: Exception) {
                logger.error("Reviews sync lease release failed: {}", SafeDiagnostics.describe(releaseError), releaseError)
            }
        }
    }

    private fun logOutcome(outcome: ReviewSyncOutcome, result: ReviewsSyncResult, startedAtNanos: Long) {
        logger.info(
            "Reviews sync {} teachers={} reviews={} added={} updated={} removed={} durationMs={}",
            outcome,
            result.teachers,
            result.reviews,
            result.added,
            result.updated,
            result.removed,
            elapsedMillis(startedAtNanos),
        )
    }

    private fun category(error: Exception): ReviewSyncErrorCategory =
        if (generateSequence<Throwable>(error) { it.cause }.take(10).any { it is DataAccessException || it is TransactionException }) {
            ReviewSyncErrorCategory.PERSISTENCE
        } else {
            ReviewSyncErrorCategory.INTERNAL
        }

    private fun elapsedMillis(startedAtNanos: Long): Long = (System.nanoTime() - startedAtNanos) / 1_000_000

    companion object {
        private val logger = LoggerFactory.getLogger(ReviewsSyncService::class.java)
    }
}
