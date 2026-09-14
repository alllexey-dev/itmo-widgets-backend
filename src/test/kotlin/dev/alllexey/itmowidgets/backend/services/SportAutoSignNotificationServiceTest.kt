package dev.alllexey.itmowidgets.backend.services

import api.myitmo.model.sport.SportSignLimit
import dev.alllexey.itmowidgets.backend.dto.SportNotificationIntent
import dev.alllexey.itmowidgets.backend.dto.SportQueueCandidate
import dev.alllexey.itmowidgets.backend.dto.SportQueueKind
import dev.alllexey.itmowidgets.backend.model.SportLesson
import dev.alllexey.itmowidgets.backend.model.SportQueueRules
import dev.alllexey.itmowidgets.backend.model.SportSection
import dev.alllexey.itmowidgets.backend.model.SportTeacher
import dev.alllexey.itmowidgets.backend.model.SportTimeSlot
import dev.alllexey.itmowidgets.backend.repositories.SportAutoSignEntryRepository
import dev.alllexey.itmowidgets.backend.repositories.SportLessonRepository
import dev.alllexey.itmowidgets.core.model.fcm.impl.SportAutoSignLessonsPayload
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`

class SportAutoSignNotificationServiceTest {
    private val repository = mock(SportAutoSignEntryRepository::class.java)
    private val lessonRepository = mock(SportLessonRepository::class.java)
    private val transitions = mock(SportQueueTransitionService::class.java)
    private val transfers = mock(SportAutoSignTransferService::class.java)
    private val delivery = mock(SportNotificationDeliveryService::class.java)
    private val service =
        SportAutoSignNotificationService(repository, lessonRepository, transitions, transfers, delivery)
    private val lessonId = 10L
    private val lesson = lesson(lessonId)
    private val matchKey = SportQueueRules.matchKey(lesson)!!

    init {
        `when`(lessonRepository.findAllById(setOf(lessonId))).thenReturn(listOf(lesson))
    }

    @Test
    fun `initial notification delivers the committed reservation returned by transition`() {
        val candidate = candidate(1)
        val intent = prepare(candidate, initial = true)
        `when`(repository.findUnresolvedCandidates(matchKey)).thenReturn(listOf(candidate))

        service.reconcileUnresolvedForecasts(mapOf(lessonId to 2L))

        val order = inOrder(transitions, delivery)
        order.verify(transitions).prepareAutoNotification(candidate, lessonId, true)
        order.verify(delivery).deliver(intent)
        verifyNoInteractions(transfers)
    }

    @Test
    fun `initial pass does nothing when discovery found no matching forecast`() {
        `when`(repository.findUnresolvedCandidates(matchKey)).thenReturn(emptyList())

        service.reconcileUnresolvedForecasts(mapOf(lessonId to 2L))

        verify(repository).findUnresolvedCandidates(matchKey)
        verifyNoInteractions(transitions, transfers, delivery)
    }

    @Test
    fun `a stale initial candidate does not spend capacity for the following candidate`() {
        val stale = candidate(1)
        val current = candidate(2)
        val intent = prepare(current, initial = true)
        `when`(repository.findUnresolvedCandidates(matchKey)).thenReturn(listOf(stale, current))

        service.reconcileUnresolvedForecasts(mapOf(lessonId to 2L))

        verify(transitions).prepareAutoNotification(stale, lessonId, true)
        verify(delivery).deliver(intent)
        verifyNoMoreInteractions(delivery)
        verifyNoInteractions(transfers)
    }

    @Test
    fun `initial pass preserves FIFO and keeps one available place reserved`() {
        val candidates = (1L..4L).map(::candidate)
        val intents = candidates.take(2).map { prepare(it, initial = true) }
        `when`(repository.findUnresolvedCandidates(matchKey)).thenReturn(candidates)

        service.reconcileUnresolvedForecasts(mapOf(lessonId to 3L))

        val order = inOrder(delivery, transfers)
        intents.forEach { order.verify(delivery).deliver(it) }
        candidates.drop(2).forEach { order.verify(transfers).transferEntry(it, lessonId) }
        order.verifyNoMoreInteractions()
        candidates.drop(2).forEach { verify(transitions, never()).prepareAutoNotification(it, lessonId, true) }
    }

    @Test
    fun `initial pass transfers instead of sending when only the reserved place remains`() {
        val candidate = candidate(1)
        `when`(repository.findUnresolvedCandidates(matchKey)).thenReturn(listOf(candidate))

        service.reconcileUnresolvedForecasts(mapOf(lessonId to 1L))

        verify(transfers).transferEntry(candidate, lessonId)
        verifyNoInteractions(transitions, delivery)
    }

    @Test
    fun `retry skips guarded candidates and delivers ready reservations in capacity order`() {
        val candidates = (1L..5L).map(::candidate)
        val intents = candidates.drop(2).take(2).map { prepare(it, initial = false) }
        `when`(repository.findBoundNotificationCandidates(lessonId)).thenReturn(candidates)

        service.sendNotificationsForAvailableLessons(mapOf(lessonId to limit(2)))

        val order = inOrder(delivery)
        intents.forEach { order.verify(delivery).deliver(it) }
        order.verifyNoMoreInteractions()
        verify(transitions, never()).prepareAutoNotification(candidates.last(), lessonId, false)
        verifyNoInteractions(transfers)
    }

    @Test
    fun `retry does nothing when discovery found no matching bound candidate`() {
        `when`(repository.findBoundNotificationCandidates(lessonId)).thenReturn(emptyList())

        service.sendNotificationsForAvailableLessons(mapOf(lessonId to limit(2)))

        verify(repository).findBoundNotificationCandidates(lessonId)
        verifyNoInteractions(transitions, delivery, transfers)
    }

    @Test
    fun `retry does not deliver a candidate rejected by the fresh transition check`() {
        val candidate = candidate(1)
        `when`(repository.findBoundNotificationCandidates(lessonId)).thenReturn(listOf(candidate))

        service.sendNotificationsForAvailableLessons(mapOf(lessonId to limit(2)))

        verify(transitions).prepareAutoNotification(candidate, lessonId, false)
        verifyNoInteractions(delivery, transfers)
    }

    @Test
    fun `retry ignores lessons without available places`() {
        service.sendNotificationsForAvailableLessons(mapOf(lessonId to limit(0)))

        verifyNoInteractions(repository, transitions, delivery, transfers)
    }

    @Test
    fun `zero and malformed negative capacity transfer unresolved forecasts instead of sending`() {
        val candidate = candidate(1)
        `when`(repository.findUnresolvedCandidates(matchKey)).thenReturn(listOf(candidate))

        service.reconcileUnresolvedForecasts(mapOf(lessonId to Long.MIN_VALUE))

        verify(transfers).transferEntry(candidate, lessonId)
        verifyNoInteractions(transitions, delivery)
    }

    @Test
    fun `a send failure does not release an already committed initial reservation`() {
        val first = candidate(1)
        val second = candidate(2)
        val intent = prepare(first, initial = true)
        `when`(repository.findUnresolvedCandidates(matchKey)).thenReturn(listOf(first, second))
        doThrow(IllegalStateException("Synthetic transport failure")).`when`(delivery).deliver(intent)

        service.reconcileUnresolvedForecasts(mapOf(lessonId to 2L))

        verify(delivery).deliver(intent)
        verify(transfers).transferEntry(second, lessonId)
        verify(transitions, never()).prepareAutoNotification(second, lessonId, true)
    }

    @Test
    fun `a lesson whose venue cannot prove an identity never reaches the queue`() {
        val online = 11L
        `when`(lessonRepository.findAllById(setOf(online))).thenReturn(listOf(lesson(online, buildingId = 7L, roomId = 0L)))

        service.reconcileUnresolvedForecasts(mapOf(online to 2L))

        verifyNoInteractions(repository, transitions, transfers, delivery)
    }

    @Test
    fun `a capacity for a lesson the catalog no longer holds is skipped`() {
        val missing = 12L
        `when`(lessonRepository.findAllById(setOf(missing))).thenReturn(emptyList())

        service.reconcileUnresolvedForecasts(mapOf(missing to 2L))

        verifyNoInteractions(repository, transitions, transfers, delivery)
    }

    private fun candidate(id: Long) = SportQueueCandidate(id, UUID(0, id))

    private fun lesson(id: Long, buildingId: Long? = 3L, roomId: Long = 4L): SportLesson {
        val start = OffsetDateTime.of(2026, 3, 2, 10, 0, 0, 0, ZoneOffset.UTC)
        return SportLesson(
            id = id,
            section = SportSection(1L, "Section"),
            sectionLevel = 1L,
            lessonLevel = 2L,
            typeId = 5L,
            sectionName = "Section",
            timeSlot = SportTimeSlot(6L, "10:00", "11:30"),
            buildingId = buildingId,
            teacher = SportTeacher(7L, "Teacher"),
            roomId = roomId,
            roomName = "Room",
            start = start,
            end = start.plusHours(1),
            lastSeenAt = Instant.EPOCH,
        )
    }

    private fun prepare(candidate: SportQueueCandidate, initial: Boolean): SportNotificationIntent {
        val intent = SportNotificationIntent(
            SportQueueKind.AUTO, candidate.entryId, candidate.userId, lessonId, 1,
            SportAutoSignLessonsPayload(emptyList()),
        )
        `when`(transitions.prepareAutoNotification(candidate, lessonId, initial)).thenReturn(intent)
        return intent
    }

    private fun limit(available: Int) = SportSignLimit().apply {
        this.available = available
        this.limit = 20
    }
}
