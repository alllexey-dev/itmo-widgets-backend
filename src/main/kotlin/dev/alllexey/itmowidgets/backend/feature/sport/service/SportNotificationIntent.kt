package dev.alllexey.itmowidgets.backend.feature.sport.service

import dev.alllexey.itmowidgets.backend.feature.push.web.FcmPayload
import java.time.Instant
import java.util.UUID

enum class SportQueueKind { AUTO, FREE }

/** A committed reservation, not a promise that FCM has accepted or delivered it. */
data class SportNotificationIntent(
    val kind: SportQueueKind,
    val entryId: Long,
    val userId: UUID,
    val lessonId: Long,
    val attemptNumber: Int,
    val payload: FcmPayload,
    /** End of the entry's eligibility for this lesson; the push expires with it. */
    val deadline: Instant,
)
