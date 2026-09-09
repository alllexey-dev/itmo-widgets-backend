package dev.alllexey.itmowidgets.backend.repositories

import api.myitmo.model.IdValuePair
import api.myitmo.model.sport.SportFilters
import api.myitmo.model.sport.TimeSlot
import api.myitmo.model.sport.SportLesson as ApiSportLesson
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException

class SportCatalogPersistenceTest : SportQueuePersistenceTest() {
    @Test
    fun `known lesson refreshes every source field without replacing identity or dependent rows`() {
        val oldRefs = references()
        val start = OffsetDateTime.now(clock).plusHours(3)
        val target = reserveLessonId()
        assertEquals(mapOf(target to 0L), catalog.applySnapshot(listOf(apiLesson(target, start, oldRefs))))
        val user = owner()
        val autoEntry = auto(user, target)
        val freeEntry = free(user, target)
        bookings.syncLessons(user, listOf(target))
        val bookingId = jdbc.queryForObject("SELECT id FROM user_sport_lessons WHERE user_id=? AND lesson_id=?", Long::class.java, user, target)
        val newRefs = references()
        clock.advance(Duration.ofMinutes(10))
        val changed = apiLesson(target, start.plusMinutes(20), newRefs).apply {
            sectionLevel = 2L
            lessonLevel = 3L
            typeId = 5L
            sectionName = "  Updated lesson section  "
            roomId = 77L
            roomName = "  Updated hall  "
            dateEnd = date.plusMinutes(90)
            available = 4L
        }

        assertEquals(mapOf(target to 4L), catalog.applySnapshot(listOf(changed)))

        assertEquals(LessonRow(
            id = target, section = newRefs.id, sectionLevel = 2, level = 3, type = 5,
            sectionName = "Updated lesson section", slot = newRefs.id, building = newRefs.id,
            teacher = newRefs.id, room = 77, roomName = "Updated hall", start = changed.date.toInstant(),
            end = changed.dateEnd.toInstant(), lastSeen = clock.instant(),
        ), row(target))
        assertEquals(target, jdbc.queryForObject("SELECT prototype_lesson_id FROM sport_auto_sign_entries WHERE id=?", Long::class.java, autoEntry.entryId))
        assertEquals(target, jdbc.queryForObject("SELECT lesson_id FROM sport_free_sign_entries WHERE id=?", Long::class.java, freeEntry.entryId))
        assertEquals(bookingId, jdbc.queryForObject("SELECT id FROM user_sport_lessons WHERE user_id=? AND lesson_id=?", Long::class.java, user, target))
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM sport_lessons WHERE id=?", Long::class.java, target))
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM sport_update_logs_new_lessons WHERE new_lessons_id=?", Long::class.java, target))
    }

    @Test
    fun `identical snapshot only advances last seen and retains zero capacity`() {
        val refs = references()
        val id = reserveLessonId()
        val incoming = apiLesson(id, OffsetDateTime.now(clock).plusHours(3), refs)
        catalog.applySnapshot(listOf(incoming))
        val original = row(id)
        clock.advance(Duration.ofMinutes(10))

        assertEquals(mapOf(id to 0L), catalog.applySnapshot(listOf(incoming)))

        assertEquals(original.copy(lastSeen = clock.instant()), row(id))
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM sport_update_logs_new_lessons WHERE new_lessons_id=?", Long::class.java, id))
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM sport_lessons WHERE id=?", Long::class.java, id))
    }

    @Test
    fun `invalid existing rows never partially overwrite fields or last seen`() {
        val refs = references()
        val id = reserveLessonId()
        val start = OffsetDateTime.now(clock).plusHours(3)
        catalog.applySnapshot(listOf(apiLesson(id, start, refs)))
        val original = row(id)
        clock.advance(Duration.ofMinutes(10))
        val invalidations: List<(ApiSportLesson) -> Unit> = listOf(
            { it.sectionId = Long.MAX_VALUE },
            { it.buildingId = Long.MAX_VALUE },
            { it.teacherIsu = Long.MAX_VALUE },
            { it.timeSlotId = Long.MAX_VALUE },
            { it.sectionLevel = null },
            { it.lessonLevel = null },
            { it.typeId = null },
            { it.roomId = null },
            { it.date = null },
            { it.dateEnd = null },
            { it.dateEnd = it.date },
            { it.dateEnd = it.date.minusMinutes(1) },
            { it.sectionName = null },
            { it.sectionName = "   " },
            { it.roomName = null },
            { it.sectionName = "x".repeat(256) },
            { it.roomName = "x".repeat(256) },
            { it.sectionName = "invalid\u0000name" },
            { it.roomName = "invalid\u0000room" },
        )
        for ((index, invalidate) in invalidations.withIndex()) {
            val invalid = apiLesson(id, start.plusMinutes(20), refs).apply {
                sectionName = "Must not be partially applied"
                roomName = "Changed but rejected"
                invalidate(this)
            }
            assertEquals(emptyMap(), catalog.applySnapshot(listOf(invalid)), "Invalid case $index")
            assertEquals(original, row(id), "Invalid case $index changed stored state")
        }
    }

