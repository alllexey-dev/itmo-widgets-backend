package dev.alllexey.itmowidgets.backend.feature.reviews.model

import java.time.LocalDate

/**
 * The review figures of one statistics period: [ownReviews] is every own teacher review now, [days] has one row per
 * day of the period.
 */
data class ReviewStats(val ownReviews: Long, val days: List<ReviewStatsDay>)

/**
 * One Europe/Moscow day: own reviews created on it, and the votes on own reviews and on Reviews copies first cast on
 * it that still stand (a vote taken back is deleted, a changed one keeps its day).
 */
data class ReviewStatsDay(val day: LocalDate, val newReviews: Long, val reviewVotes: Long, val externalReviewVotes: Long)
