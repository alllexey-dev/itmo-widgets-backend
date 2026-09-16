package dev.alllexey.itmowidgets.backend.repositories

import api.myitmo.model.sport.SportLesson as ApiSportLesson
import dev.alllexey.itmowidgets.backend.dto.SportFreeSignTransferResult
import dev.alllexey.itmowidgets.backend.exceptions.BusinessRuleException
import java.time.OffsetDateTime
import java.util.stream.Stream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

class SportLocationPersistenceTest : SportQueuePersistenceTest() {
    @ParameterizedTest
    @MethodSource("onlineVenues")
    fun `explicit online forecast works with nullable venue and survives target changes`(prototypeBuilding: Long?, realBuilding: Long?) {
        val user = owner()
        registerDevice(user)
        val start = OffsetDateTime.now(clock).plusHours(3)
        val prototype = mappedLesson(start.minusWeeks(2), prototypeBuilding, -1)
        val real = mappedLesson(start, realBuilding, -1)
        val candidate = auto(user, prototype)

        assertEquals(prototypeBuilding, autos.getUserEntries(user).single().targetLesson.buildingId)
        assertEquals(listOf(candidate), unresolvedCandidates(real))
        val intent = assertNotNull(transitions.prepareAutoNotification(candidate, real, true))
        assertTrue(transitions.isIntentCurrent(intent))
        assertEquals(realBuilding, autos.getUserEntries(user).single().realLesson?.buildingId)

        // The actual lesson changing to offline must invalidate a reserved delivery.
        catalog.applySnapshot(listOf(wire(real, start, 335, 20013)))
        assertFalse(transitions.isIntentCurrent(intent))
        assertEquals(prototypeBuilding, autos.getUserEntries(user).single().targetLesson.buildingId)
        assertEquals(335L, autos.getUserEntries(user).single().realLesson?.buildingId)
    }

    @Test
    fun `online forecast transfers to free by actual lesson id with no building dictionary entry`() {
        val user = owner()
        val start = OffsetDateTime.now(clock).plusHours(3)
        val prototype = mappedLesson(start.minusWeeks(2), null, -1)
        val real = mappedLesson(start, null, -1)
        val candidate = auto(user, prototype)

        assertEquals(SportFreeSignTransferResult.CREATED, transfers.transferEntry(candidate, real))
        assertEquals(listOf(user), freeRepository.findNotificationCandidates(real).map { it.userId })
        assertEquals(real, autos.getUserEntries(user).single().realLessonId)
        assertNull(autos.getUserEntries(user).single().targetLesson.buildingId)
    }

    @Test
    fun `external forecasts require raw venue and room while filter dictionary changes are irrelevant`() {
        val user = owner()
        registerDevice(user)
        val start = OffsetDateTime.now(clock).plusHours(3)
        val prototype = mappedLesson(start.minusWeeks(2), 335, 20013)
        val right = mappedLesson(start, 335, 20013)
        val wrongBuilding = mappedLesson(start, 493, 20013)
        val wrongRoom = mappedLesson(start, 335, 21765)
        val candidate = auto(user, prototype)
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM sport_buildings WHERE id IN (335,493)", Long::class.java))
        assertTrue(unresolvedCandidates(wrongBuilding).isEmpty())
        assertTrue(unresolvedCandidates(wrongRoom).isEmpty())
        assertNull(transitions.prepareAutoNotification(candidate, wrongBuilding, true))
        assertNull(transfers.transferEntry(candidate, wrongRoom))
        assertEquals(listOf(candidate), unresolvedCandidates(right))
        assertNotNull(transitions.prepareAutoNotification(candidate, right, true))
        assertEquals(335L, autos.getUserEntries(user).single().targetLesson.buildingId)
    }

    @ParameterizedTest
    @MethodSource("unsafeLocations")
    fun `ambiguous locations remain in catalog and support free queue but cannot create or match forecasts`(building: Long?, room: Long) {
        val user = owner()
        val start = OffsetDateTime.now(clock).plusHours(3)
        val prototype = mappedLesson(start.minusWeeks(2), building, room)
        val real = mappedLesson(start, building, room)
        assertFailsWith<BusinessRuleException> { autos.createEntry(user, prototype) }
        assertEquals(3, autos.getLimits(user).available)
        assertNotNull(frees.createEntry(user, real, false))

        // A snapshot that never proved a location carries no key, and a keyless row matches nothing.
        val validPrototype = mappedLesson(start.minusWeeks(2), 335, 20013)
        val candidate = auto(user, validPrototype)
        jdbc.update("UPDATE sport_auto_sign_entries SET match_key=NULL WHERE id=?", candidate.entryId)
        assertTrue(unresolvedCandidates(real).isEmpty())
        assertNull(transitions.prepareAutoNotification(candidate, real, true))
        assertNull(transfers.transferEntry(candidate, real))
        assertEquals(0, jdbc.queryForObject("SELECT notification_attempts FROM sport_auto_sign_entries WHERE id=?", Int::class.java, candidate.entryId))
    }

    private fun mappedLesson(start: OffsetDateTime, building: Long?, room: Long): Long {
        val id = lesson(start = start)
        assertEquals(0, catalog.applySnapshot(listOf(wire(id, start, building, room))).skippedLessons)
        return id
    }

    private fun wire(id: Long, start: OffsetDateTime, building: Long?, room: Long) = ApiSportLesson().apply {
        this.id = id
        date = start
        dateEnd = start.plusHours(1)
        sectionId = 980001
        sectionName = "Synthetic section"
        sectionLevel = 1
        lessonLevel = 1
        typeId = 1
        timeSlotId = 980001
        teacherIsu = 980001
        buildingId = building
        roomId = room
        roomName = if (room == -1L) "Online" else "External venue"
        available = 2
    }

    companion object {
        @JvmStatic
        fun onlineVenues(): Stream<Arguments> = Stream.of(
            Arguments.of(null, null), Arguments.of(null, -1L), Arguments.of(-1L, null), Arguments.of(-1L, -1L),
        )

        @JvmStatic
        fun unsafeLocations(): Stream<Arguments> = Stream.of(
            Arguments.of(null, 10L), Arguments.of(0L, 10L), Arguments.of(-1L, 10L),
            Arguments.of(335L, 0L), Arguments.of(335L, -2L), Arguments.of(0L, -1L), Arguments.of(335L, -1L),
        )
    }
}
