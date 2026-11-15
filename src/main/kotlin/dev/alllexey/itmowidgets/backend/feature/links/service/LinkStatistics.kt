package dev.alllexey.itmowidgets.backend.feature.links.service

import dev.alllexey.itmowidgets.backend.feature.links.persistence.LinkStatisticsRepository
import dev.alllexey.itmowidgets.backend.platform.stats.DayCount
import dev.alllexey.itmowidgets.backend.platform.stats.StatsPeriod
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** The subject link figures of a statistics period: one aggregate query, no rows loaded. */
@Service
class LinkStatistics(private val repository: LinkStatisticsRepository) {
    /**
     * Link votes that still stand, one row per day of the period by the day they were first cast: taking a vote back
     * deletes it, changing its value keeps its day.
     */
    @Transactional(readOnly = true)
    fun votesPerDay(period: StatsPeriod): List<DayCount> = period.zeroFilled(repository.votesPerDay(period))
}
