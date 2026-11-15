package dev.alllexey.itmowidgets.backend.feature.credentials.service

import dev.alllexey.itmowidgets.backend.platform.metrics.DailyMetric

/**
 * The [MyItmoGateway] calls, counted per day (docs/contracts/server-statistics.md): [METRIC] with [counterName], the
 * gateway method, as dim1 and [OK] or a [MyItmoFailureKind] name as dim2. A call that throws instead of returning a
 * result (a failed credential write, a bug) is not counted.
 */
enum class MyItmoOperation(val counterName: String) {
    SPORT_TIME_SLOTS("sportTimeSlots"),
    SPORT_FILTERS("sportFilters"),
    SPORT_SCHEDULE("sportSchedule"),
    SPORT_SIGN_LIMITS("sportSignLimits"),
    PERSONALITY("personality"),
    ;

    companion object {
        val METRIC = DailyMetric("myitmo")

        /** dim2 of a call that returned a [MyItmoResult.Success]. */
        const val OK = "OK"
    }
}
