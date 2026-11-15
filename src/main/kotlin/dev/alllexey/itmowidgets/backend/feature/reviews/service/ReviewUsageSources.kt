package dev.alllexey.itmowidgets.backend.feature.reviews.service

import dev.alllexey.itmowidgets.backend.feature.reviews.persistence.ReviewStatisticsRepository
import dev.alllexey.itmowidgets.backend.platform.stats.FeatureUsage
import dev.alllexey.itmowidgets.backend.platform.stats.StatsPeriod
import dev.alllexey.itmowidgets.backend.platform.stats.UsageSource
import org.springframework.stereotype.Component

// The review rows of the feature-use statistics: what was created in the period and by whom.

/** `REVIEWS`: own teacher reviews created. */
@Component
class ReviewUsage(private val repository: ReviewStatisticsRepository) : UsageSource {
    override val feature = "REVIEWS"

    override fun usage(period: StatsPeriod): FeatureUsage = repository.reviewUsage(period)
}

/** `REVIEW_VOTES`: votes cast on own reviews and on Reviews copies that still stand. */
@Component
class ReviewVoteUsage(private val repository: ReviewStatisticsRepository) : UsageSource {
    override val feature = "REVIEW_VOTES"

    override fun usage(period: StatsPeriod): FeatureUsage = repository.voteUsage(period)
}
