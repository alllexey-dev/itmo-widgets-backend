package dev.alllexey.itmowidgets.backend.dto

import dev.alllexey.itmowidgets.core.model.fcm.FcmPayload
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
)
