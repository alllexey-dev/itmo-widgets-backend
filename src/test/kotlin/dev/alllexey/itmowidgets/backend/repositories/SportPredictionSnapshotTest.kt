package dev.alllexey.itmowidgets.backend.repositories

import api.myitmo.model.IdValuePair
import api.myitmo.model.sport.SportFilters
import api.myitmo.model.sport.TimeSlot
import api.myitmo.model.sport.SportLesson as ApiSportLesson
import dev.alllexey.itmowidgets.backend.dto.SportQueueCandidate
import dev.alllexey.itmowidgets.core.model.fcm.FcmTypedWrapper
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions

class SportPredictionSnapshotTest : SportQueuePersistenceTest() {
    @Test
    fun `catalog and dictionary updates preserve an old target while a new entry captures new values`() {
        val fixture = forecast()
        assertNotNull(transitions.prepareAutoNotification(fixture.candidate, fixture.real, true))
        val original = autos.getUserEntries(fixture.owner).single().targetLesson
        val originalColumns = snapshotColumns(fixture.candidate)

        references(fixture.reference, "Renamed original")
        val changedReference = references(prefix = "Changed")
        val changedStart = fixture.start.minusWeeks(2).plusHours(1)
        val changedEnd = fixture.end.minusWeeks(2).plusHours(2)
        catalog.applySnapshot(listOf(wire(fixture.prototype, changedStart, changedEnd, changedReference).apply {
            sectionName = " Changed section "
            sectionLevel = 2
            lessonLevel = 3
            typeId = 5
            roomId = 25
            roomName = " Changed room "
        }))
        catalog.applySnapshot(listOf(wire(fixture.real, fixture.start, fixture.end, fixture.reference).apply {
            roomName = " Current actual room "
        }))

        val oldEntry = autos.getUserEntries(fixture.owner).single()
        assertEquals(original, oldEntry.targetLesson)
        assertEquals(originalColumns, snapshotColumns(fixture.candidate))
        val actual = assertNotNull(oldEntry.realLesson)
        assertEquals("Current actual room", actual.roomName)
        assertEquals("Renamed original teacher", actual.teacherFio)
        assertEquals(fixture.real, actual.id)

        val newOwner = owner()
        val newEntry = autos.createEntry(newOwner, fixture.prototype)
        assertEquals(fixture.prototype, newEntry.targetLesson.id)
        assertEquals(changedReference, newEntry.targetLesson.sectionId)
        assertEquals(changedReference, newEntry.targetLesson.buildingId)
        assertEquals(changedReference, newEntry.targetLesson.teacherIsu)
        assertEquals(changedReference, newEntry.targetLesson.timeSlotId)
        assertEquals("Changed section", newEntry.targetLesson.sectionName)
        assertEquals("Changed teacher", newEntry.targetLesson.teacherFio)
        assertEquals("Changed room", newEntry.targetLesson.roomName)
        assertEquals(2L, newEntry.targetLesson.sectionLevel)
        assertEquals(3L, newEntry.targetLesson.level)
        assertEquals(5L, newEntry.targetLesson.typeId)
        assertEquals(changedStart.toInstant(), newEntry.targetLesson.start.toInstant())
        assertEquals(changedEnd.toInstant(), newEntry.targetLesson.end.toInstant())
        assertEquals(25L, jdbc.queryForObject("SELECT target_room_id FROM sport_auto_sign_entries WHERE id=?", Long::class.java, newEntry.id))
    }

    @Test
    fun `original matching and recent visibility survive a prototype moving outside the recent window`() {
        val fixture = forecast()
        val shiftedStart = fixture.start.minusWeeks(8)
        catalog.applySnapshot(listOf(wire(fixture.prototype, shiftedStart, shiftedStart.plusHours(1), fixture.reference).apply {
            roomId = 999
        }))

        assertEquals(listOf(fixture.candidate), autoRepository.findUnresolvedCandidates(fixture.real))
        assertEquals(listOf(fixture.candidate.entryId), autos.getUserEntries(fixture.owner).map { it.id })
        assertNotNull(transitions.prepareAutoNotification(fixture.candidate, fixture.real, true))
        assertEquals(fixture.real, realLesson(fixture.candidate))
    }

