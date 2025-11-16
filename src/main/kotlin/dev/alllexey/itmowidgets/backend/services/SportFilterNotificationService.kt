package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.model.SportFilter
import dev.alllexey.itmowidgets.backend.model.SportLesson
import dev.alllexey.itmowidgets.backend.model.User
import dev.alllexey.itmowidgets.backend.repositories.SportFilterRepository
import dev.alllexey.itmowidgets.core.model.fcm.impl.SportNewLessonsPayload
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class SportFilterNotificationService(
    private val sportFilterRepository: SportFilterRepository,
    private val deviceService: DeviceService
) {

    fun sendNotificationsForNewLessons(newLessons: List<SportLesson>) {
        val allFilters = sportFilterRepository.findAllWithDetails()
        if (allFilters.isEmpty()) {
            logger.info("No notification filters found, skipping notification step.")
            return
        }

        val notificationsToSend = mutableMapOf<User, MutableList<SportLesson>>()

        allFilters.forEach { filter ->
            val matchingLessons = newLessons.filter { lesson -> lesson.isMatch(filter) }
            if (matchingLessons.isNotEmpty()) {
                notificationsToSend.getOrPut(filter.user) { mutableListOf() }.addAll(matchingLessons)
            }
        }

        logger.info("Found {} users to notify about new sport lessons.", notificationsToSend.size)

        notificationsToSend.forEach { (user, lessons) ->
            val uniqueLessons = lessons.distinct()
            val lessonIds = uniqueLessons.map { it.id }

            val data = SportNewLessonsPayload(lessonIds)
            deviceService.sendDataMessageToUser(user, data)
        }
    }

    private fun SportLesson.isMatch(filter: SportFilter): Boolean {
        val sectionMatch = filter.sections.isEmpty() || this.section in filter.sections
        val buildingMatch = filter.buildings.isEmpty() || this.building in filter.buildings
        val teacherMatch = filter.teachers.isEmpty() || this.teacher in filter.teachers
        val timeSlotMatch = filter.timeSlots.isEmpty() || this.timeSlot in filter.timeSlots

        return sectionMatch && buildingMatch && teacherMatch && timeSlotMatch
    }

    companion object {
        private val logger = LoggerFactory.getLogger(SportFilterNotificationService::class.java)
    }
}