package dev.alllexey.itmowidgets.backend.model

import dev.alllexey.itmowidgets.core.model.SportLessonDto
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.Instant
import java.time.OffsetDateTime

@Entity
@Table(name = "sport_lessons")
class SportLesson(
    @Id
    val id: Long,
    section: SportSection,
    sectionLevel: Long,
    lessonLevel: Long,
    typeId: Long,
    sectionName: String,
    timeSlot: SportTimeSlot,
    building: SportBuilding,
    teacher: SportTeacher,
    roomId: Long,
    roomName: String,
    start: OffsetDateTime,
    end: OffsetDateTime,
    lastSeenAt: Instant,
) {
    @ManyToOne
    var section: SportSection = section
        protected set

    var sectionLevel: Long = sectionLevel
        protected set

    var lessonLevel: Long = lessonLevel
        protected set

    var typeId: Long = typeId
        protected set

    var sectionName: String = sectionName
        protected set

    @ManyToOne(fetch = FetchType.EAGER)
    var timeSlot: SportTimeSlot = timeSlot
        protected set

    @ManyToOne
    var building: SportBuilding = building
        protected set

    @ManyToOne
    var teacher: SportTeacher = teacher
        protected set

    var roomId: Long = roomId
        protected set

    var roomName: String = roomName
        protected set

    @Column(name = "starts_at")
    var start: OffsetDateTime = start
        protected set

    @Column(name = "ends_at")
    var end: OffsetDateTime = end
        protected set

    @Column(name = "last_seen_at", nullable = false)
    var lastSeenAt: Instant = lastSeenAt
        protected set

    /** Refreshes an existing identity without replacing references held by queues or bookings. */
    fun refreshFrom(incoming: SportLesson): Boolean {
        require(id == incoming.id)
        val changed = section.id != incoming.section.id || sectionLevel != incoming.sectionLevel ||
            lessonLevel != incoming.lessonLevel || typeId != incoming.typeId || sectionName != incoming.sectionName ||
            timeSlot.id != incoming.timeSlot.id || building.id != incoming.building.id ||
            teacher.isu != incoming.teacher.isu || roomId != incoming.roomId || roomName != incoming.roomName ||
            !start.isEqual(incoming.start) || !end.isEqual(incoming.end)
        if (changed) {
            section = incoming.section
            sectionLevel = incoming.sectionLevel
            lessonLevel = incoming.lessonLevel
            typeId = incoming.typeId
            sectionName = incoming.sectionName
            timeSlot = incoming.timeSlot
            building = incoming.building
            teacher = incoming.teacher
            roomId = incoming.roomId
            roomName = incoming.roomName
            start = incoming.start
            end = incoming.end
        }
        // A successful observation is not itself a semantic lesson update.
        lastSeenAt = incoming.lastSeenAt
        return changed
    }

    companion object {
        fun SportLesson.toDto(): SportLessonDto {
            return SportLessonDto(
                id = id,
                sectionId = section.id,
                sectionName = sectionName,
                sectionLevel = sectionLevel,
                level = lessonLevel,
                buildingId = building.id,
                roomName = roomName,
                start = start,
                end = end,
                timeSlotId = timeSlot.id,
                teacherIsu = teacher.isu,
                teacherFio = teacher.name,
                typeId = typeId
            )
        }
    }
}
