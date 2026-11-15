package dev.alllexey.itmowidgets.backend.feature.reviews.service

import dev.alllexey.itmowidgets.backend.feature.reviews.model.ReviewStats
import dev.alllexey.itmowidgets.backend.feature.reviews.model.ReviewStatsDay
import dev.alllexey.itmowidgets.backend.feature.reviews.persistence.ReviewStatisticsRepository
import dev.alllexey.itmowidgets.backend.platform.stats.StatsPeriod
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** The review figures of a statistics period: four aggregate queries in one read-only transaction, no rows loaded. */
@Service
class ReviewStatistics(private val repository: ReviewStatisticsRepository) {
    @Transactional(readOnly = true)
    fun stats(period: StatsPeriod): ReviewStats {
        val reviews = period.zeroFilled(repository.reviewsPerDay(period))
        val votes = period.zeroFilled(repository.ownReviewVotesPerDay(period))
        val externalVotes = period.zeroFilled(repository.externalReviewVotesPerDay(period))
        return ReviewStats(
            ownReviews = repository.ownReviews(),
            days = reviews.mapIndexed { index, day -> ReviewStatsDay(day.day, day.count, votes[index].count, externalVotes[index].count) },
        )
    }
}
