package dev.alllexey.itmowidgets.backend.feature.credentials.service

import java.time.OffsetDateTime

// MyITMO's sport catalog as it arrives: every field MyITMO may omit stays nullable, and the consumer validates it.

data class MyItmoTimeSlot(val id: Long, val timeStart: String?, val timeEnd: String?)

/** An entry of a filter list: a building, a section, or a teacher whose [id] is the ISU. */
data class MyItmoCatalogEntry(val id: Long, val value: String?)

data class MyItmoSportFilters(
    val buildings: List<MyItmoCatalogEntry?> = emptyList(),
    val sections: List<MyItmoCatalogEntry?> = emptyList(),
    val teachers: List<MyItmoCatalogEntry?> = emptyList(),
)

/** Property names follow the wire fields (`date`, `date_end`, `section_id`, ...). */
data class MyItmoSportLesson(
    val id: Long,
    val date: OffsetDateTime? = null,
    val dateEnd: OffsetDateTime? = null,
    val sectionId: Long? = null,
    val sectionName: String? = null,
    val sectionLevel: Long? = null,
    val lessonLevel: Long? = null,
    val typeId: Long? = null,
    val buildingId: Long? = null,
    val roomId: Long? = null,
    val roomName: String? = null,
    val available: Long? = null,
    val timeSlotId: Long? = null,
    val timeSlotStart: String? = null,
    val timeSlotEnd: String? = null,
    val teacherIsu: Long? = null,
    val teacherFio: String? = null,
)

data class MyItmoSportSignLimit(val limit: Int, val available: Int)
