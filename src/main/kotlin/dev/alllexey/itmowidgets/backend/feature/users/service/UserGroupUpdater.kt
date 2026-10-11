package dev.alllexey.itmowidgets.backend.feature.users.service

import com.auth0.jwt.interfaces.DecodedJWT
import dev.alllexey.itmowidgets.backend.feature.users.model.User
import dev.alllexey.itmowidgets.backend.feature.users.persistence.GroupRepository
import dev.alllexey.itmowidgets.backend.feature.users.persistence.UserRepository
import dev.alllexey.itmowidgets.backend.platform.error.NotFoundException
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** A bean of its own so that `UserService` calls it through the transaction proxy. */
@Service
class UserGroupUpdater(
    private val groupService: GroupService,
    private val entityManager: EntityManager,
    private val groupRepository: GroupRepository,
    private val userRepository: UserRepository,
) {
    @Transactional
    fun updateGroups(user: User, decodedJWT: DecodedJWT) {
        // A native row lock: a locking find joins the eager settings into FOR UPDATE, which Hibernate rejects.
        userRepository.lockById(user.id) ?: throw NotFoundException("User not found")
        val managedUser = entityManager.find(User::class.java, user.id) ?: throw NotFoundException("User not found")
        val ids = groupService.groupIdsByIdToken(decodedJWT) ?: return
        val groups = groupRepository.findAllById(ids)
        managedUser.groups.clear()
        managedUser.groups.addAll(groups)
        entityManager.flush()
    }
}
