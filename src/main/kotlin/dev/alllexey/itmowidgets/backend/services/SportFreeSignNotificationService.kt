package dev.alllexey.itmowidgets.backend.services

import api.myitmo.model.sport.SportSignLimit
import dev.alllexey.itmowidgets.backend.model.SportFreeSignEntity
import dev.alllexey.itmowidgets.backend.model.User
import dev.alllexey.itmowidgets.backend.repositories.SportFreeSignEntryRepository
import dev.alllexey.itmowidgets.backend.repositories.SportLessonRepository
import dev.alllexey.itmowidgets.core.model.QueueEntryStatus
import dev.alllexey.itmowidgets.core.model.fcm.impl.SportFreeSignLessonsPayload
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Service
class SportFreeSignNotificationService(
    private val sportFreeSignEntryRepository: SportFreeSignEntryRepository,
    private val deviceService: DeviceService,
    private val sportLessonRepository: SportLessonRepository
) {

    @Transactional
    fun sendNotificationsForFreeLessons(limits: Map<Long, SportSignLimit>) {

        val availableLessonIds = limits
            .filter { it.value.available > 0 }
            .map { it.key }

        if (availableLessonIds.isEmpty()) {
            return
        }

        val waitingEntries = sportFreeSignEntryRepository.findByLessonIdInAndStatusOrderByCreatedAt(
            availableLessonIds,
            QueueEntryStatus.WAITING
        )

        val waitingListsByLessonId = waitingEntries.groupBy { it.lesson.id }

        val notificationsToSend = mutableMapOf<User, MutableList<Long>>()
        val processedEntries = mutableListOf<SportFreeSignEntity>()
        val lessons = sportLessonRepository.findAllById(availableLessonIds).associateBy { it.id }

        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val nowInstant = Instant.now()

        for (lessonId in availableLessonIds) {
            val waitingList = waitingListsByLessonId[lessonId]

            if (!waitingList.isNullOrEmpty()) {
                val lesson = lessons[lessonId] ?: continue
                for (entry in waitingList) {
                    if (!entry.forceSign && now > lesson.start.minusSeconds(FORCE_SIGN_SECONDS)) continue
                    val nextAt = entry.lastNotifiedAt?.plusSeconds(NOTIFICATION_DEBOUNCE_SECONDS) ?: Instant.EPOCH
                    if (nextAt.isAfter(nowInstant)) continue
                    if (entry.notificationAttempts >= NOTIFICATION_ATTEMPTS) continue
                    notificationsToSend.getOrPut(entry.user) { mutableListOf() }.add(lessonId)
                    processedEntries.add(entry)
                    break
                }
            }
        }

        if (notificationsToSend.isEmpty()) {
            return
        }

        notificationsToSend.forEach { (user, lessonIds) ->
            val data = SportFreeSignLessonsPayload(lessonIds)
            deviceService.sendDataMessageToUser(user, data)
            logger.info("Sending notification to user ${user.id} for free lessons: $lessonIds")
        }

        processedEntries.forEach { entry ->
            entry.status = QueueEntryStatus.NOTIFIED
            if (entry.notifiedAt == null) entry.notifiedAt = Instant.now()
            entry.lastNotifiedAt = Instant.now()
            entry.notificationAttempts++
        }

        sportFreeSignEntryRepository.saveAll(processedEntries)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(SportFreeSignNotificationService::class.java)
        private const val FORCE_SIGN_SECONDS = 60 * 60L
        private const val NOTIFICATION_DEBOUNCE_SECONDS = 15 * 60L
        private const val NOTIFICATION_ATTEMPTS = 10L
    }
}