    @Test
    fun `moving the prototype into the future cannot extend the original forecast deadline`() {
        val fixture = forecast()
        val later = fixture.start.plusWeeks(8)
        catalog.applySnapshot(listOf(wire(fixture.prototype, later, later.plusHours(1), fixture.reference)))
        clock.set(fixture.end.toInstant())

        assertTrue(fixture.candidate in autoRepository.findExpiredCandidates(OffsetDateTime.now(clock).minusWeeks(2)))
        transitions.expireAutoEntry(fixture.candidate)

        assertEquals("EXPIRED", status(fixture.candidate))
        assertEquals(fixture.end.toInstant(), jdbc.queryForObject(
            "SELECT expired_at FROM sport_auto_sign_entries WHERE id=?", OffsetDateTime::class.java, fixture.candidate.entryId,
        )!!.toInstant())
    }

    @ParameterizedTest
    @EnumSource(ActualChange::class)
    fun `changed actual criteria suppress bound retries and never retarget the forecast`(change: ActualChange) {
        val fixture = forecast()
        val intent = assertNotNull(transitions.prepareAutoNotification(fixture.candidate, fixture.real, true))
        val differentReference = references(prefix = "Different")
        val changed = wire(fixture.real, fixture.start, fixture.end, fixture.reference).apply {
            when (change) {
                ActualChange.BUILDING -> buildingId = differentReference
                ActualChange.ROOM -> roomId = 99
                ActualChange.TEACHER -> teacherIsu = differentReference
                ActualChange.TIME_SLOT -> timeSlotId = differentReference
                ActualChange.START -> date = fixture.start.plusSeconds(1)
                ActualChange.END -> dateEnd = fixture.end.plusSeconds(1)
            }
        }
        catalog.applySnapshot(listOf(changed))
        clock.advance(Duration.ofMinutes(15))

        assertNull(transitions.prepareAutoNotification(fixture.candidate, fixture.real, false))
        assertFalse(transitions.isIntentCurrent(intent))
        delivery.deliver(intent)
        verifyNoInteractions(fcm)

        val alternative = lesson(start = fixture.start, end = fixture.end)
        val alternatives = catalog.applySnapshot(listOf(wire(alternative, fixture.start, fixture.end, fixture.reference)))
        autoNotifications.reconcileUnresolvedForecasts(alternatives.capacities)

        assertEquals(fixture.real, realLesson(fixture.candidate))
        assertEquals(1, attempts(fixture.candidate))
        assertEquals("NOTIFIED", status(fixture.candidate))
        assertEquals(fixture.real, autos.getUserEntries(fixture.owner).single().realLesson?.id)
    }

    @Test
    fun `a corrected known lesson resolves a waiting forecast without allocating a new catalog identity`() {
        val fixture = forecast(realRoom = 99)
        assertTrue(autoRepository.findUnresolvedCandidates(fixture.real).isEmpty())

        val accepted = catalog.applySnapshot(listOf(wire(fixture.real, fixture.start, fixture.end, fixture.reference)))
        autoNotifications.reconcileUnresolvedForecasts(accepted.capacities)

        assertEquals(mapOf(fixture.real to 2L), accepted.capacities)
        assertEquals(fixture.real, realLesson(fixture.candidate))
        assertEquals("NOTIFIED", status(fixture.candidate))
        assertEquals(1, attempts(fixture.candidate))
        verify(fcm).sendDataMessage(eq(fixture.token), any<FcmTypedWrapper<Any?>>())
    }

    @Test
    fun `positive capacity binds an already booked forecast as satisfied without spending the notification slot`() {
        val fixture = forecast()
        bookings.syncLessons(fixture.owner, listOf(fixture.real))
        assertEquals("WAITING", status(fixture.candidate))
        assertNull(realLesson(fixture.candidate))
        val waitingOwner = owner()
        val waiting = auto(waitingOwner, fixture.prototype)
        val waitingToken = device(waitingOwner)

        autoNotifications.reconcileUnresolvedForecasts(mapOf(fixture.real to 2L))

        assertEquals("SATISFIED", status(fixture.candidate))
        assertEquals(fixture.real, realLesson(fixture.candidate))
        assertEquals(0, attempts(fixture.candidate))
        assertEquals(clock.instant(), jdbc.queryForObject(
            "SELECT satisfied_at FROM sport_auto_sign_entries WHERE id=?", OffsetDateTime::class.java, fixture.candidate.entryId,
        )!!.toInstant())
        assertEquals("NOTIFIED", status(waiting))
        assertEquals(1, attempts(waiting))
        verify(fcm).sendDataMessage(eq(waitingToken), any<FcmTypedWrapper<Any?>>())
        verifyNoMoreInteractions(fcm)
    }

