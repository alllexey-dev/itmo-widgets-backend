package dev.alllexey.itmowidgets.backend.feature.sport.service

import dev.alllexey.itmowidgets.backend.feature.credentials.service.MyItmoFailureKind
import dev.alllexey.itmowidgets.backend.feature.credentials.service.MyItmoGateway
import dev.alllexey.itmowidgets.backend.feature.credentials.service.MyItmoResult
import dev.alllexey.itmowidgets.backend.feature.credentials.service.MyItmoService
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportQueueRules
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportUpdateErrorCategory
import dev.alllexey.itmowidgets.backend.feature.sport.persistence.SportAutoSignEntryRepository
import dev.alllexey.itmowidgets.backend.feature.sport.persistence.SportFreeSignEntryRepository
import dev.alllexey.itmowidgets.backend.platform.error.SafeDiagnostics
import jakarta.persistence.PersistenceException
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationListener
import org.springframework.context.event.ContextRefreshedEvent
import org.springframework.core.annotation.Order
import org.springframework.dao.DataAccessException
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.TransactionException
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.SQLException
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * Single instance by design: no scheduled method here takes a distributed lock, so a second
 * replica would run every cron independently. Owner row locks keep each transition correct on
 * its own, but nothing stops two processes from reserving two attempts for one entry.
 * See the single-instance section of docs/sport-automation.md before scaling this service.
 */
@Service
@Order(2)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SportUpdateService(
    private val myItmo: MyItmoGateway,
    private val myItmoService: MyItmoService,
    private val catalog: SportCatalogService,
    private val updateLogs: SportUpdateLogService,
    private val sportFreeSignEntryRepository: SportFreeSignEntryRepository,
    private val sportFreeSignNotificationService: SportFreeSignNotificationService,
    private val sportAutoSignNotificationService: SportAutoSignNotificationService,
    private val sportAutoSignEntryRepository: SportAutoSignEntryRepository,
    private val transitions: SportQueueTransitionService,
    private val clock: Clock,
) : ApplicationListener<ContextRefreshedEvent> {
    // Alternation phase is process memory, not shared state: a second replica would keep its own.
    private var processAutoSignNext = false

    override fun onApplicationEvent(event: ContextRefreshedEvent) {
        checkOtherUpdates()
        checkLessonUpdates()
    }

    fun updateTimeSlots() {
        catalog.applyTimeSlots(myItmo.sportTimeSlots().orThrow())
    }

    fun updateFromFilters() {
        catalog.applyFilters(myItmo.sportFilters().orThrow())
    }

    @Scheduled(cron = "0 0 * * * *", zone = "Europe/Moscow")
    fun checkOtherUpdates() = safely("dictionaries") {
        updateTimeSlots()
        updateFromFilters()
    }

    @Scheduled(cron = "0 */10 * * * *", zone = "Europe/Moscow")
    fun checkLessonUpdates() {
        val startedAtNanos = System.nanoTime()
        var receivedLessons = 0
        val result = try {
            val from = LocalDate.now(clock)
            val incoming = myItmo.sportSchedule(from, from.plusDays(21)).orThrow()
            receivedLessons = incoming.size
            catalog.applySnapshot(incoming, startedAtNanos)
        } catch (error: Exception) {
            val category = failureCategory(error)
            logger.error("Sport catalog failed category={}: {}", category, SafeDiagnostics.describe(error), error)
            try {
                updateLogs.recordFailure(elapsedSportUpdateMillis(startedAtNanos), receivedLessons, category)
            } catch (logError: Exception) {
                logger.error("Sport failure log unavailable category={}: {}", category, SafeDiagnostics.describe(logError), logError)
            }
            if (category == SportUpdateErrorCategory.AUTH) {
                try {
                    myItmoService.recordAuthFailure("sport")
                } catch (credentialError: Exception) {
                    logger.error("Sport credential status unavailable: {}", SafeDiagnostics.describe(credentialError), credentialError)
                }
            }
            return
        }
        // Queue failures cannot retroactively turn a committed catalog refresh into FAILED.
        safely("forecast reconciliation") {
            sportAutoSignNotificationService.reconcileUnresolvedForecasts(result.capacities)
        }
    }

    @Scheduled(cron = "30 * * * * *", zone = "Europe/Moscow")
    fun processSportLimits() = safely("limits") {
        val limits = myItmo.sportSignLimits().orThrow()
        sportAutoSignNotificationService.reconcileUnresolvedForecasts(limits.mapValues { it.value.available.toLong() })
        if (processAutoSignNext) {
            sportAutoSignNotificationService.sendNotificationsForAvailableLessons(limits)
        } else {
            sportFreeSignNotificationService.sendNotificationsForFreeLessons(limits)
        }
        processAutoSignNext = !processAutoSignNext
    }

    @Scheduled(cron = "0 0 * * * *", zone = "Europe/Moscow")
    fun cleanupExpiredFreeSignEntries() = safely("free expiry candidates") {
        val horizon = SportQueueRules.freeExpiryHorizon(OffsetDateTime.now(clock))
        val candidates = sportFreeSignEntryRepository.findExpiredCandidates(horizon)
        candidates.forEach { candidate -> safely("free expiry") { transitions.expireFreeEntry(candidate) } }
    }

    @Scheduled(cron = "0 0 * * * *", zone = "Europe/Moscow")
    fun cleanupExpiredAutoSignEntries() = safely("auto expiry candidates") {
        val candidates = sportAutoSignEntryRepository.findExpiredCandidates(OffsetDateTime.now(clock))
        candidates.forEach { candidate -> safely("auto expiry") { transitions.expireAutoEntry(candidate) } }
    }

    private fun <T> MyItmoResult<T>.orThrow(): T = when (this) {
        is MyItmoResult.Success -> value
        is MyItmoResult.Failure -> throw SportUpstreamFailure(category(kind), cause)
    }

    private fun category(kind: MyItmoFailureKind): SportUpdateErrorCategory = when (kind) {
        MyItmoFailureKind.AUTH -> SportUpdateErrorCategory.AUTH
        MyItmoFailureKind.HTTP -> SportUpdateErrorCategory.HTTP
        MyItmoFailureKind.NETWORK -> SportUpdateErrorCategory.NETWORK
        MyItmoFailureKind.MAPPING -> SportUpdateErrorCategory.MAPPING
    }

    /** MyITMO's failures arrive classified by the gateway; the rest is Backend's own. */
    private fun failureCategory(error: Exception): SportUpdateErrorCategory {
        val causes = generateSequence<Throwable>(error) { it.cause }.take(10).toList()
        return when {
            causes.any { it is DataAccessException || it is SQLException || it is PersistenceException || it is TransactionException } ->
                SportUpdateErrorCategory.PERSISTENCE

            else -> causes.filterIsInstance<SportUpstreamFailure>().firstOrNull()?.category ?: SportUpdateErrorCategory.INTERNAL
        }
    }

    private class SportUpstreamFailure(val category: SportUpdateErrorCategory, cause: Throwable?) :
        RuntimeException("Sport upstream request failed", cause)

    // Scheduled exceptions must not reach Spring's default Throwable logger with upstream details.
    private fun safely(operation: String, action: () -> Unit) {
        try {
            action()
        } catch (error: Exception) {
            logger.error("Sport {} failed: {}", operation, SafeDiagnostics.describe(error), error)
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(SportUpdateService::class.java)
    }
}
