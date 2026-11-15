package dev.alllexey.itmowidgets.backend.feature.moderation.service

import dev.alllexey.itmowidgets.backend.feature.moderation.persistence.ModerationStatisticsRepository
import dev.alllexey.itmowidgets.backend.platform.stats.FeatureUsage
import dev.alllexey.itmowidgets.backend.platform.stats.StatsPeriod
import dev.alllexey.itmowidgets.backend.platform.stats.UsageSource
import org.springframework.stereotype.Component

/** `REPORTS`, the moderation row of the feature-use statistics: reports created in the period and their reporters. */
@Component
class ReportUsage(private val repository: ModerationStatisticsRepository) : UsageSource {
    override val feature = "REPORTS"

    override fun usage(period: StatsPeriod): FeatureUsage = repository.reportUsage(period)
}
