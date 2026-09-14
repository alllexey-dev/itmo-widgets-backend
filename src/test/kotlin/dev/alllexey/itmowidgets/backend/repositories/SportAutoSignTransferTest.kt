package dev.alllexey.itmowidgets.backend.repositories

import api.myitmo.model.sport.SportLesson as ApiSportLesson
import dev.alllexey.itmowidgets.backend.dto.SportFreeSignTransferResult
import dev.alllexey.itmowidgets.backend.dto.SportQueueCandidate
import dev.alllexey.itmowidgets.core.model.QueueEntryStatus
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.verifyNoInteractions

class SportAutoSignTransferTest : SportQueuePersistenceTest() {
    @Test
    fun `transfer atomically creates free and terminally binds auto`() {
        val user = owner()
        val start = OffsetDateTime.now(clock).plusHours(3)
        val target = lesson(start = start)
        val entry = auto(user, lesson(start = start.minusWeeks(2)))

        assertEquals(SportFreeSignTransferResult.CREATED, transfers.transferEntry(entry, target))

        assertAuto(entry, "EXPIRED", target)
        assertEquals(clock.instant(), jdbc.queryForObject("SELECT expired_at FROM sport_auto_sign_entries WHERE id=?", OffsetDateTime::class.java, entry.entryId)!!.toInstant())
        val freeEntry = freeRepository.findNotificationCandidates(target).single { it.userId == user }
        assertFalse(jdbc.queryForObject("SELECT force_sign FROM sport_free_sign_entries WHERE id=?", Boolean::class.java, freeEntry.entryId)!!)
        assertNull(transfers.transferEntry(entry, target))
        assertEquals(1L, freeCount(user))
        verifyNoInteractions(fcm)
    }

    @Test
    fun `existing active free is reused without resetting force attempts or identity`() {
        val user = owner()
        val start = OffsetDateTime.now(clock).plusHours(3)
        val target = lesson(start = start)
        val existing = free(user, target, force = true, status = QueueEntryStatus.NOTIFIED, attempts = 2)
        val entry = auto(user, lesson(start = start.minusWeeks(2)))

        assertEquals(SportFreeSignTransferResult.EXISTING, transfers.transferEntry(entry, target))

        assertAuto(entry, "EXPIRED", target)
        assertEquals(1L, freeCount(user))
        assertEquals(existing.entryId, freeRepository.findNotificationCandidates(target).single { it.userId == user }.entryId)
        assertTrue(jdbc.queryForObject("SELECT force_sign FROM sport_free_sign_entries WHERE id=?", Boolean::class.java, existing.entryId)!!)
        assertEquals(2, jdbc.queryForObject("SELECT notification_attempts FROM sport_free_sign_entries WHERE id=?", Int::class.java, existing.entryId))
    }

    @Test
    fun `satisfied free satisfies auto instead of creating a second subscription`() {
        val user = owner()
        val start = OffsetDateTime.now(clock).plusHours(3)
        val target = lesson(start = start)
        val existing = free(user, target, status = QueueEntryStatus.SATISFIED)
        val entry = auto(user, lesson(start = start.minusWeeks(2)))

        assertEquals(SportFreeSignTransferResult.ALREADY_SATISFIED, transfers.transferEntry(entry, target))

        assertAuto(entry, "SATISFIED", target)
        assertEquals(clock.instant(), jdbc.queryForObject("SELECT satisfied_at FROM sport_auto_sign_entries WHERE id=?", OffsetDateTime::class.java, entry.entryId)!!.toInstant())
        assertEquals(1L, freeCount(user))
        assertEquals("SATISFIED", jdbc.queryForObject("SELECT status FROM sport_free_sign_entries WHERE id=?", String::class.java, existing.entryId))
        assertNull(transfers.transferEntry(entry, target))
    }

    @Test
    fun `existing booking satisfies auto without creating free`() {
        val user = owner()
        val start = OffsetDateTime.now(clock).plusHours(3)
        val target = lesson(start = start)
        val entry = auto(user, lesson(start = start.minusWeeks(2)))
        bookings.syncLessons(user, listOf(target))

        assertEquals(SportFreeSignTransferResult.ALREADY_SATISFIED, transfers.transferEntry(entry, target))

        assertAuto(entry, "SATISFIED", target)
        assertEquals(0L, freeCount(user))
    }

