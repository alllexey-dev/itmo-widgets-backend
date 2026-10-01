package dev.alllexey.itmowidgets.backend.services

import api.myitmo.model.sport.SportFilters
import api.myitmo.model.sport.TimeSlot
import dev.alllexey.itmowidgets.backend.dto.SportCatalogUpdateResult
import dev.alllexey.itmowidgets.backend.exceptions.SafeDiagnostics
import dev.alllexey.itmowidgets.backend.model.SportBuilding
import dev.alllexey.itmowidgets.backend.model.SportLesson
import dev.alllexey.itmowidgets.backend.model.SportSection
import dev.alllexey.itmowidgets.backend.model.SportTeacher
import dev.alllexey.itmowidgets.backend.model.SportTimeSlot
import dev.alllexey.itmowidgets.backend.model.SportUpdateErrorCategory
import dev.alllexey.itmowidgets.backend.model.SportUpdateLog
import dev.alllexey.itmowidgets.backend.model.SportUpdateOutcome
import dev.alllexey.itmowidgets.backend.repositories.SportBuildingRepository
import dev.alllexey.itmowidgets.backend.repositories.SportLessonRepository
import dev.alllexey.itmowidgets.backend.repositories.SportSectionRepository
import dev.alllexey.itmowidgets.backend.repositories.SportTeacherRepository
import dev.alllexey.itmowidgets.backend.repositories.SportTimeSlotRepository
import dev.alllexey.itmowidgets.backend.repositories.SportUpdateLogRepository
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import api.myitmo.model.sport.SportLesson as ApiSportLesson

