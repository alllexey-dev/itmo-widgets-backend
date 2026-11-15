package dev.alllexey.itmowidgets.backend.feature.schedule.service

import dev.alllexey.itmowidgets.backend.feature.schedule.persistence.ScheduleStatisticsRepository
import dev.alllexey.itmowidgets.backend.platform.stats.FeatureUsage
import dev.alllexey.itmowidgets.backend.platform.stats.StatsPeriod
import dev.alllexey.itmowidgets.backend.platform.stats.UsageSource
import org.springframework.stereotype.Component

/**
 * `SCHEDULE`, the schedule row of the feature-use statistics: users with an uploaded lesson dated in the period. The
 * app uploads the schedule on its own, so the row has no events and is not an [ownAction]. An upload replaces the
 * lessons of its range, so a lesson no longer in MyITMO no longer counts.
 */
@Component
class ScheduleUsage(private val repository: ScheduleStatisticsRepository) : UsageSource {
    override val feature = "SCHEDULE"

    override val ownAction = false

    override fun usage(period: StatsPeriod): FeatureUsage = repository.scheduleUsage(period)
}
