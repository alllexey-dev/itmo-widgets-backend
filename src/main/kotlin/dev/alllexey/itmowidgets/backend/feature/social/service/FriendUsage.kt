package dev.alllexey.itmowidgets.backend.feature.social.service

import dev.alllexey.itmowidgets.backend.feature.social.persistence.SocialStatisticsRepository
import dev.alllexey.itmowidgets.backend.platform.stats.FeatureUsage
import dev.alllexey.itmowidgets.backend.platform.stats.StatsPeriod
import dev.alllexey.itmowidgets.backend.platform.stats.UsageSource
import org.springframework.stereotype.Component

/**
 * `FRIENDS`, the friendship row of the feature-use statistics: friend requests sent in the period, and the requester
 * and the addressee of every request sent or accepted in it.
 */
@Component
class FriendUsage(private val repository: SocialStatisticsRepository) : UsageSource {
    override val feature = "FRIENDS"

    override fun usage(period: StatsPeriod): FeatureUsage = repository.friendUsage(period)
}
