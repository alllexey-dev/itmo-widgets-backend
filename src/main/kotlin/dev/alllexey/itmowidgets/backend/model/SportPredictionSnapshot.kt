package dev.alllexey.itmowidgets.backend.model

import dev.alllexey.itmowidgets.core.model.SportLessonDto
import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import java.time.OffsetDateTime

/**
 * The original selected prototype, independent of future catalog/reference updates.
 * Start/end retain prototype dates; the predicted lesson is exactly two weeks later.
 */
@Embeddable
class SportPredictionSnapshot(
    @Column(name = "target_section_id", nullable = false, updatable = false)
    val sectionId: Long,

    @Column(name = "target_section_name", nullable = false, updatable = false, length = 255)
    val sectionName: String,

    @Column(name = "target_section_level", nullable = false, updatable = false)
    val sectionLevel: Long,

    @Column(name = "target_lesson_level", nullable = false, updatable = false)
    val lessonLevel: Long,

    @Column(name = "target_type_id", nullable = false, updatable = false)
    val typeId: Long,

    @Column(name = "target_time_slot_id", nullable = false, updatable = false)
    val timeSlotId: Long,

    @Column(name = "target_building_id", updatable = false)
    val buildingId: Long?,

    @Column(name = "target_teacher_isu", nullable = false, updatable = false)
    val teacherIsu: Long,

    @Column(name = "target_teacher_name", nullable = false, updatable = false, length = 255)
    val teacherName: String,

    @Column(name = "target_room_id", nullable = false, updatable = false)
    val roomId: Long,

    @Column(name = "target_room_name", nullable = false, updatable = false, length = 255)
    val roomName: String,

    @Column(name = "target_starts_at", nullable = false, updatable = false)
    val start: OffsetDateTime,

    @Column(name = "target_ends_at", nullable = false, updatable = false)
    val end: OffsetDateTime,
) {
    fun toDto(prototypeLessonId: Long): SportLessonDto = SportLessonDto(
        id = prototypeLessonId,
        sectionId = sectionId,
        sectionName = sectionName,
        sectionLevel = sectionLevel,
        level = lessonLevel,
        typeId = typeId,
        buildingId = buildingId,
        roomName = roomName,
        start = start,
        end = end,
        timeSlotId = timeSlotId,
        teacherIsu = teacherIsu,
        teacherFio = teacherName,
    )

    companion object {
        fun fromLesson(prototype: SportLesson): SportPredictionSnapshot = SportPredictionSnapshot(
            sectionId = prototype.section.id,
            sectionName = prototype.sectionName.trim(),
            sectionLevel = prototype.sectionLevel,
            lessonLevel = prototype.lessonLevel,
            typeId = prototype.typeId,
            timeSlotId = prototype.timeSlot.id,
            buildingId = prototype.buildingId,
            teacherIsu = prototype.teacher.isu,
            teacherName = prototype.teacher.name.trim(),
            roomId = prototype.roomId,
            roomName = prototype.roomName.trim(),
            start = prototype.start,
            end = prototype.end,
        )
    }
}
