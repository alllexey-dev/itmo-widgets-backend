package dev.alllexey.itmowidgets.backend.feature.users.web

import dev.alllexey.itmowidgets.backend.feature.users.model.SharingVisibility

/** Own settings only. All values are required on PUT; absent values never widen access. */
data class UserPrivacySettings(
    val scheduleVisibility: SharingVisibility,
    val sportVisibility: SharingVisibility,
    val friendsVisibility: SharingVisibility,
)
