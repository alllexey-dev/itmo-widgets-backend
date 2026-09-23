package dev.alllexey.itmowidgets.backend.model

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Embeddable
data class SubjectLinkVoteId(
    @Column(nullable = false) val linkId: UUID,
    @Column(nullable = false) val userId: UUID,
) : java.io.Serializable

@Entity
@Table(name = "subject_link_votes")
class SubjectLinkVoteEntity(
    @EmbeddedId val id: SubjectLinkVoteId,
    @Column(nullable = false) var value: Short,
    @Column(nullable = false) val createdAt: Instant,
)
