package dev.alllexey.itmowidgets.backend.feature.credentials.model

import dev.alllexey.itmowidgets.backend.feature.credentials.service.MyItmoFailureKind
import dev.alllexey.itmowidgets.backend.platform.stats.DayCount

/**
 * The calls of the technical My ITMO credential in a statistics period, from the `MyItmoOperation` counters: per day
 * every counted call ([calls]) and the failed ones ([failures]), each holding only the days with a count; and the
 * whole period per operation ([operations], by calls descending, then by operation).
 */
data class MyItmoStats(val calls: List<DayCount>, val failures: List<DayCount>, val operations: List<MyItmoOperationStats>)

/** One gateway operation as it was counted (its counter name), with every [MyItmoFailureKind] a key of [failures]. */
data class MyItmoOperationStats(val operation: String, val calls: Long, val failures: Map<MyItmoFailureKind, Long>)
