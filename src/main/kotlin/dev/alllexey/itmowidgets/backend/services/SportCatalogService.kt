package dev.alllexey.itmowidgets.backend.services

import api.myitmo.model.sport.SportFilters
import api.myitmo.model.sport.TimeSlot
import dev.alllexey.itmowidgets.backend.exceptions.SafeDiagnostics
import dev.alllexey.itmowidgets.backend.model.*
import dev.alllexey.itmowidgets.backend.repositories.*
import java.time.Clock
import org.slf4j.LoggerFactory
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
        val knownIds = timeSlots.findAll().map { it.id }.toSet()
        timeSlots.saveAll(incoming.distinctBy { it.id }.filter { it.id !in knownIds }.map(SportTimeSlot::fromApi))
    }

    fun applyFilters(incoming: SportFilters) {
        val buildingIds = buildings.findAll().map { it.id }.toSet()
        val sectionIds = sections.findAll().map { it.id }.toSet()
        val teacherIds = teachers.findAll().map { it.isu }.toSet()
        buildings.saveAll(incoming.buildingId.distinctBy { it.id }.filter { it.id !in buildingIds }.map(SportBuilding::fromApi))
        sections.saveAll(incoming.sectionId.distinctBy { it.id }.filter { it.id !in sectionIds }.map(SportSection::fromApi))
        teachers.saveAll(incoming.teacherIsu.distinctBy { it.id }.filter { it.id !in teacherIds }.map(SportTeacher::fromApi))
    }

    /** Returns only accepted catalog IDs with a known available-seat count, including zero. */
    fun applySnapshot(incoming: List<ApiSportLesson>): Map<Long, Long> {
        val knownIds = lessons.findAllIds()
        val sectionMap = sections.findAll().associateBy { it.id }
        val buildingMap = buildings.findAll().associateBy { it.id }
        val teacherMap = teachers.findAll().associateBy { it.isu }
        val slotMap = timeSlots.findAll().associateBy { it.id }
        val additions = mutableListOf<SportLesson>()
        val capacities = linkedMapOf<Long, Long>()

        for (row in incoming.distinctBy { it.id }) {
            val mapped = try {
                SportLesson(
                    id = row.id,
                    section = requireNotNull(sectionMap[row.sectionId]),
                    sectionLevel = row.sectionLevel,
                    lessonLevel = row.lessonLevel,
                    typeId = row.typeId,
                    sectionName = row.sectionName,
                    timeSlot = requireNotNull(slotMap[row.timeSlotId]),
                    building = requireNotNull(buildingMap[row.buildingId] ?: buildingMap[0]),
                    teacher = requireNotNull(teacherMap[row.teacherIsu]),
                    roomId = row.roomId,
                    roomName = row.roomName,
                    start = row.date,
                    end = row.dateEnd,
                )
            } catch (error: Exception) {
                logger.warn("Sport lesson mapping failed: {}", SafeDiagnostics.describe(error))
                continue
            }
            if (mapped.id !in knownIds) additions.add(mapped)
            // Unknown capacity is not evidence of either available seats or a full lesson.
            row.available?.let { capacities[mapped.id] = it }
        }

        val persisted = lessons.saveAllAndFlush(additions)
        logs.save(SportUpdateLog(
            updateTimestamp = clock.instant(),
            newLessonsAdded = persisted.size,
            newLessons = persisted.toMutableList(),
        ))
        return capacities.toMap()
    }

    companion object {
        private val logger = LoggerFactory.getLogger(SportCatalogService::class.java)
    }
}
