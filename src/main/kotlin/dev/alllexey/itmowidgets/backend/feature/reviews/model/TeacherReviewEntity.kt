package dev.alllexey.itmowidgets.backend.feature.reviews.model

import dev.alllexey.itmowidgets.backend.feature.users.model.User
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/** Whether the teacher taught the author, as checked against ISU flows; only `VERIFIED` is shown to others. */
enum class ReviewVerification { PENDING, VERIFIED, UNVERIFIED }

/** The author's current content. Other viewers see the latest approved revision instead. */
@Entity
@Table(name = "teacher_reviews")
class TeacherReviewEntity(
    @Id val id: UUID = UUID.randomUUID(),
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", nullable = false) val author: User,
    @Column(nullable = false) val teacherIsu: Int,
    @Column(length = 200) var subjectTitle: String?,
    @Column(nullable = false, columnDefinition = "text") var text: String,
    /** Applies immediately and never creates a revision. */
    @Column(nullable = false) var anonymous: Boolean = true,
    @Column(nullable = false) var score: Int = 0,
    var hiddenAt: Instant? = null,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    var verification: ReviewVerification = ReviewVerification.PENDING,
    /** The ISU flow that proved the teacher taught the author; set only when `VERIFIED`. */
    var verifiedFlowId: Long? = null,
    /** When the next ISU check is due; set only while `PENDING`. */
    var verificationDueAt: Instant? = null,
    @Column(nullable = false) var verificationAttempts: Int = 0,
    var verificationCheckedAt: Instant? = null,
    @Column(nullable = false) val createdAt: Instant,
    @Column(nullable = false) var updatedAt: Instant,
)
