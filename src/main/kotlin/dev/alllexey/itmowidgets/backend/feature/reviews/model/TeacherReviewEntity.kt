package dev.alllexey.itmowidgets.backend.feature.reviews.model

import dev.alllexey.itmowidgets.backend.feature.users.model.User
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/** Whether the teacher taught the author; only `VERIFIED` is shown to others. */
enum class ReviewVerification { PENDING, VERIFIED, UNVERIFIED }

/** What proved a `VERIFIED` review: an ISU flow, or the teacher's lessons in the sport catalog (ADR 0037). */
enum class ReviewVerificationBasis { ISU_FLOW, SPORT_TEACHER }

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
    /** Set only when `VERIFIED`; null on a row verified by an image before V27, which means `ISU_FLOW`. */
    @Enumerated(EnumType.STRING) @Column(length = 16)
    var verifiedBasis: ReviewVerificationBasis? = null,
    /** The ISU flow that proved the teacher taught the author; set only for `ISU_FLOW`. */
    var verifiedFlowId: Long? = null,
    /** When the next ISU check is due; set only while `PENDING`. */
    var verificationDueAt: Instant? = null,
    @Column(nullable = false) var verificationAttempts: Int = 0,
    var verificationCheckedAt: Instant? = null,
    @Column(nullable = false) val createdAt: Instant,
    @Column(nullable = false) var updatedAt: Instant,
)
