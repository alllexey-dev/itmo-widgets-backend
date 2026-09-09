package dev.alllexey.itmowidgets.backend.services

import com.auth0.jwt.interfaces.DecodedJWT
import dev.alllexey.itmowidgets.backend.exceptions.BusinessRuleException
import dev.alllexey.itmowidgets.backend.exceptions.NotFoundException
import dev.alllexey.itmowidgets.backend.dto.UserPrivacySettings
import dev.alllexey.itmowidgets.backend.model.User
import dev.alllexey.itmowidgets.backend.repositories.GroupRepository
import dev.alllexey.itmowidgets.backend.repositories.UserRepository
import dev.alllexey.itmowidgets.backend.services.ItmoJwtVerifier.Companion.getClaimOrNull
import dev.alllexey.itmowidgets.backend.services.ItmoJwtVerifier.Companion.getIsu
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.hibernate.exception.LockAcquisitionException
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Lazy
import org.springframework.retry.annotation.Backoff
import org.springframework.retry.annotation.Retryable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.*

@Service
class UserService(
    private val userRepository: UserRepository,
    private val itmoJwtVerifier: ItmoJwtVerifier,
    private val groupService: GroupService,
    private val entityManager: EntityManager,
    private val groupRepository: GroupRepository,
    private val userRegistrationService: UserRegistrationService,
) {

    @Lazy
    @Autowired
    lateinit var userService: UserService

    fun findOrCreateByIsu(isu: Int): User = userRegistrationService.findOrCreateByIsu(isu)

    fun privacySettings(user: User): UserPrivacySettings = UserPrivacySettings(
        scheduleVisibility = user.settings.scheduleVisibility,
        sportVisibility = user.settings.sportVisibility
    )

    @Transactional
    fun updatePrivacySettings(user: User, privacy: UserPrivacySettings): UserPrivacySettings {
        // Resolve inside this transaction: a caller may pass an entity detached from its read context.
        val managedUser = findUserById(user.id)
        managedUser.settings.apply {
            scheduleVisibility = privacy.scheduleVisibility
            sportVisibility = privacy.sportVisibility
        }
        return privacySettings(managedUser)
    }

    @Transactional
    fun updateDataFromIdToken(user: User, idToken: String) {
        val token = itmoJwtVerifier.verifyAndDecode(idToken)
        val isu = token.getIsu() ?: throw BusinessRuleException("Not found isu in idToken")
        if (user.isu != isu) throw BusinessRuleException("User isu didn't match isu in idToken")
        user.pictureUrl = token.getClaimOrNull("picture")?.asString()
        user.name = token.getClaimOrNull("name")?.asString()
        userService.updateUserGroups(user, token)
    }

    @Retryable(
        retryFor = [LockAcquisitionException::class],
        maxAttempts = 5,
        backoff = Backoff(delay = 100)
    )
    @Transactional
    fun updateUserGroups(user: User, decodedJWT: DecodedJWT) {
        val managedUser = entityManager.find(User::class.java, user.id, LockModeType.PESSIMISTIC_WRITE)
            ?: throw NotFoundException("User not found")
        val ids = groupService.groupIdsByIdToken(decodedJWT)
        val groups = groupRepository.findAllById(ids)
        managedUser.groups.clear()
        managedUser.groups.addAll(groups)
        entityManager.flush()
    }

    fun findUserByIsu(isu: Int): User {
        return userRepository.findByIsu(isu)
            ?: throw NotFoundException("User not found with isu: $isu")
    }

    fun findUserById(id: UUID): User {
        return userRepository.findById(id)
            .orElseThrow { NotFoundException("User not found with ID: $id") }
    }
}
