package dev.alllexey.itmowidgets.backend.model

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class ReviewProvider { REVIEWS_WORK_GD }

/** A copy of a provider's teacher review; the sync updates it in place and marks it removed when the provider drops it. */
@Entity
@Table(name = "external_teacher_reviews")
class ExternalTeacherReviewEntity(
    @Id val id: UUID = UUID.randomUUID(),
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) val provider: ReviewProvider,
    @Column(nullable = false) val externalId: Long,
    @Column(nullable = false) var teacherIsu: Int,
    @Column(nullable = false, columnDefinition = "text") var teacherName: String,
    @Column(columnDefinition = "text") var subjectTitle: String?,
    @Column(columnDefinition = "text") var sourceTitle: String?,
    @Column(columnDefinition = "text") var sourceLink: String?,
    /** The provider's date as written; empty when it has none. */
    @Column(nullable = false, columnDefinition = "text") var dateRaw: String,
    @JdbcTypeCode(SqlTypes.LOCAL_DATE)
    var writtenOn: LocalDate?,
    var writtenBeforeYear: Int?,
    @Column(nullable = false, columnDefinition = "text") var text: String,
    @Column(nullable = false) val firstSeenAt: Instant,
    @Column(nullable = false) var lastSeenAt: Instant,
    var removedAt: Instant? = null,
    /** The sum of the votes on this copy. */
    @Column(nullable = false) var score: Int = 0,
)
