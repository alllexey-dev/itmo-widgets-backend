package dev.alllexey.itmowidgets.backend.dto


/** Confirmed current/upcoming IDs plus active queues, authorized together by canViewSport. */
data class UserSportBookingsResponse(
    val lessonIds: List<Long>,
    val entries: List<SportQueueEntry> = emptyList(),
)
