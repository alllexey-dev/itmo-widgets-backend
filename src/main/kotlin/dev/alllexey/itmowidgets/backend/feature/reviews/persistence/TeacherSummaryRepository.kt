package dev.alllexey.itmowidgets.backend.feature.reviews.persistence

import dev.alllexey.itmowidgets.backend.feature.admin.persistence.LabelCount
import dev.alllexey.itmowidgets.backend.feature.reviews.model.SummaryConfidence
import dev.alllexey.itmowidgets.backend.feature.reviews.model.SummaryLevel
import dev.alllexey.itmowidgets.backend.feature.reviews.model.TeacherSummaryEntity
import org.springframework.data.domain.Limit
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

/** The input side of a summary row; [contentHash] is null when the row has no content. */
data class SummaryInputRow(val teacherIsu: Int, val inputHash: String?, val inputCount: Int, val contentHash: String?)

data class SummaryLevelRow(val teacherIsu: Int, val level: SummaryLevel)

/**
 * Writes to existing rows touch only their own columns, so an admin action and a run never overwrite each other.
 * Statuses are computed: `HIDDEN` when hidden, `READY` when the content matches the input, `FAILED` after a
 * rejected attempt and `PENDING` otherwise; rows without input that are not hidden have no status.
 */
interface TeacherSummaryRepository : JpaRepository<TeacherSummaryEntity, Int> {
    /** Levels of shown summaries the model was confident about. */
    @Query("""
        SELECT new dev.alllexey.itmowidgets.backend.feature.reviews.persistence.SummaryLevelRow(t.teacherIsu, t.level)
        FROM TeacherSummaryEntity t
        WHERE t.teacherIsu IN :isus AND t.content IS NOT NULL AND t.hiddenAt IS NULL AND t.inputHash IS NOT NULL
          AND t.confidence IN (dev.alllexey.itmowidgets.backend.feature.reviews.model.SummaryConfidence.MEDIUM,
              dev.alllexey.itmowidgets.backend.feature.reviews.model.SummaryConfidence.HIGH)
    """)
    fun findShownLevels(isus: Collection<Int>): List<SummaryLevelRow>

    @Query("""
        SELECT new dev.alllexey.itmowidgets.backend.feature.reviews.persistence.SummaryInputRow(t.teacherIsu, t.inputHash, t.inputCount, t.contentHash)
        FROM TeacherSummaryEntity t
    """)
    fun findInputRows(): List<SummaryInputRow>

    /** A new input restarts the attempts. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        UPDATE TeacherSummaryEntity t
        SET t.attempts = CASE WHEN t.inputHash = :hash THEN t.attempts ELSE 0 END,
            t.inputHash = :hash, t.inputCount = :count, t.updatedAt = :now
        WHERE t.teacherIsu = :isu AND (t.inputHash IS NULL OR t.inputHash <> :hash OR t.inputCount <> :count)
    """)
    fun updateInput(isu: Int, hash: String, count: Int, now: Instant): Int

    /** Teachers that are no longer eligible lose their input and content at once; hiding stays. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        UPDATE TeacherSummaryEntity t
        SET t.inputHash = NULL, t.inputCount = 0, t.content = NULL, t.contentHash = NULL, t.contentCount = NULL,
            t.level = NULL, t.confidence = NULL, t.model = NULL, t.generatedAt = NULL, t.requestedAt = NULL,
            t.attempts = 0, t.updatedAt = :now
        WHERE t.teacherIsu IN :isus AND (t.inputHash IS NOT NULL OR t.content IS NOT NULL)
    """)
    fun clearInput(isus: Collection<Int>, now: Instant): Int

    /**
     * The next teacher of a run: admin requests first, then the most reviewed. A teacher is tried at most once per
     * run unless an admin asks again after the attempt.
     */
    @Query("""
        SELECT t FROM TeacherSummaryEntity t
        WHERE t.inputHash IS NOT NULL AND t.hiddenAt IS NULL
          AND (t.requestedAt IS NOT NULL OR t.contentHash IS NULL OR t.contentHash <> t.inputHash)
          AND t.attempts < :maxAttempts
          AND (t.lastAttemptAt IS NULL OR t.lastAttemptAt < :runStartedAt
              OR (t.requestedAt IS NOT NULL AND t.lastAttemptAt < t.requestedAt))
        ORDER BY t.requestedAt ASC NULLS LAST, t.inputCount DESC, t.teacherIsu
    """)
    fun findNext(maxAttempts: Int, runStartedAt: Instant, limit: Limit): List<TeacherSummaryEntity>

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE TeacherSummaryEntity t SET t.lastAttemptAt = :now, t.updatedAt = :now WHERE t.teacherIsu = :isu")
    fun markAttempt(isu: Int, now: Instant): Int

