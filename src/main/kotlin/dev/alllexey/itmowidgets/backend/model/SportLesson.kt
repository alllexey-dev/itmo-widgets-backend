package dev.alllexey.itmowidgets.backend.model

import dev.alllexey.itmowidgets.core.model.BasicSportLessonData
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
        fun SportLesson.toBasicData(): BasicSportLessonData {
            return BasicSportLessonData(
                id = id,
                sectionId = section.id,
                sectionName = sectionName,
                sectionLevel = sectionLevel,
                lessonLevel = lessonLevel,
                buildingId = building.id,
                roomName = roomName,
                dateStart = start,
                dateEnd = end,
                timeSlotId = timeSlot.id,
                teacherIsu = teacher.isu,
                teacherFio = teacher.name
            )
        }
    }
}