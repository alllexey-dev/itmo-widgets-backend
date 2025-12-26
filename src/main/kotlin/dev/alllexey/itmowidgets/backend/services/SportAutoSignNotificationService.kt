package dev.alllexey.itmowidgets.backend.services

import api.myitmo.model.sport.SportSignLimit
import dev.alllexey.itmowidgets.backend.model.SportAutoSignEntity
import dev.alllexey.itmowidgets.backend.model.SportLesson
import dev.alllexey.itmowidgets.backend.repositories.SportAutoSignEntryRepository
import dev.alllexey.itmowidgets.backend.repositories.SportLessonRepository
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
    private val deviceService: DeviceService,
    private val sportLessonRepository: SportLessonRepository
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

        notifyUsers(usersToNotify, lesson)

        usersToMoveToFreeSign.forEach { entry ->
            try {
                sportFreeSignService.addToQueue(entry.user.id, lesson.id, false)
                logger.info("Moved user ${entry.user.id} (entry: ${entry.id}) to FreeSign for lesson ${lesson.id}")
            } catch (e: Exception) {
                logger.warn("Could not move user ${entry.user.id} (entry: ${entry.id}) to FreeSign: ${e.message}")
            }
            autoSignRepository.delete(entry)
        }
    }

    @Transactional
    fun sendNotificationsForAvailableLessons(limits: Map<Long, SportSignLimit>) {
        val availableLessonIds = limits
            .filter { it.value.available > 0 }
            .map { it.key }

        if (availableLessonIds.isEmpty()) return

        val lessons = sportLessonRepository.findAllById(availableLessonIds)
        val nowInstant = Instant.now()

        for (lesson in lessons) {
            val expectedPrototypeStart = lesson.start.minusWeeks(2)

            val matchingEntries = autoSignRepository.findMatchingWaitingEntries(
                sectionId = lesson.section.id,
                teacherId = lesson.teacher.isu,
                level = lesson.sectionLevel,
                timeSlotId = lesson.timeSlot.id,
                prototypeStart = expectedPrototypeStart
            )

            if (matchingEntries.isEmpty()) continue

            val usersToNotify = mutableListOf<SportAutoSignEntity>()

            var slotsRemaining = limits[lesson.id]?.available ?: 0

            for (entry in matchingEntries) {
                if (slotsRemaining <= 0) break

                val nextAt = entry.lastNotifiedAt?.plusSeconds(NOTIFICATION_DEBOUNCE_SECONDS) ?: Instant.EPOCH
                if (nextAt.isAfter(nowInstant)) continue
                if (entry.notificationAttempts >= NOTIFICATION_ATTEMPTS) continue

                usersToNotify.add(entry)
                slotsRemaining--
            }

            if (usersToNotify.isNotEmpty()) {
                notifyUsers(usersToNotify, lesson)
            }
        }
    }

    private fun notifyUsers(entries: List<SportAutoSignEntity>, lesson: SportLesson) {
        entries.forEach { entry ->
            entry.status = QueueEntryStatus.NOTIFIED
            if (entry.notifiedAt == null) entry.notifiedAt = Instant.now()
            entry.lastNotifiedAt = Instant.now()
            entry.notificationAttempts++
            entry.realLesson = lesson

            try {
                deviceService.sendDataMessageToUser(
                    entry.user,
                    SportAutoSignLessonsPayload(listOf(lesson.id))
                )
                logger.info("Notified user ${entry.user.id} (entry: ${entry.id}) for lesson ${lesson.id}")
            } catch (e: Exception) {
                logger.error("Failed to send FCM for auto-sign user ${entry.user.id} (entry: ${entry.id})", e)
            }
        }
        autoSignRepository.saveAll(entries)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(SportAutoSignNotificationService::class.java)
        private const val NOTIFICATION_DEBOUNCE_SECONDS = 15 * 60L
        private const val NOTIFICATION_ATTEMPTS = 10
    }
}