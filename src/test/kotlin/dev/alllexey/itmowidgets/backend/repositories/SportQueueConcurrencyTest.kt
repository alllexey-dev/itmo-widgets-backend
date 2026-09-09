package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.dto.SportQueueCandidate
import dev.alllexey.itmowidgets.backend.exceptions.BusinessRuleException
import dev.alllexey.itmowidgets.core.model.QueueEntryStatus
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException

class SportQueueConcurrencyTest : SportQueuePersistenceTest() {
    @Test
    fun `concurrent auto creates return one entry even with exhausted quota`() {
        val user = owner(limit = 1)
        val prototype = lesson()

        val (first, second) = overlapping(user,
            { autos.createEntry(user, prototype) },
            { autos.createEntry(user, prototype) },
        )

        assertEquals(first.getOrThrow().id, second.getOrThrow().id)
        assertEquals(first.getOrThrow().id, autos.createEntry(user, prototype).id)
        assertEquals(1L, count("sport_auto_sign_entries", user))
        assertEquals(0, autos.getLimits(user).available)
    }

    @Test
    fun `concurrent free creates preserve the first force parameter`() {
        val user = owner()
        val target = lesson()

        val (first, second) = overlapping(user,
            { frees.createEntry(user, target, false) },
            { frees.createEntry(user, target, true) },
        )

        assertEquals(first.getOrThrow().id, second.getOrThrow().id)
        assertFalse(second.getOrThrow().forceSign)
        assertEquals(1L, count("sport_free_sign_entries", user))
    }

    @Test
    fun `concurrent different prototypes cannot both spend the last quota slot`() {
        val user = owner(limit = 1)
        val firstPrototype = lesson()
        val secondPrototype = lesson(roomId = 11)

        val (first, second) = overlapping(user,
            { autos.createEntry(user, firstPrototype) },
            { autos.createEntry(user, secondPrototype) },
        )

        first.getOrThrow()
        assertTrue(second.exceptionOrNull() is BusinessRuleException)
        assertEquals(1L, count("sport_auto_sign_entries", user))
        assertEquals(0, autos.getLimits(user).available)
    }

    @Test
    fun `partial unique constraints also reject direct SQL duplicates`() {
        val user = owner()
        val prototype = lesson()
        val target = lesson()
        auto(user, prototype)
        free(user, target)

        assertFailsWith<DataIntegrityViolationException> {
            jdbc.update("INSERT INTO sport_auto_sign_entries (user_id, prototype_lesson_id) VALUES (?, ?)", user, prototype)
        }
        assertFailsWith<DataIntegrityViolationException> {
            jdbc.update("INSERT INTO sport_free_sign_entries (user_id, lesson_id, force_sign) VALUES (?, ?, false)", user, target)
        }
        assertEquals(1L, count("sport_auto_sign_entries", user))
        assertEquals(1L, count("sport_free_sign_entries", user))
    }

    @Test
    fun `replacing completed entries releases the unique slot before identity insertion`() {
        val user = owner()
        val prototype = lesson()
        val target = lesson()
        val oldAuto = auto(user, prototype, status = QueueEntryStatus.EXPIRED)
        val oldFree = free(user, target, status = QueueEntryStatus.SATISFIED)

        val newAuto = autos.createEntry(user, prototype)
        val newFree = frees.createEntry(user, target, false)

        assertNotEquals(oldAuto.entryId, newAuto.id)
        assertNotEquals(oldFree.entryId, newFree.id)
        assertTrue(cancelled("sport_auto_sign_entries", oldAuto))
        assertTrue(cancelled("sport_free_sign_entries", oldFree))
        assertEquals(1L, count("sport_auto_sign_entries", user))
        assertEquals(1L, count("sport_free_sign_entries", user))
        assertEquals(2L, count("sport_auto_sign_entries", user, activeOnly = false))
        assertEquals(2L, count("sport_free_sign_entries", user, activeOnly = false))
        assertEquals(clock.instant(), newAuto.createdAt.toInstant())
        assertEquals(clock.instant(), newFree.createdAt.toInstant())
    }

