package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.model.ReviewVerification
import dev.alllexey.itmowidgets.backend.model.TeacherReviewEntity
import org.springframework.data.domain.Limit
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

interface TeacherReviewRepository : JpaRepository<TeacherReviewEntity, UUID> {
    fun findByAuthorIdAndTeacherIsu(authorId: UUID, teacherIsu: Int): TeacherReviewEntity?

    /** Serializes the author's saves of one review; null when the author has none yet. */
    @Query(value = "SELECT id FROM teacher_reviews WHERE author_id = :authorId AND teacher_isu = :teacherIsu FOR UPDATE", nativeQuery = true)
    fun lockByAuthorAndTeacher(authorId: UUID, teacherIsu: Int): UUID?

    @Query(value = "SELECT id FROM teacher_reviews WHERE id = :id FOR UPDATE", nativeQuery = true)
    fun lockById(id: UUID): UUID?

    fun findAllByTeacherIsuAndHiddenAtIsNull(teacherIsu: Int): List<TeacherReviewEntity>

    fun findAllByAuthorId(authorId: UUID): List<TeacherReviewEntity>

    /** A review others can see: not hidden and with approved content. */
    @Query("""
        SELECT COUNT(r) > 0 FROM TeacherReviewEntity r
        WHERE r.teacherIsu = :teacherIsu AND r.hiddenAt IS NULL
          AND EXISTS (SELECT x.id FROM TeacherReviewRevisionEntity x WHERE x.review = r
              AND x.status = dev.alllexey.itmowidgets.backend.model.ReviewRevisionStatus.APPROVED)
        """)
    fun existsPublishedForTeacher(teacherIsu: Int): Boolean

    fun countByVerification(verification: ReviewVerification): Long

    /** Pending reviews whose ISU check is due, the longest waiting first. */
    @Query("""
        SELECT r FROM TeacherReviewEntity r
        WHERE r.verification = dev.alllexey.itmowidgets.backend.model.ReviewVerification.PENDING AND r.verificationDueAt <= :now
        ORDER BY r.verificationDueAt, r.id
        """)
    fun findDue(now: Instant, limit: Limit): List<TeacherReviewEntity>

    /** The conditions below apply a result only to the check that was started: a save meanwhile moves the due time. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        UPDATE TeacherReviewEntity r
        SET r.verification = dev.alllexey.itmowidgets.backend.model.ReviewVerification.VERIFIED, r.verifiedFlowId = :flowId,
            r.verificationDueAt = NULL, r.verificationCheckedAt = :now
        WHERE r.id = :id AND r.verification = dev.alllexey.itmowidgets.backend.model.ReviewVerification.PENDING
          AND r.verificationDueAt = :dueAt
        """)
    fun markVerified(id: UUID, dueAt: Instant, flowId: Long, now: Instant): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        UPDATE TeacherReviewEntity r
        SET r.verification = dev.alllexey.itmowidgets.backend.model.ReviewVerification.UNVERIFIED,
            r.verificationDueAt = NULL, r.verificationCheckedAt = :now
        WHERE r.id = :id AND r.verification = dev.alllexey.itmowidgets.backend.model.ReviewVerification.PENDING
          AND r.verificationDueAt = :dueAt
        """)
    fun markUnverified(id: UUID, dueAt: Instant, now: Instant): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        UPDATE TeacherReviewEntity r
        SET r.verificationDueAt = :until, r.verificationAttempts = r.verificationAttempts + :attempts
        WHERE r.id = :id AND r.verification = dev.alllexey.itmowidgets.backend.model.ReviewVerification.PENDING
          AND r.verificationDueAt = :dueAt
        """)
    fun postpone(id: UUID, dueAt: Instant, until: Instant, attempts: Int): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        UPDATE TeacherReviewEntity r SET r.verificationDueAt = :until
        WHERE r.verification = dev.alllexey.itmowidgets.backend.model.ReviewVerification.PENDING AND r.verificationDueAt <= :now
        """)
    fun postponeAllDue(now: Instant, until: Instant): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        UPDATE TeacherReviewEntity r SET r.verificationDueAt = :now, r.verificationAttempts = 0
        WHERE r.verification = dev.alllexey.itmowidgets.backend.model.ReviewVerification.PENDING
        """)
    fun rescheduleAllPending(now: Instant): Int
}
