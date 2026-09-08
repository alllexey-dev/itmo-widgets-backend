package dev.alllexey.itmowidgets.backend.services

import api.myitmo.model.sport.SportSignLimit
import dev.alllexey.itmowidgets.backend.model.SportAutoSignEntity
import dev.alllexey.itmowidgets.backend.model.SportBuilding
import dev.alllexey.itmowidgets.backend.model.SportLesson
import dev.alllexey.itmowidgets.backend.model.SportLesson.Companion.toDto
import dev.alllexey.itmowidgets.backend.model.SportSection
import dev.alllexey.itmowidgets.backend.model.SportTeacher
import dev.alllexey.itmowidgets.backend.model.SportTimeSlot
import dev.alllexey.itmowidgets.backend.model.User
import dev.alllexey.itmowidgets.backend.repositories.SportAutoSignEntryRepository
import dev.alllexey.itmowidgets.backend.repositories.SportLessonRepository
import dev.alllexey.itmowidgets.core.model.QueueEntryStatus
import dev.alllexey.itmowidgets.core.model.fcm.impl.SportAutoSignLessonsPayload
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import org.junit.jupiter.api.Test
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`

class SportAutoSignNotificationServiceTest {

    private val autoSignRepository = mock(SportAutoSignEntryRepository::class.java)
    private val freeSignService = mock(SportFreeSignService::class.java)
    private val deviceService = mock(DeviceService::class.java)
    private val lessonRepository = mock(SportLessonRepository::class.java)
    private val service = SportAutoSignNotificationService(
        autoSignRepository,
        freeSignService,
        deviceService,
        lessonRepository
    )

    @Test
    fun `initial notification matches the same building and room`() {
        val lesson = lesson()
        val entry = entry(1, lesson)
        stubMatchingEntries(lesson, listOf(entry))

        service.processSingleLesson(lesson, capacity = 2)

        verifyMatchingQuery(lesson)
        verify(deviceService).sendDataMessageToUser(entry.user, payload(lesson))
        verify(autoSignRepository).saveAll(listOf(entry))
        assertNotified(entry, lesson, attempts = 1)
        verifyNoInteractions(freeSignService, lessonRepository)
    }

    @Test
    fun `initial notification does not notify or move a queue from another building`() {
        val lesson = lesson()
        val otherBuildingLesson = lesson(buildingId = 99)
        val otherBuildingEntry = entry(1, otherBuildingLesson)
        stubMatchingEntries(otherBuildingLesson, listOf(otherBuildingEntry))
        stubMatchingEntries(lesson, emptyList())

        service.processSingleLesson(lesson, capacity = 2)

        verifyMatchingQuery(lesson)
        verifyNoMoreInteractions(autoSignRepository)
        verifyNoInteractions(deviceService, freeSignService, lessonRepository)
        assertWaiting(otherBuildingEntry)
    }

    @Test
    fun `initial notification does not notify or move a queue from another room in the same building`() {
        val lesson = lesson()
        val otherRoomLesson = lesson(roomId = 99)
        val otherRoomEntry = entry(1, otherRoomLesson)
        stubMatchingEntries(otherRoomLesson, listOf(otherRoomEntry))
        stubMatchingEntries(lesson, emptyList())

        service.processSingleLesson(lesson, capacity = 2)

        verifyMatchingQuery(lesson)
        verifyNoMoreInteractions(autoSignRepository)
        verifyNoInteractions(deviceService, freeSignService, lessonRepository)
        assertWaiting(otherRoomEntry)
    }

    @Test
    fun `initial notification preserves queue order and reserves one available place`() {
        val lesson = lesson()
        val entries = (1L..4L).map { entry(it, lesson) }
        stubMatchingEntries(lesson, entries)

        service.processSingleLesson(lesson, capacity = 3)

        verifyMatchingQuery(lesson)
        val notifications = inOrder(deviceService, freeSignService)
        entries.take(2).forEach { entry ->
            notifications.verify(deviceService).sendDataMessageToUser(entry.user, payload(lesson))
            assertNotified(entry, lesson, attempts = 1)
        }
        entries.drop(2).forEach { entry ->
            notifications.verify(freeSignService).createEntry(entry.user.id, lesson.id, false)
            assertEquals(QueueEntryStatus.EXPIRED, entry.status)
            assertNotNull(entry.expiredAt)
            assertNull(entry.realLesson)
            assertNull(entry.firstNotifiedAt)
            assertEquals(0, entry.notificationAttempts)
        }
        notifications.verifyNoMoreInteractions()
        verify(autoSignRepository).saveAll(entries.take(2))
    }

    @Test
    fun `initial notification does not send when only the reserved place remains`() {
        val lesson = lesson()
        val entry = entry(1, lesson)
        stubMatchingEntries(lesson, listOf(entry))

        service.processSingleLesson(lesson, capacity = 1)

        verifyMatchingQuery(lesson)
        verifyNoInteractions(deviceService)
        verify(freeSignService).createEntry(entry.user.id, lesson.id, false)
        assertEquals(QueueEntryStatus.EXPIRED, entry.status)
        assertNotNull(entry.expiredAt)
        assertNull(entry.realLesson)
        assertEquals(0, entry.notificationAttempts)
    }

    @Test
    fun `retry matches the building and room and preserves capacity order cooldown and attempt guards`() {
        val lesson = lesson()
        val firstNotification = Instant.parse("2000-01-01T00:00:00Z")
        val cooldown = entry(1, lesson).apply {
            status = QueueEntryStatus.NOTIFIED
            notificationAttempts = 1
            firstNotifiedAt = firstNotification
            lastNotifiedAt = Instant.parse("9999-01-01T00:00:00Z")
        }
        val exhausted = entry(2, lesson).apply {
            notificationAttempts = 10
        }
        val ready = entry(3, lesson).apply {
            status = QueueEntryStatus.NOTIFIED
            notificationAttempts = 2
            firstNotifiedAt = firstNotification
            lastNotifiedAt = firstNotification
        }
        val lastAttempt = entry(4, lesson).apply {
            status = QueueEntryStatus.NOTIFIED
            notificationAttempts = 9
            firstNotifiedAt = firstNotification
            lastNotifiedAt = firstNotification
        }
        val waitingForCapacity = entry(5, lesson)
        stubMatchingEntries(lesson, listOf(cooldown, exhausted, ready, lastAttempt, waitingForCapacity))
        `when`(lessonRepository.findAllById(listOf(lesson.id))).thenReturn(listOf(lesson))

        service.sendNotificationsForAvailableLessons(mapOf(lesson.id to limit(available = 2)))

        verifyMatchingQuery(lesson)
        verify(lessonRepository).findAllById(listOf(lesson.id))
        val notifications = inOrder(deviceService)
        notifications.verify(deviceService).sendDataMessageToUser(ready.user, payload(lesson))
        notifications.verify(deviceService).sendDataMessageToUser(lastAttempt.user, payload(lesson))
        notifications.verifyNoMoreInteractions()
        verify(autoSignRepository).saveAll(listOf(ready, lastAttempt))
        assertNotified(ready, lesson, attempts = 3)
        assertEquals(firstNotification, ready.firstNotifiedAt)
        assertEquals(QueueEntryStatus.GAVE_UP_NOTIFYING, lastAttempt.status)
        assertEquals(10, lastAttempt.notificationAttempts)
        assertEquals(firstNotification, lastAttempt.firstNotifiedAt)
        assertNotNull(lastAttempt.lastNotifiedAt)
        assertSame(lesson, lastAttempt.realLesson)
        assertEquals(QueueEntryStatus.NOTIFIED, cooldown.status)
        assertEquals(1, cooldown.notificationAttempts)
        assertEquals(Instant.parse("9999-01-01T00:00:00Z"), cooldown.lastNotifiedAt)
        assertNull(cooldown.realLesson)
        assertEquals(QueueEntryStatus.WAITING, exhausted.status)
        assertEquals(10, exhausted.notificationAttempts)
        assertNull(exhausted.realLesson)
        assertWaiting(waitingForCapacity)
        verifyNoInteractions(freeSignService)
    }

    @Test
    fun `retry does not notify or move a queue from another building`() {
        val lesson = lesson()
        val otherBuildingLesson = lesson(buildingId = 99)
        val otherBuildingEntry = entry(1, otherBuildingLesson)
        stubMatchingEntries(otherBuildingLesson, listOf(otherBuildingEntry))
        stubMatchingEntries(lesson, emptyList())
        `when`(lessonRepository.findAllById(listOf(lesson.id))).thenReturn(listOf(lesson))

        service.sendNotificationsForAvailableLessons(mapOf(lesson.id to limit(available = 2)))

        verifyMatchingQuery(lesson)
        verifyNoMoreInteractions(autoSignRepository)
        verifyNoInteractions(deviceService, freeSignService)
        assertWaiting(otherBuildingEntry)
    }

    @Test
    fun `retry does not notify or move a queue from another room in the same building`() {
        val lesson = lesson()
        val otherRoomLesson = lesson(roomId = 99)
        val otherRoomEntry = entry(1, otherRoomLesson)
        stubMatchingEntries(otherRoomLesson, listOf(otherRoomEntry))
        stubMatchingEntries(lesson, emptyList())
        `when`(lessonRepository.findAllById(listOf(lesson.id))).thenReturn(listOf(lesson))

        service.sendNotificationsForAvailableLessons(mapOf(lesson.id to limit(available = 2)))

        verifyMatchingQuery(lesson)
        verifyNoMoreInteractions(autoSignRepository)
        verifyNoInteractions(deviceService, freeSignService)
        assertWaiting(otherRoomEntry)
    }

    @Test
    fun `retry ignores lessons without available places`() {
        service.sendNotificationsForAvailableLessons(mapOf(10L to limit(available = 0)))

        verifyNoInteractions(autoSignRepository, lessonRepository, deviceService, freeSignService)
    }

    private fun stubMatchingEntries(lesson: SportLesson, entries: List<SportAutoSignEntity>) {
        `when`(
            autoSignRepository.findMatchingWaitingEntries(
                sectionId = lesson.section.id,
                teacherId = lesson.teacher.isu,
                buildingId = lesson.building.id,
                roomId = lesson.roomId,
                sectionLevel = lesson.sectionLevel,
                lessonLevel = lesson.lessonLevel,
                typeId = lesson.typeId,
                timeSlotId = lesson.timeSlot.id,
                prototypeStart = lesson.start.minusWeeks(2)
            )
        ).thenReturn(entries)
    }

    private fun verifyMatchingQuery(lesson: SportLesson) {
        verify(autoSignRepository).findMatchingWaitingEntries(
            sectionId = lesson.section.id,
            teacherId = lesson.teacher.isu,
            buildingId = lesson.building.id,
            roomId = lesson.roomId,
            sectionLevel = lesson.sectionLevel,
            lessonLevel = lesson.lessonLevel,
            typeId = lesson.typeId,
            timeSlotId = lesson.timeSlot.id,
            prototypeStart = lesson.start.minusWeeks(2)
        )
    }

    private fun assertNotified(entry: SportAutoSignEntity, lesson: SportLesson, attempts: Int) {
        assertEquals(QueueEntryStatus.NOTIFIED, entry.status)
        assertEquals(attempts, entry.notificationAttempts)
        assertNotNull(entry.firstNotifiedAt)
        assertNotNull(entry.lastNotifiedAt)
        assertSame(lesson, entry.realLesson)
    }

    private fun assertWaiting(entry: SportAutoSignEntity) {
        assertEquals(QueueEntryStatus.WAITING, entry.status)
        assertEquals(0, entry.notificationAttempts)
        assertNull(entry.realLesson)
        assertNull(entry.firstNotifiedAt)
        assertNull(entry.lastNotifiedAt)
        assertNull(entry.expiredAt)
    }

    private fun payload(lesson: SportLesson) = SportAutoSignLessonsPayload(listOf(lesson.toDto()))

    private fun limit(available: Int) = SportSignLimit().apply {
        this.available = available
        this.limit = 20
    }

    private fun entry(id: Long, targetLesson: SportLesson) = SportAutoSignEntity(
        id = id,
        user = User(
            id = UUID(0, id),
            isu = 100000 + id.toInt(),
            pictureUrl = null,
            name = null,
            createdAt = Instant.parse("2026-08-01T00:00:00Z")
        ),
        prototypeLesson = lesson(
            id = 1000 + id,
            buildingId = targetLesson.building.id,
            start = targetLesson.start.minusWeeks(2),
            roomId = targetLesson.roomId
        ),
        realLesson = null,
        createdAt = Instant.parse("2026-08-01T00:00:00Z").plusSeconds(id)
    )

    private fun lesson(
        id: Long = 10,
        buildingId: Long = 4,
        start: OffsetDateTime = OffsetDateTime.parse("2026-09-21T12:00:00+03:00"),
        roomId: Long = 50
    ) = SportLesson(
        id = id,
        section = SportSection(id = 1, name = "Волейбол"),
        sectionLevel = 2,
        lessonLevel = 3,
        typeId = 5,
        sectionName = "Волейбол",
        timeSlot = SportTimeSlot(id = 6, timeStart = "12:00", timeEnd = "13:30"),
        building = SportBuilding(id = buildingId, name = "Корпус $buildingId"),
        teacher = SportTeacher(isu = 123456, name = "Тестовый преподаватель"),
        roomId = roomId,
        roomName = "Зал $roomId",
        start = start,
        end = start.plusMinutes(90)
    )
}
