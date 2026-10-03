package dev.alllexey.itmowidgets.backend.feature.users.web

/** What this authenticated viewer may read; self access remains true for every owner audience. */
data class UserCapabilities(
    val canViewSchedule: Boolean,
    val canViewSport: Boolean,
    val canViewFriends: Boolean,
)
