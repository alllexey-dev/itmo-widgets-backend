package dev.alllexey.itmowidgets.backend.dto

import dev.alllexey.itmowidgets.core.model.SportQueueEntry

/** Confirmed current/upcoming IDs plus active queues, authorized together by canViewSport. */
data class UserSportBookingsResponse(
    val lessonIds: List<Long>,
    val entries: List<SportQueueEntry> = emptyList(),
)
