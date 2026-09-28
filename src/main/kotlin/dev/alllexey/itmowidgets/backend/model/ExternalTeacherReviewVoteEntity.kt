package dev.alllexey.itmowidgets.backend.model

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Embeddable
data class ExternalTeacherReviewVoteId(
    @Column(nullable = false) val reviewId: UUID,
    @Column(nullable = false) val userId: UUID,
) : java.io.Serializable

/** A vote on a Reviews copy; the copy keeps its UUID across syncs, so the vote survives them. */
@Entity
@Table(name = "external_teacher_review_votes")
class ExternalTeacherReviewVoteEntity(
    @EmbeddedId val id: ExternalTeacherReviewVoteId,
    @Column(nullable = false) var value: Short,
    @Column(nullable = false) val createdAt: Instant,
)
