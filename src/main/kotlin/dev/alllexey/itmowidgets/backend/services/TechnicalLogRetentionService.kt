package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.configs.RetentionConfig
import dev.alllexey.itmowidgets.backend.exceptions.SafeDiagnostics
import dev.alllexey.itmowidgets.backend.repositories.SportUpdateLogRepository
import java.time.Clock
import java.time.temporal.ChronoUnit
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TechnicalLogRetentionService(
    private val logs: SportUpdateLogRepository,
    private val config: RetentionConfig,
    private val clock: Clock,
) {
    /** Returns committed deletions; each bounded batch commits independently of the daily run. */
    @Scheduled(cron = "0 15 4 * * *", zone = "Europe/Moscow")
    fun cleanupSportUpdateLogs(): Long {
        var deletedTotal = 0L
        try {
            val cutoff = clock.instant().minus(config.sportUpdateLogDays.toLong(), ChronoUnit.DAYS)
            repeat(MAX_BATCHES_PER_RUN) {
                val deleted = logs.deleteBatchBefore(cutoff, config.batchSize)
                deletedTotal += deleted
                if (deleted < config.batchSize) return deletedTotal
            }
        } catch (error: Exception) {
            // Spring's default scheduled-task logger includes unsafe exception messages and causes.
            logger.error("Sport update log retention failed: {}", SafeDiagnostics.describe(error), error)
        }
        return deletedTotal
    }

    companion object {
        private const val MAX_BATCHES_PER_RUN = 100
        private val logger = LoggerFactory.getLogger(TechnicalLogRetentionService::class.java)
    }
}