/** Commits catalog data before queue processing; neither HTTP nor FCM runs in this transaction. */
@Service
@Transactional(propagation = Propagation.REQUIRES_NEW)
class SportCatalogService(
    private val lessons: SportLessonRepository,
    private val logs: SportUpdateLogRepository,
    private val sections: SportSectionRepository,
    private val buildings: SportBuildingRepository,
    private val teachers: SportTeacherRepository,
    private val timeSlots: SportTimeSlotRepository,
    private val clock: Clock,
) {
    private val reportedReasons: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun applyTimeSlots(incoming: List<TimeSlot>) {
        val mapped = validatedUnique(
            incoming,
            kind = "time slot",
            rowId = { it.id },
            mapper = { SportTimeSlot(it.id, persistedText("time_start", it.timeStart), persistedText("time_end", it.timeEnd)) },
            id = SportTimeSlot::id,
        )
        upsertDictionary(mapped, timeSlots, SportTimeSlot::id, SportTimeSlot::refreshFrom)
    }

    fun applyFilters(incoming: SportFilters) {
        val mappedBuildings = validatedUnique(
            incoming.buildingId.orEmpty(),
            kind = "building",
            rowId = { it.id },
            mapper = { SportBuilding(it.id, persistedText("value", it.value)) },
            id = SportBuilding::id,
        )
        val mappedSections = validatedUnique(
            incoming.sectionId.orEmpty(),
            kind = "section",
            rowId = { it.id },
            mapper = { SportSection(it.id, persistedText("value", it.value)) },
            id = SportSection::id,
        )
        val mappedTeachers = validatedUnique(
            incoming.teacherIsu.orEmpty(),
            kind = "teacher",
            // A teacher row's ID is the person's ISU, so the log names only the reason.
            rowId = null,
            mapper = { SportTeacher(it.id, persistedText("value", it.value)) },
            id = SportTeacher::isu,
        )
        upsertDictionary(mappedBuildings, buildings, SportBuilding::id, SportBuilding::refreshFrom)
        upsertDictionary(mappedSections, sections, SportSection::id, SportSection::refreshFrom)
        upsertDictionary(mappedTeachers, teachers, SportTeacher::isu, SportTeacher::refreshFrom)
    }

    /** Accepted known and new IDs retain zero capacity; missing or negative capacity triggers no queue action. */
    fun applySnapshot(
        incoming: List<ApiSportLesson>,
        startedAtNanos: Long = System.nanoTime(),
    ): SportCatalogUpdateResult {
        // Java deserialization can put null elements into its otherwise non-null generic list.
        val wireRows: List<ApiSportLesson?> = incoming
        val existing = lessons.findAllById(wireRows.mapNotNull { it?.id }.toSet()).associateBy { it.id }
        val sectionMap = sections.findAllById(wireRows.mapNotNull { it?.sectionId }.toSet()).associateByTo(mutableMapOf()) { it.id }
        val teacherMap = teachers.findAllById(wireRows.mapNotNull { it?.teacherIsu }.toSet()).associateByTo(mutableMapOf()) { it.isu }
        val slotMap = timeSlots.findAllById(wireRows.mapNotNull { it?.timeSlotId }.toSet()).associateByTo(mutableMapOf()) { it.id }
        addUnlistedReferences(wireRows.filterNotNull(), sectionMap, teacherMap, slotMap)
        val seenAt = clock.instant()
        val capacities = linkedMapOf<Long, Long>()
        val accepted = validatedUnique(
            incoming,
            kind = "lesson",
            rowId = { it.id },
            mapper = { row ->
                val start = required("date", row.date)
                val end = required("date_end", row.dateEnd)
                if (!end.isAfter(start)) reject("date_end is not after date", "date=$start date_end=$end")
                val sectionId = required("section_id", row.sectionId)
                val teacherIsu = required("teacher_isu", row.teacherIsu)
                val timeSlotId = required("time_slot_id", row.timeSlotId)
                val mapped = SportLesson(
                    id = row.id,
                    section = sectionMap[sectionId] ?: reject("section_id=$sectionId is unlisted and section_name is unusable"),
                    sectionLevel = required("section_level", row.sectionLevel),
                    lessonLevel = required("lesson_level", row.lessonLevel),
                    typeId = required("type_id", row.typeId),
                    sectionName = persistedText("section_name", row.sectionName),
                    timeSlot = slotMap[timeSlotId] ?: reject("time_slot_id=$timeSlotId is unlisted and time_slot_start/end are unusable"),
                    buildingId = row.buildingId,
                    teacher = teacherMap[teacherIsu] ?: reject("teacher_isu is unlisted and teacher_fio is unusable"),
                    roomId = required("room_id", row.roomId),
                    roomName = persistedText("room_name", row.roomName, allowBlank = true),
                    start = start,
                    end = end,
                    lastSeenAt = seenAt,
                )
                mapped to row.available?.takeIf { it >= 0 }
            },
            id = { it.first.id },
        )

        val additions = mutableListOf<SportLesson>()
        var updatedLessons = 0
        for ((mapped, capacity) in accepted) {
            val previous = existing[mapped.id]
            if (previous == null) additions.add(mapped) else if (previous.refreshFrom(mapped)) updatedLessons++
            // A completely validated row wins before touching its managed predecessor.
            if (capacity != null) capacities[mapped.id] = capacity
        }
        val persisted = lessons.saveAllAndFlush(additions)
        val result = SportCatalogUpdateResult(
            capacities = capacities.toMap(),
            receivedLessons = incoming.size,
            newLessonsAdded = persisted.size,
            updatedLessons = updatedLessons,
            skippedLessons = incoming.size - accepted.size,
        )
        val partial = result.skippedLessons > 0
        logs.save(SportUpdateLog(
            updateTimestamp = seenAt,
            outcome = if (partial) SportUpdateOutcome.PARTIAL else SportUpdateOutcome.SUCCESS,
            durationMillis = elapsedSportUpdateMillis(startedAtNanos),
            receivedLessons = result.receivedLessons,
            newLessonsAdded = result.newLessonsAdded,
            updatedLessons = result.updatedLessons,
            skippedLessons = result.skippedLessons,
            errorCategory = if (partial) SportUpdateErrorCategory.MAPPING else null,
            newLessons = persisted.toMutableList(),
        ))
        // Omission is never evidence of cancellation, including partial and empty responses.
        return result
    }

    /**
     * The filters may list a section or teacher days after its lessons appear in the schedule,
     * so a lesson adds a missing reference from its own fields rather than being dropped; the
     * hourly dictionary refresh later replaces the name with the filters' wording. Existing
     * entries are never touched here.
     */
    private fun addUnlistedReferences(
        rows: List<ApiSportLesson>,
        sectionMap: MutableMap<Long, SportSection>,
        teacherMap: MutableMap<Long, SportTeacher>,
        slotMap: MutableMap<Long, SportTimeSlot>,
    ) {
        val newSections = linkedMapOf<Long, SportSection>()
        val newTeachers = linkedMapOf<Long, SportTeacher>()
        val newSlots = linkedMapOf<Long, SportTimeSlot>()
        for (row in rows) {
            row.sectionId?.takeIf { it !in sectionMap && it !in newSections }?.let { id ->
                usableText(row.sectionName)?.let { newSections[id] = SportSection(id, it) }
            }
            row.teacherIsu?.takeIf { it !in teacherMap && it !in newTeachers }?.let { isu ->
                usableText(row.teacherFio)?.let { newTeachers[isu] = SportTeacher(isu, it) }
            }
            row.timeSlotId?.takeIf { it !in slotMap && it !in newSlots }?.let { id ->
                val start = usableText(row.timeSlotStart)
                val end = usableText(row.timeSlotEnd)
                if (start != null && end != null) newSlots[id] = SportTimeSlot(id, start, end)
            }
        }
        if (newSections.isEmpty() && newTeachers.isEmpty() && newSlots.isEmpty()) return
        // saveAll merges entities with assigned IDs, so lessons must reference the returned instances.
        sections.saveAll(newSections.values).associateByTo(sectionMap) { it.id }
        teachers.saveAll(newTeachers.values).associateByTo(teacherMap) { it.isu }
        timeSlots.saveAll(newSlots.values).associateByTo(slotMap) { it.id }
        logger.info(
            "Sport catalog added unlisted references from lessons: {} sections, {} teachers, {} time slots",
            newSections.size, newTeachers.size, newSlots.size,
        )
    }

    private fun <T : Any> upsertDictionary(
        incoming: List<T>,
        repository: JpaRepository<T, Long>,
        id: (T) -> Long,
        refresh: (T, T) -> Boolean,
    ) {
        val existing = repository.findAllById(incoming.map(id)).associateBy(id)
        val additions = mutableListOf<T>()
        for (mapped in incoming) {
            val previous = existing[id(mapped)]
            if (previous == null) additions.add(mapped) else refresh(previous, mapped)
        }
        repository.saveAll(additions)
    }

    /** An invalid first occurrence cannot hide a later valid row; subsequent valid duplicates are ignored. */
    private fun <W : Any, T : Any> validatedUnique(
        incoming: List<W>,
        kind: String,
        rowId: ((W) -> Long)?,
        mapper: (W) -> T,
        id: (T) -> Long,
    ): List<T> {
        val accepted = linkedMapOf<Long, T>()
        val wireRows: List<W?> = incoming
        for (row in wireRows) {
            val mapped = try {
                mapper(row ?: reject("row is null"))
            } catch (error: Exception) {
                reportRejection(kind, row?.let { rowId?.invoke(it) }, error)
                continue
            }
            accepted.putIfAbsent(id(mapped), mapped)
        }
        return accepted.values.toList()
    }

    /**
     * Upstream repeats a bad row on every ten-minute refresh, so each distinct reason is one WARN
     * per process and DEBUG afterwards; the per-run count stays in `sport_update_logs`.
     */
    private fun reportRejection(kind: String, rowId: Long?, error: Exception) {
        val rejected = error as? RejectedRow
        val reason = rejected?.reason ?: SafeDiagnostics.describe(error)
        val row = rowId?.let { "$kind $it" } ?: kind
        val detail = rejected?.detail?.let { " ($it)" }.orEmpty()
        val firstTime = reportedReasons.size < MAX_REPORTED_REASONS && reportedReasons.add("$kind: $reason")
        if (firstTime) {
            logger.warn("Sport {} rejected: {}{}; repeats are logged at DEBUG", row, reason, detail)
        } else {
            logger.debug("Sport {} rejected: {}{}", row, reason, detail)
        }
        if (rejected == null) logger.debug("Sport {} mapping failure detail", kind, error)
    }

    private fun persistedText(field: String, value: String?, allowBlank: Boolean = false): String {
        val normalized = required(field, value).trim()
        if (!allowBlank && normalized.isBlank()) reject("$field is blank")
        if ('\u0000' in normalized) reject("$field contains NUL")
        if (normalized.codePointCount(0, normalized.length) > MAX_TEXT_CODE_POINTS) {
            reject("$field is longer than $MAX_TEXT_CODE_POINTS code points")
        }
        return normalized
    }

    private fun usableText(value: String?): String? = try {
        persistedText("text", value)
    } catch (unusable: RejectedRow) {
        null
    }

    private fun <V : Any> required(field: String, value: V?): V = value ?: reject("$field is null")

    private fun reject(reason: String, detail: String? = null): Nothing = throw RejectedRow(reason, detail)

    /**
     * Our own wording plus field names, catalog IDs and times, never names or another person's ID,
     * so unlike an arbitrary exception message it may be logged.
     */
    private class RejectedRow(val reason: String, val detail: String?) : RuntimeException(reason, null, false, false)

    companion object {
        private val logger = LoggerFactory.getLogger(SportCatalogService::class.java)
        private const val MAX_TEXT_CODE_POINTS = 255
        private const val MAX_REPORTED_REASONS = 256
    }
}
