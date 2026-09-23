package dev.alllexey.itmowidgets.backend.dto

import com.fasterxml.jackson.annotation.JsonProperty
import dev.alllexey.itmowidgets.backend.model.LinkCategory
import dev.alllexey.itmowidgets.backend.model.LinkRevisionStatus
import dev.alllexey.itmowidgets.backend.model.LinkVisibility
import java.time.Instant
import java.util.UUID

/** Owners see every state of their own link; everybody else only ever sees PUBLISHED content. */
enum class SubjectLinkStatus { PRIVATE, PENDING, PUBLISHED, REJECTED, HIDDEN }

/**
 * An owner gets the link's current content. Other viewers get the latest approved revision,
 * so an edit under review never replaces what they already see. [author] is null on own links.
 */
data class SubjectLink(
    val id: UUID,
    val subjectId: Long,
    val subjectName: String,
    val periodKey: String,
    val category: LinkCategory,
    val url: String,
    val title: String?,
    val visibility: LinkVisibility,
    /** Group names of a GROUP or FLOW audience joined with ", ". */
    val audienceLabel: String?,
    val status: SubjectLinkStatus,
    val reviewNote: String?,
    val score: Int,
    /** -1, 0 or 1. */
    val myVote: Int,
    // Jackson would otherwise drop the `is` prefix; Core reads these exact keys.
    @get:JsonProperty("isMine") val isMine: Boolean,
    @get:JsonProperty("isSaved") val isSaved: Boolean,
    val reportedByMe: Boolean,
    val author: UserData?,
    val updatedAt: Instant,
)

/** A GROUP or FLOW audience the viewer can publish to right now. */
data class LinkAudience(val visibility: LinkVisibility, val label: String)

data class SubjectLinksResponse(
    val mine: List<SubjectLink>,
    val shared: List<SubjectLink>,
    val previous: List<SubjectLink>,
    val pinnedId: UUID?,
    val audiences: List<LinkAudience>,
    val premoderation: Boolean,
)

/** `PUT /api/links/{id}` creates the link under a client UUID or edits the caller's own link. */
data class SaveSubjectLinkRequest(
    val subjectId: Long,
    val subjectName: String,
    val periodKey: String,
    val category: LinkCategory,
    val url: String,
    val title: String? = null,
    val visibility: LinkVisibility,
)

data class SetLinkSavedRequest(val saved: Boolean)

/** A null [linkId] clears the pin of this subject and period. */
data class PinSubjectLinkRequest(val periodKey: String, val linkId: UUID? = null)

/** -1, 0 (remove the vote) or 1. */
data class ResourceVoteRequest(val value: Int)

/** Immutable submitted content with its review outcome. */
data class SubjectLinkRevision(
    val id: UUID,
    val linkId: UUID,
    val number: Int,
    val category: LinkCategory,
    val url: String,
    val title: String?,
    val visibility: LinkVisibility,
    val status: LinkRevisionStatus,
    val submittedAt: Instant,
    val decidedAt: Instant?,
    val note: String?,
)
