package dev.alllexey.itmowidgets.backend.feature.push.model

import dev.alllexey.itmowidgets.backend.platform.stats.DayCount

/**
 * The pushes of a statistics period from the [PushOutcome] counters: per day the devices FCM accepted a message for
 * ([sent]), the ones it failed for ([failed]) and the registrations it reported gone ([tokenGone]), each holding only
 * the days with a count; and the whole period per FCM `type` ([types], by their total descending, then by type).
 */
data class PushStats(val sent: List<DayCount>, val failed: List<DayCount>, val tokenGone: List<DayCount>, val types: List<PushTypeStats>)

/** One FCM `type` as it was counted, with every [PushOutcome] a key of [outcomes]. */
data class PushTypeStats(val type: String, val outcomes: Map<PushOutcome, Long>)
