package dev.alllexey.itmowidgets.backend.feature.push.service

import dev.alllexey.itmowidgets.backend.feature.app.model.AppPlatform
import java.util.UUID

/** Immutable transport snapshot; its token must never be included in diagnostics. */
data class DeviceDeliveryTarget(
    val deviceId: UUID,
    val fcmToken: String,
    val recipientIsu: Int = 0,
    val platform: AppPlatform = AppPlatform.ANDROID,
) {
    override fun toString(): String = "DeviceDeliveryTarget(deviceId=$deviceId)"
}
