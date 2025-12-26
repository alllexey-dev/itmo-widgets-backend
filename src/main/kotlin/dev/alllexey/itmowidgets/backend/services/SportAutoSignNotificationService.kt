package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.model.SportAutoSignEntity
import dev.alllexey.itmowidgets.backend.model.SportLesson
import dev.alllexey.itmowidgets.backend.repositories.SportAutoSignEntryRepository
import dev.alllexey.itmowidgets.core.model.QueueEntryStatus
import dev.alllexey.itmowidgets.core.model.fcm.impl.SportAutoSignLessonsPayload
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class SportAutoSignNotificationService(
    private val autoSignRepository: SportAutoSignEntryRepository,
    private val sportFreeSignService: SportFreeSignService,
    private val deviceService: DeviceService
) {

    @Transactional
    fun handleNewLessons(
        newMappedLessons: List<SportLesson>,
        apiLessonSource: List<api.myitmo.model.sport.SportLesson>
    ) {
        if (newMappedLessons.isEmpty()) return

        val capacityMap = apiLessonSource.associate { it.id to it.available }

        for (lesson in newMappedLessons) {
            val capacity = capacityMap[lesson.id] ?: 20
            processSingleLesson(lesson, capacity)
        }
    }

    private fun processSingleLesson(lesson: SportLesson, capacity: Long) {
        val expectedPrototypeStart = lesson.start.minusWeeks(2)

        val matchingEntries = autoSignRepository.findMatchingWaitingEntries(
            sectionId = lesson.section.id,
            teacherId = lesson.teacher.isu,
            level = lesson.sectionLevel,
            timeSlotId = lesson.timeSlot.id,
            prototypeStart = expectedPrototypeStart
        )

        if (matchingEntries.isEmpty()) return

        val maxNotifications = (capacity - 1).coerceAtLeast(0)

        val usersToNotify = mutableListOf<SportAutoSignEntity>()
        val usersToMoveToFreeSign = mutableListOf<SportAutoSignEntity>()

        matchingEntries.forEachIndexed { index, entry ->
            if (index < maxNotifications) {
                usersToNotify.add(entry)
            } else {
                usersToMoveToFreeSign.add(entry)
            }
        }

        usersToNotify.forEach { entry ->
            entry.status = QueueEntryStatus.NOTIFIED
            entry.notifiedAt = Instant.now()
            entry.lastNotifiedAt = entry.notifiedAt
            entry.notificationAttempts = 1
            entry.realLesson = lesson

            try {
                deviceService.sendDataMessageToUser(
                    entry.user,
                    SportAutoSignLessonsPayload(listOf(lesson.id))
                )
                logger.info("AutoSign: Notified user ${entry.user.id} for lesson ${lesson.id}")
            } catch (e: Exception) {
                logger.error("Failed to send FCM for auto-sign user ${entry.user.id}", e)
            }
        }
        autoSignRepository.saveAll(usersToNotify)

        usersToMoveToFreeSign.forEach { entry ->
            try {
                sportFreeSignService.addToQueue(entry.user.id, lesson.id, false)
                logger.info("AutoSign: Moved user ${entry.user.id} to FreeSign for lesson ${lesson.id}")
            } catch (e: Exception) {
                logger.warn("Could not move user ${entry.user.id} to FreeSign: ${e.message}")
            }
            autoSignRepository.delete(entry)
        }
    }


    companion object {
        private val logger = LoggerFactory.getLogger(SportAutoSignNotificationService::class.java)
    }
}