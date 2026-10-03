package dev.alllexey.itmowidgets.backend.feature.social.service

import dev.alllexey.itmowidgets.backend.feature.social.web.FriendshipEvent
import java.time.OffsetDateTime
import java.util.UUID

/** Immutable event reserved by a successful relationship transition, delivered only after commit. */
data class FriendshipNotificationIntent(
    val recipientId: UUID,
    val actorIsu: Int,
    val event: FriendshipEvent,
    val occurredAt: OffsetDateTime,
)
