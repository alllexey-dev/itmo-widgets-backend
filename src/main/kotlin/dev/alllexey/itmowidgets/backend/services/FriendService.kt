package dev.alllexey.itmowidgets.backend.services

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

@Service
class FriendService(
    private val friendships: FriendshipRepository,
    private val users: UserRepository,
    private val userService: UserService,
    private val clock: Clock,
) {
    @Transactional
    fun sendRequest(fromIsu: Int, toIsu: Int) {
        lockPair(fromIsu, toIsu)
        val existing = friendships.findBetween(fromIsu, toIsu)
        when {
            existing == null -> friendships.save(FriendshipEntity(
                requester = userService.findUserByIsu(fromIsu),
                addressee = userService.findUserByIsu(toIsu),
                createdAt = clock.instant(),
            ))
            existing.status == Status.PENDING && existing.addressee.isu == fromIsu -> accept(existing)
            // Repeated outgoing requests and requests to an accepted friend are no-ops.
        }
    }

    @Transactional
    fun acceptRequest(viewerIsu: Int, otherIsu: Int) {
        lockPair(viewerIsu, otherIsu)
        val friendship = friendships.findBetween(viewerIsu, otherIsu)
            ?: throw BusinessRuleException("No incoming friend request")
        if (friendship.status == Status.ACCEPTED) return
        if (friendship.addressee.isu != viewerIsu) throw BusinessRuleException("No incoming friend request")
        accept(friendship)
    }

    @Transactional
    fun rejectRequest(viewerIsu: Int, otherIsu: Int) {
        lockPair(viewerIsu, otherIsu)
        val friendship = friendships.findBetween(viewerIsu, otherIsu) ?: return
        if (friendship.status != Status.PENDING || friendship.addressee.isu != viewerIsu) {
            throw BusinessRuleException("No incoming friend request")
        }
        friendships.delete(friendship)
    }

    @Transactional
    fun cancelRequest(viewerIsu: Int, otherIsu: Int) {
        lockPair(viewerIsu, otherIsu)
        val friendship = friendships.findBetween(viewerIsu, otherIsu) ?: return
        if (friendship.status != Status.PENDING || friendship.requester.isu != viewerIsu) {
            throw BusinessRuleException("No outgoing friend request")
        }
        friendships.delete(friendship)
    }

    @Transactional
    fun removeFriend(viewerIsu: Int, otherIsu: Int) {
        lockPair(viewerIsu, otherIsu)
        val friendship = friendships.findBetween(viewerIsu, otherIsu) ?: return
        if (friendship.status != Status.ACCEPTED) throw BusinessRuleException("Users are not friends")
        friendships.delete(friendship)
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
