package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.model.TeacherReviewVoteEntity
import dev.alllexey.itmowidgets.backend.model.TeacherReviewVoteId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface TeacherReviewVoteRepository : JpaRepository<TeacherReviewVoteEntity, TeacherReviewVoteId> {
    @Query("SELECT COALESCE(SUM(v.value), 0) FROM TeacherReviewVoteEntity v WHERE v.id.reviewId = :reviewId")
    fun sumValues(reviewId: UUID): Int

    @Query("SELECT v FROM TeacherReviewVoteEntity v WHERE v.id.userId = :userId AND v.id.reviewId IN :reviewIds")
    fun findByUserAndReviews(userId: UUID, reviewIds: Collection<UUID>): List<TeacherReviewVoteEntity>
}
