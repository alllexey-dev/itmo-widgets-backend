package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.model.TeacherReviewFlowEntity
import dev.alllexey.itmowidgets.backend.model.TeacherReviewFlowId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface TeacherReviewFlowRepository : JpaRepository<TeacherReviewFlowEntity, TeacherReviewFlowId> {
    @Query("SELECT f.id.flowId FROM TeacherReviewFlowEntity f WHERE f.id.reviewId = :reviewId ORDER BY f.id.flowId")
    fun findFlowIds(reviewId: UUID): List<Long>

    @Modifying
    @Query("DELETE FROM TeacherReviewFlowEntity f WHERE f.id.reviewId = :reviewId")
    fun deleteAllByIdReviewId(reviewId: UUID): Int
}
