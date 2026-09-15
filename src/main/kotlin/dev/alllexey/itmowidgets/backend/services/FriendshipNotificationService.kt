package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.dto.FriendshipEvent
import dev.alllexey.itmowidgets.backend.dto.FriendshipEventPayload
import dev.alllexey.itmowidgets.backend.dto.FriendshipNotificationIntent
import dev.alllexey.itmowidgets.backend.dto.RelationshipState
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class FriendshipNotificationService(
    private val payloads: FriendshipNotificationPayloadService,
    private val devices: DeviceService,
) {
    /** Best effort, outside the mutation transaction. No payloads or exception messages in logs. */
    fun deliver(intent: FriendshipNotificationIntent) {
        try {
            val payload = payloads.currentPayload(intent) ?: return
            devices.sendDataMessageToUser(intent.recipientId, payload)
        } catch (error: Exception) {
            logger.warn("Friendship notification failed: {}", error.javaClass.simpleName)
        }
    }

    companion object { private val logger = LoggerFactory.getLogger(FriendshipNotificationService::class.java) }
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
