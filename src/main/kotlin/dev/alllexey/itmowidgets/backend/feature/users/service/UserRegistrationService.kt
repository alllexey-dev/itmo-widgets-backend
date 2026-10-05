package dev.alllexey.itmowidgets.backend.feature.users.service

import dev.alllexey.itmowidgets.backend.feature.users.model.User
import dev.alllexey.itmowidgets.backend.feature.users.persistence.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class UserRegistrationService(private val userRepository: UserRepository) {
    /** The ISU's user id: one read for a registered user; the idempotent registration runs only on a miss. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun resolveIdByIsu(isu: Int): UUID = userRepository.findRegisteredIdByIsu(isu) ?: register(isu)

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun findOrCreateByIsu(isu: Int): User {
        val id = userRepository.findRegisteredIdByIsu(isu) ?: register(isu)
        return userRepository.findById(id).orElseThrow { IllegalStateException("Registered user not found") }
    }

    /** Also completes a user whose settings row is missing. */
    private fun register(isu: Int): UUID {
        userRepository.insertIgnore(UUID.randomUUID(), isu)
        // A competing registration may own this ISU. Resolve its ID before creating settings,
        // without loading the inverse eager relation until the settings row exists.
        val id = checkNotNull(userRepository.findIdByIsu(isu)) { "User registration did not resolve an identity" }
        userRepository.insertSettingsIgnore(id)
        return id
    }
}
