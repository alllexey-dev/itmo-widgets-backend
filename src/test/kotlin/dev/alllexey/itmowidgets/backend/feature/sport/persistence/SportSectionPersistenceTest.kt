package dev.alllexey.itmowidgets.backend.feature.sport.persistence

import dev.alllexey.itmowidgets.backend.feature.sport.model.SportSectionOccurrenceState
import dev.alllexey.itmowidgets.backend.feature.sport.web.MySportSection
import dev.alllexey.itmowidgets.backend.feature.sport.web.QueueEntryStatus
import dev.alllexey.itmowidgets.backend.platform.error.BusinessRuleException
import dev.alllexey.itmowidgets.backend.platform.error.InvalidRequestDataException
import dev.alllexey.itmowidgets.backend.platform.error.PermissionDeniedException
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SportSectionPersistenceTest : SportQueuePersistenceTest() {
    private val start: OffsetDateTime get() = OffsetDateTime.now(clock).plusHours(3)

    @Test
    fun `a section starts only from a booked lesson and a slot is subscribed once`() {
        val user = owner()
        val source = lesson(start = start)
        val later = lesson(start = start.plusWeeks(2))

        assertFailsWith<BusinessRuleException> { sections.subscribe(user, source) }
        bookings.syncLessons(user, listOf(source))
        val section = sections.subscribe(user, source)

        assertEquals(section.id, sections.subscribe(user, later).id, "the lesson two weeks later is the same slot")
        assertEquals(source, section.lesson.id)
        assertEquals(1, section.place)
        assertEquals(1, section.subscribers)
        assertEquals(listOf(section.id), sections.getMine(user).map { it.id })
    }

    @Test
    fun `the occurrence two weeks after a published lesson is queued at once through one auto entry`() {
        val user = owner()
        val source = booked(user, start)
        val section = sections.subscribe(user, source)

        val next = section.occurrences.first()
        val entry = autoRepository.findById(assertNotNull(next.entryId)).orElseThrow()
        assertEquals(source, entry.prototypeLesson.id)
        assertEquals(start.plusWeeks(2).toInstant(), next.start.toInstant())
        assertEquals(listOf(SportSectionOccurrenceState.QUEUED, null, null), section.occurrences.map { it.state })
        repeat(3) { runPass() }
        assertEquals(1L, autoCount(user))
    }

    @Test
    fun `each newly published lesson of the slot opens the next occurrence`() {
        val user = owner()
        val source = booked(user, start)
        sections.subscribe(user, source)
        val next = lesson(start = start.plusWeeks(2))

        runPass()

        val section = mine(user)
        assertEquals(start.plusWeeks(4).toInstant(), section.occurrences[1].start.toInstant())
        assertEquals(next, autoRepository.findById(assertNotNull(section.occurrences[1].entryId)).orElseThrow().prototypeLesson.id)
        assertEquals(2L, autoCount(user))
    }

    @Test
    fun `subscribers are queued in subscription order and see their place`() {
        val users = List(3) { owner() }
        val source = lesson(start = start)
        users.forEach { bookings.syncLessons(it, listOf(source)) }
        users.forEach { sections.subscribe(it, source) }
        val next = lesson(start = start.plusWeeks(2))

        runPass()

        val queue = autoRepository.findAllByPrototypeLessonsAndStatuses(listOf(next), listOf(QueueEntryStatus.WAITING))
        assertEquals(users, queue.map { it.user.id })
        assertEquals(listOf(1, 2, 3), users.map { mine(it).place })
        assertEquals(listOf(3, 3, 3), users.map { mine(it).subscribers })
    }

    @Test
    fun `no free slot skips the occurrence and keeps the place for the next one`() {
        val user = owner(limit = 1)
        val source = booked(user, start)
        val blocking = auto(user, lesson(start = start.plusDays(1)))

        val section = sections.subscribe(user, source)
        assertNull(section.occurrences[0].entryId)
        assertEquals(SportSectionOccurrenceState.LIMIT, section.occurrences[0].state)

        autos.cancelEntry(user, blocking.entryId)
        lesson(start = start.plusWeeks(2))
        runPass()

        val next = mine(user)
        assertEquals(SportSectionOccurrenceState.LIMIT, next.occurrences[0].state, "a skipped occurrence is not retried")
        assertEquals(start.plusWeeks(4).toInstant(), next.occurrences[1].start.toInstant())
        assertNotNull(next.occurrences[1].entryId)
        assertEquals(1, next.place)
    }

    @Test
    fun `the user's own entry for an occurrence is adopted instead of a second one`() {
        val user = owner()
        val source = booked(user, start)
        val own = auto(user, source)

        assertEquals(own.entryId, sections.subscribe(user, source).occurrences[0].entryId)
        assertEquals(1L, autoCount(user))
    }

    @Test
    fun `the cap is a share of the lesson's places and a full slot refuses new subscribers`() {
        val source = lesson(start = start)
        catalog.recordPlacesLimits(mapOf(source to 3))
        val users = List(3) { owner().also { bookings.syncLessons(it, listOf(source)) } }
        users.take(2).forEach { sections.subscribe(it, source) }

        val slot = sections.getSlots(users[2], listOf(source)).single()
        assertEquals(2, slot.cap)
        assertEquals(2, slot.subscribers)
        assertTrue(slot.full)
        assertNull(slot.place)
        assertFailsWith<BusinessRuleException> { sections.subscribe(users[2], source) }

        val own = sections.getSlots(users[1], listOf(source)).single()
        assertFalse(own.full)
        assertEquals(2, own.place)
        assertNotNull(own.subscriptionId)
    }

    @Test
    fun `a slot without a published lesson for two periods ends the section`() {
        val user = owner()
        val section = sections.subscribe(user, booked(user, start))

        val end = section.occurrences[0].end
        clock.set(end.plusWeeks(2).minusMinutes(1).toInstant())
        runPass()
        assertEquals(section.id, mine(user).id)

        clock.set(end.plusWeeks(2).toInstant())
        runPass()
        assertTrue(sections.getMine(user).isEmpty())
        assertEquals("EXPIRED", column(section, "stop_reason"))
    }

    @Test
    fun `only the owner stops a section and its queued entry stays`() {
        val user = owner()
        val section = sections.subscribe(user, booked(user, start))

        assertFailsWith<PermissionDeniedException> { sections.stop(owner(), section.id) }
        sections.stop(user, section.id)
        lesson(start = start.plusWeeks(2))
        runPass()

        assertTrue(sections.getMine(user).isEmpty())
        assertEquals("USER", column(section, "stop_reason"))
        assertEquals(1L, autoCount(user))
    }

    @Test
    fun `skipping a queued occurrence cancels its entry and keeps the place, turning it back on queues it again`() {
        val user = owner()
        val section = sections.subscribe(user, booked(user, start))
        val queued = section.occurrences[0]

        val skipped = sections.setSkipped(user, section.id, queued.start, skipped = true)
        assertEquals(SportSectionOccurrenceState.SKIPPED, skipped.occurrences[0].state)
        assertNull(skipped.occurrences[0].entryId)
        assertEquals(0L, autoCount(user))
        assertEquals(1, skipped.place)
        repeat(2) { runPass() }
        assertEquals(0L, autoCount(user))

        val restored = sections.setSkipped(user, section.id, queued.start, skipped = false)
        assertEquals(SportSectionOccurrenceState.QUEUED, restored.occurrences[0].state)
        assertNotNull(restored.occurrences[0].entryId)
        assertEquals(1L, autoCount(user))
    }

    @Test
    fun `a future occurrence skipped before it opens is never queued`() {
        val user = owner()
        val section = sections.subscribe(user, booked(user, start))
        val later = section.occurrences[1]
        sections.setSkipped(user, section.id, later.start, skipped = true)
        lesson(start = start.plusWeeks(2))

        runPass()

        assertEquals(SportSectionOccurrenceState.SKIPPED, mine(user).occurrences[1].state)
        assertEquals(1L, autoCount(user))
        assertFailsWith<InvalidRequestDataException> { sections.setSkipped(user, section.id, later.start.plusDays(1), skipped = true) }
        assertFailsWith<PermissionDeniedException> { sections.setSkipped(owner(), section.id, later.start, skipped = true) }
    }

    private fun booked(user: UUID, at: OffsetDateTime): Long = lesson(start = at).also { bookings.syncLessons(user, listOf(it)) }

    /** What the catalog refresh does after committing: discovery, then one step per subscription in queue order. */
    private fun runPass() = sections.pendingSteps().forEach(sections::process)

    private fun mine(user: UUID): MySportSection = sections.getMine(user).single()

    private fun autoCount(user: UUID): Long =
        jdbc.queryForObject("SELECT count(*) FROM sport_auto_sign_entries WHERE user_id=? AND NOT is_cancelled", Long::class.java, user)!!

    private fun column(section: MySportSection, column: String): String? =
        jdbc.queryForObject("SELECT $column FROM sport_section_subscriptions WHERE id=?", String::class.java, section.id)
}