    @Test
    fun `ended lesson is an expected committed expiry not unexpected rollback`() {
        val user = owner()
        val start = OffsetDateTime.now(clock).plusHours(1)
        val end = start.plusHours(1)
        val target = lesson(start = start, end = end)
        val entry = auto(user, lesson(start = start.minusWeeks(2), end = end.minusWeeks(2)))
        clock.set(end.toInstant())

        assertEquals(SportFreeSignTransferResult.LESSON_ENDED, transfers.transferEntry(entry, target))

        assertAuto(entry, "EXPIRED", target)
        assertEquals(clock.instant(), jdbc.queryForObject("SELECT expired_at FROM sport_auto_sign_entries WHERE id=?", OffsetDateTime::class.java, entry.entryId)!!.toInstant())
        assertEquals(0L, freeCount(user))
        assertNull(transfers.transferEntry(entry, target))
    }

    @Test
    fun `replaying transfer after manual free cancellation never recreates free`() {
        val user = owner()
        val start = OffsetDateTime.now(clock).plusHours(3)
        val target = lesson(start = start)
        val entry = auto(user, lesson(start = start.minusWeeks(2)))
        assertEquals(SportFreeSignTransferResult.CREATED, transfers.transferEntry(entry, target))
        val freeEntry = freeRepository.findNotificationCandidates(target).single { it.userId == user }
        frees.cancelEntry(user, freeEntry.entryId)

        assertNull(transfers.transferEntry(entry, target))
        autoNotifications.reconcileUnresolvedForecasts(mapOf(target to 0L))

        assertAuto(entry, "EXPIRED", target)
        assertEquals(1L, freeCount(user))
        assertEquals(0L, freeCount(user, activeOnly = true))
        assertTrue(jdbc.queryForObject("SELECT is_cancelled FROM sport_free_sign_entries WHERE id=?", Boolean::class.java, freeEntry.entryId)!!)
    }

    @Test
    fun `cancelled terminal and already bound candidates do not enter transfer`() {
        val start = OffsetDateTime.now(clock).plusHours(3)
        val target = lesson(start = start)
        val otherTarget = lesson(start = start)
        val cancelledOwner = owner()
        val cancelled = auto(cancelledOwner, lesson(start = start.minusWeeks(2)))
        autos.cancelEntry(cancelledOwner, cancelled.entryId)
        val expiredOwner = owner()
        val expired = auto(expiredOwner, lesson(start = start.minusWeeks(2)), status = QueueEntryStatus.EXPIRED)
        val boundOwner = owner()
        val bound = auto(boundOwner, lesson(start = start.minusWeeks(2)), status = QueueEntryStatus.NOTIFIED, real = otherTarget)

        listOf(cancelled, expired, bound).forEach { assertNull(transfers.transferEntry(it, target)) }
        autoNotifications.reconcileUnresolvedForecasts(mapOf(target to 0L))

        assertEquals(0L, freeCount(cancelledOwner))
        assertEquals(0L, freeCount(expiredOwner))
        assertEquals(0L, freeCount(boundOwner))
        assertAuto(bound, "NOTIFIED", otherTarget)
    }

    @Test
    fun `changed room after candidate discovery is rechecked under owner lock`() {
        val user = owner()
        val start = OffsetDateTime.now(clock).plusHours(3)
        val target = lesson(start = start)
        val entry = auto(user, lesson(start = start.minusWeeks(2)))
        val candidate = unresolvedCandidates(target).single { it.entryId == entry.entryId }
        jdbc.update("UPDATE sport_lessons SET room_id=11 WHERE id=?", target)

        assertNull(transfers.transferEntry(candidate, target))

        assertAuto(entry, "WAITING", null)
        assertEquals(0L, freeCount(user))
    }

    @Test
    fun `changed building after candidate discovery is rechecked under owner lock`() {
        val user = owner()
        val start = OffsetDateTime.now(clock).plusHours(3)
        val target = lesson(start = start)
        val entry = auto(user, lesson(start = start.minusWeeks(2)))
        val candidate = unresolvedCandidates(target).single { it.entryId == entry.entryId }
        lesson(buildingId = 980002)
        jdbc.update("UPDATE sport_lessons SET building_id=980002 WHERE id=?", target)

        assertNull(transfers.transferEntry(candidate, target))

        assertAuto(entry, "WAITING", null)
        assertEquals(0L, freeCount(user))
    }

