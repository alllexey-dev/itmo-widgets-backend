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
    fun applyTimeSlots(incoming: List<TimeSlot>) {
        val mapped = validatedUnique(
            incoming,
            kind = "time slot",
            mapper = { SportTimeSlot(it.id, persistedText(it.timeStart), persistedText(it.timeEnd)) },
            id = SportTimeSlot::id,
        )
        upsertDictionary(mapped, timeSlots, SportTimeSlot::id, SportTimeSlot::refreshFrom)
    }

    fun applyFilters(incoming: SportFilters) {
        val mappedBuildings = validatedUnique(
            incoming.buildingId.orEmpty(),
            kind = "building",
            mapper = { SportBuilding(it.id, persistedText(it.value)) },
            id = SportBuilding::id,
        )
        val mappedSections = validatedUnique(
            incoming.sectionId.orEmpty(),
            kind = "section",
            mapper = { SportSection(it.id, persistedText(it.value)) },
            id = SportSection::id,
        )
        val mappedTeachers = validatedUnique(
            incoming.teacherIsu.orEmpty(),
            kind = "teacher",
            mapper = { SportTeacher(it.id, persistedText(it.value)) },
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
        val sectionMap = sections.findAllById(wireRows.mapNotNull { it?.sectionId }.toSet()).associateBy { it.id }
        val buildingMap = buildings.findAllById(wireRows.mapNotNull { it?.buildingId }.toSet()).associateBy { it.id }
        val teacherMap = teachers.findAllById(wireRows.mapNotNull { it?.teacherIsu }.toSet()).associateBy { it.isu }
        val slotMap = timeSlots.findAllById(wireRows.mapNotNull { it?.timeSlotId }.toSet()).associateBy { it.id }
        val seenAt = clock.instant()
        val capacities = linkedMapOf<Long, Long>()
        val accepted = validatedUnique(
            incoming,
            kind = "lesson",
            mapper = { row ->
                val start = requireNotNull(row.date)
                val end = requireNotNull(row.dateEnd)
                require(end.isAfter(start))
                val mapped = SportLesson(
                    id = row.id,
                    section = requireNotNull(sectionMap[row.sectionId]),
                    sectionLevel = requireNotNull(row.sectionLevel),
                    lessonLevel = requireNotNull(row.lessonLevel),
                    typeId = requireNotNull(row.typeId),
                    sectionName = persistedText(row.sectionName),
                    timeSlot = requireNotNull(slotMap[row.timeSlotId]),
                    building = requireNotNull(buildingMap[row.buildingId]),
                    teacher = requireNotNull(teacherMap[row.teacherIsu]),
                    roomId = requireNotNull(row.roomId),
                    roomName = persistedText(row.roomName, allowBlank = true),
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
        mapper: (W) -> T,
        id: (T) -> Long,
    ): List<T> {
        val accepted = linkedMapOf<Long, T>()
        val wireRows: List<W?> = incoming
        for (row in wireRows) {
            val mapped = try {
                mapper(requireNotNull(row))
            } catch (error: Exception) {
                logger.warn("Sport {} mapping failed: {}", kind, SafeDiagnostics.describe(error))
                continue
            }
            accepted.putIfAbsent(id(mapped), mapped)
        }
        return accepted.values.toList()
    }

    private fun persistedText(value: String?, allowBlank: Boolean = false): String {
        val normalized = requireNotNull(value).trim()
        require(allowBlank || normalized.isNotBlank())
        require('\u0000' !in normalized)
        require(normalized.codePointCount(0, normalized.length) <= 255)
        return normalized
    }

    companion object {
        private val logger = LoggerFactory.getLogger(SportCatalogService::class.java)
    }
}
