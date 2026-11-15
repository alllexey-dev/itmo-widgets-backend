package dev.alllexey.itmowidgets.backend.feature.social.model

import dev.alllexey.itmowidgets.backend.platform.metrics.DailyMetric

/**
 * The friendship changes counted per day (docs/contracts/server-statistics.md): [METRIC] with the action as dim1 and
 * an empty dim2. Only a change that happened counts: a repeated request or a no-op removal does not.
 */
enum class FriendshipAction {
    REQUESTED,

    /** Accepted explicitly or by a crossed request. */
    ACCEPTED,
    REJECTED,
    CANCELLED,
    REMOVED,
    ;

    companion object {
        val METRIC = DailyMetric("friends")
    }
}
