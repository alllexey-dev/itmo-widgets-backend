package dev.alllexey.itmowidgets.backend.feature.push.web

/** The FCM registration to detach from the authenticated user during logout. */
data class UnregisterDeviceRequest(val fcmToken: String)