    @Test
    fun `multiple prototypes bound to one real lesson satisfy and cancel without scalar conflicts`() {
        val user = owner()
        val start = OffsetDateTime.now(clock).plusHours(3)
        val target = lesson(start = start)
        val first = auto(user, lesson(start = start.minusWeeks(2)), status = QueueEntryStatus.NOTIFIED, real = target)
        val second = auto(user, lesson(start = start.minusWeeks(2)), status = QueueEntryStatus.NOTIFIED, real = target)
        val expired = auto(user, lesson(start = start.minusWeeks(2)), status = QueueEntryStatus.EXPIRED, real = target)

        autos.markEntrySatisfiedByLesson(user, target)
        autos.markEntrySatisfiedByLesson(user, target)
        assertEquals("SATISFIED", status("sport_auto_sign_entries", first))
        assertEquals("SATISFIED", status("sport_auto_sign_entries", second))
        assertEquals("EXPIRED", status("sport_auto_sign_entries", expired))
        val satisfiedAt = timestamp("sport_auto_sign_entries", first, "satisfied_at")
        clock.advance(Duration.ofSeconds(1))
        autos.markEntrySatisfiedByLesson(user, target)
        assertEquals(satisfiedAt, timestamp("sport_auto_sign_entries", first, "satisfied_at"))

        autos.cancelEntryByLesson(user, target)
        autos.cancelEntryByLesson(user, target)
        autos.markEntrySatisfiedByLesson(user, target)
        listOf(first, second, expired).forEach { assertTrue(cancelled("sport_auto_sign_entries", it)) }
        assertEquals("EXPIRED", status("sport_auto_sign_entries", expired))
    }

    @Test
    fun `auto cancellation after discovery cannot be overwritten by reservation`() {
        val user = owner()
        val start = OffsetDateTime.now(clock).plusHours(3)
        val target = lesson(start = start)
        val entry = auto(user, lesson(start = start.minusWeeks(2)))
        val candidate = autoRepository.findUnresolvedCandidates(target).single { it.entryId == entry.entryId }

        autos.cancelEntry(user, entry.entryId)
        val cancelledAt = timestamp("sport_auto_sign_entries", entry, "cancelled_at")
        clock.advance(Duration.ofSeconds(1))
        autos.cancelEntry(user, entry.entryId)
        assertNull(transitions.prepareAutoNotification(candidate, target, bindUnresolved = true))

        assertTrue(cancelled("sport_auto_sign_entries", entry))
        assertEquals(cancelledAt, timestamp("sport_auto_sign_entries", entry, "cancelled_at"))
        assertEquals(0, attempts("sport_auto_sign_entries", entry))
        assertEquals("WAITING", status("sport_auto_sign_entries", entry))
    }

    @Test
    fun `free cancellation after discovery cannot be overwritten by reservation`() {
        val user = owner()
        val target = lesson()
        val entry = free(user, target)
        val candidate = freeRepository.findNotificationCandidates(target).single { it.entryId == entry.entryId }

        frees.cancelEntry(user, entry.entryId)
        val cancelledAt = timestamp("sport_free_sign_entries", entry, "cancelled_at")
        clock.advance(Duration.ofSeconds(1))
        frees.cancelEntry(user, entry.entryId)
        frees.cancelEntryByLesson(user, target)
        assertNull(transitions.prepareFreeNotification(candidate, target))

        assertTrue(cancelled("sport_free_sign_entries", entry))
        assertEquals(cancelledAt, timestamp("sport_free_sign_entries", entry, "cancelled_at"))
        assertEquals(0, attempts("sport_free_sign_entries", entry))
    }

    @Test
    fun `satisfaction after discovery blocks both kinds of scheduler reservation`() {
        val user = owner()
        val start = OffsetDateTime.now(clock).plusHours(3)
        val target = lesson(start = start)
        val autoEntry = auto(user, lesson(start = start.minusWeeks(2)))
        val freeEntry = free(user, target)
        val autoCandidate = autoRepository.findUnresolvedCandidates(target).single { it.entryId == autoEntry.entryId }
        val freeCandidate = freeRepository.findNotificationCandidates(target).single { it.entryId == freeEntry.entryId }

        autos.markEntrySatisfied(user, autoEntry.entryId)
        frees.markEntrySatisfied(user, freeEntry.entryId)
        assertNull(transitions.prepareAutoNotification(autoCandidate, target, bindUnresolved = true))
        assertNull(transitions.prepareFreeNotification(freeCandidate, target))
        assertEquals("SATISFIED", status("sport_auto_sign_entries", autoEntry))
        assertEquals("SATISFIED", status("sport_free_sign_entries", freeEntry))
        assertEquals(0, attempts("sport_auto_sign_entries", autoEntry))
        assertEquals(0, attempts("sport_free_sign_entries", freeEntry))
    }

