package dev.alllexey.itmowidgets.backend.model

import dev.alllexey.itmowidgets.core.model.SportLessonDto
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.OffsetDateTime

@Entity
@Table(name = "sport_lessons")
class SportLesson(
    @Id
    val id: Long,

    @ManyToOne
    val section: SportSection,

    val sectionLevel: Long,

    val lessonLevel: Long,

    val typeId: Long,

    val sectionName: String,

    @ManyToOne(fetch = FetchType.EAGER)
    val timeSlot: SportTimeSlot,

    @ManyToOne
    val building: SportBuilding,

    @ManyToOne
    val teacher: SportTeacher,

    val roomId: Long,

    val roomName: String,

    val start: OffsetDateTime,

    val end: OffsetDateTime
) {

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