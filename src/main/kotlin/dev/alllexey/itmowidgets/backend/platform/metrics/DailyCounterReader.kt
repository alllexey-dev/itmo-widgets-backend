package dev.alllexey.itmowidgets.backend.platform.metrics

import dev.alllexey.itmowidgets.backend.platform.stats.StatsPeriod
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import java.sql.ResultSet
import java.time.LocalDate

/**
 * Sums of `stats_daily` for one metric over the days of a [StatsPeriod]. Only what was counted comes back: a day or a
 * label without a row is absent, and the caller zero-fills. Counts of the current minute are still in memory and
 * appear after the next flush.
 */
@Service
class DailyCounterReader(private val jdbc: NamedParameterJdbcTemplate) {
    /** Per day, the total per dim2. */
    fun byDayAndDim2(metric: DailyMetric, period: StatsPeriod): Map<LocalDate, Map<String, Long>> = nested(
        sums(metric, period, "day, dim2") {
            Triple(it.getObject("day", LocalDate::class.java), it.getString("dim2"), it.getLong("total"))
        },
    )

    /** Per dim1, the total per dim2 over the whole period. */
    fun byDim1AndDim2(metric: DailyMetric, period: StatsPeriod): Map<String, Map<String, Long>> =
        nested(sums(metric, period, "dim1, dim2") { Triple(it.getString("dim1"), it.getString("dim2"), it.getLong("total")) })

    /** The first day with any counter of any metric; null before the first flush. */
    fun firstDay(): LocalDate? = jdbc.jdbcTemplate.queryForObject("SELECT min(day) FROM stats_daily", LocalDate::class.java)

    // Only the fixed column lists above enter the SQL; the metric and the days are parameters.
    private fun <T> sums(metric: DailyMetric, period: StatsPeriod, columns: String, row: (ResultSet) -> T): List<T> = jdbc.query(
        """
        SELECT $columns, sum(value) AS total FROM stats_daily
        WHERE metric = :metric AND day BETWEEN :from AND :to
        GROUP BY $columns ORDER BY $columns
        """,
        mapOf("metric" to metric.name, "from" to period.from, "to" to period.to),
    ) { resultSet, _ -> row(resultSet) }

    private fun <K> nested(rows: List<Triple<K, String, Long>>): Map<K, Map<String, Long>> =
        rows.groupBy({ it.first }, { it.second to it.third }).mapValues { (_, sums) -> sums.toMap() }
}
