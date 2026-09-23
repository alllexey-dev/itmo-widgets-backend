package dev.alllexey.itmowidgets.backend.model

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Embeddable
data class SubjectLinkSaveId(
    @Column(nullable = false) val userId: UUID,
    @Column(nullable = false) val linkId: UUID,
) : java.io.Serializable

/** Another student's link the viewer added to their own list. */
@Entity
@Table(name = "subject_link_saves")
class SubjectLinkSaveEntity(
    @EmbeddedId val id: SubjectLinkSaveId,
    @Column(nullable = false) val createdAt: Instant,
)
