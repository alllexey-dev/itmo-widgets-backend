package dev.alllexey.itmowidgets.backend.feature.links.service

import dev.alllexey.itmowidgets.backend.feature.links.persistence.LinkStatisticsRepository
import dev.alllexey.itmowidgets.backend.platform.stats.FeatureUsage
import dev.alllexey.itmowidgets.backend.platform.stats.StatsPeriod
import dev.alllexey.itmowidgets.backend.platform.stats.UsageSource
import org.springframework.stereotype.Component

// The subject link rows of the feature-use statistics: what was created in the period and by whom.

/** `LINKS`: subject links created. */
@Component
class LinkUsage(private val repository: LinkStatisticsRepository) : UsageSource {
    override val feature = "LINKS"

    override fun usage(period: StatsPeriod): FeatureUsage = repository.linkUsage(period)
}

/** `LINK_VOTES`: votes cast on subject links that still stand. */
@Component
class LinkVoteUsage(private val repository: LinkStatisticsRepository) : UsageSource {
    override val feature = "LINK_VOTES"

    override fun usage(period: StatsPeriod): FeatureUsage = repository.voteUsage(period)
}
