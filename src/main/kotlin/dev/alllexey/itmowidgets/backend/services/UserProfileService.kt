package dev.alllexey.itmowidgets.backend.services

import dev.alllexey.itmowidgets.backend.dto.UserLookupRequest
import dev.alllexey.itmowidgets.backend.dto.UserLookupResponse
import dev.alllexey.itmowidgets.backend.dto.UserProfile
import dev.alllexey.itmowidgets.backend.exceptions.InvalidRequestDataException
import dev.alllexey.itmowidgets.backend.model.User
import dev.alllexey.itmowidgets.backend.repositories.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class UserProfileService(
    private val users: UserService,
    private val userRepository: UserRepository,
    private val friends: FriendService,
    private val privacy: UserPrivacyService,
) {
    @Transactional(readOnly = true)
    fun profile(viewerId: UUID, isu: Int): UserProfile {
        if (isu <= 0) throw InvalidRequestDataException("ISU must be positive")
        return profileFor(users.findUserById(viewerId), users.findUserByIsu(isu))
    }

    @Transactional(readOnly = true)
    fun lookup(viewerId: UUID, request: UserLookupRequest): UserLookupResponse {
        request.validate()
        return UserLookupResponse(profilesFor(users.findUserById(viewerId), request.isus.distinct()))
    }

    @Transactional(readOnly = true)
    fun friends(viewerId: UUID): List<UserProfile> {
        val viewer = users.findUserById(viewerId)
        return profilesFor(viewer, friends.getFriends(viewer.isu))
    }

    @Transactional(readOnly = true)
    fun incoming(viewerId: UUID): List<UserProfile> {
        val viewer = users.findUserById(viewerId)
        return profilesFor(viewer, friends.getIncomingRequests(viewer.isu))
    }

    @Transactional(readOnly = true)
    fun outgoing(viewerId: UUID): List<UserProfile> {
        val viewer = users.findUserById(viewerId)
        return profilesFor(viewer, friends.getOutgoingRequests(viewer.isu))
    }

    enum class Action { REQUEST, ACCEPT, REJECT, CANCEL, REMOVE }

    /** Mutation and the returned viewer-scoped profile share a transaction and pair locks. */
    @Transactional
    fun act(viewerId: UUID, isu: Int, action: Action): UserProfile {
        val viewer = users.findUserById(viewerId)
        when (action) {
            Action.REQUEST -> friends.sendRequest(viewer.isu, isu)
            Action.ACCEPT -> friends.acceptRequest(viewer.isu, isu)
            Action.REJECT -> friends.rejectRequest(viewer.isu, isu)
            Action.CANCEL -> friends.cancelRequest(viewer.isu, isu)
            Action.REMOVE -> friends.removeFriend(viewer.isu, isu)
        }
        return profileFor(viewer, users.findUserByIsu(isu))
    }

    private fun profilesFor(viewer: User, isus: List<Int>): List<UserProfile> {
        if (isus.isEmpty()) return emptyList()
        val found = userRepository.findAllByIsuIn(isus).associateBy { it.isu }
        return isus.mapNotNull { found[it] }.map { profileFor(viewer, it) }
    }

    private fun profileFor(viewer: User, owner: User) = UserProfile(
        user = privacy.userDataFor(viewer, owner),
        relationship = friends.relationship(viewer.isu, owner.isu),
    )
}
