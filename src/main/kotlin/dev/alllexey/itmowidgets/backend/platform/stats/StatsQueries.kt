package dev.alllexey.itmowidgets.backend.platform.stats

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.sql.ResultSet
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.math.roundToLong

// The SQL side of a statistics period, shared by the statistics repositories of the features.

/**
 * The named parameters of a statistics query over [this] period: `:start` and `:end`, the instants of
 * [StatsPeriod.startInstant] and [StatsPeriod.endInstantExclusive] as `timestamptz`; `:from` and `:to`, the days as
 * `date`; `:zone`, the zone id for `AT TIME ZONE`.
 */
fun StatsPeriod.sqlParameters(): Map<String, Any> = mapOf(
    "start" to startInstant.atOffset(ZoneOffset.UTC),
    "end" to endInstantExclusive.atOffset(ZoneOffset.UTC),
    "from" to from,
    "to" to to,
    "zone" to zone.id,
)

/** A daily series query: a `day` (`date`) and a `count` column per row, any day at most once. */
fun NamedParameterJdbcTemplate.queryDays(sql: String, period: StatsPeriod): List<DayCount> =
    query(sql, period.sqlParameters()) { rs, _ -> DayCount(rs.getObject("day", LocalDate::class.java), rs.getLong("count")) }

/** A feature-use query: one row per user with a `user_id` and the `events` that user caused; [FeatureUsage.events] is their sum. */
fun NamedParameterJdbcTemplate.queryUsage(sql: String, period: StatsPeriod): FeatureUsage {
    val perUser = query(sql, period.sqlParameters()) { rs, _ -> rs.getObject("user_id", UUID::class.java) to rs.getLong("events") }
    return FeatureUsage(events = perUser.sumOf { it.second }, users = perUser.mapTo(HashSet()) { it.first })
}

/** A feature-use query of a feature without a countable event: one `user_id` per row. */
fun NamedParameterJdbcTemplate.queryUsers(sql: String, period: StatsPeriod): FeatureUsage =
    FeatureUsage(events = null, users = query(sql, period.sqlParameters()) { rs, _ -> rs.getObject("user_id", UUID::class.java) }.toSet())

/** A duration in seconds such as a `percentile_cont` or an `EXTRACT(EPOCH ...)`, rounded to whole seconds; null over no rows. */
fun ResultSet.seconds(column: String): Long? = getObject(column)?.let { (it as Number).toDouble().roundToLong() }
