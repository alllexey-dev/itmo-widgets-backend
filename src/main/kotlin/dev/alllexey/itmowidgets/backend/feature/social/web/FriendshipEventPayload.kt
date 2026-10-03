package dev.alllexey.itmowidgets.backend.feature.social.web

import dev.alllexey.itmowidgets.backend.feature.push.web.FcmPayload
import dev.alllexey.itmowidgets.backend.feature.users.web.UserData
import java.time.OffsetDateTime

enum class FriendshipEvent { REQUEST_RECEIVED, REQUEST_ACCEPTED }

/** Server-owned wire contract. Identity and capabilities are scoped to the recipient. */
data class FriendshipEventPayload(
    val event: FriendshipEvent,
    val user: UserData,
    val occurredAt: OffsetDateTime,
) : FcmPayload {
    override fun getType(): String = "FRIENDSHIP_EVENT_PAYLOAD"
}
