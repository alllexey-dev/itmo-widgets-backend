package dev.alllexey.itmowidgets.backend.services

import api.myitmo.model.sport.SportSignLimit
import dev.alllexey.itmowidgets.backend.model.FreeSignEntryStatus
import dev.alllexey.itmowidgets.backend.model.SportFreeSignEntity
import dev.alllexey.itmowidgets.backend.model.User
import dev.alllexey.itmowidgets.backend.repositories.SportFreeSignEntryRepository
import dev.alllexey.itmowidgets.core.model.fcm.impl.SportFreeSignLessonsPayload
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class SportFreeSignNotificationService(
    private val sportFreeSignEntryRepository: SportFreeSignEntryRepository,
    private val deviceService: DeviceService
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
            FreeSignEntryStatus.WAITING
        )

        val waitingListsByLessonId = waitingEntries.groupBy { it.lesson.id }

        val notificationsToSend = mutableMapOf<User, MutableList<Long>>()
        val processedEntries = mutableListOf<SportFreeSignEntity>()

        for (lessonId in availableLessonIds) {
            val waitingList = waitingListsByLessonId[lessonId]

            if (!waitingList.isNullOrEmpty()) {
                val topEntry = waitingList.first()
                notificationsToSend.getOrPut(topEntry.user) { mutableListOf() }.add(lessonId)
                processedEntries.add(topEntry)
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
            entry.status = FreeSignEntryStatus.SATISFIED
        }

        sportFreeSignEntryRepository.saveAll(processedEntries)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(SportFreeSignNotificationService::class.java)
    }
}