package dev.alllexey.itmowidgets.backend.platform.metrics

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import java.sql.ResultSet
import java.time.LocalDate

/**
 * Sums of `stat_daily` for one metric over the Europe/Moscow days [from]..[to], both inclusive. Only what was counted
 * comes back: a day or a label without a row is absent, and the caller zero-fills. Counts of the current minute are
 * still in memory and appear after the next flush.
 */
@Service
class DailyCounterReader(private val jdbc: NamedParameterJdbcTemplate) {
    /** The total per day. */
    fun byDay(metric: DailyMetric, from: LocalDate, to: LocalDate): Map<LocalDate, Long> =
        sums(metric, from, to, "day") { it.getObject("day", LocalDate::class.java) to it.getLong("total") }.toMap()

    /** Per day, the total per dim2. */
    fun byDayAndDim2(metric: DailyMetric, from: LocalDate, to: LocalDate): Map<LocalDate, Map<String, Long>> = nested(
        sums(metric, from, to, "day, dim2") {
            Triple(it.getObject("day", LocalDate::class.java), it.getString("dim2"), it.getLong("total"))
        },
    )

    /** The total per dim1 over the whole range. */
    fun byDim1(metric: DailyMetric, from: LocalDate, to: LocalDate): Map<String, Long> =
        sums(metric, from, to, "dim1") { it.getString("dim1") to it.getLong("total") }.toMap()

    /** Per dim1, the total per dim2 over the whole range. */
    fun byDim1AndDim2(metric: DailyMetric, from: LocalDate, to: LocalDate): Map<String, Map<String, Long>> =
        nested(sums(metric, from, to, "dim1, dim2") { Triple(it.getString("dim1"), it.getString("dim2"), it.getLong("total")) })

    /** The first day with any counter of any metric; null before the first flush. */
    fun firstDay(): LocalDate? = jdbc.jdbcTemplate.queryForObject("SELECT min(day) FROM stat_daily", LocalDate::class.java)

    // Only the fixed column lists above enter the SQL; the metric and the days are parameters.
    private fun <T> sums(metric: DailyMetric, from: LocalDate, to: LocalDate, columns: String, row: (ResultSet) -> T): List<T> {
        require(!from.isAfter(to)) { "from must not be after to" }
        return jdbc.query(
            """
            SELECT $columns, sum(value) AS total FROM stat_daily
            WHERE metric = :metric AND day BETWEEN :from AND :to
            GROUP BY $columns ORDER BY $columns
            """,
            mapOf("metric" to metric.name, "from" to from, "to" to to),
        ) { resultSet, _ -> row(resultSet) }
    }

    private fun <K> nested(rows: List<Triple<K, String, Long>>): Map<K, Map<String, Long>> =
        rows.groupBy({ it.first }, { it.second to it.third }).mapValues { (_, sums) -> sums.toMap() }
}
