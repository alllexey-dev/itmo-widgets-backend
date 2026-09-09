package dev.alllexey.itmowidgets.backend.services

import api.myitmo.model.sport.SportSignLimit
import dev.alllexey.itmowidgets.backend.exceptions.SafeDiagnostics
import dev.alllexey.itmowidgets.backend.repositories.SportAutoSignEntryRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SportAutoSignNotificationService(
    private val autoSignRepository: SportAutoSignEntryRepository,
    private val transitions: SportQueueTransitionService,
    private val transfers: SportAutoSignTransferService,
    private val delivery: SportNotificationDeliveryService,
) {
    /** Revisit unresolved forecasts for known lessons too, including zero-capacity lessons. */
    fun reconcileUnresolvedForecasts(capacities: Map<Long, Long>) {
        for ((lessonId, capacity) in capacities.toSortedMap()) {
            // Clamp before subtraction so malformed negative capacities cannot overflow.
            var slotsRemaining = capacity.coerceAtLeast(1L) - 1L
            for (candidate in autoSignRepository.findUnresolvedCandidates(lessonId)) {
                try {
                    if (slotsRemaining > 0) {
                        val intent = transitions.prepareAutoNotification(candidate, lessonId, bindUnresolved = true)
                            ?: continue
                        slotsRemaining--
                        delivery.deliver(intent)
                    } else {
                        transfers.transferEntry(candidate, lessonId)
                    }
                } catch (error: Exception) {
                    logger.warn("Failed to reconcile auto entry {} for lesson {}: {}",
                        candidate.entryId, lessonId, SafeDiagnostics.describe(error))
                }
            }
        }
    }

    fun sendNotificationsForAvailableLessons(limits: Map<Long, SportSignLimit>) {
        for ((lessonId, limit) in limits.toSortedMap()) {
            var slotsRemaining = limit.available.toLong()
            if (slotsRemaining <= 0) continue
            for (candidate in autoSignRepository.findBoundNotificationCandidates(lessonId)) {
                if (slotsRemaining <= 0) break
                try {
                    val intent = transitions.prepareAutoNotification(candidate, lessonId, bindUnresolved = false)
                        ?: continue
                    slotsRemaining--
                    delivery.deliver(intent)
                } catch (error: Exception) {
                    logger.warn("Failed to process auto notification entry {} for lesson {}: {}",
                        candidate.entryId, lessonId, SafeDiagnostics.describe(error))
                }
            }
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(SportAutoSignNotificationService::class.java)
    }
}