    @Test
    fun `unknown building never falls back to an existing zero building`() {
        val refs = references()
        val oldZeroName = jdbc.queryForList("SELECT name FROM sport_buildings WHERE id=0", String::class.java).singleOrNull()
        val start = OffsetDateTime.now(clock).plusHours(3)
        val existing = reserveLessonId()
        val absent = reserveLessonId()
        val explicitZero = reserveLessonId()
        try {
            catalog.applyFilters(SportFilters().apply { buildingId = listOf(pair(0, "Unknown building")) })
            catalog.applySnapshot(listOf(apiLesson(existing, start, refs)))
            val original = row(existing)
            clock.advance(Duration.ofMinutes(10))

            val result = catalog.applySnapshot(listOf(
                apiLesson(existing, start, refs).apply { buildingId = Long.MAX_VALUE },
                apiLesson(absent, start, refs).apply { buildingId = Long.MAX_VALUE },
                apiLesson(explicitZero, start, refs).apply { buildingId = 0L },
            ))

            assertEquals(mapOf(explicitZero to 0L), result)
            assertEquals(original, row(existing))
            assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM sport_lessons WHERE id=?", Long::class.java, absent))
            assertEquals(0L, row(explicitZero).building)
        } finally {
            jdbc.update("DELETE FROM sport_update_logs WHERE id IN (SELECT sport_update_log_id FROM sport_update_logs_new_lessons WHERE new_lessons_id=?)", explicitZero)
            jdbc.update("DELETE FROM sport_lessons WHERE id=?", explicitZero)
            if (oldZeroName == null) jdbc.update("DELETE FROM sport_buildings WHERE id=0")
            else jdbc.update("UPDATE sport_buildings SET name=? WHERE id=0", oldZeroName)
        }
    }

    @Test
    fun `Unicode limit counts codepoints and an unknown empty room name remains valid`() {
        val refs = references()
        val id = reserveLessonId()
        val start = OffsetDateTime.now(clock).plusHours(3)
        val unicodeName = "\uD83D\uDE00".repeat(255)
        val incoming = apiLesson(id, start, refs).apply {
            sectionName = "  $unicodeName  "
            roomName = "   "
        }

        assertEquals(mapOf(id to 0L), catalog.applySnapshot(listOf(incoming)))

        assertEquals(unicodeName, row(id).sectionName)
        assertEquals("", row(id).roomName)
        val accepted = row(id)
        clock.advance(Duration.ofMinutes(1))
        assertEquals(emptyMap(), catalog.applySnapshot(listOf(apiLesson(id, start, refs).apply {
            sectionName = unicodeName + "\uD83D\uDE00"
        })))
        assertEquals(accepted, row(id))
    }

    @Test
    fun `invalid first duplicate does not hide valid later row and first valid occurrence wins`() {
        val refs = references()
        val id = reserveLessonId()
        val start = OffsetDateTime.now(clock).plusHours(3)
        val invalid = apiLesson(id, start, refs).apply { roomName = null }
        val firstValid = apiLesson(id, start, refs).apply { sectionName = "First valid"; available = 3L }
        val laterValid = apiLesson(id, start, refs).apply { sectionName = "Ignored duplicate"; available = 9L }

        assertEquals(mapOf(id to 3L), catalog.applySnapshot(listOf(invalid, firstValid, laterValid)))

        assertEquals("First valid", row(id).sectionName)
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM sport_lessons WHERE id=?", Long::class.java, id))
    }

    @Test
    fun `malformed Java null element is skipped before ID extraction without hiding good row`() {
        val refs = references()
        val id = reserveLessonId()
        val start = OffsetDateTime.now(clock).plusHours(3)

        assertEquals(mapOf(id to 0L), catalog.applySnapshot(wireRows(null, apiLesson(id, start, refs))))

        assertEquals(clock.instant(), row(id).lastSeen)
    }

