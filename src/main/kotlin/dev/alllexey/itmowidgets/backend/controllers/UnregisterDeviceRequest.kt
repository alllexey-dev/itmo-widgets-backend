package dev.alllexey.itmowidgets.backend.controllers

/** The FCM registration to detach from the authenticated user during logout. */
data class UnregisterDeviceRequest(
    val fcmToken: String
)
