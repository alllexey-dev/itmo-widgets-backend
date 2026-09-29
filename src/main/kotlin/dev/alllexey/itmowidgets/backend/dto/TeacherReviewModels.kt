package dev.alllexey.itmowidgets.backend.dto

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import dev.alllexey.itmowidgets.backend.model.ReviewRevisionStatus
import dev.alllexey.itmowidgets.backend.model.ReviewVerification
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** `COMMUNITY` is an own review of an ITMO.Widgets user; `REVIEWS` is a copy from the Reviews project. */
enum class TeacherReviewKind { COMMUNITY, REVIEWS }

/** The author's view of their own review; everybody else only ever sees published content. */
enum class TeacherReviewStatus { PENDING, PUBLISHED, REJECTED, HIDDEN }

/**
 * Reviews of one teacher for one viewer. [reviews] holds other authors' published reviews and the active
 * Reviews copies in `ReviewOrder.RANKED`; the viewer's own review comes only in [mine].
 */
data class TeacherReviewsResponse(
    val teacherIsu: Int,
    val providerUrl: String,
    val reviews: List<TeacherReview>,
    val mine: OwnTeacherReview?,
    val canWrite: Boolean,
    val canVote: Boolean,
    val canReport: Boolean,
    /** The teacher appears in a loaded lesson, the ISU flow cache, an active Reviews copy or a published review. */
    val knownTeacher: Boolean,
)

/**
 * A review as another viewer sees it. [author] is set only on a `COMMUNITY` review written under the
 * author's name and is always null on an anonymous one. A `REVIEWS` copy has no author,
 * `verified = false` and `reportedByMe = false`. A `COMMUNITY` review has no [sourceTitle],
 * [sourceLink] or [writtenBeforeYear].
 */
data class TeacherReview(
    val id: UUID,
    val kind: TeacherReviewKind,
    val subjectTitle: String?,
    /** For a `COMMUNITY` review, the Moscow date the shown approved revision was sent. */
    val writtenOn: LocalDate?,
    val writtenBeforeYear: Int?,
    val text: String,
    val score: Int,
    /** -1, 0 or 1. */
    val myVote: Int,
    /** The ISU check proved the teacher taught the author. */
    val verified: Boolean,
    val reportedByMe: Boolean,
    val author: UserData?,
    val sourceTitle: String?,
    val sourceLink: String?,
)

/** The author's own review with its current content; [reviewNote] is set only when [status] is `REJECTED`. */
data class OwnTeacherReview(
    val id: UUID,
    val subjectTitle: String?,
    val text: String,
    val anonymous: Boolean,
    val status: TeacherReviewStatus,
    val reviewNote: String?,
    val score: Int,
    val verified: Boolean,
    /** The Moscow date the author's newest revision was sent. */
    val writtenOn: LocalDate,
)

/**
 * `PUT /api/teachers/{isu}/reviews/mine` creates or edits the caller's review. [flowIds] are candidate ISU
 * flows from the author's schedule history; the server checks them against ISU and never trusts them.
 */
data class SaveTeacherReviewRequest(
    val subjectTitle: String? = null,
    val text: String,
    @JsonDeserialize(using = StrictBooleanDeserializer::class)
    val anonymous: Boolean = true,
    val flowIds: List<Long> = emptyList(),
)

/** Immutable submitted content with its review outcome. */
data class TeacherReviewRevision(
    val id: UUID,
    val reviewId: UUID,
    val number: Int,
    val subjectTitle: String?,
    val text: String,
    val status: ReviewRevisionStatus,
    val submittedAt: Instant,
    val decidedAt: Instant?,
    val note: String?,
)

/**
 * What a moderator sees next to a reviewed revision: [shown] is the approved content others see now,
 * null before the first approval. [teacherName] comes from MyITMO for moderators and is never stored.
 */
data class ModeratedTeacherReview(
    val id: UUID,
    val teacherIsu: Int,
    val teacherName: String?,
    val anonymous: Boolean,
    val status: TeacherReviewStatus,
    val reviewNote: String?,
    val shown: TeacherReviewRevision?,
    val score: Int,
    val hidden: Boolean,
    val verification: ReviewVerification,
    val verifiedFlowId: Long?,
)

/** Accepts only JSON `true` and `false`; numbers and strings make the body unreadable. */
class StrictBooleanDeserializer : JsonDeserializer<Boolean>() {
    override fun deserialize(parser: JsonParser, context: DeserializationContext): Boolean = when (parser.currentToken()) {
        JsonToken.VALUE_TRUE -> true
        JsonToken.VALUE_FALSE -> false
        else -> context.handleUnexpectedToken(Boolean::class.java, parser) as Boolean
    }
}
