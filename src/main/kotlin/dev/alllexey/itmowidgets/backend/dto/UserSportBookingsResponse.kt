package dev.alllexey.itmowidgets.backend.dto

/** Confirmed current/upcoming lesson IDs (end >= now), not history, scores, queues or predictions. */
data class UserSportBookingsResponse(val lessonIds: List<Long>)
