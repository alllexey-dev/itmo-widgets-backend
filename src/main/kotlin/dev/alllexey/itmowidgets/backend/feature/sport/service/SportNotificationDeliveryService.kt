package dev.alllexey.itmowidgets.backend.feature.sport.service

import dev.alllexey.itmowidgets.backend.feature.push.service.DeviceService
import dev.alllexey.itmowidgets.backend.feature.push.service.PushAlert
import dev.alllexey.itmowidgets.backend.feature.push.service.PushLocKeys
import dev.alllexey.itmowidgets.backend.feature.sport.web.SportLessonDto
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SportNotificationDeliveryService(private val transitions: SportQueueTransitionService, private val devices: DeviceService) {
    /** Best effort after reservation commit. A send already in progress cannot be recalled. */
    fun deliver(intent: SportNotificationIntent) {
        if (!transitions.isIntentCurrent(intent)) return
        devices.sendDataMessageToUser(intent.userId, intent.payload, intent.deadline, alert(intent))
    }

    /**
     * The device books after the push, so Backend cannot know the outcome: it sends a neutral "a place is free" alert
     * that the NSE shows when it cannot book and retitles with its outcome otherwise.
     */
    private fun alert(intent: SportNotificationIntent) = PushAlert(
        titleLocKey = PushLocKeys.SPORT_PLACE_FREE,
        locKey = PushLocKeys.SPORT_LESSON,
        locArgs = listOf(intent.lesson.sectionName, intent.lesson.startText()),
        collapseId = "sport-${intent.entryId}",
        threadId = "sport",
        interruptionLevel = PushAlert.InterruptionLevel.TIME_SENSITIVE,
    )

    private fun SportLessonDto.startText(): String = start.atZoneSameInstant(MOSCOW).format(START_FORMAT)

    companion object {
        private val MOSCOW: ZoneId = ZoneId.of("Europe/Moscow")

        /** Digits only, so the text needs no month names in Backend. */
        private val START_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM HH:mm")
    }
}
