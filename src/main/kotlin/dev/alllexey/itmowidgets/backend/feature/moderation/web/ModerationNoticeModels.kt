package dev.alllexey.itmowidgets.backend.feature.moderation.web

import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationOutcome
import java.time.Instant
import java.util.UUID

/**
 * What the author of a link or a teacher review is told about a moderation decision on it: an entry of
 * `GET /api/users/me/moderation-notices` and the payload of `MODERATION_NOTICE_PAYLOAD`
 * (docs/contracts/notifications.md#moderation-notices). Exactly one of [link] and [review] is set.
 */
data class ModerationNotice(
    /** The decision's id: stable, so the bell tells a notice it has seen. */
    val id: UUID,
    val outcome: ModerationOutcome,
    /** The moderator's note of a rejection; null for other outcomes. */
    val note: String?,
    val decidedAt: Instant,
    val link: ModerationNoticeLink?,
    val review: ModerationNoticeReview?,
) {
    companion object {
        const val TYPE = "MODERATION_NOTICE_PAYLOAD"
    }
}

/** The link as it is now; [title] and [host] are of the decided revision. */
data class ModerationNoticeLink(
    val id: UUID,
    val subjectId: Long,
    val subjectName: String,
    val periodKey: String,
    val title: String?,
    val host: String,
)

/** The review as it is now; [subjectTitle] is of the decided revision, [teacherName] null when it is unknown. */
data class ModerationNoticeReview(val id: UUID, val teacherIsu: Int, val teacherName: String?, val subjectTitle: String?)
