package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.dto.FriendshipEvent
import dev.alllexey.itmowidgets.backend.dto.FriendshipNotificationIntent
import dev.alllexey.itmowidgets.backend.dto.RelationshipState
import dev.alllexey.itmowidgets.backend.exceptions.BusinessRuleException
import dev.alllexey.itmowidgets.backend.exceptions.InvalidRequestDataException
import dev.alllexey.itmowidgets.backend.exceptions.NotFoundException
import dev.alllexey.itmowidgets.backend.model.FriendshipEntity
import dev.alllexey.itmowidgets.backend.model.FriendshipEntity.Status
import dev.alllexey.itmowidgets.backend.repositories.FriendshipRepository
import dev.alllexey.itmowidgets.backend.repositories.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.OffsetDateTime

@Service
class FriendService(
    private val friendships: FriendshipRepository,
    private val users: UserRepository,
    private val userService: UserService,
    private val clock: Clock,
) {
    @Transactional
    fun sendRequest(fromIsu: Int, toIsu: Int): List<FriendshipNotificationIntent> {
        lockPair(fromIsu, toIsu)
        val existing = friendships.findBetween(fromIsu, toIsu)
        return when {
            existing == null -> {
                val actor = userService.findUserByIsu(fromIsu)
                val recipient = userService.findUserByIsu(toIsu)
                friendships.save(FriendshipEntity(
                    requester = actor,
                    addressee = recipient,
                    createdAt = clock.instant(),
                ))
                listOf(FriendshipNotificationIntent(recipient.id, actor.isu,
                    FriendshipEvent.REQUEST_RECEIVED, OffsetDateTime.now(clock)))
            }
            existing.status == Status.PENDING && existing.addressee.isu == fromIsu -> {
                accept(existing)
                listOf(acceptedIntent(existing))
            }
            // Repeated requests must not produce duplicate notifications.
            else -> emptyList()
        }
    }

    @Transactional
    fun acceptRequest(viewerIsu: Int, otherIsu: Int): List<FriendshipNotificationIntent> {
        lockPair(viewerIsu, otherIsu)
        val friendship = friendships.findBetween(viewerIsu, otherIsu)
            ?: throw BusinessRuleException("No incoming friend request")
        if (friendship.status == Status.ACCEPTED) return emptyList()
        if (friendship.addressee.isu != viewerIsu) throw BusinessRuleException("No incoming friend request")
        accept(friendship)
        return listOf(acceptedIntent(friendship))
    }

    @Transactional
    fun rejectRequest(viewerIsu: Int, otherIsu: Int): List<FriendshipNotificationIntent> {
        lockPair(viewerIsu, otherIsu)
        val friendship = friendships.findBetween(viewerIsu, otherIsu) ?: return emptyList()
        if (friendship.status != Status.PENDING || friendship.addressee.isu != viewerIsu) {
            throw BusinessRuleException("No incoming friend request")
        }
        friendships.delete(friendship)
        return emptyList()
    }

    @Transactional
    fun cancelRequest(viewerIsu: Int, otherIsu: Int): List<FriendshipNotificationIntent> {
        lockPair(viewerIsu, otherIsu)
        val friendship = friendships.findBetween(viewerIsu, otherIsu) ?: return emptyList()
        if (friendship.status != Status.PENDING || friendship.requester.isu != viewerIsu) {
            throw BusinessRuleException("No outgoing friend request")
        }
        friendships.delete(friendship)
        return emptyList()
    }

    @Transactional
    fun removeFriend(viewerIsu: Int, otherIsu: Int): List<FriendshipNotificationIntent> {
        lockPair(viewerIsu, otherIsu)
        val friendship = friendships.findBetween(viewerIsu, otherIsu) ?: return emptyList()
        if (friendship.status != Status.ACCEPTED) throw BusinessRuleException("Users are not friends")
        friendships.delete(friendship)
        return emptyList()
    }

    @Transactional(readOnly = true)
    fun relationship(viewerIsu: Int, otherIsu: Int): RelationshipState {
        if (viewerIsu == otherIsu) return RelationshipState.NONE
        val friendship = friendships.findBetween(viewerIsu, otherIsu) ?: return RelationshipState.NONE
        return when {
            friendship.status == Status.ACCEPTED -> RelationshipState.FRIENDS
            friendship.requester.isu == viewerIsu -> RelationshipState.OUTGOING
            else -> RelationshipState.INCOMING
        }
    }

    @Transactional(readOnly = true)
    fun getFriends(isu: Int): List<Int> = friendships.findUserFriendsIsu(isu)

    @Transactional(readOnly = true)
    fun getIncomingRequests(isu: Int): List<Int> = friendships.findIncomingRequests(isu)

    @Transactional(readOnly = true)
    fun getOutgoingRequests(isu: Int): List<Int> = friendships.findOutgoingRequests(isu)

    @Transactional(readOnly = true)
    fun areFriends(isu1: Int, isu2: Int): Boolean = relationship(isu1, isu2) == RelationshipState.FRIENDS

    private fun acceptedIntent(friendship: FriendshipEntity) = FriendshipNotificationIntent(
        recipientId = friendship.requester.id,
        actorIsu = friendship.addressee.isu,
        event = FriendshipEvent.REQUEST_ACCEPTED,
        occurredAt = OffsetDateTime.now(clock),
    )

    private fun accept(friendship: FriendshipEntity) {
        friendship.status = Status.ACCEPTED
        friendship.respondedAt = clock.instant()
    }

    private fun lockPair(first: Int, second: Int) {
        if (first <= 0 || second <= 0 || first == second) {
            throw InvalidRequestDataException("Friendship requires two different positive ISUs")
        }
        // An absent friendship cannot be row-locked. Serialize every mutation on the existing
        // users in ascending ISU order, including concurrent first/crossed requests and deletion.
        for (isu in listOf(first, second).sorted()) {
            users.lockByIsu(isu) ?: throw NotFoundException("User not found")
        }
    }
}
