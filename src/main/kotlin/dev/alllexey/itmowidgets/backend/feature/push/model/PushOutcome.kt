package dev.alllexey.itmowidgets.backend.feature.push.model

import dev.alllexey.itmowidgets.backend.platform.metrics.DailyMetric

/**
 * What became of a push, counted per day (docs/contracts/server-statistics.md): [METRIC] with the FCM `type` as dim1
 * and the outcome as dim2. [SENT], [FAILED] and [TOKEN_GONE] are per device; [NO_DEVICE] once for a user without one.
 * [SENT] means FCM accepted the message: FCM reports no delivery to the device.
 */
enum class PushOutcome {
    SENT,
    FAILED,

    /** FCM answered `UNREGISTERED`: the registration is removed. */
    TOKEN_GONE,
    NO_DEVICE,
    ;

    companion object {
        val METRIC = DailyMetric("push")
    }
}
