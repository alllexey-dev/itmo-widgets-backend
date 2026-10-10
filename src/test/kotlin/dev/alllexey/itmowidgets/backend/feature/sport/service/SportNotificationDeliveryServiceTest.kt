package dev.alllexey.itmowidgets.backend.feature.sport.service

import dev.alllexey.itmowidgets.backend.feature.push.service.DeviceService
import dev.alllexey.itmowidgets.backend.feature.push.service.PushAlert
import dev.alllexey.itmowidgets.backend.feature.push.web.FcmPayload
import dev.alllexey.itmowidgets.backend.feature.push.web.SportAutoSignLessonsPayload
import dev.alllexey.itmowidgets.backend.feature.push.web.SportFreeSignLessonsPayload
import dev.alllexey.itmowidgets.backend.feature.sport.web.SportLessonDto
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

class SportNotificationDeliveryServiceTest {
    private val transitions = mock(SportQueueTransitionService::class.java)
    private val devices = mock(DeviceService::class.java)
    private val service = SportNotificationDeliveryService(transitions, devices)
    private val deadline = Instant.parse("2026-10-07T07:00:00Z")

    @ParameterizedTest
    @EnumSource(SportQueueKind::class)
    fun `the iOS alert is the neutral free place with the section and the Moscow start time`(kind: SportQueueKind) {
        // 07:00 UTC is 10:00 in Moscow; the lesson's own offset must not leak into the text.
        val lesson = lesson(OffsetDateTime.parse("2026-10-07T07:00:00Z"))
        val payload = if (kind ==
            SportQueueKind.AUTO
        ) {
            SportAutoSignLessonsPayload(listOf(lesson))
        } else {
            SportFreeSignLessonsPayload(listOf(lesson))
        }
        val intent = intent(kind, payload, lesson)

        service.deliver(intent)

        val alert = PushAlert(
            "notification_sport_place_free",
            "notification_sport_lesson",
            listOf("Плавание", "07.10 10:00"),
            "sport-42",
            "sport",
            PushAlert.InterruptionLevel.TIME_SENSITIVE,
        )
        verify(devices).sendDataMessageToUser(intent.userId, payload as FcmPayload, deadline, alert)
    }

    private fun intent(kind: SportQueueKind, payload: FcmPayload, lesson: SportLessonDto): SportNotificationIntent {
        val intent = SportNotificationIntent(kind, 42, UUID.randomUUID(), 9_000_000_001, 1, payload, deadline, lesson)
        `when`(transitions.isIntentCurrent(intent)).thenReturn(true)
        return intent
    }

    private fun lesson(start: OffsetDateTime) = SportLessonDto(
        id = 9_000_000_001,
        sectionId = 41,
        sectionName = "Плавание",
        sectionLevel = 2,
        level = 4,
        typeId = 8,
        buildingId = 13,
        roomName = "Бассейн",
        start = start,
        end = start.plusMinutes(90),
        timeSlotId = 3,
        teacherIsu = 200001,
        teacherFio = "Тренерова Тренера Тренеровна",
    )
}
