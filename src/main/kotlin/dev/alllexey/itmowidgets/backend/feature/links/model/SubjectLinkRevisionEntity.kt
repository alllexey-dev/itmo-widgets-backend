package dev.alllexey.itmowidgets.backend.feature.links.model

import dev.alllexey.itmowidgets.backend.feature.moderation.model.TargetRevisionStatus
import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/** [moderated] is the same status in the moderation machinery. */
enum class LinkRevisionStatus(val moderated: TargetRevisionStatus) {
    PENDING(TargetRevisionStatus.PENDING),
    APPROVED(TargetRevisionStatus.APPROVED),
    REJECTED(TargetRevisionStatus.REJECTED),
    WITHDRAWN(TargetRevisionStatus.WITHDRAWN),
    ;

    companion object {
        fun of(status: TargetRevisionStatus): LinkRevisionStatus = entries.single { it.moderated == status }
    }
}

/** Content is immutable after sending. Only outcome fields change through authorized transitions. */
@Entity
@Table(name = "subject_link_revisions")
class SubjectLinkRevisionEntity(
    @Id val id: UUID = UUID.randomUUID(),
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "link_id", nullable = false) val link: SubjectLinkEntity,
    @Column(nullable = false) val number: Int,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) val category: LinkCategory,
    @Column(nullable = false, columnDefinition = "text") val url: String,
    @Column(nullable = false, columnDefinition = "text") val normalizedUrl: String,
    @Column(length = 120) val title: String?,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 8) val visibility: LinkVisibility,
    /**
     * The schedule flow of a FLOW revision, null otherwise: the submitted flow until a moderator's APPROVE moves it to
     * another of [flowOptions] (the decision records both).
     */
    var flowId: Long? = null,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) var status: LinkRevisionStatus = LinkRevisionStatus.PENDING,
    @Column(nullable = false) val submittedAt: Instant,
    var decidedAt: Instant? = null,
    @Column(length = 500) var note: String? = null,
    /** When the ISU check of a pending FLOW revision is due; null when none is (see `FlowLinkIsuQueue`). */
    var isuCheckDueAt: Instant? = null,
    @Column(nullable = false) var isuCheckAttempts: Int = 0,
    /**
     * The flows of the subject the author could pick from, a JSON array of `LinkAudience` as Backend has them
     * ([FlowOptions]); null when the client sent none.
     */
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") val flowOptions: String? = null,
)
