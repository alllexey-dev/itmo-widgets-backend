package dev.alllexey.itmowidgets.backend.dto

import java.util.UUID

/** Immutable transport snapshot; its token must never be included in diagnostics. */
data class DeviceDeliveryTarget(
    val deviceId: UUID,
    val fcmToken: String,
    val recipientIsu: Int = 0,
) {
    override fun toString(): String = "DeviceDeliveryTarget(deviceId=$deviceId)"
}
