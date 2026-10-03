package dev.alllexey.itmowidgets.backend.feature.schedule.web

import java.time.LocalDate
import java.time.LocalTime

data class LessonSyncRequest(val lessons: List<LessonDto>, val from: LocalDate, val to: LocalDate)

/** A MyITMO schedule lesson as the app uploads it; the numeric IDs are MyITMO's. */
data class LessonDto(
    val pairId: Long,
    val date: LocalDate,
    val start: LocalTime,
    val end: LocalTime,
    /** MyITMO `Lesson.workType`. */
    val type: String,
    /** MyITMO `Lesson.workTypeId`: 1 lecture, 2 lab, 3 practice, 5 exam, 6 credit, 10 consultation, 11 sport. */
    val typeId: Int,
    val note: String?,
    val subjectName: String,
    val subjectId: Long,
    val groupName: String,
    val flowId: Long,
    /** 2 lessons, 3 sport, 5 room booking. */
    val flowTypeId: Int,
    val teacherIsu: Long?,
    val teacherFio: String?,
    val room: String?,
    val building: String?,
    val buildingId: Int?,
    /** 13 Kronverksky, 273 Lomonosova, 5 Vyazemsky, 319 virtual rooms. */
    val mainBuildingId: Int?,
    val format: String,
    /** 1 in person, 2 hybrid, 3 remote. */
    val formatId: Int,
)
