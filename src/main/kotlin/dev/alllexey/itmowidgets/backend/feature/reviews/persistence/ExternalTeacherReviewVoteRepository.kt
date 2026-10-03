package dev.alllexey.itmowidgets.backend.feature.reviews.persistence

import dev.alllexey.itmowidgets.backend.feature.reviews.model.ExternalTeacherReviewVoteEntity
import dev.alllexey.itmowidgets.backend.feature.reviews.model.ExternalTeacherReviewVoteId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface ExternalTeacherReviewVoteRepository : JpaRepository<ExternalTeacherReviewVoteEntity, ExternalTeacherReviewVoteId> {
    @Query("SELECT COALESCE(SUM(v.value), 0) FROM ExternalTeacherReviewVoteEntity v WHERE v.id.reviewId = :reviewId")
    fun sumValues(reviewId: UUID): Int

    @Query("SELECT v FROM ExternalTeacherReviewVoteEntity v WHERE v.id.userId = :userId AND v.id.reviewId IN :reviewIds")
    fun findByUserAndReviews(userId: UUID, reviewIds: Collection<UUID>): List<ExternalTeacherReviewVoteEntity>
}
