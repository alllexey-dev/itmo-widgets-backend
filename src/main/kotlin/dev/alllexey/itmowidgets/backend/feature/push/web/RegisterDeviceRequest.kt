package dev.alllexey.itmowidgets.backend.feature.push.web

/** `fcmToken` is the field name released clients send. */
data class RegisterDeviceRequest(val fcmToken: String, val deviceName: String)