    @Test
    fun `fresh delivery check also refuses a confirmed booking with an unreconciled notified row`() {
        val fixture = forecast()
        val intent = assertNotNull(transitions.prepareAutoNotification(fixture.candidate, fixture.real, true))
        jdbc.update("INSERT INTO user_sport_lessons(user_id,lesson_id,created_at) VALUES (?,?,?)",
            fixture.owner, fixture.real, OffsetDateTime.now(clock))
        assertEquals("NOTIFIED", status(fixture.candidate))

        delivery.deliver(intent)

        verifyNoInteractions(fcm)
        assertEquals(1, attempts(fixture.candidate))
    }

    private fun forecast(realRoom: Long = 10): ForecastFixture {
        val userId = owner()
        val reference = references()
        val start = OffsetDateTime.now(clock).plusHours(3)
        val end = start.plusHours(1)
        val prototype = lesson(start = start.minusWeeks(2), end = end.minusWeeks(2))
        val real = lesson(start = start, end = end)
        catalog.applySnapshot(listOf(
            wire(prototype, start.minusWeeks(2), end.minusWeeks(2), reference),
            wire(real, start, end, reference).apply { roomId = realRoom },
        ))
        val candidate = auto(userId, prototype)
        return ForecastFixture(userId, prototype, real, reference, start, end, candidate, device(userId))
    }

    private fun references(id: Long = nextReference.getAndIncrement(), prefix: String = "Original"): Long {
        catalog.applyFilters(SportFilters().apply {
            buildingId = listOf(pair(id, "$prefix building"))
            sectionId = listOf(pair(id, "$prefix section"))
            teacherIsu = listOf(pair(id, "$prefix teacher"))
        })
        catalog.applyTimeSlots(listOf(TimeSlot().apply {
            this.id = id
            timeStart = "12:00"
            timeEnd = "13:00"
        }))
        return id
    }

    private fun pair(id: Long, name: String) = IdValuePair().apply { this.id = id; value = name }

    private fun wire(id: Long, start: OffsetDateTime, end: OffsetDateTime, reference: Long) = ApiSportLesson().apply {
        this.id = id
        sectionId = reference
        sectionName = " Original section "
        sectionLevel = 1
        lessonLevel = 1
        typeId = 1
        buildingId = reference
        teacherIsu = reference
        timeSlotId = reference
        roomId = 10
        roomName = " Original room "
        date = start
        dateEnd = end
        available = 2
    }

    private fun device(userId: UUID): String = "synthetic-prediction-token-${UUID.randomUUID()}".also { token ->
        jdbc.update("INSERT INTO devices(id,user_id,fcm_token,device_name,last_login) VALUES (?,?,?,'Synthetic device',?)",
            UUID.randomUUID(), userId, token, OffsetDateTime.now(clock))
    }

    private fun snapshotColumns(candidate: SportQueueCandidate): Map<String, Any?> = jdbc.queryForMap("""
        SELECT target_section_id,target_section_name,target_section_level,target_lesson_level,target_type_id,
            target_time_slot_id,target_building_id,target_teacher_isu,target_teacher_name,target_room_id,
            target_room_name,target_starts_at,target_ends_at
        FROM sport_auto_sign_entries WHERE id=?
    """.trimIndent(), candidate.entryId)

    private fun realLesson(candidate: SportQueueCandidate): Long? =
        jdbc.queryForObject("SELECT real_lesson_id FROM sport_auto_sign_entries WHERE id=?", Long::class.java, candidate.entryId)

    private fun status(candidate: SportQueueCandidate): String? =
        jdbc.queryForObject("SELECT status FROM sport_auto_sign_entries WHERE id=?", String::class.java, candidate.entryId)

    private fun attempts(candidate: SportQueueCandidate): Int? =
        jdbc.queryForObject("SELECT notification_attempts FROM sport_auto_sign_entries WHERE id=?", Int::class.java, candidate.entryId)

    enum class ActualChange { BUILDING, ROOM, TEACHER, TIME_SLOT, START, END }

    private data class ForecastFixture(
        val owner: UUID,
        val prototype: Long,
        val real: Long,
        val reference: Long,
        val start: OffsetDateTime,
        val end: OffsetDateTime,
        val candidate: SportQueueCandidate,
        val token: String,
    )

    companion object {
        private val nextReference = AtomicLong(99000001)
    }
}
