package dev.alllexey.itmowidgets.backend.model

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Embeddable
data class TeacherReviewVoteId(
    @Column(nullable = false) val reviewId: UUID,
    @Column(nullable = false) val userId: UUID,
) : java.io.Serializable

@Entity
@Table(name = "teacher_review_votes")
class TeacherReviewVoteEntity(
    @EmbeddedId val id: TeacherReviewVoteId,
    @Column(nullable = false) var value: Short,
    @Column(nullable = false) val createdAt: Instant,
)
