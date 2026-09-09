package dev.alllexey.itmowidgets.backend.services

import api.myitmo.model.ResultResponse
import dev.alllexey.itmowidgets.backend.exceptions.SafeDiagnostics
import dev.alllexey.itmowidgets.backend.repositories.SportAutoSignEntryRepository
import dev.alllexey.itmowidgets.backend.repositories.SportFreeSignEntryRepository
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import retrofit2.Response
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationListener
import org.springframework.context.event.ContextRefreshedEvent
import org.springframework.core.annotation.Order
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
@Order(2)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SportUpdateService(
    private val myItmoService: MyItmoService,
    private val catalog: SportCatalogService,
    private val sportFreeSignEntryRepository: SportFreeSignEntryRepository,
    private val sportFreeSignNotificationService: SportFreeSignNotificationService,
    private val sportAutoSignNotificationService: SportAutoSignNotificationService,
    private val sportAutoSignEntryRepository: SportAutoSignEntryRepository,
    private val transitions: SportQueueTransitionService,
    private val clock: Clock,
) : ApplicationListener<ContextRefreshedEvent> {
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
    fun checkLessonUpdates() = safely("catalog") {
        val from = LocalDate.now(clock)
        val response = myItmoService.myItmo.api.getSportSchedule(from, from.plusDays(21), null, null, null).execute()
        val days = requireResult(response)
        val capacities = catalog.applySnapshot(days.flatMap { it.lessons ?: emptyList() })
        sportAutoSignNotificationService.reconcileUnresolvedForecasts(capacities)
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
        val candidates = sportFreeSignEntryRepository.findExpiredCandidates(OffsetDateTime.now(clock))
        candidates.forEach { candidate -> safely("free expiry") { transitions.expireFreeEntry(candidate) } }
    }

    @Scheduled(cron = "0 0 * * * *", zone = "Europe/Moscow")
    fun cleanupExpiredAutoSignEntries() = safely("auto expiry candidates") {
        val candidates = sportAutoSignEntryRepository.findExpiredCandidates(OffsetDateTime.now(clock).minusWeeks(2))
        candidates.forEach { candidate -> safely("auto expiry") { transitions.expireAutoEntry(candidate) } }
    }

    private fun <T : Any> requireResult(response: Response<ResultResponse<T>>): T {
        check(response.isSuccessful) { "Sport upstream HTTP request failed" }
        val body = checkNotNull(response.body()) { "Sport upstream response missing" }
        check(body.errorCode == 0) { "Sport upstream rejected request" }
        // A valid empty list is different from an error envelope or a missing result.
        return checkNotNull(body.result) { "Sport upstream result missing" }
    }

    // Scheduled exceptions must not reach Spring's default Throwable logger with upstream details.
    private fun safely(operation: String, action: () -> Unit) {
        try {
            action()
        } catch (error: Exception) {
            logger.error("Sport {} failed: {}", operation, SafeDiagnostics.describe(error))
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(SportUpdateService::class.java)
    }
}
