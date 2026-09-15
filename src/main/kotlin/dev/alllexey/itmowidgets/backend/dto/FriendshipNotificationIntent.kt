package dev.alllexey.itmowidgets.backend.dto

import dev.alllexey.itmowidgets.core.model.fcm.FcmPayload
import java.time.OffsetDateTime
import java.util.UUID

enum class FriendshipEvent { REQUEST_RECEIVED, REQUEST_ACCEPTED }

/** Immutable event reserved by a successful relationship transition, delivered only after commit. */
data class FriendshipNotificationIntent(
    val recipientId: UUID,
    val actorIsu: Int,
    val event: FriendshipEvent,
    val occurredAt: OffsetDateTime,
)

/** Server-owned wire contract. Identity and capabilities are scoped to the recipient. */
data class FriendshipEventPayload(
    val event: FriendshipEvent,
    val user: UserData,
    val occurredAt: OffsetDateTime,
) : FcmPayload {
    override fun getType(): String = "FRIENDSHIP_EVENT_PAYLOAD"
}
