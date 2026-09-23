package dev.alllexey.itmowidgets.backend.model

import jakarta.persistence.*
import java.util.UUID

@Embeddable
data class SubjectLinkPinId(
    @Column(nullable = false) val userId: UUID,
    @Column(nullable = false) val subjectId: Long,
    @Column(nullable = false, length = 8) val periodKey: String,
) : java.io.Serializable

/** At most one pinned link per viewer, subject and period. */
@Entity
@Table(name = "subject_link_pins")
class SubjectLinkPinEntity(
    @EmbeddedId val id: SubjectLinkPinId,
    @Column(nullable = false) var linkId: UUID,
)
