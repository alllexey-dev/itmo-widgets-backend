package dev.alllexey.itmowidgets.backend.services

import api.myitmo.model.sport.SportSignLimit
import dev.alllexey.itmowidgets.backend.exceptions.SafeDiagnostics
import dev.alllexey.itmowidgets.backend.repositories.SportFreeSignEntryRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SportFreeSignNotificationService(
    private val freeSignRepository: SportFreeSignEntryRepository,
    private val transitions: SportQueueTransitionService,
    private val delivery: SportNotificationDeliveryService,
) {
    fun sendNotificationsForFreeLessons(limits: Map<Long, SportSignLimit>) {
        for ((lessonId, limit) in limits.toSortedMap()) {
            if (limit.available <= 0) continue
            for (candidate in freeSignRepository.findNotificationCandidates(lessonId)) {
                val intent = try {
                    transitions.prepareFreeNotification(candidate, lessonId)
                } catch (error: Exception) {
                    logger.warn("Failed to prepare free notification entry {} for lesson {}: {}",
                        candidate.entryId, lessonId, SafeDiagnostics.describe(error), error)
                    continue
                } ?: continue
                try {
                    delivery.deliver(intent)
                } catch (error: Exception) {
                    logger.warn("Failed to process free notification entry {} for lesson {}: {}",
                        candidate.entryId, lessonId, SafeDiagnostics.describe(error), error)
                }
                // A committed reservation spends this tick's one opportunity even if sending fails.
                break
            }
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(SportFreeSignNotificationService::class.java)
    }
}
