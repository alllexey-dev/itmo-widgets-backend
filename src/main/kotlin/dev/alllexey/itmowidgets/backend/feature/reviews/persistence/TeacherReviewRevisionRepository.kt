package dev.alllexey.itmowidgets.backend.feature.reviews.persistence

import dev.alllexey.itmowidgets.backend.feature.reviews.model.ReviewRevisionStatus
import dev.alllexey.itmowidgets.backend.feature.reviews.model.TeacherReviewRevisionEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

interface TeacherReviewRevisionRepository : JpaRepository<TeacherReviewRevisionEntity, UUID> {
    @Query(
        """
        SELECT r FROM TeacherReviewRevisionEntity r
        WHERE r.review.id = :reviewId
          AND r.number = (SELECT MAX(x.number) FROM TeacherReviewRevisionEntity x WHERE x.review.id = :reviewId)
        """,
    )
    fun findLatest(reviewId: UUID): TeacherReviewRevisionEntity?

    @Query(
        """
        SELECT r FROM TeacherReviewRevisionEntity r
        WHERE r.review.id = :reviewId
          AND r.number = (SELECT MAX(x.number) FROM TeacherReviewRevisionEntity x WHERE x.review.id = :reviewId
              AND x.status = dev.alllexey.itmowidgets.backend.feature.reviews.model.ReviewRevisionStatus.APPROVED)
        """,
    )
    fun findLatestApproved(reviewId: UUID): TeacherReviewRevisionEntity?

    @Query(
        """
        SELECT r FROM TeacherReviewRevisionEntity r
        WHERE r.review.id = :reviewId AND r.status = dev.alllexey.itmowidgets.backend.feature.reviews.model.ReviewRevisionStatus.PENDING
        """,
    )
    fun findPending(reviewId: UUID): TeacherReviewRevisionEntity?

    /** The newest revision of each given review: the author's status. */
    @Query(
        """
        SELECT r FROM TeacherReviewRevisionEntity r
        WHERE r.review.id IN :reviewIds
          AND r.number = (SELECT MAX(x.number) FROM TeacherReviewRevisionEntity x WHERE x.review = r.review)
        """,
    )
    fun findLatestIn(reviewIds: Collection<UUID>): List<TeacherReviewRevisionEntity>

    /** The newest approved revision of each given review: the content other viewers see. */
    @Query(
        """
        SELECT r FROM TeacherReviewRevisionEntity r
        WHERE r.review.id IN :reviewIds
          AND r.number = (SELECT MAX(x.number) FROM TeacherReviewRevisionEntity x WHERE x.review = r.review
              AND x.status = dev.alllexey.itmowidgets.backend.feature.reviews.model.ReviewRevisionStatus.APPROVED)
        """,
    )
    fun findLatestApprovedIn(reviewIds: Collection<UUID>): List<TeacherReviewRevisionEntity>

    @Query("SELECT r FROM TeacherReviewRevisionEntity r WHERE r.review.id = :reviewId ORDER BY r.number")
    fun findAllByReview(reviewId: UUID): List<TeacherReviewRevisionEntity>

    /** Revisions with their reviews in one query; authors stay unloaded. */
    @Query("SELECT r FROM TeacherReviewRevisionEntity r JOIN FETCH r.review WHERE r.id IN :ids")
    fun findAllWithReview(ids: Collection<UUID>): List<TeacherReviewRevisionEntity>

    @Query(
        """
        SELECT r FROM TeacherReviewRevisionEntity r
        WHERE r.review.author.id = :authorId AND r.status = dev.alllexey.itmowidgets.backend.feature.reviews.model.ReviewRevisionStatus.PENDING
        """,
    )
    fun findPendingByAuthor(authorId: UUID): List<TeacherReviewRevisionEntity>

    @Query("SELECT COUNT(r) FROM TeacherReviewRevisionEntity r WHERE r.review.author.id = :authorId AND r.submittedAt >= :since")
    fun countByAuthorSince(authorId: UUID, since: Instant): Long

    @Query("SELECT COUNT(r) FROM TeacherReviewRevisionEntity r WHERE r.review.author.id = :authorId AND r.status = :status")
    fun countByAuthorAndStatus(authorId: UUID, status: ReviewRevisionStatus): Long
}