    @Test
    fun `partial empty and entirely invalid snapshots preserve omitted catalog rows`() {
        val refs = references()
        val first = reserveLessonId()
        val second = reserveLessonId()
        val start = OffsetDateTime.now(clock).plusHours(3)
        catalog.applySnapshot(listOf(apiLesson(first, start, refs), apiLesson(second, start, refs)))
        val originalSecond = row(second)
        clock.advance(Duration.ofMinutes(10))

        catalog.applySnapshot(listOf(apiLesson(first, start, refs).apply { roomName = "Changed first" }))
        val updatedFirst = row(first)
        assertEquals("Changed first", updatedFirst.roomName)
        assertEquals(originalSecond, row(second))
        assertEquals(emptyMap(), catalog.applySnapshot(emptyList()))
        assertEquals(emptyMap(), catalog.applySnapshot(listOf(apiLesson(second, start, refs).apply { date = null })))
        assertEquals(updatedFirst, row(first))
        assertEquals(originalSecond, row(second))
    }

    @Test
    fun `unknown and malformed capacities do not invent free places or block valid metadata refresh`() {
        val refs = references()
        val first = reserveLessonId()
        val second = reserveLessonId()
        val third = reserveLessonId()
        val start = OffsetDateTime.now(clock).plusHours(3)

        assertEquals(mapOf(third to 0L), catalog.applySnapshot(listOf(
            apiLesson(first, start, refs).apply { available = null },
            apiLesson(second, start, refs).apply { available = -1L },
            apiLesson(third, start, refs),
        )))

        listOf(first, second, third).forEach { assertEquals(clock.instant(), row(it).lastSeen) }
        clock.advance(Duration.ofMinutes(10))
        assertEquals(emptyMap(), catalog.applySnapshot(listOf(apiLesson(first, start, refs).apply {
            available = null
            roomName = "Changed despite unknown capacity"
        })))
        assertEquals("Changed despite unknown capacity", row(first).roomName)
        assertEquals(clock.instant(), row(first).lastSeen)
    }

    @Test
    fun `database failure rolls back the complete refresh and retains previous catalog`() {
        val refs = references()
        val existing = reserveLessonId()
        val rejected = reserveLessonId()
        val start = OffsetDateTime.now(clock).plusHours(3)
        catalog.applySnapshot(listOf(apiLesson(existing, start, refs)))
        val original = row(existing)
        clock.advance(Duration.ofMinutes(10))
        val constraint = "test_rejected_catalog_$rejected"
        jdbc.execute("ALTER TABLE sport_lessons ADD CONSTRAINT $constraint CHECK (id <> $rejected) NOT VALID")
        try {
            assertFailsWith<DataIntegrityViolationException> {
                catalog.applySnapshot(listOf(
                    apiLesson(existing, start.plusMinutes(20), refs),
                    apiLesson(rejected, start, refs),
                ))
            }
            assertEquals(original, row(existing))
            assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM sport_lessons WHERE id=?", Long::class.java, rejected))
        } finally {
            jdbc.execute("ALTER TABLE sport_lessons DROP CONSTRAINT IF EXISTS $constraint")
        }
    }

    @Test
    fun `dictionary names and both slot fields refresh in place and are trimmed`() {
        val refs = references()
        val target = reserveLessonId()
        catalog.applySnapshot(listOf(apiLesson(target, OffsetDateTime.now(clock).plusHours(3), refs)))

        catalog.applyFilters(filters(refs.id, "  Changed building  ", "  Changed section  ", "  Changed teacher  "))
        catalog.applyTimeSlots(listOf(slot(refs.id, "  08:20  ", "  09:50  ")))

        assertEquals("Changed building", label("sport_buildings", "id", refs.id))
        assertEquals("Changed section", label("sport_sections", "id", refs.id))
        assertEquals("Changed teacher", label("sport_teachers", "isu", refs.id))
        assertEquals(listOf("08:20", "09:50"), slotTimes(refs.id))
        assertEquals(refs.id, row(target).building)
        assertEquals(refs.id, row(target).section)
        assertEquals(refs.id, row(target).teacher)
        assertEquals(refs.id, row(target).slot)
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM sport_buildings WHERE id=?", Long::class.java, refs.id))
    }

