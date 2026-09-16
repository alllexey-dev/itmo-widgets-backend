package dev.alllexey.itmowidgets.backend.dto

import dev.alllexey.itmowidgets.backend.model.SharingVisibility

/** Own settings only. All values are required on PUT; absent values never widen access. */
data class UserPrivacySettings(
    val scheduleVisibility: SharingVisibility,
    val sportVisibility: SharingVisibility,
    val friendsVisibility: SharingVisibility,
)
