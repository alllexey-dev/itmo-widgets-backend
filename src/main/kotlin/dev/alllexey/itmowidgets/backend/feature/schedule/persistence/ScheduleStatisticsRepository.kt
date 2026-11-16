package dev.alllexey.itmowidgets.backend.feature.schedule.persistence

import dev.alllexey.itmowidgets.backend.platform.stats.StatsPeriod
import dev.alllexey.itmowidgets.backend.platform.stats.sqlParameters
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

/** Aggregates over uploaded lessons for statistics. Every method is one query and returns no lessons. */
@Repository
class ScheduleStatisticsRepository(private val jdbc: NamedParameterJdbcTemplate) {
    /** The ISUs with an uploaded lesson dated in the period: lessons name their user by ISU, not by account. */
    fun isusWithLessons(period: StatsPeriod): List<Int> = jdbc.queryForList(
        "SELECT DISTINCT user_isu FROM schedule_lessons WHERE date BETWEEN :from AND :to",
        period.sqlParameters(),
        Int::class.java,
    )
}