    @Test
    fun `cleanup racing with booking sync cannot replace committed satisfaction`() {
        val user = owner()
        val start = OffsetDateTime.now(clock).plusMinutes(30)
        val target = lesson(start = start)
        val entry = free(user, target)
        val candidate = freeRepository.findExpiredCandidates(OffsetDateTime.now(clock)).single { it.entryId == entry.entryId }

        val (sync, cleanup) = overlapping(user,
            { bookings.syncLessons(user, listOf(target)) },
            { transitions.expireFreeEntry(candidate) },
        )
        sync.getOrThrow()
        cleanup.getOrThrow()

        assertEquals("SATISFIED", status("sport_free_sign_entries", entry))
        assertNull(timestamp("sport_free_sign_entries", entry, "expired_at"))
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM user_sport_lessons WHERE user_id = ? AND lesson_id = ?", Long::class.java, user, target))
    }

    @Test
    fun `auto reservation observes exact debounce own attempt maximum and immutable binding`() {
        val user = owner()
        val start = OffsetDateTime.now(clock).plusHours(3)
        val target = lesson(start = start)
        val otherTarget = lesson(start = start)
        val entry = auto(user, lesson(start = start.minusWeeks(2)), maxAttempts = 2)

        val first = assertNotNull(transitions.prepareAutoNotification(entry, target, bindUnresolved = true))
        assertEquals(1, first.attemptNumber)
        assertNull(transitions.prepareAutoNotification(entry, otherTarget, bindUnresolved = true))
        assertNull(transitions.prepareAutoNotification(entry, otherTarget, bindUnresolved = false))
        clock.advance(Duration.ofMinutes(15).minusSeconds(1))
        assertNull(transitions.prepareAutoNotification(entry, target, bindUnresolved = false))
        clock.advance(Duration.ofSeconds(1))
        val last = assertNotNull(transitions.prepareAutoNotification(entry, target, bindUnresolved = false))

        assertEquals(2, last.attemptNumber)
        assertEquals("GAVE_UP_NOTIFYING", status("sport_auto_sign_entries", entry))
        assertFalse(transitions.isIntentCurrent(first))
        assertTrue(transitions.isIntentCurrent(last))
        clock.advance(Duration.ofMinutes(15))
        assertNull(transitions.prepareAutoNotification(entry, target, bindUnresolved = false))
        assertEquals(2, attempts("sport_auto_sign_entries", entry))
        assertEquals(target, jdbc.queryForObject("SELECT real_lesson_id FROM sport_auto_sign_entries WHERE id = ?", Long::class.java, entry.entryId))
    }

    @Test
    fun `free reservation retries a missed send only after debounce and stops at own maximum`() {
        val user = owner()
        val target = lesson()
        val entry = free(user, target, maxAttempts = 2)
        val first = assertNotNull(transitions.prepareFreeNotification(entry, target))
        assertNull(transitions.prepareFreeNotification(entry, target))
        clock.advance(Duration.ofMinutes(15))
        val last = assertNotNull(transitions.prepareFreeNotification(entry, target))

        assertEquals(2, last.attemptNumber)
        assertEquals("GAVE_UP_NOTIFYING", status("sport_free_sign_entries", entry))
        assertFalse(transitions.isIntentCurrent(first))
        assertTrue(transitions.isIntentCurrent(last))
        clock.advance(Duration.ofMinutes(15))
        assertNull(transitions.prepareFreeNotification(entry, target))
        assertEquals(2, attempts("sport_free_sign_entries", entry))
    }

    @Test
    fun `non-force deadline is exactly one hour before start`() {
        val user = owner()
        val start = OffsetDateTime.now(clock).plusHours(1)
        val target = lesson(start = start)
        val entry = free(user, target)

        assertNull(transitions.prepareFreeNotification(entry, target))
        assertEquals("EXPIRED", status("sport_free_sign_entries", entry))
        assertEquals(clock.instant(), timestamp("sport_free_sign_entries", entry, "expired_at"))
        assertEquals(0, attempts("sport_free_sign_entries", entry))
    }

