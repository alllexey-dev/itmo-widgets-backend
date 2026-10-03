package dev.alllexey.itmowidgets.backend.feature.reviews.model

import jakarta.persistence.*
import java.util.UUID

@Embeddable
data class TeacherReviewFlowId(
    @Column(nullable = false) val reviewId: UUID,
    @Column(nullable = false) val flowId: Long,
) : java.io.Serializable

/** A flow id the author sent with the latest save: a candidate for the ISU check, never trusted on its own. */
@Entity
@Table(name = "teacher_review_flows")
class TeacherReviewFlowEntity(
    @EmbeddedId val id: TeacherReviewFlowId,
)
