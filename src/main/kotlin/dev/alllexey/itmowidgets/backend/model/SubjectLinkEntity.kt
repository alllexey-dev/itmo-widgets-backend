package dev.alllexey.itmowidgets.backend.model

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.databind.JsonNode
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

enum class LinkCategory {
    SCORES, QUEUE, MATERIALS, TASKS, RECORDINGS, NOTES, EXAM, CHAT, OTHER;

    companion object {
        /** Categories whose approved links stay useful to the next years' students. */
        val PREVIOUS_YEARS: Set<LinkCategory> = setOf(MATERIALS, TASKS, RECORDINGS, NOTES, EXAM)

        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun fromJson(value: JsonNode): LinkCategory {
            require(value.isTextual) { "Link category must be an enum name string" }
            return entries.firstOrNull { it.name == value.textValue() }
                ?: throw IllegalArgumentException("Unknown link category")
        }
    }
}

/** Owner-selected audience. FLOW is one schedule flow of the author (`flowId`), ALL is premoderated. */
enum class LinkVisibility {
    PRIVATE, FLOW, ALL;

    companion object {
        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun fromJson(value: JsonNode): LinkVisibility {
            // Jackson normally accepts enum ordinals: numeric 3 must never open an ALL audience.
            require(value.isTextual) { "Link visibility must be an enum name string" }
            return entries.firstOrNull { it.name == value.textValue() }
                ?: throw IllegalArgumentException("Unknown link visibility")
        }
    }
}

/** The owner's current content. Other viewers see the latest approved revision instead. */
@Entity
@Table(name = "subject_links")
class SubjectLinkEntity(
    @Id val id: UUID,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false) val owner: User,
    @Column(nullable = false) var subjectId: Long,
    @Column(nullable = false, length = 200) var subjectName: String,
    @Column(nullable = false, length = 8) var periodKey: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) var category: LinkCategory,
    @Column(nullable = false, columnDefinition = "text") var url: String,
    @Column(nullable = false, columnDefinition = "text") var normalizedUrl: String,
    @Column(length = 120) var title: String?,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 8) var visibility: LinkVisibility,
    /** The schedule flow of a FLOW link; null otherwise. */
    var flowId: Long? = null,
    @Column(nullable = false) var score: Int = 0,
    var hiddenAt: Instant? = null,
    @Column(nullable = false) val createdAt: Instant,
    @Column(nullable = false) var updatedAt: Instant,
)