    @Test
    fun `failed zero-capacity transfer leaves catalog committed and retries a known lesson next tick`() {
        val start = OffsetDateTime.now(clock).plusHours(3)
        val prototype = lesson(start = start.minusWeeks(2))
        val target = reserveLessonId()
        val failingUser = owner()
        val successfulUser = owner()
        val failed = auto(failingUser, prototype)
        val succeeded = auto(successfulUser, prototype)
        val capacities = catalog.applySnapshot(listOf(apiLesson(target, start))).capacities
        assertEquals(mapOf(target to 0L), capacities)
        assertCatalogCommitted(target)

        // Synthetic test-only CHECK fails a real identity insert in exactly one owner's transaction.
        val constraint = "test_free_rejection_${failingUser.toString().replace("-", "") }"
        jdbc.execute("ALTER TABLE sport_free_sign_entries ADD CONSTRAINT $constraint CHECK (user_id <> '$failingUser'::uuid) NOT VALID")
        try {
            autoNotifications.reconcileUnresolvedForecasts(capacities)
            assertAuto(failed, "WAITING", null)
            assertEquals(0L, freeCount(failingUser))
            assertAuto(succeeded, "EXPIRED", target)
            assertEquals(1L, freeCount(successfulUser))
            assertCatalogCommitted(target)
        } finally {
            jdbc.execute("ALTER TABLE sport_free_sign_entries DROP CONSTRAINT IF EXISTS $constraint")
        }

        // Catalog already knows the ID; recovery is reconciliation, not reinsertion/new-ID discovery.
        autoNotifications.reconcileUnresolvedForecasts(mapOf(target to 0L))
        assertAuto(failed, "EXPIRED", target)
        assertEquals(1L, freeCount(failingUser))
        assertEquals(1L, freeCount(successfulUser))
        assertCatalogCommitted(target)
        verifyNoInteractions(fcm)
    }

    @Test
    fun `transfer replaces a completed unsatisfied free entry after releasing unique slot`() {
        val user = owner()
        val start = OffsetDateTime.now(clock).plusHours(3)
        val target = lesson(start = start)
        val old = free(user, target, status = QueueEntryStatus.GAVE_UP_NOTIFYING, attempts = 10)
        val entry = auto(user, lesson(start = start.minusWeeks(2)))

        assertEquals(SportFreeSignTransferResult.CREATED, transfers.transferEntry(entry, target))

        assertAuto(entry, "EXPIRED", target)
        assertTrue(jdbc.queryForObject("SELECT is_cancelled FROM sport_free_sign_entries WHERE id=?", Boolean::class.java, old.entryId)!!)
        assertEquals(2L, freeCount(user))
        assertEquals(1L, freeCount(user, activeOnly = true))
        assertNotNull(freeRepository.findNotificationCandidates(target).singleOrNull { it.userId == user })
    }

    private fun apiLesson(id: Long, start: OffsetDateTime) = ApiSportLesson().apply {
        this.id = id
        date = start
        dateEnd = start.plusHours(1)
        sectionId = 980001L
        sectionName = "Synthetic section"
        sectionLevel = 1L
        lessonLevel = 1L
        typeId = 1L
        timeSlotId = 980001L
        buildingId = 980001L
        teacherIsu = 980001L
        roomId = 10L
        roomName = "Synthetic room"
        available = 0L
    }

    private fun assertAuto(entry: SportQueueCandidate, expectedStatus: String, real: Long?) {
        assertEquals(expectedStatus, jdbc.queryForObject("SELECT status FROM sport_auto_sign_entries WHERE id=?", String::class.java, entry.entryId))
        assertEquals(real, jdbc.queryForObject("SELECT real_lesson_id FROM sport_auto_sign_entries WHERE id=?", Long::class.java, entry.entryId))
    }

    private fun freeCount(user: UUID, activeOnly: Boolean = false): Long =
        jdbc.queryForObject("SELECT count(*) FROM sport_free_sign_entries WHERE user_id=?" + if (activeOnly) " AND NOT is_cancelled" else "", Long::class.java, user)!!

    private fun assertCatalogCommitted(id: Long) {
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM sport_lessons WHERE id=?", Long::class.java, id))
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM sport_update_logs_new_lessons WHERE new_lessons_id=?", Long::class.java, id))
    }
}
