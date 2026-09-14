package dev.alllexey.itmowidgets.backend.services

import api.myitmo.model.ResultResponse
import api.myitmo.utils.TokenRefreshException
import com.google.gson.JsonParseException
import dev.alllexey.itmowidgets.backend.exceptions.SafeDiagnostics
import dev.alllexey.itmowidgets.backend.model.SportQueueRules
import dev.alllexey.itmowidgets.backend.model.SportUpdateErrorCategory
import dev.alllexey.itmowidgets.backend.repositories.SportAutoSignEntryRepository
import dev.alllexey.itmowidgets.backend.repositories.SportFreeSignEntryRepository
import jakarta.persistence.PersistenceException
import java.io.IOException
import java.sql.SQLException
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
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
import retrofit2.Response

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
        val response = myItmoService.myItmo.api.sportTimeSlots.execute()
        catalog.applyTimeSlots(requireResult(response))
    }

    fun updateFromFilters() {
        val response = myItmoService.myItmo.api.sportFilters.execute()
        catalog.applyFilters(requireResult(response))
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
            val response = myItmoService.myItmo.api.getSportSchedule(from, from.plusDays(21), null, null, null).execute()
            val incoming = requireResult(response).flatMap { it.lessons ?: emptyList() }
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
            return
        }
        // Queue failures cannot retroactively turn a committed catalog refresh into FAILED.
        safely("forecast reconciliation") {
            sportAutoSignNotificationService.reconcileUnresolvedForecasts(result.capacities)
        }
    }

    @Scheduled(cron = "30 * * * * *", zone = "Europe/Moscow")
    fun processSportLimits() = safely("limits") {
        val response = myItmoService.myItmo.api.sportSignLimits.execute()
        val limits = requireResult(response)
        val map = limits.flatMap { it.value.entries }.associate { it.key to it.value }
        sportAutoSignNotificationService.reconcileUnresolvedForecasts(map.mapValues { it.value.available.toLong() })
        if (processAutoSignNext) {
            sportAutoSignNotificationService.sendNotificationsForAvailableLessons(map)
        } else {
            sportFreeSignNotificationService.sendNotificationsForFreeLessons(map)
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

    private fun <T : Any> requireResult(response: Response<ResultResponse<T>>): T {
        if (!response.isSuccessful) {
            throw SportUpstreamFailure(if (response.code() == 401 || response.code() == 403) {
                SportUpdateErrorCategory.AUTH
            } else SportUpdateErrorCategory.HTTP)
        }
        val body = response.body() ?: throw SportUpstreamFailure(SportUpdateErrorCategory.HTTP)
        if (body.errorCode != 0) throw SportUpstreamFailure(SportUpdateErrorCategory.HTTP)
        // A valid empty list is different from an error envelope or a missing result.
        return body.result ?: throw SportUpstreamFailure(SportUpdateErrorCategory.HTTP)
    }

    private fun failureCategory(error: Exception): SportUpdateErrorCategory {
        val causes = generateSequence<Throwable>(error) { it.cause }.take(10).toList()
        return when {
            causes.any { it is DataAccessException || it is SQLException || it is PersistenceException || it is TransactionException } ->
                SportUpdateErrorCategory.PERSISTENCE
            causes.any { it is TokenRefreshException } -> SportUpdateErrorCategory.AUTH
            causes.any { it is SportUpstreamFailure } -> causes.filterIsInstance<SportUpstreamFailure>().first().category
            causes.any { it is IOException } -> SportUpdateErrorCategory.NETWORK
            causes.any { it is JsonParseException } -> SportUpdateErrorCategory.MAPPING
            else -> SportUpdateErrorCategory.INTERNAL
        }
    }

    private class SportUpstreamFailure(val category: SportUpdateErrorCategory) : RuntimeException("Sport upstream request failed")

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
