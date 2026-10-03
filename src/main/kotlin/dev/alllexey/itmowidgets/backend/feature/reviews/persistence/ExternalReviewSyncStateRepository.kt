package dev.alllexey.itmowidgets.backend.feature.reviews.persistence

import dev.alllexey.itmowidgets.backend.feature.reviews.model.ExternalReviewSyncStateEntity
import dev.alllexey.itmowidgets.backend.feature.reviews.model.ReviewProvider
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant

interface ExternalReviewSyncStateRepository : JpaRepository<ExternalReviewSyncStateEntity, ReviewProvider> {
    /** Applying a snapshot holds one transaction-scoped lock per provider. */
    @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(hashtextextended('reviews:' || :provider, 0))", nativeQuery = true)
    fun lockProvider(provider: String): Int

    /** Exactly one caller takes a free or stale lease. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        """
        UPDATE ExternalReviewSyncStateEntity s SET s.runningSince = :now
        WHERE s.provider = :provider AND (s.runningSince IS NULL OR s.runningSince < :staleBefore)
    """,
    )
    fun claim(provider: ReviewProvider, now: Instant, staleBefore: Instant): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE ExternalReviewSyncStateEntity s SET s.runningSince = NULL WHERE s.provider = :provider AND s.runningSince IS NOT NULL")
    fun release(provider: ReviewProvider): Int
}