    @Test
    fun `invalid dictionary fields never partially replace existing names or slot times`() {
        val refs = references()
        val originalBuilding = label("sport_buildings", "id", refs.id)
        val originalSection = label("sport_sections", "id", refs.id)
        val originalTeacher = label("sport_teachers", "isu", refs.id)
        val originalSlot = slotTimes(refs.id)
        for (invalid in listOf(null, "  ", "x".repeat(256), "bad\u0000name")) {
            catalog.applyFilters(filters(refs.id, invalid, invalid, invalid))
            catalog.applyTimeSlots(listOf(slot(refs.id, "New first field", invalid)))
            catalog.applyTimeSlots(listOf(slot(refs.id, invalid, "New second field")))
            assertEquals(originalBuilding, label("sport_buildings", "id", refs.id))
            assertEquals(originalSection, label("sport_sections", "id", refs.id))
            assertEquals(originalTeacher, label("sport_teachers", "isu", refs.id))
            assertEquals(originalSlot, slotTimes(refs.id))
        }
        catalog.applyFilters(SportFilters())
        catalog.applyTimeSlots(emptyList())
        assertEquals(originalBuilding, label("sport_buildings", "id", refs.id))
        assertEquals(originalSlot, slotTimes(refs.id))
    }

    @Test
    fun `dictionary null rows and invalid duplicates do not hide a valid update`() {
        val refs = references()
        catalog.applyFilters(SportFilters().apply {
            buildingId = wireRows(null, pair(refs.id, null), pair(refs.id, "First valid"), pair(refs.id, "Ignored duplicate"))
        })
        catalog.applyTimeSlots(wireRows(null, slot(refs.id, null, "bad"), slot(refs.id, "09:00", "10:00")))

        assertEquals("First valid", label("sport_buildings", "id", refs.id))
        assertEquals(listOf("09:00", "10:00"), slotTimes(refs.id))
    }

    private fun references(): References {
        // Separate IDs keep updates to these dictionaries isolated from other tests' shared seed references.
        val id = 100_000_000L + reserveLessonId()
        catalog.applyFilters(filters(id, "Building $id", "Section $id", "Teacher $id"))
        catalog.applyTimeSlots(listOf(slot(id, "12:00", "13:00")))
        return References(id)
    }

    private fun apiLesson(id: Long, start: OffsetDateTime, references: References) = ApiSportLesson().apply {
        this.id = id
        date = start
        dateEnd = start.plusHours(1)
        sectionId = references.id
        sectionLevel = 1L
        lessonLevel = 1L
        typeId = 1L
        sectionName = "  Synthetic section  "
        timeSlotId = references.id
        buildingId = references.id
        teacherIsu = references.id
        roomId = 10L
        roomName = "  Synthetic room  "
        available = 0L
    }

    private fun filters(id: Long, building: String?, section: String?, teacher: String?) = SportFilters().apply {
        buildingId = listOf(pair(id, building))
        sectionId = listOf(pair(id, section))
        teacherIsu = listOf(pair(id, teacher))
    }

    private fun pair(id: Long, text: String?) = IdValuePair().apply { this.id = id; value = text }

    private fun slot(id: Long, start: String?, end: String?) = TimeSlot().apply {
        this.id = id
        timeStart = start
        timeEnd = end
    }

    /** Model the malformed element Java/Gson may return, without widening the production API. */
    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> wireRows(vararg rows: T?): List<T> = rows.toList() as List<T>

    private fun row(id: Long): LessonRow = jdbc.queryForObject("SELECT * FROM sport_lessons WHERE id=?", { rs, _ ->
        LessonRow(
            id = rs.getLong("id"), section = rs.getLong("section_id"), sectionLevel = rs.getLong("section_level"),
            level = rs.getLong("lesson_level"), type = rs.getLong("type_id"), sectionName = rs.getString("section_name"),
            slot = rs.getLong("time_slot_id"), building = rs.getLong("building_id"), teacher = rs.getLong("teacher_isu"),
            room = rs.getLong("room_id"), roomName = rs.getString("room_name"),
            start = rs.getObject("starts_at", OffsetDateTime::class.java).toInstant(),
            end = rs.getObject("ends_at", OffsetDateTime::class.java).toInstant(),
            lastSeen = rs.getObject("last_seen_at", OffsetDateTime::class.java).toInstant(),
        )
    }, id)!!

    private fun label(table: String, key: String, id: Long): String =
        jdbc.queryForObject("SELECT name FROM $table WHERE $key=?", String::class.java, id)!!

    private fun slotTimes(id: Long): List<String> = jdbc.queryForObject(
        "SELECT time_start,time_end FROM sport_time_slots WHERE id=?", { rs, _ -> listOf(rs.getString(1), rs.getString(2)) }, id,
    )!!

    private data class References(val id: Long)

    private data class LessonRow(
        val id: Long,
        val section: Long,
        val sectionLevel: Long,
        val level: Long,
        val type: Long,
        val sectionName: String,
        val slot: Long,
        val building: Long,
        val teacher: Long,
        val room: Long,
        val roomName: String,
        val start: Instant,
        val end: Instant,
        val lastSeen: Instant,
    )
}
