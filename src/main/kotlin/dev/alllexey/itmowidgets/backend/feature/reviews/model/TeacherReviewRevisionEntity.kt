package dev.alllexey.itmowidgets.backend.feature.reviews.model

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

enum class ReviewRevisionStatus { PENDING, APPROVED, REJECTED, WITHDRAWN }

/** Content is immutable after sending. Only outcome fields change through authorized transitions. */
@Entity
@Table(name = "teacher_review_revisions")
class TeacherReviewRevisionEntity(
    @Id val id: UUID = UUID.randomUUID(),
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "review_id", nullable = false) val review: TeacherReviewEntity,
    @Column(nullable = false) val number: Int,
    @Column(length = 200) val subjectTitle: String?,
    @Column(nullable = false, columnDefinition = "text") val text: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) var status: ReviewRevisionStatus = ReviewRevisionStatus.PENDING,
    @Column(nullable = false) val submittedAt: Instant,
    var decidedAt: Instant? = null,
    @Column(length = 500) var note: String? = null,
)