    /** Applies only while the teacher is eligible; hiding is left as it is. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        UPDATE TeacherSummaryEntity t
        SET t.content = :content, t.contentHash = :contentHash, t.contentCount = :count, t.level = :level,
            t.confidence = :confidence, t.model = :model, t.generatedAt = :now, t.attempts = 0, t.requestedAt = NULL,
            t.lastError = NULL, t.updatedAt = :now
        WHERE t.teacherIsu = :isu AND t.inputHash IS NOT NULL
    """)
    fun recordContent(
        isu: Int, content: String, contentHash: String, count: Int, level: SummaryLevel, confidence: SummaryConfidence,
        model: String, now: Instant,
    ): Int

    /** The previous content stays shown. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        UPDATE TeacherSummaryEntity t
        SET t.attempts = t.attempts + 1, t.lastError = :code, t.requestedAt = NULL, t.updatedAt = :now
        WHERE t.teacherIsu = :isu
    """)
    fun recordRejected(isu: Int, code: String, now: Instant): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE TeacherSummaryEntity t SET t.hiddenAt = :hiddenAt, t.hiddenBy = :hiddenBy, t.updatedAt = :now WHERE t.teacherIsu = :isu")
    fun setHidden(isu: Int, hiddenAt: Instant?, hiddenBy: UUID?, now: Instant): Int

    /** An admin asks for a new summary: first in the queue with fresh attempts. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE TeacherSummaryEntity t SET t.requestedAt = :now, t.attempts = 0, t.updatedAt = :now WHERE t.teacherIsu = :isu")
    fun request(isu: Int, now: Instant): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE TeacherSummaryEntity t SET t.attempts = 0, t.updatedAt = :now WHERE t.attempts > 0")
    fun resetFailedAttempts(now: Instant): Int

    /** Rows with a status, most reviewed first; [status] is an `AdminSummaryStatus` name or null for all. */
    @Query(
        value = """
        SELECT t FROM TeacherSummaryEntity t
        WHERE (t.inputHash IS NOT NULL OR t.hiddenAt IS NOT NULL)
          AND (:status IS NULL
            OR (:status = 'HIDDEN' AND t.hiddenAt IS NOT NULL)
            OR (:status = 'READY' AND t.hiddenAt IS NULL AND t.contentHash = t.inputHash)
            OR (:status = 'FAILED' AND t.hiddenAt IS NULL AND (t.contentHash IS NULL OR t.contentHash <> t.inputHash) AND t.attempts > 0)
            OR (:status = 'PENDING' AND t.hiddenAt IS NULL AND (t.contentHash IS NULL OR t.contentHash <> t.inputHash) AND t.attempts = 0))
        ORDER BY t.inputCount DESC, t.teacherIsu
        """,
        countQuery = """
        SELECT COUNT(t) FROM TeacherSummaryEntity t
        WHERE (t.inputHash IS NOT NULL OR t.hiddenAt IS NOT NULL)
          AND (:status IS NULL
            OR (:status = 'HIDDEN' AND t.hiddenAt IS NOT NULL)
            OR (:status = 'READY' AND t.hiddenAt IS NULL AND t.contentHash = t.inputHash)
            OR (:status = 'FAILED' AND t.hiddenAt IS NULL AND (t.contentHash IS NULL OR t.contentHash <> t.inputHash) AND t.attempts > 0)
            OR (:status = 'PENDING' AND t.hiddenAt IS NULL AND (t.contentHash IS NULL OR t.contentHash <> t.inputHash) AND t.attempts = 0))
        """,
    )
    fun findAdminPage(status: String?, pageable: Pageable): Page<TeacherSummaryEntity>

    /** Row counts per status; the labels are `READY`, `PENDING`, `FAILED` and `HIDDEN`. */
    @Query(
        value = """
        SELECT CASE WHEN hidden_at IS NOT NULL THEN 'HIDDEN'
                    WHEN content_hash = input_hash THEN 'READY'
                    WHEN attempts > 0 THEN 'FAILED'
                    ELSE 'PENDING' END AS label,
               count(*) AS total
        FROM teacher_summaries
        WHERE input_hash IS NOT NULL OR hidden_at IS NOT NULL
        GROUP BY 1
        """,
        nativeQuery = true,
    )
    fun countByStatus(): List<LabelCount>
}
