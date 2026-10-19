package dev.alllexey.itmowidgets.backend.feature.sport.service

import dev.alllexey.itmowidgets.backend.feature.push.model.PushCategory
import dev.alllexey.itmowidgets.backend.feature.push.service.DeviceService
import dev.alllexey.itmowidgets.backend.feature.push.service.PushAlert
import dev.alllexey.itmowidgets.backend.feature.push.service.PushLocKeys
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportLesson.Companion.toDto
import dev.alllexey.itmowidgets.backend.feature.sport.persistence.SportLessonRepository
import dev.alllexey.itmowidgets.backend.feature.sport.web.SportAgentBookedPayload
import dev.alllexey.itmowidgets.backend.feature.sport.web.SportLessonDto
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Tells the owner's phones that their self-hosted agent booked a lesson; best effort after the satisfaction commits. */
@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SportAgentBookedNotificationService(
    private val lessons: SportLessonRepository,
    private val devices: DeviceService,
    transactions: PlatformTransactionManager,
) {
    private val readOnly = TransactionTemplate(transactions).apply { isReadOnly = true }

    fun notify(userId: UUID, lessonId: Long, agentName: String) {
        try {
            val lesson = readOnly.execute { lessons.findById(lessonId).orElse(null)?.toDto() } ?: return
            val payload = SportAgentBookedPayload(listOf(lesson), agentName)
            devices.sendTypedMessageToUser(
                userId,
                SportAgentBookedPayload.TYPE,
                payload,
                lesson.end.toInstant(),
                alert(lesson),
                PushCategory.SPORT,
            )
        } catch (error: Exception) {
            logger.warn("Agent booking notification failed: {}", error.javaClass.simpleName)
        }
    }

    private fun alert(lesson: SportLessonDto) = PushAlert(
        titleLocKey = PushLocKeys.SPORT_SUCCESS,
        locKey = PushLocKeys.SPORT_LESSON,
        locArgs = listOf(lesson.sectionName, lesson.start.atZoneSameInstant(MOSCOW).format(START_FORMAT)),
        collapseId = "sport-booked-${lesson.id}",
        threadId = "sport",
        interruptionLevel = PushAlert.InterruptionLevel.ACTIVE,
    )

    companion object {
        private val MOSCOW: ZoneId = ZoneId.of("Europe/Moscow")
        private val START_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM HH:mm")
        private val logger = LoggerFactory.getLogger(SportAgentBookedNotificationService::class.java)
    }
}