    @Test
    fun `non-force candidate deadline is identical in UTC and Moscow database sessions`() {
        val user = owner()
        val start = OffsetDateTime.now(clock).plusHours(1)
        val deadline = start.minusHours(1).toInstant()
        val target = lesson(start = start)
        val entry = free(user, target)

        for (zone in listOf("UTC", "Europe/Moscow")) {
            inTransaction {
                jdbc.execute("SET LOCAL TIME ZONE '$zone'")
                // PostgreSQL timestamp(6) resolves microseconds, not individual nanoseconds.
                clock.set(deadline.minusNanos(1_000))
                assertTrue(freeRepository.findExpiredCandidates(OffsetDateTime.now(clock)).none {
                    it.entryId == entry.entryId
                }, "Before deadline in $zone")
                clock.set(deadline)
                assertTrue(freeRepository.findExpiredCandidates(OffsetDateTime.now(clock)).any {
                    it.entryId == entry.entryId
                }, "At deadline in $zone")
            }
        }
    }

    @Test
    fun `force is eligible after start but stops exactly at lesson end`() {
        val user = owner()
        val start = OffsetDateTime.now(clock).minusMinutes(30)
        val end = start.plusHours(1)
        val target = lesson(start = start, end = end)
        val entry = free(user, target, force = true)
        val intent = assertNotNull(transitions.prepareFreeNotification(entry, target))
        assertTrue(transitions.isIntentCurrent(intent))
        assertTrue(freeRepository.findExpiredCandidates(OffsetDateTime.now(clock)).none { it.entryId == entry.entryId })

        clock.set(end.toInstant())
        assertFalse(transitions.isIntentCurrent(intent))
        assertTrue(freeRepository.findExpiredCandidates(OffsetDateTime.now(clock)).any { it.entryId == entry.entryId })
        transitions.expireFreeEntry(entry)
        assertEquals("EXPIRED", status("sport_free_sign_entries", entry))
        assertNull(transitions.prepareFreeNotification(entry, target))
        assertEquals(1, attempts("sport_free_sign_entries", entry))
    }

    @Test
    fun `booking synchronization uses injected time for academic cutoff and creation`() {
        val user = owner()
        val past = lesson(start = OffsetDateTime.now(clock).minusHours(1))
        val future = lesson(start = OffsetDateTime.now(clock).plusHours(1))
        bookings.syncLessons(user, listOf(past, future))
        val createdAt = jdbc.queryForObject("SELECT created_at FROM user_sport_lessons WHERE user_id = ? AND lesson_id = ?", OffsetDateTime::class.java, user, future)
        assertEquals(clock.instant(), createdAt!!.toInstant())

        bookings.syncLessons(user, emptyList())
        assertEquals(listOf(past), jdbc.queryForList("SELECT lesson_id FROM user_sport_lessons WHERE user_id = ?", Long::class.java, user))
    }

    private fun <T> overlapping(user: UUID, first: () -> T, second: () -> T): Pair<Result<T>, Result<T>> {
        val executor = Executors.newFixedThreadPool(2)
        lockGate.arm(user)
        try {
            val left = executor.submit(Callable { runCatching(first) })
            lockGate.awaitFirstLocked()
            val right = executor.submit(Callable { runCatching(second) })
            lockGate.awaitSecondAttempt()
            lockGate.awaitSecondBlocked()
            lockGate.release()
            return left.get(15, TimeUnit.SECONDS) to right.get(15, TimeUnit.SECONDS)
        } finally {
            lockGate.release()
            lockGate.reset()
            executor.shutdownNow()
            executor.awaitTermination(15, TimeUnit.SECONDS)
        }
    }

    private fun count(table: String, user: UUID, activeOnly: Boolean = true): Long =
        jdbc.queryForObject("SELECT count(*) FROM $table WHERE user_id = ?" + if (activeOnly) " AND NOT is_cancelled" else "", Long::class.java, user)!!

    private fun status(table: String, entry: SportQueueCandidate): String =
        jdbc.queryForObject("SELECT status FROM $table WHERE id = ?", String::class.java, entry.entryId)!!

    private fun cancelled(table: String, entry: SportQueueCandidate): Boolean =
        jdbc.queryForObject("SELECT is_cancelled FROM $table WHERE id = ?", Boolean::class.java, entry.entryId)!!

    private fun attempts(table: String, entry: SportQueueCandidate): Int =
        jdbc.queryForObject("SELECT notification_attempts FROM $table WHERE id = ?", Int::class.java, entry.entryId)!!

    private fun timestamp(table: String, entry: SportQueueCandidate, column: String): Instant? =
        jdbc.queryForObject("SELECT $column FROM $table WHERE id = ?", OffsetDateTime::class.java, entry.entryId)?.toInstant()
}
