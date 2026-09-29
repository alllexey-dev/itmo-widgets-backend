package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.model.ExternalTeacherReviewEntity
import dev.alllexey.itmowidgets.backend.model.ReviewProvider
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface ExternalTeacherReviewRepository : JpaRepository<ExternalTeacherReviewEntity, UUID> {
    fun findAllByProvider(provider: ReviewProvider): List<ExternalTeacherReviewEntity>

    /** Every active copy: the input of the AI summaries. */
    fun findAllByProviderAndRemovedAtIsNull(provider: ReviewProvider): List<ExternalTeacherReviewEntity>

    fun findAllByProviderAndTeacherIsuAndRemovedAtIsNull(
        provider: ReviewProvider,
        teacherIsu: Int,
    ): List<ExternalTeacherReviewEntity>

    fun countByProvider(provider: ReviewProvider): Long

    /** The teacher name of the most recently seen active copy of each given teacher. */
    @Query(
        value = """
        SELECT DISTINCT ON (teacher_isu) teacher_isu AS teacherIsu, teacher_name AS teacherName
        FROM external_teacher_reviews
        WHERE provider = :provider AND removed_at IS NULL AND teacher_isu IN (:isus)
        ORDER BY teacher_isu, last_seen_at DESC, first_seen_at DESC, id
        """,
        nativeQuery = true,
    )
    fun findActiveTeacherNames(provider: String, isus: Collection<Int>): List<TeacherNameRow>

    fun countByProviderAndRemovedAtIsNull(provider: ReviewProvider): Long

    fun countByProviderAndRemovedAtIsNotNull(provider: ReviewProvider): Long

    @Query("SELECT COUNT(DISTINCT r.teacherIsu) FROM ExternalTeacherReviewEntity r WHERE r.provider = :provider AND r.removedAt IS NULL")
    fun countActiveTeachers(provider: ReviewProvider): Long
}

interface TeacherNameRow {
    val teacherIsu: Int
    val teacherName: String
}
