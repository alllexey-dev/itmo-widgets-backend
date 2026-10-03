package dev.alllexey.itmowidgets.backend.dto

/** `fcmToken` is the field name released clients send. */
data class RegisterDeviceRequest(val fcmToken: String, val deviceName: String)
