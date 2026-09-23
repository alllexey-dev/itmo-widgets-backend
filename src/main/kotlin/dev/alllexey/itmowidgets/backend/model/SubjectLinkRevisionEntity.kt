package dev.alllexey.itmowidgets.backend.model

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

enum class LinkRevisionStatus { PENDING, APPROVED, REJECTED, WITHDRAWN }

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
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) var status: LinkRevisionStatus = LinkRevisionStatus.PENDING,
    @Column(nullable = false) val submittedAt: Instant,
    var decidedAt: Instant? = null,
    @Column(length = 500) var note: String? = null,
)
