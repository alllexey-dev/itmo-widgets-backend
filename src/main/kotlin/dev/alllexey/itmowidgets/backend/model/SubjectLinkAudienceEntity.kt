package dev.alllexey.itmowidgets.backend.model

import jakarta.persistence.*
import java.util.UUID

@Embeddable
data class SubjectLinkAudienceId(
    @Column(nullable = false) val linkId: UUID,
    @Column(nullable = false) val flowId: Long,
) : java.io.Serializable

/** A schedule flow a GROUP or FLOW link is published to. */
@Entity
@Table(name = "subject_link_audience")
class SubjectLinkAudienceEntity(
    @EmbeddedId val id: SubjectLinkAudienceId,
)
