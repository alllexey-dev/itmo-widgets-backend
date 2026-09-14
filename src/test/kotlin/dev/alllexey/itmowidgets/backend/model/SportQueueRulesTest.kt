package dev.alllexey.itmowidgets.backend.model

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * The forecast rule has exactly one definition, so one suite covers it everywhere it is applied.
 * Every field that takes part in matching is mutated here; a field dropped from the key fails a case.
 */
class SportQueueRulesTest {
    private val prototypeStart = OffsetDateTime.of(2026, 3, 2, 10, 0, 0, 0, ZoneOffset.UTC)

    @Test
    fun `a forecast matches the lesson exactly two weeks after its prototype`() {
        val prediction = SportPredictionSnapshot.fromLesson(lesson(1))
        val realLesson = lesson(2, start = prototypeStart.plusWeeks(2))

        assertTrue(SportQueueRules.matches(prediction, realLesson))
    }

    @Test
    fun `a lesson one week away from the prototype is a different lesson`() {
        val prediction = SportPredictionSnapshot.fromLesson(lesson(1))

        assertFalse(SportQueueRules.matches(prediction, lesson(2, start = prototypeStart.plusWeeks(1))))
        assertFalse(SportQueueRules.matches(prediction, lesson(2, start = prototypeStart.plusWeeks(3))))
        assertFalse(SportQueueRules.matches(prediction, lesson(2, start = prototypeStart)))
    }

    @Test
    fun `every matched field separates a forecast from an otherwise identical lesson`() {
        val prediction = SportPredictionSnapshot.fromLesson(lesson(1))
        val target = prototypeStart.plusWeeks(2)
        val divergences = mapOf<String, SportLesson>(
            "sectionId" to lesson(2, start = target, sectionId = 99L),
            "sectionLevel" to lesson(2, start = target, sectionLevel = 99L),
            "lessonLevel" to lesson(2, start = target, lessonLevel = 99L),
            "typeId" to lesson(2, start = target, typeId = 99L),
            "timeSlotId" to lesson(2, start = target, timeSlotId = 99L),
            "buildingId" to lesson(2, start = target, buildingId = 99L),
            "teacherIsu" to lesson(2, start = target, teacherIsu = 99L),
            "roomId" to lesson(2, start = target, roomId = 99L),
            "end" to lesson(2, start = target, durationMinutes = 90L),
        )
        divergences.forEach { (field, diverged) ->
            assertFalse(SportQueueRules.matches(prediction, diverged), "$field must separate two lessons")
        }
    }

    @Test
    fun `labels and identity of the prototype row never take part in matching`() {
        val prediction = SportPredictionSnapshot.fromLesson(lesson(1))
        val renamed = lesson(2, start = prototypeStart.plusWeeks(2), sectionName = "Renamed", roomName = "Elsewhere")

        assertTrue(SportQueueRules.matches(prediction, renamed))
    }

    @Test
    fun `one moment written in two offsets produces one key`() {
        val utc = lesson(1, start = prototypeStart)
        val moscow = lesson(1, start = prototypeStart.withOffsetSameInstant(ZoneOffset.ofHours(3)))

        assertEquals(SportQueueRules.matchKey(utc), SportQueueRules.matchKey(moscow))
    }

    @Test
    fun `an explicitly online lesson matches only another explicitly online lesson`() {
        val online = SportPredictionSnapshot.fromLesson(lesson(1, buildingId = null, roomId = -1L))
        val target = prototypeStart.plusWeeks(2)

        assertNotNull(online.matchKey)
        assertTrue(SportQueueRules.matches(online, lesson(2, start = target, buildingId = -1L, roomId = -1L)))
        assertFalse(SportQueueRules.matches(online, lesson(2, start = target, buildingId = 3L, roomId = 4L)))
    }

    @Test
    fun `a venue that proves nothing yields no key and therefore matches nothing`() {
        val unusable = listOf(
            lesson(1, buildingId = null, roomId = 4L),
            lesson(1, buildingId = 0L, roomId = 4L),
            lesson(1, buildingId = 3L, roomId = 0L),
            lesson(1, buildingId = 3L, roomId = -1L),
            lesson(1, buildingId = null, roomId = 0L),
        )
        unusable.forEach { assertNull(SportQueueRules.matchKey(it)) }

        val prediction = SportPredictionSnapshot.fromLesson(unusable.first())
        assertNull(prediction.matchKey)
        assertFalse(SportQueueRules.matches(prediction, unusable.first()))
    }

    @Test
    fun `the frozen key describes the predicted lesson, not the prototype`() {
        val prototype = lesson(1)
        val prediction = SportPredictionSnapshot.fromLesson(prototype)

        assertEquals(prototype.start.plusWeeks(2), prediction.predictedStart)
        assertEquals(prototype.end.plusWeeks(2), prediction.predictedEnd)
        assertEquals(prototype.start, prediction.start)
        assertEquals(SportQueueRules.matchKey(lesson(2, start = prototype.start.plusWeeks(2))), prediction.matchKey)
        assertNotEquals(SportQueueRules.matchKey(prototype), prediction.matchKey)
    }

    private fun lesson(
        id: Long,
        start: OffsetDateTime = prototypeStart,
        sectionId: Long = 1L,
        sectionLevel: Long = 1L,
        lessonLevel: Long = 2L,
        typeId: Long = 5L,
        timeSlotId: Long = 6L,
        buildingId: Long? = 3L,
        teacherIsu: Long = 7L,
        roomId: Long = 4L,
        sectionName: String = "Section",
        roomName: String = "Room",
        durationMinutes: Long = 60L,
    ): SportLesson = SportLesson(
        id = id,
        section = SportSection(sectionId, "Section"),
        sectionLevel = sectionLevel,
        lessonLevel = lessonLevel,
        typeId = typeId,
        sectionName = sectionName,
        timeSlot = SportTimeSlot(timeSlotId, "10:00", "11:30"),
        buildingId = buildingId,
        teacher = SportTeacher(teacherIsu, "Teacher"),
        roomId = roomId,
        roomName = roomName,
        start = start,
        end = start.plusMinutes(durationMinutes),
        lastSeenAt = Instant.EPOCH,
    )
}
