package dev.alllexey.itmowidgets.backend.feature.sport.service

import dev.alllexey.itmowidgets.backend.feature.sport.model.SportQueueKind
import dev.alllexey.itmowidgets.backend.feature.sport.persistence.SportStatisticsRepository
import dev.alllexey.itmowidgets.backend.platform.stats.FeatureUsage
import dev.alllexey.itmowidgets.backend.platform.stats.StatsPeriod
import dev.alllexey.itmowidgets.backend.platform.stats.UsageSource
import org.springframework.stereotype.Component

// The sport rows of the feature-use statistics: what was created in the period and by whom.

/** `SPORT_AUTO_SIGN`: auto-sign entries created. */
@Component
class SportAutoSignUsage(private val repository: SportStatisticsRepository) : UsageSource {
    override val feature = "SPORT_AUTO_SIGN"

    override fun usage(period: StatsPeriod): FeatureUsage = repository.entryUsage(SportQueueKind.AUTO, period)
}

/** `SPORT_FREE_SIGN`: free-sign (queue) entries created. */
@Component
class SportFreeSignUsage(private val repository: SportStatisticsRepository) : UsageSource {
    override val feature = "SPORT_FREE_SIGN"

    override fun usage(period: StatsPeriod): FeatureUsage = repository.entryUsage(SportQueueKind.FREE, period)
}

/** `SPORT_SECTIONS`: section subscriptions created. */
@Component
class SportSectionsUsage(private val repository: SportStatisticsRepository) : UsageSource {
    override val feature = "SPORT_SECTIONS"

    override fun usage(period: StatsPeriod): FeatureUsage = repository.subscriptionUsage(period)
}
