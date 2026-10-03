package dev.alllexey.itmowidgets.backend.feature.sport.persistence

import dev.alllexey.itmowidgets.backend.feature.admin.persistence.SportErrorRow
import dev.alllexey.itmowidgets.backend.feature.admin.persistence.SportOutcomeRow
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportUpdateLog
import dev.alllexey.itmowidgets.backend.feature.sport.model.SportUpdateOutcome
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

interface SportUpdateLogRepository : JpaRepository<SportUpdateLog, Long> {
    /** Deletes only technical logs; their association rows are removed by the database FK. */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query(
        nativeQuery = true,
        value = """
            WITH expired_logs AS (
                SELECT id FROM sport_update_logs
                WHERE update_timestamp < :cutoff
                ORDER BY update_timestamp, id
                LIMIT :batchSize
            )
            DELETE FROM sport_update_logs logs
            USING expired_logs
            WHERE logs.id = expired_logs.id
        """,
    )
    fun deleteBatchBefore(@Param("cutoff") cutoff: Instant, @Param("batchSize") batchSize: Int): Int

    fun findTop50ByOrderByUpdateTimestampDescIdDesc(): List<SportUpdateLog>

    @Query(
        """
        SELECT new dev.alllexey.itmowidgets.backend.feature.admin.persistence.SportOutcomeRow(l.outcome, COUNT(l), SUM(l.durationMillis))
        FROM SportUpdateLog l WHERE l.updateTimestamp >= :since GROUP BY l.outcome
    """,
    )
    fun countOutcomesSince(since: Instant): List<SportOutcomeRow>

    @Query(
        """
        SELECT new dev.alllexey.itmowidgets.backend.feature.admin.persistence.SportErrorRow(l.errorCategory, COUNT(l))
        FROM SportUpdateLog l WHERE l.updateTimestamp >= :since AND l.errorCategory IS NOT NULL GROUP BY l.errorCategory
    """,
    )
    fun countErrorsSince(since: Instant): List<SportErrorRow>

    @Query("SELECT MAX(l.updateTimestamp) FROM SportUpdateLog l WHERE l.outcome = :outcome")
    fun findLastAt(outcome: SportUpdateOutcome): Instant?
}
