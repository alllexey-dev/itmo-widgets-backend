package dev.alllexey.itmowidgets.backend.feature.push.web

import dev.alllexey.itmowidgets.backend.feature.app.model.AppPlatform

/**
 * `fcmToken` is the field name released clients send; they send nothing else, so an absent [platform] means
 * `ANDROID`, an absent [alertsAllowed] means `true`. [appVersion] is the app's version name, at most 32 characters.
 */
data class RegisterDeviceRequest(
    val fcmToken: String,
    val deviceName: String,
    val platform: AppPlatform? = null,
    val alertsAllowed: Boolean? = null,
    val appVersion: String? = null,
)
