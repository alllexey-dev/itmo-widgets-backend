package dev.alllexey.itmowidgets.backend.services
import dev.alllexey.itmowidgets.backend.model.FriendRequestEntity
import dev.alllexey.itmowidgets.backend.repositories.FriendRequestRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class FriendService(
    private val friendRequestRepository: FriendRequestRepository,
    private val userService: UserService,
) {

    @Transactional
    fun sendRequest(fromIsu: Int, toIsu: Int) {
        require(fromIsu != toIsu)

        val from = userService.findUserByIsu(fromIsu)
        val to = userService.findUserByIsu(toIsu)

        val existing = friendRequestRepository
            .findByFromIsuAndToIsu(fromIsu, toIsu)

        when {
            existing == null -> {
                friendRequestRepository.save(
                    FriendRequestEntity(from = from, to = to)
                )
                // todo: notify
            }

            existing.status == FriendRequestEntity.Status.CANCELLED -> {
                existing.status = FriendRequestEntity.Status.ACTIVE
                existing.lastActivatedAt = Instant.now()
            }

            existing.status == FriendRequestEntity.Status.ACTIVE -> {
                return
            }
        }
    }

    @Transactional
    fun cancelRequest(fromIsu: Int, toIsu: Int) {
        val request = friendRequestRepository
            .findByFromIsuAndToIsu(fromIsu, toIsu)
            ?: return

        request.status = FriendRequestEntity.Status.CANCELLED
    }

    @Transactional
    fun removeRelationship(isu1: Int, isu2: Int) {
        friendRequestRepository
            .findByFromIsuAndToIsu(isu1, isu2)
            ?.status = FriendRequestEntity.Status.CANCELLED

        friendRequestRepository
            .findByFromIsuAndToIsu(isu2, isu1)
            ?.status = FriendRequestEntity.Status.CANCELLED
    }

    @Transactional(readOnly = true)
    fun getFriends(isu: Int): List<Int> {
        return friendRequestRepository.findUserFriendsIsu(isu)
    }

    @Transactional(readOnly = true)
    fun getIncomingRequests(isu: Int): List<Int> {
        return friendRequestRepository.findIncomingRequests(isu)
    }

    @Transactional(readOnly = true)
    fun getOutgoingRequests(isu: Int): List<Int> {
        return friendRequestRepository.findOutgoingRequests(isu)
    }

    @Transactional(readOnly = true)
    fun areFriends(isu1: Int, isu2: Int): Boolean {
        return friendRequestRepository.areFriends(isu1, isu2)
    }
}