package dev.alllexey.itmowidgets.backend.feature.moderation.service

import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationStats
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationStatsDay
import dev.alllexey.itmowidgets.backend.feature.moderation.persistence.ModerationStatisticsRepository
import dev.alllexey.itmowidgets.backend.platform.stats.StatsPeriod
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** The moderation figures of a statistics period: five aggregate queries in one read-only transaction, no rows loaded. */
@Service
class ModerationStatistics(private val repository: ModerationStatisticsRepository) {
    @Transactional(readOnly = true)
    fun stats(period: StatsPeriod): ModerationStats {
        val cases = repository.casesPerDay(period)
        check(cases.map { it.day } == period.days) { "The case series must have every day of $period" }
        val reports = period.zeroFilled(repository.reportsPerDay(period))
        return ModerationStats(
            days = cases.mapIndexed { index, day ->
                ModerationStatsDay(day.day, day.opened, day.resolved, day.withdrawn, day.backlog, reports[index].count)
            },
            cases = repository.cases(period),
            decisions = repository.decisions(period),
            reports = repository.reports(period),
        )
    }
}
