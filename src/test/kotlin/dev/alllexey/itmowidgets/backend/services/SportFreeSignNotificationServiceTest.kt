package dev.alllexey.itmowidgets.backend.services

import api.myitmo.model.sport.SportSignLimit
import dev.alllexey.itmowidgets.backend.dto.SportNotificationIntent
import dev.alllexey.itmowidgets.backend.dto.SportQueueCandidate
import dev.alllexey.itmowidgets.backend.dto.SportQueueKind
import dev.alllexey.itmowidgets.backend.repositories.SportFreeSignEntryRepository
import dev.alllexey.itmowidgets.core.model.fcm.impl.SportFreeSignLessonsPayload
import java.util.UUID
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*

class SportFreeSignNotificationServiceTest {
    private val repository = mock(SportFreeSignEntryRepository::class.java)
    private val transitions = mock(SportQueueTransitionService::class.java)
    private val delivery = mock(SportNotificationDeliveryService::class.java)
    private val service = SportFreeSignNotificationService(repository, transitions, delivery)
    private val first = SportQueueCandidate(1, UUID(0, 1))
    private val second = SportQueueCandidate(2, UUID(0, 2))
    private val lesson = 10L

    @Test
    fun `only one committed reservation is selected per lesson and tick`() {
        val intent = prepared(first)
        `when`(repository.findNotificationCandidates(lesson)).thenReturn(listOf(first, second))

        service.sendNotificationsForFreeLessons(mapOf(lesson to limit(20)))

        verify(delivery).deliver(intent)
        verify(transitions, never()).prepareFreeNotification(second, lesson)
    }

    @Test
    fun `failed delivery still consumes this ticks one reservation`() {
        val intent = prepared(first)
        `when`(repository.findNotificationCandidates(lesson)).thenReturn(listOf(first, second))
        doThrow(IllegalStateException("Synthetic transport failure")).`when`(delivery).deliver(intent)

        service.sendNotificationsForFreeLessons(mapOf(lesson to limit(20)))

        verify(delivery).deliver(intent)
        verify(transitions, never()).prepareFreeNotification(second, lesson)
    }

    @Test
    fun `stale and failed candidates do not consume a reservation`() {
        val failed = SportQueueCandidate(3, UUID(0, 3))
        val intent = prepared(second)
        `when`(repository.findNotificationCandidates(lesson)).thenReturn(listOf(first, failed, second))
        `when`(transitions.prepareFreeNotification(failed, lesson)).thenThrow(IllegalStateException("Synthetic persistence failure"))

        service.sendNotificationsForFreeLessons(mapOf(lesson to limit(1)))

        verify(delivery).deliver(intent)
        verifyNoMoreInteractions(delivery)
    }

    @Test
    fun `unavailable lessons do not discover or notify free candidates`() {
        service.sendNotificationsForFreeLessons(mapOf(lesson to limit(0)))

        verifyNoInteractions(repository, transitions, delivery)
    }

    private fun prepared(candidate: SportQueueCandidate): SportNotificationIntent {
        val intent = SportNotificationIntent(SportQueueKind.FREE, candidate.entryId, candidate.userId,
            lesson, 1, SportFreeSignLessonsPayload(emptyList()))
        `when`(transitions.prepareFreeNotification(candidate, lesson)).thenReturn(intent)
        return intent
    }

    private fun limit(available: Int) = SportSignLimit().apply { this.available = available; this.limit = 20 }
}
