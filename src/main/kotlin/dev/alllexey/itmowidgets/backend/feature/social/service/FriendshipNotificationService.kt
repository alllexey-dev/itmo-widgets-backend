package dev.alllexey.itmowidgets.backend.feature.social.service

import dev.alllexey.itmowidgets.backend.feature.push.service.DeviceService
import dev.alllexey.itmowidgets.backend.feature.push.service.PushAlert
import dev.alllexey.itmowidgets.backend.feature.push.service.PushLocKeys
import dev.alllexey.itmowidgets.backend.feature.social.web.FriendshipEvent
import dev.alllexey.itmowidgets.backend.feature.social.web.FriendshipEventPayload
import dev.alllexey.itmowidgets.backend.feature.users.service.UserPrivacyService
import dev.alllexey.itmowidgets.backend.feature.users.service.UserService
import dev.alllexey.itmowidgets.backend.feature.users.web.RelationshipState
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Duration

@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class FriendshipNotificationService(private val payloads: FriendshipNotificationPayloadService, private val devices: DeviceService) {
    /** Best effort, outside the mutation transaction. No payloads or exception messages in logs. */
    fun deliver(intent: FriendshipNotificationIntent) {
        try {
            val payload = payloads.currentPayload(intent) ?: return
            devices.sendDataMessageToUser(intent.recipientId, payload, intent.occurredAt.toInstant().plus(PUSH_LIFETIME), alert(payload))
        } catch (error: Exception) {
            logger.warn("Friendship notification failed: {}", error.javaClass.simpleName)
        }
    }

    /** Mirrors Android's `FriendshipPushHandler`: the friends title and the event text naming the actor. */
    private fun alert(payload: FriendshipEventPayload): PushAlert {
        val actor = payload.user
        return PushAlert(
            titleLocKey = PushLocKeys.FRIENDS_TITLE,
            locKey = when (payload.event) {
                FriendshipEvent.REQUEST_RECEIVED -> PushLocKeys.FRIEND_REQUEST
                FriendshipEvent.REQUEST_ACCEPTED -> PushLocKeys.FRIEND_ACCEPTED
            },
            locArgs = listOf(actor.name.trim().ifEmpty { actor.isu.toString() }),
            collapseId = "friend-${actor.isu}",
            threadId = "friends",
            interruptionLevel = PushAlert.InterruptionLevel.ACTIVE,
        )
    }

    companion object {
        /** A friendship event older than this is no longer news; the app shows the state on its next refresh. */
        val PUSH_LIFETIME: Duration = Duration.ofHours(12)

        private val logger = LoggerFactory.getLogger(FriendshipNotificationService::class.java)
    }
}

/** Materialize lazy identity fields in a short read transaction, never around FCM I/O. */
@Service
class FriendshipNotificationPayloadService(
    private val users: UserService,
    private val privacy: UserPrivacyService,
    private val friends: FriendService,
) {
    @Transactional(readOnly = true)
    fun currentPayload(intent: FriendshipNotificationIntent): FriendshipEventPayload? {
        val recipient = users.findUserById(intent.recipientId)
        val actor = users.findUserByIsu(intent.actorIsu)
        val expected = when (intent.event) {
            FriendshipEvent.REQUEST_RECEIVED -> RelationshipState.INCOMING
            FriendshipEvent.REQUEST_ACCEPTED -> RelationshipState.FRIENDS
        }
        if (friends.relationship(recipient.isu, actor.isu) != expected) return null
        return FriendshipEventPayload(intent.event, privacy.userDataFor(recipient, actor), intent.occurredAt)
    }
}
