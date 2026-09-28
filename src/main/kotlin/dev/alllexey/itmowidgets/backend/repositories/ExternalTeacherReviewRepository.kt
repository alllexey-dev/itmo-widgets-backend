package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.model.ExternalTeacherReviewEntity
import dev.alllexey.itmowidgets.backend.model.ReviewProvider
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface ExternalTeacherReviewRepository : JpaRepository<ExternalTeacherReviewEntity, UUID> {
    fun findAllByProvider(provider: ReviewProvider): List<ExternalTeacherReviewEntity>

    fun findAllByProviderAndTeacherIsuAndRemovedAtIsNull(
        provider: ReviewProvider,
        teacherIsu: Int,
    ): List<ExternalTeacherReviewEntity>

    fun countByProvider(provider: ReviewProvider): Long

    fun countByProviderAndRemovedAtIsNull(provider: ReviewProvider): Long

    fun countByProviderAndRemovedAtIsNotNull(provider: ReviewProvider): Long

    @Query("SELECT COUNT(DISTINCT r.teacherIsu) FROM ExternalTeacherReviewEntity r WHERE r.provider = :provider AND r.removedAt IS NULL")
    fun countActiveTeachers(provider: ReviewProvider): Long
}
