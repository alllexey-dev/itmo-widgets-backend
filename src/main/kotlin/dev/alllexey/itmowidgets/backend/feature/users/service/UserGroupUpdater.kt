package dev.alllexey.itmowidgets.backend.feature.users.service

import com.auth0.jwt.interfaces.DecodedJWT
import dev.alllexey.itmowidgets.backend.feature.users.model.User
import dev.alllexey.itmowidgets.backend.feature.users.persistence.GroupRepository
import dev.alllexey.itmowidgets.backend.platform.error.NotFoundException
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.hibernate.exception.LockAcquisitionException
import org.springframework.retry.annotation.Backoff
import org.springframework.retry.annotation.Retryable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** A bean of its own so that `UserService` calls it through the retry and transaction proxy. */
@Service
class UserGroupUpdater(
    private val groupService: GroupService,
    private val entityManager: EntityManager,
    private val groupRepository: GroupRepository,
) {
    @Retryable(
        retryFor = [LockAcquisitionException::class],
        maxAttempts = 5,
        backoff = Backoff(delay = 100)
    )
    @Transactional
    fun updateGroups(user: User, decodedJWT: DecodedJWT) {
        val managedUser = entityManager.find(User::class.java, user.id, LockModeType.PESSIMISTIC_WRITE)
            ?: throw NotFoundException("User not found")
        val ids = groupService.groupIdsByIdToken(decodedJWT)
        val groups = groupRepository.findAllById(ids)
        managedUser.groups.clear()
        managedUser.groups.addAll(groups)
        entityManager.flush()
    }
}
